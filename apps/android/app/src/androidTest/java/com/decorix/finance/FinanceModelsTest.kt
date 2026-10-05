package com.decorix.finance

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceModelsTest {
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

        assertEquals("22000.00", summary.safeToSpend?.safeTotal)
        assertEquals("733.33", summary.safeToSpend?.safePerDay)
        assertEquals("actual_income", summary.safeToSpend?.incomeBasis)
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
               "historyWeeks":2,"paceStatus":"normal","paceShare":"0.88"}}
        """.trimIndent()))

        assertEquals("family", report.scope)
        assertEquals("64000.50", report.expenseTotal)
        assertEquals("3000.00", report.debtPaymentTotal)
        assertEquals("15000.25", report.expenseByCategory["еда"])
        assertEquals("64000.50", report.expenseByDay["2026-10-01"])
        assertEquals("0.00", report.expenseByDay["2026-10-02"])
        assertNull(report.weekendSharePercent)
        assertEquals("-14000.50", report.monthlyBudgetRemaining)
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
}
