package com.decorix.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Read-only presentation for the durable F43 analytics job and its Core-owned report. */
@Composable
internal fun AdviceAnalyticsSection(
    role: String?,
    language: String,
    currency: String = "RUB",
    job: FinanceAdviceAnalyticsJob?,
    busy: Boolean,
    error: String?,
    onRequest: () -> Unit,
    onPoll: () -> Unit,
) {
    val russian = language == "ru"
    val canEnqueue = role in setOf("owner", "admin", "member")
    val state = job?.state

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (russian) "Аналитика советов" else "Advice analytics",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (russian) {
                "Отчёт показывает наблюдения по истории покупок. Он не доказывает, что совет стал причиной изменений."
            } else {
                "This report summarizes purchase-history observations. It does not prove that advice caused a change."
            },
        )

        error?.let { code ->
            Text(adviceAnalyticsErrorText(code, language), color = MaterialTheme.colorScheme.error)
        }
        if (busy) Text(if (russian) "Обновляем аналитику…" else "Refreshing analytics…")

        when (state) {
            null -> Unit
            "pending" -> Text(if (russian) "Расчёт поставлен в очередь" else "Analysis queued")
            "processing" -> Text(if (russian) "Анализируем историю покупок…" else "Analyzing purchase history…")
            "ready" -> Text(if (russian) "Расчёт готов" else "Analysis ready")
            "failed" -> Text(if (russian) "Расчёт не выполнен" else "Analysis failed")
            "stale" -> Text(
                if (russian) "Данные изменились — рассчитайте заново" else "Data changed — run the analysis again",
            )
            else -> Text(if (russian) "Статус расчёта неизвестен" else "Analysis status is unknown")
        }

        if (state == "pending" || state == "processing") {
            TextButton(onClick = onPoll, enabled = !busy) {
                Text(if (russian) "Обновить статус" else "Refresh status")
            }
        } else if (canEnqueue) {
            Button(onClick = onRequest, enabled = !busy) {
                Text(
                    if (state == null) {
                        if (russian) "Рассчитать аналитику" else "Calculate analytics"
                    } else {
                        if (russian) "Рассчитать заново" else "Recalculate"
                    },
                )
            }
        }

        if (state == "ready") {
            val currentJob = job
            val report = currentJob?.report
            if (report != null && report.inputWatermark == currentJob?.inputWatermark) {
                AdviceAnalyticsReportContent(report, language, currency)
            } else {
                Text(
                    if (russian) "Отчёт изменился или недоступен. Обновите статус расчёта."
                    else "The report changed or is unavailable. Refresh the analysis status.",
                )
                TextButton(onClick = onPoll, enabled = !busy) {
                    Text(if (russian) "Обновить статус" else "Refresh status")
                }
            }
        }
    }
}

@Composable
private fun AdviceAnalyticsReportContent(report: FinanceAdviceAnalyticsReport, language: String, currency: String) {
    val russian = language == "ru"
    val hasMissingAmounts = report.completeness == "partial" ||
        report.savings.reasonCode == "missing_amounts" || report.trend.reasonCode == "missing_amounts"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (report.completeness == "partial") {
            Text(
                if (russian) "Отчёт неполный: некоторые суммы неизвестны." else
                    "Report is incomplete: some amounts are unknown.",
            )
        }
        if (hasMissingAmounts) {
            Text(
                if (russian) "Часть сумм в чеках неизвестна; итог не рассчитываем."
                else "Some receipt amounts are unknown; no total is calculated.",
            )
        }

        val savings = report.savings
        val monthlyCeiling = savings.monthlyCeiling
        if (savings.available && monthlyCeiling != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        if (russian) "Теоретический потолок за месяц" else "Theoretical monthly ceiling",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(formatMoney(monthlyCeiling, language, currency))
                    Text(
                        if (russian) "Оценка повторяющихся необязательных покупок за ${savings.days} дней, " +
                            "приведённая к 30 дням. Это не фактическая экономия."
                        else "Repeated optional purchases over ${savings.days} days, scaled to 30 days. " +
                            "This is not money actually saved.",
                    )
                    savings.shareOfIncome?.let { share ->
                        Text(
                            if (russian) "${formatPercentValue(share, language)} от планового дохода"
                            else "${formatPercentValue(share, language)} of planned income",
                        )
                    }
                    savings.shareOfLimit?.let { share ->
                        Text(
                            if (russian) "${formatPercentValue(share, language)} от личного лимита"
                            else "${formatPercentValue(share, language)} of personal limit",
                        )
                    }
                    savings.groups.forEach { group ->
                        val groupCeiling = group.monthlyCeiling?.let { formatMoney(it, language, currency) }
                            ?: if (russian) "потолок неизвестен" else "ceiling unavailable"
                        Text(
                            if (russian) {
                                "${group.name} · ${group.count} покупок за ${savings.days} дней · " +
                                    "${formatMoney(group.spend, language, currency)} за период · " +
                                    "$groupCeiling за 30 дней"
                            } else {
                                "${group.name} · ${group.count} purchases in ${savings.days} days · " +
                                    "${formatMoney(group.spend, language, currency)} for the period · " +
                                    "$groupCeiling for 30 days"
                            },
                        )
                    }
                }
            }
        } else {
            Text(adviceAnalyticsSavingsUnavailable(savings.reasonCode, language))
        }

        AdviceAnalyticsTrendContent(report.trend, language, currency)
        AdviceAnalyticsEffectsContent(report.effects, language, currency)
        AdviceAnalyticsRecalculationContent(report.recalculation, language, currency)
    }
}

@Composable
private fun AdviceAnalyticsTrendContent(trend: FinanceAdviceTrend, language: String, currency: String) {
    val russian = language == "ru"
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(if (russian) "Недельная динамика" else "Weekly trend", style = MaterialTheme.typography.titleSmall)
        if (trend.available && trend.weeks.isNotEmpty()) {
            trend.weeks.forEach { week ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${week.start} – ${week.end}")
                        Text(
                            if (russian) "Расходы: ${formatMoney(week.spend, language, currency)} · необязательные: " +
                                "${formatMoney(week.optionalSpend, language, currency)} · доля: " +
                                "${formatRatioPercent(week.optionalShare, language)} · позиций: ${week.itemCount}"
                            else "Spending: ${formatMoney(week.spend, language, currency)} · optional: " +
                                "${formatMoney(week.optionalSpend, language, currency)} · share: " +
                                "${formatRatioPercent(week.optionalShare, language)} · items: ${week.itemCount}",
                        )
                        if (week.recalculated) {
                            Text(if (russian) "Затронуто пересчётом F42" else "Affected by F42 recalculation")
                        }
                    }
                }
            }
            trend.delta?.let { delta ->
                val directionLabel = if (trend.direction == "down") {
                    if (russian) "Доля снизилась" else "Share decreased"
                } else if (trend.direction == "up") {
                    if (russian) "Доля выросла" else "Share increased"
                } else {
                    if (russian) "Без заметного изменения" else "No clear change"
                }
                Text("$directionLabel · ${formatRatioPercent(delta, language)}")
            }
        } else {
            Text(
                if (russian) "Для сравнения нужны подтверждённые покупки как минимум за две недели."
                else "A comparison needs confirmed purchases from at least two weeks.",
            )
        }
    }
}

@Composable
private fun AdviceAnalyticsEffectsContent(effects: FinanceAdviceEffects, language: String, currency: String) {
    val russian = language == "ru"
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(if (russian) "Наблюдения после совета" else "Observations after advice", style = MaterialTheme.typography.titleSmall)
        Text(
            if (russian) "Изменение частоты после совета — не доказательство причинной связи."
            else "This is a frequency change after advice, not evidence of cause.",
        )
        effects.effects.forEach { effect ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(effect.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (russian) "После совета: ${effect.afterCount} · До совета: ${effect.beforeCount}"
                        else "After advice: ${effect.afterCount} · Before advice: ${effect.beforeCount}",
                    )
                    Text(effect.advice)
                    if (effect.afterCount == 0) {
                        Text(
                            if (russian) "Покупок после совета не было в окне наблюдения."
                            else "No purchases appeared in the observation window.",
                        )
                    }
                    Text(
                        if (russian) "Периоды наблюдения: ${effect.daysAfter} дней после · ${effect.daysBefore} дней до"
                        else "Observation windows: ${effect.daysAfter} days after · ${effect.daysBefore} days before",
                    )
                    val afterSpend = effect.afterSpend
                    if (afterSpend != null) {
                        Text(
                            if (russian) "Расходы после совета: ${formatMoney(afterSpend, language, currency)}"
                            else "Spending after advice: ${formatMoney(afterSpend, language, currency)}",
                        )
                    } else {
                        Text(if (russian) "Расходы после совета неизвестны" else "Spending after advice is unknown")
                    }
                    effect.intervalBefore?.let { before ->
                        Text(
                            if (russian) "Интервал до: $before дней" else "Interval before: $before days",
                        )
                    }
                    effect.intervalAfter?.let { after ->
                        Text(
                            if (russian) "Интервал после: $after дней" else "Interval after: $after days",
                        )
                    }
                    Text(
                        if (russian) "Изменение частоты: ${formatPercentValue(effect.change, language)} · " +
                            adviceAnalyticsDirection(effect.direction, language)
                        else "Frequency change: ${formatPercentValue(effect.change, language)} · " +
                            adviceAnalyticsDirection(effect.direction, language),
                    )
                }
            }
        }
        effects.pending.forEach { pending ->
            Text(
                if (russian) "${pending.name}: Пока рано сравнивать (${pending.daysLeft} дн. до достаточного окна)"
                else "${pending.name}: Too early to compare (${pending.daysLeft} days until the window is long enough)",
            )
        }
        if (effects.effects.isEmpty() && effects.pending.isEmpty()) {
            Text(
                if (russian) "Пока нет советов с достаточной историей для сравнения."
                else "There are no advice observations with enough purchase history yet.",
            )
        }
    }
}

@Composable
private fun AdviceAnalyticsRecalculationContent(
    recalculation: FinanceAdviceRecalculation,
    language: String,
    currency: String,
) {
    val russian = language == "ru"
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(if (russian) "Отдельно: пересчёт F42" else "Separate: F42 recalculation", style = MaterialTheme.typography.titleSmall)
        if (recalculation.available) {
            Text(
                if (russian) "Изменено позиций: ${recalculation.changedItemCount}" else
                    "Changed items: ${recalculation.changedItemCount}",
            )
            recalculation.optionalSpendDelta?.let { delta ->
                Text(
                    if (russian) "Изменение необязательных расходов по расчёту F42: ${formatMoney(delta, language, currency)}"
                    else "Optional-spend change from F42 recalculation: ${formatMoney(delta, language, currency)}",
                )
            }
            recalculation.windows.forEach { window ->
                Text(
                    if (russian) "${window.start} – ${window.end}: ${window.changedItemCount} позиций"
                    else "${window.start} – ${window.end}: ${window.changedItemCount} items",
                )
                window.optionalSpendDelta?.let { delta ->
                    Text(
                        if (russian) "Необязательные расходы: ${formatMoney(delta, language, currency)}" else
                            "Optional spending: ${formatMoney(delta, language, currency)}",
                    )
                }
            }
        } else {
            Text(if (russian) "Нет отдельного результата пересчёта." else "No separate recalculation result.")
        }
    }
}

private fun adviceAnalyticsSavingsUnavailable(reason: String, language: String): String = when (reason) {
    "insufficient_history" -> if (language == "ru") "Для этого показателя пока недостаточно истории."
        else "There is not enough history for this metric yet."
    "missing_amounts" -> if (language == "ru") "Нет данных для оценки потолка."
        else "There is not enough data to estimate a ceiling."
    else -> if (language == "ru") "Нет данных для оценки потолка." else "No data is available to estimate a ceiling."
}

private fun adviceAnalyticsErrorText(code: String, language: String): String = when (code) {
    "too_many_items" -> if (language == "ru") "Слишком много позиций для одного расчёта."
        else "There are too many items for one analysis job."
    else -> if (language == "ru") "Не удалось загрузить аналитику. Повторите попытку."
        else "Could not load analytics. Please try again."
}

private fun formatPercentValue(value: String, language: String): String =
    localizedNumber(value, language, maximumFractionDigits = 1) + "%"

private fun formatRatioPercent(value: String, language: String): String = runCatching {
    val percentage = value.toBigDecimal().movePointRight(2).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
    formatPercentValue(percentage, language)
}.getOrElse { "—" }

private fun localizedNumber(value: String, language: String, maximumFractionDigits: Int): String = runCatching {
    val amount = BigDecimal(value)
    NumberFormat.getNumberInstance(if (language == "ru") Locale.forLanguageTag("ru-RU") else Locale.US).apply {
        minimumFractionDigits = 0
        this.maximumFractionDigits = maximumFractionDigits
        roundingMode = RoundingMode.HALF_EVEN
    }.format(amount)
}.getOrElse { value }

private fun adviceAnalyticsDirection(direction: String, language: String): String = when (direction) {
    "less_often" -> if (language == "ru") "покупки реже" else "purchases less often"
    "more_often" -> if (language == "ru") "покупки чаще" else "purchases more often"
    "unchanged" -> if (language == "ru") "без изменений" else "no change"
    else -> if (language == "ru") "направление не определено" else "direction unavailable"
}
