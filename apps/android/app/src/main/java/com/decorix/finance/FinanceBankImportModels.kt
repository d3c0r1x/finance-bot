package com.decorix.finance

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

data class FinanceBankImportPreview(
    val quality: String,
    val periodStart: String,
    val periodEnd: String,
    val parsedExpenseTotal: String,
    val parsedIncomeTotal: String,
    val expectedExpenseTotal: String?,
    val expectedIncomeTotal: String?,
    val rows: List<FinanceBankImportRow>,
    val id: String = "",
    val tenantId: String = "",
)

data class FinanceBankImportRow(
    val operationDate: String,
    val operationTime: String,
    val signedAmount: String,
    val merchant: String?,
    val description: String,
    val cardLast4: String?,
)

internal object FinanceBankImportModels {
    private val amountPattern = Regex("(?:0|[1-9]\\d{0,17})\\.\\d{2}")
    private val signedAmountPattern = Regex("-?(?:0|[1-9]\\d{0,17})\\.\\d{2}")
    private val timePattern = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
    private val qualities = setOf("valid", "mismatch", "unverifiable")

    fun preview(json: JSONObject): FinanceBankImportPreview {
        val quality = json.getString("quality")
        require(quality in qualities) { "Unsupported bank import quality" }
        val rows = json.getJSONArray("rows")
        require(rows.length() in 1..10_000) { "Invalid bank import rows" }
        val resultRows = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val merchant = row.nullableString("merchant")
            val description = row.getString("description")
            require(description.isNotBlank()) { "Invalid bank import row description" }
            val cardLast4 = row.nullableString("cardLast4")
            require(cardLast4 == null || cardLast4.matches(Regex("\\d{4}"))) {
                "Invalid bank import card suffix"
            }
            val operationDate = row.getString("operationDate").also(::requireDate)
            val operationTime = row.getString("operationTime")
            require(timePattern.matches(operationTime)) { "Invalid bank import operation time" }
            try {
                LocalTime.parse(operationTime)
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("Invalid bank import operation time")
            }
            val signedAmount = row.getString("signedAmount")
            require(signedAmountPattern.matches(signedAmount) && signedAmount != "0.00" && signedAmount != "-0.00") {
                "Invalid bank import signed amount"
            }
            FinanceBankImportRow(operationDate, operationTime, signedAmount, merchant, description, cardLast4)
        }
        val periodStart = json.getString("periodStart")
        val periodEnd = json.getString("periodEnd")
        requireDate(periodStart)
        requireDate(periodEnd)
        require(periodStart <= periodEnd) { "Invalid bank import period" }
        return FinanceBankImportPreview(
            quality = quality,
            periodStart = periodStart,
            periodEnd = periodEnd,
            parsedExpenseTotal = json.getString("parsedExpenseTotal").requireAmount(),
            parsedIncomeTotal = json.getString("parsedIncomeTotal").requireAmount(),
            expectedExpenseTotal = json.requiredNullableString("expectedExpenseTotal")?.requireAmount(),
            expectedIncomeTotal = json.requiredNullableString("expectedIncomeTotal")?.requireAmount(),
            rows = resultRows,
            id = json.getString("id").also { require(it.isNotBlank()) },
            tenantId = json.getString("tenantId").also { require(it.isNotBlank()) },
        )
    }

    private fun String.requireAmount(): String = also {
        require(amountPattern.matches(it)) { "Invalid bank import amount" }
    }

    private fun requireDate(value: String) {
        require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Invalid bank import date" }
        try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("Invalid bank import date")
        }
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun JSONObject.requiredNullableString(name: String): String? {
        require(has(name)) { "Missing bank import field: $name" }
        return if (isNull(name)) null else getString(name)
    }
}
