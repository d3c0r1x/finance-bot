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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceGoalsScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun countProgressRendersCorePurchaseAndSpendValuesInEnglish() {
        show(language = "en", overview = overview(active = activeGoal()).copy(
            activeProgress = progress(unit = "count", bought = 1, spent = "45.67",
                amountsUnknown = false, over = false, met = false),
        ))

        compose.onNodeWithText("Goal progress").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirmed purchases: 1 of 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Spent toward goal: 45.67 RUB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("20 days left").performScrollTo().assertIsDisplayed()
    }

    @Test fun countProgressFormatsKnownSpendInRussianWithoutRecalculation() {
        show(overview = overview(active = activeGoal()).copy(
            activeProgress = progress(unit = "count", bought = 1, spent = "45.67",
                amountsUnknown = false, over = false, met = true),
        ))

        compose.onNodeWithText("Подтверждённые покупки: 1 из 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Потрачено за цель: 45,67 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Пока укладываетесь в цель.").performScrollTo().assertIsDisplayed()
    }

    @Test fun unknownSumProgressDoesNotInventSpendOrVerdictInRussian() {
        show(overview = overview(active = activeGoal().copy(unit = "sum", monthlyLimit = "100.00")).copy(
            activeProgress = progress(unit = "sum", bought = 3, spent = null,
                amountsUnknown = true, over = null, met = null),
        ))

        compose.onNodeWithText("Ход цели").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Потрачено за цель: — из 100,00 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Суммы чеков неизвестны — итог по деньгам не вычисляю.")
            .performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Пока укладываетесь в цель.").assertCountEquals(0)
        compose.onAllNodesWithText("Лимит уже превышен.").assertCountEquals(0)
        compose.onAllNodesWithText("0,00 ₽").assertCountEquals(0)
    }

    @Test fun historyPreservesCoreOrderAndShowsLegacyUnknownOutcome() {
        val completed = outcome(id = "recent", name = "Первый товар", spent = "12.30", met = true)
        val legacy = outcome(id = "legacy", goalId = null, name = "Старый товар", spent = null,
            met = null, origin = "legacy")
        show(overview = overview().copy(history = listOf(completed, legacy)))

        compose.onNodeWithText("История целей").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Первый товар").assertExists()
        compose.onNodeWithText("Старый товар").assertExists()
        val firstTop = compose.onNodeWithText("Первый товар").fetchSemanticsNode().boundsInRoot.top
        val legacyTop = compose.onNodeWithText("Старый товар").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Core newest-first outcome order must stay unchanged", firstTop < legacyTop)
        compose.onNodeWithText("Итог по деньгам неизвестен").performScrollTo().assertIsDisplayed()
    }

    @Test fun returnedCandidateAfterCompletionIsAvailableButNeverAutoAccepted() {
        val accepted = mutableListOf<Pair<String, String>>()
        show(overview = overview().copy(
            active = null,
            activeProgress = null,
            history = listOf(outcome(id = "done", name = "Чипсы", spent = "88.00", met = false)),
        ), onAccept = { key, watermark -> accepted += key to watermark })

        assertEquals(emptyList<Pair<String, String>>(), accepted)
        compose.onNodeWithText("Поставить цель: Чипсы").performScrollTo().assertIsEnabled()
        assertEquals("Displaying a candidate must not accept it", emptyList<Pair<String, String>>(), accepted)
    }

    @Test fun viewerCanReadProgressAndHistoryWithoutGoalActions() {
        show(role = "viewer", overview = overview(active = activeGoal()).copy(
            activeProgress = progress(unit = "count", bought = 1, spent = null,
                amountsUnknown = true, over = null, met = null),
            history = listOf(outcome(id = "legacy", goalId = null, name = "Архивная цель",
                spent = null, met = null, origin = "legacy")),
        ), onAccept = { _, _ -> error("viewer cannot accept") })

        compose.onNodeWithText("Подтверждённые покупки: 1 из 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Архивная цель").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Отменить цель").assertCountEquals(0)
        compose.onAllNodesWithText("Поставить цель: Чипсы").assertCountEquals(0)
    }

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

    private fun progress(
        unit: String,
        bought: Int,
        spent: String?,
        amountsUnknown: Boolean,
        over: Boolean?,
        met: Boolean?,
    ) = FinanceGoalProgress(
        algorithmVersion = "goal-progress-f45.v1", inputWatermark = "41", unit = unit,
        bought = bought, spent = spent, amountsUnknown = amountsUnknown, over = over, met = met,
        finished = false, daysLeft = 20, windowStart = "2026-10-01", windowEnd = "2026-10-31",
    )

    private fun outcome(
        id: String,
        goalId: String? = "goal-$id",
        name: String,
        spent: String?,
        met: Boolean?,
        origin: String = "goal",
    ) = FinanceGoalOutcome(
        id = id, goalId = goalId, key = id, name = name, scope = "product", unit = "count",
        countTarget = 2, monthlyLimit = null, bought = 2, spent = spent, met = met,
        acceptedAt = "2026-09-01T00:00:00Z", completedAt = "2026-10-01T00:00:00Z", origin = origin,
    )
}
