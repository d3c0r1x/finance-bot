package com.decorix.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Reports UI for an explicit, auditable receipt-review recalculation. */
@Composable
internal fun ReceiptRecalculationSection(
    role: String?,
    language: String,
    currency: String = "RUB",
    preview: FinanceReceiptRecalculationPreview?,
    history: FinanceReceiptRecalculationHistoryPage?,
    selectedDetail: FinanceReceiptRecalculationDetail?,
    busy: Boolean,
    error: String?,
    onPreview: () -> Unit,
    onApply: (String) -> Unit,
    onLoadHistory: (String?) -> Unit,
    onLoadDetail: (String, String?) -> Unit,
    applied: FinanceReceiptRecalculationApplyResult? = null,
) {
    val russian = language == "ru"
    val canWrite = role in setOf("owner", "admin", "member")

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (russian) "Пересчёт старых разборов чеков" else "Recalculate old receipt reviews",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(if (russian) "Суммы чеков и операций не меняются." else "Receipt and transaction totals stay unchanged.")

        if (canWrite) {
            Button(onClick = onPreview, enabled = !busy) {
                Text(if (russian) "Проверить старые разборы" else "Preview old reviews")
            }
        }

        error?.let { code ->
            Text(recalculationErrorMessage(code, language), color = MaterialTheme.colorScheme.error)
        }
        if (busy) Text(if (russian) "Загрузка…" else "Loading…")

        preview?.let { current ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (russian) "Предпросмотр · проверено: ${current.checked} · к обновлению: ${current.updateCount}" else
                        "Preview · checked: ${current.checked} · to update: ${current.updateCount}")
                    Text(if (russian) "Изменится: ${current.changedCount}" else "Changes: ${current.changedCount}")
                    renderImpact(current.impact, language)
                    RecalculationChangeList(current.runId, current.changes, language, currency)
                    if (canWrite && current.state == "previewed" && current.updateCount > 0) {
                        Button(onClick = { onApply(current.runId) }, enabled = !busy) {
                            Text(if (russian) "Применить пересчёт" else "Apply recalculation")
                        }
                    }
                }
            }
        }

        applied?.let { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (russian) "Пересчёт применён · проверено: ${result.appliedCount}" else
                        "Recalculation applied · checked: ${result.appliedCount}")
                    renderImpact(result.impact, language)
                    RecalculationChangeList(result.runId, result.changes, language, currency)
                }
            }
        }

        if (canWrite) {
            TextButton(onClick = { onLoadHistory(null) }, enabled = !busy) {
                Text(if (russian) "История пересчётов" else "Recalculation history")
            }
            history?.runs.orEmpty().forEach { run ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (russian) "${run.createdAt} · ${run.state} · обновлено ${run.updateCount}" else
                            "${run.createdAt} · ${run.state} · updated ${run.updateCount}")
                        TextButton(onClick = { onLoadDetail(run.runId, null) }, enabled = !busy) {
                            Text(if (russian) "Показать сохранённые изменения" else "Show saved changes")
                        }
                    }
                }
            }
            history?.nextCursor?.let { cursor ->
                TextButton(onClick = { onLoadHistory(cursor) }, enabled = !busy) {
                    Text(if (russian) "Предыдущие запуски" else "Earlier runs")
                }
            }

            selectedDetail?.let { detail ->
                Text(if (russian) "Сохранённые изменения · ${detail.run.runId}" else "Saved changes · ${detail.run.runId}",
                    style = MaterialTheme.typography.titleSmall)
                RecalculationChangeList(detail.run.runId, detail.changes, language, currency)
                detail.nextCursor?.let { cursor ->
                    TextButton(onClick = { onLoadDetail(detail.run.runId, cursor) }, enabled = !busy) {
                        Text(if (russian) "Предыдущие запуски" else "Earlier changes")
                    }
                }
            }
        }
    }
}

@Composable
private fun RecalculationChangeList(
    runId: String,
    changes: List<FinanceReceiptRecalculationChange>,
    language: String,
    currency: String,
) {
    val visibleCount = remember(runId) { mutableStateOf(minOf(100, changes.size)) }
    for (index in 0 until visibleCount.value) {
        RecalculationChange(changes[index], language, currency)
    }
    if (visibleCount.value < changes.size) {
        val batchSize = minOf(100, changes.size - visibleCount.value)
        TextButton(onClick = { visibleCount.value += batchSize }) {
            Text(if (language == "ru") "Показать ещё $batchSize изменений" else "Show $batchSize more changes")
        }
    }
}

@Composable
private fun RecalculationChange(change: FinanceReceiptRecalculationChange, language: String, currency: String) {
    val russian = language == "ru"
    val beforeVerdict = recalculationVerdictLabel(change.beforeVerdict, language)
    val afterVerdict = recalculationVerdictLabel(change.afterVerdict, language)
    val beforeReason = recalculationReviewText(change.beforeReason, language)
    val afterReason = recalculationReviewText(change.afterReason, language)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(change.name)
        Text(if (russian) "Старое: $beforeVerdict · $beforeReason" else "Before: $beforeVerdict · $beforeReason")
        Text(if (russian) "Новое: $afterVerdict · $afterReason" else "After: $afterVerdict · $afterReason")
        change.beforeAction?.let {
            Text(if (russian) "Старое действие: ${recalculationActionLabel(it, language)}" else
                "Previous action: ${recalculationActionLabel(it, language)}")
        }
        change.afterAction?.let {
            Text(if (russian) "Новое действие: ${recalculationActionLabel(it, language)}" else
                "New action: ${recalculationActionLabel(it, language)}")
        }
        if (change.beforeSource != null || change.afterSource != null) {
            Text(if (russian) "Источник: ${recalculationSourceLabel(change.beforeSource, language)} → ${recalculationSourceLabel(change.afterSource, language)}" else
                "Source: ${recalculationSourceLabel(change.beforeSource, language)} → ${recalculationSourceLabel(change.afterSource, language)}")
        }
        change.lineSum?.let {
            Text(if (russian) "Сумма позиции: ${formatMoney(it, language, currency)}" else
                "Item amount: ${formatMoney(it, language, currency)}")
        }
    }
}

@Composable
private fun renderImpact(impact: FinanceReceiptRecalculationImpact?, language: String) {
    val russian = language == "ru"
    if (impact == null || impact.reasonCode != "available" ||
        impact.optionalSpendBefore == null || impact.optionalSpendAfter == null || impact.currency == null
    ) {
        val reason = impact?.reasonCode
        val message = when (reason) {
            "missing_amounts" -> if (russian) "Дельта не рассчитана: в чеках не хватает сумм." else
                "No delta: some receipt items have no amounts."
            "no_reviewed_items" -> if (russian) "Дельта не рассчитана: нет проверенных позиций." else
                "No delta: there are no reviewed receipt items."
            else -> if (russian) "Дельта недоступна для этого предпросмотра." else
                "Delta is unavailable for this preview."
        }
        Text(message)
        return
    }
    val before = formatMoney(impact.optionalSpendBefore, language, impact.currency)
    val after = formatMoney(impact.optionalSpendAfter, language, impact.currency)
    Text(if (russian) "Необязательные покупки: $before → $after" else "Optional purchases: $before → $after")
    impact.optionalSpendDelta?.let { delta ->
        Text(if (russian) "Изменение: ${formatMoney(delta, language, impact.currency)}" else
            "Change: ${formatMoney(delta, language, impact.currency)}")
    }
}

private fun recalculationErrorMessage(code: String, language: String): String {
    val russian = language == "ru"
    return when (code) {
        "stale_preview", "412" -> if (russian) "Предпросмотр устарел. Создайте новый перед применением." else
            "Preview is stale. Create a new one before applying."
        "403", "forbidden" -> if (russian) "Недостаточно прав для пересчёта." else
            "You do not have permission to recalculate reviews."
        else -> if (russian) "Не удалось выполнить пересчёт. Повторите попытку." else
            "Could not complete recalculation. Try again."
    }
}

private fun recalculationVerdictLabel(value: String?, language: String): String =
    when (value) {
        "neutral" -> if (language == "ru") "нейтральная" else "neutral"
        "harmful" -> if (language == "ru") "неблагоприятная" else "harmful"
        "unnecessary" -> if (language == "ru") "необязательная" else "unnecessary"
        "useful" -> if (language == "ru") "полезная" else "useful"
        null, "" -> if (language == "ru") "не указана" else "not provided"
        else -> if (language == "ru") "неизвестная" else "unknown"
    }

private fun recalculationActionLabel(value: String, language: String): String = when (value.lowercase()) {
    "avoid" -> if (language == "ru") "не брать" else "avoid"
    "unknown", "null", "not_provided" -> if (language == "ru") "не указано" else "not provided"
    else -> value
}

private fun recalculationSourceLabel(value: String?, language: String): String = when (value) {
    "rule" -> if (language == "ru") "правило" else "rule"
    "model" -> if (language == "ru") "модель" else "model"
    "default" -> if (language == "ru") "по умолчанию" else "default"
    "human" -> if (language == "ru") "человек" else "human"
    else -> if (language == "ru") "неизвестен" else "unknown"
}

private fun recalculationReviewText(value: String?, language: String): String =
    value?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (language == "ru") "не указана" else "not provided"
