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
    val state: String = "needs_review",
    val revision: Long = 1L,
    val expenseTotal: String = "0.00",
    val incomeTotal: String = "0.00",
    val refundTotal: String = "0.00",
    val transferTotal: String = "0.00",
    val excludedTotal: String = "0.00",
    val includedCount: Int = rows.count { it.included },
    val excludedCount: Int = rows.count { !it.included },
    val createdCount: Int = 0,
    val duplicateCount: Int = 0,
)

data class FinanceBankImportRow(
    val operationDate: String,
    val operationTime: String,
    val signedAmount: String,
    val merchant: String?,
    val description: String,
    val cardLast4: String?,
    val id: String = "",
    val ordinal: Int = 0,
    val kind: String = "purchase",
    val amount: String = "0.00",
    val transactionType: String? = null,
    val included: Boolean = false,
    val selectionSource: String = "default",
    val exclusionReason: String? = null,
    val duplicate: Boolean = false,
    val duplicateOfTransactionId: String? = null,
    val outcome: String = "pending",
    val transactionId: String? = null,
)

internal object FinanceBankImportModels {
    private val amountPattern = Regex("(?:0|[1-9]\\d{0,17})\\.\\d{2}")
    private val signedAmountPattern = Regex("-?(?:0|[1-9]\\d{0,17})\\.\\d{2}")
    private val timePattern = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
    private val qualities = setOf("valid", "mismatch", "unverifiable")
    private val states = setOf("needs_review", "ready", "committed", "reverted", "failed")
    private val kinds = setOf("purchase", "income", "refund", "fee", "transfer_out", "transfer_in", "withdrawal", "internal")
    private val transactionTypes = setOf("expense", "income", "refund", "transfer")
    private val exclusionReasons = setOf("bank_fee", "external_transfer", "cash_withdrawal", "internal_transfer", "requires_review")
    private val outcomes = setOf("pending", "created", "duplicate", "excluded", "reverted")
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

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
            val amount = row.getString("amount").requireAmount()
            val kind = row.getString("kind")
            require(kind in kinds) { "Unsupported bank import row kind" }
            val transactionType = row.nullableString("transactionType")
            require(transactionType == null || transactionType in transactionTypes) { "Invalid bank import transaction type" }
            val included = row.getBoolean("included")
            val selectionSource = row.getString("selectionSource")
            require(selectionSource == "default" || selectionSource == "user") { "Invalid bank import selection source" }
            val exclusionReason = row.nullableString("exclusionReason")
            require(exclusionReason == null || exclusionReason in exclusionReasons) { "Invalid bank import exclusion reason" }
            val outcome = row.getString("outcome")
            require(outcome in outcomes) { "Invalid bank import row outcome" }
            val duplicateOfTransactionId = row.requiredNullableString("duplicateOfTransactionId")
                .also { require(it == null || uuidPattern.matches(it)) { "Invalid duplicate transaction id" } }
            val transactionId = row.requiredNullableString("transactionId")
                .also { require(it == null || uuidPattern.matches(it)) { "Invalid bank transaction id" } }
            FinanceBankImportRow(
                operationDate = operationDate,
                operationTime = operationTime,
                signedAmount = signedAmount,
                merchant = merchant,
                description = description,
                cardLast4 = cardLast4,
                id = row.getString("id").also { require(it.isNotBlank()) },
                ordinal = row.getInt("ordinal").also { require(it >= 0) },
                kind = kind,
                amount = amount,
                transactionType = transactionType,
                included = included,
                selectionSource = selectionSource,
                exclusionReason = exclusionReason,
                duplicate = row.getBoolean("duplicate"),
                duplicateOfTransactionId = duplicateOfTransactionId,
                outcome = outcome,
                transactionId = transactionId,
            )
        }
        val periodStart = json.getString("periodStart")
        val periodEnd = json.getString("periodEnd")
        requireDate(periodStart)
        requireDate(periodEnd)
        require(periodStart <= periodEnd) { "Invalid bank import period" }
        val state = json.getString("state")
        require(state in states) { "Unsupported bank import state" }
        val revision = json.getLong("revision")
        require(revision > 0L) { "Invalid bank import revision" }
        fun count(name: String): Int = json.getInt(name).also { require(it >= 0) { "Invalid bank import count" } }
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
            state = state,
            revision = revision,
            expenseTotal = json.getString("expenseTotal").requireAmount(),
            incomeTotal = json.getString("incomeTotal").requireAmount(),
            refundTotal = json.getString("refundTotal").requireAmount(),
            transferTotal = json.getString("transferTotal").requireAmount(),
            excludedTotal = json.getString("excludedTotal").requireAmount(),
            includedCount = count("includedCount"),
            excludedCount = count("excludedCount"),
            createdCount = count("createdCount"),
            duplicateCount = count("duplicateCount"),
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
