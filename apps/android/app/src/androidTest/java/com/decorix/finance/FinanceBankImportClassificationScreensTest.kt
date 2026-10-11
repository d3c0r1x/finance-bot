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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** F50 import hypotheses and personal merchant mappings; historical F51 reclassification is separate. */
@RunWith(AndroidJUnit4::class)
class FinanceBankImportClassificationScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun classificationStartsOnlyAfterWriterTapAndOnlyForEligibleImportRows() {
        val role = mutableStateOf("owner")
        val busy = mutableStateOf(false)
        var classifyCalls = 0
        val current = mutableStateOf(preview(rows = listOf(
            row("purchase", included = true, transactionType = "expense", merchant = "Кофейня"),
            row("income", included = true, transactionType = "income", merchant = "Работодатель"),
            row("excluded", included = false, transactionType = null, merchant = "Комиссия банка"),
            row("missing-merchant", included = true, transactionType = "expense", merchant = null),
        )))
        show(role, busy, current, onClassify = { classifyCalls++ })

        scrollTo("bank-import-classify")
        compose.onNodeWithTag("bank-import-classify").assertIsDisplayed().assertIsEnabled()
        assertEquals("Classification must wait for explicit tap", 0, classifyCalls)
        compose.onNodeWithTag("bank-import-classify").performClick()
        assertEquals(1, classifyCalls)

        compose.runOnIdle { busy.value = true }
        scrollTo("bank-import-classify")
        compose.onNodeWithTag("bank-import-classify").assertIsNotEnabled()
        compose.runOnIdle { role.value = "viewer"; busy.value = false }
        compose.onAllNodesWithTag("bank-import-classify").assertCountEquals(0)
        assertEquals("Viewer must never start classification", 1, classifyCalls)

        compose.runOnIdle {
            role.value = "owner"
            current.value = current.value.copy(state = "committed")
        }
        compose.onAllNodesWithTag("bank-import-classify").assertCountEquals(0)
    }

    @Test fun coreHypothesisAndClarificationCandidatesRenderInRuAndEnWithoutSavingMapping() {
        val current = mutableStateOf(preview(rows = listOf(
            row("mapped-hypothesis", merchant = "Пятёрочка", categoryCode = "еда", categorySource = "model",
                suggestedCategoryCode = "еда", categoryConfidence = "0.880", clarificationCandidate = true),
            row("unmapped", merchant = "Малая покупка", categoryCode = "прочее", categorySource = "unknown",
                clarificationCandidate = false),
        )))
        val language = mutableStateOf("ru")
        var categorySaves = 0
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = "member", language = language.value, preview = current.value,
                    busy = false, error = null, onRefresh = {}, canUpload = true,
                    onSelectCategory = { _, _ -> categorySaves++ },
                )
            }
        }

        scrollTo("bank-import-row-hypothesis-mapped-hypothesis")
        compose.onNodeWithTag("bank-import-row-hypothesis-mapped-hypothesis")
            .assertIsDisplayed().assert(hasText("Гипотеза: еда · 88%"))
        scrollTo("bank-import-clarification")
        compose.onNodeWithTag("bank-import-clarification").assertIsDisplayed()
        compose.onNodeWithTag("bank-import-clarification-merchant-Пятёрочка").assertIsDisplayed()
        compose.onAllNodesWithTag("bank-import-clarification-merchant-Малая покупка").assertCountEquals(0)
        assertEquals("Rendering a suggestion must not persist a mapping", 0, categorySaves)

        compose.runOnIdle { language.value = "en" }
        scrollTo("bank-import-row-hypothesis-mapped-hypothesis")
        compose.onNodeWithTag("bank-import-row-hypothesis-mapped-hypothesis")
            .assert(hasText("Hypothesis: Food · 88%"))
        scrollTo("bank-import-clarification")
        compose.onNodeWithText("Categories need review").assertIsDisplayed()
        compose.onNodeWithText("Up to 6 merchants with at least RUB 300").assertIsDisplayed()
    }

    @Test fun explicitCategoryChoiceSavesOnlyEligibleRowAndWaitsForCoreResponse() {
        val current = mutableStateOf(preview(rows = listOf(
            row("purchase", merchant = "Кофейня", categoryCode = "прочее", categorySource = "unknown",
                included = true, transactionType = "expense"),
            row("income", merchant = "Работодатель", included = true, transactionType = "income"),
            row("excluded", merchant = "Комиссия", included = false, transactionType = null),
        )))
        val selections = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = "owner", language = "ru", preview = current.value, busy = false,
                    error = null, onRefresh = {}, canUpload = true,
                    onSelectCategory = { rowId, category -> selections += rowId to category },
                )
            }
        }

        scrollTo("bank-import-row-category-purchase")
        compose.onNodeWithTag("bank-import-row-category-purchase").performClick()
        compose.onNodeWithTag("bank-import-category-choice-еда").performClick()
        assertEquals(listOf("purchase" to "еда"), selections)
        // UI remains Core-authoritative; callback alone cannot claim the saved mapping.
        scrollTo("bank-import-row-category-state-purchase")
        compose.onNodeWithTag("bank-import-row-category-state-purchase").assert(hasText("прочее", substring = true))

        compose.runOnIdle {
            current.value = current.value.copy(rows = current.value.rows.map {
                if (it.id == "purchase") it.copy(categoryCode = "еда", categorySource = "human") else it
            })
        }
        scrollTo("bank-import-row-category-state-purchase")
        compose.onNodeWithTag("bank-import-row-category-state-purchase").assert(hasText("еда", substring = true))
        compose.onAllNodesWithTag("bank-import-row-category-income").assertCountEquals(0)
        compose.onAllNodesWithTag("bank-import-row-category-excluded").assertCountEquals(0)
    }

    private fun show(
        role: androidx.compose.runtime.MutableState<String>,
        busy: androidx.compose.runtime.MutableState<Boolean>,
        preview: androidx.compose.runtime.MutableState<FinanceBankImportPreview>,
        onClassify: () -> Unit,
    ) {
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = role.value, language = "ru", preview = preview.value,
                    busy = busy.value, error = null, onRefresh = {}, canUpload = true,
                    onClassify = onClassify,
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("bank-import-preview").performScrollToNode(hasTestTag(tag))
    }

    private fun preview(rows: List<FinanceBankImportRow>) = FinanceBankImportPreview(
        quality = "valid", periodStart = "2026-10-01", periodEnd = "2026-10-02",
        parsedExpenseTotal = "10.00", parsedIncomeTotal = "20.00",
        expectedExpenseTotal = "10.00", expectedIncomeTotal = "20.00", rows = rows,
        id = "import-1", tenantId = "tenant-1", state = "needs_review", revision = 1,
        expenseTotal = "10.00", incomeTotal = "20.00", includedCount = rows.count { it.included },
        excludedCount = rows.count { !it.included },
    )

    private fun row(
        id: String,
        merchant: String?,
        categoryCode: String? = null,
        categorySource: String = "unknown",
        suggestedCategoryCode: String? = null,
        categoryConfidence: String? = null,
        clarificationCandidate: Boolean = false,
        included: Boolean = true,
        transactionType: String? = "expense",
    ) = FinanceBankImportRow(
        operationDate = "2026-10-02", operationTime = "12:34", signedAmount = "-10.00",
        merchant = merchant, description = merchant ?: "Без магазина", cardLast4 = null,
        id = id, kind = "purchase", amount = "10.00", transactionType = transactionType,
        included = included, selectionSource = "default", exclusionReason = if (included) null else "bank_fee",
        categoryCode = categoryCode, categorySource = categorySource,
        suggestedCategoryCode = suggestedCategoryCode, categoryConfidence = categoryConfidence,
        clarificationCandidate = clarificationCandidate,
    )
}
