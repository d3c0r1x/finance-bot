package com.decorix.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** F51 explicit historical review and apply. Core candidate IDs/versions/categories remain authoritative. */
@RunWith(AndroidJUnit4::class)
class FinanceMerchantReclassificationScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reviewShowsOnlyCoreCandidatesAndApplyWaitsForNonemptyPreviewAndExplicitTap() {
        val preview = mutableStateOf<FinanceMerchantReclassificationPreview?>(null)
        var reviewedMerchant: String? = null
        val applied = mutableListOf<FinanceMerchantReclassificationPreview>()
        show(
            preview = preview,
            onReview = { reviewedMerchant = it },
            onApply = { applied += it },
        )

        scrollTo("bank-import-review-past-purchase")
        compose.onNodeWithTag("bank-import-review-past-purchase").performClick()
        assertEquals("Market", reviewedMerchant)
        compose.onAllNodesWithTag("bank-import-reclassification-apply").assertCountEquals(0)
        assertEquals("Review must not change transactions", 0, applied.size)

        compose.runOnIdle { preview.value = reclassification(candidates = emptyList()) }
        scrollTo("bank-import-reclassification")
        compose.onNodeWithTag("bank-import-reclassification-no-candidates").assertIsDisplayed()
        compose.onNodeWithTag("bank-import-reclassification-apply").assertIsNotEnabled()

        val reviewed = reclassification(candidates = listOf(
            candidate("eligible-1", version = 4, currentCategory = "прочее", description = "Coffee"),
            candidate("eligible-2", version = 2, currentCategory = "досуг", description = "Cafe"),
        ))
        compose.runOnIdle { preview.value = reviewed }
        scrollTo("bank-import-reclassification-candidate-eligible-1")
        compose.onNodeWithTag("bank-import-reclassification-candidate-eligible-1")
            .assertIsDisplayed()
        compose.onNodeWithTag("bank-import-reclassification-category-eligible-1")
            .assertIsDisplayed().assert(hasText("Other → Food", substring = true))
        compose.onNodeWithTag("bank-import-reclassification-candidate-eligible-2").assertIsDisplayed()
        compose.onAllNodesWithTag("bank-import-reclassification-candidate-manually-edited")
            .assertCountEquals(0)
        compose.onNodeWithTag("bank-import-reclassification-apply").assertIsEnabled()
        assertEquals("Displaying review must not apply it", 0, applied.size)

        compose.onNodeWithTag("bank-import-reclassification-apply").performClick()
        assertEquals(listOf(reviewed), applied)
        assertEquals(
            listOf(
                "eligible-1" to (4L to "прочее"),
                "eligible-2" to (2L to "досуг"),
            ),
            applied.single().candidates.map { it.transactionId to (it.version to it.currentCategoryCode) },
        )
    }

    @Test fun stale412KeepsManualEditSafeAndRequiresFreshExplicitReview() {
        val preview = mutableStateOf(reclassification(candidates = listOf(
            candidate("edited-after-review", version = 1, currentCategory = "прочее", description = "Manual edit"),
        )))
        var applies = 0
        var reviews = 0
        val stale = mutableStateOf<String?>("stale_preview")
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = "owner", language = "en", preview = importPreview(), busy = false,
                    error = null, onRefresh = {}, canUpload = true,
                    reclassificationPreview = preview.value,
                    reclassificationError = stale.value,
                    onReviewMerchantReclassification = { reviews++ },
                    onApplyMerchantReclassification = { applies++ },
                )
            }
        }

        scrollTo("bank-import-reclassification-error")
        compose.onNodeWithTag("bank-import-reclassification-error")
            .assertIsDisplayed().assert(hasText("Transactions or mapping changed", substring = true))
        compose.onNodeWithTag("bank-import-reclassification-apply").assertIsNotEnabled()
        assertEquals("Stale reviewed IDs must not be resent", 0, applies)
        assertEquals("Stale response must not trigger a hidden preview retry", 0, reviews)

        // Fresh preview must come from a separate user action; stale candidate stays display-only.
        compose.onNodeWithTag("bank-import-review-past-purchase").performClick()
        assertEquals(1, reviews)
        assertEquals("Failed apply must not report success", 0, applies)
    }

    @Test fun viewerCannotReviewOrApplyAndBusyWriterCannotRepeatEitherAction() {
        val role = mutableStateOf("viewer")
        val busy = mutableStateOf(false)
        val preview = mutableStateOf(reclassification(candidates = listOf(
            candidate("eligible", version = 3, currentCategory = "прочее"),
        )))
        var reviews = 0
        var applies = 0
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = role.value, language = "ru", preview = importPreview(), busy = busy.value,
                    error = null, onRefresh = {}, canUpload = true,
                    reclassificationPreview = preview.value,
                    onReviewMerchantReclassification = { reviews++ },
                    onApplyMerchantReclassification = { applies++ },
                )
            }
        }

        compose.onAllNodesWithTag("bank-import-review-past-purchase").assertCountEquals(0)
        compose.onAllNodesWithTag("bank-import-reclassification-apply").assertCountEquals(0)
        assertEquals(0, reviews)
        assertEquals(0, applies)

        compose.runOnIdle { role.value = "owner"; busy.value = true }
        scrollTo("bank-import-review-past-purchase")
        compose.onNodeWithTag("bank-import-review-past-purchase").assertIsNotEnabled()
        scrollTo("bank-import-reclassification-apply")
        compose.onNodeWithTag("bank-import-reclassification-apply").assertIsNotEnabled()
        assertEquals(0, reviews)
        assertEquals(0, applies)
    }

    @Test fun manuallyCategorizedImportRowDoesNotOfferHistoricalReclassification() {
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = "owner", language = "en", preview = importPreview("human"), busy = false,
                    error = null, onRefresh = {}, canUpload = true,
                )
            }
        }

        compose.onAllNodesWithTag("bank-import-review-past-purchase").assertCountEquals(0)
    }

    @Test fun previewFailureRemainsVisibleWhenThereAreNoReviewedCandidates() {
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = "owner", language = "en", preview = importPreview(), busy = false,
                    error = null, onRefresh = {}, canUpload = true,
                    reclassificationError = "forbidden",
                )
            }
        }

        scrollTo("bank-import-reclassification-error")
        compose.onNodeWithTag("bank-import-reclassification-error")
            .assertIsDisplayed().assert(hasText("do not have access", substring = true))
        compose.onAllNodesWithTag("bank-import-reclassification-apply").assertCountEquals(0)
    }

    private fun show(
        preview: androidx.compose.runtime.MutableState<FinanceMerchantReclassificationPreview?>,
        onReview: (String) -> Unit,
        onApply: (FinanceMerchantReclassificationPreview) -> Unit,
    ) {
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = "owner", language = "en", preview = importPreview(), busy = false,
                    error = null, onRefresh = {}, canUpload = true,
                    onReviewMerchantReclassification = onReview,
                    reclassificationPreview = preview.value,
                    onApplyMerchantReclassification = onApply,
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("bank-import-preview").performScrollToNode(hasTestTag(tag))
    }

    private fun importPreview(categorySource: String = "mapping") = FinanceBankImportPreview(
        quality = "valid", periodStart = "2026-10-01", periodEnd = "2026-10-02",
        parsedExpenseTotal = "12.00", parsedIncomeTotal = "0.00",
        expectedExpenseTotal = "12.00", expectedIncomeTotal = "0.00",
        rows = listOf(FinanceBankImportRow(
            operationDate = "2026-10-01", operationTime = "09:30", signedAmount = "-12.00",
            merchant = "Market", description = "Payment", cardLast4 = null, id = "purchase",
            kind = "purchase", amount = "12.00", transactionType = "expense", included = true,
            categoryCode = "еда", categorySource = categorySource, outcome = "created",
            transactionId = "00000000-0000-4000-8000-000000000071",
        )),
        id = "import-1", tenantId = "tenant-1", state = "committed", revision = 3,
        expenseTotal = "12.00", includedCount = 1, excludedCount = 0,
    )

    private fun reclassification(candidates: List<FinanceMerchantReclassificationCandidate>) =
        FinanceMerchantReclassificationPreview(
            merchant = "Market", normalizedMerchant = "market", categoryCode = "еда", candidates = candidates,
        )

    private fun candidate(
        transactionId: String,
        version: Long,
        currentCategory: String,
        description: String = "Payment",
    ) = FinanceMerchantReclassificationCandidate(
        transactionId = transactionId, version = version, currentCategoryCode = currentCategory,
        amount = "12.00", occurredAt = "2026-10-01T09:30:00Z", description = description,
    )
}
