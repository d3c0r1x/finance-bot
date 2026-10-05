package com.decorix.finance

import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId

data class RollingFoodStatus(
    val fromDate: String,
    val toDate: String,
    val limit: String,
    val spent: String,
    val remaining: String?,
    val limitStatus: String,
    val usualWeeklySpend: String?,
    val historyWeeks: Int,
    val paceStatus: String,
    val paceShare: String?,
)

data class BudgetOverview(
    val month: String,
    val familyLimits: Map<String, String>,
    val personalOverrides: Map<String, String>,
    val effectiveLimits: Map<String, String>,
    val monthlySpent: Map<String, String>,
    val limitStatus: Map<String, String>,
    val familyVersions: Map<String, Long>,
    val personalVersions: Map<String, Long>,
    val familyTotalLimit: String,
    val personalTotalOverride: String?,
    val effectiveTotalLimit: String,
    val totalMonthlySpent: String,
    val totalLimitStatus: String,
    val familyTotalVersion: Long,
    val personalTotalVersion: Long,
    val rolling7FoodLimit: String,
    val personalRolling7FoodOverride: String?,
    val effectiveRolling7FoodLimit: String,
    val rolling7FoodSpent: String,
    val rolling7FoodLimitStatus: String,
    val familyRolling7FoodVersion: Long,
    val personalRolling7FoodVersion: Long,
    val rolling7FoodStatus: RollingFoodStatus,
)

data class BudgetProposal(
    val id: String,
    val monthlyIncome: String,
    val totalLimit: String,
    val limits: Map<String, String>,
    val status: String,
    val proposalSource: String,
    val historyDays: Int,
    val modelVersion: String?,
)

data class FinanceDebt(
    val id: String,
    val tenantId: String,
    val name: String,
    val openingBalance: String,
    val currentBalance: String,
    val interestRate: String?,
    val minimumPayment: String,
    val status: String,
    val version: Long,
)

data class DebtForecast(val monthsToPayoff: Int?, val estimateBasis: String)

data class DashboardSummary(
    val month: String,
    val incomeTotal: String,
    val expenseTotal: String,
    val transactionCount: Int,
    val asOfDate: String,
    val daysElapsed: Int,
    val daysInMonth: Int,
    val daysRemaining: Int,
    val dailyExpensePace: String?,
    val projectedExpenseTotal: String?,
    val rolling7FoodStatus: RollingFoodStatus,
    val safeToSpend: SafeToSpend? = null,
)

data class SafeToSpend(
    val incomeBasis: String,
    val incomeBase: String,
    val month: String,
    val horizonDate: String,
    val daysRemaining: Int,
    val monthlyExpenses: String,
    val reserve: String,
    val promisedPayments: String,
    val safeTotal: String,
    val safePerDay: String,
)

data class FinanceReport(
    val period: String,
    val scope: String,
    val fromDate: String,
    val toDate: String,
    val asOfDate: String,
    val timezone: String,
    val currency: String,
    val incomeTotal: String,
    val expenseTotal: String,
    val debtPaymentTotal: String,
    val refundTotal: String,
    val transactionCount: Int,
    val expenseByCategory: Map<String, String>,
    val weekendSharePercent: Int?,
    val monthlyBudgetLimit: String?,
    val monthlyBudgetRemaining: String?,
    val rolling7FoodStatus: RollingFoodStatus,
    val expenseByDay: Map<String, String> = emptyMap(),
)

data class FinanceShoppingCandidate(
    val productName: String,
    val productKey: String,
    val purchaseCount: Int,
    val medianIntervalDays: Int,
    val usualUnitPrice: String,
    val estimatedCost: String,
    val lastPurchasedAt: String,
    val dueAt: String,
    val daysUntilDue: Int,
)

data class FinanceShoppingList(
    val candidates: List<FinanceShoppingCandidate>,
    val estimatedListCost: String,
    val inventoryTracked: Boolean,
    val boughtCandidates: List<FinanceShoppingCandidate> = emptyList(),
    val mutedCandidates: List<FinanceShoppingCandidate> = emptyList(),
    val blockedCandidates: List<FinanceBlockedShoppingCandidate> = emptyList(),
)

data class FinanceBlockedShoppingCandidate(val productKey: String, val productName: String, val reasonCode: String)

data class FinancePersonalInflationItem(
    val productName: String,
    val oldUnitPrice: String,
    val newUnitPrice: String,
    val oldSpendWeight: String,
    val changePercent: String,
    val olderPurchaseCount: Int,
    val windowPurchaseCount: Int,
)

data class FinancePersonalInflation(
    val available: Boolean,
    val reasonCode: String,
    val asOf: String,
    val windowDays: Int,
    val productCount: Int,
    val basketBefore: String?,
    val basketNow: String?,
    val indexPercent: String?,
    val rising: List<FinancePersonalInflationItem>,
    val falling: List<FinancePersonalInflationItem>,
)

data class FinanceTransactionDraft(
    val id: String,
    val tenantId: String,
    val type: String,
    val amount: String,
    val currency: String,
    val categoryCode: String,
    val subcategoryCode: String?,
    val description: String,
    val occurredAt: String,
    val debtId: String?,
    val state: String,
    val version: Long,
    val provider: String,
    val modelVersion: String,
    val promptVersion: String,
)

data class FinanceMemberProfile(
    val displayName: String,
    val plannedIncome: String?,
    val onboardingState: String,
    val timezone: String,
    val currency: String,
)

data class FinanceNotificationPreferences(
    val timezone: String,
    val telegramLinked: Boolean,
    val language: String,
    val dailyEnabled: Boolean,
    val dailyLocalTime: String,
    val weeklyEnabled: Boolean,
    val weeklyDayOfWeek: Int,
    val weeklyLocalTime: String,
    val quietHoursStart: String?,
    val quietHoursEnd: String?,
    val version: Long,
)

data class FinanceTelegramLinkCode(val code: String, val expiresAt: String)

data class FinanceBudgetAlert(val budgetKey: String, val threshold: String, val limit: String, val spent: String)

data class TransactionDraftEdit(
    val id: String,
    val version: Long,
    val type: String,
    val amount: String,
    val categoryCode: String,
    val subcategoryCode: String?,
    val description: String,
    val occurredAt: String,
    val debtId: String?,
)

internal object FinanceModels {
    fun budgetAlerts(json: JSONArray?): List<FinanceBudgetAlert> {
        val alerts = json ?: JSONArray()
        require(alerts.length() <= 2) { "Invalid budget alert count" }
        val money = Regex("^(?:0|[1-9]\\d{0,17})\\.\\d{2}$")
        return (0 until alerts.length()).map { index ->
            val item = alerts.getJSONObject(index)
            val key = item.getString("budgetKey")
            val threshold = item.getString("threshold")
            val limit = item.getString("limit")
            val spent = item.getString("spent")
            require(key.isNotBlank() && key.length <= 64 && threshold in setOf("near", "exceeded")
                && money.matches(limit) && limit != "0.00" && money.matches(spent)) { "Invalid budget alert" }
            FinanceBudgetAlert(key, threshold, limit, spent)
        }
    }

    fun telegramLinkCode(json: JSONObject) = FinanceTelegramLinkCode(
        code = json.getString("code").also { require(Regex("^[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){3}$").matches(it)) {
            "Invalid Telegram link code"
        } },
        expiresAt = json.getString("expiresAt").also { Instant.parse(it) },
    )

    fun memberProfile(json: JSONObject) = FinanceMemberProfile(
        displayName = json.getString("displayName"),
        plannedIncome = if (!json.has("plannedIncome") || json.isNull("plannedIncome")) null else
            json.get("plannedIncome").toString().toBigDecimalOrNull()?.setScale(2, java.math.RoundingMode.HALF_UP)
                ?.toPlainString() ?: throw IllegalArgumentException("Invalid planned income in profile"),
        onboardingState = json.getString("onboardingState"),
        timezone = json.getString("timezone"),
        currency = json.getString("currency"),
    )

    fun notificationPreferences(json: JSONObject): FinanceNotificationPreferences {
        val result = FinanceNotificationPreferences(
            timezone = json.getString("timezone"),
            telegramLinked = json.getBoolean("telegramLinked"),
            language = json.getString("language"),
            dailyEnabled = json.getBoolean("dailyEnabled"),
            dailyLocalTime = json.getString("dailyLocalTime"),
            weeklyEnabled = json.getBoolean("weeklyEnabled"),
            weeklyDayOfWeek = json.getInt("weeklyDayOfWeek"),
            weeklyLocalTime = json.getString("weeklyLocalTime"),
            quietHoursStart = nullableString(json, "quietHoursStart"),
            quietHoursEnd = nullableString(json, "quietHoursEnd"),
            version = json.getLong("version"),
        )
        val time = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
        require(result.timezone.isNotBlank() && runCatching { ZoneId.of(result.timezone) }.isSuccess &&
            result.language in setOf("ru", "en") && time.matches(result.dailyLocalTime) &&
            time.matches(result.weeklyLocalTime) && result.weeklyDayOfWeek in 1..7 && result.version >= 0 &&
            (result.quietHoursStart == null) == (result.quietHoursEnd == null) &&
            (result.quietHoursStart == null || time.matches(result.quietHoursStart)) &&
            (result.quietHoursEnd == null || time.matches(result.quietHoursEnd))) {
            "Invalid notification preferences"
        }
        return result
    }

    fun transactionDraft(json: JSONObject) = FinanceTransactionDraft(
        id = json.getString("id"),
        tenantId = json.getString("tenantId"),
        type = json.getString("type"),
        amount = json.getString("amount"),
        currency = json.getString("currency"),
        categoryCode = json.getString("categoryCode"),
        subcategoryCode = nullableString(json, "subcategoryCode"),
        description = json.getString("description"),
        occurredAt = json.getString("occurredAt"),
        debtId = nullableString(json, "debtId"),
        state = json.getString("state"),
        version = json.getLong("version"),
        provider = json.getString("provider"),
        modelVersion = json.getString("modelVersion"),
        promptVersion = json.getString("promptVersion"),
    )

    fun budgetOverview(json: JSONObject): BudgetOverview {
        return BudgetOverview(
            month = json.getString("month"),
            familyLimits = stringMap(json, "familyLimits"),
            personalOverrides = stringMap(json, "personalOverrides"),
            effectiveLimits = stringMap(json, "effectiveLimits"),
            monthlySpent = stringMap(json, "monthlySpent"),
            limitStatus = stringMap(json, "limitStatus"),
            familyVersions = longMap(json, "familyVersions"),
            personalVersions = longMap(json, "personalVersions"),
            familyTotalLimit = json.getString("familyTotalLimit"),
            personalTotalOverride = nullableString(json, "personalTotalOverride"),
            effectiveTotalLimit = json.getString("effectiveTotalLimit"),
            totalMonthlySpent = json.getString("totalMonthlySpent"),
            totalLimitStatus = json.getString("totalLimitStatus"),
            familyTotalVersion = json.getLong("familyTotalVersion"),
            personalTotalVersion = json.getLong("personalTotalVersion"),
            rolling7FoodLimit = json.getString("rolling7FoodLimit"),
            personalRolling7FoodOverride = nullableString(json, "personalRolling7FoodOverride"),
            effectiveRolling7FoodLimit = json.getString("effectiveRolling7FoodLimit"),
            rolling7FoodSpent = json.getString("rolling7FoodSpent"),
            rolling7FoodLimitStatus = json.getString("rolling7FoodLimitStatus"),
            familyRolling7FoodVersion = json.getLong("familyRolling7FoodVersion"),
            personalRolling7FoodVersion = json.getLong("personalRolling7FoodVersion"),
            rolling7FoodStatus = rollingFoodStatus(json),
        )
    }

    fun dashboardSummary(json: JSONObject) = DashboardSummary(
        month = json.getString("month"),
        incomeTotal = json.getString("incomeTotal"),
        expenseTotal = json.getString("expenseTotal"),
        transactionCount = json.getInt("transactionCount"),
        asOfDate = json.getString("asOfDate"),
        daysElapsed = json.getInt("daysElapsed"),
        daysInMonth = json.getInt("daysInMonth"),
        daysRemaining = json.getInt("daysRemaining"),
        dailyExpensePace = nullableString(json, "dailyExpensePace"),
        projectedExpenseTotal = nullableString(json, "projectedExpenseTotal"),
        rolling7FoodStatus = rollingFoodStatus(json),
        safeToSpend = json.optJSONObject("safeToSpend")?.let { safeToSpend(it) },
    )

    private fun safeToSpend(json: JSONObject) = SafeToSpend(
        incomeBasis = json.getString("incomeBasis"),
        incomeBase = json.getString("incomeBase"),
        month = json.getString("month"),
        horizonDate = json.getString("horizonDate"),
        daysRemaining = json.getInt("daysRemaining"),
        monthlyExpenses = json.getString("monthlyExpenses"),
        reserve = json.getString("reserve"),
        promisedPayments = json.getString("promisedPayments"),
        safeTotal = json.getString("safeTotal"),
        safePerDay = json.getString("safePerDay"),
    )

    fun report(json: JSONObject) = FinanceReport(
        period = json.getString("period"),
        scope = json.getString("scope"),
        fromDate = json.getString("fromDate"),
        toDate = json.getString("toDate"),
        asOfDate = json.getString("asOfDate"),
        timezone = json.getString("timezone"),
        currency = json.getString("currency"),
        incomeTotal = json.getString("incomeTotal"),
        expenseTotal = json.getString("expenseTotal"),
        debtPaymentTotal = json.getString("debtPaymentTotal"),
        refundTotal = json.getString("refundTotal"),
        transactionCount = json.getInt("transactionCount"),
        expenseByCategory = stringMap(json, "expenseByCategory"),
        weekendSharePercent = nullableInt(json, "weekendSharePercent"),
        monthlyBudgetLimit = nullableString(json, "monthlyBudgetLimit"),
        monthlyBudgetRemaining = nullableString(json, "monthlyBudgetRemaining"),
        rolling7FoodStatus = rollingFoodStatus(json),
        expenseByDay = stringMap(json, "expenseByDay"),
    )

    fun shoppingList(json: JSONObject): FinanceShoppingList {
        val moneyPattern = Regex("^(?:0|[1-9]\\d{0,21})\\.\\d{2}$")
        val unitPricePattern = Regex("^(?:0|[1-9]\\d{0,29})\\.\\d{6}$")
        val productKeyPattern = Regex("^[a-zа-я0-9]{1,256}$")
        val totalRaw = json.getString("estimatedListCost")
        require(moneyPattern.matches(totalRaw) && !json.getBoolean("inventoryTracked")) {
            "Invalid shopping response"
        }
        val keys = mutableSetOf<String>()
        fun parseCandidates(field: String): List<FinanceShoppingCandidate> {
            val items = json.getJSONArray(field)
            return (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val name = item.getString("productName")
                val productKey = item.getString("productKey")
                val purchaseCount = exactInt(item, "purchaseCount")
                val interval = exactInt(item, "medianIntervalDays")
                val daysUntilDue = exactInt(item, "daysUntilDue")
                val usualRaw = item.getString("usualUnitPrice")
                val costRaw = item.getString("estimatedCost")
                val lastPurchasedAt = item.getString("lastPurchasedAt")
                val dueAt = item.getString("dueAt")
                val usual = usualRaw.toBigDecimalOrNull()
                val cost = costRaw.toBigDecimalOrNull()
                require(name.isNotBlank() && name.length <= 200 && productKeyPattern.matches(productKey)
                    && keys.add(productKey) && purchaseCount in 3..5000
                    && interval in 3..3650 && daysUntilDue in -7300..3
                    && unitPricePattern.matches(usualRaw) && usual != null && usual.signum() > 0
                    && moneyPattern.matches(costRaw) && cost != null && cost.signum() >= 0
                    && usual.setScale(2, java.math.RoundingMode.HALF_EVEN) == cost
                    && Instant.parse(dueAt).isAfter(Instant.parse(lastPurchasedAt))) {
                    "Invalid shopping candidate"
                }
                FinanceShoppingCandidate(name, productKey, purchaseCount, interval, usualRaw, costRaw,
                    lastPurchasedAt, dueAt, daysUntilDue)
            }
        }
        val candidates = parseCandidates("candidates")
        val bought = parseCandidates("boughtCandidates")
        val muted = parseCandidates("mutedCandidates")
        val blockedJson = json.getJSONArray("blockedCandidates")
        val blocked = (0 until blockedJson.length()).map { index ->
            val item = blockedJson.getJSONObject(index)
            val productKey = item.getString("productKey")
            val name = item.getString("productName")
            val reason = item.getString("reasonCode")
            require(productKeyPattern.matches(productKey) && keys.add(productKey)
                && name.isNotBlank() && name.length <= 200 && reason == "confirmed_not_to_buy") {
                "Invalid blocked shopping candidate"
            }
            FinanceBlockedShoppingCandidate(productKey, name, reason)
        }
        require(candidates.size + bought.size + muted.size + blocked.size <= 10) {
            "Invalid shopping candidate count"
        }
        val estimatedTotal = candidates.fold(java.math.BigDecimal("0.00")) { total, candidate ->
            total.add(candidate.estimatedCost.toBigDecimal())
        }
        require(estimatedTotal.compareTo(totalRaw.toBigDecimal()) == 0) { "Shopping estimate does not match candidates" }
        return FinanceShoppingList(candidates, totalRaw, false, bought, muted, blocked)
    }

    fun personalInflation(json: JSONObject): FinancePersonalInflation {
        val moneyPattern = Regex("^(?:0|[1-9]\\d{0,29})\\.\\d{2}$")
        val signedPattern = Regex("^-?(?:0|[1-9]\\d{0,29})\\.\\d{2}$")
        val available = json.getBoolean("available")
        val reason = json.getString("reasonCode")
        val asOf = json.getString("asOf")
        val windowDays = exactInt(json, "windowDays")
        val productCount = exactInt(json, "productCount")
        val before = nullableString(json, "basketBefore")
        val now = nullableString(json, "basketNow")
        val index = nullableString(json, "indexPercent")
        val risingJson = json.getJSONArray("rising")
        val fallingJson = json.getJSONArray("falling")
        requireNotNull(runCatching { Instant.parse(asOf) }.getOrNull()) { "Invalid personal inflation date" }
        require(windowDays == 90 && productCount in 0..5000
            && risingJson.length() <= 3 && fallingJson.length() <= 3) { "Invalid personal inflation window" }

        fun decimal(raw: String, pattern: Regex, positive: Boolean): java.math.BigDecimal {
            require(pattern.matches(raw)) { "Invalid personal inflation decimal" }
            val amount = raw.toBigDecimalOrNull() ?: throw IllegalArgumentException("Invalid personal inflation decimal")
            require(amount.signum() != 0 || !positive) { "Invalid personal inflation amount" }
            require(!positive || amount.signum() > 0) { "Invalid personal inflation amount" }
            return amount
        }

        if (!available) {
            require(reason == "insufficient_history" && productCount == 0 && before == null && now == null
                && index == null && risingJson.length() == 0 && fallingJson.length() == 0) {
                "Unavailable personal inflation must not contain totals"
            }
            return FinancePersonalInflation(false, reason, asOf, windowDays, productCount, null, null, null,
                emptyList(), emptyList())
        }

        require(reason == "available" && productCount >= 3 && before != null && now != null && index != null) {
            "Available personal inflation requires totals and three products"
        }
        decimal(before, moneyPattern, positive = true)
        decimal(now, moneyPattern, positive = true)
        require(decimal(index, signedPattern, positive = false) > java.math.BigDecimal("-100.00")) {
            "Invalid personal inflation index"
        }
        val names = mutableSetOf<String>()
        fun parseItems(items: JSONArray, isRising: Boolean): List<FinancePersonalInflationItem> =
            (0 until items.length()).map { position ->
                val item = items.getJSONObject(position)
                val name = item.getString("productName")
                val oldPrice = item.getString("oldUnitPrice")
                val newPrice = item.getString("newUnitPrice")
                val weight = item.getString("oldSpendWeight")
                val change = item.getString("changePercent")
                val older = exactInt(item, "olderPurchaseCount")
                val inWindow = exactInt(item, "windowPurchaseCount")
                val changeAmount = decimal(change, signedPattern, positive = false)
                require(name.isNotBlank() && name.length <= 200 && names.add(name.lowercase(java.util.Locale.ROOT))
                    && older in 2..5000 && inWindow in 1..5000
                    && (if (isRising) changeAmount.signum() > 0 else changeAmount.signum() < 0)) {
                    "Invalid personal inflation item"
                }
                decimal(oldPrice, moneyPattern, positive = true)
                decimal(newPrice, moneyPattern, positive = true)
                decimal(weight, moneyPattern, positive = true)
                FinancePersonalInflationItem(name, oldPrice, newPrice, weight, change, older, inWindow)
            }
        return FinancePersonalInflation(true, reason, asOf, windowDays, productCount, before, now, index,
            parseItems(risingJson, isRising = true), parseItems(fallingJson, isRising = false))
    }

    fun budgetProposal(json: JSONObject) = BudgetProposal(
        id = json.getString("id"),
        monthlyIncome = json.getString("monthlyIncome"),
        totalLimit = json.getString("totalLimit"),
        limits = stringMap(json, "limits"),
        status = json.getString("status"),
        proposalSource = json.getString("proposalSource"),
        historyDays = json.getInt("historyDays"),
        modelVersion = nullableString(json, "modelVersion"),
    )

    fun debtPage(json: JSONObject): List<FinanceDebt> {
        val items = json.getJSONArray("items")
        return (0 until items.length()).map { index -> debt(items.getJSONObject(index)) }
    }

    fun debt(json: JSONObject) = FinanceDebt(
        id = json.getString("id"),
        tenantId = json.getString("tenantId"),
        name = json.getString("name"),
        openingBalance = json.getString("openingBalance"),
        currentBalance = json.getString("currentBalance"),
        interestRate = nullableString(json, "interestRate"),
        minimumPayment = json.getString("minimumPayment"),
        status = json.getString("status"),
        version = json.getLong("version"),
    )

    fun debtForecast(json: JSONObject) = DebtForecast(
        monthsToPayoff = if (json.isNull("monthsToPayoff")) null else json.getInt("monthsToPayoff"),
        estimateBasis = json.getString("estimateBasis"),
    )

    private fun stringMap(json: JSONObject, key: String): Map<String, String> =
        json.getJSONObject(key).let { values ->
            values.keys().asSequence().associateWith { entry -> values.getString(entry) }
        }

    private fun exactInt(json: JSONObject, key: String): Int {
        val value = json.get(key) as? Number ?: throw IllegalArgumentException("Invalid shopping integer")
        return java.math.BigDecimal(value.toString()).intValueExact()
    }

    private fun longMap(json: JSONObject, key: String): Map<String, Long> =
        json.getJSONObject(key).let { values ->
            values.keys().asSequence().associateWith { entry -> values.getLong(entry) }
        }

    private fun rollingFoodStatus(json: JSONObject): RollingFoodStatus = json.getJSONObject("rolling7FoodStatus").let { food ->
        RollingFoodStatus(
            fromDate = food.getString("fromDate"),
            toDate = food.getString("toDate"),
            limit = food.getString("limit"),
            spent = food.getString("spent"),
            remaining = nullableString(food, "remaining"),
            limitStatus = food.getString("limitStatus"),
            usualWeeklySpend = nullableString(food, "usualWeeklySpend"),
            historyWeeks = food.getInt("historyWeeks"),
            paceStatus = food.getString("paceStatus"),
            paceShare = nullableString(food, "paceShare"),
        )
    }

    private fun nullableString(json: JSONObject, key: String): String? =
        if (!json.has(key) || json.isNull(key)) null else json.getString(key)

    private fun nullableInt(json: JSONObject, key: String): Int? =
        if (!json.has(key) || json.isNull(key)) null else json.getInt(key)
}
