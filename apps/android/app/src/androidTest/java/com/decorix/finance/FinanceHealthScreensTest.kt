package com.decorix.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceHealthScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun capabilityStatusLocalizesAvailableUnavailableAndDisabledStatesInRussianAndEnglish() {
        val status = FinanceHealthStatus(mapOf(
            "localAi" to FinanceHealthCapability("available", null),
            "receiptVision" to FinanceHealthCapability("unavailable", "MODEL_MISSING"),
            "receiptOcr" to FinanceHealthCapability("disabled", "TESSERACT_MISSING"),
        ))
        val language = mutableStateOf("ru")
        compose.setContent {
            MaterialTheme {
                FinanceHealthSection(status, loading = false, error = null, language = language.value,
                    onRefresh = { })
            }
        }

        compose.onNodeWithText("Состояние сервисов").assertIsDisplayed()
        compose.onNodeWithText("Локальный AI").assertIsDisplayed()
        compose.onNodeWithText("Доступен").assertIsDisplayed()
        compose.onNodeWithText("Проверка Vision чеков").assertIsDisplayed()
        compose.onNodeWithText("Недоступен").assertIsDisplayed()
        compose.onNodeWithText("Модель не найдена").assertIsDisplayed()
        compose.onNodeWithText("OCR чеков").assertIsDisplayed()
        compose.onNodeWithText("Отключён").assertIsDisplayed()

        language.value = "en"
        compose.onNodeWithText("Service status").assertIsDisplayed()
        compose.onNodeWithText("Local AI").assertIsDisplayed()
        compose.onNodeWithText("Available").assertIsDisplayed()
        compose.onNodeWithText("Receipt vision").assertIsDisplayed()
        compose.onNodeWithText("Unavailable").assertIsDisplayed()
        compose.onNodeWithText("Model is missing").assertIsDisplayed()
        compose.onNodeWithText("Receipt OCR").assertIsDisplayed()
        compose.onNodeWithText("Disabled").assertIsDisplayed()
    }

    @Test fun unavailableScreenShowsRetryAndNeverRendersRawProviderDetails() {
        var refreshes = 0
        compose.setContent {
            MaterialTheme {
                FinanceHealthSection(status = null, loading = false, error = "OLLAMA_URL=https://private.invalid local-path",
                    language = "en", onRefresh = { refreshes++ })
            }
        }

        compose.onNodeWithText("Service status is unavailable").assertIsDisplayed()
        compose.onNodeWithText("Refresh status").assertIsEnabled().performClick()
        assertEquals(1, refreshes)
        compose.onAllNodesWithText("OLLAMA_URL=https://private.invalid local-path", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test fun loadingScreenExplainsCheckInProgressAndKeepsManualRefreshAvailable() {
        var refreshes = 0
        compose.setContent {
            MaterialTheme {
                FinanceHealthSection(status = null, loading = true, error = null, language = "ru",
                    onRefresh = { refreshes++ })
            }
        }

        compose.onNodeWithText("Проверяем доступность сервисов…").assertIsDisplayed()
        compose.onNodeWithText("Проверяем…").assertIsEnabled().performClick()
        assertEquals(1, refreshes)
    }
}
