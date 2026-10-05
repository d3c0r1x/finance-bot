package com.decorix.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
