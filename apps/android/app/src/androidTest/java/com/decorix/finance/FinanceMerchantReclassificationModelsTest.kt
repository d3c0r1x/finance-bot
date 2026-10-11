package com.decorix.finance

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F51 Core preview/apply DTO contract and strict parsing boundary. */
class FinanceMerchantReclassificationModelsTest {
    @Test fun previewPreservesCoreCandidateOrderAmountsAndApplySelectionExactly() {
        val preview = FinanceMerchantReclassificationModels.preview(previewJson())

        assertEquals("Пятёрочка", preview.merchant)
        assertEquals("пятёрочка", preview.normalizedMerchant)
        assertEquals("еда", preview.categoryCode)
        assertEquals(listOf(TRANSACTION_2, TRANSACTION_1), preview.candidates.map { it.transactionId })
        assertEquals(listOf(9L, 4L), preview.candidates.map { it.version })
        assertEquals(listOf("123.40", "999999999999999999.99"),
            preview.candidates.map { it.amount })
        assertEquals(listOf("прочее", "транспорт"), preview.candidates.map { it.currentCategoryCode })

        val request = FinanceMerchantReclassificationModels.applyRequest(preview)
        assertEquals(setOf("merchant", "categoryCode", "candidates"),
            request.keys().asSequence().toSet())
        assertEquals("Пятёрочка", request.getString("merchant"))
        assertEquals("еда", request.getString("categoryCode"))
        val selections = request.getJSONArray("candidates")
        assertEquals(2, selections.length())
        assertEquals(setOf("transactionId", "version", "currentCategoryCode"),
            selections.getJSONObject(0).keys().asSequence().toSet())
        assertEquals(listOf(TRANSACTION_2, TRANSACTION_1), (0 until selections.length()).map {
            selections.getJSONObject(it).getString("transactionId")
        })
        assertEquals(listOf(9L, 4L), (0 until selections.length()).map {
            selections.getJSONObject(it).getLong("version")
        })
    }

    @Test fun applyResultPreservesCoreCountCategoryAndTransactionOrder() {
        val result = FinanceMerchantReclassificationModels.applyResult(JSONObject()
            .put("merchant", "Пятёрочка")
            .put("categoryCode", "еда")
            .put("changedCount", 2)
            .put("transactionIds", JSONArray().put(TRANSACTION_2).put(TRANSACTION_1)))

        assertEquals("Пятёрочка", result.merchant)
        assertEquals("еда", result.categoryCode)
        assertEquals(2, result.changedCount)
        assertEquals(listOf(TRANSACTION_2, TRANSACTION_1), result.transactionIds)
    }

    @Test fun previewRejectsInvalidUuidVersionAmountCategoryDuplicateAndMissingFields() {
        assertPreviewRejected { it.getJSONArray("candidates").getJSONObject(0).put("transactionId", "bad-id") }
        assertPreviewRejected { it.getJSONArray("candidates").getJSONObject(0).put("version", 0) }
        assertPreviewRejected { it.getJSONArray("candidates").getJSONObject(0).put("version", 2.5) }
        assertPreviewRejected { it.getJSONArray("candidates").getJSONObject(0).put("amount", "1e3") }
        assertPreviewRejected { it.getJSONArray("candidates").getJSONObject(0).put("amount", "-1.00") }
        assertPreviewRejected { it.put("categoryCode", "gambling") }
        assertPreviewRejected { it.put("normalizedMerchant", "someone-else") }
        assertPreviewRejected {
            it.getJSONArray("candidates").getJSONObject(1).put("transactionId", TRANSACTION_2)
        }
        assertPreviewRejected { it.getJSONArray("candidates").getJSONObject(0).remove("currentCategoryCode") }
        assertPreviewRejected { it.remove("normalizedMerchant") }
    }

    @Test fun applyResultRejectsInvalidUuidCategoryCountDuplicateIdsAndMissingFields() {
        assertApplyResultRejected { it.put("transactionIds", JSONArray().put("not-a-uuid")) }
        assertApplyResultRejected { it.put("categoryCode", "unknown") }
        assertApplyResultRejected { it.put("changedCount", 1) }
        assertApplyResultRejected { it.put("transactionIds", JSONArray().put(TRANSACTION_2).put(TRANSACTION_2)) }
        assertApplyResultRejected { it.remove("changedCount") }
        assertApplyResultRejected { it.remove("merchant") }
    }

    private fun assertPreviewRejected(mutate: (JSONObject) -> Unit) {
        val value = previewJson()
        mutate(value)
        assertTrue(runCatching { FinanceMerchantReclassificationModels.preview(value) }.isFailure)
    }

    private fun assertApplyResultRejected(mutate: (JSONObject) -> Unit) {
        val value = JSONObject()
            .put("merchant", "Пятёрочка")
            .put("categoryCode", "еда")
            .put("changedCount", 2)
            .put("transactionIds", JSONArray().put(TRANSACTION_2).put(TRANSACTION_1))
        mutate(value)
        assertTrue(runCatching { FinanceMerchantReclassificationModels.applyResult(value) }.isFailure)
    }

    private fun previewJson() = JSONObject()
        .put("merchant", "Пятёрочка")
        .put("normalizedMerchant", "пятёрочка")
        .put("categoryCode", "еда")
        .put("candidates", JSONArray()
            .put(candidate(TRANSACTION_2, 9, "прочее", "123.40"))
            .put(candidate(TRANSACTION_1, 4, "транспорт", "999999999999999999.99")))

    private fun candidate(id: String, version: Number, category: String, amount: String) = JSONObject()
        .put("transactionId", id)
        .put("version", version)
        .put("currentCategoryCode", category)
        .put("amount", amount)
        .put("occurredAt", "2026-10-10T20:15:30Z")
        .put("description", "Покупка в магазине")

    private companion object {
        const val TRANSACTION_1 = "00000000-0000-4000-8000-000000000051"
        const val TRANSACTION_2 = "00000000-0000-4000-8000-000000000052"
    }
}
