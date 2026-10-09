package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptItemUpdateWithPageRefreshTest {
    @Test fun conflictRefreshKeepsUpdatedReceiptAndPreviousPageWhenPageReloadFails() {
        val previousPage = FinanceReceiptItemPage(
            listOf(syntheticReceipt(version = 4).items.single()), page = 2, totalItems = 9, hasMore = true,
        )
        val updatedReceipt = syntheticReceipt(version = 9)
        val pageFailure = IllegalStateException("synthetic conflict page reload failed")
        var requestedReceipt: FinanceReceipt? = null

        val result = refreshReceiptAfterConflictWithPage(
            currentPage = previousPage,
            refreshReceipt = { updatedReceipt },
            refreshPage = { receipt ->
                requestedReceipt = receipt
                throw pageFailure
            },
        )

        assertSame(updatedReceipt, requestedReceipt)
        assertSame(updatedReceipt, result.updatedReceipt)
        assertEquals(9L, result.updatedReceipt?.version)
        assertSame(previousPage, result.page)
        assertNull(result.receiptRefreshError)
        assertSame(pageFailure, result.pageRefreshError)
    }

    @Test fun patchFailureRemainsMutationFailureAndDoesNotReloadPage() {
        val receipt = syntheticReceipt(version = 4)
        val page = FinanceReceiptItemPage(listOf(receipt.items.single()), 1, 1, false)
        val patchFailure = IllegalStateException("synthetic patch rejected")
        var refreshCalled = false

        val result = updateReceiptItemWithPageRefresh(
            update = { throw patchFailure },
            refreshPage = { refreshCalled = true; page },
        )

        assertNull(result.updatedReceipt)
        assertNull(result.updatedPage)
        assertSame(patchFailure, result.mutationError)
        assertNull(result.pageRefreshError)
        assertFalse(refreshCalled)
    }

    @Test fun pageReloadFailureRetainsSuccessfulPatchAndReportsSeparateRefreshFailure() {
        val updated = syntheticReceipt(version = 9).copy(cashTotal = "245.70", itemsTotal = "246.80")
        val refreshFailure = IllegalStateException("synthetic page reload failed")
        var refreshedReceipt: FinanceReceipt? = null

        val result = updateReceiptItemWithPageRefresh(
            update = { updated },
            refreshPage = { receipt ->
                refreshedReceipt = receipt
                throw refreshFailure
            },
        )

        assertSame(updated, refreshedReceipt)
        assertSame(updated, result.updatedReceipt)
        assertEquals(9L, result.updatedReceipt?.version)
        assertEquals("245.70", result.updatedReceipt?.cashTotal)
        assertEquals("246.80", result.updatedReceipt?.itemsTotal)
        assertNull(result.updatedPage)
        assertNull(result.mutationError)
        assertSame(refreshFailure, result.pageRefreshError)
        assertTrue(result.pageRefreshError?.message?.contains("page reload") == true)
    }

    private fun syntheticReceipt(version: Long): FinanceReceipt {
        val item = FinanceReceiptItem(
            id = "00000000-0000-4000-8000-000000000001", name = "Synthetic bread",
            quantity = "2.000", unitPrice = "123.40", lineSum = "246.80", productKey = null,
            provenance = "ocr", confidence = null, categoryCode = null, verdict = null, advice = null,
            reviewReason = null, reviewAction = null, verdictSource = null, reviewProvider = null,
            reviewModelVersion = null, reviewPromptVersion = null, reviewAlgorithmVersion = null, version = 2,
        )
        return FinanceReceipt(
            id = "00000000-0000-4000-8000-000000000042", tenantId = "00000000-0000-4000-8000-000000000017",
            documentId = null, state = "review_required", version = version, transactionId = null, currency = "RUB",
            cashTotal = "245.70", itemsTotal = "246.80", merchant = null, receiptDate = null,
            selectedReader = "ocr", categoryCode = null, categorySource = "unknown",
            categoryAlgorithmVersion = "receipt-category.v1", alcoholShare = null, leisureShare = null,
            leisure = false, duplicateDecision = "unknown", duplicateOfReceiptId = null, items = listOf(item),
            itemCount = 1, createdAt = "2026-10-09T08:00:00Z",
        )
    }
}
