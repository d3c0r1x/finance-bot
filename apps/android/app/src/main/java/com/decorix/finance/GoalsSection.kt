package com.decorix.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Presents the Core-owned F44 goal proposal and immutable accepted terms. */
@Composable
internal fun GoalsSection(
    role: String,
    language: String,
    overview: FinanceGoalOverview?,
    busy: Boolean,
    error: String?,
    selectedUnit: String,
    onSelectedUnit: (String) -> Unit,
    onRefresh: () -> Unit,
    onSaveUnit: (String) -> Unit,
    onAccept: (String, String) -> Unit,
    onCancel: (String) -> Unit,
) {
    val russian = language == "ru"
    val canWrite = role in setOf("owner", "admin", "member")
    val selectedGoalUnit = remember(selectedUnit) { mutableStateOf(selectedUnit) }
    val confirmCancellation = remember { mutableStateOf<FinanceMemberGoal?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (russian) "Цель на месяц" else "Monthly goal",
            style = MaterialTheme.typography.titleMedium,
        )

        if (overview == null) {
            error?.let { Text(goalErrorText(it, language), color = MaterialTheme.colorScheme.error) }
            if (busy) Text(if (russian) "Загрузка целей…" else "Loading goals…")
            TextButton(onClick = onRefresh, enabled = !busy) {
                Text(if (russian) "Повторить" else "Retry")
            }
        } else {
            Text(
                if (russian) "F44 · Версия данных: ${overview.inputWatermark}"
                else "F44 · Data version: ${overview.inputWatermark}",
                style = MaterialTheme.typography.labelMedium,
            )
            error?.let { Text(goalErrorText(it, language), color = MaterialTheme.colorScheme.error) }
            if (busy) Text(if (russian) "Сохраняем…" else "Saving…")

            if (canWrite) {
                Text(if (russian) "Формат цели" else "Goal measure")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedGoalUnit.value == "count",
                        onClick = { selectedGoalUnit.value = "count"; onSelectedUnit("count") },
                        label = { Text(if (russian) "Покупки" else "Purchases") },
                        enabled = !busy,
                    )
                    FilterChip(
                        selected = selectedGoalUnit.value == "sum",
                        onClick = { selectedGoalUnit.value = "sum"; onSelectedUnit("sum") },
                        label = { Text(if (russian) "Сумма" else "Spend") },
                        enabled = !busy,
                    )
                }
                if (selectedGoalUnit.value != overview.unit) {
                    TextButton(onClick = { onSaveUnit(selectedGoalUnit.value) }, enabled = !busy) {
                        Text(if (russian) "Сохранить формат" else "Save measure")
                    }
                }
            }

            overview.active?.let { goal ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (russian) "Цель активна" else "Goal is active")
                        Text(goal.name, style = MaterialTheme.typography.titleSmall)
                        Text(goalMeasure(goal.unit, goal.countTarget, goal.monthlyLimit, language))
                        Text(
                            if (russian) "Обычная частота: ${formatGoalNumber(goal.monthlyRate, language)} покупок в месяц"
                            else "Usual rate: ${formatGoalNumber(goal.monthlyRate, language)} purchases per month",
                        )
                        Text(
                            if (russian) "Принята: ${goalDate(goal.acceptedAt, language)} · до ${goalDate(goal.endsAt, language)}"
                            else "Accepted: ${goalDate(goal.acceptedAt, language)} · until ${goalDate(goal.endsAt, language)}",
                        )
                        goal.monthlySpend?.let {
                            Text(if (russian) "Оценка расходов за месяц: ${formatMoney(it, language)}"
                            else "Estimated monthly spend: ${formatMoney(it, language)}")
                        } ?: Text(if (russian) "Сумма неизвестна" else "Amount unknown")
                        Text(
                            if (russian) "Срок цели: ${goalDurationDays(goal.acceptedAt, goal.endsAt)} дней"
                            else "Goal term: ${goalDurationDays(goal.acceptedAt, goal.endsAt)} days",
                        )
                        if (canWrite) {
                            TextButton(
                                onClick = { confirmCancellation.value = goal },
                                enabled = !busy,
                            ) { Text(if (russian) "Отменить цель" else "Cancel goal") }
                        }
                    }
                }
            }

            if (overview.candidates.isNotEmpty()) {
                Text(if (russian) "Предложения" else "Suggestions", style = MaterialTheme.typography.titleSmall)
                overview.candidates.forEach { candidate ->
                    GoalCandidateCard(
                        candidate = candidate,
                        language = language,
                        kind = if (russian) "Товар" else "Product",
                        canWrite = canWrite,
                        hasActiveGoal = overview.active != null,
                        busy = busy,
                        inputWatermark = overview.inputWatermark,
                        onAccept = onAccept,
                    )
                }
            }
            if (overview.groups.isNotEmpty()) {
                Text(if (russian) "Категории" else "Categories", style = MaterialTheme.typography.titleSmall)
                overview.groups.forEach { candidate ->
                    GoalCandidateCard(
                        candidate = candidate,
                        language = language,
                        kind = if (russian) "Категория" else "Category",
                        canWrite = canWrite,
                        hasActiveGoal = overview.active != null,
                        busy = busy,
                        inputWatermark = overview.inputWatermark,
                        onAccept = onAccept,
                    )
                }
            }
            if (overview.candidates.isEmpty() && overview.groups.isEmpty() && overview.skipped.isEmpty()) {
                Text(
                    if (russian) "Пока нет подходящих целей. Нужны подтверждённые покупки и история чеков."
                    else "No eligible goals yet. Confirmed purchases and receipt history are needed.",
                )
            }
            if (overview.skipped.isNotEmpty()) {
                Text(if (russian) "Пока нет полезного денежного лимита" else "No useful spending limit yet")
                overview.skipped.forEach { skipped ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(skipped.name)
                            Text(
                                if (russian) "Оценка расходов за месяц: ${formatMoney(skipped.monthlySpend, language)}"
                                else "Estimated monthly spend: ${formatMoney(skipped.monthlySpend, language)}",
                            )
                            Text(skippedReason(skipped.reasonCode, language))
                        }
                    }
                }
            }
            if (canWrite && error != null) {
                TextButton(onClick = onRefresh, enabled = !busy) {
                    Text(if (russian) "Обновить предложения" else "Refresh suggestions")
                }
            }
        }
    }

    confirmCancellation.value?.let { goal ->
        AlertDialog(
            onDismissRequest = { confirmCancellation.value = null },
            title = { Text(if (russian) "Отменить цель?" else "Cancel goal?") },
            text = { Text(if (russian) "Цель будет остановлена. Продолжить?" else "This will stop the goal. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancellation.value = null
                    onCancel(goal.id)
                }) { Text(if (russian) "Продолжить" else "Continue") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancellation.value = null }) {
                    Text(if (russian) "Не сейчас" else "Not now")
                }
            },
        )
    }
}

@Composable
private fun GoalCandidateCard(
    candidate: FinanceGoalCandidate,
    language: String,
    kind: String,
    canWrite: Boolean,
    hasActiveGoal: Boolean,
    busy: Boolean,
    inputWatermark: String,
    onAccept: (String, String) -> Unit,
) {
    val russian = language == "ru"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(kind, style = MaterialTheme.typography.labelMedium)
            Text(candidate.name, style = MaterialTheme.typography.titleSmall)
            Text(
                if (russian) "Обычная частота: ${formatGoalNumber(candidate.monthlyRate, language)} покупок в месяц"
                else "Usual rate: ${formatGoalNumber(candidate.monthlyRate, language)} purchases per month",
            )
            Text(goalMeasure(candidate.unit, candidate.countTarget, candidate.monthlyLimit, language))
            Text(
                if (russian) "Оценка сокращения расходов: ${formatMoney(candidate.estimatedReduction, language)}"
                else "Estimated spend reduction: ${formatMoney(candidate.estimatedReduction, language)}",
            )
            Text(
                if (russian) "Оценка расходов за месяц: ${formatMoney(candidate.monthlySpend, language)}"
                else "Estimated monthly spend: ${formatMoney(candidate.monthlySpend, language)}",
            )
            Text(if (russian) "Подтверждённых сигналов: ${candidate.evidenceCount}" else "Confirmed signals: ${candidate.evidenceCount}")
            Text(if (russian) "Покупок в истории: ${candidate.purchaseCount}" else "Purchases in history: ${candidate.purchaseCount}")
            if (canWrite) {
                Button(
                    onClick = { onAccept(candidate.key, inputWatermark) },
                    enabled = !busy && !hasActiveGoal,
                ) {
                    Text(if (russian) "Поставить цель: ${candidate.name}" else "Set goal: ${candidate.name}")
                }
            }
        }
    }
}

private fun goalMeasure(unit: String, countTarget: Int, monthlyLimit: String?, language: String): String {
    val russian = language == "ru"
    return if (unit == "count") {
        if (russian) "$countTarget ${goalPurchaseNoun(countTarget)} в месяц" else "Target: at most $countTarget ${if (countTarget == 1) "purchase" else "purchases"} per month"
    } else {
        if (russian) "Лимит в месяц: ${formatMoney(monthlyLimit, language)}"
        else "Monthly limit: ${formatMoney(monthlyLimit, language)}"
    }
}

private fun goalPurchaseNoun(count: Int): String {
    val lastTwo = count % 100
    return when {
        lastTwo in 11..14 -> "покупок"
        count % 10 == 1 -> "покупка"
        count % 10 in 2..4 -> "покупки"
        else -> "покупок"
    }
}

private fun formatGoalNumber(value: String, language: String): String {
    val number = value.toBigDecimalOrNull() ?: return "—"
    val locale = if (language == "ru") Locale.forLanguageTag("ru-RU") else Locale.US
    return java.text.NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }.format(number)
}

private fun goalDate(value: String, language: String): String {
    val instant = runCatching { Instant.parse(value) }.getOrNull() ?: return "—"
    val locale = if (language == "ru") Locale.forLanguageTag("ru-RU") else Locale.US
    val pattern = if (language == "ru") "d MMM yyyy 'г.'" else "MMM d, yyyy"
    return runCatching {
        DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneOffset.UTC).format(instant)
    }.getOrDefault("—")
}

private fun goalDurationDays(start: String, end: String): Long {
    val startAt = runCatching { Instant.parse(start) }.getOrNull() ?: return 30L
    val endAt = runCatching { Instant.parse(end) }.getOrNull() ?: return 30L
    return Duration.between(startAt, endAt).toDays().coerceIn(0, 30)
}

private fun skippedReason(reasonCode: String, language: String): String {
    val russian = language == "ru"
    return when (reasonCode) {
        "missing_amounts" -> if (russian) "Не хватает сумм в чеках" else "Receipt amounts are missing"
        "minimum_savings" -> if (russian) "Пока нет полезного денежного лимита" else "No useful spending limit yet"
        else -> if (russian) "Предложение недоступно" else "Suggestion unavailable"
    }
}

private fun goalErrorText(code: String, language: String): String {
    val russian = language == "ru"
    return when (code) {
        "stale_candidate", "stale", "candidate_stale" -> if (russian) {
            "Данные изменились. Обновите предложения и выберите цель заново."
        } else "Data changed. Refresh suggestions and choose again."
        else -> if (russian) "Не удалось загрузить или сохранить цель." else "Could not load or save the goal."
    }
}
