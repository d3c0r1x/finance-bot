package com.decorix.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
class FinanceAdviceAnalyticsScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun writerEnqueuesOnlyAfterExplicitRequestInRussian() {
        val jobState = mutableStateOf<FinanceAdviceAnalyticsJob?>(null)
        var requests = 0
        render(jobState = jobState, onRequest = { requests++; jobState.value = job("pending") })

        compose.onAllNodesWithText("Рассчитать аналитику").assertCountEquals(1)
        assertEquals(0, requests)
        compose.onNodeWithText("Рассчитать аналитику").performClick()
        assertEquals(1, requests)
        compose.onAllNodesWithText("Расчёт поставлен в очередь").assertCountEquals(1)
    }

    @Test fun viewerCanReadCurrentReportButCannotEnqueue() {
        render(role = "viewer", job = job("ready", readyReport()))

        compose.onAllNodesWithText("Расчёт готов").assertCountEquals(1)
        compose.onAllNodesWithText("Теоретический потолок за месяц").assertCountEquals(1)
        compose.onAllNodesWithText("Рассчитать аналитику").assertCountEquals(0)
        compose.onAllNodesWithText("Рассчитать заново").assertCountEquals(0)
    }

    @Test fun pendingAndProcessingAreDistinctPollingStatesInRussianAndEnglish() {
        val jobState = mutableStateOf<FinanceAdviceAnalyticsJob?>(job("pending"))
        val language = mutableStateOf("ru")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AdviceAnalyticsSection(
                        role = "owner",
                        language = language.value,
                        job = jobState.value,
                        busy = false,
                        error = null,
                        onRequest = {},
                        onPoll = { jobState.value = job("processing") },
                    )
                }
            }
        }

        compose.onAllNodesWithText("Расчёт поставлен в очередь").assertCountEquals(1)
        compose.onNodeWithText("Обновить статус").performClick()
        compose.onAllNodesWithText("Анализируем историю покупок…").assertCountEquals(1)
        // English state labels use the same server state, localized only at the UI boundary.
        compose.runOnIdle { language.value = "en" }
        compose.onAllNodesWithText("Analyzing purchase history…").assertCountEquals(1)
    }

    @Test fun readyReportShowsFourWeeksCeilingPendingAndMeasuredEffectWithoutCausality() {
        render(job = job("ready", readyReport()))

        compose.onAllNodesWithText("Теоретический потолок за месяц").assertCountEquals(1)
        compose.onAllNodesWithText("Это не фактическая экономия.", substring = true).assertCountEquals(1)
        for (range in listOf(
            "2026-09-10 – 2026-09-16",
            "2026-09-17 – 2026-09-23",
            "2026-09-24 – 2026-09-30",
            "2026-10-01 – 2026-10-07",
        )) {
            compose.onAllNodesWithText(range).assertCountEquals(1)
        }
        compose.onAllNodesWithText("Затронуто пересчётом F42").assertCountEquals(1)
        compose.onAllNodesWithText("Конфеты: Пока рано сравнивать", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("После совета: 0 · До совета: 2", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("не доказательство причинной связи", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Отдельно: пересчёт F42").assertCountEquals(1)
        compose.onAllNodesWithText("Изменено позиций: 1").assertCountEquals(1)
        compose.onAllNodesWithText("Изменение необязательных расходов по расчёту F42", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Сэкономлено вами", substring = true).assertCountEquals(0)
    }

    @Test fun englishCopyKeepsCeilingTheoreticalAndDeniesCausalClaims() {
        render(job = job("ready", readyReport()), language = "en")

        compose.onAllNodesWithText("Theoretical monthly ceiling").assertCountEquals(1)
        compose.onAllNodesWithText("This is not money actually saved.", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Observations after advice").assertCountEquals(1)
        compose.onAllNodesWithText("This is a frequency change after advice, not evidence of cause.", substring = true)
            .assertCountEquals(1)
        compose.onAllNodesWithText("Separate: F42 recalculation").assertCountEquals(1)
    }

    @Test fun exactAdviceAmountsUseTheMemberCurrency() {
        render(job = job("ready", readyReport()), language = "en", currency = "USD")

        compose.onAllNodesWithText("1,200.00 USD").assertCountEquals(1)
        compose.onAllNodesWithText("3,600.00 USD for the period", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Spending: 5,000.00 USD · optional: 1,500.00 USD", substring = true)
            .assertCountEquals(1)
        compose.onAllNodesWithText("Optional-spend change from F42 recalculation: -50.00 USD", substring = true)
            .assertCountEquals(1)
        compose.onAllNodesWithText("1,200.00 ₽").assertCountEquals(0)
    }

    @Test fun partialMissingAndUnavailableValuesStayNullWithoutInventedWeeksOrZeros() {
        render(job = job("ready", partialUnavailableReport()))

        compose.onAllNodesWithText("Отчёт неполный: некоторые суммы неизвестны.").assertCountEquals(1)
        compose.onAllNodesWithText("Часть сумм в чеках неизвестна; итог не рассчитываем.").assertCountEquals(1)
        compose.onAllNodesWithText("Нет данных для оценки потолка.").assertCountEquals(1)
        compose.onAllNodesWithText("Для сравнения нужны подтверждённые покупки как минимум за две недели.")
            .assertCountEquals(1)
        compose.onAllNodesWithText("2026-09", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("0,00 ₽").assertCountEquals(0)
        compose.onAllNodesWithText("0.00 RUB").assertCountEquals(0)
    }

    @Test fun missingPerGroupCeilingIsNotRenderedAsZero() {
        val report = readyReport().copy(
            savings = readyReport().savings.copy(
                groups = listOf(readyReport().savings.groups.single().copy(monthlyCeiling = null)),
            ),
        )
        render(job = job("ready", report))

        compose.onAllNodesWithText("потолок неизвестен", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("0,00 ₽").assertCountEquals(0)
    }

    @Test fun failedStaleAndReadyButUnavailableAreRenderedAsDifferentStates() {
        val jobState = mutableStateOf<FinanceAdviceAnalyticsJob?>(job("failed"))
        render(jobState = jobState)
        compose.onAllNodesWithText("Расчёт не выполнен").assertCountEquals(1)
        compose.runOnIdle { jobState.value = job("stale", readyReport()) }
        compose.onAllNodesWithText("Данные изменились — рассчитайте заново").assertCountEquals(1)
        compose.onAllNodesWithText("Теоретический потолок за месяц").assertCountEquals(0)
        compose.runOnIdle { jobState.value = job("ready", unavailableReport()) }
        compose.onAllNodesWithText("Расчёт готов").assertCountEquals(1)
        compose.onAllNodesWithText("Для этого показателя пока недостаточно истории.").assertCountEquals(1)
        compose.onAllNodesWithText("2026-09", substring = true).assertCountEquals(0)
    }

    @Test fun enqueueButtonIsDisabledWhilePendingOrProcessing() {
        val jobState = mutableStateOf<FinanceAdviceAnalyticsJob?>(job("pending"))
        render(jobState = jobState)
        compose.onAllNodesWithText("Рассчитать аналитику").assertCountEquals(0)
        compose.runOnIdle { jobState.value = job("processing") }
        compose.onAllNodesWithText("Рассчитать аналитику").assertCountEquals(0)
    }

    private fun render(
        role: String = "owner",
        language: String = "ru",
        currency: String = "RUB",
        job: FinanceAdviceAnalyticsJob? = null,
        jobState: androidx.compose.runtime.MutableState<FinanceAdviceAnalyticsJob?>? = null,
        onRequest: () -> Unit = {},
        onPoll: () -> Unit = {},
    ) {
        val activeJob = jobState ?: mutableStateOf(job)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AdviceAnalyticsSection(
                        role = role,
                        language = language,
                        currency = currency,
                        job = activeJob.value,
                        busy = false,
                        error = null,
                        onRequest = onRequest,
                        onPoll = onPoll,
                    )
                }
            }
        }
    }

    private fun job(state: String, report: FinanceAdviceAnalyticsReport? = null) = FinanceAdviceAnalyticsJob(
        id = JOB_ID,
        state = state,
        inputWatermark = "202",
        algorithmVersion = "advice-f43.v1",
        completeness = report?.completeness,
        errorCode = if (state == "failed") "worker_failed" else null,
        report = report,
        updatedAt = "2026-10-07T12:00:00Z",
    )

    private fun readyReport() = FinanceAdviceAnalyticsReport(
        algorithmVersion = "advice-f43.v1",
        inputWatermark = "202",
        completeness = "complete",
        reasonCode = "available",
        savings = FinanceAdviceSavings(
            available = true,
            reasonCode = "available",
            label = "theoretical_ceiling_not_actual_savings",
            days = 90,
            monthlyCeiling = "1200.00",
            shareOfIncome = "6.0",
            shareOfLimit = "12.0",
            groups = listOf(FinanceAdviceSavingsGroup("snack", "Снеки", 3, "3600.00", "1200.00")),
        ),
        trend = FinanceAdviceTrend(
            available = true, reasonCode = "available", delta = "-0.125", direction = "down",
            weeks = listOf(
                FinanceAdviceWeek("2026-09-10", "2026-09-16", "5000.00", "1500.00", "0.300", 8, false),
                FinanceAdviceWeek("2026-09-17", "2026-09-23", "4000.00", "700.00", "0.175", 6, true),
                FinanceAdviceWeek("2026-09-24", "2026-09-30", "3000.00", "900.00", "0.300", 5, false),
                FinanceAdviceWeek("2026-10-01", "2026-10-07", "6000.00", "1200.00", "0.200", 9, false),
            ),
        ),
        effects = FinanceAdviceEffects(
            effects = listOf(FinanceAdviceEffect(
                productKey = "snack", name = "Снеки", advice = "Покупать реже",
                beforeCount = 2, afterCount = 0, daysBefore = 35, daysAfter = 30,
                intervalBefore = "17.5", intervalAfter = null, change = "-100.0",
                direction = "less_often", afterSpend = "0.00",
            )),
            pending = listOf(FinanceAdvicePendingEffect("candy", "Конфеты", 6, 15)),
            causalityClaim = false,
        ),
        recalculation = FinanceAdviceRecalculation(
            available = true, changedItemCount = 1, optionalSpendDelta = "-50.00",
            windows = listOf(FinanceAdviceRecalculationWindow("2026-09-17", "2026-09-23", 1, "-50.00")),
        ),
    )

    private fun partialUnavailableReport() = readyReport().copy(
        completeness = "partial",
        reasonCode = "missing_amounts",
        savings = readyReport().savings.copy(
            available = false, reasonCode = "missing_amounts", monthlyCeiling = null,
            shareOfIncome = null, shareOfLimit = null, groups = emptyList(),
        ),
        trend = readyReport().trend.copy(
            available = false, reasonCode = "missing_amounts", weeks = emptyList(), delta = null,
        ),
        effects = FinanceAdviceEffects(emptyList(), emptyList(), causalityClaim = false),
        recalculation = FinanceAdviceRecalculation(false, 0, null, emptyList()),
    )

    private fun unavailableReport() = partialUnavailableReport().copy(
        completeness = "complete",
        reasonCode = "insufficient_history",
        savings = partialUnavailableReport().savings.copy(reasonCode = "insufficient_history"),
        trend = partialUnavailableReport().trend.copy(reasonCode = "insufficient_history"),
    )

    private companion object { const val JOB_ID = "00000000-0000-4000-8000-000000000043" }
}
