package com.decorix.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceGoalsScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun writerChoosesCountOrSumAndSavesOnlyAfterExplicitAction() {
        val saved = mutableListOf<String>()
        val selected = mutableStateOf("count")
        compose.setContent {
            MaterialTheme {
                GoalsSection(
                    role = "owner", language = "ru", overview = overview(), busy = false, error = null,
                    selectedUnit = selected.value, onSelectedUnit = { selected.value = it },
                    onRefresh = {}, onSaveUnit = saved::add, onAccept = { _, _ -> }, onCancel = {},
                )
            }
        }

        compose.onNodeWithText("Формат цели").assertIsDisplayed()
        compose.onNodeWithText("Сумма").performClick()
        assertEquals(emptyList<String>(), saved)
        compose.onNodeWithText("Сохранить формат").assertIsEnabled().performClick()
        assertEquals(listOf("sum"), saved)
    }

    @Test fun proposalsShowServerOwnedValuesAndKeepUnknownMoneyUnknown() {
        compose.setContent {
            MaterialTheme {
                GoalsSection(
                    role = "owner", language = "ru", overview = overview(), busy = false, error = null,
                    selectedUnit = "count", onSelectedUnit = {}, onRefresh = {}, onSaveUnit = {},
                    onAccept = { _, _ -> }, onCancel = {},
                )
            }
        }

        compose.onNodeWithText("Чипсы").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Категории").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сладкое").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сок").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Предложения").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Не хватает сумм в чеках").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Оценка расходов за месяц: 1 234,56 ₽").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Оценка сокращения расходов: —").assertCountEquals(2)
        compose.onAllNodesWithText("Оценка расходов за месяц: —").assertCountEquals(2)
        compose.onAllNodesWithText("0,00 ₽").assertCountEquals(0)
    }

    @Test fun acceptanceIsExplicitAndUsesTheExactCandidateKeyAndWatermark() {
        val accepted = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MaterialTheme {
                GoalsSection(
                    role = "owner", language = "ru", overview = overview(), busy = false, error = null,
                    selectedUnit = "count", onSelectedUnit = {}, onRefresh = {}, onSaveUnit = {},
                    onAccept = { key, watermark -> accepted += key to watermark }, onCancel = {},
                )
            }
        }

        assertEquals(emptyList<Pair<String, String>>(), accepted)
        compose.onNodeWithText("Поставить цель: Чипсы").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("chips" to "17"), accepted)
    }

    @Test fun onlyOneGoalCanBeAcceptedAndActiveGoalRetainsItsThirtyDayTerms() {
        val cancelled = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                GoalsSection(
                    role = "owner", language = "ru", overview = overview(active = activeGoal()), busy = false,
                    error = null, selectedUnit = "sum", onSelectedUnit = {}, onRefresh = {}, onSaveUnit = {},
                    onAccept = { _, _ -> error("an active goal must block acceptance") }, onCancel = cancelled::add,
                )
            }
        }

        compose.onNodeWithText("Цель активна").assertIsDisplayed()
        compose.onAllNodesWithText("2 покупки в месяц").assertCountEquals(2)
        compose.onNodeWithText("Принята: 7 окт. 2026 г. · до 6 нояб. 2026 г.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Поставить цель: Чипсы").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Отменить цель").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Цель будет остановлена. Продолжить?").assertIsDisplayed()
        compose.onNodeWithText("Продолжить").performClick()
        assertEquals(listOf(activeGoal().id), cancelled)
    }

    @Test fun viewerCanInspectProposalsButCannotChangeUnitAcceptOrCancel() {
        compose.setContent {
            MaterialTheme {
                GoalsSection(
                    role = "viewer", language = "en", overview = overview(active = activeGoal()), busy = false,
                    error = null, selectedUnit = "count", onSelectedUnit = {}, onRefresh = {}, onSaveUnit = {},
                    onAccept = { _, _ -> error("viewer must not accept") }, onCancel = { error("viewer must not cancel") },
                )
            }
        }

        compose.onNodeWithText("Monthly goal").assertIsDisplayed()
        compose.onNodeWithText("Product").performScrollTo().assertIsDisplayed()
        // Core keeps product names in the source language; both the active goal and
        // its matching proposal are visible in the tree, so target their unique
        // section labels instead of an ambiguous name selector.
        compose.onAllNodesWithText("Чипсы").assertCountEquals(2)
        compose.onNodeWithText("Goal is active").assertIsDisplayed()
        compose.onAllNodesWithText("Save measure").assertCountEquals(0)
        compose.onAllNodesWithText("Set goal: Chips").assertCountEquals(0)
        compose.onAllNodesWithText("Cancel goal").assertCountEquals(0)
        compose.onNodeWithText("Categories").performScrollTo().assertIsDisplayed()
    }

    @Test fun staleCandidateRequiresRefreshingServerProposalsBeforeRetry() {
        var refreshes = 0
        val language = mutableStateOf("ru")
        compose.setContent {
            MaterialTheme {
                GoalsSection(
                    role = "owner", language = language.value, overview = overview(), busy = false,
                    error = "stale_candidate", selectedUnit = "count", onSelectedUnit = {},
                    onRefresh = { refreshes++ }, onSaveUnit = {}, onAccept = { _, _ -> }, onCancel = {},
                )
            }
        }

        compose.onNodeWithText("Данные изменились. Обновите предложения и выберите цель заново.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Обновить предложения").performScrollTo().performClick()
        assertEquals(1, refreshes)
        compose.runOnIdle { language.value = "en" }
        compose.onNodeWithText("Data changed. Refresh suggestions and choose again.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun saveAndAcceptControlsStayDisabledWhileWriteIsRunning() {
        compose.setContent {
            MaterialTheme {
                GoalsSection(
                    role = "owner", language = "en", overview = overview(), busy = true, error = null,
                    selectedUnit = "sum", onSelectedUnit = {}, onRefresh = {}, onSaveUnit = {},
                    onAccept = { _, _ -> }, onCancel = {},
                )
            }
        }

        compose.onNodeWithText("Save measure").assertIsNotEnabled()
        compose.onNodeWithText("Set goal: Чипсы").performScrollTo().assertIsNotEnabled()
    }

    private fun show(
        role: String = "owner",
        language: String = "ru",
        overview: FinanceGoalOverview = overview(),
        busy: Boolean = false,
        error: String? = null,
        onRefresh: () -> Unit = {},
        onSaveUnit: (String) -> Unit = {},
        onAccept: (String, String) -> Unit = { _, _ -> },
        onCancel: (String) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                GoalsSection(
                    role = role, language = language, overview = overview, busy = busy, error = error,
                    selectedUnit = overview.unit, onSelectedUnit = {}, onRefresh = onRefresh,
                    onSaveUnit = onSaveUnit, onAccept = onAccept, onCancel = onCancel,
                )
            }
        }
    }

    private fun overview(active: FinanceMemberGoal? = null) = FinanceGoalOverview(
        unit = "count", active = active, inputWatermark = "17",
        candidates = listOf(candidate()), groups = listOf(candidate(
            key = "cat:sweets", productKey = null, name = "Сладкое", monthlyRate = "3.00",
            countTarget = 1, monthlySpend = null, monthlyLimit = null, estimatedReduction = null,
            purchaseCount = 3, evidenceCount = 5, memberProductKeys = listOf("candy", "chocolate"),
        )),
        skipped = listOf(FinanceGoalSkipped("juice", "Сок", null, "missing_amounts")),
        activeProgress = null, history = emptyList(),
    )

    private fun candidate(
        key: String = "chips",
        productKey: String? = "chips",
        name: String = "Чипсы",
        monthlyRate: String = "4.00",
        countTarget: Int = 2,
        monthlySpend: String? = "1234.56",
        monthlyLimit: String? = null,
        estimatedReduction: String? = null,
        purchaseCount: Int = 4,
        evidenceCount: Int = 4,
        memberProductKeys: List<String>? = null,
    ) = FinanceGoalCandidate(
        key = key, productKey = productKey, name = name, unit = "count", monthlyRate = monthlyRate,
        countTarget = countTarget, monthlySpend = monthlySpend, monthlyLimit = monthlyLimit,
        estimatedReduction = estimatedReduction, purchaseCount = purchaseCount, evidenceCount = evidenceCount,
        memberProductKeys = memberProductKeys,
    )

    private fun activeGoal() = FinanceMemberGoal(
        id = "c80fc08b-8f86-4956-bdca-1e2646f3f514", key = "chips", scope = "product", name = "Чипсы",
        unit = "count", monthlyRate = "4.00", countTarget = 2, monthlySpend = null, monthlyLimit = null,
        evidenceCount = 4, inputWatermark = "17", acceptedAt = "2026-10-07T10:00:00Z",
        endsAt = "2026-11-06T10:00:00Z", status = "active", version = 1,
    )
}
