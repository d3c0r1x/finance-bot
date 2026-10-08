package com.decorix.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
        show(FinanceUiState(authenticated = true, tenants = listOf(tenant("owner")),
            budgets = budget(), debts = listOf(debt())))

        compose.onNodeWithText("Бюджеты").performClick()
        compose.onNodeWithText("Лимиты · 2026-10").assertIsDisplayed()
        compose.onNodeWithText("Расход еды за 7 дней: 350,25 ₽ / 1 000,00 ₽ · Недостаточно истории").assertIsDisplayed()

        compose.onNodeWithText("Долги").performScrollTo().performClick()
        compose.onNodeWithText("Кредитная карта").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Записать платёж").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Прогноз выплаты").performScrollTo().assertIsDisplayed()
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
        compose.onNodeWithText("Доходы месяца: 95 000,00 ₽").assertIsDisplayed()
        compose.onNodeWithText("Расходы месяца: 42 000,35 ₽").assertIsDisplayed()
        compose.onNodeWithText("Безопасно тратить в день: 4 444,44 ₽").assertIsDisplayed()

        compose.onNodeWithText("Отчёты").performScrollTo().performClick()
        compose.onNodeWithText("Доходы: 120 000,00 ₽").assertIsDisplayed()
        compose.onNodeWithText("Платежи по долгам: 3 000,00 ₽").assertIsDisplayed()
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
                     onCreateTenant: (String, String, String?) -> Unit = { _, _, _ -> },
                     onProfileSave: (String, String?) -> Unit = { _, _ -> },
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
                      onUpdateTransaction: (FinanceTransactionEdit) -> Unit = {}) {
        val language = mutableStateOf("ru")
        val contentKey = Any()
        compose.setContent {
        MaterialTheme {
            androidx.compose.runtime.key(contentKey) {
            FinanceScreen(
                state = state,
                language = language.value,
                onLanguage = { language.value = it }, onLogin = {}, onRefresh = {}, onCreate = onCreateTenant,
                onProfileSave = onProfileSave, onCreateDraft = onCreateDraft,
                onCreateTelegramLink = onCreateTelegramLink,
                onNotificationPreferencesSave = onNotificationPreferencesSave,
                onShoppingDecision = onShoppingDecision, onShoppingCopy = onShoppingCopy,
                onDoNotBuyDecision = onDoNotBuyDecision,
                onPersonalInflationLoad = onPersonalInflationLoad,
                onRecurringLoad = onRecurringLoad,
                onRecurringDecision = onRecurringDecision,
                onUpdateDraft = onUpdateDraft, onConfirmDraft = onConfirmDraft, onCancelDraft = onCancelDraft,
                onLogout = {},
                onBudgetUpdate = { _, _, _, _, _ -> }, onBudgetReset = {}, onBudgetProposal = {}, onBudgetApply = {},
                onDebtCreate = { _, _, _, _ -> }, onDebtPay = { _, _, _ -> }, onDebtAdjust = { _, _, _ -> }, onDebtForecast = {},
                onReportLoad = onReportLoad,
                onRepeatTransaction = onRepeatTransaction, onVoidTransaction = onVoidTransaction,
                onTransactionFilter = onTransactionFilter, onTransactionLoadMore = onTransactionLoadMore,
                onUpdateTransaction = onUpdateTransaction,
            )
            }
        }
        }
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
