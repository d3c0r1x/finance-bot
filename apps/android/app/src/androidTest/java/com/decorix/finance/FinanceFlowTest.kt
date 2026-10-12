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
        val launched = mutableListOf<Intent>()

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
        val launched = mutableListOf<Intent>()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.openExportDownload("http://files.example.test/export.csv", launched::add)
        }

        assertTrue(launched.isEmpty())
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
        const val DOWNLOAD_URL = "https://files.example.test/private/export.csv?signature=short-lived"
    }
}
