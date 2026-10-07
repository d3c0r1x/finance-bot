package com.decorix.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

internal fun formatMoney(value: String?, language: String, currency: String = "RUB"): String {
    if (value == null || !value.matches(Regex("-?\\d{1,30}(?:\\.\\d{1,12})?"))) return "—"
    val amount = value.toBigDecimalOrNull() ?: return "—"
    val locale = if (language == "ru") Locale.forLanguageTag("ru-RU") else Locale.US
    val formatter = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        roundingMode = RoundingMode.HALF_EVEN
    }
    val symbol = if (language == "ru" && currency == "RUB") "₽" else currency
    return "${formatter.format(amount.setScale(2, RoundingMode.HALF_EVEN))} $symbol"
}

internal fun formatSemanticStatus(kind: String, code: String, language: String): String {
    val russian = language == "ru"
    val labels = when (kind) {
        "limitStatus" -> if (russian) mapOf(
            "disabled" to "Отключён", "normal" to "В норме", "near" to "Почти достигнут", "exceeded" to "Превышен",
        ) else mapOf(
            "disabled" to "Disabled", "normal" to "Within limit", "near" to "Near limit", "exceeded" to "Exceeded",
        )
        "paceStatus" -> if (russian) mapOf(
            "under" to "Медленнее обычного", "normal" to "Обычный темп", "over" to "Быстрее обычного",
            "insufficient_history" to "Недостаточно истории",
        ) else mapOf(
            "under" to "Slower than usual", "normal" to "Usual pace", "over" to "Faster than usual",
            "insufficient_history" to "Insufficient history",
        )
        else -> emptyMap()
    }
    return labels[code] ?: if (russian) "Неизвестный статус" else "Unknown status"
}
