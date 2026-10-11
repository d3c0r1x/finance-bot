package com.decorix.finance

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class FinanceMerchantReclassificationCandidate(
    val transactionId: String,
    val version: Long,
    val currentCategoryCode: String,
    val amount: String,
    val occurredAt: String,
    val description: String,
)

data class FinanceMerchantReclassificationPreview(
    val merchant: String,
    val normalizedMerchant: String,
    val categoryCode: String,
    val candidates: List<FinanceMerchantReclassificationCandidate>,
)

data class FinanceMerchantReclassificationResult(
    val merchant: String,
    val categoryCode: String,
    val changedCount: Int,
    val transactionIds: List<String>,
)

internal object FinanceMerchantReclassificationModels {
    private val categories = setOf("еда", "транспорт", "жилье", "досуг", "одежда", "здоровье",
        "работа", "техника", "долги", "прочее")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    private val amount = Regex("(?:0|[1-9]\\d{0,17})\\.\\d{2}")

    fun normalizeMerchant(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)

    fun preview(json: JSONObject): FinanceMerchantReclassificationPreview {
        val merchant = json.requiredText("merchant", 120)
        val normalized = json.requiredText("normalizedMerchant", 120)
        require(normalizeMerchant(merchant) == normalized) { "Inconsistent normalized merchant" }
        val category = json.requiredCategory("categoryCode")
        val rows = json.getJSONArray("candidates")
        require(rows.length() <= 10_000) { "Too many reclassification candidates" }
        val seen = mutableSetOf<String>()
        val candidates = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val id = row.requiredText("transactionId", 36)
            require(uuid.matches(id) && runCatching { UUID.fromString(id) }.isSuccess) { "Invalid transaction id" }
            require(seen.add(id.lowercase())) { "Duplicate reclassification candidate" }
            val version = row.strictLong("version")
            require(version > 0L) { "Invalid transaction version" }
            val currentCategory = row.requiredCategory("currentCategoryCode")
            val value = row.requiredText("amount", 21)
            require(amount.matches(value)) { "Invalid candidate amount" }
            val occurredAt = row.requiredText("occurredAt", 40)
            Instant.parse(occurredAt)
            FinanceMerchantReclassificationCandidate(id, version, currentCategory, value,
                occurredAt, row.requiredText("description", 500))
        }
        return FinanceMerchantReclassificationPreview(merchant, normalized, category, candidates)
    }

    fun applyRequest(preview: FinanceMerchantReclassificationPreview): JSONObject {
        require(preview.merchant.isNotBlank() && preview.categoryCode in categories && preview.candidates.isNotEmpty())
        val ids = mutableSetOf<String>()
        return JSONObject().put("merchant", preview.merchant).put("categoryCode", preview.categoryCode)
            .put("candidates", JSONArray().also { rows -> preview.candidates.forEach { candidate ->
                require(uuid.matches(candidate.transactionId) && candidate.version > 0L &&
                    candidate.currentCategoryCode in categories && ids.add(candidate.transactionId.lowercase()))
                rows.put(JSONObject().put("transactionId", candidate.transactionId)
                    .put("version", candidate.version).put("currentCategoryCode", candidate.currentCategoryCode))
            } })
    }

    fun applyResult(json: JSONObject): FinanceMerchantReclassificationResult {
        val merchant = json.requiredText("merchant", 120)
        val category = json.requiredCategory("categoryCode")
        val count = json.strictLong("changedCount")
        require(count in 0..10_000) { "Invalid changed count" }
        val values = json.getJSONArray("transactionIds")
        require(values.length() <= 10_000 && values.length().toLong() == count) { "Invalid changed transaction list" }
        val ids = (0 until values.length()).map { values.getString(it) }
        require(ids.all { uuid.matches(it) && runCatching { UUID.fromString(it) }.isSuccess }) { "Invalid changed transaction id" }
        require(ids.map(String::lowercase).toSet().size == ids.size) { "Duplicate changed transaction id" }
        return FinanceMerchantReclassificationResult(merchant, category, count.toInt(), ids)
    }

    private fun JSONObject.requiredText(key: String, max: Int): String = getString(key).also {
        require(it.isNotBlank() && it.length <= max && it == it.trim()) { "Invalid $key" }
    }

    private fun JSONObject.requiredCategory(key: String): String = requiredText(key, 64).also {
        require(it in categories) { "Invalid $key" }
    }

    private fun JSONObject.strictLong(key: String): Long {
        val value = get(key)
        require(value is Number) { "Invalid $key" }
        return BigDecimal(value.toString()).longValueExact()
    }
}
