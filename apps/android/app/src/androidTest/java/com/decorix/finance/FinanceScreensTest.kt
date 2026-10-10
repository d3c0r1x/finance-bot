package com.decorix.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun authenticatedWriterCanNavigateToReceiptPhotoUpload() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner"))))

        compose.onNodeWithText("Чеки").assertIsDisplayed()
        compose.onNodeWithText("Чеки").performClick()
        compose.onNodeWithText("Загрузить фото чека").assertIsDisplayed()
    }

    @Test fun budgetThresholdAlertIsLocalizedAndUsesCoreAmounts() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), budgetAlerts = listOf(
            FinanceBudgetAlert("еда", "near", "5000.00", "4500.00"),
            FinanceBudgetAlert("__total__", "exceeded", "55000.00", "56000.00"),
        )))

        compose.onNodeWithText("⚠️ Почти достигнут: еда · 4 500,00 ₽ / 5 000,00 ₽").assertIsDisplayed()
        compose.onNodeWithText("🚨 Лимит исчерпан: Общий лимит · 56 000,00 ₽ / 55 000,00 ₽").assertIsDisplayed()
        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("⚠️ Near limit: еда · 4,500.00 RUB / 5,000.00 RUB").assertIsDisplayed()
        compose.onNodeWithText("🚨 Limit reached: Total limit · 56,000.00 RUB / 55,000.00 RUB").assertIsDisplayed()
    }

    @Test fun budgetAndDebtScreensLoadForOwnerInRussian() {
        val owner = tenant("owner")
        show(FinanceUiState(authenticated = true, tenants = listOf(owner), budgets = budget(), debts = listOf(debt()),
            familyBudgetFoodStatus = budget().rolling7FoodStatus, familyBudgetFoodMonth = budget().month,
            familyBudgetFoodTenantId = owner.id))

        compose.onNodeWithText("Бюджеты").performClick()
        compose.onNodeWithText("Лимиты · 2026-10").assertIsDisplayed()
        compose.onNodeWithText("Еда за 7 дней (2026-09-25–2026-10-01): потрачено 350,25 ₽ / " +
            "1 000,00 ₽ · остаток 649,75 ₽ · В норме · Недостаточно истории").assertIsDisplayed()
        compose.onNodeWithText("Личный").performClick()
        compose.onNodeWithText("Еда за 7 дней (2026-09-25–2026-10-01): потрачено 350,25 ₽ / " +
            "1 000,00 ₽ · остаток 649,75 ₽ · В норме · Недостаточно истории").assertIsDisplayed()

        compose.onNodeWithText("Долги").performScrollTo().performClick()
        compose.onAllNodesWithText("Долги").assertCountEquals(2)
        compose.onNodeWithText("Кредитная карта").assertIsDisplayed()
        compose.onNodeWithText("Записать платёж").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Прогноз выплаты").performScrollTo().assertIsDisplayed()
    }

    @Test fun debtCardDisplaysCoreInterestRateAndMinimumPayment() {
        val coreDebt = debt().copy(interestRate = "18.50", minimumPayment = "450.00")
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), debts = listOf(coreDebt)))

        compose.onNodeWithText("Долги").performScrollTo().performClick()
        compose.onNodeWithText("Кредитная карта").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ставка: 18,50% · Минимальный платёж: 450,00 ₽").assertIsDisplayed()
    }

    @Test fun debtCardLocalizesRateAndMinimumPaymentInRussianAndEnglish() {
        val coreDebt = debt().copy(interestRate = "18.50", minimumPayment = "450.00")
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), debts = listOf(coreDebt)))

        compose.onNodeWithText("Долги").performScrollTo().performClick()
        compose.onNodeWithText("Ставка: 18,50% · Минимальный платёж: 450,00 ₽").assertIsDisplayed()
        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Interest rate: 18.50% · Minimum payment: 450.00 RUB").assertIsDisplayed()
    }

    @Test fun debtCardMatchesWebFallbackWhenCoreRateIsNull() {
        val coreDebt = debt().copy(interestRate = null, minimumPayment = "450.00")
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), debts = listOf(coreDebt)))

        compose.onNodeWithText("Долги").performScrollTo().performClick()
        compose.onNodeWithText("Ставка: 0,0000% · Минимальный платёж: 450,00 ₽").assertIsDisplayed()
    }

    @Test fun debtWriterSubmitsExactCoreIdAmountAndVersionForPayment() {
        var paid: List<Any>? = null
        show(
            FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), debts = listOf(debt())),
            onDebtPay = { id, amount, version -> paid = listOf(id, amount, version) },
        )

        compose.onNodeWithText("Долги").performScrollTo().performClick()
        compose.onAllNodesWithText("Долги").assertCountEquals(2)
        compose.onNodeWithText("Платёж, ₽").performTextInput("125,50")
        compose.onNodeWithText("Записать платёж").performClick()

        assertEquals(listOf("debt-1", "125,50", 3L), paid)
    }

    @Test fun debtMutationsAreHiddenForViewer() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("viewer")), debts = listOf(debt())))
        compose.onNodeWithText("Долги").performScrollTo().performClick()
        compose.onAllNodesWithText("Долги").assertCountEquals(2)
        compose.onNodeWithText("Просмотр только для чтения").assertIsDisplayed()
        compose.onNodeWithText("Записать платёж").assertDoesNotExist()
        compose.onNodeWithText("Создать долг").assertDoesNotExist()
    }

    @Test fun debtMutationsAreHiddenForClosedDebt() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), debts = listOf(debt().copy(status = "closed"))))
        compose.onNodeWithText("Долги").performScrollTo().performClick()
        compose.onAllNodesWithText("Долги").assertCountEquals(2)
        compose.onNodeWithText("закрыт", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Записать платёж").assertDoesNotExist()
        compose.onNodeWithText("Сохранить остаток").assertDoesNotExist()
    }

    @Test fun debtAdjustmentAndForecastActionsPreserveCoreDebtIdAndVersion() {
        var adjustment: List<Any>? = null
        var forecastDebtId: String? = null
        show(
            FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), debts = listOf(debt())),
            onDebtAdjust = { id, amount, version -> adjustment = listOf(id, amount, version) },
            onDebtForecast = { forecastDebtId = it },
        )

        compose.onNodeWithText("Долги").performScrollTo().performClick()
        compose.onNodeWithText("Исправить остаток, ₽").performTextInput("8150,25")
        compose.onNodeWithText("Сохранить остаток").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithText("Прогноз выплаты").performScrollTo().performClick()

        assertEquals(listOf("debt-1", "8150,25", 3L), adjustment)
        assertEquals("debt-1", forecastDebtId)
    }

    @Test fun rollingFoodStatusUsesSameCoreWindowAmountsAndStatusesAcrossScreensInRussianAndEnglish() {
        val food = RollingFoodStatus(
            fromDate = "2026-10-02", toDate = "2026-10-08", limit = "1000.00", spent = "950.00",
            remaining = "50.00", limitStatus = "near", usualWeeklySpend = null, historyWeeks = 0,
            paceStatus = "insufficient_history", paceShare = null,
        )
        val budgets = budget().copy(
            rolling7FoodLimit = food.limit, effectiveRolling7FoodLimit = food.limit,
            rolling7FoodSpent = food.spent, rolling7FoodLimitStatus = food.limitStatus,
            rolling7FoodStatus = food,
        )
        val summary = DashboardSummary(
            month = "2026-10", incomeTotal = "0.00", expenseTotal = food.spent, transactionCount = 1,
            asOfDate = food.toDate, daysElapsed = 8, daysInMonth = 31, daysRemaining = 23,
            dailyExpensePace = null, projectedExpenseTotal = null, rolling7FoodStatus = food,
        )
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), budgets = budgets,
            dashboardSummary = summary, report = report().copy(rolling7FoodStatus = food)))

        val russianLine = "Еда за 7 дней (2026-10-02–2026-10-08): потрачено 950,00 ₽ / 1 000,00 ₽ · " +
            "остаток 50,00 ₽ · Почти достигнут · Недостаточно истории"
        compose.onNodeWithText("Обзор").performClick()
        compose.onNodeWithText(russianLine).assertIsDisplayed()
        compose.onNodeWithText("Бюджеты").performClick()
        compose.onNodeWithText("Личный").performClick()
        compose.onNodeWithText(russianLine).assertIsDisplayed()
        compose.onNodeWithText("Отчёты").performScrollTo().performClick()
        compose.onNodeWithText(russianLine).performScrollTo().assertIsDisplayed()

        val englishLine = "Food over 7 days (2026-10-02–2026-10-08): spent 950.00 RUB / 1,000.00 RUB · " +
            "remaining 50.00 RUB · Near limit · Insufficient history"
        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText(englishLine).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Overview").performClick()
        compose.onNodeWithText(englishLine).assertIsDisplayed()
        compose.onNodeWithText("Budgets").performClick()
        compose.onNodeWithText("Personal").performClick()
        compose.onNodeWithText(englishLine).assertIsDisplayed()
    }

    @Test fun rollingFoodStatusDoesNotInventBaselineOrRemainingWhenCoreReturnsNullInRussianAndEnglish() {
        val food = RollingFoodStatus(
            fromDate = "2026-10-02", toDate = "2026-10-08", limit = "0.00", spent = "350.25",
            remaining = null, limitStatus = "disabled", usualWeeklySpend = null, historyWeeks = 0,
            paceStatus = "insufficient_history", paceShare = null,
        )
        val budgets = budget().copy(
            rolling7FoodLimit = "0.00", effectiveRolling7FoodLimit = "0.00", rolling7FoodSpent = food.spent,
            rolling7FoodLimitStatus = "disabled", rolling7FoodStatus = food,
        )
        val summary = DashboardSummary(
            month = "2026-10", incomeTotal = "0.00", expenseTotal = food.spent, transactionCount = 1,
            asOfDate = food.toDate, daysElapsed = 8, daysInMonth = 31, daysRemaining = 23,
            dailyExpensePace = null, projectedExpenseTotal = null, rolling7FoodStatus = food,
        )
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), budgets = budgets,
            dashboardSummary = summary, report = report().copy(rolling7FoodStatus = food)))

        val russianLine = "Еда за 7 дней (2026-10-02–2026-10-08): потрачено 350,25 ₽ · " +
            "Лимит отключён · Недостаточно истории"
        compose.onNodeWithText("Обзор").performClick()
        compose.onNodeWithText(russianLine).assertIsDisplayed()
        compose.onNodeWithText("Бюджеты").performClick()
        compose.onNodeWithText("Личный").performClick()
        compose.onNodeWithText(russianLine).assertIsDisplayed()
        compose.onNodeWithText("Отчёты").performScrollTo().performClick()
        compose.onNodeWithText(russianLine).performScrollTo().assertIsDisplayed()

        val englishLine = "Food over 7 days (2026-10-02–2026-10-08): spent 350.25 RUB · " +
            "Limit disabled · Insufficient history"
        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText(englishLine).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Overview").performClick()
        compose.onNodeWithText(englishLine).assertIsDisplayed()
        compose.onNodeWithText("Budgets").performClick()
        compose.onNodeWithText("Personal").performClick()
        compose.onNodeWithText(englishLine).assertIsDisplayed()
    }

    @Test fun familyFoodStatusUsesFamilyReportAndRequestsMonthlyFamilyReport() {
        var reportRequest: List<String>? = null
        val personalFood = RollingFoodStatus(
            fromDate = "2026-10-02", toDate = "2026-10-08", limit = "1000.00", spent = "111.11",
            remaining = "888.89", limitStatus = "normal", usualWeeklySpend = "200.00", historyWeeks = 3,
            paceStatus = "under", paceShare = "0.555550",
        )
        val familyFood = RollingFoodStatus(
            fromDate = "2026-10-03", toDate = "2026-10-09", limit = "2000.00", spent = "1750.00",
            remaining = "250.00", limitStatus = "near", usualWeeklySpend = "1200.00", historyWeeks = 4,
            paceStatus = "over", paceShare = "1.458333",
        )
        val budgets = budget().copy(
            rolling7FoodLimit = "2000.00", effectiveRolling7FoodLimit = "1000.00",
            rolling7FoodSpent = personalFood.spent, rolling7FoodLimitStatus = personalFood.limitStatus,
            rolling7FoodStatus = personalFood,
        )
        val personalReport = report().copy(
            period = "month", scope = "personal", fromDate = "2026-10-01", toDate = "2026-10-09",
            monthlyBudgetLimit = "50000.00", monthlyBudgetRemaining = "12345.67",
            rolling7FoodStatus = personalFood,
        )
        val familyReport = report().copy(
            period = "month", scope = "family", fromDate = "2026-10-01", toDate = "2026-10-09",
            rolling7FoodStatus = familyFood,
        )
        var familyBudgetRequests = emptyList<String>()
        val owner = tenant("owner")
        val state = mutableStateOf(FinanceUiState(authenticated = true, tenants = listOf(owner), budgets = budgets,
            report = personalReport))
        show(state.value, stateHolder = state, onReportLoad = { period, scope, month, from, to ->
            reportRequest = listOf(period, scope, month, from, to)
            state.value = state.value.copy(report = if (scope == "family") familyReport else personalReport)
        }, onFamilyBudgetFoodStatusLoad = { requestedMonth ->
            familyBudgetRequests = familyBudgetRequests + requestedMonth
            state.value = state.value.copy(familyBudgetFoodStatus = familyFood,
                familyBudgetFoodMonth = requestedMonth, familyBudgetFoodTenantId = owner.id)
        })

        val russianFamilyLine = "Еда за 7 дней (2026-10-03–2026-10-09): потрачено 1 750,00 ₽ / 2 000,00 ₽ · " +
            "остаток 250,00 ₽ · Почти достигнут · Быстрее обычного"
        compose.onNodeWithText("Бюджеты").performClick()
        compose.onNodeWithText("Личный").performClick()
        compose.onNodeWithText("Семейный").performClick()
        compose.onNodeWithText(russianFamilyLine).assertIsDisplayed()
        state.value = state.value.copy(familyBudgetFoodRefreshToken = 1L)
        compose.waitForIdle()
        assertEquals(listOf(budgets.month, budgets.month, budgets.month), familyBudgetRequests)
        compose.onNodeWithText("Отчёты").performScrollTo().performClick()
        assertEquals("personal", state.value.report?.scope)
        compose.onNodeWithText("Лимит месяца: 50 000,00 ₽ · остаток 12 345,67 ₽")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Еда за 7 дней (2026-10-02–2026-10-08): потрачено 111,11 ₽ / 1 000,00 ₽ · " +
            "остаток 888,89 ₽ · В норме · Медленнее обычного").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Личные").performClick()
        compose.onNodeWithText(russianFamilyLine).assertDoesNotExist()
        compose.onNodeWithText("Показать отчёт").performClick()
        compose.onNodeWithText("Семейные").performClick()
        compose.onNodeWithText("Личные").performClick()
        compose.onNodeWithText(russianFamilyLine).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Показать отчёт").performClick()
        assertEquals(listOf("month", "family", budgets.month, "", ""), reportRequest)

        val englishFamilyLine = "Food over 7 days (2026-10-03–2026-10-09): spent 1,750.00 RUB / 2,000.00 RUB · " +
            "remaining 250.00 RUB · Near limit · Faster than usual"
        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText(englishFamilyLine).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Budgets").performClick()
        compose.onNodeWithText("Personal").performClick()
        compose.onNodeWithText("Family").performClick()
        compose.onNodeWithText(englishFamilyLine).assertIsDisplayed()
    }

    @Test fun personalBudgetOverrideUsesPersonalVersionAndShowsInheritedCategories() {
        var update: List<String>? = null
        var resets = 0
        val budgets = budget().copy(
            familyLimits = mapOf("еда" to "20000.00", "транспорт" to "10000.00"),
            personalOverrides = mapOf("еда" to "18000.00"),
            effectiveLimits = mapOf("еда" to "18000.00", "транспорт" to "10000.00"),
            monthlySpent = mapOf("еда" to "350.25", "транспорт" to "25.00"),
            limitStatus = mapOf("еда" to "normal", "транспорт" to "normal"),
            familyVersions = mapOf("еда" to 7L, "транспорт" to 2L),
            personalVersions = mapOf("еда" to 3L),
            familyTotalLimit = "50000.00", effectiveTotalLimit = "50000.00",
        )
        val owner = tenant("owner")
        show(FinanceUiState(authenticated = true, tenants = listOf(owner), budgets = budgets,
            familyBudgetFoodStatus = budgets.rolling7FoodStatus, familyBudgetFoodMonth = budgets.month,
            familyBudgetFoodTenantId = owner.id),
            onBudgetUpdate = { key, scope, amount, period, version ->
                update = listOf(key, scope, amount, period, version.toString())
            }, onBudgetReset = { resets++ })

        compose.onNodeWithText("Бюджеты").performClick()
        compose.onNodeWithText("Личный").performClick()
        compose.onNodeWithText("Лимит за месяц: 50 000,00 ₽", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Действует 18 000,00 ₽", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Действует 10 000,00 ₽", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Категория или __total__").performTextReplacement("еда")
        compose.onNodeWithText("Лимит, ₽").performTextInput("17500.00")
        compose.onNodeWithText("Сохранить лимит").performClick()

        assertEquals(listOf("еда", "personal", "17500.00", "monthly", "3"), update)
        compose.onNodeWithText("Сбросить личные").performClick()
        assertEquals(1, resets)
    }

    @Test fun personalTotalBudgetUsesPersonalTotalVersion() {
        var update: List<String>? = null
        val budgets = budget().copy(personalTotalOverride = "45000.00", effectiveTotalLimit = "45000.00",
            personalTotalVersion = 9L)
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), budgets = budgets),
            onBudgetUpdate = { key, scope, amount, period, version ->
                update = listOf(key, scope, amount, period, version.toString())
            })

        compose.onNodeWithText("Бюджеты").performClick()
        compose.onNodeWithText("Личный").performClick()
        compose.onNodeWithText("Лимит за месяц: 45 000,00 ₽", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Лимит, ₽").performTextInput("43000.00")
        compose.onNodeWithText("Сохранить лимит").performClick()

        assertEquals(listOf("__total__", "personal", "43000.00", "monthly", "9"), update)
    }

    @Test fun familyBudgetScopeShowsFamilyTotalsAndCategoryLimits() {
        val updates = mutableListOf<List<String>>()
        val budgets = budget().copy(
            familyLimits = mapOf("еда" to "20000.00", "транспорт" to "10000.00"),
            personalOverrides = mapOf("еда" to "18000.00", "транспорт" to "8000.00"),
            effectiveLimits = mapOf("еда" to "18000.00", "транспорт" to "8000.00"),
            familyTotalLimit = "50000.00",
            personalTotalOverride = "45000.00",
            effectiveTotalLimit = "45000.00",
            familyVersions = mapOf("еда" to 7L, "транспорт" to 2L),
            familyTotalVersion = 11L,
        )
        val owner = tenant("owner")
        show(FinanceUiState(authenticated = true, tenants = listOf(owner), budgets = budgets,
            familyBudgetFoodStatus = budgets.rolling7FoodStatus, familyBudgetFoodMonth = budgets.month,
            familyBudgetFoodTenantId = owner.id),
            onBudgetUpdate = { key, scope, amount, period, version ->
                updates += listOf(key, scope, amount, period, version.toString())
            })

        compose.onNodeWithText("Бюджеты").performClick()
        compose.onNodeWithText("Личный").performClick()
        compose.onNodeWithText("Семейный").performClick()

        compose.onNodeWithText("Семейный лимит за месяц: 50 000,00 ₽")
            .assertIsDisplayed()
        compose.onNodeWithText("Семейный лимит: 20 000,00 ₽", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Семейный лимит: 10 000,00 ₽", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Семейный лимит за месяц: 50 000,00 ₽ · потрачено", substring = true)
            .assertDoesNotExist()
        compose.onNodeWithText("Семейный лимит: 20 000,00 ₽ · потрачено", substring = true)
            .assertDoesNotExist()

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Family monthly limit: 50,000.00 RUB", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Family limit: 20,000.00 RUB", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Food over 7 days (2026-09-25–2026-10-01): spent 350.25 RUB / " +
            "1,000.00 RUB · remaining 649.75 RUB · Within limit · Insufficient history")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("RU").performClick()

        compose.onNodeWithText("Лимит, ₽").performScrollTo().performTextReplacement("52000.00")
        compose.onNodeWithText("Сохранить лимит").performScrollTo().performClick()
        assertEquals(listOf("__total__", "family", "52000.00", "monthly", "11"), updates[0])
        compose.onNodeWithText("Категория или __total__").performScrollTo().performTextReplacement("еда")
        compose.onNodeWithText("Лимит, ₽").performScrollTo().performTextReplacement("22000.00")
        compose.onNodeWithText("Сохранить лимит").performScrollTo().performClick()
        assertEquals(listOf("еда", "family", "22000.00", "monthly", "7"), updates[1])
    }

    @Test fun shoppingSuggestionsAreLocalizedAndNeverPresentedAsInventory() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), shoppingList = shopping()))

        compose.onNodeWithText("Покупки").performScrollTo().performClick()
        compose.onNodeWithText("Пора купить").assertIsDisplayed()
        compose.onNodeWithText("Молоко 1 л").assertIsDisplayed()
        compose.onNodeWithText("Оценка: 100.00 ₽").assertIsDisplayed()
        compose.onNodeWithText("Медиана: раз в 10 дн. · 3 покупки").assertIsDisplayed()
        compose.onNodeWithText("Это подсказка по чекам, не учёт запасов.").assertIsDisplayed()

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Shopping list").assertIsDisplayed()
        compose.onNodeWithText("Not home inventory: suggestions use your confirmed receipt rhythm.").assertIsDisplayed()
    }

    @Test fun shoppingDecisionsExplainHiddenItemsAndCopyOnlyActiveCandidates() {
        var decision: Pair<String, String>? = null
        var copied = ""
        val active = shopping().candidates.single()
        val list = shopping().copy(boughtCandidates = listOf(active),
            mutedCandidates = listOf(active.copy(productKey = "tea")),
            blockedCandidates = listOf(FinanceBlockedShoppingCandidate("candy", "Конфеты", "confirmed_not_to_buy")))
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), shoppingList = list),
            onShoppingDecision = { key, action -> decision = key to action }, onShoppingCopy = { copied = it })

        compose.onNodeWithText("Покупки").performScrollTo().performClick()
        compose.onNodeWithText("Отметка действует до следующего обычного интервала покупки.").assertIsDisplayed()
        compose.onNodeWithText("Скопировать список").performClick()
        assertEquals("Молоко 1 л — 100.00 RUB\nОценка списка: 100.00 ₽", copied)
        assertEquals(false, copied.contains("Конфеты"))
        compose.onNodeWithTag("shopping-list").performScrollToIndex(4)
        compose.onNodeWithText("Вы скрыли эту подсказку.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("shopping-list").performScrollToIndex(1)
        compose.onNodeWithText("Скрыть").performClick()
        assertEquals("milk" to "mute", decision)
        compose.onNodeWithTag("shopping-list").performScrollToIndex(6)
        compose.onNodeWithText("Конфеты · Вы отметили как «не брать».").assertIsDisplayed()
    }

    @Test fun doNotBuySeparatesModelGuessesAndSupportsHumanDecision() {
        var decision: Pair<String, String>? = null
        val chips = FinanceAdviceGroup("chips", "Чипсы", 2, "20.00", 0, 2, 0, 0,
            false, "harmful", "", "2026-10-06T10:00:00Z")
        val tea = FinanceAdviceGroup("tea", "Чай", 2, null, 2, 0, 2, 0,
            true, "unnecessary", "Проверьте привычку", "2026-10-06T10:00:00Z")
        val report = FinanceDoNotBuy(true, "available", listOf(chips), listOf(tea))
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            doNotBuy = report, productDecisions = FinanceProductDecisions(emptyList(), emptyList())),
            onDoNotBuyDecision = { key, action -> decision = key to action })

        compose.onNodeWithText("Не брать").performScrollTo().performClick()
        compose.onNodeWithText("Догадки модели").assertIsDisplayed()
        compose.onNodeWithTag("do-not-buy-list").performScrollToIndex(4)
        compose.onNodeWithText("Чай").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сумма неизвестна").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Подтвердить «не брать»").performScrollTo().performClick()
        assertEquals("tea" to "confirm", decision)
        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Model guesses").assertIsDisplayed()
    }

    @Test fun personalInflationShowsReceiptOnlyDisclosureAndCoreBasketInRussianAndEnglish() {
        val inflation = FinancePersonalInflation(
            available = true, reasonCode = "available", asOf = "2026-10-06T12:00:00Z", windowDays = 90,
            productCount = 3, basketBefore = "1250.00", basketNow = "1275.00", indexPercent = "2.00",
            rising = listOf(FinancePersonalInflationItem("Кофе", "100.00", "110.00", "500.00", "10.00", 2, 1)),
            falling = listOf(FinancePersonalInflationItem("Молоко", "200.00", "180.00", "700.00", "-10.00", 3, 2)),
        )
        var loads = 0
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), personalInflation = inflation),
            onPersonalInflationLoad = { loads++ })

        compose.onNodeWithText("Динамика цен").performScrollTo().performClick()
        compose.onNodeWithText("Личная динамика цен · 90 дней").assertIsDisplayed()
        compose.onNodeWithText("Корзина по старым ценам: 1250.00 ₽").assertIsDisplayed()
        compose.onNodeWithText("Та же корзина по новым ценам: 1275.00 ₽").assertIsDisplayed()
        compose.onNodeWithText("Личный индекс: 2.00% · 3 товара").assertIsDisplayed()
        compose.onNodeWithText("Кофе · 100.00 → 110.00 ₽ · 10.00% · вес 500.00 ₽").assertIsDisplayed()
        compose.onNodeWithText("Цены только из ваших чеков, не официальная статистика.").assertIsDisplayed()
        assertEquals(0, loads)

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Personal price trend · 90 days").assertIsDisplayed()
        compose.onNodeWithText("Receipt prices only; not official inflation statistics.").assertIsDisplayed()
    }

    @Test fun personalInflationLoadsWhenScreenOpensAndExplainsMissingHistory() {
        var loads = 0
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner"))),
            onPersonalInflationLoad = { loads++ })

        compose.onNodeWithText("Динамика цен").performScrollTo().performClick()

        assertEquals(1, loads)
        compose.onNodeWithText("Загрузка…").assertIsDisplayed()
    }

    @Test fun personalInflationExplainsInsufficientHistoryWithoutShowingZeroTotals() {
        val unavailable = FinancePersonalInflation(
            available = false, reasonCode = "insufficient_history", asOf = "2026-10-06T12:00:00Z", windowDays = 90,
            productCount = 0, basketBefore = null, basketNow = null, indexPercent = null, rising = emptyList(), falling = emptyList(),
        )
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), personalInflation = unavailable))

        compose.onNodeWithText("Динамика цен").performScrollTo().performClick()
        compose.onNodeWithText("Недостаточно истории для расчёта.").assertIsDisplayed()
        compose.onNodeWithText("Нужно минимум 3 товара: для каждого — 2 покупки до окна и 1 внутри 90-дневного окна.")
            .assertIsDisplayed()
        compose.onNodeWithText("0.00 ₽").assertDoesNotExist()
    }

    @Test fun recurringScreenSeparatesNearDueFromOverdueAndLocalizes() {
        val due = recurring("11111111111111111111111111111111", "rent", "Аренда", "expense", "100.00", 3, "2026-10-09")
        val overdue = recurring("22222222222222222222222222222222", "service", "Сервис", "expense", "500.00", -1, "2026-10-05")
        val income = recurring("33333333333333333333333333333333", "salary", "Зарплата", "income", "120000.00", 4, "2026-10-10")
        val projection = FinanceRecurringProjection("recurring.v1", "complete", "Europe/Moscow", "2026-10-05T21:00:00Z",
            listOf(due, overdue), listOf(income), listOf(due), listOf(overdue), income, "2571.43", mapOf("RUB" to "2571.43"),
            emptyList())
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), recurringProjection = projection))

        compose.onNodeWithText("Регулярные").performScrollTo().performClick()
        compose.onNodeWithText("Регулярные доходы и расходы").assertIsDisplayed()
        compose.onNodeWithText("Скоро · следующие 3 дня").assertIsDisplayed()
        compose.onNodeWithText("Через 3 дн. · 2026-10-09").assertIsDisplayed()
        compose.onNodeWithTag("recurring-projection").performScrollToIndex(2)
        assertEquals(2, compose.onAllNodesWithText("Обычно: 100.00–100.00 ₽ · интервал: 7–7 дн.").fetchSemanticsNodes().size)
        compose.onNodeWithTag("recurring-projection").performScrollToIndex(5)
        assertEquals(1, compose.onAllNodesWithText("Обычно: 100.00–100.00 ₽ · интервал: 7–7 дн.").fetchSemanticsNodes().size)
        compose.onNodeWithTag("recurring-projection").performScrollToIndex(3)
        compose.onNodeWithText("Оценка расходов в месяц: 2571.43 ₽").assertIsDisplayed()
        compose.onNodeWithTag("recurring-projection").performScrollToIndex(8)
        compose.onNodeWithText("Зарплата").assertIsDisplayed()
        compose.onNodeWithTag("recurring-projection").performScrollToIndex(9)
        compose.onNodeWithText("Просрочено · не входит в ближайшие списания").assertIsDisplayed()
        compose.onNodeWithText("Просрочено на 1 дн. · 2026-10-05").assertIsDisplayed()

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithTag("recurring-projection").performScrollToIndex(0)
        compose.onNodeWithText("Recurring income and expenses").assertIsDisplayed()
        compose.onNodeWithTag("recurring-projection").performScrollToIndex(1)
        compose.onNodeWithText("Due soon · next 3 days").assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithText("Typical range: 100.00–100.00 RUB · interval: 7–7 days").fetchSemanticsNodes().size)
        compose.onNodeWithTag("recurring-projection").performScrollToIndex(9)
        compose.onNodeWithText("Overdue · excluded from upcoming charges").assertIsDisplayed()
    }

    @Test fun recurringScreenLoadsWhenOpened() {
        var loads = 0
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner"))), onRecurringLoad = { loads++ })
        compose.onNodeWithText("Регулярные").performScrollTo().performClick()
        assertEquals(1, loads)
        compose.onNodeWithTag("recurring-projection").assertIsDisplayed()
    }

    @Test fun recurringScreenOffersReminderMuteAction() {
        val due = recurring("11111111111111111111111111111111", "rent", "Аренда", "expense", "100.00", 3, "2026-10-09")
        val muted = due.copy(id = "44444444444444444444444444444444", key = "backup", name = "Облако")
        val projection = FinanceRecurringProjection("recurring.v1", "complete", "Europe/Moscow", "2026-10-05T21:00:00Z",
            listOf(due), emptyList(), emptyList(), emptyList(), null, null, emptyMap(), listOf(muted))
        var decision: Pair<String, Boolean>? = null
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), recurringProjection = projection),
            onRecurringDecision = { id, muted -> decision = id to muted })

        compose.onNodeWithText("Регулярные").performScrollTo().performClick()
        compose.onNodeWithText("Отключить напоминание").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(due.id to true, decision)
        compose.onNodeWithTag("recurring-projection").performScrollToIndex(6)
        compose.onNodeWithText("Отключённые напоминания").assertIsDisplayed()
        compose.onNodeWithText("Восстановить напоминание").assertIsDisplayed().performClick()
        assertEquals(muted.id to false, decision)
    }

    @Test fun recurringScreenShowsNoFakeTotalsWithoutHistory() {
        val empty = FinanceRecurringProjection("recurring.v1", "complete", "Europe/Moscow", "2026-10-05T21:00:00Z",
            emptyList(), emptyList(), emptyList(), emptyList(), null, null, emptyMap(), emptyList())
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), recurringProjection = empty))
        compose.onNodeWithText("Регулярные").performScrollTo().performClick()
        compose.onNodeWithText("Пока нет найденных регулярных операций.").assertIsDisplayed()
        compose.onNodeWithText("Для серии нужны минимум три похожие операции.").assertIsDisplayed()
        compose.onNodeWithText("0.00 ₽").assertDoesNotExist()
    }

    @Test fun viewerCannotSubmitTransactionOrChangeFamilyBudget() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("viewer")), budgets = budget()))
        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithText("Опишите операцию").assertIsNotEnabled()
        compose.onNodeWithText("Разобрать текст").assertIsNotEnabled()

        compose.onNodeWithText("Бюджеты").performClick()
        compose.onNodeWithText("Лимит, ₽").assertIsNotEnabled()
        compose.onNodeWithText("Сохранить лимит").assertIsNotEnabled()
        compose.onNodeWithText("У вас нет прав изменять этот бюджет").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Личный").performClick()
        compose.onNodeWithText("Лимит, ₽").assertIsNotEnabled()
        compose.onNodeWithText("Сохранить лимит").assertIsNotEnabled()
        compose.onNodeWithText("Сбросить личные").performScrollTo().assertIsNotEnabled()
    }

    @Test fun onboardingCreatesTenantWithMemberNameAndOptionalIncome() {
        var created: Triple<String, String, String?>? = null
        show(FinanceUiState(authenticated = true), onCreateTenant = { tenantName, memberName, income ->
            created = Triple(tenantName, memberName, income)
        })
        compose.onNodeWithTag("onboarding-start").performClick()
        compose.onNodeWithTag("onboarding-workspace-name").performTextInput("Дом")
        compose.onNodeWithTag("onboarding-member-name").performTextInput("Алекс")
        compose.onNodeWithTag("onboarding-identity-next").performClick()
        compose.onNodeWithTag("onboarding-income-value").performTextInput("120000,50")
        compose.onNodeWithTag("onboarding-income-next").performClick()
        assertEquals(Triple("Дом", "Алекс", "120000.50"), created)
    }

    @Test fun authenticatedZeroTenantSeesWelcomeBeforeIdentityAndIncomeSteps() {
        show(FinanceUiState(authenticated = true))

        compose.onNodeWithTag("onboarding-welcome").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-identity").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-start").performClick()

        compose.onNodeWithTag("onboarding-identity").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-workspace-name").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-member-name").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-workspace-name").performTextInput("Welcome Family")
        compose.onNodeWithTag("onboarding-member-name").performTextInput("Taylor Example")
        compose.onNodeWithTag("onboarding-identity-next").performClick()

        compose.onNodeWithTag("onboarding-income").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-income-value").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-income-skip").assertIsDisplayed()
    }

    @Test fun onboardingBackPreservesWorkspaceAndMemberValues() {
        show(FinanceUiState(authenticated = true))
        compose.onNodeWithTag("onboarding-start").performClick()
        compose.onNodeWithTag("onboarding-workspace-name").performTextInput("Дом синтетический")
        compose.onNodeWithTag("onboarding-member-name").performTextInput("Taylor Example")
        compose.onNodeWithTag("onboarding-identity-next").performClick()
        compose.onNodeWithTag("onboarding-income-value").performTextInput("13500.25")
        compose.onNodeWithTag("onboarding-income-back").performClick()

        assertEditableText("onboarding-workspace-name", "Дом синтетический")
        assertEditableText("onboarding-member-name", "Taylor Example")
        compose.onNodeWithTag("onboarding-identity-next").performClick()
        assertEditableText("onboarding-income-value", "13500.25")
    }

    @Test fun onboardingSkipIncomeSubmitsNullWithPreservedIdentity() {
        var created: Triple<String, String, String?>? = null
        show(FinanceUiState(authenticated = true), onCreateTenant = { workspace, member, income ->
            created = Triple(workspace, member, income)
        })
        compose.onNodeWithTag("onboarding-start").performClick()
        compose.onNodeWithTag("onboarding-workspace-name").performTextInput("Семья тест")
        compose.onNodeWithTag("onboarding-member-name").performTextInput("Alex Example")
        compose.onNodeWithTag("onboarding-identity-next").performClick()
        compose.onNodeWithTag("onboarding-income-value").performTextInput("24000")
        compose.onNodeWithTag("onboarding-income-skip").performClick()
        compose.onNodeWithTag("onboarding-income-next").performClick()

        assertEquals(Triple("Семья тест", "Alex Example", null), created)
    }

    @Test fun failedWorkspaceCreateCanRetryWithIdentityAndIncomePreserved() {
        val ui = mutableStateOf(FinanceUiState(authenticated = true))
        val attempts = mutableListOf<Triple<String, String, String?>>()
        show(ui.value, stateHolder = ui, onCreateTenant = { workspace, member, income ->
            attempts += Triple(workspace, member, income)
            if (attempts.size == 1) {
                ui.value = ui.value.copy(busy = false, error = "temporary create failure")
            } else {
                ui.value = ui.value.copy(tenants = listOf(tenant("owner")), error = null)
            }
        })

        compose.onNodeWithTag("onboarding-start").performClick()
        compose.onNodeWithTag("onboarding-workspace-name").performTextInput("Retry Family")
        compose.onNodeWithTag("onboarding-member-name").performTextInput("Taylor Example")
        compose.onNodeWithTag("onboarding-identity-next").performClick()
        compose.onNodeWithTag("onboarding-income-value").performTextInput("72500.25")
        compose.onNodeWithTag("onboarding-income-next").performClick()

        compose.onNodeWithText("temporary create failure").assertIsDisplayed()
        assertEditableText("onboarding-income-value", "72500.25")
        compose.onNodeWithTag("onboarding-income-back").performClick()
        assertEditableText("onboarding-workspace-name", "Retry Family")
        assertEditableText("onboarding-member-name", "Taylor Example")
        compose.onNodeWithTag("onboarding-identity-next").performClick()
        assertEditableText("onboarding-income-value", "72500.25")
        compose.onNodeWithTag("onboarding-income-next").assertIsEnabled()
        compose.onNodeWithTag("onboarding-income-next").performClick()

        assertEquals(
            listOf(
                Triple("Retry Family", "Taylor Example", "72500.25"),
                Triple("Retry Family", "Taylor Example", "72500.25"),
            ),
            attempts,
        )
    }

    @Test fun onboardingBudgetProposalShowsExplicitChoicesWithoutAutomaticApply() {
        var applied = emptyList<String>()
        reachOnboardingBudgetChoice(applied = { applied = applied + it })

        compose.onNodeWithTag("onboarding-budget-choice").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-budget-proposal-total").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-budget-apply").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-budget-keep").assertIsDisplayed()
        assertEquals(emptyList<String>(), applied)
    }

    @Test fun budgetProposalPreviewShowsCoreAmountsInRussianAndEnglishUntilExplicitApply() {
        var applied = emptyList<String>()
        reachOnboardingBudgetChoice(applied = { applied = applied + it })

        compose.onNodeWithTag("onboarding-budget-proposal-total")
            .assertTextContains("Предложенный общий лимит: 70 000,00 ₽")
        compose.onNodeWithText("еда · 20 000,00 ₽").assertIsDisplayed()
        compose.onNodeWithText("Предложение не меняет лимиты. Примените его только если суммы вам подходят.")
            .assertIsDisplayed()
        assertEquals(emptyList<String>(), applied)

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithTag("onboarding-budget-proposal-total")
            .assertTextContains("Suggested total limit: 70,000.00 RUB")
        compose.onNodeWithText("20,000.00 RUB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("This suggestion does not change your limits. Apply it only if the amounts work for you.")
            .assertIsDisplayed()
        assertEquals(emptyList<String>(), applied)

        compose.onNodeWithTag("onboarding-budget-apply").performClick()
        assertEquals(listOf("proposal-7"), applied)
    }

    @Test fun keepingBudgetProposalNeverAppliesItAndApplyRequiresExplicitTap() {
        var applied = emptyList<String>()
        reachOnboardingBudgetChoice(applied = { applied = applied + it })

        compose.onNodeWithTag("onboarding-budget-keep").performClick()
        assertEquals(emptyList<String>(), applied)
    }

    @Test fun onboardingBudgetProposalAppliesOnlyAfterExplicitApplyTap() {
        var applied = emptyList<String>()
        reachOnboardingBudgetChoice(applied = { applied = applied + it })

        assertEquals(emptyList<String>(), applied)
        compose.onNodeWithTag("onboarding-budget-apply").performClick()
        assertEquals(listOf("proposal-7"), applied)
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
        compose.onNodeWithTag("onboarding-start").performClick()
        compose.onNodeWithTag("onboarding-workspace-name").performTextInput("Дом")
        compose.onNodeWithTag("onboarding-member-name").performTextInput("Алекс")
        compose.onNodeWithTag("onboarding-identity-next").performClick()
        compose.onNodeWithTag("onboarding-income-skip").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-income-skip").performClick()
        compose.onNodeWithTag("onboarding-income-next").performClick()
        assertEquals(null, createdIncome)

        compose.onNodeWithText("Профиль").performScrollTo().performClick()
        compose.onNodeWithText("Мой профиль").assertIsDisplayed()
        compose.onNodeWithText("Ваше имя").performScrollTo().performTextReplacement("Алексей")
        compose.onNodeWithText("Плановый доход в месяц, ₽").performScrollTo().performTextReplacement("90000")
        compose.onNodeWithText("Сохранить профиль").performScrollTo().performClick()
        compose.onNodeWithText("Дом").assertIsDisplayed()
        assertEquals("Алексей" to "90000.00", updated)
    }

    @Test fun existingUserCanRepeatSetupFromProfileWithoutCreatingWorkspaceOrLosingHistory() {
        var created = 0
        var saved: Pair<String, String?>? = null
        var transactionMutations = 0
        var proposals = emptyList<String?>()
        var applied = emptyList<String>()
        var finishProfileSave: ((Boolean) -> Unit)? = null
        val ui = mutableStateOf(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            transactions = listOf(transaction()),
            memberProfile = FinanceMemberProfile("Алекс", "90000.00", "complete", "Europe/Moscow", "RUB")))
        show(ui.value, stateHolder = ui,
            onCreateTenant = { _, _, _ -> created++ },
            onProfileSave = { name, income ->
                saved = name to income
                ui.value = ui.value.copy(memberProfile = ui.value.memberProfile!!.copy(
                    displayName = name, plannedIncome = income, onboardingState = "complete"))
            },
            onRepeatProfileSave = { name, income, finished ->
                saved = name to income
                ui.value = ui.value.copy(memberProfile = ui.value.memberProfile!!.copy(
                    displayName = name, plannedIncome = income, onboardingState = "complete"))
                finishProfileSave = finished
            },
            onBudgetProposal = { income ->
                proposals = proposals + income
                ui.value = ui.value.copy(budgetProposal = budgetProposal().copy(monthlyIncome = income!!))
            },
            onBudgetApply = { proposalId ->
                applied = applied + proposalId
                ui.value = ui.value.copy(budgetProposal = null)
            },
            onTransactionMutation = { transactionMutations++ })

        compose.onNodeWithText("Операции").performScrollTo().performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithText("Такси").assertIsDisplayed()
        compose.onNodeWithText("Профиль").performScrollTo().performClick()
        compose.onNodeWithText("Пройти настройку заново").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-start").performClick()

        assertEditableText("onboarding-member-name", "Алекс")
        compose.onNodeWithTag("onboarding-member-name").performTextReplacement("Алексей")
        compose.onNodeWithTag("onboarding-identity-next").performClick()
        assertEditableText("onboarding-income-value", "90000.00")
        compose.onNodeWithTag("onboarding-income-value").performTextReplacement("95000")
        compose.onNodeWithTag("onboarding-income-next").performClick()

        assertEquals("Алексей" to "95000.00", saved)
        assertEquals(0, created)
        assertEquals(0, transactionMutations)
        assertEquals(emptyList<String?>(), proposals)
        compose.runOnIdle { finishProfileSave!!.invoke(true) }
        assertEquals(listOf("95000.00"), proposals)
        assertEquals(emptyList<String>(), applied)
        compose.onNodeWithTag("onboarding-budget-choice").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-budget-proposal-total").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-budget-apply").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-budget-keep").assertIsDisplayed()
        assertEquals(emptyList<String>(), applied)
        compose.onNodeWithTag("onboarding-budget-apply").performClick()
        assertEquals(listOf("proposal-7"), applied)
        compose.onNodeWithText("Операции").performScrollTo().performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithText("Такси").assertIsDisplayed()
    }

    @Test fun englishExistingUserCanRepeatSetupAndSkipIncomeThroughProfileUpdate() {
        var created = 0
        var saved: Pair<String, String?>? = null
        var proposals = emptyList<String?>()
        var transactionMutations = 0
        val ui = mutableStateOf(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            transactions = listOf(transaction()),
            memberProfile = FinanceMemberProfile("Alex Example", null, "started", "Europe/Moscow", "RUB")))
        show(ui.value, stateHolder = ui,
            onCreateTenant = { _, _, _ -> created++ },
            onProfileSave = { name, income ->
                saved = name to income
                ui.value = ui.value.copy(memberProfile = ui.value.memberProfile!!.copy(
                    displayName = name, plannedIncome = income, onboardingState = "complete"))
            },
            onBudgetProposal = { income -> proposals = proposals + income },
            onTransactionMutation = { transactionMutations++ })

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Profile").performScrollTo().performClick()
        compose.onNodeWithText("Repeat setup").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-start").performClick()
        assertEditableText("onboarding-member-name", "Alex Example")
        compose.onNodeWithTag("onboarding-identity-next").performClick()
        compose.onNodeWithTag("onboarding-income-skip").performClick()
        compose.onNodeWithTag("onboarding-income-next").performClick()

        assertEquals("Alex Example" to null, saved)
        assertEquals(0, created)
        assertEquals(emptyList<String?>(), proposals)
        assertEquals(0, transactionMutations)
        compose.onNodeWithText("Transactions").performScrollTo().performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithText("Такси").assertIsDisplayed()
    }

    @Test fun repeatSetupSaveFailureDoesNotProposeBudgetAndCanRetry() {
        var proposals = emptyList<String?>()
        val completions = mutableListOf<(Boolean) -> Unit>()
        val ui = mutableStateOf(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            transactions = listOf(transaction()),
            memberProfile = FinanceMemberProfile("Алекс", "90000.00", "complete", "Europe/Moscow", "RUB")))
        show(ui.value, stateHolder = ui,
            onRepeatProfileSave = { _, _, finished -> completions += finished },
            onBudgetProposal = { income ->
                proposals = proposals + income
                ui.value = ui.value.copy(budgetProposal = budgetProposal().copy(monthlyIncome = income!!))
            })

        compose.onNodeWithText("Профиль").performScrollTo().performClick()
        compose.onNodeWithText("Пройти настройку заново").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-start").performClick()
        compose.onNodeWithTag("onboarding-identity-next").performClick()
        compose.onNodeWithTag("onboarding-income-value").performTextReplacement("95000")
        compose.onNodeWithTag("onboarding-income-next").performClick()

        assertEquals(1, completions.size)
        assertEquals(emptyList<String?>(), proposals)
        compose.runOnIdle { completions[0](false) }
        compose.onNodeWithTag("onboarding-income").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-income-next").assertIsEnabled()
        assertEquals(emptyList<String?>(), proposals)

        compose.onNodeWithTag("onboarding-income-next").performClick()
        assertEquals(2, completions.size)
        assertEquals(emptyList<String?>(), proposals)
        compose.runOnIdle { completions[1](true) }
        assertEquals(listOf("95000.00"), proposals)
        compose.onNodeWithTag("onboarding-budget-choice").assertIsDisplayed()
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
        compose.onNodeWithText("Доходы месяца: 95 000,00 ₽").assertIsDisplayed()
        compose.onNodeWithText("Расходы месяца: 42 000,35 ₽").assertIsDisplayed()
        compose.onNodeWithText("Безопасно тратить в день: 4 444,44 ₽").assertIsDisplayed()

        compose.onNodeWithText("Отчёты").performScrollTo().performClick()
        compose.onNodeWithText("Доходы: 120 000,00 ₽").assertIsDisplayed()
        compose.onNodeWithText("Платежи по долгам: 3 000,00 ₽").assertIsDisplayed()
    }

    @Test fun safeToSpendDisclosesActualIncomeBasisAndCoreAmountsInRussianAndEnglish() {
        val safe = SafeToSpend(
            incomeBasis = "actual_income", incomeBase = "120000.00", month = "2026-10",
            horizonDate = "2026-10-20", daysRemaining = 10, monthlyExpenses = "42000.35",
            reserve = "12000.00", promisedPayments = "5000.25", safeTotal = "60999.40",
            safePerDay = "6099.94",
        )
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            dashboardSummary = DashboardSummary(
                month = "2026-10", incomeTotal = "120000.00", expenseTotal = "42000.35",
                transactionCount = 4, asOfDate = "2026-10-10", daysElapsed = 10, daysInMonth = 31,
                daysRemaining = 21, dailyExpensePace = null, projectedExpenseTotal = null,
                rolling7FoodStatus = budget().rolling7FoodStatus, safeToSpend = safe,
            )))

        compose.onNodeWithText("Обзор").performClick()
        compose.onNodeWithText("Расчёт по фактическому доходу: 120 000,00 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Расходы в расчёте: 42 000,35 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Горизонт расчёта: 2026-10-20 · 10 дн.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Свободно до 2026-10-20: 60 999,40 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Безопасно тратить в день: 6 099,94 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Резерв 10%: 12 000,00 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Обязательные списания: 5 000,25 ₽").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Calculated from actual income: 120,000.00 RUB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Expenses used: 42,000.35 RUB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Calculation horizon: 2026-10-20 · 10 days").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Available through 2026-10-20: 60,999.40 RUB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Safe to spend per day: 6,099.94 RUB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("10% reserve: 12,000.00 RUB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Committed payments: 5,000.25 RUB").performScrollTo().assertIsDisplayed()
    }

    @Test fun safeToSpendDisclosesPlannedIncomeBasisAndCoreAmountsInRussianAndEnglish() {
        val safe = SafeToSpend(
            incomeBasis = "planned_income", incomeBase = "80000.00", month = "2026-10",
            horizonDate = "2026-11-05", daysRemaining = 26, monthlyExpenses = "18000.00",
            reserve = "8000.00", promisedPayments = "3500.00", safeTotal = "50500.00",
            safePerDay = "1942.31",
        )
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            dashboardSummary = DashboardSummary(
                month = "2026-10", incomeTotal = "0.00", expenseTotal = "18000.00",
                transactionCount = 2, asOfDate = "2026-10-10", daysElapsed = 10, daysInMonth = 31,
                daysRemaining = 21, dailyExpensePace = null, projectedExpenseTotal = null,
                rolling7FoodStatus = budget().rolling7FoodStatus, safeToSpend = safe,
            )))

        compose.onNodeWithText("Обзор").performClick()
        compose.onNodeWithText("Расчёт по плановому доходу: 80 000,00 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Расходы в расчёте: 18 000,00 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Горизонт расчёта: 2026-11-05 · 26 дн.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Свободно до 2026-11-05: 50 500,00 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Безопасно тратить в день: 1 942,31 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Резерв 10%: 8 000,00 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Обязательные списания: 3 500,00 ₽").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Calculated from planned income: 80,000.00 RUB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Expenses used: 18,000.00 RUB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Calculation horizon: 2026-11-05 · 26 days").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Available through 2026-11-05: 50,500.00 RUB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Safe to spend per day: 1,942.31 RUB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("10% reserve: 8,000.00 RUB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Committed payments: 3,500.00 RUB").performScrollTo().assertIsDisplayed()
    }

    @Test fun dashboardDoesNotInventSafeToSpendWhenCoreOmitsThePlan() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            dashboardSummary = DashboardSummary(
                month = "2026-10", incomeTotal = "0.00", expenseTotal = "0.00", transactionCount = 0,
                asOfDate = "2026-10-01", daysElapsed = 1, daysInMonth = 31, daysRemaining = 30,
                dailyExpensePace = null, projectedExpenseTotal = null,
                rolling7FoodStatus = budget().rolling7FoodStatus, safeToSpend = null,
            )))

        compose.onNodeWithText("Обзор").performClick()
        compose.onNodeWithText("Безопасно тратить в день:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Свободно до", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Safe to spend per day:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Available through", substring = true).assertDoesNotExist()
        compose.onNodeWithText("null").assertDoesNotExist()
    }

    @Test fun dashboardShowsCoreBudgetRemainderPaceAndForecastExactlyInRussianAndEnglish() {
        val summary = DashboardSummary(
            month = "2026-10", incomeTotal = "95000.00", expenseTotal = "42000.35", transactionCount = 8,
            asOfDate = "2026-10-18", daysElapsed = 18, daysInMonth = 31, daysRemaining = 13,
            dailyExpensePace = "1354.27", projectedExpenseTotal = "41982.37",
            rolling7FoodStatus = budget().rolling7FoodStatus,
        )
        val coreReport = report().copy(
            scope = "personal", monthlyBudgetLimit = "50000.00", monthlyBudgetRemaining = "7957.63",
        )
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), budgets = budget(),
            dashboardSummary = summary, report = coreReport))

        compose.onNodeWithText("Обзор").performClick()
        compose.onNodeWithText("Остаток лимита месяца: 7 957,63 ₽").assertExists().assertIsDisplayed()
        compose.onNodeWithText("Средний расход в день: 1 354,27 ₽").assertIsDisplayed()
        compose.onNodeWithText("Прогноз расходов за месяц: 41 982,37 ₽").assertIsDisplayed()

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Monthly budget remaining: 7,957.63 RUB").assertIsDisplayed()
        compose.onNodeWithText("Daily expense pace: 1,354.27 RUB").assertIsDisplayed()
        compose.onNodeWithText("Projected monthly expenses: 41,982.37 RUB").assertIsDisplayed()
    }

    @Test fun dashboardDoesNotInventUnavailablePaceOrMonthlyForecastInRussianAndEnglish() {
        val summary = DashboardSummary(
            month = "2026-10", incomeTotal = "0.00", expenseTotal = "0.00", transactionCount = 0,
            asOfDate = "2026-10-01", daysElapsed = 1, daysInMonth = 31, daysRemaining = 30,
            dailyExpensePace = null, projectedExpenseTotal = null,
            rolling7FoodStatus = budget().rolling7FoodStatus,
        )
        val coreReport = report().copy(
            scope = "personal", monthlyBudgetLimit = "50000.00", monthlyBudgetRemaining = "50000.00",
        )
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), budgets = budget(),
            dashboardSummary = summary, report = coreReport))

        compose.onNodeWithText("Обзор").performClick()
        compose.onNodeWithText("Средний расход в день:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Прогноз расходов за месяц:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("null").assertDoesNotExist()

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Daily expense pace:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Projected monthly expenses:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("null").assertDoesNotExist()
    }

    @Test fun dashboardShowsZeroMonthlyLimitAsDisabledWithoutProgressBar() {
        val disabledBudget = budget().copy(effectiveTotalLimit = "0.00", totalLimitStatus = "disabled")
        val summary = DashboardSummary(
            month = "2026-10", incomeTotal = "0.00", expenseTotal = "12.00", transactionCount = 1,
            asOfDate = "2026-10-01", daysElapsed = 1, daysInMonth = 31, daysRemaining = 30,
            dailyExpensePace = null, projectedExpenseTotal = null,
            rolling7FoodStatus = disabledBudget.rolling7FoodStatus,
        )
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), budgets = disabledBudget,
            dashboardSummary = summary))

        compose.onNodeWithText("Обзор").performClick()
        compose.onNodeWithText("Лимит месяца отключён").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-month-budget-progress").assertDoesNotExist()

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Monthly budget disabled").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-month-budget-progress").assertDoesNotExist()
    }

    @Test fun reportControlsRequestSelectedDateRangeAndFamilyScope() {
        var requested: List<String>? = null
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner"))),
            onReportLoad = { period, scope, month, from, to -> requested = listOf(period, scope, month, from, to) })
        compose.onNodeWithText("Отчёты").performScrollTo().performClick()
        compose.onNodeWithText("Месяц ▾").performClick()
        compose.onNodeWithText("Неделя ▾").performClick()
        compose.onNodeWithText("90 дней ▾").performClick()
        compose.onNodeWithText("Произвольный период ▾").assertIsDisplayed()
        compose.onNodeWithText("Личные").performClick()
        compose.onNodeWithText("Показать отчёт").performClick()

        assertEquals(listOf("custom", "family", "${java.time.YearMonth.now()}",
            "${java.time.YearMonth.now().atDay(1)}", "${java.time.LocalDate.now()}"), requested)
    }

    @Test fun reloadingDisplayedFamilyReportKeepsFamilyBudgetScope() {
        var requestedScope: String? = null
        val familyReport = report().copy(monthlyBudgetLimit = "50000.00", monthlyBudgetRemaining = "-14000.50")
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), report = familyReport),
            onReportLoad = { _, scope, _, _, _ -> requestedScope = scope })

        compose.onNodeWithText("Отчёты").performScrollTo().performClick()
        compose.onNodeWithText("Показать отчёт").performClick()

        assertEquals("family", requestedScope)
        compose.onNodeWithText("Лимит месяца: 50 000,00 ₽ · остаток -14 000,50 ₽")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun reportScreenShowsServerDailyExpensesForZeroAndNonzeroDays() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            report = report().copy(expenseByDay = mapOf("2026-10-01" to "15000.00", "2026-10-02" to "0.00"))))
        compose.onNodeWithText("Отчёты").performScrollTo().performClick()
        compose.onNodeWithTag("report-results").performScrollToIndex(2)
        compose.onNodeWithText("Расходы по дням").assertIsDisplayed()
        compose.onNodeWithTag("report-results").performScrollToIndex(3)
        compose.onNodeWithText("2026-10-01: 15 000,00 ₽").assertIsDisplayed()
        compose.onNodeWithTag("report-results").performScrollToIndex(4)
        compose.onNodeWithText("2026-10-02: 0,00 ₽").assertIsDisplayed()
    }

    @Test fun reportShowsWasteAmountsSourcesAndCorrectionsInBothLanguages() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")), report = report()))
        compose.onNodeWithText("Отчёты").performScrollTo().performClick()
        compose.onNodeWithTag("report-results").performScrollToIndex(1)
        compose.onNodeWithText("Необязательные покупки: 20,00 ₽ · 16,7% от проверенных 120,00 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Источник model: 13,00 ₽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сок: 13,00 ₽ · model").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Исправлено: Молоко · 30,00 ₽").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithTag("report-results").performScrollToIndex(1)
        compose.onNodeWithText("Optional purchases: 20.00 RUB · 16.7% of reviewed 120.00 RUB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Source model: 13.00 RUB").performScrollTo().assertIsDisplayed()
    }

    @Test fun reportShowsCoreOptionalSpendByDayFromAvailableWaste() {
        val waste = report().waste.copy(optionalByDay = mapOf(
            "2026-10-01" to "0.00", "2026-10-02" to "13.00", "2026-10-03" to "7.00"))
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            report = report().copy(toDate = "2026-10-03", waste = waste)))
        compose.onNodeWithText("Отчёты").performScrollTo().performClick()
        compose.onNodeWithTag("report-results").performScrollToIndex(4)
        compose.onNodeWithText("Необязательные покупки по дням").assertIsDisplayed()
        compose.onNodeWithTag("report-results").performScrollToIndex(5)
        compose.onNodeWithText("2026-10-01: 0,00 ₽").assertIsDisplayed()
        compose.onNodeWithTag("report-results").performScrollToIndex(6)
        compose.onNodeWithText("2026-10-02: 13,00 ₽").assertIsDisplayed()
        compose.onNodeWithTag("report-results").performScrollToIndex(7)
        compose.onNodeWithText("2026-10-03: 7,00 ₽").assertIsDisplayed()
    }

    @Test fun reportUnavailableWasteShowsReasonWithoutZeroAmount() {
        val unavailable = FinanceWasteReport(false, "missing_amounts", "partial", null, null, null,
            2, 0, 1, emptyMap(), emptyList(), emptyList())
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            report = report().copy(waste = unavailable)))
        compose.onNodeWithText("Отчёты").performScrollTo().performClick()
        compose.onNodeWithTag("report-results").performScrollToIndex(1)
        compose.onNodeWithText("Не все позиции чеков имеют сумму (1); итоги не рассчитаны.").assertIsDisplayed()
        compose.onNodeWithText("Необязательные покупки по дням").assertDoesNotExist()
        compose.onNodeWithText("0.00 ₽").assertDoesNotExist()
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
        compose.onNodeWithTag("transaction-history").performScrollToIndex(1)
        compose.onNodeWithText("Опишите операцию").performTextInput("Такси 2 тыс")
        compose.onNodeWithText("Разобрать текст").performClick()

        assertEquals("Такси 2 тыс", submittedText)
        assertEquals(null, confirmation)

        compose.onNodeWithText("Предложение операции").assertIsDisplayed()
        compose.onNodeWithText("Провайдер: ollama · модель: local-test · промпт: transaction-draft.v1")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 000 ₽").performScrollTo().performClick()
        compose.onNodeWithText("Сумма, ₽").assertIsDisplayed()
        compose.onNodeWithText("Подтвердить").assertIsNotEnabled()
        compose.onNodeWithText("Сохранить изменения").performScrollTo().performClick()
        assertEquals("1000.00", edit?.amount)
        assertEquals(1L, edit?.version)

        compose.onNodeWithText("Подтвердить").performScrollTo().performClick()
        assertEquals("draft-1" to 2L, confirmation)
    }

    @Test fun transactionDraftCanBeCancelledAtItsCurrentVersion() {
        var cancelled: Pair<String, Long>? = null
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            transactionDraft = draft(version = 4)), onCancelDraft = { id, version -> cancelled = id to version })
        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(1)
        compose.onNodeWithText("Предложение операции").assertIsDisplayed()
        compose.onNodeWithText("Отменить").performScrollTo().performClick()
        assertEquals("draft-1" to 4L, cancelled)
    }

    @Test fun postedTransactionHistoryOffersRepeatAndVoidWithExactValues() {
        val original = transaction()
        var repeated: FinanceTransaction? = null
        var voided: Pair<String, Long>? = null
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            transactions = listOf(original)),
            onRepeatTransaction = { repeated = it },
            onVoidTransaction = { item -> voided = item.id to item.version })

        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithText("Такси").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("−2 000,00 ₽", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("transaction-repeat-tx-1").performScrollTo().performClick()
        assertEquals(original, repeated)
        compose.onNodeWithTag("transaction-void-tx-1").performScrollTo().performClick()
        assertEquals("tx-1" to 3L, voided)

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithText("Такси").assertIsDisplayed()
        compose.onNodeWithText("−2,000.00 RUB", substring = true).assertIsDisplayed()
    }

    @Test fun ownerCanOpenEditorForPostedTransactionWithoutChangingExactHistoryValues() {
        val original = transaction()
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            transactions = listOf(original)))

        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithText("Такси").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("−2 000,00 ₽", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("transaction-edit-${original.id}").performScrollTo().assertIsDisplayed()
    }

    @Test fun ownerCanSaveExactVersionedTransactionEdits() {
        val original = transaction()
        var saved: FinanceTransactionEdit? = null
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            transactions = listOf(original)), onUpdateTransaction = { saved = it })

        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithTag("transaction-edit-${original.id}").performScrollTo().performClick()
        compose.onNodeWithTag("transaction-edit-form").performScrollTo()
        assertEditableText("transaction-edit-amount", "2000.00")
        assertEditableText("transaction-edit-category", "transport")
        assertEditableText("transaction-edit-subcategory", "taxi")
        assertEditableText("transaction-edit-description", "Такси")
        assertEditableText("transaction-edit-source", "manual")
        assertEditableText("transaction-edit-date", "2026-10-08")

        compose.onNodeWithTag("transaction-edit-type").performClick()
        compose.onNodeWithText("income").performClick()
        compose.onNodeWithTag("transaction-edit-amount").performTextReplacement("2750.50")
        compose.onNodeWithTag("transaction-edit-category").performTextReplacement("salary")
        compose.onNodeWithTag("transaction-edit-subcategory").performTextReplacement("bonus")
        compose.onNodeWithTag("transaction-edit-description").performTextReplacement("Зарплата")
        compose.onNodeWithTag("transaction-edit-date").performTextReplacement("2026-10-07")
        compose.onNodeWithTag("transaction-edit-save").performScrollTo().performClick()

        assertEquals(FinanceTransactionEdit(
            id = "tx-1", version = 3L, type = "income", amount = "2750.50", currency = "RUB",
            categoryCode = "salary", subcategoryCode = "bonus", description = "Зарплата", source = "manual",
            occurredAt = "2026-10-07T09:00:00Z", debtId = null, ownerUserId = "user-1", accountId = null,
        ), saved)
    }

    @Test fun memberCanEditOwnPostedTransactionButNotAnotherMembersTransaction() {
        val own = transaction()
        val anotherMember = transaction().copy(id = "tx-other", description = "Other", ownerUserId = "user-2")
        var saved: FinanceTransactionEdit? = null
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("member")),
            transactions = listOf(own, anotherMember),
            transactionMembers = listOf(FinanceTenantMember("user-1", "User", "member"))),
            onUpdateTransaction = { saved = it })

        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithTag("transaction-edit-tx-1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("transaction-edit-tx-1").performClick()
        compose.onNodeWithTag("transaction-edit-owner-picker").assertDoesNotExist()
        compose.onNodeWithTag("transaction-edit-description").performTextReplacement("Такси уточнено")
        compose.onNodeWithTag("transaction-edit-save").performScrollTo().performClick()
        assertEquals("user-1", saved?.ownerUserId)
        assertEquals(3L, saved?.version)
        compose.onNodeWithTag("transaction-edit-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(4)
        compose.onNodeWithTag("transaction-edit-tx-other").assertDoesNotExist()
    }

    @Test fun ownerCanReassignPostedTransactionToActiveMemberByName() {
        assertManagerCanReassignTransaction("owner")
    }

    @Test fun adminCanReassignPostedTransactionToActiveMemberByName() {
        assertManagerCanReassignTransaction("admin")
    }

    @Test fun transactionEditorUsesTenantLocalDateAcrossUtcBoundary() {
        val lateUtc = transaction().copy(occurredAt = "2026-10-07T21:30:00Z")
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            transactions = listOf(lateUtc)))

        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithTag("transaction-edit-tx-1").performScrollTo().performClick()
        compose.onNodeWithTag("transaction-edit-form").performScrollTo()

        assertEditableText("transaction-edit-date", "2026-10-08")
    }

    @Test fun transactionEditorDisablesSaveForCoreFieldLengthLimits() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            transactions = listOf(transaction())))

        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithTag("transaction-edit-tx-1").performScrollTo().performClick()
        compose.onNodeWithTag("transaction-edit-form").performScrollTo()

        val category = compose.onNodeWithTag("transaction-edit-category")
        category.performTextReplacement("c".repeat(65))
        compose.onNodeWithTag("transaction-edit-save").assertIsNotEnabled()
        category.performTextReplacement("transport")

        val subcategory = compose.onNodeWithTag("transaction-edit-subcategory")
        subcategory.performTextReplacement("s".repeat(65))
        compose.onNodeWithTag("transaction-edit-save").assertIsNotEnabled()
        subcategory.performTextReplacement("taxi")

        val source = compose.onNodeWithTag("transaction-edit-source")
        source.performTextReplacement("s".repeat(65))
        compose.onNodeWithTag("transaction-edit-save").assertIsNotEnabled()
        source.performTextReplacement("manual")

        compose.onNodeWithTag("transaction-edit-description").performTextReplacement("d".repeat(501))
        compose.onNodeWithTag("transaction-edit-save").assertIsNotEnabled()
    }

    @Test fun viewerCannotRepeatOrVoidPostedTransactions() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("viewer")),
            transactions = listOf(transaction())))
        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithTag("transaction-repeat-tx-1").assertDoesNotExist()
        compose.onNodeWithTag("transaction-edit-tx-1").assertDoesNotExist()
        compose.onNodeWithTag("transaction-void-tx-1").assertDoesNotExist()
    }

    @Test fun transactionHistorySearchAndTypeFilterRequestCoreResultsAndCanLoadNextPage() {
        val grocery = transaction().copy(description = "Продукты")
        val salary = transaction().copy(id = "tx-2", type = "income", description = "Зарплата")
        val queries = mutableListOf<Pair<String, String>>()
        var loadedMore = 0
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            transactions = listOf(grocery, salary), transactionNextCursor = "cursor-2"),
            onTransactionFilter = { search, type, _, _, _ -> queries += search to type },
            onTransactionLoadMore = { loadedMore++ })

        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-search").performTextInput("зарп")
        compose.onNodeWithText("Применить фильтры").performScrollTo().performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(4)
        compose.onNodeWithText("Зарплата").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Продукты").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("transaction-search").performTextReplacement("")
        compose.onNodeWithTag("transaction-filter-expense").performScrollTo().performClick()
        compose.onNodeWithText("Применить фильтры").performScrollTo().performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithText("Продукты").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(4)
        compose.onNodeWithText("Зарплата").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("зарп" to "all", "" to "expense"), queries)

        compose.onNodeWithTag("transaction-history").performScrollToIndex(5)
        compose.onNodeWithText("Загрузить ещё").performScrollTo().performClick()
        assertEquals(1, loadedMore)
    }

    @Test fun transactionDateAndMemberFiltersReachCoreWhenApplied() {
        var applied: List<String>? = null
        val member = FinanceTenantMember("member-42", "Taylor Display", "member")
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            transactionMembers = listOf(member)),
            onTransactionFilter = { search, type, from, to, memberId ->
                applied = listOf(search, type, from, to, memberId)
            })

        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-search").performTextInput("зарп")
        compose.onNodeWithTag("transaction-filter-expense").performScrollTo().performClick()
        compose.onNodeWithTag("transaction-filter-from").performScrollTo().performTextInput("2026-10-01")
        compose.onNodeWithTag("transaction-filter-to").performScrollTo().performTextInput("2026-10-31")
        compose.onNodeWithTag("transaction-filter-member-picker").performScrollTo().performClick()
        compose.onNodeWithTag("transaction-member-option-all").assertIsDisplayed()
        compose.onNodeWithTag("transaction-member-option-member-42").assertIsDisplayed()
        compose.onNodeWithTag("transaction-member-option-member-42").performClick()
        compose.onNodeWithText("Участник: Taylor Display").assertIsDisplayed()
        compose.onNodeWithText("Применить фильтры").performScrollTo().performClick()

        assertEquals(listOf("зарп", "expense", "2026-10-01", "2026-10-31", "member-42"), applied)
    }

    @Test fun reversedTransactionDateRangeShowsLocalizedErrorAndDoesNotApply() {
        var applied: List<String>? = null
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner"))),
            onTransactionFilter = { search, type, from, to, memberId ->
                applied = listOf(search, type, from, to, memberId)
            })

        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-filter-from").performScrollTo().performTextInput("2026-10-31")
        compose.onNodeWithTag("transaction-filter-to").performScrollTo().performTextInput("2026-10-01")
        compose.onNodeWithText("Применить фильтры").performScrollTo().performClick()

        assertEquals(null, applied)
        compose.onNodeWithText("Дата начала должна быть не позже даты окончания").assertIsDisplayed()

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithTag("transaction-filter-from").performScrollTo().performTextReplacement("2026-10-31")
        compose.onNodeWithTag("transaction-filter-to").performScrollTo().performTextReplacement("2026-10-01")
        compose.onNodeWithText("Apply filters").performScrollTo().performClick()

        assertEquals(null, applied)
        compose.onNodeWithText("Start date must be on or before end date").assertIsDisplayed()
    }

    @Test fun viewerMemberPickerUsesCoreSuppliedSelfAndHidesAllMembers() {
        val self = FinanceTenantMember("viewer-42", "Viewer Display", "member")
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("viewer")),
            transactionMembers = listOf(self)))

        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-filter-member-picker").performScrollTo().performClick()
        compose.onNodeWithTag("transaction-member-option-self").assertIsDisplayed()
        compose.onNodeWithTag("transaction-member-option-viewer-42").assertIsDisplayed()
        compose.onNodeWithText("Viewer Display").assertIsDisplayed()
        compose.onNodeWithTag("transaction-member-option-all").assertDoesNotExist()
    }

    @Test fun transactionTypeFilterShowsAppliedSelection() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            transactions = listOf(transaction())))

        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-filter-expense").performScrollTo().performClick()
        compose.onNodeWithText("Применить фильтры").performScrollTo().performClick()

        compose.onNodeWithTag("transaction-filter-expense")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithTag("transaction-filter-income")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test fun emptyTransactionResultsExplainAppliedFiltersInRussianAndEnglish() {
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner"))))

        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithText("Нет операций по выбранным фильтрам").assertIsDisplayed()

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("No transactions match these filters").assertIsDisplayed()
    }

    private fun show(state: FinanceUiState,
                     onReportLoad: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
                     onFamilyBudgetFoodStatusLoad: (String) -> Unit = {},
                     onCreateTenant: (String, String, String?) -> Unit = { _, _, _ -> },
                     onBudgetUpdate: (String, String, String, String, Long) -> Unit = { _, _, _, _, _ -> },
                     onBudgetReset: () -> Unit = {},
                     onBudgetApply: (String) -> Unit = {},
                     onBudgetProposal: (String?) -> Unit = {},
                     onTransactionMutation: () -> Unit = {},
                     stateHolder: androidx.compose.runtime.MutableState<FinanceUiState>? = null,
                     onProfileSave: (String, String?) -> Unit = { _, _ -> },
                     onRepeatProfileSave: (String, String?, (Boolean) -> Unit) -> Unit = { name, income, finished ->
                         onProfileSave(name, income)
                         finished(true)
                     },
                     onCreateDraft: (String, String) -> Unit = { _, _ -> }, onUpdateDraft: (TransactionDraftEdit) -> Unit = {},
                     onConfirmDraft: (String, Long) -> Unit = { _, _ -> },
                     onCancelDraft: (String, Long) -> Unit = { _, _ -> },
                     onCreateTelegramLink: () -> Unit = {},
                      onNotificationPreferencesSave: (FinanceNotificationPreferences) -> Unit = {},
                      onShoppingDecision: (String, String) -> Unit = { _, _ -> },
                      onShoppingCopy: (String) -> Unit = {},
                      onDoNotBuyDecision: (String, String) -> Unit = { _, _ -> },
                      onPersonalInflationLoad: () -> Unit = {},
                     onRecurringLoad: () -> Unit = {},
                      onRecurringDecision: (String, Boolean) -> Unit = { _, _ -> },
                     onRepeatTransaction: (FinanceTransaction) -> Unit = {},
                      onVoidTransaction: (FinanceTransaction) -> Unit = {},
                     onTransactionFilter: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
                     onTransactionLoadMore: () -> Unit = {},
                     onUpdateTransaction: (FinanceTransactionEdit) -> Unit = {},
                     onDebtCreate: (String, String, String?, String) -> Unit = { _, _, _, _ -> },
                     onDebtPay: (String, String, Long) -> Unit = { _, _, _ -> },
                     onDebtAdjust: (String, String, Long) -> Unit = { _, _, _ -> },
                     onDebtForecast: (String) -> Unit = {}) {
        val language = mutableStateOf("ru")
        val contentKey = Any()
        compose.setContent {
        MaterialTheme {
            androidx.compose.runtime.key(contentKey) {
            FinanceScreen(
                state = stateHolder?.value ?: state,
                language = language.value,
                onLanguage = { language.value = it }, onLogin = {}, onRefresh = {}, onCreate = onCreateTenant,
                onProfileSave = onProfileSave, onRepeatProfileSave = { name, income, finished ->
                    onRepeatProfileSave(name, income, finished)
                }, onCreateDraft = { text, key -> onTransactionMutation(); onCreateDraft(text, key) },
                onCreateTelegramLink = onCreateTelegramLink,
                onNotificationPreferencesSave = onNotificationPreferencesSave,
                onShoppingDecision = onShoppingDecision, onShoppingCopy = onShoppingCopy,
                onDoNotBuyDecision = onDoNotBuyDecision,
                onPersonalInflationLoad = onPersonalInflationLoad,
                onRecurringLoad = onRecurringLoad,
                onRecurringDecision = onRecurringDecision,
                onUpdateDraft = { edit -> onTransactionMutation(); onUpdateDraft(edit) },
                onConfirmDraft = { id, version -> onTransactionMutation(); onConfirmDraft(id, version) },
                onCancelDraft = { id, version -> onTransactionMutation(); onCancelDraft(id, version) },
                onLogout = {},
                onBudgetUpdate = onBudgetUpdate, onBudgetReset = onBudgetReset,
                onBudgetProposal = onBudgetProposal, onBudgetApply = onBudgetApply,
                onDebtCreate = onDebtCreate, onDebtPay = onDebtPay, onDebtAdjust = onDebtAdjust, onDebtForecast = onDebtForecast,
                onReportLoad = onReportLoad,
                onFamilyBudgetFoodStatusLoad = onFamilyBudgetFoodStatusLoad,
                onRepeatTransaction = { transaction -> onTransactionMutation(); onRepeatTransaction(transaction) },
                onVoidTransaction = { transaction -> onTransactionMutation(); onVoidTransaction(transaction) },
                onTransactionFilter = onTransactionFilter, onTransactionLoadMore = onTransactionLoadMore,
                onUpdateTransaction = { edit -> onTransactionMutation(); onUpdateTransaction(edit) },
            )
            }
        }
        }
    }

    private fun reachOnboardingBudgetChoice(applied: (String) -> Unit) {
        val ui = mutableStateOf(FinanceUiState(authenticated = true))
        show(ui.value, stateHolder = ui, onCreateTenant = { _, _, income ->
            ui.value = ui.value.copy(
                tenants = listOf(tenant("owner")),
                budgetProposal = budgetProposal().copy(monthlyIncome = income ?: "0.00"),
            )
        }, onBudgetApply = applied)

        compose.onNodeWithTag("onboarding-start").performClick()
        compose.onNodeWithTag("onboarding-workspace-name").performTextInput("Синтетический дом")
        compose.onNodeWithTag("onboarding-member-name").performTextInput("Taylor Example")
        compose.onNodeWithTag("onboarding-identity-next").performClick()
        compose.onNodeWithTag("onboarding-income-value").performTextInput("100000")
        compose.onNodeWithTag("onboarding-income-next").performClick()
    }

    private fun assertEditableText(tag: String, expected: String) {
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(expected)))
    }

    private fun assertManagerCanReassignTransaction(role: String) {
        val original = transaction().copy(ownerUserId = "member-old", memberName = "Sam Example")
        val members = listOf(
            FinanceTenantMember("manager-1", "Alex Example", role),
            FinanceTenantMember("member-old", "Sam Example", "member"),
            FinanceTenantMember("member-target", "Taylor Test", "member"),
        )
        var saved: FinanceTransactionEdit? = null
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant(role)),
            transactions = listOf(original), transactionMembers = members),
            onUpdateTransaction = { saved = it })

        compose.onNodeWithText("Операции").performClick()
        compose.onNodeWithTag("transaction-history").performScrollToIndex(3)
        compose.onNodeWithTag("transaction-edit-${original.id}").performScrollTo().performClick()
        compose.onNodeWithTag("transaction-edit-owner-picker").performScrollTo().performClick()
        compose.onNodeWithText("Taylor Test").performClick()
        compose.onNodeWithText("Пользователь операции: Taylor Test").assertIsDisplayed()
        compose.onNodeWithTag("transaction-edit-save").performScrollTo().performClick()

        assertEquals(FinanceTransactionEdit(
            id = original.id, version = original.version, type = original.type, amount = original.amount,
            currency = original.currency, categoryCode = original.categoryCode,
            subcategoryCode = original.subcategoryCode, description = original.description,
            source = original.source, occurredAt = "2026-10-08T09:00:00Z", debtId = original.debtId,
            ownerUserId = "member-target", accountId = original.accountId,
        ), saved)
    }

    private fun tenant(role: String) = FinanceTenant("tenant-1", "Дом", role, "Europe/Moscow")

    private fun transaction() = FinanceTransaction(
        id = "tx-1", tenantId = "tenant-1", type = "expense", amount = "2000.00", currency = "RUB",
        categoryCode = "transport", subcategoryCode = "taxi", description = "Такси", source = "manual",
        occurredAt = "2026-10-08T09:00:00Z", accountId = null, status = "posted", version = 3,
        createdAt = "2026-10-08T09:00:00Z", memberName = "User", debtId = null, ownerUserId = "user-1",
    )

    private fun recurring(id: String, key: String, name: String, type: String, amount: String, daysUntil: Int, nextDate: String) =
        FinanceRecurringSeries(id, key, name, if (type == "expense") "bills" else null, type, "RUB", amount,
            amount, amount, if (key == "salary") "month" else "week", if (key == "salary") 30 else 7,
            if (key == "salary") 29 else 7, if (key == "salary") 31 else 7, 3,
            if (key == "salary") "2026-09-10" else if (daysUntil < 0) "2026-09-28" else "2026-10-02",
            nextDate, daysUntil)

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

    private fun budgetProposal() = BudgetProposal(
        id = "proposal-7", monthlyIncome = "100000.00", totalLimit = "70000.00",
        limits = mapOf("еда" to "20000.00"), status = "pending", proposalSource = "history",
        historyDays = 90, modelVersion = null,
    )

    private fun debt() = FinanceDebt("debt-1", "tenant-1", "Кредитная карта", "10000.00", "8400.00",
        "19.9", "500.00", "open", 3L)

    private fun shopping() = FinanceShoppingList(listOf(FinanceShoppingCandidate(
        "Молоко 1 л", "milk", 3, 10, "100.000000", "100.00", "2026-10-04T00:00:00Z",
        "2026-10-05T00:00:00Z", 0)), "100.00", false)

    private fun report() = FinanceReport("month", "family", "2026-10-01", "2026-10-01", "2026-10-01",
        "Europe/Moscow", "RUB", "120000.00", "64000.50", "3000.00", "250.00", 12,
        mapOf("еда" to "15000.25"), null, "50000.00", "-14000.50", budget().rolling7FoodStatus,
        waste = FinanceWasteReport(true, "available", "complete", "120.00", "20.00", "0.166667",
            5, 2, 0, mapOf("model" to "13.00", "rule" to "7.00"),
            listOf(FinanceWasteItem("Сок", "13.00", "optional", "model")),
            listOf(FinanceWasteCorrection("Молоко", 1, "30.00")),
            optionalByDay = mapOf("2026-10-01" to "0.00")))
}
