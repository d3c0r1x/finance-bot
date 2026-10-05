package com.decorix.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun budgetThresholdAlertIsLocalizedAndUsesCoreAmounts() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), budgetAlerts = listOf(
            FinanceBudgetAlert("еда", "near", "5000.00", "4500.00"),
            FinanceBudgetAlert("__total__", "exceeded", "55000.00", "56000.00"),
        )))

        compose.onNodeWithText("⚠️ Почти достигнут: еда · 4500.00 / 5000.00 RUB").assertIsDisplayed()
        compose.onNodeWithText("🚨 Лимит исчерпан: Общий лимит · 56000.00 / 55000.00 RUB").assertIsDisplayed()
        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("⚠️ Near limit: еда · 4500.00 / 5000.00 RUB").assertIsDisplayed()
        compose.onNodeWithText("🚨 Limit reached: Total limit · 56000.00 / 55000.00 RUB").assertIsDisplayed()
    }

    @Test fun budgetAndDebtScreensLoadForOwnerInRussian() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            budgets = budget(), debts = listOf(debt())))

        compose.onNodeWithText("Бюджеты").performClick()
        compose.onNodeWithText("Лимиты · 2026-10").assertIsDisplayed()
        compose.onNodeWithText("Расход еды за 7 дней: 350.25 / 1000.00 ₽ · insufficient_history").assertIsDisplayed()

        compose.onNodeWithText("Долги").performClick()
        compose.onNodeWithText("Кредитная карта").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Записать платёж").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Прогноз выплаты").performScrollTo().assertIsDisplayed()
    }

    @Test fun viewerCannotSubmitTransactionOrChangeFamilyBudget() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("viewer")), budgets = budget()))
        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithText("Опишите операцию").assertIsNotEnabled()
        compose.onNodeWithText("Разобрать текст").assertIsNotEnabled()

        compose.onNodeWithText("Бюджеты").performClick()
        compose.onNodeWithText("Лимит, ₽").assertIsNotEnabled()
        compose.onNodeWithText("Сохранить лимит").assertIsNotEnabled()
        compose.onNodeWithText("У вас нет прав изменять этот бюджет").assertIsDisplayed()
    }

    @Test fun onboardingCreatesTenantWithMemberNameAndOptionalIncome() {
        var created: Triple<String, String, String?>? = null
        show(FinanceUiState(authenticated = true), onCreateTenant = { tenantName, memberName, income ->
            created = Triple(tenantName, memberName, income)
        })
        compose.onNodeWithText("Название пространства").performTextInput("Дом")
        compose.onNodeWithText("Ваше имя").performTextInput("Алекс")
        compose.onNodeWithText("Плановый доход в месяц, ₽").performTextInput("120000,50")
        compose.onNodeWithText("Создать").performClick()
        assertEquals(Triple("Дом", "Алекс", "120000.50"), created)
    }

    @Test fun onboardingCanSkipIncomeAndProfileCanBeChangedLater() {
        var createdIncome: String? = "not-set"
        var updated: Pair<String, String?>? = null
        val ui = mutableStateOf(FinanceUiState(authenticated = true))
        compose.setContent {
            MaterialTheme {
                FinanceScreen(state = ui.value, language = "ru", onLanguage = {}, onLogin = {}, onRefresh = {},
                    onCreate = { _, memberName, income ->
                        createdIncome = income
                        ui.value = ui.value.copy(tenants = listOf(tenant("owner")),
                            memberProfile = FinanceMemberProfile(memberName, income, "started", "Europe/Moscow", "RUB"))
                    }, onProfileSave = { name, income -> updated = name to income }, onCreateDraft = { _, _ -> },
                    onUpdateDraft = {}, onConfirmDraft = { _, _ -> }, onCancelDraft = { _, _ -> }, onLogout = {},
                    onBudgetUpdate = { _, _, _, _, _ -> }, onBudgetReset = {}, onBudgetProposal = {},
                    onBudgetApply = {}, onDebtCreate = { _, _, _, _ -> }, onDebtPay = { _, _, _ -> },
                    onDebtAdjust = { _, _, _ -> }, onDebtForecast = {}, onReportLoad = { _, _, _, _, _ -> })
            }
        }
        compose.onNodeWithText("Название пространства").performTextInput("Дом")
        compose.onNodeWithText("Ваше имя").performTextInput("Алекс")
        compose.onNodeWithText("Пропустить доход").assertIsDisplayed()
        compose.onNodeWithText("Создать").performClick()
        assertEquals(null, createdIncome)

        compose.onNodeWithText("Профиль").performScrollTo().performClick()
        compose.onNodeWithText("Мой профиль").assertIsDisplayed()
        compose.onNodeWithText("Ваше имя").performScrollTo().performTextReplacement("Алексей")
        compose.onNodeWithText("Плановый доход в месяц, ₽").performScrollTo().performTextReplacement("90000")
        compose.onNodeWithText("Сохранить профиль").performScrollTo().performClick()
        compose.onNodeWithText("Дом").assertIsDisplayed()
        assertEquals("Алексей" to "90000.00", updated)
    }

    @Test fun profileOffersTelegramBindingCode() {
        var requested = false
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            memberProfile = FinanceMemberProfile("Алексей", null, "complete", "Europe/Moscow", "RUB")),
            onCreateTelegramLink = { requested = true })

        compose.onNodeWithText("Профиль").performScrollTo().performClick()
        compose.onNodeWithText("Подключить Telegram").performScrollTo().performClick()

        assertEquals(true, requested)
    }

    @Test fun profileSavesMemberLocalTelegramDigestSchedule() {
        var saved: FinanceNotificationPreferences? = null
        val preferences = FinanceNotificationPreferences("Europe/Moscow", true, "ru", true, "21:00",
            true, 7, "19:00", null, null, 0L)
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            memberProfile = FinanceMemberProfile("Алексей", null, "complete", "Europe/Moscow", "RUB"),
            notificationPreferences = preferences), onNotificationPreferencesSave = { saved = it })

        compose.onNodeWithText("Профиль").performScrollTo().performClick()
        compose.onNodeWithText("English").performScrollTo().performClick()
        compose.onNodeWithText("Время ежедневной сводки").performScrollTo().performTextReplacement("08:30")
        compose.onNodeWithText("День недельного дайджеста").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Воскресенье").performScrollTo().performClick()
        compose.onNodeWithText("Понедельник").performClick()
        compose.onNodeWithText("Начало тихих часов").performScrollTo().performTextReplacement("22:00")
        compose.onNodeWithText("Конец тихих часов").performScrollTo().performTextReplacement("07:00")
        compose.onNodeWithText("Сохранить расписание").performScrollTo().performClick()

        assertEquals(preferences.copy(language = "en", dailyLocalTime = "08:30", weeklyDayOfWeek = 1,
            quietHoursStart = "22:00", quietHoursEnd = "07:00"), saved)
    }

    @Test fun dashboardAndReportTabsRenderCoreOwnedTotals() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), budgets = budget(),
            dashboardSummary = DashboardSummary(
                month = "2026-10", incomeTotal = "95000.00", expenseTotal = "42000.35", transactionCount = 8,
                asOfDate = "2026-10-01", daysElapsed = 1, daysInMonth = 31, daysRemaining = 30,
                dailyExpensePace = null, projectedExpenseTotal = null, rolling7FoodStatus = budget().rolling7FoodStatus,
                safeToSpend = SafeToSpend("actual_income", "95000.00", "2026-10", "2026-10-10", 9,
                    "42000.35", "9500.00", "3000.00", "40000.00", "4444.44"),
            ), report = report()))

        compose.onNodeWithText("Обзор").performClick()
        compose.onNodeWithText("Доходы месяца: 95000.00 ₽").assertIsDisplayed()
        compose.onNodeWithText("Расходы месяца: 42000.35 ₽").assertIsDisplayed()
        compose.onNodeWithText("Безопасно тратить в день: 4444.44 ₽").assertIsDisplayed()

        compose.onNodeWithText("Отчёты").performClick()
        compose.onNodeWithText("Доходы: 120000.00 ₽").assertIsDisplayed()
        compose.onNodeWithText("Платежи по долгам: 3000.00 ₽").assertIsDisplayed()
    }

    @Test fun reportControlsRequestSelectedDateRangeAndFamilyScope() {
        var requested: List<String>? = null
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner"))),
            onReportLoad = { period, scope, month, from, to -> requested = listOf(period, scope, month, from, to) })
        compose.onNodeWithText("Отчёты").performClick()
        compose.onNodeWithText("Месяц ▾").performClick()
        compose.onNodeWithText("Неделя ▾").performClick()
        compose.onNodeWithText("90 дней ▾").performClick()
        compose.onNodeWithText("Произвольный период ▾").assertIsDisplayed()
        compose.onNodeWithText("Личные").performClick()
        compose.onNodeWithText("Показать отчёт").performClick()

        assertEquals(listOf("custom", "family", "${java.time.YearMonth.now()}",
            "${java.time.YearMonth.now().atDay(1)}", "${java.time.LocalDate.now()}"), requested)
    }

    @Test fun reportScreenShowsServerDailyExpensesForZeroAndNonzeroDays() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            report = report().copy(expenseByDay = mapOf("2026-10-01" to "15000.00", "2026-10-02" to "0.00"))))
        compose.onNodeWithText("Отчёты").performClick()
        compose.onNodeWithTag("report-results").performScrollToIndex(2)
        compose.onNodeWithText("Расходы по дням").assertIsDisplayed()
        compose.onNodeWithTag("report-results").performScrollToIndex(3)
        compose.onNodeWithText("2026-10-01: 15000.00 RUB").assertIsDisplayed()
        compose.onNodeWithTag("report-results").performScrollToIndex(4)
        compose.onNodeWithText("2026-10-02: 0.00 RUB").assertIsDisplayed()
    }

    @Test fun transactionTextCreatesDraftThenRequiresReviewBeforeConfirmation() {
        var submittedText: String? = null
        var edit: TransactionDraftEdit? = null
        var confirmation: Pair<String, Long>? = null
        val ui = mutableStateOf(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner"))))
        compose.setContent {
            MaterialTheme {
                FinanceScreen(state = ui.value, language = "ru", onLanguage = {}, onLogin = {}, onRefresh = {},
                    onCreate = { _, _, _ -> }, onProfileSave = { _, _ -> }, onCreateDraft = { text, _ ->
                        submittedText = text
                        ui.value = ui.value.copy(transactionDraft = draft(version = 1))
                    }, onUpdateDraft = {
                        edit = it
                        ui.value = ui.value.copy(transactionDraft = draft(version = it.version + 1, amount = it.amount))
                    }, onConfirmDraft = { id, version -> confirmation = id to version }, onCancelDraft = { _, _ -> },
                    onLogout = {}, onBudgetUpdate = { _, _, _, _, _ -> }, onBudgetReset = {}, onBudgetProposal = {},
                    onBudgetApply = {}, onDebtCreate = { _, _, _, _ -> }, onDebtPay = { _, _, _ -> },
                    onDebtAdjust = { _, _, _ -> }, onDebtForecast = {}, onReportLoad = { _, _, _, _, _ -> })
            }
        }
        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithText("Опишите операцию").performTextInput("Такси 2 тыс")
        compose.onNodeWithText("Разобрать текст").performClick()

        assertEquals("Такси 2 тыс", submittedText)
        assertEquals(null, confirmation)

        compose.onNodeWithText("Предложение операции").assertIsDisplayed()
        compose.onNodeWithText("Провайдер: ollama · модель: local-test · промпт: transaction-draft.v1")
            .assertIsDisplayed()
        compose.onNodeWithText("1 000 ₽").performClick()
        compose.onNodeWithText("Сумма, ₽").assertIsDisplayed()
        compose.onNodeWithText("Подтвердить").assertIsNotEnabled()
        compose.onNodeWithText("Сохранить изменения").performScrollTo().performClick()
        assertEquals("1000.00", edit?.amount)
        assertEquals(1L, edit?.version)

        compose.onNodeWithText("Подтвердить").performClick()
        assertEquals("draft-1" to 2L, confirmation)
    }

    @Test fun transactionDraftCanBeCancelledAtItsCurrentVersion() {
        var cancelled: Pair<String, Long>? = null
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            transactionDraft = draft(version = 4)), onCancelDraft = { id, version -> cancelled = id to version })
        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithText("Предложение операции").assertIsDisplayed()
        compose.onNodeWithText("Отменить").performScrollTo().performClick()
        assertEquals("draft-1" to 4L, cancelled)
    }

    private fun show(state: FinanceUiState,
                     onReportLoad: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
                     onCreateTenant: (String, String, String?) -> Unit = { _, _, _ -> },
                     onProfileSave: (String, String?) -> Unit = { _, _ -> },
                     onCreateDraft: (String, String) -> Unit = { _, _ -> }, onUpdateDraft: (TransactionDraftEdit) -> Unit = {},
                     onConfirmDraft: (String, Long) -> Unit = { _, _ -> },
                     onCancelDraft: (String, Long) -> Unit = { _, _ -> },
                      onCreateTelegramLink: () -> Unit = {},
                      onNotificationPreferencesSave: (FinanceNotificationPreferences) -> Unit = {}) {
        val language = mutableStateOf("ru")
        compose.setContent {
        MaterialTheme {
            FinanceScreen(
                state = state,
                language = language.value,
                onLanguage = { language.value = it }, onLogin = {}, onRefresh = {}, onCreate = onCreateTenant,
                onProfileSave = onProfileSave, onCreateDraft = onCreateDraft,
                onCreateTelegramLink = onCreateTelegramLink,
                onNotificationPreferencesSave = onNotificationPreferencesSave,
                onUpdateDraft = onUpdateDraft, onConfirmDraft = onConfirmDraft, onCancelDraft = onCancelDraft,
                onLogout = {},
                onBudgetUpdate = { _, _, _, _, _ -> }, onBudgetReset = {}, onBudgetProposal = {}, onBudgetApply = {},
                onDebtCreate = { _, _, _, _ -> }, onDebtPay = { _, _, _ -> }, onDebtAdjust = { _, _, _ -> }, onDebtForecast = {},
                onReportLoad = onReportLoad,
            )
        }
        }
    }

    private fun tenant(role: String) = FinanceTenant("tenant-1", "Дом", role, "Europe/Moscow")

    private fun draft(version: Long, amount: String = "2000.00") = FinanceTransactionDraft(
        id = "draft-1", tenantId = "tenant-1", type = "expense", amount = amount, currency = "RUB",
        categoryCode = "транспорт", subcategoryCode = "такси", description = "Такси", occurredAt = "2026-10-01T12:00:00Z",
        debtId = null, state = "pending", version = version, provider = "ollama", modelVersion = "local-test",
        promptVersion = "transaction-draft.v1",
    )

    private fun budget() = BudgetOverview(
        month = "2026-10", familyLimits = mapOf("еда" to "20000.00"), personalOverrides = emptyMap(),
        effectiveLimits = mapOf("еда" to "20000.00"), monthlySpent = mapOf("еда" to "350.25"),
        limitStatus = mapOf("еда" to "normal"), familyVersions = mapOf("еда" to 1L), personalVersions = emptyMap(),
        familyTotalLimit = "50000.00", personalTotalOverride = null, effectiveTotalLimit = "50000.00",
        totalMonthlySpent = "350.25", totalLimitStatus = "normal", familyTotalVersion = 0L, personalTotalVersion = 0L,
        rolling7FoodLimit = "1000.00", personalRolling7FoodOverride = null, effectiveRolling7FoodLimit = "1000.00",
        rolling7FoodSpent = "350.25", rolling7FoodLimitStatus = "normal", familyRolling7FoodVersion = 1L,
        personalRolling7FoodVersion = 0L, rolling7FoodStatus = RollingFoodStatus(
            fromDate = "2026-09-25", toDate = "2026-10-01", limit = "1000.00", spent = "350.25", remaining = "649.75",
            limitStatus = "normal", usualWeeklySpend = null, historyWeeks = 0, paceStatus = "insufficient_history", paceShare = null,
        ),
    )

    private fun debt() = FinanceDebt("debt-1", "tenant-1", "Кредитная карта", "10000.00", "8400.00",
        "19.9", "500.00", "open", 3L)

    private fun report() = FinanceReport("month", "family", "2026-10-01", "2026-10-01", "2026-10-01",
        "Europe/Moscow", "RUB", "120000.00", "64000.50", "3000.00", "250.00", 12,
        mapOf("еда" to "15000.25"), null, "50000.00", "-14000.50", budget().rolling7FoodStatus)
}
