package com.decorix.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
) {
    val russian = language == "ru"
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
            itemsIndexed(statement.rows, key = { index, _ -> "operation-$index" }) { _, row ->
                Card(Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.merchant?.takeIf(String::isNotBlank) ?: row.description,
                            style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(row.operationDate, row.operationTime).joinToString(" · "))
                        Text(formatMoney(row.signedAmount, language))
                        row.cardLast4?.let { Text(if (russian) "Карта ••$it" else "Card ••$it") }
                    }
                }
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
private fun BankImportTotal(ru: String, en: String, value: String?, language: String) {
    val label = if (language == "ru") ru else en
    Text("$label: ${value?.let { formatMoney(it, language) }
        ?: if (language == "ru") "не указано" else "not provided"}")
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
