package com.decorix.finance

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F50 strict Core DTO parsing contract for bank-import category metadata. */
class FinanceBankImportClassificationModelsTest {
    @Test fun parsesCategoryFieldsAndEverySupportedProvenance() {
        listOf("human", "mapping", "model", "unknown").forEach { source ->
            val row = parse(rowJson(
                categoryCode = "еда",
                categorySource = source,
                suggestedCategoryCode = "транспорт",
                categoryConfidence = "0.875",
                clarificationCandidate = true,
            )).rows.single()

            assertEquals("еда", row.categoryCode)
            assertEquals(source, row.categorySource)
            assertEquals("транспорт", row.suggestedCategoryCode)
            assertEquals("0.875", row.categoryConfidence)
            assertTrue(row.clarificationCandidate)
        }
    }

    @Test fun absentOrNullOptionalCategoryValuesKeepLegacyRowsReadable() {
        val absent = rowJson().apply {
            remove("categoryCode")
            remove("suggestedCategoryCode")
            remove("categoryConfidence")
            remove("categorySource")
            remove("clarificationCandidate")
        }
        val absentRow = parse(absent).rows.single()
        assertNull(absentRow.categoryCode)
        assertEquals("unknown", absentRow.categorySource)
        assertNull(absentRow.suggestedCategoryCode)
        assertNull(absentRow.categoryConfidence)
        assertFalse(absentRow.clarificationCandidate)

        val nullRow = parse(rowJson(categoryCode = null, suggestedCategoryCode = null,
            categoryConfidence = null)).rows.single()
        assertNull(nullRow.categoryCode)
        assertEquals("unknown", nullRow.categorySource)
        assertNull(nullRow.suggestedCategoryCode)
        assertNull(nullRow.categoryConfidence)
        assertFalse(nullRow.clarificationCandidate)
    }

    @Test fun rejectsUnsupportedCategoryAndProvenanceValues() {
        assertInvalid(rowJson(categoryCode = "gambling"))
        assertInvalid(rowJson(suggestedCategoryCode = "other"))
        assertInvalid(rowJson(categorySource = "heuristic"))
    }

    @Test fun rejectsMalformedClarificationCandidateRatherThanHidingCoreFlag() {
        assertInvalid(rowJson().put("clarificationCandidate", "false"))
        assertInvalid(rowJson().put("clarificationCandidate", JSONObject.NULL))
    }

    @Test fun confidenceMatchesCoreSchemaExactly() {
        listOf("0.000", "0.001", "0.999", "1.000").forEach { confidence ->
            val row = parse(rowJson(categoryConfidence = confidence)).rows.single()
            assertEquals(confidence, row.categoryConfidence)
        }
        listOf("-0.001", "1.001", "0", "0.9", "0.99", "1.0", "0.9999", "NaN").forEach { confidence ->
            assertInvalid(rowJson(categoryConfidence = confidence))
        }
    }

    private fun parse(row: JSONObject) = FinanceBankImportModels.preview(
        JSONObject().apply {
            put("id", IMPORT_ID)
            put("tenantId", TENANT_ID)
            put("state", "needs_review")
            put("revision", 1)
            put("quality", "valid")
            put("periodStart", "2026-10-01")
            put("periodEnd", "2026-10-01")
            put("parsedExpenseTotal", "10.00")
            put("parsedIncomeTotal", "0.00")
            put("expectedExpenseTotal", JSONObject.NULL)
            put("expectedIncomeTotal", JSONObject.NULL)
            put("expenseTotal", "10.00")
            put("incomeTotal", "0.00")
            put("refundTotal", "0.00")
            put("transferTotal", "0.00")
            put("excludedTotal", "0.00")
            put("includedCount", 1)
            put("excludedCount", 0)
            put("createdCount", 0)
            put("duplicateCount", 0)
            put("rows", JSONArray().put(row))
        },
    )

    private fun rowJson(
        categoryCode: String? = null,
        categorySource: String? = "unknown",
        suggestedCategoryCode: String? = null,
        categoryConfidence: String? = null,
        clarificationCandidate: Boolean = false,
    ) = JSONObject().apply {
        put("id", ROW_ID)
        put("ordinal", 0)
        put("operationDate", "2026-10-01")
        put("operationTime", "12:00")
        put("signedAmount", "-10.00")
        put("amount", "10.00")
        put("kind", "purchase")
        put("transactionType", "expense")
        put("included", true)
        put("selectionSource", "default")
        put("exclusionReason", JSONObject.NULL)
        put("duplicate", false)
        put("duplicateOfTransactionId", JSONObject.NULL)
        put("outcome", "pending")
        put("transactionId", JSONObject.NULL)
        put("merchant", "Кофейня")
        put("description", "Покупка")
        put("cardLast4", JSONObject.NULL)
        put("categoryCode", categoryCode ?: JSONObject.NULL)
        put("categorySource", categorySource ?: JSONObject.NULL)
        put("suggestedCategoryCode", suggestedCategoryCode ?: JSONObject.NULL)
        put("categoryConfidence", categoryConfidence ?: JSONObject.NULL)
        put("clarificationCandidate", clarificationCandidate)
    }

    private fun assertInvalid(row: JSONObject) {
        assertTrue(runCatching { parse(row) }.isFailure)
    }

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000047"
        const val IMPORT_ID = "00000000-0000-4000-8000-000000000048"
        const val ROW_ID = "00000000-0000-4000-8000-000000000049"
    }
}
