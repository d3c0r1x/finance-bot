package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainActivityMutationRecoveryTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<Pair<String?, String?>>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun startServer() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.method to request.path
                val body = when {
                    request.path == "/api/v1/me/tenants" -> tenantsJson()
                    request.path == "/api/v1/tenants/$TENANT_ID/members" -> "[]"
                    request.path?.startsWith("/api/v1/tenants/$TENANT_ID/transactions?") == true ->
                        """{"items":[],"nextCursor":null}"""
                    request.path == "/api/v1/tenants/$TENANT_ID/budgets" -> budgetJson()
                    request.path == "/api/v1/tenants/$TENANT_ID/debts" -> """{"items":[]}"""
                    request.path == "/api/v1/tenants/$TENANT_ID/summary" -> summaryJson()
                    request.path?.startsWith("/api/v1/tenants/$TENANT_ID/reports/period?") == true -> reportJson()
                    request.path == "/api/v1/tenants/$TENANT_ID/profile/me" ->
                        """{"displayName":"Synthetic owner","plannedIncome":null,"onboardingState":"complete","timezone":"UTC","currency":"RUB"}"""
                    request.path == "/api/v1/tenants/$TENANT_ID/notification-preferences" ->
                        """{"timezone":"UTC","telegramLinked":false,"language":"ru","dailyEnabled":false,"dailyLocalTime":"21:00","weeklyEnabled":false,"weeklyDayOfWeek":1,"weeklyLocalTime":"19:00","quietHoursStart":null,"quietHoursEnd":null,"version":0}"""
                    else -> return MockResponse().setResponseCode(404).setBody("{}")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
    }

    @After fun cleanUp() {
        runCatching { scenario?.close() }
        scenario = null
        MainActivity.financeApiFactoryForTesting = null
        MainActivity.mutationCheckpointStorageFactoryForTesting = null
        clearEncryptedFixture(SESSION_PREFERENCES, SESSION_KEY_ALIAS)
        clearEncryptedFixture(MUTATION_PREFERENCES, MUTATION_KEY_ALIAS)
        server.shutdown()
    }

    @Test fun authenticatedLaunchRestoresOnlyMatchingActorAndTenantWithoutPostingMutation() {
        val matching = checkpoint(ACTOR_ID, TENANT_ID, "matching-retry")
        val wrongActor = checkpoint(OTHER_ACTOR_ID, TENANT_ID, "wrong-actor")
        val wrongTenant = checkpoint(ACTOR_ID, OTHER_TENANT_ID, "wrong-tenant")

        launchAuthenticated(listOf(matching, wrongActor, wrongTenant), ACTOR_ID)
        val state = awaitWorkspace()

        assertTrue(state.authenticated)
        assertEquals(ACTOR_ID, state.tenants.single().userId)
        assertEquals(TENANT_ID, state.tenants.single().id)
        assertEquals(listOf(matching.copy(sessionGeneration = 0L)), state.pendingMutationCheckpoints)
        assertFalse(state.mutationCheckpointError)
        assertNoStartupMutationPosts()
    }

    @Test fun authenticatedLaunchDoesNotExposeCheckpointForDifferentCoreOwner() {
        val checkpoint = checkpoint(ACTOR_ID, TENANT_ID, "other-owner-retry")

        launchAuthenticated(listOf(checkpoint), OTHER_ACTOR_ID)
        val state = awaitWorkspace()

        assertTrue(state.authenticated)
        assertEquals(OTHER_ACTOR_ID, state.tenants.single().userId)
        assertTrue(state.pendingMutationCheckpoints.isEmpty())
        assertFalse(state.mutationCheckpointError)
        assertNoStartupMutationPosts()
    }

    private fun launchAuthenticated(checkpoints: List<MutationCheckpoint>, coreActorId: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tokens = TokenVault.isolatedForTesting(context, SESSION_PREFERENCES, SESSION_KEY_ALIAS).apply {
            save(org.json.JSONObject().put("accessToken", "synthetic-main-activity-token")
                .put("refreshToken", "synthetic-main-activity-refresh"))
        }
        AndroidEncryptedMutationCheckpointStorage(context, MUTATION_PREFERENCES, MUTATION_KEY_ALIAS)
            .write(checkpoints)
        val baseUrl = server.url("/").toString().removeSuffix("/")
        MainActivity.financeApiFactoryForTesting = { activityContext ->
            FinanceApi(activityContext, FinanceApiEndpoints(baseUrl, baseUrl), OkHttpClient(), tokens)
        }
        MainActivity.mutationCheckpointStorageFactoryForTesting = { activityContext ->
            AndroidEncryptedMutationCheckpointStorage(activityContext, MUTATION_PREFERENCES, MUTATION_KEY_ALIAS)
        }
        coreUserId = coreActorId
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun awaitWorkspace(): FinanceUiState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var latest = FinanceUiState()
        while (System.nanoTime() < deadline) {
            scenario?.onActivity { activity -> latest = activity.mutationRecoveryUiForTesting() }
            if (latest.authenticated && !latest.busy && latest.tenants.isNotEmpty()) return latest
            Thread.sleep(25)
        }
        throw AssertionError("Authenticated workspace did not finish loading: $latest")
    }

    private fun assertNoStartupMutationPosts() {
        assertTrue("Startup must not POST a repeat or void: $requests",
            requests.none { (method, path) -> method == "POST" && path?.contains("/transactions") == true })
        assertTrue("Workspace restore should use GET only: $requests", requests.all { it.first == "GET" })
    }

    private fun checkpoint(actor: String, tenant: String, suffix: String) = MutationCheckpoint(
        action = MutationAction.REPEAT,
        actorUserId = actor,
        tenantId = tenant,
        original = FinanceTransaction(
            id = "$CHECKPOINT_TRANSACTION_PREFIX-$suffix",
            tenantId = tenant,
            type = "expense",
            amount = "12.34",
            currency = "RUB",
            categoryCode = "food",
            subcategoryCode = "groceries",
            description = "Synthetic $suffix",
            source = "manual",
            occurredAt = "2026-10-11T10:00:00Z",
            accountId = null,
            status = "posted",
            version = 2,
            createdAt = "2026-10-11T10:00:00Z",
            memberName = "Synthetic owner",
            debtId = null,
            ownerUserId = actor,
        ),
        occurredAt = "2026-10-12T10:00:00Z",
        idempotencyKey = "synthetic-retry-key-$suffix",
        sessionGeneration = 99L,
    )

    private fun tenantsJson() =
        """[{"tenantId":"$TENANT_ID","userId":"$coreUserId","displayName":"Synthetic","role":"owner","timezone":"UTC"}]"""

    private fun budgetJson() = """
        {"month":"2026-10","familyLimits":{},"personalOverrides":{},"effectiveLimits":{},"monthlySpent":{},
         "limitStatus":{},"familyVersions":{},"personalVersions":{},"familyTotalLimit":"0.00",
         "personalTotalOverride":null,"effectiveTotalLimit":"0.00","totalMonthlySpent":"0.00",
         "totalLimitStatus":"normal","familyTotalVersion":0,"personalTotalVersion":0,
         "rolling7FoodLimit":"0.00","personalRolling7FoodOverride":null,"effectiveRolling7FoodLimit":"0.00",
         "rolling7FoodSpent":"0.00","rolling7FoodLimitStatus":"disabled","familyRolling7FoodVersion":0,
         "personalRolling7FoodVersion":0,"rolling7FoodStatus":{"fromDate":"2026-10-06","toDate":"2026-10-12",
         "limit":"0.00","spent":"0.00","remaining":null,"limitStatus":"disabled","usualWeeklySpend":null,
         "historyWeeks":0,"paceStatus":"insufficient_history","paceShare":null}}
    """.trimIndent()

    private fun summaryJson() = """
        {"month":"2026-10","incomeTotal":"0.00","expenseTotal":"0.00","transactionCount":0,
         "asOfDate":"2026-10-12","daysElapsed":12,"daysInMonth":31,"daysRemaining":19,
         "dailyExpensePace":null,"projectedExpenseTotal":null,
         "rolling7FoodStatus":{"fromDate":"2026-10-06","toDate":"2026-10-12","limit":"0.00",
         "spent":"0.00","remaining":null,"limitStatus":"disabled","usualWeeklySpend":null,
         "historyWeeks":0,"paceStatus":"insufficient_history","paceShare":null}}
    """.trimIndent()

    private fun reportJson() = """
        {"period":"month","scope":"personal","fromDate":"2026-10-01","toDate":"2026-10-12",
         "asOfDate":"2026-10-12","timezone":"UTC","currency":"RUB","incomeTotal":"0.00",
         "expenseTotal":"0.00","debtPaymentTotal":"0.00","refundTotal":"0.00","transactionCount":0,
         "expenseByCategory":{},"expenseByDay":{},"weekendSharePercent":null,"monthlyBudgetLimit":null,
         "monthlyBudgetRemaining":null,"rolling7FoodStatus":{"fromDate":"2026-10-06","toDate":"2026-10-12",
         "limit":"0.00","spent":"0.00","remaining":null,"limitStatus":"disabled","usualWeeklySpend":null,
         "historyWeeks":0,"paceStatus":"insufficient_history","paceShare":null},
         "waste":{"available":false,"reasonCode":"missing_amounts","completeness":"partial","reviewedSpend":null,
         "optionalSpend":null,"optionalShare":null,"reviewedItemCount":0,"optionalItemCount":0,"missingAmountCount":0,
         "bySource":{},"topItems":[],"corrected":[],"optionalByDay":{}}}
    """.trimIndent()

    private fun clearEncryptedFixture(preferencesName: String, keyAlias: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit())
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(keyAlias)) deleteEntry(keyAlias)
        }
    }

    private var coreUserId = ACTOR_ID

    private companion object {
        const val TENANT_ID = "7ed3e606-0000-4000-8000-000000000001"
        const val OTHER_TENANT_ID = "7ed3e606-0000-4000-8000-000000000003"
        const val ACTOR_ID = "7ed3e606-0000-4000-8000-000000000002"
        const val OTHER_ACTOR_ID = "7ed3e606-0000-4000-8000-000000000004"
        const val CHECKPOINT_TRANSACTION_PREFIX = "7ed3e606-0000-4000-8000-000000000010"
        const val SESSION_PREFERENCES = "finance_session_main_activity_recovery_test"
        const val SESSION_KEY_ALIAS = "finance-session-main-activity-recovery-test-v1"
        const val MUTATION_PREFERENCES = "finance_mutation_main_activity_recovery_test"
        const val MUTATION_KEY_ALIAS = "finance-mutation-main-activity-recovery-test-v1"
    }
}
