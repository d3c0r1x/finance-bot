package com.decorix.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** F49 Android presentation contract for duplicate outcomes and batch undo. */
@RunWith(AndroidJUnit4::class)
class FinanceBankImportUndoScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun duplicateCreatedAndRevertedRowsShowCoreOutcomes() {
        show(preview = preview(state = "reverted", rows = listOf(
            row(id = "duplicate", description = "Повтор покупки", outcome = "duplicate",
                duplicate = true, duplicateOfTransactionId = EXISTING_TRANSACTION_ID),
            row(id = "created", description = "Созданная покупка", outcome = "created",
                transactionId = CREATED_TRANSACTION_ID),
            row(id = "reverted", description = "Отменённая покупка", outcome = "reverted"),
        )))

        assertOutcome("duplicate", "Уже импортировано · пропущено")
        assertOutcome("created", "Добавлено")
        assertOutcome("reverted", "Отменено")
        compose.onAllNodesWithTag("bank-import-undo").assertCountEquals(0)
    }

    @Test fun committedUndoIsShownOnlyWithWritableOwnedImportCapability() {
        // The app only holds a staged import ID after this authenticated member uploaded it.
        val ownerCapability = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = "owner",
                    language = "ru",
                    preview = preview(state = "committed"),
                    busy = false,
                    error = null,
                    onRefresh = {},
                    onUndo = {},
                    canUpload = ownerCapability.value,
                )
            }
        }

        scrollTo("bank-import-undo")
        compose.onNodeWithTag("bank-import-undo").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { ownerCapability.value = false }
        compose.onAllNodesWithTag("bank-import-undo").assertCountEquals(0)
    }

    @Test fun undoNeedsAnExplicitConfirmationDialogAction() {
        var undoCalls = 0
        show(preview = preview(state = "committed"), canUpload = true, onUndo = { undoCalls++ })

        scrollTo("bank-import-undo")
        compose.onNodeWithTag("bank-import-undo").assertIsDisplayed()
        assertEquals("Undo must wait for explicit confirmation", 0, undoCalls)
        compose.onNodeWithTag("bank-import-undo").performClick()
        compose.onNodeWithTag("bank-import-undo-dialog").assertIsDisplayed()
        compose.onNodeWithText("Отменить импорт?").assertIsDisplayed()
        assertEquals(0, undoCalls)
        compose.onNodeWithTag("bank-import-undo-cancel").performClick()
        assertEquals("Cancel must not send a Core mutation", 0, undoCalls)

        scrollTo("bank-import-undo")
        compose.onNodeWithTag("bank-import-undo").performClick()
        compose.onNodeWithTag("bank-import-undo-confirm").performClick()
        assertEquals("Only dialog confirmation starts undo", 1, undoCalls)
    }

    @Test fun pendingUndoIsDisabled() {
        show(preview = preview(state = "committed"), canUpload = true, busy = true,
            onUndo = { error("Pending undo must block another request") })

        scrollTo("bank-import-undo")
        compose.onNodeWithTag("bank-import-undo").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun conflictKeepsBatchCommittedAndNamesChangedTransactionByRowDescription() {
        val committed = preview(state = "committed", revision = 2, createdCount = 1, rows = listOf(
            row(id = "row-conflict", description = "Покупка в городском маркете", outcome = "created",
                transactionId = CONFLICTING_TRANSACTION_ID),
        ))
        val result = FinanceBankImportUndoResult(
            id = IMPORT_ID,
            state = "committed",
            revision = 2,
            revertedCount = 0,
            conflicts = listOf(CONFLICTING_TRANSACTION_ID),
        )

        show(preview = committed, canUpload = true, undoResult = result)

        scrollTo("bank-import-confirm-result")
        compose.onNodeWithTag("bank-import-confirm-result")
            .assert(hasText("Импорт выполнен", substring = true))
        scrollTo("bank-import-undo-result")
        compose.onNodeWithTag("bank-import-undo-result")
            .assert(hasText("Отмена остановлена", substring = true))
            .assert(hasText("Покупка в городском маркете", substring = true))
        scrollTo("bank-import-undo")
        compose.onNodeWithTag("bank-import-undo").assertIsDisplayed()
        compose.onAllNodesWithText(CONFLICTING_TRANSACTION_ID).assertCountEquals(0)
    }

    @Test fun successfulUndoUsesCoreRevertedStateAndCount() {
        val result = FinanceBankImportUndoResult(
            id = IMPORT_ID,
            state = "reverted",
            revision = 3,
            revertedCount = 2,
            conflicts = emptyList(),
        )
        show(preview = preview(state = "reverted", revision = 3,
            rows = listOf(row(id = "reverted-1", description = "Первая", outcome = "reverted"),
                row(id = "reverted-2", description = "Вторая", outcome = "reverted"))),
            undoResult = result)

        scrollTo("bank-import-undo-result")
        compose.onNodeWithTag("bank-import-undo-result")
            .assert(hasText("Импорт отменён", substring = true))
            .assert(hasText("2", substring = true))
        scrollTo("bank-import-row-outcome-reverted-1")
        compose.onNodeWithTag("bank-import-row-outcome-reverted-1")
            .assert(hasText("Отменено", substring = true))
        scrollTo("bank-import-row-outcome-reverted-2")
        compose.onNodeWithTag("bank-import-row-outcome-reverted-2")
            .assert(hasText("Отменено", substring = true))
        compose.onAllNodesWithTag("bank-import-undo").assertCountEquals(0)
    }

    @Test fun viewerNeverSeesBatchUndo() {
        show(role = "viewer", preview = preview(state = "committed"), canUpload = true,
            onUndo = { error("Viewer must never invoke undo") })

        compose.onAllNodesWithTag("bank-import-undo").assertCountEquals(0)
    }

    private fun show(
        role: String = "owner",
        preview: FinanceBankImportPreview,
        canUpload: Boolean = role != "viewer",
        undoResult: FinanceBankImportUndoResult? = null,
        busy: Boolean = false,
        onUndo: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = role,
                    language = "ru",
                    preview = preview,
                    busy = busy,
                    error = null,
                    onRefresh = {},
                    canUpload = canUpload,
                    undoResult = undoResult,
                    onUndo = onUndo,
                )
            }
        }
    }

    private fun assertOutcome(id: String, text: String) {
        compose.onNodeWithTag("bank-import-preview").performScrollToNode(hasTestTag("bank-import-row-outcome-$id"))
        compose.onNodeWithTag("bank-import-row-outcome-$id").assert(hasText(text))
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("bank-import-preview").performScrollToNode(hasTestTag(tag))
    }

    private fun preview(
        state: String,
        revision: Long = 1,
        createdCount: Int = 0,
        rows: List<FinanceBankImportRow> = listOf(
            row(id = "purchase", description = "Покупка", outcome = "created",
                transactionId = CREATED_TRANSACTION_ID),
        ),
    ) = FinanceBankImportPreview(
        quality = "valid",
        periodStart = "2026-10-01",
        periodEnd = "2026-10-04",
        parsedExpenseTotal = "30.00",
        parsedIncomeTotal = "0.00",
        expectedExpenseTotal = "30.00",
        expectedIncomeTotal = "0.00",
        rows = rows,
        id = IMPORT_ID,
        tenantId = TENANT_ID,
        state = state,
        revision = revision,
        expenseTotal = "30.00",
        incomeTotal = "0.00",
        refundTotal = "0.00",
        transferTotal = "0.00",
        excludedTotal = "0.00",
        includedCount = rows.size,
        excludedCount = 0,
        createdCount = createdCount,
        duplicateCount = rows.count { it.duplicate },
    )

    private fun row(
        id: String,
        description: String,
        outcome: String,
        duplicate: Boolean = false,
        duplicateOfTransactionId: String? = null,
        transactionId: String? = null,
    ) = FinanceBankImportRow(
        operationDate = "2026-10-02",
        operationTime = "12:34",
        signedAmount = "-10.00",
        merchant = description,
        description = description,
        cardLast4 = null,
        id = id,
        ordinal = 0,
        kind = "purchase",
        amount = "10.00",
        transactionType = "expense",
        included = outcome != "duplicate",
        selectionSource = "default",
        duplicate = duplicate,
        duplicateOfTransactionId = duplicateOfTransactionId,
        outcome = outcome,
        transactionId = transactionId,
    )

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000047"
        const val IMPORT_ID = "00000000-0000-4000-8000-000000000048"
        const val EXISTING_TRANSACTION_ID = "00000000-0000-4000-8000-000000000060"
        const val CREATED_TRANSACTION_ID = "00000000-0000-4000-8000-000000000061"
        const val CONFLICTING_TRANSACTION_ID = "00000000-0000-4000-8000-000000000062"
    }
}
