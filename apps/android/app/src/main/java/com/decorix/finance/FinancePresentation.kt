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

internal fun formatShoppingPurchaseCount(count: Int, language: String): String {
    if (language != "ru") return "$count purchases"
    val lastTwo = count % 100
    val noun = when {
        lastTwo in 11..14 -> "покупок"
        count % 10 == 1 -> "покупка"
        count % 10 in 2..4 -> "покупки"
        else -> "покупок"
    }
    return "$count $noun"
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

internal fun formatDebtForecastBasis(basis: String, language: String): String {
    val russian = language == "ru"
    return when (basis) {
        "Fixed minimum payment with monthly compound estimate; no interest is posted to ledger." ->
            if (russian) {
                "Прогноз по фиксированному минимальному платежу; проценты рассчитываются ежемесячно и не добавляются к остатку."
            } else {
                "Estimate uses a fixed minimum payment with monthly interest; interest is not posted to the ledger."
            }
        else -> if (russian) "Основание прогноза недоступно" else "Forecast basis unavailable"
    }
}

internal fun priceChangeMagnitudeFraction(changePercent: String): Float {
    val percent = changePercent.toBigDecimalOrNull()?.abs() ?: return 0f
    return percent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)
        .min(BigDecimal.ONE)
        .toFloat()
}

internal fun formatRollingFoodStatus(status: RollingFoodStatus, language: String, currency: String = "RUB"): String {
    val russian = language == "ru"
    val label = if (russian) "Еда за 7 дней" else "Food over 7 days"
    val window = "${status.fromDate}–${status.toDate}"
    val spent = formatMoney(status.spent, language, currency)
    val limit = if (status.limitStatus == "disabled") null else formatMoney(status.limit, language, currency)
    val remaining = status.remaining?.let { formatMoney(it, language, currency) }
    val limitStatus = if (status.limitStatus == "disabled") {
        if (russian) "Лимит отключён" else "Limit disabled"
    } else formatSemanticStatus("limitStatus", status.limitStatus, language)
    val paceStatus = formatSemanticStatus("paceStatus", status.paceStatus, language)
    val values = if (russian) buildList {
        add("потрачено $spent${limit?.let { " / $it" } ?: ""}")
        if (remaining != null) add("остаток $remaining")
        add(limitStatus)
        add(paceStatus)
    } else buildList {
        add("spent $spent${limit?.let { " / $it" } ?: ""}")
        if (remaining != null) add("remaining $remaining")
        add(limitStatus)
        add(paceStatus)
    }
    return "$label ($window): ${values.joinToString(" · ")}"
}
