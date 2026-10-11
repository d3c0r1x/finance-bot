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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun FinanceHealthSection(
    status: FinanceHealthStatus?,
    loading: Boolean,
    error: String?,
    language: String,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
) {
    val russian = language == "ru"
    val title = if (russian) "Состояние сервисов" else "Service status"
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onRefresh, modifier = Modifier.testTag("health-refresh")) {
                Text(if (loading) {
                    if (russian) "Проверяем…" else "Checking…"
                } else if (russian) "Обновить статус" else "Refresh status")
            }
        }
        Text(if (russian) "Доступность OCR чеков, распознавания фото и локального ИИ."
            else "Availability of receipt OCR, photo recognition and local AI.")

        if (loading && status == null) {
            Text(if (russian) "Проверяем доступность сервисов…" else "Checking services…")
        }
        if (status == null && !loading) {
            Text(if (error != null) {
                if (russian) "Состояние сервисов недоступно" else "Service status is unavailable"
            } else if (russian) "Состояние ещё не загружено" else "Status has not been loaded")
            Button(onClick = onRefresh, modifier = Modifier.testTag("health-retry")) {
                Text(if (russian) "Повторить" else "Retry")
            }
        }
        status?.let { value ->
            listOf(
                "localAi" to if (russian) "Локальный AI" else "Local AI",
                "receiptVision" to if (russian) "Проверка Vision чеков" else "Receipt vision",
                "receiptOcr" to if (russian) "OCR чеков" else "Receipt OCR",
            ).forEach { (key, label) ->
                value.capabilities[key]?.let { capability ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            Text(healthStatusLabel(capability.status, russian))
                            healthDiagnosticLabel(capability.diagnosticCode, russian)?.let { Text(it) }
                        }
                    }
                }
            }
        }
    }
}

private fun healthStatusLabel(status: String, russian: Boolean): String = when (status) {
    "available" -> if (russian) "Доступен" else "Available"
    "disabled" -> if (russian) "Отключён" else "Disabled"
    else -> if (russian) "Недоступен" else "Unavailable"
}

private fun healthDiagnosticLabel(code: String?, russian: Boolean): String? = when (code) {
    null -> null
    "OLLAMA_UNAVAILABLE" -> if (russian) "Сервис модели не отвечает." else "The model service is not responding."
    "MODEL_MISSING" -> if (russian) "Модель не найдена" else "Model is missing"
    "REMOTE_MODEL_STATUS_UNCHECKED" -> if (russian) "Список удалённых моделей не запрашивается." else
        "Remote model inventory is not queried."
    "MODEL_CONFIG_INVALID" -> if (russian) "Проверьте настройки локальной модели." else
        "Check the local model configuration."
    "VISION_DISABLED" -> if (russian) "Распознавание фото выключено." else "Photo recognition is disabled."
    "VISION_CONFIG_INVALID" -> if (russian) "Настройки распознавания фото требуют проверки." else
        "Photo recognition settings need review."
    "TESSERACT_MISSING" -> if (russian) "Компонент OCR не установлен." else "The OCR component is not installed."
    "HEALTH_CHECK_FAILED" -> if (russian) "Проверка зависимости не завершилась." else
        "A dependency check did not finish."
    "HEALTH_SERVICE_UNAVAILABLE" -> if (russian) "Сервис состояния временно недоступен." else
        "The status service is temporarily unavailable."
    else -> if (russian) "Недоступен" else "Unavailable"
}
