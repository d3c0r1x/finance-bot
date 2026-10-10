package com.decorix.finance

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceModelsTest {
    @Test fun parsesTransactionHistoryWithoutChangingExactMoneyOrVersion() {
        val transaction = FinanceModels.transaction(JSONObject("""
            {"id":"tx-1","tenantId":"tenant-1","type":"expense","amount":"2000.00","currency":"RUB",
             "categoryCode":"transport","subcategoryCode":"taxi","description":"Такси","source":"manual",
             "occurredAt":"2026-10-08T09:00:00Z","accountId":null,"status":"posted","version":3,
             "createdAt":"2026-10-08T09:00:00Z","memberName":"User","debtId":null,"ownerUserId":"user-1"}
        """.trimIndent()))

        assertEquals("2000.00", transaction.amount)
        assertEquals(3L, transaction.version)
        assertEquals("posted", transaction.status)
        assertEquals("user-1", transaction.ownerUserId)
        assertNull(transaction.accountId)
        assertNull(transaction.debtId)
    }

    @Test fun parsesTransactionPageAndRetainsOpaqueNextCursor() {
        val page = FinanceModels.transactionPage(JSONObject("""
            {"items":[{"id":"tx-1","tenantId":"tenant-1","type":"expense","amount":"0.10",
             "currency":"RUB","categoryCode":"food","subcategoryCode":null,"description":"Чай",
             "source":"manual","occurredAt":"2026-10-08T09:00:00Z","accountId":null,"status":"posted",
             "version":1,"createdAt":"2026-10-08T09:00:00Z","memberName":"User","debtId":null,
             "ownerUserId":"user-1"}],"nextCursor":"opaque-cursor-2"}
        """.trimIndent()))

        assertEquals("0.10", page.items.single().amount)
        assertEquals("opaque-cursor-2", page.nextCursor)
        assertEquals("pageSize=50&cursor=opaque%20cursor&from=2026-10-01&to=2026-10-31&type=expense&search=tea%20coffee&memberId=member%201",
            FinanceModels.transactionQuery(cursor = "opaque cursor", from = "2026-10-01", to = "2026-10-31",
                type = "expense", search = "tea coffee", memberId = "member 1"))
    }

    @Test fun parsesTenantMembersForTransactionFilterPicker() {
        val members = FinanceModels.tenantMembers(org.json.JSONArray("""
            [{"userId":"member-42","displayName":"Taylor Display","role":"member"},
             {"userId":"owner-1","displayName":"Owner Display","role":"owner"}]
        """.trimIndent()))

        assertEquals(listOf(
            FinanceTenantMember("member-42", "Taylor Display", "member"),
            FinanceTenantMember("owner-1", "Owner Display", "owner"),
        ), members)
    }

    @Test fun transactionQueryOmitsBlankOptionalFiltersAndEncodesReservedCharacters() {
        assertEquals("pageSize=1", FinanceModels.transactionQuery(pageSize = 1, cursor = " ", from = "",
            to = null, type = "", search = "  ", memberId = null))
        assertEquals("pageSize=200&search=x%26y%3D1%2B%20%2F%D1%8F",
            FinanceModels.transactionQuery(pageSize = 200, search = "x&y=1+ /я"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun transactionQueryRejectsPageSizeBelowOne() {
        FinanceModels.transactionQuery(pageSize = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun transactionQueryRejectsPageSizeAboveTwoHundred() {
        FinanceModels.transactionQuery(pageSize = 201)
    }

    @Test fun parsesServerBudgetAlertsWithoutRecomputingThresholds() {
        val alerts = FinanceModels.budgetAlerts(JSONObject("""
            {"budgetAlerts":[{"budgetKey":"еда","threshold":"near","limit":"5000.00","spent":"4500.00"},
              {"budgetKey":"__total__","threshold":"exceeded","limit":"55000.00","spent":"56000.00"}]}
        """.trimIndent()).optJSONArray("budgetAlerts"))

        assertEquals(2, alerts.size)
        assertEquals(FinanceBudgetAlert("еда", "near", "5000.00", "4500.00"), alerts[0])
        assertEquals(FinanceBudgetAlert("__total__", "exceeded", "55000.00", "56000.00"), alerts[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownBudgetAlertThreshold() {
        FinanceModels.budgetAlerts(org.json.JSONArray("""
            [{"budgetKey":"еда","threshold":"disabled","limit":"5000.00","spent":"4500.00"}]
        """.trimIndent()))
    }

    @Test fun parsesTelegramLinkCodeAndExpiry() {
        val code = FinanceModels.telegramLinkCode(JSONObject("""
            {"code":"ABCD-EFGH-JKLM-NPQR","expiresAt":"2026-10-02T08:20:00Z"}
        """.trimIndent()))
        assertEquals("ABCD-EFGH-JKLM-NPQR", code.code)
        assertEquals("2026-10-02T08:20:00Z", code.expiresAt)
    }

    @Test fun parsesMemberProfileAndKeepsSkippedIncomeNull() {
        val withIncome = FinanceModels.memberProfile(JSONObject("""
            {"displayName":"Alex","plannedIncome":120000.5,"onboardingState":"complete",
             "timezone":"Europe/Moscow","currency":"RUB"}
        """.trimIndent()))
        val skipped = FinanceModels.memberProfile(JSONObject("""
            {"displayName":"Alex","plannedIncome":null,"onboardingState":"started",
             "timezone":"Europe/Moscow","currency":"RUB"}
        """.trimIndent()))

        assertEquals("120000.50", withIncome.plannedIncome)
        assertNull(skipped.plannedIncome)
        assertEquals("started", skipped.onboardingState)
    }

    @Test fun parsesNotificationPreferencesWithoutChangingServerSchedule() {
        val preferences = FinanceModels.notificationPreferences(JSONObject("""
            {"timezone":"Europe/Moscow","telegramLinked":true,"language":"ru","dailyEnabled":true,
             "dailyLocalTime":"21:00","weeklyEnabled":true,"weeklyDayOfWeek":7,"weeklyLocalTime":"19:00",
             "quietHoursStart":"22:00","quietHoursEnd":"07:00","version":4}
        """.trimIndent()))

        assertEquals(FinanceNotificationPreferences("Europe/Moscow", true, "ru", true, "21:00",
            true, 7, "19:00", "22:00", "07:00", 4L), preferences)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompleteNotificationQuietHours() {
        FinanceModels.notificationPreferences(JSONObject("""
            {"timezone":"Europe/Moscow","telegramLinked":true,"language":"en","dailyEnabled":true,
             "dailyLocalTime":"21:00","weeklyEnabled":false,"weeklyDayOfWeek":7,"weeklyLocalTime":"19:00",
             "quietHoursStart":"22:00","quietHoursEnd":null,"version":0}
        """.trimIndent()))
    }

    @Test fun parsesDashboardSummaryWithNullablePaceAndExactTotals() {
        val summary = FinanceModels.dashboardSummary(JSONObject("""
            {"month":"2026-10","currency":"RUB","incomeTotal":"95000.00","expenseTotal":"42000.35",
             "transactionCount":8,"asOfDate":"2026-10-01","daysElapsed":1,"daysInMonth":31,"daysRemaining":30,
             "dailyExpensePace":null,"projectedExpenseTotal":null,
             "rolling7FoodStatus":{"fromDate":"2026-09-25","toDate":"2026-10-01","limit":"1000.00",
               "spent":"350.25","remaining":"649.75","limitStatus":"normal","usualWeeklySpend":null,
               "historyWeeks":0,"paceStatus":"insufficient_history","paceShare":null}}
        """.trimIndent()))

        assertEquals("95000.00", summary.incomeTotal)
        assertEquals("42000.35", summary.expenseTotal)
        assertNull(summary.dailyExpensePace)
        assertEquals("2026-10-01", summary.asOfDate)
    }

    @Test fun parsesSafeToSpendAsServerOwnedDecimalStrings() {
        val summary = FinanceModels.dashboardSummary(JSONObject("""
            {"month":"2026-10","currency":"RUB","incomeTotal":"50000.00","expenseTotal":"20000.00",
             "transactionCount":2,"asOfDate":"2026-10-01","daysElapsed":1,"daysInMonth":31,"daysRemaining":30,
             "dailyExpensePace":"20000.00","projectedExpenseTotal":"620000.00",
             "safeToSpend":{"incomeBasis":"actual_income","incomeBase":"50000.00","month":"2026-10",
               "horizonDate":"2026-10-31","daysRemaining":30,"monthlyExpenses":"20000.00","reserve":"5000.00",
               "promisedPayments":"3000.00","safeTotal":"22000.00","safePerDay":"733.33"},
             "rolling7FoodStatus":{"fromDate":"2026-09-25","toDate":"2026-10-01","limit":"1000.00",
               "spent":"350.25","remaining":"649.75","limitStatus":"normal","usualWeeklySpend":null,
               "historyWeeks":0,"paceStatus":"insufficient_history","paceShare":null}}
        """.trimIndent()))

        val safe = requireNotNull(summary.safeToSpend)
        assertEquals("actual_income", safe.incomeBasis)
        assertEquals("50000.00", safe.incomeBase)
        assertEquals("2026-10", safe.month)
        assertEquals("2026-10-31", safe.horizonDate)
        assertEquals(30, safe.daysRemaining)
        assertEquals("20000.00", safe.monthlyExpenses)
        assertEquals("5000.00", safe.reserve)
        assertEquals("3000.00", safe.promisedPayments)
        assertEquals("22000.00", safe.safeTotal)
        assertEquals("733.33", safe.safePerDay)
    }

    @Test fun parsesPersonalAndFamilyReportFieldsWithoutCalculatingTotals() {
        val report = FinanceModels.report(JSONObject("""
            {"period":"month","scope":"family","fromDate":"2026-10-01","toDate":"2026-10-01",
             "asOfDate":"2026-10-01","timezone":"Europe/Moscow","currency":"RUB",
             "incomeTotal":"120000.00","expenseTotal":"64000.50","debtPaymentTotal":"3000.00",
             "refundTotal":"250.00","transactionCount":12,"expenseByCategory":{"еда":"15000.25"},
             "expenseByDay":{"2026-10-01":"64000.50","2026-10-02":"0.00"},
             "weekendSharePercent":null,"monthlyBudgetLimit":"50000.00","monthlyBudgetRemaining":"-14000.50",
             "rolling7FoodStatus":{"fromDate":"2026-09-25","toDate":"2026-10-01","limit":"1000.00",
               "spent":"350.25","remaining":"649.75","limitStatus":"normal","usualWeeklySpend":"400.00",
               "historyWeeks":2,"paceStatus":"normal","paceShare":"0.88"},
             "waste":{"available":true,"reasonCode":"available","completeness":"complete",
               "reviewedSpend":"120.00","optionalSpend":"20.00","optionalShare":"0.166667",
               "reviewedItemCount":5,"optionalItemCount":2,"missingAmountCount":0,
               "optionalByDay":{"2026-10-01":"0.00","2026-10-02":"13.00"},
               "bySource":{"model":"13.00","rule":"7.00"},
               "topItems":[{"name":"Сок","amount":"13.00","verdict":"optional","source":"model"}],
               "corrected":[{"productName":"Молоко","count":1,"amount":"30.00"}]}}
        """.trimIndent()))

        assertEquals("family", report.scope)
        assertEquals("64000.50", report.expenseTotal)
        assertEquals("3000.00", report.debtPaymentTotal)
        assertEquals("15000.25", report.expenseByCategory["еда"])
        assertEquals("64000.50", report.expenseByDay["2026-10-01"])
        assertEquals("0.00", report.expenseByDay["2026-10-02"])
        assertNull(report.weekendSharePercent)
        assertEquals("-14000.50", report.monthlyBudgetRemaining)
        assertEquals("20.00", report.waste.optionalSpend)
        assertEquals(mapOf("2026-10-01" to "0.00", "2026-10-02" to "13.00"), report.waste.optionalByDay)
        assertEquals("13.00", report.waste.bySource["model"])
        assertEquals("Сок", report.waste.topItems.single().name)
        assertEquals("Молоко", report.waste.corrected.single().productName)
    }

    @Test fun parsesUnavailableWasteWithoutInventingTotals() {
        val report = FinanceModels.report(JSONObject("""
            {"period":"month","scope":"personal","fromDate":"2026-10-01","toDate":"2026-10-01",
             "asOfDate":"2026-10-01","timezone":"Europe/Moscow","currency":"RUB",
             "incomeTotal":"0.00","expenseTotal":"0.00","debtPaymentTotal":"0.00","refundTotal":"0.00",
             "transactionCount":0,"expenseByCategory":{},"expenseByDay":{},"weekendSharePercent":null,
             "monthlyBudgetLimit":null,"monthlyBudgetRemaining":null,
             "rolling7FoodStatus":{"fromDate":"2026-09-25","toDate":"2026-10-01","limit":"0.00",
               "spent":"0.00","remaining":null,"limitStatus":"disabled","usualWeeklySpend":null,
               "historyWeeks":0,"paceStatus":"insufficient_history","paceShare":null},
             "waste":{"available":false,"reasonCode":"missing_amounts","completeness":"partial",
               "reviewedSpend":null,"optionalSpend":null,"optionalShare":null,"reviewedItemCount":2,
               "optionalItemCount":0,"missingAmountCount":1,"bySource":{},"optionalByDay":{},"topItems":[],"corrected":[]}}
        """.trimIndent()))

        assertEquals(false, report.waste.available)
        assertEquals("missing_amounts", report.waste.reasonCode)
        assertNull(report.waste.optionalSpend)
        assertEquals(1, report.waste.missingAmountCount)
        assertEquals(emptyMap<String, String>(), report.waste.optionalByDay)
    }

    @Test fun parsesBudgetOverviewAndRollingFoodStatusWithoutRecomputingMoney() {
        val budget = FinanceModels.budgetOverview(JSONObject("""
            {
              "currency":"RUB","month":"2026-10",
              "familyLimits":{"food":"20000.00"},"personalOverrides":{"food":"18000.00"},
              "effectiveLimits":{"food":"18000.00"},"monthlySpent":{"food":"1250.35"},
              "limitStatus":{"food":"normal"},"familyVersions":{"food":4},"personalVersions":{"food":2},
              "familyTotalLimit":"50000.00","personalTotalOverride":"45000.00",
              "effectiveTotalLimit":"45000.00","totalMonthlySpent":"1250.35","totalLimitStatus":"normal",
              "familyTotalVersion":3,"personalTotalVersion":1,
              "rolling7FoodLimit":"1000.00","personalRolling7FoodOverride":null,
              "effectiveRolling7FoodLimit":"1000.00","rolling7FoodSpent":"350.25",
              "rolling7FoodLimitStatus":"normal","familyRolling7FoodVersion":1,"personalRolling7FoodVersion":0,
              "rolling7FoodStatus":{"fromDate":"2026-09-25","toDate":"2026-10-01","limit":"1000.00",
                "spent":"350.25","remaining":"649.75","limitStatus":"normal","usualWeeklySpend":null,
                "historyWeeks":0,"paceStatus":"insufficient_history","paceShare":null}
            }
        """.trimIndent()))

        assertEquals("2026-10", budget.month)
        assertEquals("18000.00", budget.effectiveLimits["food"])
        assertEquals(2L, budget.personalVersions["food"])
        assertEquals("350.25", budget.rolling7FoodStatus.spent)
        assertEquals("insufficient_history", budget.rolling7FoodStatus.paceStatus)
        assertNull(budget.rolling7FoodStatus.usualWeeklySpend)
    }

    @Test fun parsesDebtPageAndNullableInterestRate() {
        val debts = FinanceModels.debtPage(JSONObject("""
            {"items":[{"id":"debt-1","tenantId":"tenant-1","name":"Карта",
              "openingBalance":"10000.00","currentBalance":"8400.00","interestRate":null,
              "minimumPayment":"500.00","status":"open","version":3,"createdAt":"2026-10-01T09:00:00Z"}]}
        """.trimIndent()))

        assertEquals(1, debts.size)
        assertEquals("Карта", debts.single().name)
        assertEquals("8400.00", debts.single().currentBalance)
        assertNull(debts.single().interestRate)
        assertEquals(3L, debts.single().version)
    }

    @Test fun parsesShoppingCandidatesAndExactEstimateWithoutInventoryClaim() {
        val shopping = FinanceModels.shoppingList(JSONObject("""
            {"candidates":[{"productName":"Молоко 1 л","productKey":"milk","purchaseCount":3,"medianIntervalDays":10,
              "usualUnitPrice":"100.000000","estimatedCost":"100.00",
              "lastPurchasedAt":"2026-10-04T00:00:00Z","dueAt":"2026-10-05T00:00:00Z","daysUntilDue":0}],
             "estimatedListCost":"100.00","inventoryTracked":false,
             "boughtCandidates":[],"mutedCandidates":[],"blockedCandidates":[]}
        """.trimIndent()))

        assertEquals("Молоко 1 л", shopping.candidates.single().productName)
        assertEquals("milk", shopping.candidates.single().productKey)
        assertEquals(3, shopping.candidates.single().purchaseCount)
        assertEquals("100.00", shopping.estimatedListCost)
        assertEquals(false, shopping.inventoryTracked)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShoppingPayloadThatClaimsInventory() {
        FinanceModels.shoppingList(JSONObject("""
            {"candidates":[],"estimatedListCost":"0.00","inventoryTracked":true,
             "boughtCandidates":[],"mutedCandidates":[],"blockedCandidates":[]}
        """.trimIndent()))
    }

    @Test fun parsesShoppingDecisionSectionsAndBlockReason() {
        val candidate = """{"productName":"Молоко 1 л","productKey":"milk","purchaseCount":3,
            "medianIntervalDays":10,"usualUnitPrice":"100.000000","estimatedCost":"100.00",
            "lastPurchasedAt":"2026-10-04T00:00:00Z","dueAt":"2026-10-05T00:00:00Z","daysUntilDue":0}"""
        val shopping = FinanceModels.shoppingList(JSONObject("""
            {"candidates":[],"estimatedListCost":"0.00","inventoryTracked":false,
             "boughtCandidates":[$candidate],"mutedCandidates":[],
             "blockedCandidates":[{"productKey":"tea","productName":"Чай","reasonCode":"confirmed_not_to_buy"}]}
        """.trimIndent()))

        assertEquals(1, shopping.boughtCandidates.size)
        assertEquals("milk", shopping.boughtCandidates.single().productKey)
        assertEquals("confirmed_not_to_buy", shopping.blockedCandidates.single().reasonCode)
    }

    @Test fun parsesDoNotBuyEvidenceWithoutTreatingModelGuessAsBlock() {
        val report = FinanceModels.doNotBuy(JSONObject("""
            {"available":true,"reasonCode":"available","algorithmVersion":"advice-evidence.v1",
             "inputVersion":"${"0".repeat(64)}",
             "banned":[{"productKey":"chips","productName":"Чипсы","count":2,"amount":"20.00",
                        "missingAmountCount":0,"ruleCount":2,"modelCount":0,"unmarkedCount":0,
                        "modelOnly":false,"latestVerdict":"harmful","latestAdvice":"",
                        "lastPurchasedAt":"2026-10-06T10:00:00Z"}],
             "guesses":[{"productKey":"tea","productName":"Чай","count":2,"amount":null,
                         "missingAmountCount":2,"ruleCount":0,"modelCount":2,"unmarkedCount":0,
                         "modelOnly":true,"latestVerdict":"unnecessary","latestAdvice":"Проверьте",
                         "lastPurchasedAt":"2026-10-06T10:00:00Z"}]}
        """.trimIndent()))
        assertEquals("chips", report.banned.single().productKey)
        assertEquals("tea", report.guesses.single().productKey)
        assertNull(report.guesses.single().amount)
        assertEquals("Проверьте", report.guesses.single().latestAdvice)
    }

    @Test fun shoppingAcceptsRuleBackedBlockedReason() {
        val shopping = FinanceModels.shoppingList(JSONObject("""
            {"candidates":[],"estimatedListCost":"0.00","inventoryTracked":false,
             "boughtCandidates":[],"mutedCandidates":[],
             "blockedCandidates":[{"productKey":"chips","productName":"Чипсы",
                                   "reasonCode":"rule_backed_not_to_buy"}]}
        """.trimIndent()))
        assertEquals("rule_backed_not_to_buy", shopping.blockedCandidates.single().reasonCode)
    }

    @Test fun parsesUnmarkedAdviceSourceAsReviewOnlyGuess() {
        val report = FinanceModels.doNotBuy(JSONObject("""
            {"available":true,"reasonCode":"available","algorithmVersion":"advice-evidence.v1",
             "inputVersion":"${"0".repeat(64)}","banned":[],
             "guesses":[{"productKey":"tea","productName":"Чай","count":3,"amount":null,
                         "missingAmountCount":1,"ruleCount":0,"modelCount":2,"unmarkedCount":1,
                         "modelOnly":false,"latestVerdict":"unnecessary","latestAdvice":"",
                         "lastPurchasedAt":"2026-10-06T10:00:00Z"}]}
        """.trimIndent()))
        assertEquals(false, report.guesses.single().modelOnly)
    }

    @Test fun parsesPersonalInflationWithoutRecomputingCoreTotals() {
        val inflation = FinanceModels.personalInflation(JSONObject("""
            {"available":true,"reasonCode":"available","asOf":"2026-10-06T12:00:00Z","windowDays":90,
             "productCount":3,"basketBefore":"1250.00","basketNow":"1275.00","indexPercent":"2.00",
             "rising":[{"productName":"Кофе","oldUnitPrice":"100.00","newUnitPrice":"110.00",
               "oldSpendWeight":"500.00","changePercent":"10.00","olderPurchaseCount":2,"windowPurchaseCount":1}],
             "falling":[{"productName":"Молоко","oldUnitPrice":"200.00","newUnitPrice":"180.00",
               "oldSpendWeight":"700.00","changePercent":"-10.00","olderPurchaseCount":3,"windowPurchaseCount":2}]}
        """.trimIndent()))

        assertEquals(true, inflation.available)
        assertEquals("1250.00", inflation.basketBefore)
        assertEquals("1275.00", inflation.basketNow)
        assertEquals("2.00", inflation.indexPercent)
        assertEquals("Кофе", inflation.rising.single().productName)
        assertEquals("Молоко", inflation.falling.single().productName)
        assertEquals(2, inflation.rising.single().olderPurchaseCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInventedTotalsForInsufficientPersonalInflationHistory() {
        FinanceModels.personalInflation(JSONObject("""
            {"available":false,"reasonCode":"insufficient_history","asOf":"2026-10-06T12:00:00Z",
             "windowDays":90,"productCount":0,"basketBefore":"0.00","basketNow":null,"indexPercent":null,
             "rising":[],"falling":[]}
        """.trimIndent()))
    }

    @Test fun parsesRecurringProjectionAndSeparatesDueAndOverdue() {
        val due = """{"id":"11111111111111111111111111111111","key":"rent","name":"Аренда","category":"housing","type":"expense","currency":"RUB","amount":"100.00","minAmount":"95.00","maxAmount":"105.00","periodCode":"week","periodDays":7,"minIntervalDays":7,"maxIntervalDays":7,"occurrences":3,"lastDate":"2026-10-02","nextDate":"2026-10-09","daysUntil":3}"""
        val overdue = """{"id":"22222222222222222222222222222222","key":"service","name":"Сервис","category":"bills","type":"expense","currency":"RUB","amount":"500.00","minAmount":"475.00","maxAmount":"525.00","periodCode":"week","periodDays":7,"minIntervalDays":7,"maxIntervalDays":7,"occurrences":4,"lastDate":"2026-09-28","nextDate":"2026-10-05","daysUntil":-1}"""
        val income = """{"id":"33333333333333333333333333333333","key":"salary","name":"Зарплата","category":null,"type":"income","currency":"RUB","amount":"120000.00","minAmount":"118000.00","maxAmount":"122000.00","periodCode":"month","periodDays":30,"minIntervalDays":29,"maxIntervalDays":31,"occurrences":3,"lastDate":"2026-09-10","nextDate":"2026-10-10","daysUntil":4}"""
        val muted = """{"id":"44444444444444444444444444444444","key":"cloud backup","name":"Облако","category":"services","type":"expense","currency":"RUB","amount":"900.00","minAmount":"900.00","maxAmount":"900.00","periodCode":"month","periodDays":30,"minIntervalDays":30,"maxIntervalDays":30,"occurrences":3,"lastDate":"2026-09-06","nextDate":"2026-10-06","daysUntil":0}"""
        val projection = FinanceModels.recurringProjection(JSONObject("""
            {"algorithmVersion":"recurring.v1","completeness":"complete","timeZone":"Europe/Moscow","asOf":"2026-10-05T21:00:00Z",
             "expenseSeries":[$due,$overdue],"incomeSeries":[$income],"dueSoon":[$due],"overdue":[$overdue],
             "nextIncome":$income,"monthlyExpenseEstimate":"2571.43","monthlyExpenseEstimates":{"RUB":"2571.43"},
             "mutedSeries":[$muted]}
        """.trimIndent()))

        assertEquals("Europe/Moscow", projection.timeZone)
        assertEquals(2, projection.expenseSeries.size)
        assertEquals("rent", projection.dueSoon.single().key)
        assertEquals("service", projection.overdue.single().key)
        assertEquals("salary", projection.nextIncome?.key)
        assertEquals("2571.43", projection.monthlyExpenseEstimate)
        assertEquals("Облако", projection.mutedSeries.single().name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverdueExpenseInDueSoonGroup() {
        val overdue = """{"id":"22222222222222222222222222222222","key":"service","name":"Сервис","category":"bills","type":"expense","currency":"RUB","amount":"500.00","minAmount":"475.00","maxAmount":"525.00","periodCode":"week","periodDays":7,"minIntervalDays":7,"maxIntervalDays":7,"occurrences":4,"lastDate":"2026-09-28","nextDate":"2026-10-05","daysUntil":-1}"""
        FinanceModels.recurringProjection(JSONObject("""
            {"algorithmVersion":"recurring.v1","completeness":"complete","timeZone":"Europe/Moscow","asOf":"2026-10-05T21:00:00Z",
             "expenseSeries":[$overdue],"incomeSeries":[],"dueSoon":[$overdue],"overdue":[$overdue],
             "nextIncome":null,"monthlyExpenseEstimate":"2142.86","monthlyExpenseEstimates":{"RUB":"2142.86"},
             "mutedSeries":[]}
        """.trimIndent()))
    }
}
