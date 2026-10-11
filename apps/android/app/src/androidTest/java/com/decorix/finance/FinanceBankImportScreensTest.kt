package com.decorix.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** F47 is a read-only statement preview. Row decisions and confirmation belong to F48/F49. */
@RunWith(AndroidJUnit4::class)
class FinanceBankImportScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun qualityStatesAreDisplayedAsReturnedByCoreInRussianAndEnglish() {
        val current = mutableStateOf(preview(quality = "valid"))
        show(previewState = current)

        assertVisible("Итоги выписки сверены")
        compose.runOnIdle { current.value = preview(quality = "mismatch") }
        assertVisible("Расхождение итогов выписки")
        compose.runOnIdle { current.value = preview(quality = "unverifiable") }
        assertVisible("Итоги выписки не удалось проверить")

        compose.runOnIdle { current.value = preview(quality = "valid") }
        compose.runOnIdle { language.value = "en" }
        assertVisible("Statement totals reconciled")
        compose.runOnIdle { current.value = preview(quality = "mismatch") }
        assertVisible("Statement totals do not match")
        compose.runOnIdle { current.value = preview(quality = "unverifiable") }
        assertVisible("Statement totals could not be verified")
    }

    @Test fun nullExpectedBankTotalsStayUnknownAndDoNotRenderAsZero() {
        show(preview = preview(
            quality = "unverifiable",
            parsedExpenseTotal = "1234.56",
            parsedIncomeTotal = "45.67",
            expectedExpenseTotal = null,
            expectedIncomeTotal = null,
        ))

        assertVisible("Итоги выписки не удалось проверить")
        assertVisible("1 234,56 ₽", substring = true)
        assertVisible("45,67 ₽", substring = true)
        compose.onAllNodesWithText("0,00 ₽", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("0.00 RUB", substring = true).assertCountEquals(0)
    }

    @Test fun mismatchShowsBothParsedAndAvailableStatementTotalsWithoutChangingQuality() {
        show(preview = preview(
            quality = "mismatch",
            parsedExpenseTotal = "1234.56",
            expectedExpenseTotal = "1200.00",
            parsedIncomeTotal = "45.67",
            expectedIncomeTotal = "40.00",
        ))

        assertVisible("Расхождение итогов выписки")
        assertVisible("1 234,56 ₽", substring = true)
        assertVisible("1 200,00 ₽", substring = true)
        assertVisible("45,67 ₽", substring = true)
        assertVisible("40,00 ₽", substring = true)
    }

    @Test fun periodAndOrderedRowsShowDatesSignedAmountsMerchantFallbackAndCard() {
        show(preview = preview(rows = listOf(
            row(date = "2026-10-03", amount = "-125.50", merchant = "Кофейня", cardLast4 = "4242"),
            row(date = "2026-10-02", amount = "18.25", merchant = null, description = "Пополнение счёта"),
        )))

        assertVisible("2026-09-01 — 2026-10-03", substring = true)
        assertVisible("2026-10-03 · 12:34")
        assertVisible("Кофейня")
        assertVisible("4242", substring = true)
        assertVisible("-125,50 ₽")
        assertVisible("2026-10-02 · 12:34")
        assertVisible("Пополнение счёта")
        assertVisible("18,25 ₽")
    }

    @Test fun parserErrorsExplainUnsupportedFormatAndScannedPdfInRussianAndEnglish() {
        val errorState = mutableStateOf<String?>("invalid_format")
        show(errorState = errorState)

        assertVisible("Поддерживается только выписка Т-Банка «Справка о движении средств».")
        compose.runOnIdle { errorState.value = "no_text" }
        assertVisible("В PDF нет извлекаемого текста. Скан выписки пока не поддерживается.")
        compose.runOnIdle { language.value = "en"; errorState.value = "invalid_format" }
        assertVisible("Only T-Bank account statement PDFs are supported.")
        compose.runOnIdle { errorState.value = "no_text" }
        assertVisible("The PDF has no extractable text. Scanned statements are not supported yet.")
    }

    @Test fun viewerCanInspectPreviewButNoF48F49MutationControlsAreRendered() {
        show(role = "viewer", preview = preview())

        assertVisible("Итоги выписки сверены")
        assertVisible("Кофейня")
        for (label in listOf(
            "Подтвердить импорт", "Создать транзакции", "Выбрать тип", "Не включать",
            "Отменить импорт", "Undo import", "Create transactions", "Choose type",
        )) {
            compose.onAllNodesWithText(label, substring = true).assertCountEquals(0)
        }
    }

    @Test fun writerCanPickPdfAndCallbackRunsOnlyAfterOneExplicitTapInRussianAndEnglish() {
        var picks = 0
        val currentLanguage = mutableStateOf("ru")
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = "owner",
                    language = currentLanguage.value,
                    preview = preview(),
                    busy = false,
                    error = null,
                    onRefresh = {},
                    canUpload = true,
                    onPick = { picks++ },
                )
            }
        }

        compose.onNodeWithText("Выбрать PDF-выписку").assertIsDisplayed()
        assertTrue("Picker must not open before user tap", picks == 0)
        compose.onNodeWithText("Выбрать PDF-выписку").performClick()
        assertTrue("One tap must invoke picker once", picks == 1)
        compose.runOnIdle { currentLanguage.value = "en" }
        compose.onNodeWithText("Choose PDF statement").assertIsDisplayed()
    }

    @Test fun viewerNeverSeesPdfPickerEvenWhenUploadCapabilityIsTrue() {
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = "viewer",
                    language = "en",
                    preview = preview(),
                    busy = false,
                    error = null,
                    onRefresh = {},
                    canUpload = true,
                    onPick = { error("viewer must not start PDF upload") },
                )
            }
        }

        compose.onAllNodesWithText("Choose PDF statement").assertCountEquals(0)
        compose.onAllNodesWithText("Выбрать PDF-выписку").assertCountEquals(0)
    }

    @Test fun longStatementCanScrollToLastReturnedOperationWithoutOuterScrollWrapper() {
        val rows = (1..40).map { index ->
            row(date = "2026-10-03", amount = "-$index.00", merchant = "Operation $index")
        }
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = "owner",
                    language = "en",
                    preview = preview(rows = rows),
                    busy = false,
                    error = null,
                    onRefresh = {},
                )
            }
        }

        compose.onNodeWithTag("bank-import-preview").performScrollToNode(hasText("Operation 40"))
        compose.onNodeWithText("Operation 40").assertIsDisplayed()
    }

    private val language = mutableStateOf("ru")

    private fun show(
        role: String = "owner",
        preview: FinanceBankImportPreview = preview(),
        previewState: androidx.compose.runtime.MutableState<FinanceBankImportPreview>? = null,
        error: String? = null,
        errorState: androidx.compose.runtime.MutableState<String?>? = null,
    ) {
        language.value = "ru"
        val shownPreview = previewState ?: mutableStateOf(preview)
        val shownError = errorState ?: mutableStateOf(error)
        compose.setContent {
            MaterialTheme {
                BankImportPreviewSection(
                    role = role,
                    language = language.value,
                    preview = shownPreview.value,
                    busy = false,
                    error = shownError.value,
                    onRefresh = {},
                )
            }
        }
    }

    private fun assertVisible(text: String, substring: Boolean = false) {
        compose.onNodeWithTag("bank-import-preview")
            .performScrollToNode(hasText(text, substring = substring))
        compose.onNodeWithText(text, substring = substring).assertIsDisplayed()
    }

    private fun preview(
        quality: String = "valid",
        parsedExpenseTotal: String = "125.50",
        expectedExpenseTotal: String? = "125.50",
        parsedIncomeTotal: String = "18.25",
        expectedIncomeTotal: String? = "18.25",
        rows: List<FinanceBankImportRow> = listOf(
            row(date = "2026-10-03", amount = "-125.50", merchant = "Кофейня", cardLast4 = "4242"),
        ),
    ) = FinanceBankImportPreview(
        quality = quality,
        periodStart = "2026-09-01",
        periodEnd = "2026-10-03",
        parsedExpenseTotal = parsedExpenseTotal,
        parsedIncomeTotal = parsedIncomeTotal,
        expectedExpenseTotal = expectedExpenseTotal,
        expectedIncomeTotal = expectedIncomeTotal,
        rows = rows,
    )

    private fun row(
        date: String,
        time: String = "12:34",
        amount: String,
        merchant: String?,
        description: String = merchant ?: "Операция без магазина",
        cardLast4: String? = null,
    ) = FinanceBankImportRow(
        operationDate = date,
        operationTime = time,
        signedAmount = amount,
        merchant = merchant,
        description = description,
        cardLast4 = cardLast4,
    )
}
