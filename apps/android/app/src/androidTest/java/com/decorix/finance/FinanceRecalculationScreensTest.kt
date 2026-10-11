package com.decorix.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
class FinanceRecalculationScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun writerMustPreviewBeforeSeparateExplicitApplyAndReceiptTotalsStayUnchanged() {
        var previewRequests = 0
        val appliedRunIds = mutableListOf<String>()
        val previewState = mutableStateOf<FinanceReceiptRecalculationPreview?>(null)
        show(
            role = "owner",
            language = "ru",
            previewState = previewState,
            onPreview = { previewRequests++; previewState.value = preview() },
            onApply = { appliedRunIds += it },
        )

        compose.onNodeWithText("Проверить старые разборы").assertIsDisplayed().performClick()
        assertEquals(1, previewRequests)
        assertEquals(emptyList<String>(), appliedRunIds)
        compose.onNodeWithText("Применить пересчёт").assertIsDisplayed()

        compose.onNodeWithText("Старое: нейтральная · устаревшая причина").assertIsDisplayed()
        compose.onNodeWithText("Новое: необязательная · Текущая причина").assertIsDisplayed()
        compose.onNodeWithText("Суммы чеков и операций не меняются.").assertIsDisplayed()
        compose.onNodeWithText("Необязательные покупки: 125,00 ₽ → 0,00 ₽").assertIsDisplayed()
        assertEquals(emptyList<String>(), appliedRunIds)

        compose.onNodeWithText("Применить пересчёт").assertIsEnabled().performClick()
        assertEquals(listOf("run-1"), appliedRunIds)
    }

    @Test fun viewerCanReadNoRecalculationActions() {
        show(role = "viewer", language = "ru")

        compose.onAllNodesWithText("Проверить старые разборы").assertCountEquals(0)
        compose.onAllNodesWithText("Применить пересчёт").assertCountEquals(0)
        compose.onAllNodesWithText("История пересчётов").assertCountEquals(0)
    }

    @Test fun previewShowsOldAndNewVerdictReasonsInRussianAndEnglish() {
        val language = mutableStateOf("ru")
        show(role = "owner", language = "ru", languageState = language, preview = preview())

        compose.onNodeWithText("Старое: нейтральная · устаревшая причина").assertIsDisplayed()
        compose.onNodeWithText("Новое: необязательная · Текущая причина").assertIsDisplayed()
        compose.onNodeWithText("Суммы чеков и операций не меняются.").assertIsDisplayed()
        compose.runOnIdle { language.value = "en" }
        compose.onNodeWithText("Before: neutral · устаревшая причина").assertIsDisplayed()
        compose.onNodeWithText("After: unnecessary · Текущая причина").assertIsDisplayed()
        compose.onNodeWithText("Receipt and transaction totals stay unchanged.").assertIsDisplayed()
    }

    @Test fun unavailableImpactNeverRendersNullAsZeroInRussianAndEnglish() {
        val unavailable = preview().copy(impact = FinanceReceiptRecalculationImpact(
            algorithmVersion = "receipt-recalculation-impact.v1",
            inputVersion = "a".repeat(64),
            reasonCode = "missing_amounts",
            completeness = "partial",
            optionalSpendBefore = null,
            optionalSpendAfter = null,
            optionalSpendDelta = null,
            currency = "RUB",
        ))
        val language = mutableStateOf("ru")
        compose.setContent {
            MaterialTheme {
                ReceiptRecalculationSection(
                    role = "owner", language = language.value, preview = unavailable,
                    history = null, selectedDetail = null, busy = false, error = null,
                    onPreview = {}, onApply = {}, onLoadHistory = {}, onLoadDetail = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Дельта не рассчитана: в чеках не хватает сумм.").assertIsDisplayed()
        compose.onAllNodesWithText("0,00 ₽").assertCountEquals(0)
        compose.runOnIdle { language.value = "en" }
        compose.onNodeWithText("No delta: some receipt items have no amounts.").assertIsDisplayed()
        compose.onAllNodesWithText("0.00 RUB").assertCountEquals(0)
    }

    @Test fun stalePreviewExplainsThatWriterMustCreateANewPreview() {
        val language = mutableStateOf("ru")
        compose.setContent {
            MaterialTheme {
                ReceiptRecalculationSection(
                    role = "owner", language = language.value, preview = null,
                    history = null, selectedDetail = null, busy = false, error = "stale_preview",
                    onPreview = {}, onApply = {}, onLoadHistory = {}, onLoadDetail = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Предпросмотр устарел. Создайте новый перед применением.").assertIsDisplayed()
        compose.onNodeWithText("Проверить старые разборы").assertIsDisplayed()
        compose.runOnIdle { language.value = "en" }
        compose.onNodeWithText("Preview is stale. Create a new one before applying.").assertIsDisplayed()
    }

    @Test fun historyAndDetailContinueUsingCoreCursors() {
        val historyCursors = mutableListOf<String?>()
        val detailRequests = mutableListOf<Pair<String, String?>>()
        show(
            role = "owner", language = "ru",
            history = FinanceReceiptRecalculationHistoryPage(
                runs = listOf(run("run-1")), nextCursor = "history-cursor-2",
            ),
            selectedDetail = FinanceReceiptRecalculationDetail(
                run = run("run-1"), changes = listOf(change()), nextCursor = "detail-cursor-2",
            ),
            onLoadHistory = { historyCursors += it },
            onLoadDetail = { runId, cursor -> detailRequests += runId to cursor },
        )

        compose.onNodeWithText("История пересчётов").performClick()
        assertEquals(listOf(null), historyCursors)
        compose.onNodeWithText("Показать сохранённые изменения").performClick()
        assertEquals(listOf("run-1" to null), detailRequests)
        compose.onAllNodesWithText("Предыдущие запуски").assertCountEquals(2)
        compose.onAllNodesWithText("Предыдущие запуски")[0].performClick()
        assertEquals(listOf(null, "history-cursor-2"), historyCursors)
        compose.onAllNodesWithText("Предыдущие запуски")[1].performClick()
        assertEquals(listOf("run-1" to null, "run-1" to "detail-cursor-2"), detailRequests)
    }

    @Test fun largePreviewRendersAtMostOneHundredChangesUntilExplicitlyExpanded() {
        val previewState: androidx.compose.runtime.MutableState<FinanceReceiptRecalculationPreview?> = mutableStateOf(preview().copy(
            updateCount = 1_001,
            changedCount = 1_001,
            changes = (1..1_001).map { index ->
                change(index).copy(name = "Товар $index")
            },
        ))
        show(role = "owner", language = "en", previewState = previewState, scrollableRoot = true)

        for (index in 1..100) {
            compose.onAllNodesWithText("Товар $index").assertCountEquals(1)
        }
        compose.onAllNodesWithText("Товар 101").assertCountEquals(0)
        compose.onAllNodesWithText("Товар 1001").assertCountEquals(0)
        compose.onAllNodesWithText("Show 100 more changes").assertCountEquals(1)
        compose.onNodeWithText("Show 100 more changes").performScrollTo().performClick()

        for (index in 101..200) {
            compose.onAllNodesWithText("Товар $index").assertCountEquals(1)
        }
        compose.onAllNodesWithText("Товар 201").assertCountEquals(0)
        compose.onAllNodesWithText("Товар 1001").assertCountEquals(0)
    }

    @Test fun expandedSavedDetailRowsStayExpandedWhenNextCursorPageArrives() {
        val detailState = mutableStateOf(FinanceReceiptRecalculationDetail(
            run = run("run-1"),
            changes = (1..250).map(::change),
            nextCursor = "detail-cursor-2",
        ))
        val detailRequests = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReceiptRecalculationSection(
                        role = "owner", language = "en", preview = null,
                        history = null, selectedDetail = detailState.value, busy = false, error = null,
                        onPreview = {}, onApply = {}, onLoadHistory = {},
                        onLoadDetail = { runId, cursor ->
                            detailRequests += runId to cursor
                            detailState.value = detailState.value.copy(
                                changes = detailState.value.changes + (251..300).map(::change),
                                nextCursor = null,
                            )
                        },
                    )
                }
            }
        }

        compose.onAllNodesWithText("Show 100 more changes").assertCountEquals(1)
        compose.onNodeWithText("Show 100 more changes").performScrollTo().performClick()
        compose.onAllNodesWithText("Товар 101").assertCountEquals(1)

        compose.onNodeWithText("Earlier changes").performScrollTo().performClick()
        assertEquals(listOf("run-1" to "detail-cursor-2"), detailRequests)
        compose.onAllNodesWithText("Товар 101").assertCountEquals(1)
    }

    @Test fun receiptLineSumUsesReportCurrencyInsteadOfDefaultRubles() {
        compose.setContent {
            MaterialTheme {
                ReceiptRecalculationSection(
                    role = "owner", language = "en", currency = "USD",
                    preview = preview().copy(impact = preview().impact!!.copy(
                        reasonCode = "missing_amounts", completeness = "partial",
                        optionalSpendBefore = null, optionalSpendAfter = null,
                        optionalSpendDelta = null,
                    )),
                    history = null, selectedDetail = null, busy = false, error = null,
                    onPreview = {}, onApply = {}, onLoadHistory = {}, onLoadDetail = { _, _ -> },
                )
            }
        }

        compose.onAllNodesWithText("125.00 USD", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("125.00 RUB", substring = true).assertCountEquals(0)
    }

    private fun show(
        role: String,
        language: String,
        preview: FinanceReceiptRecalculationPreview? = null,
        previewState: androidx.compose.runtime.MutableState<FinanceReceiptRecalculationPreview?>? = null,
        history: FinanceReceiptRecalculationHistoryPage? = null,
        selectedDetail: FinanceReceiptRecalculationDetail? = null,
        languageState: androidx.compose.runtime.MutableState<String>? = null,
        scrollableRoot: Boolean = false,
        onPreview: () -> Unit = {},
        onApply: (String) -> Unit = {},
        onLoadHistory: (String?) -> Unit = {},
        onLoadDetail: (String, String?) -> Unit = { _, _ -> },
    ) {
        val activePreview = previewState ?: mutableStateOf(preview)
        val activeLanguage = languageState ?: mutableStateOf(language)
        compose.setContent {
            MaterialTheme {
                val section: @Composable () -> Unit = {
                    ReceiptRecalculationSection(
                    role = role,
                    language = activeLanguage.value,
                    preview = activePreview.value,
                    history = history,
                    selectedDetail = selectedDetail,
                    busy = false,
                    error = null,
                    onPreview = onPreview,
                    onApply = onApply,
                    onLoadHistory = onLoadHistory,
                    onLoadDetail = onLoadDetail,
                    )
                }
                if (scrollableRoot) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { section() }
                } else {
                    section()
                }
            }
        }
    }

    private fun preview() = FinanceReceiptRecalculationPreview(
        runId = "run-1",
        algorithmVersion = "receipt-basket.v1",
        state = "previewed",
        checked = 4,
        updateCount = 2,
        changedCount = 1,
        impact = FinanceReceiptRecalculationImpact(
            algorithmVersion = "receipt-recalculation-impact.v1",
            inputVersion = "a".repeat(64),
            reasonCode = "available",
            completeness = "complete",
            optionalSpendBefore = "125.00",
            optionalSpendAfter = "0.00",
            optionalSpendDelta = "-125.00",
            currency = "RUB",
        ),
        changes = listOf(change()),
    )

    private fun run(runId: String) = FinanceReceiptRecalculationRunSummary(
        runId = runId,
        algorithmVersion = "receipt-basket.v1",
        state = "applied",
        checked = 4,
        updateCount = 2,
        changedCount = 1,
        createdAt = "2026-10-06T12:00:00Z",
        appliedAt = "2026-10-06T12:01:00Z",
        impact = preview().impact,
    )

    private fun change(index: Int = 1) = FinanceReceiptRecalculationChange(
        itemId = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
        name = if (index == 1) "Чипсы" else "Товар $index",
        lineSum = "125.00",
        itemVersion = 3,
        beforeVerdict = "neutral",
        beforeReason = "устаревшая причина",
        beforeAction = null,
        beforeSource = "model",
        afterVerdict = "unnecessary",
        afterReason = "Текущая причина",
        afterAction = "Не покупать",
        afterSource = "rule",
        changed = true,
    )
}
