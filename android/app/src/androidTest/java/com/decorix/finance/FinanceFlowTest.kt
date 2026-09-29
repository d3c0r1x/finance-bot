package com.decorix.finance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun awaitText(text: String) {
        compose.waitUntil(20000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun registerAddTransactionSwitchAppearanceAndRestoreSession() {
        val username = "emu" + System.currentTimeMillis().toString().takeLast(10)
        compose.onNodeWithText("EN").performScrollTo().performClick()
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.onNodeWithText("Name", substring = false).performScrollTo().performTextInput("Emulator")
        compose.onNodeWithText("Username", substring = false).performScrollTo().performTextInput(username)
        compose.onNodeWithText("Password", substring = false).performScrollTo().performTextInput("Emulator-test-123")
        compose.onNodeWithText("Repeat password", substring = false).performScrollTo().performTextInput("Emulator-test-123")
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        awaitText("Let’s set up your finances")
        compose.onNodeWithText("Start").performScrollTo().performClick()
        awaitText("Recent transactions")
        compose.onNodeWithText("Add", substring = false).performClick()
        compose.onNodeWithText("Amount, ₽").performScrollTo().performTextInput("1250")
        compose.onNodeWithText("Description").performScrollTo().performTextInput("Emulator groceries")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        awaitText("Recent transactions")
        compose.onNodeWithText("Emulator groceries").performScrollTo().assertExists().performClick()
        awaitText("Repeat")
        compose.onNodeWithText("Repeat").performScrollTo().performClick()
        awaitText("History")
        compose.waitUntil(10000) { compose.onAllNodesWithText("Emulator groceries").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("Profile", substring = false).performClick()
        awaitText("Dark")
        compose.onNodeWithText("Dark").performScrollTo().performClick()
        compose.onNodeWithText("Русский").performScrollTo().performClick()
        awaitText("Список покупок")
        compose.onNodeWithText("English").performScrollTo().performClick()
        awaitText("Light")
        compose.onNodeWithText("Light").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        awaitText("Profile")
        compose.onAllNodesWithText("Home").onFirst().performClick()
        awaitText("Recent transactions")
        compose.onAllNodesWithText("Emulator groceries").onFirst().performScrollTo().assertExists()
    }
}
