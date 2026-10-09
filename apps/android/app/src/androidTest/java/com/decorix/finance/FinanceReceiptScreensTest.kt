package com.decorix.finance

import android.os.Handler
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FinanceReceiptScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun writerCanStartPhotoPicker() {
        var pickerCalls = 0
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner"))),
            onReceiptPick = { pickerCalls++ })

        compose.onNodeWithTag("receipt-open-document").assertIsDisplayed().performClick()
        assertEquals(1, pickerCalls)
    }

    @Test fun viewerCannotUploadAndSeesReadOnlyMessage() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("viewer"))))
        compose.onNodeWithTag("receipt-open-document").assertDoesNotExist()
        compose.onNodeWithText("Загрузка чеков недоступна для просмотра.").assertIsDisplayed()
    }

    @Test fun runningReceiptJobShowsLocalizedStageAndExactProgress() {
        val job = FinanceReceiptProcessingJob("job-29", "tenant-17", "document-9", "running", "ocr",
            63, 1, false, null, null, "2026-10-08T09:00:00Z", "2026-10-08T09:01:00Z")
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptJob = job, receiptUploadInProgress = true))

        compose.onNodeWithTag("receipt-upload-progress").assertIsDisplayed()
        compose.onNodeWithText("63% · Распознавание чека").assertIsDisplayed()
        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("63% · Receipt recognition").assertIsDisplayed()
    }

    @Test fun completedJobShowsReviewDraftWithoutCreatingTransaction() {
        val receipt = receiptDraft()
        val created = mutableListOf<String>()
        val confirmed = mutableListOf<String>()
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptJob = FinanceReceiptProcessingJob("job-29", "tenant-17", "document-9", "completed", "complete",
                100, 1, false, null, "receipt-42", "2026-10-08T09:00:00Z", "2026-10-08T09:01:00Z"),
            receiptDraft = receipt),
            onCreate = { type, amount, _ -> created += "$type:$amount" },
            onConfirmDraft = { id, _ -> confirmed += id })

        compose.onNodeWithTag("receipt-draft").assertIsDisplayed()
        compose.onNodeWithText("Магазин Тест").assertIsDisplayed()
        compose.onNodeWithText("245,70 ₽").assertIsDisplayed()
        compose.onNodeWithText("Хлеб", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Черновик · проверьте данные").assertIsDisplayed()
        assertEquals(emptyList<String>(), created)
        assertEquals(emptyList<String>(), confirmed)
    }

    @Test fun ownerBrowsesReceiptItemsEightThenOneThenEightWithoutMutationOrCashTotalChange() {
        val firstPage = receiptItemPage((1..8).map(::receiptItem), page = 1, totalItems = 9, hasMore = true)
        val secondPage = receiptItemPage(listOf(receiptItem(9)), page = 2, totalItems = 9, hasMore = false)
        val cashTotal = "101.00"
        val receipt = receiptDraft().copy(cashTotal = cashTotal, itemsTotal = "84.00", itemCount = 9,
            items = firstPage.items)
        val state = mutableStateOf(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptDraft = receipt, receiptItemsPage = firstPage, receiptItemsReceiptId = receipt.id,
            receiptItemsLoading = false, receiptItemsError = null))
        val requestedPages = mutableListOf<String>()
        val created = mutableListOf<String>()
        val confirmed = mutableListOf<String>()
        show(state.value, onCreate = { type, amount, _ -> created += "$type:$amount" },
            onConfirmDraft = { id, _ -> confirmed += id },
            onReceiptItemsPage = { receiptId, page ->
                requestedPages += "$receiptId:$page"
                state.value = state.value.copy(receiptItemsPage = if (page == 2) secondPage else firstPage,
                    receiptItemsReceiptId = receiptId, receiptItemsLoading = false, receiptItemsError = null)
            }, stateHolder = state)

        val missingItemValues = mutableListOf<String>()
        missingItemValues += assertReceiptItemPage(firstPage)
        assertEquals(cashTotal, state.value.receiptDraft?.cashTotal)
        compose.onNodeWithTag("receipt-items-next").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf("${receipt.id}:2"), requestedPages)
        missingItemValues += assertReceiptItemPage(secondPage)
        compose.onNodeWithTag("receipt-items-next").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("receipt-items-previous").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()

        missingItemValues += assertReceiptItemPage(firstPage)
        assertEquals(listOf("${receipt.id}:2", "${receipt.id}:1"), requestedPages)
        assertEquals(cashTotal, state.value.receiptDraft?.cashTotal)
        compose.onNodeWithText("101,00 ₽").performScrollTo().assertIsDisplayed()
        listOf("Добавить позицию", "Сохранить позицию", "Удалить позицию", "Синхронизировать итог",
            "Решить дубликат", "Подтвердить чек").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        assertEquals(emptyList<String>(), created)
        assertEquals(emptyList<String>(), confirmed)
        assertEquals("Missing exact item values: ${missingItemValues.joinToString()}", emptyList<String>(), missingItemValues)
    }

    @Test fun retryingReceiptItemsErrorDoesNotIssueHiddenThirdRequest() {
        val firstPage = receiptItemPage((1..8).map(::receiptItem), page = 1, totalItems = 9, hasMore = true)
        val receipt = receiptDraft().copy(itemCount = 9, items = firstPage.items)
        val state = mutableStateOf(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptDraft = receipt, receiptItemsPage = firstPage, receiptItemsReceiptId = receipt.id,
            receiptItemsLoading = false, receiptItemsError = null))
        val requests = AtomicInteger()
        val completions = AtomicInteger()
        val handler = Handler(Looper.getMainLooper())
        show(state.value, onReceiptItemsPage = { receiptId, page ->
            requests.incrementAndGet()
            state.value = state.value.copy(receiptItemsReceiptId = receiptId,
                receiptItemsRequestedPage = page, receiptItemsLoading = true, receiptItemsError = null)
            handler.postDelayed({
                completions.incrementAndGet()
                state.value = state.value.copy(receiptItemsRequestedPage = null,
                    receiptItemsLoading = false, receiptItemsError = "unavailable")
            }, 50)
        }, stateHolder = state)

        compose.onNodeWithTag("receipt-items-next").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { completions.get() >= 1 && state.value.receiptItemsError != null }
        compose.onNodeWithTag("receipt-items-retry").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            requests.get() >= 3 || (completions.get() >= 2 && state.value.receiptItemsError != null &&
                !state.value.receiptItemsLoading)
        }
        compose.waitForIdle()

        assertEquals("Retry should make one additional request, not trigger a hidden third request", 2, requests.get())
    }

    @Test fun nextWaitsForPageHasMoreAfterPageLoadError() {
        val firstPage = receiptItemPage((1..8).map(::receiptItem), page = 1, totalItems = 25, hasMore = true)
        val secondPage = receiptItemPage((9..16).map(::receiptItem), page = 2, totalItems = 25, hasMore = true)
        val receipt = receiptDraft().copy(itemCount = 25, items = firstPage.items)
        val state = mutableStateOf(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptDraft = receipt, receiptItemsPage = firstPage, receiptItemsReceiptId = receipt.id,
            receiptItemsLoading = false, receiptItemsError = null))
        val requestedPages = mutableListOf<Int>()
        show(state.value, onReceiptItemsPage = { receiptId, page ->
            requestedPages += page
            state.value = state.value.copy(receiptItemsReceiptId = receiptId,
                receiptItemsRequestedPage = page, receiptItemsLoading = false, receiptItemsError = "unavailable")
        }, stateHolder = state)

        compose.onNodeWithTag("receipt-items-next").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf(2), requestedPages)
        compose.onNodeWithTag("receipt-items-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receipt-items-next").performScrollTo().assertIsNotEnabled()

        state.value = state.value.copy(receiptItemsPage = secondPage, receiptItemsReceiptId = receipt.id,
            receiptItemsRequestedPage = null, receiptItemsLoading = false, receiptItemsError = null)
        compose.waitForIdle()
        compose.onNodeWithTag("receipt-items-next").performScrollTo().assertIsEnabled()
    }

    private fun assertReceiptItemPage(page: FinanceReceiptItemPage): List<String> {
        val missingValues = mutableListOf<String>()
        compose.onNodeWithTag("receipt-items-page").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Страница ${page.page}").performScrollTo().assertIsDisplayed()
        page.items.forEach { item ->
            val row = compose.onNodeWithTag("receipt-item-${item.id}").performScrollTo().assertIsDisplayed()
            listOf(item.name, requireNotNull(item.quantity), requireNotNull(item.unitPrice),
                requireNotNull(item.lineSum)).forEach { expected ->
                try {
                    row.assertTextContains(expected, substring = true)
                } catch (_: AssertionError) {
                    missingValues += "page ${page.page}: item ${item.id}: $expected"
                }
            }
        }
        val offPageIndex = if (page.page == 1) 9 else 1
        compose.onNodeWithText("Synthetic item $offPageIndex", substring = true).assertDoesNotExist()
        return missingValues
    }

    private fun receiptItemPage(items: List<FinanceReceiptItem>, page: Int, totalItems: Int,
                                hasMore: Boolean) = FinanceReceiptItemPage(items, page, totalItems, hasMore)

    private fun receiptItem(index: Int) = FinanceReceiptItem(
        id = "00000000-0000-4000-8000-%012d".format(index), name = "Synthetic item $index",
        quantity = "$index.000", unitPrice = "$index.10", lineSum = "$index.100",
        productKey = null, provenance = "ocr",
        confidence = null, categoryCode = null, verdict = null, advice = null, reviewReason = null,
        reviewAction = null, verdictSource = null, reviewProvider = null, reviewModelVersion = null,
        reviewPromptVersion = null, reviewAlgorithmVersion = null, version = index.toLong(),
    )

    @Test fun visionReadingStaysUnverifiedAndUnknownDraftFieldsStayNullInRussianAndEnglish() {
        val draft = receiptDraft().copy(merchant = null, receiptDate = null, cashTotal = null,
            categoryCode = null, categorySource = "unknown")
        val created = mutableListOf<String>()
        val confirmed = mutableListOf<String>()
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptDraft = draft, receiptReading = receiptReading()),
            onCreate = { type, amount, _ -> created += "$type:$amount" },
            onConfirmDraft = { id, _ -> confirmed += id })

        compose.onNodeWithTag("receipt-reading").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Непроверенное чтение Vision").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receipt-reading-vision-store").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic Vision Mart").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receipt-reading-vision-date").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2026-10-04").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receipt-reading-vision-total").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("900.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receipt-reading-ocr-text").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("OCR ORIGINAL: TOTAL 120.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receipt-reading-category-unknown").performScrollTo().assertIsDisplayed()

        assertNull(draft.merchant)
        assertNull(draft.receiptDate)
        assertNull(draft.cashTotal)
        assertNull(draft.categoryCode)
        assertEquals("unknown", draft.categorySource)
        assertEquals(emptyList<String>(), created)
        assertEquals(emptyList<String>(), confirmed)

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Unverified Vision reading").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receipt-reading-vision-store").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receipt-reading-vision-date").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receipt-reading-vision-total").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receipt-reading-category-unknown").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receipt-draft").performScrollTo().assertIsDisplayed()
        assertEquals(emptyList<String>(), created)
        assertEquals(emptyList<String>(), confirmed)
    }

    @Test fun missingReceiptReadingShowsUnavailableStateWithoutFillingVerifiedFields() {
        val draft = receiptDraft().copy(merchant = null, receiptDate = null, cashTotal = null,
            categoryCode = null, categorySource = "unknown")
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptDraft = draft, receiptReadingError = "not_found"))

        compose.onNodeWithTag("receipt-reading-unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receipt-reading").assertDoesNotExist()
        assertNull(draft.merchant)
        assertNull(draft.receiptDate)
        assertNull(draft.cashTotal)
        assertNull(draft.categoryCode)
        assertEquals("unknown", draft.categorySource)
    }

    @Test fun receiptFallbackReasonsUseLocalizedMessagesWithoutMachineCodes() {
        val reading = receiptReading().copy(
            visionFallbackReason = "VISION_UNAVAILABLE",
            ocrFallbackReason = "OCR_INVALID_RESPONSE",
            vision = requireNotNull(receiptReading().vision).copy(fallbackReason = "vision_model_unavailable"),
        )
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptDraft = receiptDraft(), receiptReading = reading))

        val missing = mutableListOf<String>()
        expectReceiptText(missing, "Резерв Vision: распознавание Vision временно недоступно")
        expectReceiptText(missing, "Резерв OCR: OCR не смог обработать изображение")
        expectReceiptText(missing, "Резерв модели Vision: выбранная модель Vision недоступна")
        collectReceiptMachineCodes(missing)

        compose.onNodeWithText("EN").performClick()
        expectReceiptText(missing, "Vision fallback: Vision recognition is temporarily unavailable")
        expectReceiptText(missing, "OCR fallback: OCR could not process the image")
        expectReceiptText(missing, "Vision model fallback: selected Vision model is unavailable")
        collectReceiptMachineCodes(missing)
        assertEquals("Missing localized fallback text or exposed machine codes: ${missing.joinToString()}", emptyList<String>(), missing)
    }

    @Test fun unknownReceiptFallbackReasonUsesGenericLocalizedText() {
        val reading = receiptReading().copy(ocrFallbackReason = "UNRECOGNIZED_FUTURE_CODE")
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptDraft = receiptDraft(), receiptReading = reading))

        val missing = mutableListOf<String>()
        expectReceiptText(missing, "Резерв OCR: Дополнительная информация о распознавании недоступна")
        collectReceiptMachineCodes(missing)

        compose.onNodeWithText("EN").performClick()
        expectReceiptText(missing, "OCR fallback: Additional recognition details are unavailable")
        collectReceiptMachineCodes(missing)
        assertEquals("Missing generic fallback text or exposed machine code: ${missing.joinToString()}", emptyList<String>(), missing)
    }

    @Test fun coreReceiptFallbackCodesUseLocalizedExplanationsWithoutRawCodes() {
        val visionCases = listOf(
            Triple("VISION_RESPONSE_TOO_LARGE", "ответ Vision слишком велик для обработки", "Vision response is too large to process"),
            Triple("VISION_UNSUPPORTED", "распознавание Vision не поддерживается", "Vision recognition is unsupported"),
            Triple("VISION_INVALID_RESPONSE", "Vision вернул некорректный результат", "Vision returned an invalid result"),
            Triple("VISION_TIMEOUT", "время распознавания Vision истекло", "Vision recognition timed out"),
            Triple("VISION_INTERRUPTED", "распознавание Vision было прервано", "Vision recognition was interrupted"),
            Triple("VISION_NOT_CONFIGURED", "распознавание Vision не настроено", "Vision recognition is not configured"),
        )
        val state = mutableStateOf(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptDraft = receiptDraft(), receiptReading = receiptReading()))
        show(state.value, stateHolder = state)
        val missing = mutableListOf<String>()

        visionCases.forEach { (code, ru, _) ->
            state.value = state.value.copy(receiptReading = receiptReading().copy(visionFallbackReason = code))
            compose.waitForIdle()
            expectReceiptText(missing, "Резерв Vision: $ru")
            expectReceiptCodeAbsent(missing, code)
        }
        state.value = state.value.copy(receiptReading = receiptReading().copy(ocrFallbackReason = "OCR_UNAVAILABLE"))
        compose.waitForIdle()
        expectReceiptText(missing, "Резерв OCR: OCR временно недоступен")
        expectReceiptCodeAbsent(missing, "OCR_UNAVAILABLE")

        compose.onNodeWithText("EN").performClick()
        visionCases.forEach { (code, _, en) ->
            state.value = state.value.copy(receiptReading = receiptReading().copy(visionFallbackReason = code))
            compose.waitForIdle()
            expectReceiptText(missing, "Vision fallback: $en")
            expectReceiptCodeAbsent(missing, code)
        }
        state.value = state.value.copy(receiptReading = receiptReading().copy(ocrFallbackReason = "OCR_UNAVAILABLE"))
        compose.waitForIdle()
        expectReceiptText(missing, "OCR fallback: OCR is temporarily unavailable")
        expectReceiptCodeAbsent(missing, "OCR_UNAVAILABLE")

        assertEquals("Missing mapped fallback text or exposed machine codes: ${missing.joinToString()}", emptyList<String>(), missing)
    }

    private fun expectReceiptText(missing: MutableList<String>, expected: String) {
        try {
            compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        } catch (_: AssertionError) {
            missing += expected
        }
    }

    private fun collectReceiptMachineCodes(missing: MutableList<String>) {
        listOf("VISION_RESPONSE_TOO_LARGE", "VISION_UNSUPPORTED", "VISION_INVALID_RESPONSE",
            "VISION_TIMEOUT", "VISION_INTERRUPTED", "VISION_NOT_CONFIGURED", "VISION_UNAVAILABLE",
            "OCR_UNAVAILABLE", "OCR_INVALID_RESPONSE", "vision_model_unavailable",
            "UNRECOGNIZED_FUTURE_CODE").forEach { expectReceiptCodeAbsent(missing, it) }
    }

    private fun expectReceiptCodeAbsent(missing: MutableList<String>, code: String) {
        try {
            compose.onNodeWithText(code, substring = true).assertDoesNotExist()
        } catch (_: AssertionError) {
            missing += "raw code: $code"
        }
    }

    @Test fun retryableJobKeepsStatusRefreshAvailable() {
        var refreshCalls = 0
        val job = FinanceReceiptProcessingJob("job-29", "tenant-17", "document-9", "retryable", "ocr",
            63, 2, true, "scanner_unavailable", null, "2026-10-08T09:00:00Z", "2026-10-08T09:01:00Z")
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), receiptJob = job,
            receiptUploadError = "retryable", receiptCanRetryUpload = true),
            onReceiptRefresh = { refreshCalls++ })

        compose.onNodeWithTag("receipt-refresh-status").assertIsDisplayed().performClick()
        assertEquals(1, refreshCalls)
    }

    @Test fun retryableJobKeepsManualRefreshEnabledWhileBackgroundPolling() {
        var refreshCalls = 0
        val job = FinanceReceiptProcessingJob("job-29", "tenant-17", "document-9", "retryable", "ocr",
            63, 2, true, "scanner_unavailable", null, "2026-10-08T09:00:00Z", "2026-10-08T09:01:00Z")
        show(FinanceUiState(authenticated = true, busy = true, tenants = listOf(tenant("owner")),
            receiptJob = job, receiptUploadInProgress = true, receiptUploadError = "retryable",
            receiptCanRetryUpload = true), onReceiptRefresh = { refreshCalls++ })

        compose.onNodeWithTag("receipt-refresh-status").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, refreshCalls)
    }

    @Test fun timedOutRunningJobKeepsManualStatusRefreshAvailable() {
        var refreshCalls = 0
        val job = FinanceReceiptProcessingJob("job-29", "tenant-17", "document-9", "running", "ocr",
            63, 2, false, null, null, "2026-10-08T09:00:00Z", "2026-10-08T09:01:00Z")
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), receiptJob = job,
            receiptUploadError = "timeout"), onReceiptRefresh = { refreshCalls++ })

        compose.onNodeWithTag("receipt-refresh-status").assertIsDisplayed().performClick()
        assertEquals(1, refreshCalls)
    }

    @Test fun completedJobCanRetryTransientReceiptLoadUntilDraftAppears() {
        val state = mutableStateOf(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptJob = FinanceReceiptProcessingJob("job-29", "tenant-17", "document-9", "completed", "complete",
                100, 1, false, null, "receipt-42", "2026-10-08T09:00:00Z", "2026-10-08T09:01:00Z"),
            receiptUploadError = "request"))
        var refreshCalls = 0
        show(state.value, onReceiptRefresh = {
            refreshCalls++
            state.value = if (refreshCalls == 1) state.value.copy(receiptUploadError = "request")
                else state.value.copy(receiptDraft = receiptDraft(), receiptUploadError = null)
        }, stateHolder = state)

        compose.onNodeWithTag("receipt-refresh-status").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("receipt-refresh-status").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(2, refreshCalls)
        compose.onNodeWithTag("receipt-draft").assertIsDisplayed()
        compose.onNodeWithText("Магазин Тест").assertIsDisplayed()
    }

    @Test fun activeReceiptJobPreventsSelectingAnotherPhoto() {
        val state = mutableStateOf(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptJob = FinanceReceiptProcessingJob("job-29", "tenant-17", "document-9", "queued", "queued",
                0, 1, false, null, null, "2026-10-08T09:00:00Z", "2026-10-08T09:00:00Z")))
        var pickerCalls = 0
        show(state.value, onReceiptPick = { pickerCalls++ }, stateHolder = state)

        compose.onNodeWithTag("receipt-open-document").assertIsNotEnabled()
        state.value = state.value.copy(receiptJob = FinanceReceiptProcessingJob("job-29", "tenant-17",
            "document-9", "running", "ocr", 63, 1, false, null, null,
            "2026-10-08T09:00:00Z", "2026-10-08T09:01:00Z"))
        compose.waitForIdle()
        compose.onNodeWithTag("receipt-open-document").assertIsNotEnabled()
        assertEquals(0, pickerCalls)
    }

    @Test fun unresolvedCheckpointWithoutLoadedJobCanRefreshStatus() {
        var refreshCalls = 0
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptCheckpointUnresolved = true, receiptUploadError = "request"),
            onReceiptRefresh = { refreshCalls++ })

        compose.onNodeWithTag("receipt-refresh-status").assertIsDisplayed().assertIsEnabled().performClick()

        assertEquals(1, refreshCalls)
    }

    @Test fun unrecoverablePendingPhotoOffersExplicitDiscardAndChooseNewPhoto() {
        var chooseNewPhotoCalls = 0
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            receiptUploadError = "file_read", receiptCanRetryUpload = true,
            receiptCheckpointUnresolved = true), onReceiptDiscard = { chooseNewPhotoCalls++ })

        compose.onNodeWithTag("receipt-discard-upload").assertIsDisplayed().assertIsEnabled().performClick()

        assertEquals(1, chooseNewPhotoCalls)
    }

    private fun tenant(role: String) = FinanceTenant("tenant-17", "Семья", role, "Europe/Moscow")

    private fun receiptDraft() = FinanceReceipt(
        id = "receipt-42", tenantId = "tenant-17", documentId = "document-9", state = "review_required",
        version = 1, transactionId = null, currency = "RUB", cashTotal = "245.70", itemsTotal = "245.70",
        merchant = "Магазин Тест", receiptDate = "2026-10-08", selectedReader = "ocr", categoryCode = null,
        categorySource = "unknown", categoryAlgorithmVersion = "receipt-category-v1", alcoholShare = null,
        leisureShare = null, leisure = false, duplicateDecision = "unknown", duplicateOfReceiptId = null,
        items = listOf(FinanceReceiptItem("item-3", "Хлеб", "1", "245.70", "245.70", null, "ocr", 0.94,
            null, null, null, null, null, null, null, null, null, null, 1)),
        itemCount = 1, createdAt = "2026-10-08T09:01:00Z",
    )

    private fun receiptReading() = FinanceModels.receiptReading(org.json.JSONObject("""
        {"text":"OCR ORIGINAL: TOTAL 120.00",
         "words":[{"text":"TOTAL","confidence":96.0,"box":{"x":31,"y":17,"width":52,"height":10}}],
         "provider":"tesseract","modelVersion":"tesseract-5.3.0","promptVersion":"tesseract-ocr.v2",
         "confidence":0.9600,"ocrTotal":"120.00",
         "ocrItems":[{"name":"Bread","quantity":"1","unitPrice":"120.00","lineSum":"120.00"}],
         "reconciliation":{"algorithmVersion":"receipt-reconciliation.v1","decision":"review_required",
           "selectedReader":"ocr","mismatchFields":["total"],"ocrItemsTotal":"120.00",
           "visionItemsTotal":"900.00","allowedDifference":"0.02","ocrItemsReconciled":true,
           "visionItemsReconciled":false,"itemEvidence":[{"visionOrdinal":1,"ocrOrdinal":1,
             "status":"corroborated"}],"suggestedTopUps":[]},
         "visionFallbackReason":null,"ocrFallbackReason":null,
         "vision":{"store":"Synthetic Vision Mart","date":"2026-10-04","total":"900.00",
           "items":[{"name":"Model Bread","quantity":"1","unitPrice":"900.00","lineSum":"900.00"}],
           "provider":"synthetic-vision","modelVersion":"synthetic-vision-v1",
           "promptVersion":"vision-prompt-v1","fallbackReason":null}}
    """))

    private fun show(state: FinanceUiState, onReceiptPick: () -> Unit = {},
                     onCreate: (String, String, String?) -> Unit = { _, _, _ -> },
                     onConfirmDraft: (String, Long) -> Unit = { _, _ -> },
                     onReceiptRefresh: () -> Unit = {},
                     onReceiptDiscard: () -> Unit = {},
                     onReceiptItemsPage: (String, Int) -> Unit = { _, _ -> },
                     stateHolder: androidx.compose.runtime.MutableState<FinanceUiState>? = null) {
        val language = mutableStateOf("ru")
        compose.setContent {
            MaterialTheme {
                FinanceScreen(state = stateHolder?.value ?: state, language = language.value,
                    onLanguage = { language.value = it }, onLogin = {}, onRefresh = {},
                    onCreate = onCreate, onProfileSave = { _, _ -> }, onCreateDraft = { _, _ -> },
                    onUpdateDraft = {}, onConfirmDraft = onConfirmDraft, onCancelDraft = { _, _ -> }, onLogout = {},
                    onBudgetUpdate = { _, _, _, _, _ -> }, onBudgetReset = {}, onBudgetProposal = {}, onBudgetApply = {},
                    onDebtCreate = { _, _, _, _ -> }, onDebtPay = { _, _, _ -> }, onDebtAdjust = { _, _, _ -> },
                    onDebtForecast = {}, onReportLoad = { _, _, _, _, _ -> }, onReceiptPick = onReceiptPick,
                    onReceiptRefresh = onReceiptRefresh, onReceiptDiscard = onReceiptDiscard,
                    onReceiptItemsPage = onReceiptItemsPage)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Чеки").performScrollTo().performClick()
    }
}
