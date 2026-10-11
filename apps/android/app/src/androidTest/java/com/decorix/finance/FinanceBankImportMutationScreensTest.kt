package com.decorix.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
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

/** F48 row choices and explicit confirm only; batch dedupe and undo remain F49. */
@RunWith(AndroidJUnit4::class)
class FinanceBankImportMutationScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rowSelectorsShowCoreStateAndAllAllowedTypesThenUseReturnedTotals() {
        val selected = mutableListOf<Pair<String, String?>>()
        val current = mutableStateOf(preview(rows = listOf(
            row(id = "purchase", kind = "purchase", amount = "-10.00", included = true,
                transactionType = "expense", selectionSource = "default"),
            row(id = "income", kind = "income", amount = "20.00", included = true,
                transactionType = "income", selectionSource = "default"),
            row(id = "refund", kind = "refund", amount = "3.00", included = true,
                transactionType = "refund", selectionSource = "default"),
            row(id = "fee", kind = "fee", amount = "-1.00", included = false,
                transactionType = null, exclusionReason = "bank_fee"),
        )))
        show(previewState = current, onSelect = { rowId, type ->
            selected += rowId to type
            // The screen must render the server-returned state, not calculate totals locally.
            current.value = when (rowId to type) {
                "purchase" to "transfer" -> current.value.copy(
                    revision = 2, expenseTotal = "0.00", transferTotal = "10.00",
                    rows = current.value.rows.map {
                        if (it.id == rowId) it.copy(transactionType = type, selectionSource = "user") else it
                    })
                "income" to "refund" -> current.value.copy(
                    revision = 3, incomeTotal = "10.00", refundTotal = "23.00",
                    rows = current.value.rows.map {
                        if (it.id == rowId) it.copy(transactionType = type, selectionSource = "user") else it
                    })
                "refund" to "transfer" -> current.value.copy(
                    revision = 4, refundTotal = "20.00", transferTotal = "13.00",
                    rows = current.value.rows.map {
                        if (it.id == rowId) it.copy(transactionType = type, selectionSource = "user") else it
                    })
                "fee" to "expense" -> current.value.copy(
                    revision = 5, expenseTotal = "1.00", excludedTotal = "0.00",
                    includedCount = 4, excludedCount = 0,
                    rows = current.value.rows.map {
                        if (it.id == rowId) it.copy(included = true, transactionType = type,
                            selectionSource = "user", exclusionReason = null) else it
                    })
                "fee" to null -> current.value.copy(
                    revision = 6, expenseTotal = "0.00", excludedTotal = "1.00",
                    includedCount = 3, excludedCount = 1,
                    rows = current.value.rows.map {
                        if (it.id == rowId) it.copy(included = false, transactionType = null,
                            selectionSource = "default", exclusionReason = "bank_fee") else it
                    })
                else -> error("Unexpected selection: $rowId / $type")
            }
        })

        scrollTo("bank-import-row-purchase")
        compose.onNodeWithTag("bank-import-row-state-purchase").assert(hasText("Включено", substring = true))
        scrollTo("bank-import-row-fee")
        compose.onNodeWithTag("bank-import-row-state-fee").assert(hasText("Исключено", substring = true))

        scrollTo("bank-import-row-type-purchase")
        compose.onNodeWithTag("bank-import-row-type-purchase").performClick()
        compose.onNodeWithText("Перевод").performClick()
        scrollTo("bank-import-row-type-income")
        compose.onNodeWithTag("bank-import-row-type-income").performClick()
        compose.onNodeWithText("Возврат").performClick()
        scrollTo("bank-import-row-type-refund")
        compose.onNodeWithTag("bank-import-row-type-refund").performClick()
        compose.onNodeWithText("Перевод").performClick()

        assertEquals(listOf(
            "purchase" to "transfer",
            "income" to "refund",
            "refund" to "transfer",
        ), selected)
        assertCoreTotal("income", "0,00")
        assertCoreTotal("refund", "20,00")
        assertCoreTotal("transfer", "13,00")

        scrollTo("bank-import-row-type-fee")
        compose.onNodeWithTag("bank-import-row-type-fee").performClick()
        compose.onNodeWithText("Расход").performClick()
        assertEquals("fee" to "expense", selected.last())
        scrollTo("bank-import-row-fee")
        compose.onNodeWithTag("bank-import-row-state-fee").assert(hasText("Включено", substring = true))
        compose.onNodeWithTag("bank-import-row-amount-fee").assert(hasText("-1,00", substring = true))
        assertCoreTotal("expense", "1,00")
        assertCoreTotal("excluded", "0,00")

        scrollTo("bank-import-row-type-fee")
        compose.onNodeWithTag("bank-import-row-type-fee").performClick()
        compose.onNodeWithText("Сбросить выбор").performClick()
        assertEquals("fee" to null, selected.last())
        scrollTo("bank-import-row-fee")
        compose.onNodeWithTag("bank-import-row-amount-fee").assert(hasText("-1,00", substring = true))
        assertCoreTotal("expense", "0,00")
        assertCoreTotal("excluded", "1,00")
    }

    @Test fun explicitConfirmationOnlyAppearsForIncludedRowsAndCommittedResultOffersUndo() {
        var confirmations = 0
        val current = mutableStateOf(preview(includedCount = 0, rows = listOf(
            row(id = "fee", kind = "fee", amount = "-1.00", included = false,
                transactionType = null, exclusionReason = "bank_fee"),
        )))
        show(previewState = current, onConfirm = {
            confirmations++
            current.value = current.value.copy(state = "committed", revision = 2,
                createdCount = 1, duplicateCount = 0, includedCount = 1)
        })

        compose.onAllNodesWithTag("bank-import-confirm").assertCountEquals(0)
        assertEquals("Confirmation must not happen before explicit tap", 0, confirmations)

        compose.runOnIdle {
            current.value = current.value.copy(includedCount = 1, excludedCount = 0)
        }
        scrollTo("bank-import-confirm")
        compose.onNodeWithTag("bank-import-confirm").assertIsDisplayed().assertIsEnabled()
        assertEquals(0, confirmations)
        compose.onNodeWithTag("bank-import-confirm").performClick()
        assertEquals(1, confirmations)
        scrollTo("bank-import-confirm-result")
        compose.onNodeWithTag("bank-import-confirm-result").assertIsDisplayed()
        scrollTo("bank-import-undo")
        compose.onNodeWithTag("bank-import-undo").assertIsDisplayed()

        compose.runOnIdle { current.value = current.value.copy(state = "needs_review", includedCount = 0) }
        compose.onAllNodesWithTag("bank-import-confirm").assertCountEquals(0)
        compose.onAllNodesWithTag("bank-import-undo").assertCountEquals(0)
        compose.runOnIdle { current.value = current.value.copy(state = "committed", includedCount = 1) }
        compose.onAllNodesWithTag("bank-import-confirm").assertCountEquals(0)
        scrollTo("bank-import-undo")
        compose.onNodeWithTag("bank-import-undo").assertIsDisplayed()
    }

    @Test fun pendingAndViewerCannotChangeRowsOrConfirm() {
        val role = mutableStateOf("owner")
        val busy = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = role.value,
                    language = "ru",
                    preview = preview(),
                    busy = busy.value,
                    error = null,
                    onRefresh = {},
                    canUpload = role.value != "viewer",
                    onSelectRow = { _, _ -> error("read-only or pending preview must block selection") },
                    onConfirm = { error("read-only or pending preview must block confirmation") },
                )
            }
        }
        scrollTo("bank-import-row-type-purchase")
        compose.onNodeWithTag("bank-import-row-type-purchase").assertIsNotEnabled()
        scrollTo("bank-import-confirm")
        compose.onNodeWithTag("bank-import-confirm").assertIsNotEnabled()

        compose.runOnIdle { role.value = "viewer"; busy.value = false }
        compose.onAllNodesWithTag("bank-import-row-type-purchase").assertCountEquals(0)
        compose.onAllNodesWithTag("bank-import-confirm").assertCountEquals(0)
    }

    @Test fun longStatementScrollsToLastOperation() {
        val rows = (1..40).map { index ->
            row(id = "row-$index", kind = "purchase", amount = "-${index}.00",
                merchant = "Операция $index", included = true, transactionType = "expense")
        }
        show(preview = preview(rows = rows, includedCount = rows.size))

        compose.onNodeWithTag("bank-import-preview").performScrollToNode(hasText("Операция 40"))
        compose.onNodeWithText("Операция 40").assertIsDisplayed()
    }

    private fun show(
        role: String = "owner",
        preview: FinanceBankImportPreview = preview(),
        previewState: androidx.compose.runtime.MutableState<FinanceBankImportPreview>? = null,
        busy: Boolean = false,
        onSelect: (String, String?) -> Unit = { _, _ -> },
        onConfirm: () -> Unit = {},
    ) {
        val shownPreview = previewState ?: mutableStateOf(preview)
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = role,
                    language = "ru",
                    preview = shownPreview.value,
                    busy = busy,
                    error = null,
                    onRefresh = {},
                    canUpload = role != "viewer",
                    onSelectRow = onSelect,
                    onConfirm = onConfirm,
                )
            }
        }
    }

    private fun assertCoreTotal(name: String, value: String) {
        scrollTo("bank-import-total-$name")
        compose.onNodeWithTag("bank-import-total-$name").assert(hasText(value, substring = true))
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("bank-import-preview").performScrollToNode(hasTestTag(tag))
    }

    private fun preview(
        state: String = "needs_review",
        revision: Long = 1,
        expenseTotal: String = "10.00",
        incomeTotal: String = "20.00",
        refundTotal: String = "3.00",
        transferTotal: String = "0.00",
        excludedTotal: String = "1.00",
        includedCount: Int = 3,
        excludedCount: Int = 1,
        createdCount: Int = 0,
        duplicateCount: Int = 0,
        rows: List<FinanceBankImportRow> = listOf(
            row(id = "purchase", kind = "purchase", amount = "-10.00", included = true,
                transactionType = "expense"),
            row(id = "income", kind = "income", amount = "20.00", included = true,
                transactionType = "income"),
            row(id = "refund", kind = "refund", amount = "3.00", included = true,
                transactionType = "refund"),
            row(id = "fee", kind = "fee", amount = "-1.00", included = false,
                transactionType = null, exclusionReason = "bank_fee"),
        ),
    ) = FinanceBankImportPreview(
        quality = "valid",
        periodStart = "2026-10-01",
        periodEnd = "2026-10-04",
        parsedExpenseTotal = "10.00",
        parsedIncomeTotal = "20.00",
        expectedExpenseTotal = "10.00",
        expectedIncomeTotal = "20.00",
        rows = rows,
        id = "import-1",
        tenantId = "tenant-1",
        state = state,
        revision = revision,
        expenseTotal = expenseTotal,
        incomeTotal = incomeTotal,
        refundTotal = refundTotal,
        transferTotal = transferTotal,
        excludedTotal = excludedTotal,
        includedCount = includedCount,
        excludedCount = excludedCount,
        createdCount = createdCount,
        duplicateCount = duplicateCount,
    )

    private fun row(
        id: String,
        kind: String,
        amount: String,
        merchant: String = kind,
        included: Boolean,
        transactionType: String?,
        selectionSource: String = "default",
        exclusionReason: String? = null,
    ) = FinanceBankImportRow(
        operationDate = "2026-10-02",
        operationTime = "12:34",
        signedAmount = amount,
        merchant = merchant,
        description = merchant,
        cardLast4 = null,
        id = id,
        kind = kind,
        transactionType = transactionType,
        included = included,
        selectionSource = selectionSource,
        exclusionReason = exclusionReason,
    )
}
