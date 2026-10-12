package com.decorix.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun FinanceExportsSection(
    role: String,
    language: String,
    timezone: String,
    members: List<FinanceTenantMember>,
    membersLoading: Boolean,
    job: FinanceExportJob?,
    busy: Boolean,
    error: String?,
    retryNeedsConfirmation: Boolean = false,
    onCreate: (FinanceExportRequest) -> Unit,
    onRefresh: (String) -> Unit,
    onDownload: (String) -> Unit,
    modifier: Modifier = Modifier,
    pollIdentity: FinanceExportStatusIdentity? = null,
    pollDelay: suspend (Long) -> Unit = { delay(it) },
) {
    val russian = language == "ru"
    val manager = role == "owner" || role == "admin"
    val today = remember(timezone) {
        val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))
        LocalDate.now(zone)
    }
    var fromDate by remember(timezone) { mutableStateOf(today.minusDays(29).toString()) }
    var toDate by remember(timezone) { mutableStateOf(today.toString()) }
    var memberScope by remember(role) { mutableStateOf<String?>(null) }
    var scopeMenuExpanded by remember { mutableStateOf(false) }
    val validatedRequest = remember(fromDate, toDate, memberScope) {
        runCatching { FinanceExportModels.request(fromDate.trim(), toDate.trim(), memberScope) }.getOrNull()
    }
    val retryNeedsDuplicateWarning = retryNeedsConfirmation
    var retrySubmitted by remember(error, fromDate, toDate, memberScope) { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (!busy) retrySubmitted = false
    }
    val polls = remember(job?.id) { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var screenResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> screenResumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> screenResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(job?.id, job?.status, screenResumed, pollIdentity) {
        while (pollIdentity != null &&
            FinanceExportPollingPolicy.shouldPoll(job?.status, polls.intValue, screenResumed)) {
            pollDelay(FinanceExportPollingPolicy.INTERVAL_MILLIS)
            if (FinanceExportPollingPolicy.shouldPoll(job?.status, polls.intValue, screenResumed)) {
                polls.intValue += 1
                job?.id?.let(onRefresh)
            }
        }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (russian) "Экспорт операций" else "Transaction export", style = MaterialTheme.typography.headlineSmall)
        Text(if (russian) "CSV для Excel: безопасный формат готовит сервер." else
            "Excel-compatible CSV. The server creates and filters the file.")
        OutlinedTextField(fromDate, { fromDate = it },
            modifier = Modifier.fillMaxWidth().testTag("export-from-date"),
            label = { Text(if (russian) "С даты (ГГГГ-ММ-ДД)" else "From date (YYYY-MM-DD)") }, singleLine = true)
        OutlinedTextField(toDate, { toDate = it },
            modifier = Modifier.fillMaxWidth().testTag("export-to-date"),
            label = { Text(if (russian) "По дату (ГГГГ-ММ-ДД)" else "To date (YYYY-MM-DD)") }, singleLine = true)

        if (manager) {
            Column {
                Text(if (russian) "Кому выгрузить" else "Export scope")
                TextButton(enabled = !busy, onClick = { scopeMenuExpanded = true }) {
                    Text(scopeLabel(memberScope, members, russian))
                }
                DropdownMenu(expanded = scopeMenuExpanded, onDismissRequest = { scopeMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(if (russian) "Свои операции" else "My transactions") },
                        onClick = { memberScope = null; scopeMenuExpanded = false },
                    )
                    DropdownMenuItem(
                        text = { Text(if (russian) "Все участники" else "All members") },
                        onClick = { memberScope = "all"; scopeMenuExpanded = false },
                    )
                    members.forEach { member ->
                        DropdownMenuItem(
                            text = { Text(member.displayName) },
                            onClick = { memberScope = member.userId; scopeMenuExpanded = false },
                        )
                    }
                }
                if (membersLoading) Text(if (russian) "Загружаю участников…" else "Loading members…")
            }
        }

        if (validatedRequest == null) {
            Text(if (russian) "Проверьте даты: период должен быть от 1 до 366 дней." else
                "Check the dates: the period must be 1 to 366 days.", color = MaterialTheme.colorScheme.error)
        }
        error?.let { Text(localizeExportError(it, russian), color = MaterialTheme.colorScheme.error) }
        if (retryNeedsDuplicateWarning) {
            Text(if (russian) "Предыдущий запрос мог создать файл. Повтор может создать дубликат." else
                "The previous request may have created a file. Retrying may create a duplicate.",
                color = MaterialTheme.colorScheme.error)
            Button(enabled = !busy && validatedRequest != null && !retrySubmitted, onClick = {
                val request = validatedRequest ?: return@Button
                retrySubmitted = true
                onCreate(request)
            }, modifier = Modifier.testTag("retry-export")) {
                Text(if (busy) {
                    if (russian) "Повторяю…" else "Retrying…"
                } else if (russian) "Повторить запрос (возможен дубль)" else
                    "Retry request (may create duplicate)")
            }
        } else {
            Button(enabled = !busy && validatedRequest != null, onClick = {
                validatedRequest?.let(onCreate)
            }, modifier = Modifier.testTag("create-export")) {
                Text(if (busy) {
                    if (russian) "Создаю…" else "Creating…"
                } else if (russian) "Сформировать CSV" else "Create CSV")
            }
        }

        job?.let { current ->
            Card(Modifier.fillMaxWidth().testTag("export-job")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (russian) "Экспорт: ${statusLabel(current.status, true)}" else
                        "Export: ${statusLabel(current.status, false)}", style = MaterialTheme.typography.titleMedium)
                    Text(if (russian) "Операций: ${current.rowCount}" else "Transactions: ${current.rowCount}")
                    if (current.status == "ready" && current.rowCount == 0) {
                        Text(if (russian) "За период операций нет. CSV содержит только заголовки." else
                            "No transactions for this period. The CSV contains its headers only.")
                    }
                    if (current.status == "ready" && current.downloadUrl != null) {
                        Button(onClick = { onDownload(current.id) }, modifier = Modifier.testTag("download-export")) {
                            Text(if (russian) "Скачать CSV" else "Download CSV")
                        }
                    } else if (current.status == "ready") {
                        Text(if (russian) "Ссылка истекла или недоступна. Запросите свежую ссылку." else
                            "The link expired or is unavailable. Request a fresh link.")
                        Button(onClick = { onDownload(current.id) }, modifier = Modifier.testTag("download-export")) {
                            Text(if (russian) "Получить ссылку и скачать" else "Refresh link and download")
                        }
                    }
                    if (current.status == "queued" || current.status == "processing") {
                        TextButton(enabled = !busy, onClick = { onRefresh(current.id) }) {
                            Text(if (russian) "Обновить статус" else "Refresh status")
                        }
                    }
                }
            }
        }
    }
}

private fun scopeLabel(scope: String?, members: List<FinanceTenantMember>, russian: Boolean): String = when (scope) {
    "all" -> if (russian) "Все участники" else "All members"
    null -> if (russian) "Свои операции" else "My transactions"
    else -> members.firstOrNull { it.userId == scope }?.displayName ?: if (russian) "Участник" else "Member"
}

private fun statusLabel(status: String, russian: Boolean): String = when (status) {
    "queued" -> if (russian) "в очереди" else "queued"
    "processing" -> if (russian) "готовится" else "processing"
    "ready" -> if (russian) "готов" else "ready"
    "failed" -> if (russian) "ошибка" else "failed"
    else -> if (russian) "истёк" else "expired"
}

private fun localizeExportError(value: String, russian: Boolean): String = when (value) {
    "too_many_rows" -> if (russian) "Слишком много операций для одного файла." else
        "Too many transactions for one export."
    "forbidden" -> if (russian) "Нет доступа к выбранной области экспорта." else
        "You cannot export the selected scope."
    "invalid_request" -> if (russian) "Проверьте период и участников экспорта." else
        "Check the export period and member selection."
    else -> if (russian) "Не удалось выполнить экспорт. Проверьте соединение и повторите." else
        "Export failed. Check the connection and retry."
}
