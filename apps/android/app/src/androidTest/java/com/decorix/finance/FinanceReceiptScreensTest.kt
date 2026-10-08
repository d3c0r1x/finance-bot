package com.decorix.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        compose.onNodeWithText("Хлеб").assertIsDisplayed()
        compose.onNodeWithText("Черновик · проверьте данные").assertIsDisplayed()
        assertEquals(emptyList<String>(), created)
        assertEquals(emptyList<String>(), confirmed)
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

    private fun show(state: FinanceUiState, onReceiptPick: () -> Unit = {},
                     onCreate: (String, String, String?) -> Unit = { _, _, _ -> },
                     onConfirmDraft: (String, Long) -> Unit = { _, _ -> },
                     onReceiptRefresh: () -> Unit = {},
                     onReceiptDiscard: () -> Unit = {},
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
                    onReceiptRefresh = onReceiptRefresh, onReceiptDiscard = onReceiptDiscard)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Чеки").performScrollTo().performClick()
    }
}
