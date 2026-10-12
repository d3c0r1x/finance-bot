package com.decorix.finance

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FinanceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun unauthenticatedUserSeesOidcSignIn() {
        compose.onNodeWithText("Войти").assertIsDisplayed()
    }

    @Test fun languageCanSwitchToEnglish() {
        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Sign in").assertIsDisplayed()
    }

    @Test fun mainActivityHandoffPassesOnlyValidatedCsvUrlToChooser() {
        val launched = CopyOnWriteArrayList<Intent>()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.openExportDownload(DOWNLOAD_URL, launched::add)
        }

        assertEquals(1, launched.size)
        val chooser = launched.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val view = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_VIEW, view?.action)
        assertEquals(Uri.parse(DOWNLOAD_URL), view?.data)
        assertNull(view?.extras)
    }

    @Test fun mainActivityDoesNotLaunchChooserForUnsafeCsvUrl() {
        val launched = CopyOnWriteArrayList<Intent>()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.openExportDownload("http://files.example.test/export.csv", launched::add)
        }

        assertTrue(launched.isEmpty())
    }

    @Test fun delayedReadyStatusAfterTenantSwitchCannotPublishOrLaunchAndCannotClearNewBusyOwner() {
        assertDelayedReadyResponseRejectedAfterIdentitySwitch(switchSession = false)
    }

    @Test fun delayedReadyStatusAfterSessionSwitchCannotPublishOrLaunchAndCannotClearNewBusyOwner() {
        assertDelayedReadyResponseRejectedAfterIdentitySwitch(switchSession = true)
    }

    @Test fun tokenVaultEncryptsAndClearsSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = TokenVault(context)
        val token = "synthetic-access-token-for-test"
        vault.save(org.json.JSONObject().put("accessToken", token).put("refreshToken", "synthetic-refresh-token"))
        assertEquals(token, vault.read()?.getString("accessToken"))
        val ciphertext = context.getSharedPreferences("finance_session", 0).getString("ciphertext", "")
        assertFalse(ciphertext!!.contains(token))
        vault.clear()
        assertNull(vault.read())
    }

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000061"
        const val OTHER_TENANT_ID = "00000000-0000-4000-8000-000000000062"
        const val DOWNLOAD_URL = "https://files.example.test/private/export.csv?signature=short-lived"
        const val EXPORT_ID = "00000000-0000-4000-8000-000000000063"
    }

    private fun assertDelayedReadyResponseRejectedAfterIdentitySwitch(switchSession: Boolean) {
        val activity = compose.activity
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completionBarrier = CountDownLatch(1)
        val launched = CopyOnWriteArrayList<Intent>()
        val firstTenant = tenant(TENANT_ID)
        val replacementTenant = if (switchSession) firstTenant else tenant(OTHER_TENANT_ID)
        val oldJob = exportJob(EXPORT_ID)
        val replacementJob = exportJob(EXPORT_ID, status = "processing", rows = 2, downloadUrl = null)
        val observedRequest = arrayOfNulls<Any>(3)
        var originalSessionGeneration = -1L

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            originalSessionGeneration = activity.authSessionGenerationForExportTest()
            activity.prepareExportStatusIntegrationTest(firstTenant, oldJob)
            activity.exportStatusCallForTesting = { tenantId, requestedId, sessionGeneration ->
                observedRequest[0] = tenantId
                observedRequest[1] = requestedId
                observedRequest[2] = sessionGeneration
                firstStarted.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
                exportJob(requestedId)
            }
            activity.exportDownloadLaunchForTesting = launched::add
            activity.requestExportDownloadForTesting(EXPORT_ID)
        }

        assertTrue("first export status request did not start", firstStarted.await(5, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (switchSession) activity.clearExportSessionForTesting()
            activity.prepareExportStatusIntegrationTest(replacementTenant, replacementJob, busyOwner = "create")
            activity.enqueueExportStatusTestBarrier(completionBarrier::countDown)
        }
        assertEquals(TENANT_ID, observedRequest[0])
        assertEquals(EXPORT_ID, observedRequest[1])
        assertEquals(originalSessionGeneration, observedRequest[2])
        val capturedSessionGeneration = observedRequest[2] as Long
        releaseFirst.countDown()
        assertTrue("delayed status completion did not finish", completionBarrier.await(5, TimeUnit.SECONDS))
        if (switchSession) assertTrue(activity.authSessionGenerationForExportTest() > capturedSessionGeneration)
        else assertEquals(capturedSessionGeneration, activity.authSessionGenerationForExportTest())

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertEquals(replacementJob, activity.exportStatusUiForTesting().exportJob)
            assertEquals(replacementTenant.id, activity.exportStatusUiForTesting().tenants.first().id)
            assertTrue("stale response cleared the replacement operation's busy state",
                activity.exportStatusUiForTesting().exportBusy)
            assertTrue("stale response launched an export chooser", launched.isEmpty())
        }
    }

    private fun tenant(id: String) = FinanceTenant(id, "Synthetic household", "owner", "UTC", "synthetic-user")

    private fun exportJob(id: String, status: String = "ready", rows: Int = 1, downloadUrl: String? = DOWNLOAD_URL) = FinanceExportJob(
        id = id, status = status, formatVersion = "csv-v1", fromDate = "2026-10-01", toDate = "2026-10-02",
        memberId = null, allMembers = true, rowCount = rows, snapshotAt = "2026-10-12T00:00:00Z",
        createdAt = "2026-10-12T00:00:00Z", expiresAt = "2026-10-12T00:05:00Z", downloadUrl = downloadUrl,
    )
}
