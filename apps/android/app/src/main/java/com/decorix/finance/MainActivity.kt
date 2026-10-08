package com.decorix.finance

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.connectivity.DefaultConnectionBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.security.MessageDigest
import java.time.YearMonth
import java.time.ZoneId
import java.time.LocalDate
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var authService: AuthorizationService
    private val api by lazy { FinanceApi(this) }
    private val executor = Executors.newSingleThreadExecutor()
    private var ui by mutableStateOf(FinanceUiState())
    private var language by mutableStateOf("ru")
    private var pendingTransactionEdit: FinanceTransactionEdit? = null
    private var pendingTransactionEditKey: String? = null
    private val loginResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val response = net.openid.appauth.AuthorizationResponse.fromIntent(result.data ?: Intent())
        val error = net.openid.appauth.AuthorizationException.fromIntent(result.data ?: Intent())
        if (error != null) ui = ui.copy(error = error.errorDescription ?: "Sign in cancelled")
        else if (response != null) exchangeCode(response)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val connectionBuilder = if (BuildConfig.DEBUG) DevelopmentConnectionBuilder
            else DefaultConnectionBuilder.INSTANCE
        authService = AuthorizationService(this,
            AppAuthConfiguration.Builder().setConnectionBuilder(connectionBuilder).build())
        if (api.hasSession()) {
            ui = ui.copy(authenticated = true)
            loadTenants()
        }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    FinanceScreen(ui, language, onLanguage = { language = it }, onLogin = ::startLogin,
                        onRefresh = ::loadTenants, onCreate = ::createTenantAndRefresh,
                        onProfileSave = ::saveMemberProfile, onCreateTelegramLink = ::createTelegramLinkCode,
                        onNotificationPreferencesSave = ::saveNotificationPreferences,
                        onCreateDraft = ::createTransactionDraft, onUpdateDraft = ::updateTransactionDraft,
                        onConfirmDraft = ::confirmTransactionDraft, onCancelDraft = ::cancelTransactionDraft,
                        onLogout = ::logout, onBudgetUpdate = ::updateBudget, onBudgetReset = ::resetPersonalBudgets,
                        onBudgetProposal = ::createBudgetProposal, onBudgetApply = ::applyBudgetProposal,
                        onDebtCreate = ::createDebt, onDebtPay = ::payDebt, onDebtAdjust = ::adjustDebt,
                        onDebtForecast = ::loadDebtForecast, onReportLoad = ::loadReport,
                        onShoppingLoad = ::loadShoppingCandidates, onShoppingDecision = ::applyShoppingDecision,
                        onShoppingCopy = ::copyShoppingList, onPersonalInflationLoad = ::loadPersonalInflation,
                        onDoNotBuyLoad = ::loadDoNotBuy, onDoNotBuyDecision = ::applyDoNotBuyDecision,
                        onRecurringLoad = ::loadRecurring, onRecurringDecision = ::applyRecurringDecision,
                        onRepeatTransaction = ::repeatTransaction, onVoidTransaction = ::voidTransaction,
                        onTransactionFilter = ::filterTransactions, onTransactionLoadMore = ::loadMoreTransactions,
                        onUpdateTransaction = ::updateTransaction)
                }
            }
        }
    }

    private fun startLogin() {
        executor.execute {
            runCatching {
                val discovery = JSONObject(OkHttpClient().newCall(Request.Builder()
                    .url("${BuildConfig.OIDC_REALM_URL}/.well-known/openid-configuration").build()).execute().use {
                    if (!it.isSuccessful) error("Identity provider unavailable")
                    it.body?.string() ?: error("Identity metadata is empty")
                })
                fun endpoint(name: String): Uri = Uri.parse(discovery.getString(name))
                val config = AuthorizationServiceConfiguration(endpoint("authorization_endpoint"), endpoint("token_endpoint"),
                    endpoint("revocation_endpoint"), endpoint("end_session_endpoint"))
                val redirect = Uri.parse("com.decorix.finance:/oauth2redirect")
                val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
                val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
                AuthorizationRequest.Builder(config, BuildConfig.OIDC_CLIENT_ID, ResponseTypeValues.CODE, redirect)
                    .setScopes("openid")
                    .setCodeVerifier(verifier, challenge, "S256").build()
            }.onSuccess { request -> runOnUiThread { loginResult.launch(authService.getAuthorizationRequestIntent(request)) } }
                .onFailure { error -> runOnUiThread { ui = ui.copy(error = error.message ?: "Identity provider unavailable") } }
        }
    }

    private fun exchangeCode(response: net.openid.appauth.AuthorizationResponse) {
        val request = response.createTokenExchangeRequest()
        authService.performTokenRequest(request) { tokenResponse, exception ->
            if (exception != null || tokenResponse == null) {
                ui = ui.copy(error = exception?.errorDescription ?: "Sign in failed")
            } else {
                api.saveTokens(tokenResponse.accessToken.orEmpty(), tokenResponse.refreshToken.orEmpty(),
                    tokenResponse.accessTokenExpirationTime ?: 0L, tokenResponse.idToken.orEmpty())
                ui = ui.copy(authenticated = true)
                loadTenants()
            }
        }
    }

    private fun loadTenants() = runApi { workspaceData() }
    private fun createTenantAndRefresh(name: String, memberName: String, plannedIncome: String?) = runApi {
        api.createTenant(name, memberName, plannedIncome)
        workspaceData()
    }
    private fun saveMemberProfile(name: String, plannedIncome: String?) = runApi {
        val profile = api.updateMemberProfile(activeTenantId(), name, plannedIncome)
        workspaceData(memberProfile = profile)
    }
    private fun saveNotificationPreferences(preferences: FinanceNotificationPreferences) = runApi {
        val updated = api.updateNotificationPreferences(activeTenantId(), preferences)
        workspaceData(notificationPreferences = updated)
    }
    private fun createTelegramLinkCode() {
        ui = ui.copy(busy = true, error = null, telegramLinkCode = null)
        executor.execute {
            runCatching { api.createTelegramLinkCode() }
                .onSuccess { code -> ui = ui.copy(busy = false, telegramLinkCode = code) }
                .onFailure { error -> ui = ui.copy(busy = false, error = error.message ?: "Request failed") }
        }
    }
    private fun createTransactionDraft(text: String, idempotencyKey: String) = runApi {
        val tenantId = activeTenantId()
        workspaceData(draft = api.createTransactionDraft(tenantId, text, idempotencyKey))
    }

    private fun updateTransactionDraft(edit: TransactionDraftEdit) = runApi {
        val updated = api.updateTransactionDraft(activeTenantId(), edit)
        workspaceData(draft = updated)
    }

    private fun confirmTransactionDraft(id: String, version: Long) = runApi {
        val draft = ui.transactionDraft?.takeIf { it.id == id && it.version == version }
            ?: error("Draft changed. Refresh and review it again.")
        val alerts = api.confirmTransactionDraft(activeTenantId(), draft)
        workspaceData(draft = null, budgetAlerts = alerts)
    }

    private fun cancelTransactionDraft(id: String, version: Long) = runApi {
        api.cancelTransactionDraft(activeTenantId(), id, version)
        workspaceData(draft = null)
    }

    private fun repeatTransaction(transaction: FinanceTransaction) = runApi {
        val tenant = ui.tenants.firstOrNull() ?: error("Create a workspace first")
        val zone = ZoneId.of(tenant.timezone)
        val occurredAt = LocalDate.now(zone).atTime(12, 0).atZone(zone).toInstant().toString()
        api.repeatTransaction(tenant.id, transaction, occurredAt)
        workspaceData()
    }

    private fun voidTransaction(transaction: FinanceTransaction) = runApi {
        api.voidTransaction(activeTenantId(), transaction)
        workspaceData()
    }

    private fun updateTransaction(edit: FinanceTransactionEdit) = runApi {
        if (pendingTransactionEdit != edit || pendingTransactionEditKey == null) {
            pendingTransactionEdit = edit
            pendingTransactionEditKey = java.util.UUID.randomUUID().toString()
        }
        api.updateTransaction(activeTenantId(), edit, pendingTransactionEditKey!!)
        workspaceData(transactionEditSavedToken = java.util.UUID.randomUUID().toString())
    }

    private fun filterTransactions(search: String, type: String, from: String, to: String, memberId: String) = runApi {
        workspaceData(transactionSearch = search, transactionType = type,
            transactionFrom = from, transactionTo = to, transactionMemberId = memberId)
    }

    private fun loadMoreTransactions() = runApi {
        val cursor = ui.transactionNextCursor ?: return@runApi workspaceData()
        workspaceData(transactionCursor = cursor, appendTransactions = true)
    }

    private fun workspaceData(proposal: BudgetProposal? = ui.budgetProposal,
                              draft: FinanceTransactionDraft? = ui.transactionDraft,
                              memberProfile: FinanceMemberProfile? = null,
                              notificationPreferences: FinanceNotificationPreferences? = null,
                              budgetAlerts: List<FinanceBudgetAlert> = emptyList(),
                              transactionSearch: String = ui.transactionSearch,
                              transactionType: String = ui.transactionTypeFilter,
                              transactionFrom: String = ui.transactionFrom,
                              transactionTo: String = ui.transactionTo,
                              transactionMemberId: String = ui.transactionMemberId,
                              transactionCursor: String? = null,
                              appendTransactions: Boolean = false,
                              transactionEditSavedToken: String? = null): FinanceWorkspaceSnapshot {
        val tenants = api.tenants()
        val tenant = tenants.firstOrNull()
        return if (tenant == null) FinanceWorkspaceSnapshot(tenants, emptyList(), emptyList(), null, transactionSearch,
            transactionType, transactionFrom, transactionTo, transactionMemberId, null, emptyList(), proposal,
            null, null, draft, null, null, budgetAlerts, transactionEditSavedToken)
        else {
            val month = YearMonth.now(ZoneId.of(tenant.timezone)).toString()
            val members = api.members(tenant.id)
            val transactionPage = api.transactions(tenant.id, transactionSearch, transactionType, transactionCursor,
                transactionFrom.trim().ifBlank { null }, transactionTo.trim().ifBlank { null },
                transactionMemberId.trim().ifBlank { null })
            val transactions = (if (appendTransactions) ui.transactions else emptyList()) + transactionPage.items
            FinanceWorkspaceSnapshot(tenants, transactions, members, transactionPage.nextCursor, transactionSearch, transactionType,
                transactionFrom, transactionTo, transactionMemberId,
                api.budgets(tenant.id), api.debts(tenant.id),
                proposal, api.dashboardSummary(tenant.id), api.report(tenant.id, "month", "personal", month, "", ""),
                draft, memberProfile ?: api.memberProfile(tenant.id),
                notificationPreferences ?: api.notificationPreferences(tenant.id), budgetAlerts,
                transactionEditSavedToken)
        }
    }

    private fun runApi(action: () -> FinanceWorkspaceSnapshot) {
        ui = ui.copy(busy = true, error = null)
        executor.execute {
            runCatching(action).onSuccess { snapshot ->
                if (snapshot.transactionEditSavedToken != null) {
                    pendingTransactionEdit = null
                    pendingTransactionEditKey = null
                }
                val sameTenant = ui.tenants.firstOrNull()?.id == snapshot.tenants.firstOrNull()?.id
                ui = ui.copy(busy = false, tenants = snapshot.tenants,
                    transactions = snapshot.transactions, transactionNextCursor = snapshot.transactionNextCursor,
                    transactionMembers = snapshot.transactionMembers,
                    transactionSearch = snapshot.transactionSearch, transactionTypeFilter = snapshot.transactionTypeFilter,
                    transactionFrom = snapshot.transactionFrom, transactionTo = snapshot.transactionTo,
                    transactionMemberId = snapshot.transactionMemberId,
                    budgets = snapshot.budgets, debts = snapshot.debts,
                    budgetProposal = snapshot.proposal, dashboardSummary = snapshot.dashboardSummary,
                    report = snapshot.report, transactionDraft = snapshot.transactionDraft,
                    memberProfile = snapshot.memberProfile, notificationPreferences = snapshot.notificationPreferences,
                    budgetAlerts = snapshot.budgetAlerts, transactionEditSavedToken = snapshot.transactionEditSavedToken,
                    error = null,
                    doNotBuy = ui.doNotBuy.takeIf { sameTenant },
                    productDecisions = ui.productDecisions.takeIf { sameTenant },
                    doNotBuyError = ui.doNotBuyError.takeIf { sameTenant },
                    doNotBuyLoading = ui.doNotBuyLoading && sameTenant)
            }
                .onFailure { error ->
                    if (error is ApiFailure && error.status == 401) ui = FinanceUiState(error = "Sign in again")
                    else ui = ui.copy(busy = false, error = apiErrorMessage(error))
                }
        }
    }

    private fun apiErrorMessage(error: Throwable): String {
        return when ((error as? ApiFailure)?.status) {
            412 -> if (language == "ru") "Операция уже изменилась. Обновите список и откройте её снова."
                else "This transaction changed. Refresh the list and open it again."
            409 -> if (language == "ru") "Не удалось повторить изменение. Обновите список и попробуйте снова."
                else "The update could not be retried. Refresh the list and try again."
            403 -> if (language == "ru") "Нет прав на изменение этой операции."
                else "You cannot edit this transaction."
            else -> error.message ?: "Request failed"
        }
    }

    private fun activeTenantId(): String = ui.tenants.firstOrNull()?.id ?: error("Create a workspace first")

    private fun updateBudget(key: String, scope: String, amount: String, period: String, version: Long) = runApi {
        val tenantId = activeTenantId()
        api.updateBudget(tenantId, key, scope, amount, period, version)
        workspaceData(proposal = null)
    }

    private fun resetPersonalBudgets() = runApi {
        api.resetPersonalBudgets(activeTenantId())
        workspaceData(proposal = null)
    }

    private fun createBudgetProposal(monthlyIncome: String?) = runApi {
        val tenantId = activeTenantId()
        val proposal = if (monthlyIncome == null) api.proposeBudgetFromHistory(tenantId)
        else api.proposeBudget(tenantId, monthlyIncome)
        workspaceData(proposal)
    }

    private fun applyBudgetProposal(proposalId: String) = runApi {
        api.applyBudgetProposal(activeTenantId(), proposalId)
        workspaceData(proposal = null)
    }

    private fun createDebt(name: String, openingBalance: String, interestRate: String?, minimumPayment: String) = runApi {
        val tenantId = activeTenantId()
        api.createDebt(tenantId, name, openingBalance, interestRate, minimumPayment)
        workspaceData()
    }

    private fun payDebt(debtId: String, amount: String, version: Long) = runApi {
        val tenantId = activeTenantId()
        api.payDebt(tenantId, debtId, amount, version)
        workspaceData()
    }

    private fun adjustDebt(debtId: String, balance: String, version: Long) = runApi {
        val tenantId = activeTenantId()
        api.adjustDebtBalance(tenantId, debtId, balance, version)
        workspaceData()
    }

    private fun loadDebtForecast(debtId: String) {
        val tenantId = activeTenantId()
        ui = ui.copy(busy = true, error = null)
        executor.execute {
            runCatching { api.debtForecast(tenantId, debtId) }
                .onSuccess { forecast -> ui = ui.copy(busy = false, debtForecasts = ui.debtForecasts + (debtId to forecast)) }
                .onFailure { error -> ui = ui.copy(busy = false, error = error.message ?: "Request failed") }
        }
    }

    private fun loadReport(period: String, scope: String, month: String, from: String, to: String) {
        val tenantId = activeTenantId()
        ui = ui.copy(busy = true, error = null)
        executor.execute {
            runCatching { api.report(tenantId, period, scope, month, from, to) }
                .onSuccess { report -> ui = ui.copy(busy = false, report = report) }
                .onFailure { error ->
                    if (error is ApiFailure && error.status == 401) ui = FinanceUiState(error = "Sign in again")
                    else ui = ui.copy(busy = false, error = error.message ?: "Request failed")
                }
        }
    }

    private fun loadShoppingCandidates() {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        ui = ui.copy(shoppingLoading = true, shoppingError = null)
        executor.execute {
            runCatching { api.shoppingCandidates(tenantId) }
                .onSuccess { shopping ->
                    if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(shoppingLoading = false, shoppingList = shopping, shoppingError = null)
                    }
                }
                .onFailure { error ->
                    if (error is ApiFailure && error.status == 401) ui = FinanceUiState(error = "Sign in again")
                    else if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(shoppingLoading = false, shoppingError = error.message ?: "Request failed")
                    }
                }
        }
    }

    private fun loadDoNotBuy() {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        ui = ui.copy(doNotBuyLoading = true, doNotBuyError = null)
        executor.execute {
            runCatching { api.doNotBuy(tenantId) to api.productDecisions(tenantId) }
                .onSuccess { (report, decisions) ->
                    if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(doNotBuyLoading = false, doNotBuy = report,
                            productDecisions = decisions, doNotBuyError = null)
                    }
                }
                .onFailure { error ->
                    if (error is ApiFailure && error.status == 401) ui = FinanceUiState(error = "Sign in again")
                    else if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(doNotBuyLoading = false, doNotBuyError = error.message ?: "Request failed")
                    }
                }
        }
    }

    private fun applyDoNotBuyDecision(productKey: String, action: String) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        ui = ui.copy(doNotBuyLoading = true, doNotBuyError = null)
        executor.execute {
            runCatching {
                api.decideDoNotBuy(tenantId, productKey, action)
                api.doNotBuy(tenantId) to api.productDecisions(tenantId)
            }.onSuccess { (report, decisions) ->
                if (ui.tenants.firstOrNull()?.id == tenantId) {
                    ui = ui.copy(doNotBuyLoading = false, doNotBuy = report,
                        productDecisions = decisions, doNotBuyError = null)
                    if (ui.shoppingList != null) runCatching { api.shoppingCandidates(tenantId) }
                        .onSuccess { shopping ->
                            if (ui.tenants.firstOrNull()?.id == tenantId) ui = ui.copy(shoppingList = shopping)
                        }
                }
            }.onFailure { error ->
                if (error is ApiFailure && error.status == 401) ui = FinanceUiState(error = "Sign in again")
                else if (ui.tenants.firstOrNull()?.id == tenantId) {
                    ui = ui.copy(doNotBuyLoading = false, doNotBuyError = error.message ?: "Request failed")
                }
            }
        }
    }

    private fun loadPersonalInflation() {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        ui = ui.copy(personalInflationLoading = true, personalInflationError = null)
        executor.execute {
            runCatching { api.personalInflation(tenantId) }
                .onSuccess { inflation ->
                    if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(personalInflationLoading = false, personalInflation = inflation,
                            personalInflationError = null)
                    }
                }
                .onFailure { error ->
                    if (error is ApiFailure && error.status == 401) ui = FinanceUiState(error = "Sign in again")
                    else if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(personalInflationLoading = false,
                            personalInflationError = error.message ?: "Request failed")
                    }
                }
        }
    }

    private fun loadRecurring() {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        ui = ui.copy(recurringLoading = true, recurringError = null)
        executor.execute {
            runCatching { api.recurringProjection(tenantId) }
                .onSuccess { projection ->
                    if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(recurringLoading = false, recurringProjection = projection, recurringError = null)
                    }
                }
                .onFailure { error ->
                    if (error is ApiFailure && error.status == 401) ui = FinanceUiState(error = "Sign in again")
                    else if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(recurringLoading = false, recurringError = error.message ?: "Request failed")
                    }
                }
        }
    }

    private fun applyRecurringDecision(seriesId: String, muted: Boolean) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        ui = ui.copy(recurringLoading = true, recurringError = null)
        executor.execute {
            runCatching {
                if (muted) api.muteRecurringSeries(tenantId, seriesId)
                else api.unmuteRecurringSeries(tenantId, seriesId)
            }.onSuccess { projection ->
                if (ui.tenants.firstOrNull()?.id == tenantId) {
                    ui = ui.copy(recurringLoading = false, recurringProjection = projection, recurringError = null)
                }
            }.onFailure { error ->
                if (error is ApiFailure && error.status == 401) ui = FinanceUiState(error = "Sign in again")
                else if (ui.tenants.firstOrNull()?.id == tenantId) {
                    ui = ui.copy(recurringLoading = false, recurringError = error.message ?: "Request failed")
                }
            }
        }
    }

    private fun applyShoppingDecision(productKey: String, action: String) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        ui = ui.copy(shoppingLoading = true, shoppingError = null)
        executor.execute {
            runCatching {
                when (action) {
                    "bought" -> api.markShoppingBought(tenantId, productKey)
                    "mute" -> api.muteShoppingSuggestion(tenantId, productKey)
                    "unmute" -> api.unmuteShoppingSuggestion(tenantId, productKey)
                    else -> error("Unknown shopping decision")
                }
            }.onSuccess { shopping ->
                if (ui.tenants.firstOrNull()?.id == tenantId) {
                    ui = ui.copy(shoppingLoading = false, shoppingList = shopping, shoppingError = null)
                }
            }.onFailure { error ->
                if (error is ApiFailure && error.status == 401) ui = FinanceUiState(error = "Sign in again")
                else if (ui.tenants.firstOrNull()?.id == tenantId) {
                    ui = ui.copy(shoppingLoading = false, shoppingError = error.message ?: "Request failed")
                }
            }
        }
    }

    private fun copyShoppingList(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Finance shopping list", text))
        Toast.makeText(this, if (language == "ru") "Список скопирован" else "List copied", Toast.LENGTH_SHORT).show()
    }

    private fun logout() {
        ui = ui.copy(busy = true, error = null)
        executor.execute {
            runCatching { api.logout() }
            runOnUiThread { ui = FinanceUiState() }
        }
    }

    override fun onDestroy() {
        authService.dispose()
        executor.shutdownNow()
        super.onDestroy()
    }
}

data class FinanceUiState(
    val authenticated: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val tenants: List<FinanceTenant> = emptyList(),
    val transactions: List<FinanceTransaction> = emptyList(),
    val transactionMembers: List<FinanceTenantMember> = emptyList(),
    val transactionNextCursor: String? = null,
    val transactionSearch: String = "",
    val transactionTypeFilter: String = "all",
    val transactionFrom: String = "",
    val transactionTo: String = "",
    val transactionMemberId: String = "",
    val budgets: BudgetOverview? = null,
    val debts: List<FinanceDebt> = emptyList(),
    val budgetProposal: BudgetProposal? = null,
    val debtForecasts: Map<String, DebtForecast> = emptyMap(),
    val dashboardSummary: DashboardSummary? = null,
    val report: FinanceReport? = null,
    val transactionDraft: FinanceTransactionDraft? = null,
    val memberProfile: FinanceMemberProfile? = null,
    val telegramLinkCode: FinanceTelegramLinkCode? = null,
    val notificationPreferences: FinanceNotificationPreferences? = null,
    val budgetAlerts: List<FinanceBudgetAlert> = emptyList(),
    val shoppingList: FinanceShoppingList? = null,
    val shoppingLoading: Boolean = false,
    val shoppingError: String? = null,
    val doNotBuy: FinanceDoNotBuy? = null,
    val productDecisions: FinanceProductDecisions? = null,
    val doNotBuyLoading: Boolean = false,
    val doNotBuyError: String? = null,
    val personalInflation: FinancePersonalInflation? = null,
    val personalInflationLoading: Boolean = false,
    val personalInflationError: String? = null,
    val recurringProjection: FinanceRecurringProjection? = null,
    val recurringLoading: Boolean = false,
    val recurringError: String? = null,
    val transactionEditSavedToken: String? = null,
)

private data class FinanceWorkspaceSnapshot(
    val tenants: List<FinanceTenant>,
    val transactions: List<FinanceTransaction>,
    val transactionMembers: List<FinanceTenantMember>,
    val transactionNextCursor: String?,
    val transactionSearch: String,
    val transactionTypeFilter: String,
    val transactionFrom: String,
    val transactionTo: String,
    val transactionMemberId: String,
    val budgets: BudgetOverview?,
    val debts: List<FinanceDebt>,
    val proposal: BudgetProposal?,
    val dashboardSummary: DashboardSummary?,
    val report: FinanceReport?,
    val transactionDraft: FinanceTransactionDraft?,
    val memberProfile: FinanceMemberProfile?,
    val notificationPreferences: FinanceNotificationPreferences?,
    val budgetAlerts: List<FinanceBudgetAlert>,
    val transactionEditSavedToken: String? = null,
)

data class FinanceTenant(val id: String, val name: String, val role: String, val timezone: String)

@androidx.compose.runtime.Composable
internal fun FinanceScreen(state: FinanceUiState, language: String, onLanguage: (String) -> Unit,
                          onLogin: () -> Unit, onRefresh: () -> Unit,
                          onCreate: (String, String, String?) -> Unit, onProfileSave: (String, String?) -> Unit,
                          onCreateDraft: (String, String) -> Unit,
                          onUpdateDraft: (TransactionDraftEdit) -> Unit,
                          onConfirmDraft: (String, Long) -> Unit,
                          onCancelDraft: (String, Long) -> Unit,
                          onLogout: () -> Unit,
                          onBudgetUpdate: (String, String, String, String, Long) -> Unit,
                          onBudgetReset: () -> Unit, onBudgetProposal: (String?) -> Unit,
                          onBudgetApply: (String) -> Unit,
                          onDebtCreate: (String, String, String?, String) -> Unit,
                          onDebtPay: (String, String, Long) -> Unit,
                          onDebtAdjust: (String, String, Long) -> Unit,
                          onDebtForecast: (String) -> Unit,
                          onReportLoad: (String, String, String, String, String) -> Unit,
                          onCreateTelegramLink: () -> Unit = {},
                          onNotificationPreferencesSave: (FinanceNotificationPreferences) -> Unit = {},
                          onShoppingLoad: () -> Unit = {},
                          onShoppingDecision: (String, String) -> Unit = { _, _ -> },
                          onShoppingCopy: (String) -> Unit = {},
                          onDoNotBuyLoad: () -> Unit = {},
                          onDoNotBuyDecision: (String, String) -> Unit = { _, _ -> },
                          onPersonalInflationLoad: () -> Unit = {},
                          onRecurringLoad: () -> Unit = {},
                          onRecurringDecision: (String, Boolean) -> Unit = { _, _ -> },
                          onRepeatTransaction: (FinanceTransaction) -> Unit = {},
                          onVoidTransaction: (FinanceTransaction) -> Unit = {},
                          onTransactionFilter: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
                          onTransactionLoadMore: () -> Unit = {},
                          onUpdateTransaction: (FinanceTransactionEdit) -> Unit = {}) {
    val russian = language == "ru"
    var workspace by androidx.compose.runtime.remember { mutableStateOf("") }
    var memberName by androidx.compose.runtime.remember { mutableStateOf("") }
    var plannedIncome by androidx.compose.runtime.remember { mutableStateOf("") }
    var transactionText by androidx.compose.runtime.remember { mutableStateOf("") }
    var transactionSearch by androidx.compose.runtime.remember(state.transactionSearch) { mutableStateOf(state.transactionSearch) }
    var transactionTypeFilter by androidx.compose.runtime.remember(state.transactionTypeFilter) {
        mutableStateOf(state.transactionTypeFilter)
    }
    var transactionFrom by androidx.compose.runtime.remember(state.transactionFrom) { mutableStateOf(state.transactionFrom) }
    var transactionTo by androidx.compose.runtime.remember(state.transactionTo) { mutableStateOf(state.transactionTo) }
    var transactionMemberId by androidx.compose.runtime.remember(state.transactionMemberId) {
        mutableStateOf(state.transactionMemberId)
    }
    var transactionMemberPickerExpanded by androidx.compose.runtime.remember { mutableStateOf(false) }
    var editingTransaction by androidx.compose.runtime.remember { mutableStateOf<FinanceTransaction?>(null) }
    var validateTransactionDates by androidx.compose.runtime.remember { mutableStateOf(false) }
    val transactionDateError = if (validateTransactionDates)
        transactionDateValidationError(transactionFrom, transactionTo, russian) else null
    var draftCreateKey by androidx.compose.runtime.remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var activeScreen by androidx.compose.runtime.remember { mutableStateOf("overview") }
    val canWriteTransactions = state.tenants.firstOrNull()?.role != "viewer"
    val role = state.tenants.firstOrNull()?.role
    val canManageFamilyTransactions = role == "owner" || role == "admin"
    val currentUserId = state.transactionMembers.singleOrNull()?.userId
    LaunchedEffect(state.transactionEditSavedToken) {
        if (state.transactionEditSavedToken != null) editingTransaction = null
    }
    var previousDraftId by androidx.compose.runtime.remember { mutableStateOf(state.transactionDraft?.id) }
    LaunchedEffect(state.transactionDraft?.id) {
        if (previousDraftId != null && state.transactionDraft == null) {
            transactionText = ""
            draftCreateKey = java.util.UUID.randomUUID().toString()
        }
        previousDraftId = state.transactionDraft?.id
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("FINANCE", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onLanguage("ru") }) { Text("RU") }
            TextButton(onClick = { onLanguage("en") }) { Text("EN") }
        }
        state.budgetAlerts.forEach { alert ->
            val isTotal = alert.budgetKey == "__total__"
            val label = when {
                isTotal && russian -> "Общий лимит"
                isTotal -> "Total limit"
                else -> alert.budgetKey
            }
            val threshold = when (alert.threshold) {
                "near" -> if (russian) "Почти достигнут" else "Near limit"
                else -> if (russian) "Лимит исчерпан" else "Limit reached"
            }
            Text("${if (alert.threshold == "near") "⚠️" else "🚨"} $threshold: $label · " +
                "${formatMoney(alert.spent, language)} / ${formatMoney(alert.limit, language)}",
                color = if (alert.threshold == "near") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
        }
        if (!state.authenticated) {
            Text(if (russian) "Финансы под контролем" else "Your finances, clearly", style = MaterialTheme.typography.headlineMedium)
            Text(if (russian) "Войдите через защищённый аккаунт Finance." else "Sign in with your secure Finance account.")
            Button(onClick = onLogin) { Text(if (russian) "Войти" else "Sign in") }
        } else {
            Row(Modifier.fillMaxWidth()) {
                Text(if (russian) "Пространства" else "Workspaces", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text(if (russian) "Обновить" else "Refresh") }
                TextButton(onClick = onLogout) { Text(if (russian) "Выйти" else "Sign out") }
            }
            if (state.tenants.isEmpty()) {
                OutlinedTextField(workspace, { workspace = it }, label = { Text(if (russian) "Название пространства" else "Workspace name") })
                OutlinedTextField(memberName, { memberName = it }, label = { Text(if (russian) "Ваше имя" else "Your name") })
                OutlinedTextField(plannedIncome, { plannedIncome = it }, singleLine = true,
                    label = { Text(if (russian) "Плановый доход в месяц, ₽" else "Planned monthly income, RUB") })
                TextButton(onClick = { plannedIncome = "" }, enabled = !state.busy) {
                    Text(if (russian) "Пропустить доход" else "Skip income")
                }
                val normalizedIncome = plannedIncome.trim().replace(',', '.')
                val validIncome = normalizedIncome.isBlank() || Regex("^(?:0\\.(?:[0-9]?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\\.[0-9]{1,2})?)$")
                    .matches(normalizedIncome)
                Button(onClick = { onCreate(workspace, memberName, normalizedIncomeInput(plannedIncome)) },
                    enabled = !state.busy && workspace.isNotBlank() && memberName.isNotBlank() && validIncome) {
                    Text(if (russian) "Создать" else "Create")
                }
            } else {
                Text(state.tenants.first().name, style = MaterialTheme.typography.headlineSmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("overview", "transactions", "shopping", "budgets", "debts", "reports", "profile", "inflation", "recurring", "nobuy").forEach { screen ->
                        TextButton(onClick = {
                            activeScreen = screen
                            if (screen == "shopping" && state.shoppingList == null && !state.shoppingLoading) onShoppingLoad()
                            if (screen == "nobuy" && state.doNotBuy == null && !state.doNotBuyLoading) onDoNotBuyLoad()
                            if (screen == "inflation" && state.personalInflation == null && !state.personalInflationLoading) {
                                onPersonalInflationLoad()
                            }
                            if (screen == "recurring" && state.recurringProjection == null && !state.recurringLoading) {
                                onRecurringLoad()
                            }
                        }) {
                            Text(when (screen) {
                                "overview" -> if (russian) "Обзор" else "Overview"
                                "shopping" -> if (russian) "Покупки" else "Shopping"
                                "nobuy" -> if (russian) "Не брать" else "Do not buy"
                                "inflation" -> if (russian) "Динамика цен" else "Price trend"
                                "recurring" -> if (russian) "Регулярные" else "Recurring"
                                "budgets" -> if (russian) "Бюджеты" else "Budgets"
                                "debts" -> if (russian) "Долги" else "Debts"
                                "reports" -> if (russian) "Отчёты" else "Reports"
                                "profile" -> if (russian) "Профиль" else "Profile"
                                else -> if (russian) "Операции" else "Transactions"
                            })
                        }
                    }
                }
                when (activeScreen) {
                    "overview" -> DashboardScreen(state, language)
                    "shopping" -> ShoppingScreen(Modifier.weight(1f), state, language, onShoppingLoad,
                        onShoppingDecision, onShoppingCopy)
                    "nobuy" -> DoNotBuyScreen(Modifier.weight(1f), state, language, onDoNotBuyLoad,
                        onDoNotBuyDecision)
                    "inflation" -> PersonalInflationScreen(Modifier.weight(1f), state, language,
                        onRetry = onPersonalInflationLoad)
                    "recurring" -> RecurringScreen(Modifier.weight(1f), state, language, onRetry = onRecurringLoad,
                        onRecurringDecision = onRecurringDecision)
                    "budgets" -> BudgetScreen(state, language, onBudgetUpdate, onBudgetReset, onBudgetProposal, onBudgetApply)
                    "debts" -> DebtScreen(state, language, onDebtCreate, onDebtPay, onDebtAdjust, onDebtForecast)
                    "reports" -> ReportScreen(state, language, onReportLoad)
                    "profile" -> ProfileScreen(Modifier.weight(1f), state, language, onProfileSave, onCreateTelegramLink,
                        onNotificationPreferencesSave,
                        onBack = { activeScreen = "overview" })
                    else -> {
                        LazyColumn(Modifier.weight(1f).testTag("transaction-history"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = transactionSearch,
                                        onValueChange = { transactionSearch = it },
                                        modifier = Modifier.fillMaxWidth().testTag("transaction-search"),
                                        label = { Text(if (russian) "Поиск операций" else "Search transactions") },
                                        singleLine = true,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = transactionTypeFilter == "all",
                                            modifier = Modifier.testTag("transaction-filter-all"),
                                            onClick = { transactionTypeFilter = "all" },
                                            label = { Text(if (russian) "Все" else "All") })
                                        FilterChip(selected = transactionTypeFilter == "expense",
                                            modifier = Modifier.testTag("transaction-filter-expense"),
                                            onClick = { transactionTypeFilter = "expense" },
                                            label = { Text(if (russian) "Расходы" else "Expenses") })
                                        FilterChip(selected = transactionTypeFilter == "income",
                                            modifier = Modifier.testTag("transaction-filter-income"),
                                            onClick = { transactionTypeFilter = "income" },
                                            label = { Text(if (russian) "Доходы" else "Income") })
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(value = transactionFrom, onValueChange = { transactionFrom = it },
                                            modifier = Modifier.weight(1f).testTag("transaction-filter-from"),
                                            label = { Text(if (russian) "С даты" else "From date") }, singleLine = true)
                                        OutlinedTextField(value = transactionTo, onValueChange = { transactionTo = it },
                                            modifier = Modifier.weight(1f).testTag("transaction-filter-to"),
                                            label = { Text(if (russian) "По дату" else "To date") }, singleLine = true)
                                    }
                                    transactionDateError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    val role = state.tenants.firstOrNull()?.role
                                    val canSeeAllMembers = role == "owner" || role == "admin"
                                    val selectedMemberId = transactionMemberId
                                    val selectedMemberLabel = when {
                                        selectedMemberId == "all" -> if (russian) "Все участники" else "All members"
                                        selectedMemberId.isBlank() -> if (russian) "Мои операции" else "My transactions"
                                        else -> state.transactionMembers.firstOrNull { it.userId == selectedMemberId }
                                            ?.displayName ?: if (russian) "Выберите участника" else "Select member"
                                    }
                                    Box {
                                        TextButton(modifier = Modifier.testTag("transaction-filter-member-picker"),
                                            onClick = { transactionMemberPickerExpanded = true }) {
                                            Text("${if (russian) "Участник" else "Member"}: $selectedMemberLabel")
                                        }
                                        DropdownMenu(expanded = transactionMemberPickerExpanded,
                                            onDismissRequest = { transactionMemberPickerExpanded = false }) {
                                            DropdownMenuItem(
                                                modifier = Modifier.testTag("transaction-member-option-self"),
                                                text = { Text(if (russian) "Мои операции" else "My transactions") },
                                                onClick = {
                                                    transactionMemberId = ""
                                                    transactionMemberPickerExpanded = false
                                                })
                                            if (canSeeAllMembers) DropdownMenuItem(
                                                modifier = Modifier.testTag("transaction-member-option-all"),
                                                text = { Text(if (russian) "Все участники" else "All members") },
                                                onClick = {
                                                    transactionMemberId = "all"
                                                    transactionMemberPickerExpanded = false
                                                })
                                            state.transactionMembers.forEach { member ->
                                                DropdownMenuItem(
                                                    modifier = Modifier.testTag("transaction-member-option-${member.userId}"),
                                                    text = { Text(member.displayName) },
                                                    onClick = {
                                                        transactionMemberId = member.userId
                                                        transactionMemberPickerExpanded = false
                                                    })
                                            }
                                        }
                                    }
                                    Button(enabled = !state.busy,
                                        onClick = {
                                            validateTransactionDates = true
                                            if (transactionDateValidationError(transactionFrom, transactionTo, russian) == null)
                                                onTransactionFilter(transactionSearch,
                                                transactionTypeFilter, transactionFrom, transactionTo, selectedMemberId)
                                        }) {
                                        Text(if (russian) "Применить фильтры" else "Apply filters")
                                    }
                                }
                            }
                            editingTransaction?.let { transaction ->
                                item(key = "transaction-edit-${transaction.id}") {
                                    TransactionEditEditor(transaction, state.debts, state.tenants.first().timezone,
                                        state.transactionMembers, canManageFamilyTransactions, language, state.busy,
                                        onSave = onUpdateTransaction,
                                        onCancel = { editingTransaction = null })
                                }
                            }
                            item {
                                val draft = state.transactionDraft?.takeIf { it.state == "pending" }
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (draft == null) {
                                            Text(if (russian) "Новая операция" else "New transaction",
                                                style = MaterialTheme.typography.titleMedium)
                                            OutlinedTextField(transactionText, { transactionText = it }, enabled = canWriteTransactions,
                                                label = { Text(if (russian) "Опишите операцию" else "Describe the transaction") })
                                            Button(onClick = { onCreateDraft(transactionText, draftCreateKey) }, enabled = canWriteTransactions &&
                                                !state.busy && transactionText.isNotBlank()) {
                                                Text(if (russian) "Разобрать текст" else "Review text")
                                            }
                                        } else {
                                            TransactionDraftEditor(draft, state.debts, state.busy, russian,
                                                onUpdateDraft, onConfirmDraft, onCancelDraft)
                                        }
                                    }
                                }
                            }
                            item {
                                Text(if (russian) "Недавние операции" else "Recent transactions",
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            if (state.transactions.isEmpty() && !state.busy) item {
                                Text(if (russian) "Нет операций по выбранным фильтрам"
                                    else "No transactions match these filters")
                            }
                            items(state.transactions) { transaction ->
                                val canEditThisTransaction = canWriteTransactions && transaction.status == "posted" &&
                                    (canManageFamilyTransactions || transaction.ownerUserId == currentUserId)
                                TransactionHistoryCard(transaction, language, canWriteTransactions, state.busy,
                                    canEditThisTransaction,
                                    onEdit = { editingTransaction = it },
                                    onRepeat = onRepeatTransaction, onVoid = onVoidTransaction)
                            }
                            if (state.transactionNextCursor != null) item {
                                Button(modifier = Modifier.testTag("transaction-load-more"), enabled = !state.busy,
                                    onClick = onTransactionLoadMore) {
                                    Text(if (russian) "Загрузить ещё" else "Load more")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (state.busy) androidx.compose.material3.CircularProgressIndicator()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@androidx.compose.runtime.Composable
private fun TransactionEditEditor(transaction: FinanceTransaction, debts: List<FinanceDebt>, timezone: String,
                                 members: List<FinanceTenantMember>, canReassignOwner: Boolean,
                                 language: String, busy: Boolean, onSave: (FinanceTransactionEdit) -> Unit,
                                 onCancel: () -> Unit) {
    val russian = language == "ru"
    var type by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.type) }
    var amount by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.amount) }
    var category by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.categoryCode) }
    var subcategory by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.subcategoryCode.orEmpty()) }
    var description by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.description) }
    var source by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.source) }
    var date by androidx.compose.runtime.remember(transaction.id, timezone) {
        mutableStateOf(runCatching {
            java.time.Instant.parse(transaction.occurredAt).atZone(ZoneId.of(timezone)).toLocalDate().toString()
        }.getOrDefault(transaction.occurredAt.take(10)))
    }
    var ownerUserId by androidx.compose.runtime.remember(transaction.id) {
        mutableStateOf(transaction.ownerUserId.orEmpty())
    }
    var ownerMenuExpanded by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(false) }
    var debtId by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.debtId) }
    var typeMenuExpanded by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(false) }
    var debtMenuExpanded by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(false) }
    val occurredAt = runCatching {
        LocalDate.parse(date).atTime(12, 0).atZone(ZoneId.of(timezone)).toInstant().toString()
    }.getOrNull()
    val validAmount = Regex("^(?:0\\.(?:0?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\\.[0-9]{1,2})?)$")
        .matches(amount.trim().replace(',', '.'))
    val categoryValue = category.trim()
    val subcategoryValue = subcategory.trim()
    val descriptionValue = description.trim()
    val sourceValue = source.trim()
    val valid = validAmount && categoryValue.length in 1..64 && subcategoryValue.length <= 64
        && descriptionValue.length in 1..500 && sourceValue.length in 1..64 && occurredAt != null
        && (type != "debt_payment" || debtId != null)
    Card(Modifier.fillMaxWidth().testTag("transaction-edit-form")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (russian) "Изменить операцию" else "Edit transaction", style = MaterialTheme.typography.titleMedium)
            Box {
                TextButton(modifier = Modifier.testTag("transaction-edit-type"), onClick = { typeMenuExpanded = true }) {
                    Text(if (russian) "Тип: $type" else "Type: $type")
                }
                DropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                    listOf("expense", "income", "refund", "transfer", "debt_payment").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = {
                            type = option
                            typeMenuExpanded = false
                        })
                    }
                }
            }
            OutlinedTextField(value = amount, onValueChange = { amount = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("transaction-edit-amount"),
                label = { Text(if (russian) "Сумма, ₽" else "Amount, RUB") }, singleLine = true)
            OutlinedTextField(value = category, onValueChange = { category = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("transaction-edit-category"),
                label = { Text(if (russian) "Категория" else "Category") }, singleLine = true)
            OutlinedTextField(value = subcategory, onValueChange = { subcategory = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("transaction-edit-subcategory"),
                label = { Text(if (russian) "Подкатегория" else "Subcategory") }, singleLine = true)
            OutlinedTextField(value = description, onValueChange = { description = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("transaction-edit-description"),
                label = { Text(if (russian) "Описание" else "Description") }, singleLine = true)
            OutlinedTextField(value = source, onValueChange = { source = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("transaction-edit-source"),
                label = { Text(if (russian) "Источник" else "Source") }, singleLine = true)
            OutlinedTextField(value = date, onValueChange = { date = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("transaction-edit-date"),
                label = { Text(if (russian) "Дата (ГГГГ-ММ-ДД)" else "Date (YYYY-MM-DD)") }, singleLine = true)
            if (canReassignOwner) {
                val selectedOwner = members.firstOrNull { it.userId == ownerUserId }
                Box {
                    TextButton(modifier = Modifier.testTag("transaction-edit-owner-picker"),
                        enabled = !busy, onClick = { ownerMenuExpanded = true }) {
                        Text("${if (russian) "Пользователь операции" else "Transaction member"}: " +
                            (selectedOwner?.displayName ?: if (russian) "Выберите участника" else "Select member"))
                    }
                    DropdownMenu(expanded = ownerMenuExpanded, onDismissRequest = { ownerMenuExpanded = false }) {
                        members.forEach { member ->
                            DropdownMenuItem(
                                modifier = Modifier.testTag("transaction-edit-owner-option-${member.userId}"),
                                text = { Text(member.displayName) },
                                onClick = {
                                    ownerUserId = member.userId
                                    ownerMenuExpanded = false
                                })
                        }
                    }
                }
            }
            if (!valid) Text(if (russian)
                "Проверьте сумму и поля: категория и источник обязательны (до 64 символов), подкатегория — до 64, описание — до 500."
            else "Check amount and fields: category and source are required (up to 64 characters), subcategory up to 64, description up to 500.",
                color = MaterialTheme.colorScheme.error)
            if (type == "debt_payment") {
                Box {
                    TextButton(modifier = Modifier.testTag("transaction-edit-debt"),
                        onClick = { debtMenuExpanded = true }) {
                        Text(debts.firstOrNull { it.id == debtId }?.name
                            ?: if (russian) "Выберите долг" else "Select debt")
                    }
                    DropdownMenu(expanded = debtMenuExpanded, onDismissRequest = { debtMenuExpanded = false }) {
                        debts.filter { it.status == "open" || it.id == transaction.debtId }.forEach { debt ->
                            DropdownMenuItem(text = { Text(debt.name) }, onClick = {
                                debtId = debt.id
                                debtMenuExpanded = false
                            })
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.testTag("transaction-edit-save"), enabled = !busy && valid,
                    onClick = {
                        val instant = occurredAt ?: return@Button
                        onSave(FinanceTransactionEdit(
                            id = transaction.id, version = transaction.version, type = type,
                            amount = amount.trim().replace(',', '.'), currency = transaction.currency,
                            categoryCode = categoryValue, subcategoryCode = subcategoryValue.takeIf(String::isNotEmpty),
                            description = descriptionValue, source = sourceValue, occurredAt = instant,
                            debtId = debtId, ownerUserId = ownerUserId.takeIf(String::isNotBlank),
                            accountId = transaction.accountId,
                        ))
                    }) { Text(if (russian) "Сохранить" else "Save") }
                TextButton(modifier = Modifier.testTag("transaction-edit-cancel"), enabled = !busy,
                    onClick = onCancel) { Text(if (russian) "Закрыть" else "Cancel") }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TransactionHistoryCard(transaction: FinanceTransaction, language: String, canWrite: Boolean, busy: Boolean,
                                   canEdit: Boolean, onEdit: (FinanceTransaction) -> Unit,
                                   onRepeat: (FinanceTransaction) -> Unit, onVoid: (FinanceTransaction) -> Unit) {
    val russian = language == "ru"
    val posted = transaction.status == "posted"
    val income = transaction.type == "income" || transaction.type == "refund"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(transaction.description.ifBlank { transaction.categoryCode }, style = MaterialTheme.typography.titleMedium)
            Text("${transaction.occurredAt.take(10)} · ${if (income) "+" else "−"}${formatMoney(transaction.amount, language, transaction.currency)}")
            Text(if (russian) {
                "${transaction.memberName?.let { "$it · " } ?: ""}${transaction.categoryCode} · " +
                    if (posted) "Проведена" else "Отменена"
            } else {
                "${transaction.memberName?.let { "$it · " } ?: ""}${transaction.categoryCode} · " +
                    if (posted) "Posted" else "Voided"
            })
            if ((canWrite || canEdit) && posted) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canEdit) TextButton(
                        modifier = Modifier.testTag("transaction-edit-${transaction.id}"),
                        enabled = !busy, onClick = { onEdit(transaction) },
                    ) { Text(if (russian) "Изменить" else "Edit") }
                    if (transaction.type != "debt_payment") TextButton(
                        modifier = Modifier.testTag("transaction-repeat-${transaction.id}"),
                        enabled = !busy, onClick = { onRepeat(transaction) },
                    ) { Text(if (russian) "Повторить" else "Repeat") }
                    TextButton(
                        modifier = Modifier.testTag("transaction-void-${transaction.id}"),
                        enabled = !busy, onClick = { onVoid(transaction) },
                    ) { Text(if (russian) "Отменить" else "Void") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RecurringScreen(modifier: Modifier, state: FinanceUiState, language: String, onRetry: () -> Unit,
                            onRecurringDecision: (String, Boolean) -> Unit) {
    val russian = language == "ru"
    val projection = state.recurringProjection
    LazyColumn(modifier.testTag("recurring-projection"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (russian) "Регулярные доходы и расходы" else "Recurring income and expenses",
                    style = MaterialTheme.typography.titleLarge)
                Text(if (russian) "Оценка по повторяющимся операциям из вашей истории."
                    else "Estimate from repeated transactions in your history.")
            }
        }
        when {
            state.recurringLoading -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            state.recurringError != null -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (russian) "Регулярные операции временно недоступны." else "Recurring transactions are unavailable.")
                    Button(onClick = onRetry) { Text(if (russian) "Повторить" else "Retry") }
                }
            }
            projection == null -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            projection.expenseSeries.isEmpty() && projection.incomeSeries.isEmpty() && projection.mutedSeries.isEmpty() -> item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (russian) "Пока нет найденных регулярных операций." else "No recurring transactions found yet.")
                    Text(if (russian) "Для серии нужны минимум три похожие операции." else "Each series needs at least three similar transactions.")
                }
            }
            else -> {
                if (projection.dueSoon.isNotEmpty()) {
                    item { Text(if (russian) "Скоро · следующие 3 дня" else "Due soon · next 3 days",
                        style = MaterialTheme.typography.titleMedium) }
                    items(projection.dueSoon, key = { "due-${it.id}" }) { series ->
                        RecurringSeriesCard(series, russian, warning = true)
                    }
                }
                if (projection.monthlyExpenseEstimate != null) item {
                    Card(Modifier.fillMaxWidth()) {
                        Text("${if (russian) "Оценка расходов в месяц" else "Estimated monthly expenses"}: " +
                            "${projection.monthlyExpenseEstimate} ${if (russian) "₽" else "RUB"}",
                            Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
                item { Text(if (russian) "Регулярные расходы" else "Recurring expenses", style = MaterialTheme.typography.titleMedium) }
                if (projection.expenseSeries.isEmpty()) item { Text(if (russian) "Нет подтверждённых серий расходов." else "No confirmed expense series.") }
                items(projection.expenseSeries, key = { "expense-${it.id}" }) { series ->
                    RecurringSeriesCard(series, russian, actionLabel = if (russian) "Отключить напоминание" else "Mute reminder") {
                        onRecurringDecision(series.id, true)
                    }
                }
                item { Text(if (russian) "Регулярные доходы" else "Recurring income", style = MaterialTheme.typography.titleMedium) }
                if (projection.incomeSeries.isEmpty()) item { Text(if (russian) "Нет подтверждённых серий доходов." else "No confirmed income series.") }
                items(projection.incomeSeries, key = { "income-${it.id}" }) { series ->
                    RecurringSeriesCard(series, russian, actionLabel = if (russian) "Отключить напоминание" else "Mute reminder") {
                        onRecurringDecision(series.id, true)
                    }
                }
                if (projection.overdue.isNotEmpty()) {
                    item { Text(if (russian) "Просрочено · не входит в ближайшие списания" else "Overdue · excluded from upcoming charges",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error) }
                    items(projection.overdue, key = { "overdue-${it.id}" }) { series ->
                        RecurringSeriesCard(series, russian, warning = false, overdue = true)
                    }
                }
                if (projection.mutedSeries.isNotEmpty()) {
                    item { Text(if (russian) "Отключённые напоминания" else "Muted reminders",
                        style = MaterialTheme.typography.titleMedium) }
                    items(projection.mutedSeries, key = { "muted-${it.id}" }) { series ->
                        RecurringSeriesCard(series, russian,
                            actionLabel = if (russian) "Восстановить напоминание" else "Restore reminder") {
                            onRecurringDecision(series.id, false)
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RecurringSeriesCard(series: FinanceRecurringSeries, russian: Boolean, warning: Boolean = false,
                                overdue: Boolean = false, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(series.name, style = MaterialTheme.typography.titleMedium)
            Text("${series.amount} ${if (russian) "₽" else series.currency} · " +
                (if (series.periodCode == "week") { if (russian) "еженедельно" else "weekly" }
                else { if (russian) "ежемесячно" else "monthly" }))
            Text("${if (russian) "Обычно" else "Typical range"}: ${series.minAmount}–${series.maxAmount} " +
                "${if (russian) "₽" else series.currency} · ${if (russian) "интервал" else "interval"}: " +
                "${series.minIntervalDays}–${series.maxIntervalDays} ${if (russian) "дн." else "days"}")
            Text(if (overdue) {
                if (russian) "Просрочено на ${-series.daysUntil} дн. · ${series.nextDate}" else "Overdue ${-series.daysUntil} days · ${series.nextDate}"
            } else if (warning) {
                if (russian) "Через ${series.daysUntil} дн. · ${series.nextDate}" else "In ${series.daysUntil} days · ${series.nextDate}"
            } else {
                if (russian) "Следующая дата: ${series.nextDate} · ${series.occurrences} операций"
                else "Next: ${series.nextDate} · ${series.occurrences} transactions"
            })
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PersonalInflationScreen(modifier: Modifier, state: FinanceUiState, language: String, onRetry: () -> Unit) {
    val russian = language == "ru"
    val inflation = state.personalInflation
    LazyColumn(modifier = modifier.testTag("personal-inflation"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (russian) "Личная динамика цен · 90 дней" else "Personal price trend · 90 days",
                    style = MaterialTheme.typography.titleLarge)
                Text(if (russian) "Цены только из ваших чеков, не официальная статистика."
                    else "Receipt prices only; not official inflation statistics.")
            }
        }
        when {
            state.personalInflationLoading -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            state.personalInflationError != null -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (russian) "Динамика цен временно недоступна." else "Price trend is temporarily unavailable.")
                    Button(onClick = onRetry) { Text(if (russian) "Повторить" else "Retry") }
                }
            }
            inflation == null -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            !inflation.available -> item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (russian) "Недостаточно истории для расчёта." else "There is not enough purchase history.")
                    Text(if (russian) "Нужно минимум 3 товара: для каждого — 2 покупки до окна и 1 внутри 90-дневного окна."
                        else "At least 3 products are needed: each must have 2 purchases before the window and 1 within it.")
                }
            }
            else -> {
                val basketBefore = inflation.basketBefore ?: return@LazyColumn
                val basketNow = inflation.basketNow ?: return@LazyColumn
                val indexPercent = inflation.indexPercent ?: return@LazyColumn
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${if (russian) "Корзина по старым ценам" else "Basket at earlier prices"}: $basketBefore ${if (russian) "₽" else "RUB"}")
                            Text("${if (russian) "Та же корзина по новым ценам" else "Same basket at recent prices"}: $basketNow ${if (russian) "₽" else "RUB"}")
                            Text("${if (russian) "Личный индекс" else "Personal index"}: $indexPercent% · " +
                                if (russian) "${inflation.productCount} товара" else "${inflation.productCount} products")
                        }
                    }
                }
                item { Text(if (russian) "Сильнее подорожали" else "Largest increases", style = MaterialTheme.typography.titleMedium) }
                if (inflation.rising.isEmpty()) item { Text(if (russian) "Нет заметных изменений." else "No notable changes.") }
                items(inflation.rising) { product ->
                    Card(Modifier.fillMaxWidth()) {
                        Text("${product.productName} · ${product.oldUnitPrice} → ${product.newUnitPrice} " +
                            "${if (russian) "₽" else "RUB"} · ${product.changePercent}% · " +
                            "${if (russian) "вес" else "weight"} ${product.oldSpendWeight} ${if (russian) "₽" else "RUB"}",
                            Modifier.padding(14.dp))
                    }
                }
                item { Text(if (russian) "Сильнее подешевели" else "Largest decreases", style = MaterialTheme.typography.titleMedium) }
                if (inflation.falling.isEmpty()) item { Text(if (russian) "Нет заметных изменений." else "No notable changes.") }
                items(inflation.falling) { product ->
                    Card(Modifier.fillMaxWidth()) {
                        Text("${product.productName} · ${product.oldUnitPrice} → ${product.newUnitPrice} " +
                            "${if (russian) "₽" else "RUB"} · ${product.changePercent}% · " +
                            "${if (russian) "вес" else "weight"} ${product.oldSpendWeight} ${if (russian) "₽" else "RUB"}",
                            Modifier.padding(14.dp))
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ShoppingScreen(modifier: Modifier, state: FinanceUiState, language: String, onRetry: () -> Unit,
                           onDecision: (String, String) -> Unit, onCopy: (String) -> Unit) {
    val russian = language == "ru"
    val shopping = state.shoppingList
    LazyColumn(modifier = modifier.testTag("shopping-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (russian) "Пора купить" else "Shopping list", style = MaterialTheme.typography.titleLarge)
                if (shopping != null && state.shoppingError == null && shopping.candidates.isNotEmpty()) {
                    Button(onClick = { onCopy(shoppingClipboardText(shopping, russian)) }) {
                        Text(if (russian) "Скопировать список" else "Copy list")
                    }
                }
            }
        }
        when {
            state.shoppingLoading -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            state.shoppingError != null -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (russian) "Список покупок временно недоступен." else "Shopping suggestions are temporarily unavailable.")
                    Button(onClick = onRetry) { Text(if (russian) "Повторить" else "Retry") }
                }
            }
            shopping == null -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            shopping.candidates.isEmpty() -> item {
                val hasHidden = shopping.boughtCandidates.isNotEmpty() || shopping.mutedCandidates.isNotEmpty()
                    || shopping.blockedCandidates.isNotEmpty()
                Text(if (hasHidden) {
                    if (russian) "Активных подсказок пока нет." else "No active suggestions right now."
                } else if (russian) "Пока нечего добавить: нужны минимум три покупки с интервалами от трёх дней."
                else "Nothing to suggest yet: at least three purchases with gaps of three days or more are needed.")
            }
            else -> shopping.candidates.forEach { candidate ->
                item {
                    val due = when {
                        candidate.daysUntilDue < 0 -> if (russian) "Просрочено на ${-candidate.daysUntilDue} дн."
                            else "${-candidate.daysUntilDue} days overdue"
                        candidate.daysUntilDue == 0 -> if (russian) "Пора" else "Due now"
                        else -> if (russian) "Через ${candidate.daysUntilDue} дн." else "In ${candidate.daysUntilDue} days"
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(candidate.productName, style = MaterialTheme.typography.titleMedium)
                            Text(due)
                            Text(if (russian) "Медиана: раз в ${candidate.medianIntervalDays} дн. · ${candidate.purchaseCount} покупки"
                                else "Median: every ${candidate.medianIntervalDays} days · ${candidate.purchaseCount} purchases")
                            Text(if (russian) "Оценка: ${candidate.estimatedCost} ₽" else "Estimate: ${candidate.estimatedCost} RUB")
                            Text(if (russian) "Последняя покупка: ${candidate.lastPurchasedAt.take(10)}"
                                else "Last purchased: ${candidate.lastPurchasedAt.take(10)}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onDecision(candidate.productKey, "bought") }) {
                                    Text(if (russian) "Уже купил" else "Already bought")
                                }
                                TextButton(onClick = { onDecision(candidate.productKey, "mute") }) {
                                    Text(if (russian) "Скрыть" else "Hide")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (shopping != null && state.shoppingError == null) {
            if (shopping.boughtCandidates.isNotEmpty()) {
                item {
                    Text(if (russian) "Уже куплено" else "Already bought", style = MaterialTheme.typography.titleMedium)
                    Text(if (russian) "Отметка действует до следующего обычного интервала покупки."
                        else "This mark expires after the next usual purchase interval.")
                    shopping.boughtCandidates.forEach { Text(it.productName) }
                }
            }
            if (shopping.mutedCandidates.isNotEmpty()) {
                item { Text(if (russian) "Скрытые подсказки" else "Hidden suggestions", style = MaterialTheme.typography.titleMedium) }
                shopping.mutedCandidates.forEach { candidate ->
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.productName)
                                Text(if (russian) "Вы скрыли эту подсказку." else "You hid this suggestion.")
                            }
                            TextButton(onClick = { onDecision(candidate.productKey, "unmute") }) {
                                Text(if (russian) "Вернуть подсказку" else "Restore suggestion")
                            }
                        }
                    }
                }
            }
            if (shopping.blockedCandidates.isNotEmpty()) {
                item { Text(if (russian) "Не брать" else "Do not buy", style = MaterialTheme.typography.titleMedium) }
                shopping.blockedCandidates.forEach { candidate ->
                    item {
                        val reason = if (candidate.reasonCode == "confirmed_not_to_buy") {
                            if (russian) "Вы отметили как «не брать»." else "You marked this as do not buy."
                        } else if (russian) "Основано на проверке чеков." else "Based on receipt review."
                        Text("${candidate.productName} · $reason")
                    }
                }
            }
            item {
                Text(if (russian) "Оценка списка: ${shopping.estimatedListCost} ₽"
                    else "Estimated list cost: ${shopping.estimatedListCost} RUB",
                    style = MaterialTheme.typography.titleMedium)
            }
            item {
                Text(if (russian) "Это подсказка по чекам, не учёт запасов."
                    else "Not home inventory: suggestions use your confirmed receipt rhythm.")
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DoNotBuyScreen(modifier: Modifier, state: FinanceUiState, language: String, onRetry: () -> Unit,
                           onDecision: (String, String) -> Unit) {
    val russian = language == "ru"
    val report = state.doNotBuy
    val decisions = state.productDecisions
    val canWrite = state.tenants.firstOrNull()?.role != "viewer" && decisions != null && !state.doNotBuyLoading
    LazyColumn(modifier = modifier.testTag("do-not-buy-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(if (russian) "Не брать" else "Do not buy", style = MaterialTheme.typography.titleLarge) }
        when {
            state.doNotBuyLoading -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            state.doNotBuyError != null -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (russian) "Советы по чекам временно недоступны." else "Receipt advice is temporarily unavailable.")
                    Button(onClick = onRetry) { Text(if (russian) "Повторить" else "Retry") }
                }
            }
            report == null || decisions == null -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            !report.available -> item {
                Text(if (russian) "Советы по чекам временно недоступны." else "Receipt advice is temporarily unavailable.")
                Button(onClick = onRetry) { Text(if (russian) "Повторить" else "Retry") }
            }
            else -> {
                if (report.banned.isEmpty() && report.guesses.isEmpty()) item {
                    Text(if (russian) "Повторяющихся отметок «вредно» или «лишнее» пока нет."
                        else "No repeated harmful or unnecessary verdicts yet.")
                }
                if (report.banned.isNotEmpty()) item {
                    Text(if (russian) "По правилам или вашему решению" else "By rule or your decision",
                        style = MaterialTheme.typography.titleMedium)
                }
                report.banned.forEach { group ->
                    item { DoNotBuyGroup(group, false, decisions, russian, canWrite, onDecision) }
                }
                if (report.guesses.isNotEmpty()) item {
                    Column {
                        val uncertain = report.guesses.any { !it.modelOnly }
                        Text(if (uncertain) {
                            if (russian) "Непроверенные отметки" else "Unverified evidence"
                        } else if (russian) "Догадки модели" else "Model guesses",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (russian) "Без вашего подтверждения эти отметки не скрывают покупку."
                            else "These verdicts do not hide a purchase until you confirm them.")
                    }
                }
                report.guesses.forEach { group ->
                    item { DoNotBuyGroup(group, true, decisions, russian, canWrite, onDecision) }
                }
                if (decisions.productKeys.isNotEmpty()) item {
                    Text(if (russian) "Вы разрешили покупать" else "You allowed purchases",
                        style = MaterialTheme.typography.titleMedium)
                }
                decisions.productKeys.forEach { key ->
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(key, Modifier.weight(1f))
                            if (canWrite) TextButton(onClick = { onDecision(key, "revoke") }) {
                                Text(if (russian) "Отменить решение" else "Undo decision")
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DoNotBuyGroup(group: FinanceAdviceGroup, guess: Boolean, decisions: FinanceProductDecisions,
                          russian: Boolean, canWrite: Boolean, onDecision: (String, String) -> Unit) {
    val confirmed = group.productKey in decisions.confirmedProductKeys
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(group.productName, style = MaterialTheme.typography.titleMedium)
            Text(when {
                confirmed -> if (russian) "Подтверждено вами" else "Confirmed by you"
                guess && group.modelOnly -> if (russian) "Догадка модели" else "Model guess"
                guess -> if (russian) "Источник части отметок неизвестен" else "Some verdict sources are unknown"
                else -> if (russian) "Основано на проверке чеков" else "Based on receipt review"
            })
            Text(if (russian) "Отмечено в чеках: ${group.count}" else "Flagged receipts: ${group.count}")
            Text(if (group.amount == null) {
                if (russian) "Сумма неизвестна" else "Amount unavailable"
            } else if (russian) "Сумма: ${group.amount} ₽" else "Amount: ${group.amount} RUB")
            if (group.latestAdvice.isNotBlank()) Text(group.latestAdvice)
            if (canWrite) {
                if (guess && !confirmed) TextButton(onClick = { onDecision(group.productKey, "confirm") }) {
                    Text(if (russian) "Подтвердить «не брать»" else "Confirm do not buy")
                }
                TextButton(onClick = { onDecision(group.productKey, "allow") }) {
                    Text(if (russian) "Можно брать" else "Allow purchase")
                }
                if (confirmed) TextButton(onClick = { onDecision(group.productKey, "revoke") }) {
                    Text(if (russian) "Отменить решение" else "Undo decision")
                }
            }
        }
    }
}

private fun shoppingClipboardText(shopping: FinanceShoppingList, russian: Boolean): String = buildString {
    shopping.candidates.forEach { appendLine("${it.productName} — ${it.estimatedCost} RUB") }
    append(if (russian) "Оценка списка: ${shopping.estimatedListCost} ₽"
        else "Estimated list cost: ${shopping.estimatedListCost} RUB")
}

@androidx.compose.runtime.Composable
private fun ProfileScreen(modifier: Modifier, state: FinanceUiState, language: String,
                          onSave: (String, String?) -> Unit, onCreateTelegramLink: () -> Unit,
                          onNotificationPreferencesSave: (FinanceNotificationPreferences) -> Unit, onBack: () -> Unit) {
    val russian = language == "ru"
    val profile = state.memberProfile
    if (profile == null) {
        Text(if (russian) "Профиль пока не загружен" else "Profile is not loaded")
        return
    }
    var name by androidx.compose.runtime.remember(profile.displayName) { mutableStateOf(profile.displayName) }
    var income by androidx.compose.runtime.remember(profile.plannedIncome) {
        mutableStateOf(profile.plannedIncome.orEmpty())
    }
    val notificationPreferences = state.notificationPreferences
    var digestLanguage by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.language ?: "ru")
    }
    var dailyEnabled by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.dailyEnabled ?: true)
    }
    var dailyTime by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.dailyLocalTime ?: "21:00")
    }
    var weeklyEnabled by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.weeklyEnabled ?: true)
    }
    var weeklyDay by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.weeklyDayOfWeek ?: 7)
    }
    var weeklyTime by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.weeklyLocalTime ?: "19:00")
    }
    var quietStart by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.quietHoursStart.orEmpty())
    }
    var quietEnd by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.quietHoursEnd.orEmpty())
    }
    var showWeekdayMenu by androidx.compose.runtime.remember { mutableStateOf(false) }
    val normalizedIncome = income.trim().replace(',', '.')
    val validIncome = normalizedIncome.isBlank() || Regex("^(?:0\\.(?:[0-9]?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\\.[0-9]{1,2})?)$")
        .matches(normalizedIncome)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (russian) "Мой профиль" else "My profile", style = MaterialTheme.typography.titleLarge)
        if (profile.onboardingState == "started") {
            Text(if (russian) "Доход можно добавить сейчас или указать позже." else "Add planned income now or leave it for later.")
        }
        OutlinedTextField(name, { name = it }, enabled = !state.busy, singleLine = true,
            label = { Text(if (russian) "Ваше имя" else "Your name") })
        OutlinedTextField(income, { income = it }, enabled = !state.busy, singleLine = true,
            label = { Text(if (russian) "Плановый доход в месяц, ₽" else "Planned monthly income, RUB") })
        TextButton(onClick = { income = "" }, enabled = !state.busy) {
            Text(if (russian) "Пропустить доход" else "Skip income")
        }
        Text(if (russian) "Часовой пояс: ${profile.timezone} · Валюта: ${profile.currency}"
            else "Timezone: ${profile.timezone} · Currency: ${profile.currency}")
        Text(if (russian) "Откройте личный чат с ботом, отправьте /link и код ниже. Код действует 10 минут."
            else "Open a private chat with the bot, then send /link and the code below. Code expires in 10 minutes.")
        state.telegramLinkCode?.let { Text(if (russian) "Код: ${it.code}" else "Code: ${it.code}") }
        TextButton(onClick = onCreateTelegramLink, enabled = !state.busy) {
            Text(if (russian) "Подключить Telegram" else "Connect Telegram")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(name.trim(), normalizedIncomeInput(income)) },
                enabled = !state.busy && name.isNotBlank() && validIncome) {
                Text(if (russian) "Сохранить профиль" else "Save profile")
            }
            TextButton(onClick = onBack) { Text(if (russian) "Назад" else "Back") }
        }
        Text(if (russian) "Сводки в Telegram" else "Telegram digests", style = MaterialTheme.typography.titleMedium)
        if (notificationPreferences == null) {
            Text(if (russian) "Настройки сводок загружаются…" else "Digest settings are loading…")
        } else {
            Text(if (notificationPreferences.telegramLinked) {
                if (russian) "Telegram подключён" else "Telegram is connected"
            } else {
                if (russian) "Сначала подключите Telegram, чтобы получать сводки." else "Connect Telegram to receive digests."
            })
            Text(if (russian) "Часовой пояс: ${notificationPreferences.timezone}"
                else "Time zone: ${notificationPreferences.timezone}")
            Text(if (russian) "Язык сводки" else "Digest language")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { digestLanguage = "ru" }, enabled = !state.busy) { Text("Русский") }
                TextButton(onClick = { digestLanguage = "en" }, enabled = !state.busy) { Text("English") }
            }
            Row {
                Checkbox(checked = dailyEnabled, onCheckedChange = { dailyEnabled = it }, enabled = !state.busy)
                Text(if (russian) "Ежедневная сводка" else "Daily digest", Modifier.padding(top = 12.dp))
            }
            OutlinedTextField(dailyTime, { dailyTime = it }, enabled = !state.busy, singleLine = true,
                label = { Text(if (russian) "Время ежедневной сводки" else "Daily digest time") })
            Row {
                Checkbox(checked = weeklyEnabled, onCheckedChange = { weeklyEnabled = it }, enabled = !state.busy)
                Text(if (russian) "Недельная сводка" else "Weekly digest", Modifier.padding(top = 12.dp))
            }
            val weekdays = if (russian) listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
                else listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
            Text(if (russian) "День недельного дайджеста" else "Weekly digest day")
            TextButton(onClick = { showWeekdayMenu = true }, enabled = !state.busy) {
                Text(weekdays[weeklyDay - 1])
            }
            DropdownMenu(expanded = showWeekdayMenu, onDismissRequest = { showWeekdayMenu = false }) {
                weekdays.forEachIndexed { index, day ->
                    DropdownMenuItem(text = { Text(day) }, onClick = { weeklyDay = index + 1; showWeekdayMenu = false })
                }
            }
            OutlinedTextField(weeklyTime, { weeklyTime = it }, enabled = !state.busy, singleLine = true,
                label = { Text(if (russian) "Время недельной сводки" else "Weekly digest time") })
            OutlinedTextField(quietStart, { quietStart = it }, enabled = !state.busy, singleLine = true,
                label = { Text(if (russian) "Начало тихих часов" else "Quiet hours start") })
            OutlinedTextField(quietEnd, { quietEnd = it }, enabled = !state.busy, singleLine = true,
                label = { Text(if (russian) "Конец тихих часов" else "Quiet hours end") })
            val timePattern = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
            val quietHoursValid = quietStart.isBlank() && quietEnd.isBlank() ||
                timePattern.matches(quietStart) && timePattern.matches(quietEnd)
            val scheduleValid = timePattern.matches(dailyTime) && timePattern.matches(weeklyTime) &&
                weeklyDay in 1..7 && quietHoursValid
            if (!quietHoursValid) Text(if (russian) "Укажите обе границы тихих часов или оставьте обе пустыми."
                else "Set both quiet-hours times or leave both empty.", color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                onNotificationPreferencesSave(notificationPreferences.copy(language = digestLanguage,
                    dailyEnabled = dailyEnabled, dailyLocalTime = dailyTime, weeklyEnabled = weeklyEnabled,
                    weeklyDayOfWeek = weeklyDay, weeklyLocalTime = weeklyTime,
                    quietHoursStart = quietStart.takeIf(String::isNotBlank), quietHoursEnd = quietEnd.takeIf(String::isNotBlank)))
            }, enabled = !state.busy && scheduleValid) {
                Text(if (russian) "Сохранить расписание" else "Save schedule")
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TransactionDraftEditor(draft: FinanceTransactionDraft, debts: List<FinanceDebt>, busy: Boolean,
                                  russian: Boolean, onUpdate: (TransactionDraftEdit) -> Unit,
                                  onConfirm: (String, Long) -> Unit, onCancel: (String, Long) -> Unit) {
    var type by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(draft.type) }
    var amount by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(draft.amount) }
    var category by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(draft.categoryCode) }
    var subcategory by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(draft.subcategoryCode.orEmpty()) }
    var description by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(draft.description) }
    var debtId by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(draft.debtId) }
    var dirty by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(false) }
    val typeLabel = when (type) {
        "income" -> if (russian) "Доход" else "Income"
        "debt_payment" -> if (russian) "Платёж по долгу" else "Debt payment"
        else -> if (russian) "Расход" else "Expense"
    }
    Text(if (russian) "Предложение операции" else "Transaction suggestion",
        style = MaterialTheme.typography.titleMedium)
    Text(if (russian) "Тип: $typeLabel" else "Type: $typeLabel")
    Text(if (russian) "Распознано: ${draft.occurredAt}" else "Detected date: ${draft.occurredAt}")
    Text(if (russian) "Провайдер: ${draft.provider} · модель: ${draft.modelVersion} · промпт: ${draft.promptVersion}"
        else "Provider: ${draft.provider} · model: ${draft.modelVersion} · prompt: ${draft.promptVersion}")
    OutlinedTextField(amount, { amount = it; dirty = true }, enabled = !busy, singleLine = true,
        label = { Text(if (russian) "Сумма, ₽" else "Amount, RUB") })
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("500" to "500 ₽", "1000" to "1 000 ₽", "2000" to "2 000 ₽", "5000" to "5 000 ₽").forEach { (value, label) ->
            TextButton(onClick = { amount = "$value.00"; dirty = true }, enabled = !busy) { Text(label) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("expense", "income", "debt_payment").forEach { option ->
            val label = when (option) {
                "income" -> if (russian) "Доход" else "Income"
                "debt_payment" -> if (russian) "Долг" else "Debt"
                else -> if (russian) "Расход" else "Expense"
            }
            TextButton(onClick = {
                type = option
                if (option == "debt_payment") category = "долги"
                if (option != "debt_payment") debtId = null
                dirty = true
            },
                enabled = !busy) { Text(label) }
        }
    }
    OutlinedTextField(category, { category = it; dirty = true }, enabled = !busy, singleLine = true,
        label = { Text(if (russian) "Категория" else "Category") })
    OutlinedTextField(subcategory, { subcategory = it; dirty = true }, enabled = !busy, singleLine = true,
        label = { Text(if (russian) "Подкатегория" else "Subcategory") })
    OutlinedTextField(description, { description = it; dirty = true }, enabled = !busy, singleLine = true,
        label = { Text(if (russian) "Описание" else "Description") })
    if (type == "debt_payment") {
        Text(if (russian) "Выберите долг" else "Choose a debt")
        debts.filter { it.status == "open" }.forEach { debt ->
            TextButton(onClick = { debtId = debt.id; dirty = true }, enabled = !busy) {
                Text(if (debtId == debt.id) "✓ ${debt.name}" else debt.name)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { onUpdate(TransactionDraftEdit(draft.id, draft.version, type, amount,
            category, subcategory.takeIf(String::isNotBlank), description, draft.occurredAt, debtId)) },
            enabled = dirty && !busy && amount.isNotBlank() && category.isNotBlank() && description.isNotBlank()) {
            Text(if (russian) "Сохранить изменения" else "Save changes")
        }
        Button(onClick = { onConfirm(draft.id, draft.version) },
            enabled = !dirty && !busy && (type != "debt_payment" || debtId != null)) {
            Text(if (russian) "Подтвердить" else "Confirm")
        }
    }
    TextButton(onClick = { onCancel(draft.id, draft.version) }, enabled = !busy) {
        Text(if (russian) "Отменить" else "Cancel")
    }
}

@androidx.compose.runtime.Composable
private fun DashboardScreen(state: FinanceUiState, language: String) {
    val russian = language == "ru"
    val summary = state.dashboardSummary
    val budget = state.budgets
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (russian) "Сводка" else "Summary", style = MaterialTheme.typography.titleLarge)
        if (summary == null) {
            Text(if (russian) "Сводка пока не загружена" else "Summary is not loaded")
            return@Column
        }
        Text(if (russian) "За ${summary.month} · на ${summary.asOfDate}" else "${summary.month} · as of ${summary.asOfDate}")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (russian) "Доходы месяца: ${formatMoney(summary.incomeTotal, language)}"
                    else "Monthly income: ${formatMoney(summary.incomeTotal, language)}")
                Text(if (russian) "Расходы месяца: ${formatMoney(summary.expenseTotal, language)}"
                    else "Monthly expenses: ${formatMoney(summary.expenseTotal, language)}")
                Text(if (russian) "Операций: ${summary.transactionCount}" else "Transactions: ${summary.transactionCount}")
            }
        }
        summary.dailyExpensePace?.let {
            Text(if (russian) "Средний расход в день: $it ₽" else "Daily expense pace: $it RUB")
        }
        summary.projectedExpenseTotal?.let {
            Text(if (russian) "Прогноз расходов за месяц: $it ₽" else "Projected monthly expenses: $it RUB")
        }
        summary.safeToSpend?.let { cash ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (russian) "Безопасно тратить в день: ${formatMoney(cash.safePerDay, language)}"
                        else "Safe to spend per day: ${formatMoney(cash.safePerDay, language)}")
                    Text(if (russian) "Свободно до ${cash.horizonDate}: ${formatMoney(cash.safeTotal, language)}"
                        else "Available through ${cash.horizonDate}: ${formatMoney(cash.safeTotal, language)}")
                    Text(if (russian) "Резерв 10%: ${formatMoney(cash.reserve, language)}"
                        else "10% reserve: ${formatMoney(cash.reserve, language)}")
                    Text(if (russian) "Обязательные списания: ${formatMoney(cash.promisedPayments, language)}"
                        else "Committed payments: ${formatMoney(cash.promisedPayments, language)}")
                }
            }
        }
        budget?.let {
            Text(if (russian) "Лимит месяца: ${formatMoney(it.effectiveTotalLimit, language)} · потрачено " +
                "${formatMoney(it.totalMonthlySpent, language)} · ${formatSemanticStatus("limitStatus", it.totalLimitStatus, language)}"
            else "Monthly budget: ${formatMoney(it.effectiveTotalLimit, language)} · spent " +
                "${formatMoney(it.totalMonthlySpent, language)} · ${formatSemanticStatus("limitStatus", it.totalLimitStatus, language)}")
            LinearProgressIndicator(progress = { amountFraction(it.totalMonthlySpent, it.effectiveTotalLimit) },
                modifier = Modifier.fillMaxWidth())
            val food = summary.rolling7FoodStatus
            Text(if (russian) "Еда за 7 дней: ${formatMoney(food.spent, language)} / ${formatMoney(food.limit, language)} · " +
                formatSemanticStatus("paceStatus", food.paceStatus, language)
            else "Food over 7 days: ${formatMoney(food.spent, language)} / ${formatMoney(food.limit, language)} · " +
                formatSemanticStatus("paceStatus", food.paceStatus, language))
        }
    }
}

private fun transactionDateValidationError(from: String, to: String, russian: Boolean): String? {
    val fromValue = from.trim()
    val toValue = to.trim()
    val parsedFrom = fromValue.takeIf(String::isNotEmpty)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val parsedTo = toValue.takeIf(String::isNotEmpty)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return when {
        fromValue.isNotEmpty() && parsedFrom == null || toValue.isNotEmpty() && parsedTo == null ->
            if (russian) "Введите дату в формате ГГГГ-ММ-ДД" else "Enter dates as YYYY-MM-DD"
        parsedFrom != null && parsedTo != null && parsedFrom.isAfter(parsedTo) ->
            if (russian) "Дата начала должна быть не позже даты окончания"
            else "Start date must be on or before end date"
        else -> null
    }
}

@androidx.compose.runtime.Composable
private fun ReportScreen(state: FinanceUiState, language: String,
                         onLoad: (String, String, String, String, String) -> Unit) {
    val russian = language == "ru"
    val initialMonth = state.report?.fromDate?.take(7) ?: YearMonth.now().toString()
    var period by androidx.compose.runtime.remember { mutableStateOf("month") }
    var scope by androidx.compose.runtime.remember { mutableStateOf("personal") }
    var month by androidx.compose.runtime.remember { mutableStateOf(initialMonth) }
    var from by androidx.compose.runtime.remember { mutableStateOf(YearMonth.now().atDay(1).toString()) }
    var to by androidx.compose.runtime.remember { mutableStateOf(LocalDate.now().toString()) }
    val customValid = period != "custom" || runCatching {
        !LocalDate.parse(from).isAfter(LocalDate.parse(to))
    }.getOrDefault(false)
    val periods = listOf("month", "week", "90d", "custom")
    val periodLabel = when (period) {
        "week" -> if (russian) "Неделя" else "Week"
        "90d" -> if (russian) "90 дней" else "90 days"
        "custom" -> if (russian) "Произвольный период" else "Custom period"
        else -> if (russian) "Месяц" else "Month"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(if (russian) "Детали отчёта" else "Report details", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { period = periods[(periods.indexOf(period) + 1) % periods.size] }) {
                Text("$periodLabel ▾")
            }
            TextButton(onClick = { scope = if (scope == "personal") "family" else "personal" }) {
                Text(if (scope == "personal") {
                    if (russian) "Личные" else "Personal"
                } else if (russian) "Семейные" else "Family")
            }
        }
        if (period == "month") {
            OutlinedTextField(month, { month = it }, singleLine = true,
                label = { Text(if (russian) "Месяц, ГГГГ-ММ" else "Month, YYYY-MM") })
        }
        if (period == "custom") {
            OutlinedTextField(from, { from = it }, singleLine = true,
                label = { Text(if (russian) "С даты, ГГГГ-ММ-ДД" else "From, YYYY-MM-DD") })
            OutlinedTextField(to, { to = it }, singleLine = true,
                label = { Text(if (russian) "По дату, ГГГГ-ММ-ДД" else "To, YYYY-MM-DD") })
        }
        Button(onClick = { onLoad(period, scope, month, from, to) },
            enabled = !state.busy && customValid && (period != "month" || month.matches(Regex("^\\d{4}-\\d{2}$")))) {
            Text(if (russian) "Показать отчёт" else "Show report")
        }
        val report = state.report
        if (report == null) {
            Text(if (russian) "Выберите период и загрузите отчёт" else "Choose a period and load a report")
        } else {
            Text(if (russian) "${report.fromDate} — ${report.toDate} · ${report.timezone}" else "${report.fromDate} — ${report.toDate} · ${report.timezone}")
            val categoryMaximum = report.expenseByCategory.values.maxByOrNull {
                it.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            } ?: "0.00"
            val dailyMaximum = report.expenseByDay.values.maxByOrNull {
                it.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            } ?: "0.00"
            val optionalDailyMaximum = report.waste.optionalByDay.values.maxByOrNull {
                it.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            } ?: "0.00"
            LazyColumn(Modifier.weight(1f).testTag("report-results"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (russian) "Доходы: ${formatMoney(report.incomeTotal, language, report.currency)}"
                                else "Income: ${formatMoney(report.incomeTotal, language, report.currency)}")
                            Text(if (russian) "Расходы: ${formatMoney(report.expenseTotal, language, report.currency)}"
                                else "Expenses: ${formatMoney(report.expenseTotal, language, report.currency)}")
                            Text(if (russian) "Платежи по долгам: ${formatMoney(report.debtPaymentTotal, language, report.currency)}"
                                else "Debt payments: ${formatMoney(report.debtPaymentTotal, language, report.currency)}")
                            Text(if (russian) "Возвраты: ${formatMoney(report.refundTotal, language, report.currency)}"
                                else "Refunds: ${formatMoney(report.refundTotal, language, report.currency)}")
                            Text(if (russian) "Операций: ${report.transactionCount}" else "Transactions: ${report.transactionCount}")
                            report.weekendSharePercent?.let {
                                Text(if (russian) "Доля расходов в выходные: $it%" else "Weekend expense share: $it%")
                            }
                            report.monthlyBudgetLimit?.let { limit ->
                                Text(if (russian) "Лимит месяца: ${formatMoney(limit, language, report.currency)} · остаток " +
                                    formatMoney(report.monthlyBudgetRemaining, language, report.currency)
                                else "Monthly budget: ${formatMoney(limit, language, report.currency)} · remaining " +
                                    formatMoney(report.monthlyBudgetRemaining, language, report.currency))
                            }
                            val food = report.rolling7FoodStatus
                            Text(if (russian) "Еда за 7 дней: ${formatMoney(food.spent, language, report.currency)} / " +
                                "${formatMoney(food.limit, language, report.currency)} · " +
                                formatSemanticStatus("paceStatus", food.paceStatus, language)
                            else "Food over 7 days: ${formatMoney(food.spent, language, report.currency)} / " +
                                "${formatMoney(food.limit, language, report.currency)} · " +
                                formatSemanticStatus("paceStatus", food.paceStatus, language))
                        }
                    }
                }
                item {
                    val waste = report.waste
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (russian) "Необязательные покупки" else "Optional purchases",
                                style = MaterialTheme.typography.titleMedium)
                            if (!waste.available) {
                                Text(wasteUnavailableMessage(waste, russian))
                            } else {
                                val rawShare = waste.optionalShare?.toBigDecimalOrNull()?.multiply(java.math.BigDecimal(100))
                                    ?.setScale(1, java.math.RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()
                                val share = rawShare?.let { if (russian) it.replace('.', ',') else it } ?: "—"
                                val optional = formatMoney(waste.optionalSpend, language, report.currency)
                                val reviewed = formatMoney(waste.reviewedSpend, language, report.currency)
                                Text(if (russian) "Необязательные покупки: $optional · $share% от проверенных $reviewed"
                                else "Optional purchases: $optional · $share% of reviewed $reviewed")
                                Text(if (russian) "Проверено: ${waste.reviewedItemCount} · необязательных: ${waste.optionalItemCount}"
                                else "Reviewed: ${waste.reviewedItemCount} · optional: ${waste.optionalItemCount}")
                                waste.bySource.toSortedMap().forEach { (source, amount) ->
                                    Text(if (russian) "Источник $source: ${formatMoney(amount, language, report.currency)}"
                                        else "Source $source: ${formatMoney(amount, language, report.currency)}")
                                }
                                waste.topItems.forEach { item ->
                                    Text("${item.name}: ${formatMoney(item.amount, language, report.currency)} · ${item.source}")
                                }
                                waste.corrected.forEach { item ->
                                    Text(if (russian) "Исправлено: ${item.productName} · ${formatMoney(item.amount, language, report.currency)}"
                                        else "Corrected: ${item.productName} · ${formatMoney(item.amount, language, report.currency)}")
                                }
                            }
                        }
                    }
                }
                items(report.expenseByCategory.toSortedMap().entries.toList()) { entry ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${entry.key}: ${formatMoney(entry.value, language, report.currency)}")
                        LinearProgressIndicator(progress = { amountFraction(entry.value, categoryMaximum) },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                item {
                    Text(if (russian) "Расходы по дням" else "Expenses by day",
                        style = MaterialTheme.typography.titleMedium)
                }
                items(report.expenseByDay.toSortedMap().entries.toList()) { entry ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${entry.key}: ${formatMoney(entry.value, language, report.currency)}")
                        LinearProgressIndicator(progress = { amountFraction(entry.value, dailyMaximum) },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                if (report.waste.available && report.waste.optionalByDay.isNotEmpty()) {
                    item {
                        Text(if (russian) "Необязательные покупки по дням" else "Optional purchases by day",
                            style = MaterialTheme.typography.titleMedium)
                    }
                    items(report.waste.optionalByDay.toSortedMap().entries.toList()) { entry ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${entry.key}: ${formatMoney(entry.value, language, report.currency)}")
                            LinearProgressIndicator(progress = { amountFraction(entry.value, optionalDailyMaximum) },
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

private fun wasteUnavailableMessage(waste: FinanceWasteReport, russian: Boolean): String = when (waste.reasonCode) {
    "missing_amounts" -> if (russian) "Не все позиции чеков имеют сумму (${waste.missingAmountCount}); итоги не рассчитаны."
        else "Some receipt items have no amount (${waste.missingAmountCount}); totals not calculated."
    "no_reviewed_items" -> if (russian) "Нет проверенных позиций чеков за период." else "No reviewed receipt items for this period."
    "too_many_items" -> if (russian) "Слишком много позиций чеков для анализа." else "Too many receipt items to analyze."
    else -> if (russian) "Аналитика временно недоступна." else "Analytics is temporarily unavailable."
}

private fun amountFraction(value: String, maximum: String): Float {
    val current = value.toBigDecimalOrNull() ?: return 0f
    val limit = maximum.toBigDecimalOrNull() ?: return 0f
    if (limit <= java.math.BigDecimal.ZERO || current <= java.math.BigDecimal.ZERO) return 0f
    return current.divide(limit, 6, java.math.RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
}

private fun normalizedIncomeInput(value: String): String? {
    val normalized = value.trim().replace(',', '.').takeIf(String::isNotBlank) ?: return null
    return normalized.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
}

@androidx.compose.runtime.Composable
private fun BudgetScreen(state: FinanceUiState, language: String,
                         onUpdate: (String, String, String, String, Long) -> Unit,
                         onReset: () -> Unit, onPropose: (String?) -> Unit, onApply: (String) -> Unit) {
    val russian = language == "ru"
    val budget = state.budgets
    val tenant = state.tenants.firstOrNull()
    val canEditFamily = tenant?.role in listOf("owner", "admin")
    val canEditPersonal = tenant?.role != "viewer"
    var scope by androidx.compose.runtime.remember { mutableStateOf("family") }
    var period by androidx.compose.runtime.remember { mutableStateOf("monthly") }
    var key by androidx.compose.runtime.remember { mutableStateOf("__total__") }
    var amount by androidx.compose.runtime.remember { mutableStateOf("") }
    var income by androidx.compose.runtime.remember { mutableStateOf("") }
    val canEdit = if (scope == "family") canEditFamily else canEditPersonal

    if (budget == null) {
        Text(if (russian) "Бюджет пока не загружен" else "Budget is not loaded")
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (russian) "Лимиты · ${budget.month}" else "Limits · ${budget.month}", style = MaterialTheme.typography.titleLarge)
        Text(if (russian) "Расход еды за 7 дней: " +
            "${formatMoney(budget.rolling7FoodStatus.spent, language)} / " +
            "${formatMoney(budget.rolling7FoodStatus.limit, language)} · " +
            formatSemanticStatus("paceStatus", budget.rolling7FoodStatus.paceStatus, language)
            else "Food over 7 days: " +
                "${formatMoney(budget.rolling7FoodStatus.spent, language)} / " +
                "${formatMoney(budget.rolling7FoodStatus.limit, language)} · " +
                formatSemanticStatus("paceStatus", budget.rolling7FoodStatus.paceStatus, language))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { scope = "family" }) { Text(if (russian) "Семейный" else "Family") }
            TextButton(onClick = { scope = "personal" }) { Text(if (russian) "Личный" else "Personal") }
            TextButton(onClick = { period = "monthly" }) { Text(if (russian) "Месяц" else "Monthly") }
            TextButton(onClick = { period = "rolling7"; key = "еда" }) { Text(if (russian) "7 дней" else "7 days") }
        }
        OutlinedTextField(key, { key = it }, enabled = period == "monthly",
            label = { Text(if (russian) "Категория или __total__" else "Category or __total__") })
        OutlinedTextField(amount, { amount = it }, enabled = canEdit,
            label = { Text(if (russian) "Лимит, ₽" else "Limit, RUB") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val version = when {
                    period == "rolling7" && scope == "personal" -> budget.personalRolling7FoodVersion
                    period == "rolling7" -> budget.familyRolling7FoodVersion
                    key == "__total__" && scope == "personal" -> budget.personalTotalVersion
                    key == "__total__" -> budget.familyTotalVersion
                    scope == "personal" -> budget.personalVersions[key] ?: 0L
                    else -> budget.familyVersions[key] ?: 0L
                }
                onUpdate(key, scope, amount, period, version)
            }, enabled = canEdit && !state.busy && amount.matches(Regex("^\\d{1,8}(?:[.,]\\d{1,2})?$"))
                    && (period != "rolling7" || key == "еда")) {
                Text(if (russian) "Сохранить лимит" else "Save limit")
            }
            if (scope == "personal") TextButton(onClick = onReset, enabled = !state.busy && canEditPersonal) {
                Text(if (russian) "Сбросить личные" else "Reset personal")
            }
        }
        if (!canEdit) Text(if (russian) "У вас нет прав изменять этот бюджет" else "You cannot change this budget")
        Text(if (russian) "Предложение лимитов" else "Budget proposal", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(income, { income = it }, enabled = canEditFamily,
            label = { Text(if (russian) "Доход в месяц, ₽" else "Monthly income, RUB") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onPropose(income.trim().takeIf(String::isNotEmpty)) },
                enabled = canEditFamily && !state.busy && (income.isBlank() || income.matches(Regex("^\\d{1,8}(?:[.,]\\d{1,2})?$")))) {
                Text(if (income.isBlank()) {
                    if (russian) "Предложить по истории" else "Suggest from history"
                } else if (russian) "Рассчитать по доходу" else "Suggest from income")
            }
            state.budgetProposal?.takeIf { it.status == "pending" }?.let { proposal ->
                Button(onClick = { onApply(proposal.id) }, enabled = canEditFamily && !state.busy) {
                    Text(if (russian) "Применить" else "Apply")
                }
            }
        }
        state.budgetProposal?.let { proposal ->
            Text(if (russian) "Предложен лимит ${formatMoney(proposal.totalLimit, language)} · ${proposal.proposalSource} · ${proposal.historyDays} дн."
            else "Suggested total ${formatMoney(proposal.totalLimit, language)} · ${proposal.proposalSource} · ${proposal.historyDays} days")
        }
        Text(if (russian) "Лимит за месяц: ${formatMoney(budget.effectiveTotalLimit, language)} · потрачено " +
            "${formatMoney(budget.totalMonthlySpent, language)} · " +
            formatSemanticStatus("limitStatus", budget.totalLimitStatus, language)
            else "Monthly limit: ${formatMoney(budget.effectiveTotalLimit, language)} · spent " +
                "${formatMoney(budget.totalMonthlySpent, language)} · " +
                formatSemanticStatus("limitStatus", budget.totalLimitStatus, language))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(budget.effectiveLimits.toSortedMap().entries.toList()) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(entry.key, style = MaterialTheme.typography.titleMedium)
                        val status = budget.limitStatus[entry.key] ?: "disabled"
                        Text(if (russian) "Действует ${formatMoney(entry.value, language)} · потрачено " +
                            "${formatMoney(budget.monthlySpent[entry.key] ?: "0.00", language)} · " +
                            formatSemanticStatus("limitStatus", status, language)
                            else "Effective ${formatMoney(entry.value, language)} · spent " +
                                "${formatMoney(budget.monthlySpent[entry.key] ?: "0.00", language)} · " +
                                formatSemanticStatus("limitStatus", status, language))
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DebtScreen(state: FinanceUiState, language: String,
                       onCreate: (String, String, String?, String) -> Unit,
                       onPay: (String, String, Long) -> Unit,
                       onAdjust: (String, String, Long) -> Unit,
                       onForecast: (String) -> Unit) {
    val russian = language == "ru"
    val canManage = state.tenants.firstOrNull()?.role != "viewer"
    var name by androidx.compose.runtime.remember { mutableStateOf("") }
    var openingBalance by androidx.compose.runtime.remember { mutableStateOf("") }
    var interestRate by androidx.compose.runtime.remember { mutableStateOf("") }
    var minimumPayment by androidx.compose.runtime.remember { mutableStateOf("") }
    var paymentAmount by androidx.compose.runtime.remember { mutableStateOf("") }
    var adjustedBalance by androidx.compose.runtime.remember { mutableStateOf("") }
    var addingDebt by androidx.compose.runtime.remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (russian) "Долги" else "Debts", style = MaterialTheme.typography.titleLarge)
        if (canManage) {
            TextButton(onClick = { addingDebt = !addingDebt }) {
                Text(if (addingDebt) {
                    if (russian) "Отмена" else "Cancel"
                } else if (russian) "Создать долг" else "Create debt")
            }
            if (addingDebt) {
                OutlinedTextField(name, { name = it }, label = { Text(if (russian) "Название" else "Name") })
                OutlinedTextField(openingBalance, { openingBalance = it }, label = { Text(if (russian) "Начальный остаток, ₽" else "Opening balance, RUB") })
                OutlinedTextField(interestRate, { interestRate = it }, label = { Text(if (russian) "Ставка, % (необязательно)" else "Interest rate, % (optional)") })
                OutlinedTextField(minimumPayment, { minimumPayment = it }, label = { Text(if (russian) "Минимальный платёж, ₽" else "Minimum payment, RUB") })
                Button(onClick = {
                    onCreate(name, openingBalance, interestRate.takeIf(String::isNotBlank), minimumPayment)
                    addingDebt = false
                }, enabled = !state.busy && name.isNotBlank() && openingBalance.isNotBlank() && minimumPayment.isNotBlank()) {
                    Text(if (russian) "Сохранить долг" else "Save debt")
                }
            }
        } else Text(if (russian) "Просмотр только для чтения" else "Read-only access")
        if (state.debts.isEmpty()) Text(if (russian) "Долгов пока нет" else "No debts yet")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.debts) { debt ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(debt.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (russian) "Остаток ${debt.currentBalance} ₽ · ${if (debt.status == "open") "открыт" else "закрыт"} · версия ${debt.version}"
                        else "Balance ${debt.currentBalance} RUB · ${debt.status} · version ${debt.version}")
                        if (canManage && debt.status == "open") {
                            OutlinedTextField(paymentAmount, { paymentAmount = it }, label = { Text(if (russian) "Платёж, ₽" else "Payment, RUB") })
                            Button(onClick = { onPay(debt.id, paymentAmount, debt.version) }, enabled = !state.busy && paymentAmount.isNotBlank()) {
                                Text(if (russian) "Записать платёж" else "Record payment")
                            }
                            OutlinedTextField(adjustedBalance, { adjustedBalance = it }, label = { Text(if (russian) "Исправить остаток, ₽" else "Adjust balance, RUB") })
                            Button(onClick = { onAdjust(debt.id, adjustedBalance, debt.version) }, enabled = !state.busy && adjustedBalance.isNotBlank()) {
                                Text(if (russian) "Сохранить остаток" else "Save balance")
                            }
                        }
                        Button(onClick = { onForecast(debt.id) }, enabled = !state.busy) {
                            Text(if (russian) "Прогноз выплаты" else "Payoff forecast")
                        }
                        state.debtForecasts[debt.id]?.let { forecast ->
                            Text(if (russian) "${forecast.monthsToPayoff?.let { "$it мес." } ?: "Срок не рассчитан"} · ${forecast.estimateBasis}"
                            else "${forecast.monthsToPayoff?.let { "$it months" } ?: "No estimate"} · ${forecast.estimateBasis}")
                        }
                    }
                }
            }
        }
    }
}
