package com.decorix.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag

@Composable
internal fun BankImportPreviewSection(
    role: String,
    language: String,
    preview: FinanceBankImportPreview?,
    busy: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    canUpload: Boolean = false,
    onPick: () -> Unit = {},
    onSelectRow: (String, String?) -> Unit = { _, _ -> },
    onConfirm: () -> Unit = {},
    confirmationNeedsRefresh: Boolean = false,
    undoResult: FinanceBankImportUndoResult? = null,
    onUndo: () -> Unit = {},
) {
    val russian = language == "ru"
    var showUndoConfirmation by remember(preview?.id) { mutableStateOf(false) }
    LaunchedEffect(preview?.id, preview?.state, role, canUpload, busy) {
        if (busy || role == "viewer" || !canUpload || preview?.state != "committed") {
            showUndoConfirmation = false
        }
    }
    LazyColumn(
        modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("bank-import-preview"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "heading") {
            Text(if (russian) "Импорт выписки" else "Statement import", style = MaterialTheme.typography.headlineSmall)
        }
        if (role != "viewer" && canUpload) {
            item(key = "upload") {
                Button(onClick = onPick, enabled = !busy) {
                    Text(if (russian) "Выбрать PDF-выписку" else "Choose PDF statement")
                }
            }
        }
        if (busy) {
            item(key = "loading") {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text(if (russian) "Загружаем и разбираем выписку…" else "Uploading and parsing statement…")
                }
            }
        }
        error?.let { code -> item(key = "error") {
            Text(bankImportErrorText(code, language), color = MaterialTheme.colorScheme.error)
        } }
        preview?.let { statement ->
            item(key = "summary") { BankImportStatementSummary(statement, language) }
            itemsIndexed(statement.rows, key = { index, row -> row.id.ifBlank { "operation-$index" } }) { _, row ->
                BankImportRowCard(
                    row = row,
                    language = language,
                    canEdit = canUpload && role != "viewer" && statement.state == "needs_review",
                    busy = busy,
                    onSelect = { type -> onSelectRow(row.id, type) },
                )
            }
            if (statement.state == "committed" && role != "viewer" && canUpload) {
                item(key = "undo") {
                    TextButton(
                        modifier = Modifier.testTag("bank-import-undo"),
                        onClick = { showUndoConfirmation = true },
                        enabled = !busy,
                    ) {
                        Text(if (russian) "Отменить импорт" else "Undo import")
                    }
                }
            }
            val currentUndoResult = undoResult?.takeIf { it.id == statement.id }
            currentUndoResult?.let { result ->
                when {
                    result.state == "reverted" && statement.state == "reverted" -> item(key = "undo-result") {
                        Text(
                            if (russian) "Импорт отменён: операций ${result.revertedCount}."
                            else "Import undone: ${result.revertedCount} operations.",
                            modifier = Modifier.testTag("bank-import-undo-result"),
                        )
                    }
                    result.state == "committed" && statement.state == "committed" && result.conflicts.isNotEmpty() -> {
                        val conflictDescriptions = statement.rows
                            .filter { it.transactionId != null && it.transactionId in result.conflicts }
                            .map { it.merchant?.takeIf(String::isNotBlank) ?: it.description }
                            .distinct()
                        item(key = "undo-result") {
                            val details = conflictDescriptions.takeIf { it.isNotEmpty() }
                                ?.joinToString(", ")
                                ?: if (russian) "некоторые операции" else "some transactions"
                            Text(
                                if (russian) "Отмена остановлена: $details. Импорт остался выполненным."
                                else "Undo stopped: $details. Import remains committed.",
                                modifier = Modifier.testTag("bank-import-undo-result"),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            if (statement.state == "needs_review" && canUpload && role != "viewer" && statement.includedCount > 0) {
                item(key = "confirm") {
                    androidx.compose.material3.Button(
                        modifier = Modifier.testTag("bank-import-confirm"),
                        onClick = onConfirm,
                        enabled = !busy && !confirmationNeedsRefresh,
                    ) {
                        Text(if (russian) "Подтвердить импорт" else "Confirm import")
                    }
                }
            }
            if (statement.state == "committed") item(key = "confirm-result") {
                Text(
                    if (russian) "Импорт выполнен: создано ${statement.createdCount}, дубликатов ${statement.duplicateCount}."
                    else "Import complete: ${statement.createdCount} created, ${statement.duplicateCount} duplicates.",
                    modifier = Modifier.testTag("bank-import-confirm-result"),
                )
            }
            if (statement.id.isNotBlank()) item(key = "refresh") {
                TextButton(onClick = onRefresh, enabled = !busy) {
                    Text(if (russian) "Обновить предпросмотр" else "Refresh preview")
                }
            }
        }
        if (preview == null && error == null && !busy) item(key = "empty") {
            Text(if (russian) "Выберите PDF-выписку Т-Банка для предпросмотра." else "Choose a T-Bank PDF statement to preview.")
        }
    }
    if (showUndoConfirmation && preview?.state == "committed" && role != "viewer" && canUpload) {
        AlertDialog(
            modifier = Modifier.testTag("bank-import-undo-dialog"),
            onDismissRequest = { showUndoConfirmation = false },
            title = { Text(if (russian) "Отменить импорт?" else "Undo import?") },
            text = {
                Text(if (russian) "Будут удалены только операции, созданные этим импортом и ещё не изменённые." else "Only transactions created by this import and not changed since will be removed.")
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("bank-import-undo-confirm"),
                    onClick = {
                        showUndoConfirmation = false
                        onUndo()
                    },
                    enabled = !busy,
                ) { Text(if (russian) "Отменить импорт" else "Undo import") }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag("bank-import-undo-cancel"),
                    onClick = { showUndoConfirmation = false },
                ) { Text(if (russian) "Оставить" else "Keep import") }
            },
        )
    }
}

@Composable
private fun BankImportStatementSummary(preview: FinanceBankImportPreview, language: String) {
    val russian = language == "ru"
    val quality = when (preview.quality) {
        "valid" -> if (russian) "Итоги выписки сверены" else "Statement totals reconciled"
        "mismatch" -> if (russian) "Расхождение итогов выписки" else "Statement totals do not match"
        "unverifiable" -> if (russian) "Итоги выписки не удалось проверить" else "Statement totals could not be verified"
        else -> if (russian) "Статус выписки недоступен" else "Statement status unavailable"
    }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(quality, style = MaterialTheme.typography.titleMedium)
        Text("${if (russian) "Период" else "Period"}: ${preview.periodStart} — ${preview.periodEnd}")
        Text(if (russian) "Выбранные операции и сверка" else "Selected operations and reconciliation",
            style = MaterialTheme.typography.titleSmall)
        Text(if (russian) "Включено: ${preview.includedCount}" else "Included: ${preview.includedCount}",
            modifier = Modifier.testTag("bank-import-total-included-count"))
        Text(if (russian) "Исключено: ${preview.excludedCount}" else "Excluded: ${preview.excludedCount}",
            modifier = Modifier.testTag("bank-import-total-excluded-count"))
        BankImportTotal("Расходы", "Expenses", preview.expenseTotal, language, "expense")
        BankImportTotal("Доходы", "Income", preview.incomeTotal, language, "income")
        BankImportTotal("Возвраты", "Refunds", preview.refundTotal, language, "refund")
        BankImportTotal("Переводы", "Transfers", preview.transferTotal, language, "transfer")
        BankImportTotal("Исключено по умолчанию", "Excluded by default", preview.excludedTotal, language, "excluded")
        Text(if (russian) "Итоги по распознанным операциям" else "Parsed operation totals",
            style = MaterialTheme.typography.titleSmall)
        Text("${if (russian) "Расходы" else "Expenses"}: ${formatMoney(preview.parsedExpenseTotal, language)}")
        Text("${if (russian) "Доходы" else "Income"}: ${formatMoney(preview.parsedIncomeTotal, language)}")
        if (preview.expectedExpenseTotal != null || preview.expectedIncomeTotal != null) {
            Text(if (russian) "Итоги из выписки банка" else "Statement totals",
                style = MaterialTheme.typography.titleSmall)
            BankImportTotal("Расходы", "Expenses", preview.expectedExpenseTotal, language)
            BankImportTotal("Доходы", "Income", preview.expectedIncomeTotal, language)
        } else {
            Text(if (russian) "Итоги банка в PDF не указаны" else "Bank totals are not available in the PDF")
        }
        Text(if (russian) "Операции: ${preview.rows.size}" else "Operations: ${preview.rows.size}",
            style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BankImportTotal(ru: String, en: String, value: String?, language: String, tag: String? = null) {
    val label = if (language == "ru") ru else en
    Text(
        "$label: ${value?.let { runCatching { formatMoney(it, language) }.getOrDefault(it) }
            ?: if (language == "ru") "не указано" else "not provided"}",
        modifier = if (tag == null) Modifier else Modifier.testTag("bank-import-total-$tag"),
    )
}

@Composable
private fun BankImportRowCard(
    row: FinanceBankImportRow,
    language: String,
    canEdit: Boolean,
    busy: Boolean,
    onSelect: (String?) -> Unit,
) {
    val russian = language == "ru"
    var expanded by remember(row.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("bank-import-row-${row.id}")) {
        androidx.compose.foundation.layout.Column(Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.merchant?.takeIf(String::isNotBlank) ?: row.description,
                style = MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(row.operationDate, row.operationTime).joinToString(" · "))
            Text(formatMoney(row.signedAmount, language),
                modifier = Modifier.testTag("bank-import-row-amount-${row.id}"))
            bankImportOutcomeText(row.outcome, language)?.let { outcome ->
                Text(outcome, modifier = Modifier.testTag("bank-import-row-outcome-${row.id}"))
            }
            row.cardLast4?.let { Text(if (russian) "Карта ••$it" else "Card ••$it") }
            val decision = if (row.included) {
                if (russian) "Включено" else "Included"
            } else {
                val reason = when (row.exclusionReason) {
                    "bank_fee" -> if (russian) "Комиссия банка" else "Bank fee"
                    "external_transfer" -> if (russian) "Внешний перевод" else "External transfer"
                    "cash_withdrawal" -> if (russian) "Снятие наличных" else "Cash withdrawal"
                    "internal_transfer" -> if (russian) "Внутренний перевод" else "Internal transfer"
                    "requires_review" -> if (russian) "Требует проверки" else "Needs review"
                    else -> if (russian) "Не выбрано" else "Not selected"
                }
                if (russian) "Исключено · $reason" else "Excluded · $reason"
            }
            Text(decision, modifier = Modifier.testTag("bank-import-row-state-${row.id}"))
            if (canEdit) {
                androidx.compose.foundation.layout.Box {
                    TextButton(
                        modifier = Modifier.testTag("bank-import-row-type-${row.id}"),
                        onClick = { expanded = true },
                        enabled = !busy,
                    ) {
                        Text(if (russian) "Тип операции: ${row.transactionType.toRussianImportType()}"
                            else "Operation type: ${row.transactionType.toEnglishImportType()}")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("expense", "income", "refund", "transfer").forEach { type ->
                            DropdownMenuItem(
                                text = { Text(if (russian) type.toRussianImportType() else type.toEnglishImportType()) },
                                onClick = { expanded = false; onSelect(type) },
                                enabled = !busy,
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (russian) "Сбросить выбор" else "Use default") },
                            onClick = { expanded = false; onSelect(null) },
                            enabled = !busy,
                        )
                    }
                }
            }
        }
    }
}

private fun bankImportOutcomeText(outcome: String, language: String): String? {
    val russian = language == "ru"
    return when (outcome) {
        "duplicate" -> if (russian) "Уже импортировано · пропущено" else "Already imported · skipped"
        "created" -> if (russian) "Добавлено" else "Added"
        "reverted" -> if (russian) "Отменено" else "Reverted"
        else -> null
    }
}

private fun String?.toRussianImportType(): String = when (this) {
    "expense" -> "Расход"
    "income" -> "Доход"
    "refund" -> "Возврат"
    "transfer" -> "Перевод"
    else -> "По умолчанию"
}

private fun String?.toEnglishImportType(): String = when (this) {
    "expense" -> "Expense"
    "income" -> "Income"
    "refund" -> "Refund"
    "transfer" -> "Transfer"
    else -> "Default"
}

private fun bankImportErrorText(code: String, language: String): String {
    val ru = language == "ru"
    return when (code) {
        "invalid_pdf" -> if (ru) "Не удалось прочитать PDF. Проверьте файл и повторите." else "Could not read the PDF. Check the file and try again."
        "invalid_format" -> if (ru) "Поддерживается только выписка Т-Банка «Справка о движении средств»." else "Only T-Bank account statement PDFs are supported."
        "no_text" -> if (ru) "В PDF нет извлекаемого текста. Скан выписки пока не поддерживается." else "The PDF has no extractable text. Scanned statements are not supported yet."
        "no_operations" -> if (ru) "В выписке не найдены операции." else "No operations were found in the statement."
        "invalid_totals" -> if (ru) "Итоги выписки имеют неверный формат или выходят за диапазон." else "Statement totals are malformed or outside the supported range."
        "invalid_operation" -> if (ru) "В одной из операций неверная сумма, дата или описание." else "An operation has an invalid amount, date or description."
        "file_size" -> if (ru) "PDF больше 12 МБ." else "PDF exceeds 12 MiB."
        "empty_file" -> if (ru) "Выбран пустой файл." else "The selected file is empty."
        "unauthorized" -> if (ru) "Войдите снова, чтобы продолжить." else "Sign in again to continue."
        "forbidden" -> if (ru) "Нет доступа к импорту в этой семье." else "You do not have access to import statements in this household."
        else -> if (ru) "Не удалось загрузить выписку. Проверьте соединение и повторите." else "Could not load the statement. Check your connection and try again."
    }
}
