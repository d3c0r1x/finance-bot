package com.decorix.finance

import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.math.BigDecimal
import java.math.RoundingMode

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
    val waste: FinanceWasteReport = FinanceWasteReport(false, "analytics_unavailable", "partial",
        null, null, null, 0, 0, 0, emptyMap(), emptyList(), emptyList()),
)

data class FinanceWasteReport(
    val available: Boolean,
    val reasonCode: String,
    val completeness: String,
    val reviewedSpend: String?,
    val optionalSpend: String?,
    val optionalShare: String?,
    val reviewedItemCount: Int,
    val optionalItemCount: Int,
    val missingAmountCount: Int,
    val bySource: Map<String, String>,
    val topItems: List<FinanceWasteItem>,
    val corrected: List<FinanceWasteCorrection>,
    val optionalByDay: Map<String, String> = emptyMap(),
)

data class FinanceWasteItem(val name: String, val amount: String, val verdict: String, val source: String)
data class FinanceWasteCorrection(val productName: String, val count: Int, val amount: String)

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

data class FinanceAdviceGroup(
    val productKey: String, val productName: String, val count: Int, val amount: String?,
    val missingAmountCount: Int, val ruleCount: Int, val modelCount: Int, val unmarkedCount: Int,
    val modelOnly: Boolean, val latestVerdict: String, val latestAdvice: String, val lastPurchasedAt: String,
)

data class FinanceDoNotBuy(
    val available: Boolean, val reasonCode: String, val banned: List<FinanceAdviceGroup>,
    val guesses: List<FinanceAdviceGroup>,
)

data class FinanceProductDecisions(val productKeys: List<String>, val confirmedProductKeys: List<String>)

data class FinanceReceiptRecalculationImpact(
    val algorithmVersion: String,
    val inputVersion: String,
    val reasonCode: String,
    val completeness: String,
    val optionalSpendBefore: String?,
    val optionalSpendAfter: String?,
    val optionalSpendDelta: String?,
    val currency: String?,
)

data class FinanceReceiptRecalculationChange(
    val itemId: String,
    val name: String,
    val lineSum: String?,
    val itemVersion: Long,
    val beforeVerdict: String?,
    val beforeReason: String?,
    val beforeAction: String?,
    val beforeSource: String?,
    val afterVerdict: String?,
    val afterReason: String?,
    val afterAction: String?,
    val afterSource: String?,
    val changed: Boolean,
)

data class FinanceReceiptRecalculationPreview(
    val runId: String,
    val algorithmVersion: String,
    val state: String,
    val checked: Int,
    val updateCount: Int,
    val changedCount: Int,
    val impact: FinanceReceiptRecalculationImpact?,
    val changes: List<FinanceReceiptRecalculationChange>,
)

data class FinanceReceiptRecalculationApplyResult(
    val runId: String,
    val algorithmVersion: String,
    val state: String,
    val appliedCount: Int,
    val changedCount: Int,
    val impact: FinanceReceiptRecalculationImpact?,
    val changes: List<FinanceReceiptRecalculationChange>,
)

data class FinanceReceiptRecalculationRunSummary(
    val runId: String,
    val algorithmVersion: String,
    val state: String,
    val checked: Int,
    val updateCount: Int,
    val changedCount: Int,
    val createdAt: String,
    val appliedAt: String?,
    val impact: FinanceReceiptRecalculationImpact?,
)

data class FinanceReceiptRecalculationHistoryPage(
    val runs: List<FinanceReceiptRecalculationRunSummary>,
    val nextCursor: String?,
)

data class FinanceReceiptRecalculationDetail(
    val run: FinanceReceiptRecalculationRunSummary,
    val changes: List<FinanceReceiptRecalculationChange>,
    val nextCursor: String?,
)

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

data class FinanceAdviceAnalyticsJob(
    val id: String?,
    val state: String?,
    val inputWatermark: String?,
    val algorithmVersion: String?,
    val completeness: String?,
    val errorCode: String?,
    val report: FinanceAdviceAnalyticsReport?,
    val updatedAt: String,
)

data class FinanceAdviceAnalyticsReport(
    val algorithmVersion: String,
    val inputWatermark: String,
    val completeness: String,
    val reasonCode: String,
    val savings: FinanceAdviceSavings,
    val trend: FinanceAdviceTrend,
    val effects: FinanceAdviceEffects,
    val recalculation: FinanceAdviceRecalculation,
)

data class FinanceAdviceSavings(
    val available: Boolean,
    val reasonCode: String,
    val label: String,
    val days: Int,
    val monthlyCeiling: String?,
    val shareOfIncome: String?,
    val shareOfLimit: String?,
    val groups: List<FinanceAdviceSavingsGroup>,
)

data class FinanceAdviceSavingsGroup(
    val productKey: String,
    val name: String,
    val count: Int,
    val spend: String,
    val monthlyCeiling: String?,
)

data class FinanceAdviceTrend(
    val available: Boolean,
    val reasonCode: String,
    val delta: String?,
    val direction: String,
    val weeks: List<FinanceAdviceWeek>,
)

data class FinanceAdviceWeek(
    val start: String,
    val end: String,
    val spend: String,
    val optionalSpend: String,
    val optionalShare: String,
    val itemCount: Int,
    val recalculated: Boolean,
)

data class FinanceAdviceEffects(
    val effects: List<FinanceAdviceEffect>,
    val pending: List<FinanceAdvicePendingEffect>,
    val causalityClaim: Boolean,
)

data class FinanceAdviceEffect(
    val productKey: String,
    val name: String,
    val advice: String,
    val beforeCount: Int,
    val afterCount: Int,
    val daysBefore: Int,
    val daysAfter: Int,
    val intervalBefore: String,
    val intervalAfter: String?,
    val change: String,
    val direction: String,
    val afterSpend: String?,
)

data class FinanceAdvicePendingEffect(
    val productKey: String,
    val name: String,
    val daysAfter: Int,
    val daysLeft: Int,
)

data class FinanceAdviceRecalculation(
    val available: Boolean,
    val changedItemCount: Int,
    val optionalSpendDelta: String?,
    val windows: List<FinanceAdviceRecalculationWindow>,
)

data class FinanceAdviceRecalculationWindow(
    val start: String,
    val end: String,
    val changedItemCount: Int,
    val optionalSpendDelta: String?,
)

data class FinanceRecurringSeries(
    val id: String, val key: String, val name: String, val category: String?, val type: String, val currency: String,
    val amount: String, val minAmount: String, val maxAmount: String, val periodCode: String, val periodDays: Int,
    val minIntervalDays: Int, val maxIntervalDays: Int, val occurrences: Int, val lastDate: String, val nextDate: String,
    val daysUntil: Int,
)

data class FinanceRecurringProjection(
    val algorithmVersion: String, val completeness: String, val timeZone: String, val asOf: String,
    val expenseSeries: List<FinanceRecurringSeries>, val incomeSeries: List<FinanceRecurringSeries>,
    val dueSoon: List<FinanceRecurringSeries>, val overdue: List<FinanceRecurringSeries>,
    val nextIncome: FinanceRecurringSeries?, val monthlyExpenseEstimate: String?,
    val monthlyExpenseEstimates: Map<String, String>, val mutedSeries: List<FinanceRecurringSeries>,
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

data class FinanceTransaction(
    val id: String,
    val tenantId: String,
    val type: String,
    val amount: String,
    val currency: String,
    val categoryCode: String,
    val subcategoryCode: String?,
    val description: String,
    val source: String,
    val occurredAt: String,
    val accountId: String?,
    val status: String,
    val version: Long,
    val createdAt: String,
    val memberName: String?,
    val debtId: String?,
    val ownerUserId: String?,
)

data class FinanceReceiptProcessingJob(
    val id: String,
    val tenantId: String,
    val documentId: String,
    val state: String,
    val stage: String,
    val progressPercent: Int,
    val attemptCount: Int,
    val retryable: Boolean,
    val errorCode: String?,
    val receiptId: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class FinanceReceipt(
    val id: String,
    val tenantId: String,
    val documentId: String?,
    val state: String,
    val version: Long,
    val transactionId: String?,
    val currency: String,
    val cashTotal: String?,
    val itemsTotal: String?,
    val merchant: String?,
    val receiptDate: String?,
    val selectedReader: String?,
    val categoryCode: String?,
    val categorySource: String,
    val categoryAlgorithmVersion: String,
    val alcoholShare: String?,
    val leisureShare: String?,
    val leisure: Boolean,
    val duplicateDecision: String,
    val duplicateOfReceiptId: String?,
    val items: List<FinanceReceiptItem>,
    val itemCount: Int,
    val createdAt: String,
)

data class FinanceReceiptItem(
    val id: String,
    val name: String,
    val quantity: String?,
    val unitPrice: String?,
    val lineSum: String?,
    val productKey: String?,
    val provenance: String?,
    val confidence: Double?,
    val categoryCode: String?,
    val verdict: String?,
    val advice: String?,
    val reviewReason: String?,
    val reviewAction: String?,
    val verdictSource: String?,
    val reviewProvider: String?,
    val reviewModelVersion: String?,
    val reviewPromptVersion: String?,
    val reviewAlgorithmVersion: String?,
    val version: Long,
)

data class FinanceReceiptItemPage(
    val items: List<FinanceReceiptItem>,
    val page: Int,
    val totalItems: Int,
    val hasMore: Boolean,
)

data class FinanceReceiptRepeatWarning(
    val itemId: String,
    val name: String,
    val productKey: String,
    val verdict: String,
    val title: String,
    val count: Int,
    val lastSum: String,
    val advice: String?,
)

data class FinanceReceiptRepeatWarnings(val warnings: List<FinanceReceiptRepeatWarning>)

data class FinanceProductPricePoint(
    val receiptId: String,
    val itemId: String,
    val purchasedAt: String,
    val merchant: String?,
    val name: String,
    val unitPrice: String,
    val current: Boolean,
)

data class FinanceProductCatalogPoint(
    val receiptId: String,
    val itemId: String,
    val purchasedAt: String,
    val merchant: String?,
    val name: String,
    val unitPrice: String,
    val current: Boolean,
)

data class FinanceProductCard(
    val productName: String,
    val purchaseCount: Int,
    val usualUnitPrice: String,
    val hasBaseline: Boolean,
    val baselineUnitPrice: String?,
    val lastUnitPrice: String,
    val lastPurchasedAt: String,
    val lastMerchant: String?,
    val cheapestUnitPrice: String,
    val cheapestMerchant: String?,
    val totalSpent: String,
    val change: String?,
    val relative: String?,
    val signal: Boolean,
    val direction: String?,
    val priorPurchases: Int,
    val chartAvailable: Boolean,
    val history: List<FinanceProductCatalogPoint>,
)

data class FinanceProductCatalog(val mode: String, val query: String, val products: List<FinanceProductCard>)

data class FinanceProductPriceComparison(
    val algorithmVersion: String,
    val productName: String,
    val hasBaseline: Boolean,
    val currentUnitPrice: String,
    val baselineUnitPrice: String?,
    val change: String?,
    val relative: String?,
    val signal: Boolean,
    val direction: String?,
    val priorPurchases: Int,
    val history: List<FinanceProductPricePoint>,
)

data class FinanceReceiptDuplicateCandidate(
    val id: String,
    val cashTotal: String,
    val merchant: String?,
    val createdAt: String,
)

data class FinanceReceiptDuplicateCandidates(
    val receiptId: String,
    val decision: String,
    val candidates: List<FinanceReceiptDuplicateCandidate>,
)

data class FinanceReceiptReading(
    val text: String,
    val words: List<Map<String, Any?>>,
    val provider: String?,
    val modelVersion: String?,
    val promptVersion: String?,
    val confidence: Double?,
    val ocrTotal: String?,
    val ocrItems: List<FinanceReceiptLineItem>,
    val reconciliation: FinanceReceiptReconciliation,
    val visionFallbackReason: String?,
    val ocrFallbackReason: String?,
    val vision: FinanceReceiptVisionReading?,
)

data class FinanceReceiptLineItem(
    val name: String,
    val quantity: String?,
    val unitPrice: String?,
    val lineSum: String?,
)

data class FinanceReceiptReconciliation(
    val algorithmVersion: String,
    val decision: String,
    val selectedReader: String?,
    val mismatchFields: List<String>,
    val ocrItemsTotal: String?,
    val visionItemsTotal: String?,
    val allowedDifference: String?,
    val ocrItemsReconciled: Boolean,
    val visionItemsReconciled: Boolean,
    val itemEvidence: List<FinanceReceiptItemEvidence>,
    val suggestedTopUps: List<FinanceReceiptTopUpSuggestion>,
)

data class FinanceReceiptItemEvidence(
    val visionOrdinal: Int,
    val ocrOrdinal: Int?,
    val status: String,
)

data class FinanceReceiptTopUpSuggestion(
    val ocrOrdinal: Int,
    val name: String,
    val lineSum: String,
)

data class FinanceReceiptVisionReading(
    val store: String?,
    val date: String?,
    val total: String?,
    val items: List<Map<String, Any?>>,
    val provider: String?,
    val modelVersion: String?,
    val promptVersion: String?,
    val fallbackReason: String?,
)

data class FinanceTransactionEdit(
    val id: String,
    val version: Long,
    val type: String,
    val amount: String,
    val currency: String,
    val categoryCode: String,
    val subcategoryCode: String?,
    val description: String,
    val source: String,
    val occurredAt: String,
    val debtId: String?,
    val ownerUserId: String?,
    val accountId: String? = null,
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

data class FinanceTransactionPage(val items: List<FinanceTransaction>, val nextCursor: String?)

data class FinanceTenantMember(val userId: String, val displayName: String, val role: String)

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
    fun productCatalog(json: JSONObject, requestedQuery: String): FinanceProductCatalog {
        val responseFields = setOf("mode", "query", "products")
        require(json.keys().asSequence().toSet() == responseFields) { "Invalid product catalog fields" }
        require(isValidProductCatalogQuery(requestedQuery)) {
            "Invalid product catalog query"
        }
        val normalizedQuery = requestedQuery.trim()
        val mode = if (normalizedQuery.isEmpty()) "catalog" else "search"
        val actualMode = json.getString("mode")
        val query = json.getString("query")
        val rows = json.getJSONArray("products")
        val limit = if (mode == "catalog") 10 else 5
        require(actualMode == mode && query == normalizedQuery && rows.length() <= limit) {
            "Product catalog query or mode does not match request"
        }

        val cards = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val fields = setOf(
                "productName", "purchaseCount", "usualUnitPrice", "hasBaseline", "baselineUnitPrice",
                "lastUnitPrice", "lastPurchasedAt", "lastMerchant", "cheapestUnitPrice", "cheapestMerchant",
                "totalSpent", "change", "relative", "signal", "direction", "priorPurchases",
                "chartAvailable", "history",
            )
            require(row.keys().asSequence().toSet() == fields) { "Invalid product catalog card fields" }
            val name = row.getString("productName")
            val purchaseCount = exactInt(row, "purchaseCount")
            val usual = row.getString("usualUnitPrice")
            val hasBaseline = requiredBoolean(row, "hasBaseline")
            val baseline = requiredNullableString(row, "baselineUnitPrice")
            val last = row.getString("lastUnitPrice")
            val lastAt = row.getString("lastPurchasedAt")
            val lastMerchant = requiredNullableString(row, "lastMerchant")
            val cheapest = row.getString("cheapestUnitPrice")
            val cheapestMerchant = requiredNullableString(row, "cheapestMerchant")
            val totalSpent = row.getString("totalSpent")
            val change = requiredNullableString(row, "change")
            val relative = requiredNullableString(row, "relative")
            val signal = requiredBoolean(row, "signal")
            val direction = requiredNullableString(row, "direction")
            val priorPurchases = exactInt(row, "priorPurchases")
            val chartAvailable = requiredBoolean(row, "chartAvailable")
            val historyJson = row.getJSONArray("history")
            val pricePattern = Regex("^\\d{1,30}\\.\\d{6}$")
            val changePattern = Regex("^-?\\d{1,30}\\.\\d{6}$")
            val relativePattern = Regex("^-?\\d{1,45}\\.\\d{6}$")
            val moneyPattern = Regex("^\\d{1,22}\\.\\d{2}$")
            require(name.isNotBlank() && name.length <= 200 && purchaseCount in 1..5000
                && (mode != "catalog" || purchaseCount >= 3)
                && pricePattern.matches(usual) && pricePattern.matches(last) && pricePattern.matches(cheapest)
                && BigDecimal(usual).signum() > 0 && BigDecimal(last).signum() > 0
                && BigDecimal(cheapest).signum() > 0
                && (baseline == null || pricePattern.matches(baseline))
                && (change == null || changePattern.matches(change))
                && (relative == null || relativePattern.matches(relative))
                && moneyPattern.matches(totalSpent) && BigDecimal(totalSpent).signum() >= 0
                && (lastMerchant == null || lastMerchant.length <= 200)
                && (cheapestMerchant == null || cheapestMerchant.length <= 200)
                && runCatching { Instant.parse(lastAt) }.isSuccess
                && priorPurchases in 0 until purchaseCount
                && direction in setOf(null, "up", "down")
                && historyJson.length() in 1..12
                && historyJson.length() <= purchaseCount
                && chartAvailable == (historyJson.length() >= 2)) { "Invalid product catalog values" }

            val points = (0 until historyJson.length()).map { pointIndex ->
                val pointJson = historyJson.getJSONObject(pointIndex)
                val pointFields = setOf("receiptId", "itemId", "purchasedAt", "merchant", "name", "unitPrice", "current")
                require(pointJson.keys().asSequence().toSet() == pointFields) { "Invalid product history fields" }
                val receiptId = pointJson.getString("receiptId")
                val itemId = pointJson.getString("itemId")
                val purchasedAt = pointJson.getString("purchasedAt")
                val merchant = requiredNullableString(pointJson, "merchant")
                val pointName = pointJson.getString("name")
                val unitPrice = pointJson.getString("unitPrice")
                val current = requiredBoolean(pointJson, "current")
                require(isCanonicalUuid(receiptId) && isCanonicalUuid(itemId)
                    && runCatching { Instant.parse(purchasedAt) }.isSuccess
                    && (merchant == null || merchant.length <= 200)
                    && pointName.isNotBlank() && pointName.length <= 200
                    && pricePattern.matches(unitPrice) && BigDecimal(unitPrice).signum() > 0
                    && !current) { "Invalid product history point" }
                FinanceProductCatalogPoint(receiptId, itemId, purchasedAt, merchant, pointName, unitPrice, current)
            }
            val times = points.map { Instant.parse(it.purchasedAt) }
            require(times.zipWithNext().all { (earlier, later) -> !later.isBefore(earlier) }) {
                "Product history must be chronological"
            }
            val latestPoint = points.last()
            require(latestPoint.name == name && latestPoint.unitPrice == last
                && latestPoint.purchasedAt == lastAt && latestPoint.merchant == lastMerchant) {
                "Product card latest values do not match history"
            }
            if (hasBaseline) {
                require(baseline != null && change != null && relative != null && priorPurchases > 0
                    && historyJson.length() >= 2 && (!signal && direction == null || signal && direction != null)) {
                    "Product baseline fields are inconsistent"
                }
            } else {
                require(baseline == null && change == null && relative == null && priorPurchases == 0
                    && !signal && direction == null) { "Product without baseline has derived values" }
            }
            FinanceProductCard(name, purchaseCount, usual, hasBaseline, baseline, last, lastAt, lastMerchant,
                cheapest, cheapestMerchant, totalSpent, change, relative, signal, direction, priorPurchases,
                chartAvailable, points)
        }
        return FinanceProductCatalog(mode, query, cards)
    }

    fun productPriceComparison(
        json: JSONObject,
        requestedReceiptId: String,
        requestedItemId: String,
    ): FinanceProductPriceComparison {
        val comparisonFields = setOf(
            "algorithmVersion", "productName", "hasBaseline", "currentUnitPrice", "baselineUnitPrice",
            "change", "relative", "signal", "direction", "priorPurchases", "history",
        )
        require(json.keys().asSequence().all { it in comparisonFields }) { "Unexpected price comparison field" }
        require(json.keys().asSequence().toSet() == comparisonFields) { "Incomplete price comparison" }
        require(isCanonicalUuid(requestedReceiptId) && isCanonicalUuid(requestedItemId)) {
            "Invalid requested price-history item"
        }

        val algorithmVersion = json.getString("algorithmVersion")
        val productName = json.getString("productName")
        val hasBaseline = requiredBoolean(json, "hasBaseline")
        val currentUnitPrice = json.getString("currentUnitPrice")
        val baseline = requiredNullableString(json, "baselineUnitPrice")
        val change = requiredNullableString(json, "change")
        val relative = requiredNullableString(json, "relative")
        val signal = requiredBoolean(json, "signal")
        val direction = requiredNullableString(json, "direction")
        val priorPurchases = exactInt(json, "priorPurchases")
        val historyJson = json.getJSONArray("history")
        val unitPricePattern = Regex("^\\d{1,24}\\.\\d{6}$")
        val changePattern = Regex("^-?\\d{1,24}\\.\\d{6}$")
        val relativePattern = Regex("^-?\\d{1,40}\\.\\d{6}$")

        require(algorithmVersion == "price-projection.v1"
            && productName.isNotBlank() && productName.length <= 200
            && unitPricePattern.matches(currentUnitPrice)
            && (baseline == null || unitPricePattern.matches(baseline))
            && (change == null || changePattern.matches(change))
            && (relative == null || relativePattern.matches(relative))
            && direction in setOf(null, "up", "down")
            && priorPurchases in 0..5000
            && historyJson.length() in 1..5001) { "Invalid price comparison values" }

        val pointFields = setOf("receiptId", "itemId", "purchasedAt", "merchant", "name", "unitPrice", "current")
        val history = (0 until historyJson.length()).map { index ->
            val row = historyJson.getJSONObject(index)
            require(row.keys().asSequence().all { it in pointFields }
                && row.keys().asSequence().toSet() == pointFields) { "Invalid price history point fields" }
            val receiptId = row.getString("receiptId")
            val itemId = row.getString("itemId")
            val purchasedAt = row.getString("purchasedAt")
            val merchant = requiredNullableString(row, "merchant")
            val name = row.getString("name")
            val unitPrice = row.getString("unitPrice")
            val current = requiredBoolean(row, "current")
            require(isCanonicalUuid(receiptId) && isCanonicalUuid(itemId)
                && runCatching { Instant.parse(purchasedAt) }.isSuccess
                && (merchant == null || merchant.length <= 200)
                && name.isNotBlank() && name.length <= 200
                && unitPricePattern.matches(unitPrice)) { "Invalid price history point" }
            FinanceProductPricePoint(receiptId, itemId, purchasedAt, merchant, name, unitPrice, current)
        }
        val orderedInstants = history.map { Instant.parse(it.purchasedAt) }
        require(orderedInstants.zipWithNext().all { (earlier, later) -> !later.isBefore(earlier) }) {
            "Price history must be chronological"
        }
        val currentPoint = history.last()
        require(history.dropLast(1).none { it.current } && currentPoint.current
            && currentPoint.receiptId.equals(requestedReceiptId, ignoreCase = true)
            && currentPoint.itemId.equals(requestedItemId, ignoreCase = true)
            && currentPoint.name == productName && currentPoint.unitPrice == currentUnitPrice) {
            "Price history current item does not match the requested receipt item"
        }

        if (hasBaseline) {
            require(baseline != null && change != null && relative != null
                && priorPurchases > 0 && history.size >= 2
                && (!signal && direction == null || signal && direction in setOf("up", "down"))) {
                "Baseline comparison is inconsistent"
            }
        } else {
            require(baseline == null && change == null && relative == null
                && !signal && direction == null && priorPurchases == 0) {
                "No-baseline comparison must not contain derived values"
    }
}

        return FinanceProductPriceComparison(algorithmVersion, productName, hasBaseline, currentUnitPrice,
            baseline, change, relative, signal, direction, priorPurchases, history)
    }

    private fun isCanonicalUuid(value: String): Boolean =
        runCatching { java.util.UUID.fromString(value).toString().equals(value, ignoreCase = true) }.getOrDefault(false)

    private fun requiredNullableString(json: JSONObject, key: String): String? {
        require(json.has(key)) { "Missing nullable field: $key" }
        return if (json.isNull(key)) null else json.get(key) as? String
            ?: throw IllegalArgumentException("Invalid string field: $key")
    }

    private fun requiredBoolean(json: JSONObject, key: String): Boolean =
        json.get(key) as? Boolean ?: throw IllegalArgumentException("Invalid boolean field: $key")

    fun tenantMembers(json: JSONArray): List<FinanceTenantMember> =
        (0 until json.length()).map { index ->
            val member = json.getJSONObject(index)
            FinanceTenantMember(member.getString("userId"), member.getString("displayName"), member.getString("role"))
        }

    fun transactionPage(json: JSONObject) = FinanceTransactionPage(
        items = json.getJSONArray("items").let { rows ->
            (0 until rows.length()).map { index -> transaction(rows.getJSONObject(index)) }
        },
        nextCursor = nullableString(json, "nextCursor"),
    )

    fun transactionQuery(pageSize: Int = 50, cursor: String? = null, from: String? = null, to: String? = null,
                         type: String? = null, search: String? = null, memberId: String? = null): String {
        require(pageSize in 1..200) { "Transaction page size must be from 1 to 200" }
        val parameters = mutableListOf("pageSize=$pageSize")
        listOf("cursor" to cursor, "from" to from, "to" to to, "type" to type,
            "search" to search, "memberId" to memberId).forEach { (name, value) ->
            if (!value.isNullOrBlank()) parameters += "$name=${android.net.Uri.encode(value)}"
        }
        return parameters.joinToString("&")
    }

    fun transaction(json: JSONObject) = FinanceTransaction(
        id = json.getString("id"), tenantId = json.getString("tenantId"), type = json.getString("type"),
        amount = json.getString("amount"), currency = json.getString("currency"),
        categoryCode = json.getString("categoryCode"), subcategoryCode = nullableString(json, "subcategoryCode"),
        description = json.getString("description"), source = json.getString("source"),
        occurredAt = json.getString("occurredAt"), accountId = nullableString(json, "accountId"),
        status = json.getString("status"), version = json.getLong("version"), createdAt = json.getString("createdAt"),
        memberName = nullableString(json, "memberName"), debtId = nullableString(json, "debtId"),
        ownerUserId = nullableString(json, "ownerUserId"),
    )

    fun receiptProcessingJob(json: JSONObject) = FinanceReceiptProcessingJob(
        id = json.getString("id"),
        tenantId = json.getString("tenantId"),
        documentId = json.getString("documentId"),
        state = json.getString("state"),
        stage = json.getString("stage"),
        progressPercent = json.getInt("progressPercent"),
        attemptCount = json.getInt("attemptCount"),
        retryable = json.getBoolean("retryable"),
        errorCode = nullableString(json, "errorCode"),
        receiptId = nullableString(json, "receiptId"),
        createdAt = json.getString("createdAt"),
        updatedAt = json.getString("updatedAt"),
    )

    fun receipt(json: JSONObject): FinanceReceipt {
        val rows = json.getJSONArray("items")
        val items = (0 until rows.length()).map { index -> receiptItem(rows.getJSONObject(index)) }
        return FinanceReceipt(
            id = json.getString("id"),
            tenantId = json.getString("tenantId"),
            documentId = nullableString(json, "documentId"),
            state = json.getString("state"),
            version = json.getLong("version"),
            transactionId = nullableString(json, "transactionId"),
            currency = json.getString("currency"),
            cashTotal = nullableString(json, "cashTotal"),
            itemsTotal = nullableString(json, "itemsTotal"),
            merchant = nullableString(json, "merchant"),
            receiptDate = nullableString(json, "receiptDate"),
            selectedReader = nullableString(json, "selectedReader"),
            categoryCode = nullableString(json, "categoryCode"),
            categorySource = json.getString("categorySource"),
            categoryAlgorithmVersion = json.getString("categoryAlgorithmVersion"),
            alcoholShare = nullableString(json, "alcoholShare"),
            leisureShare = nullableString(json, "leisureShare"),
            leisure = json.getBoolean("leisure"),
            duplicateDecision = json.getString("duplicateDecision"),
            duplicateOfReceiptId = nullableString(json, "duplicateOfReceiptId"),
            items = items,
            itemCount = json.getInt("itemCount"),
            createdAt = json.getString("createdAt"),
        )
    }

    fun receiptItemPage(json: JSONObject): FinanceReceiptItemPage {
        val rows = json.getJSONArray("items")
        return FinanceReceiptItemPage(
            items = (0 until rows.length()).map { index -> receiptItem(rows.getJSONObject(index)) },
            page = json.getInt("page"),
            totalItems = json.getInt("totalItems"),
            hasMore = json.getBoolean("hasMore"),
        )
    }

    fun receiptRepeatWarnings(json: JSONObject): FinanceReceiptRepeatWarnings {
        val rows = json.getJSONArray("warnings")
        require(rows.length() <= 200) { "Invalid receipt repeat warning count" }
        val amount = Regex("^(?:0|[1-9][0-9]{0,17})\\.[0-9]{2}$")
        val warnings = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val itemId = row.getString("itemId")
            val name = row.getString("name")
            val productKey = row.getString("productKey")
            val verdict = row.getString("verdict")
            val title = row.getString("title")
            val count = row.getInt("count")
            val lastSum = row.getString("lastSum")
            val advice = nullableString(row, "advice")
            require(runCatching { java.util.UUID.fromString(itemId).toString().equals(itemId, ignoreCase = true) }.getOrDefault(false) &&
                name.isNotBlank() && name.length <= 200 && productKey.isNotBlank() && productKey.length <= 256 &&
                verdict in setOf("harmful", "unnecessary") && title.isNotBlank() && title.length <= 80 &&
                count >= 1 && amount.matches(lastSum) && (advice == null || advice.length <= 500)) {
                "Invalid receipt repeat warning"
            }
            FinanceReceiptRepeatWarning(itemId, name, productKey, verdict, title, count, lastSum, advice)
        }
        return FinanceReceiptRepeatWarnings(warnings)
    }

    fun receiptDuplicateCandidates(json: JSONObject): FinanceReceiptDuplicateCandidates {
        val rows = json.getJSONArray("candidates")
        return FinanceReceiptDuplicateCandidates(
            receiptId = json.getString("receiptId"),
            decision = json.getString("decision"),
            candidates = (0 until rows.length()).map { index ->
                val candidate = rows.getJSONObject(index)
                FinanceReceiptDuplicateCandidate(
                    id = candidate.getString("id"),
                    cashTotal = candidate.getString("cashTotal"),
                    merchant = nullableString(candidate, "merchant"),
                    createdAt = candidate.getString("createdAt"),
                )
            },
        )
    }

    fun receiptReading(json: JSONObject): FinanceReceiptReading {
        val reconciliationJson = json.getJSONObject("reconciliation")
        val words = json.getJSONArray("words").objectMaps()
        val ocrItemsJson = json.getJSONArray("ocrItems")
        val evidenceJson = reconciliationJson.getJSONArray("itemEvidence")
        val topUpsJson = reconciliationJson.getJSONArray("suggestedTopUps")
        val visionJson = json.optJSONObject("vision")
        val ocrItems = (0 until ocrItemsJson.length()).map { index ->
            val item = ocrItemsJson.getJSONObject(index)
            FinanceReceiptLineItem(
                name = item.getString("name"),
                quantity = nullableString(item, "quantity"),
                unitPrice = nullableString(item, "unitPrice"),
                lineSum = nullableString(item, "lineSum"),
            )
        }
        val reconciliation = FinanceReceiptReconciliation(
            algorithmVersion = reconciliationJson.getString("algorithmVersion"),
            decision = reconciliationJson.getString("decision"),
            selectedReader = nullableString(reconciliationJson, "selectedReader"),
            mismatchFields = reconciliationJson.getJSONArray("mismatchFields").stringValues(),
            ocrItemsTotal = nullableString(reconciliationJson, "ocrItemsTotal"),
            visionItemsTotal = nullableString(reconciliationJson, "visionItemsTotal"),
            allowedDifference = nullableString(reconciliationJson, "allowedDifference"),
            ocrItemsReconciled = reconciliationJson.getBoolean("ocrItemsReconciled"),
            visionItemsReconciled = reconciliationJson.getBoolean("visionItemsReconciled"),
            itemEvidence = (0 until evidenceJson.length()).map { index ->
                val item = evidenceJson.getJSONObject(index)
                FinanceReceiptItemEvidence(
                    visionOrdinal = item.getInt("visionOrdinal"),
                    ocrOrdinal = nullableInt(item, "ocrOrdinal"),
                    status = item.getString("status"),
                )
            },
            suggestedTopUps = (0 until topUpsJson.length()).map { index ->
                val item = topUpsJson.getJSONObject(index)
                FinanceReceiptTopUpSuggestion(
                    ocrOrdinal = item.getInt("ocrOrdinal"),
                    name = item.getString("name"),
                    lineSum = item.getString("lineSum"),
                )
            },
        )
        val vision = visionJson?.let { value ->
            FinanceReceiptVisionReading(
                store = nullableString(value, "store"),
                date = nullableString(value, "date"),
                total = nullableString(value, "total"),
                items = value.getJSONArray("items").objectMaps(),
                provider = nullableString(value, "provider"),
                modelVersion = nullableString(value, "modelVersion"),
                promptVersion = nullableString(value, "promptVersion"),
                fallbackReason = nullableString(value, "fallbackReason"),
            )
        }
        return FinanceReceiptReading(
            text = json.getString("text"),
            words = words,
            provider = nullableString(json, "provider"),
            modelVersion = nullableString(json, "modelVersion"),
            promptVersion = nullableString(json, "promptVersion"),
            confidence = nullableDouble(json, "confidence"),
            ocrTotal = nullableString(json, "ocrTotal"),
            ocrItems = ocrItems,
            reconciliation = reconciliation,
            visionFallbackReason = nullableString(json, "visionFallbackReason"),
            ocrFallbackReason = nullableString(json, "ocrFallbackReason"),
            vision = vision,
        )
    }

    private fun JSONArray.stringValues(): List<String> =
        (0 until length()).map { index -> getString(index) }

    private fun JSONArray.objectMaps(): List<Map<String, Any?>> =
        (0 until length()).map { index -> jsonObjectMap(getJSONObject(index)) }

    private fun jsonObjectMap(json: JSONObject): Map<String, Any?> =
        json.keys().asSequence().associateWith { key -> jsonValue(json.get(key)) }

    private fun jsonValue(value: Any): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> jsonObjectMap(value)
        is JSONArray -> (0 until value.length()).map { index -> jsonValue(value.get(index)) }
        else -> value
    }

    private fun receiptItem(json: JSONObject) = FinanceReceiptItem(
        id = json.getString("id"),
        name = json.getString("name"),
        quantity = nullableString(json, "quantity"),
        unitPrice = nullableString(json, "unitPrice"),
        lineSum = nullableString(json, "lineSum"),
        productKey = nullableString(json, "productKey"),
        provenance = nullableString(json, "provenance"),
        confidence = if (json.has("confidence") && !json.isNull("confidence")) json.getDouble("confidence") else null,
        categoryCode = nullableString(json, "categoryCode"),
        verdict = nullableString(json, "verdict"),
        advice = nullableString(json, "advice"),
        reviewReason = nullableString(json, "reviewReason"),
        reviewAction = nullableString(json, "reviewAction"),
        verdictSource = nullableString(json, "verdictSource"),
        reviewProvider = nullableString(json, "reviewProvider"),
        reviewModelVersion = nullableString(json, "reviewModelVersion"),
        reviewPromptVersion = nullableString(json, "reviewPromptVersion"),
        reviewAlgorithmVersion = nullableString(json, "reviewAlgorithmVersion"),
        version = json.getLong("version"),
    )

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
        waste = wasteReport(json.getJSONObject("waste")),
    )

    private fun wasteReport(json: JSONObject): FinanceWasteReport {
        val topItems = json.getJSONArray("topItems").let { items ->
            (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                FinanceWasteItem(item.getString("name"), item.getString("amount"),
                    item.getString("verdict"), item.getString("source"))
            }
        }
        val corrected = json.getJSONArray("corrected").let { items ->
            (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                FinanceWasteCorrection(item.getString("productName"), item.getInt("count"), item.getString("amount"))
            }
        }
        return FinanceWasteReport(
            available = json.getBoolean("available"),
            reasonCode = json.getString("reasonCode"),
            completeness = json.getString("completeness"),
            reviewedSpend = nullableString(json, "reviewedSpend"),
            optionalSpend = nullableString(json, "optionalSpend"),
            optionalShare = nullableString(json, "optionalShare"),
            reviewedItemCount = json.getInt("reviewedItemCount"),
            optionalItemCount = json.getInt("optionalItemCount"),
            missingAmountCount = json.getInt("missingAmountCount"),
            bySource = stringMap(json, "bySource"),
            topItems = topItems,
            corrected = corrected,
            optionalByDay = stringMap(json, "optionalByDay"),
        )
    }

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
                && name.isNotBlank() && name.length <= 200
                && reason in setOf("confirmed_not_to_buy", "rule_backed_not_to_buy")) {
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

    fun receiptRecalculationPreview(json: JSONObject): FinanceReceiptRecalculationPreview {
        val runId = json.getString("runId")
        val algorithmVersion = json.getString("algorithmVersion")
        val state = json.getString("state")
        val checked = exactInt(json, "checked")
        val updates = exactInt(json, "updateCount")
        val changed = exactInt(json, "changedCount")
        val impact = requiredNullableObject(json, "impact")?.let(::receiptRecalculationImpact)
        val changes = receiptRecalculationChanges(json.getJSONArray("changes"), maximum = 50_000)
        require(isCanonicalUuid(runId) && algorithmVersion.isNotBlank() && algorithmVersion.length <= 128
            && state.isNotBlank() && state.length <= 64 && checked in 0..50_000
            && updates in 0..checked && changed in 0..updates
            && changes.size <= updates) { "Invalid receipt recalculation preview" }
        return FinanceReceiptRecalculationPreview(runId, algorithmVersion, state, checked, updates,
            changed, impact, changes)
    }

    fun receiptRecalculationApplyResult(json: JSONObject): FinanceReceiptRecalculationApplyResult {
        val runId = json.getString("runId")
        val algorithmVersion = json.getString("algorithmVersion")
        val state = json.getString("state")
        val applied = exactInt(json, "appliedCount")
        val changed = exactInt(json, "changedCount")
        val impact = requiredNullableObject(json, "impact")?.let(::receiptRecalculationImpact)
        val changes = receiptRecalculationChanges(json.getJSONArray("changes"), maximum = 50_000)
        require(isCanonicalUuid(runId) && algorithmVersion.isNotBlank() && algorithmVersion.length <= 128
            && state.isNotBlank() && state.length <= 64 && applied in 0..50_000
            && changed in 0..applied && changes.size <= applied) { "Invalid receipt recalculation result" }
        return FinanceReceiptRecalculationApplyResult(runId, algorithmVersion, state, applied,
            changed, impact, changes)
    }

    fun receiptRecalculationHistory(json: JSONObject): FinanceReceiptRecalculationHistoryPage {
        val rows = json.getJSONArray("runs")
        require(rows.length() <= 100) { "Too many recalculation history rows" }
        val runs = (0 until rows.length()).map { index -> receiptRecalculationRun(rows.getJSONObject(index)) }
        val cursor = nullableString(json, "nextCursor")
        require(cursor == null || cursor.isNotBlank() && cursor.length <= 1024) { "Invalid recalculation cursor" }
        return FinanceReceiptRecalculationHistoryPage(runs, cursor)
    }

    fun receiptRecalculationDetail(json: JSONObject): FinanceReceiptRecalculationDetail {
        val run = receiptRecalculationRun(json.getJSONObject("run"))
        val changes = receiptRecalculationChanges(json.getJSONArray("changes"), maximum = 100)
        val cursor = nullableString(json, "nextCursor")
        require(cursor == null || cursor.isNotBlank() && cursor.length <= 1024) { "Invalid recalculation cursor" }
        return FinanceReceiptRecalculationDetail(run, changes, cursor)
    }

    private fun receiptRecalculationRun(json: JSONObject): FinanceReceiptRecalculationRunSummary {
        val runId = json.getString("runId")
        val algorithmVersion = json.getString("algorithmVersion")
        val state = json.getString("state")
        val checked = exactInt(json, "checked")
        val updates = exactInt(json, "updateCount")
        val changed = exactInt(json, "changedCount")
        val createdAt = json.getString("createdAt")
        val appliedAt = nullableString(json, "appliedAt")
        val impact = requiredNullableObject(json, "impact")?.let(::receiptRecalculationImpact)
        require(isCanonicalUuid(runId) && algorithmVersion.isNotBlank() && algorithmVersion.length <= 128
            && state.isNotBlank() && state.length <= 64 && checked in 0..50_000
            && updates in 0..checked && changed in 0..updates
            && runCatching { Instant.parse(createdAt) }.isSuccess
            && (appliedAt == null || runCatching { Instant.parse(appliedAt) }.isSuccess)) {
            "Invalid receipt recalculation history run"
        }
        return FinanceReceiptRecalculationRunSummary(runId, algorithmVersion, state, checked,
            updates, changed, createdAt, appliedAt, impact)
    }

    private fun receiptRecalculationImpact(json: JSONObject): FinanceReceiptRecalculationImpact {
        val algorithmVersion = json.getString("algorithmVersion")
        val inputVersion = json.getString("inputVersion")
        val reason = json.getString("reasonCode")
        val completeness = json.getString("completeness")
        val before = requiredNullableString(json, "optionalSpendBefore")
        val after = requiredNullableString(json, "optionalSpendAfter")
        val delta = requiredNullableString(json, "optionalSpendDelta")
        val currency = requiredNullableString(json, "currency")
        val moneyPattern = Regex("^(?:0|[1-9]\\d{0,29})\\.\\d{2}$")
        val deltaPattern = Regex("^-?(?:0|[1-9]\\d{0,29})\\.\\d{2}$")
        val hasAmounts = before != null && after != null && delta != null
        val amountsAbsent = before == null && after == null && delta == null
        val consistentAvailability = if (reason == "available") {
            completeness == "complete" && hasAmounts
        } else {
            amountsAbsent && when (reason) {
                "no_reviewed_items" -> completeness == "complete"
                "missing_amounts", "analytics_unavailable" -> completeness == "partial"
                else -> false
            }
        }
        require(algorithmVersion == "receipt-recalculation-impact.v1"
            && Regex("^[0-9a-f]{64}$").matches(inputVersion)
            && reason in setOf("available", "no_reviewed_items", "missing_amounts", "analytics_unavailable")
            && completeness in setOf("complete", "partial")
            && (before == null || moneyPattern.matches(before))
            && (after == null || moneyPattern.matches(after))
            && (delta == null || deltaPattern.matches(delta))
            && consistentAvailability
            && (currency == null || Regex("^[A-Z]{3}$").matches(currency))) {
            "Invalid receipt recalculation impact"
        }
        return FinanceReceiptRecalculationImpact(algorithmVersion, inputVersion, reason,
            completeness, before, after, delta, currency)
    }

    private fun receiptRecalculationChanges(
        rows: JSONArray,
        maximum: Int,
    ): List<FinanceReceiptRecalculationChange> {
        require(rows.length() <= maximum) { "Too many receipt recalculation changes" }
        val ids = mutableSetOf<String>()
        val moneyPattern = Regex("^(?:0|[1-9]\\d{0,29})\\.\\d{2}$")
        return (0 until rows.length()).map { index ->
            val json = rows.getJSONObject(index)
            val itemId = json.getString("itemId")
            val name = json.getString("name")
            val lineSum = requiredNullableString(json, "lineSum")
            val itemVersion = exactLong(json, "itemVersion")
            require(isCanonicalUuid(itemId) && ids.add(itemId) && name.isNotBlank() && name.length <= 200
                && (lineSum == null || moneyPattern.matches(lineSum)) && itemVersion > 0L) {
                "Invalid receipt recalculation change"
            }
            FinanceReceiptRecalculationChange(itemId, name, lineSum, itemVersion,
                requiredNullableString(json, "beforeVerdict"),
                requiredNullableString(json, "beforeReason"),
                requiredNullableString(json, "beforeAction"),
                requiredNullableString(json, "beforeSource"),
                requiredNullableString(json, "afterVerdict"),
                requiredNullableString(json, "afterReason"),
                requiredNullableString(json, "afterAction"),
                requiredNullableString(json, "afterSource"),
                requiredBoolean(json, "changed"))
        }
    }

    private fun requiredNullableObject(json: JSONObject, key: String): JSONObject? {
        require(json.has(key)) { "Missing nullable field: $key" }
        if (json.isNull(key)) return null
        return json.get(key) as? JSONObject ?: throw IllegalArgumentException("Invalid object field: $key")
    }

    fun doNotBuy(json: JSONObject): FinanceDoNotBuy {
        val available = json.getBoolean("available")
        val reason = json.getString("reasonCode")
        val version = nullableString(json, "algorithmVersion")
        val inputVersion = nullableString(json, "inputVersion")
        require(reason in setOf("available", "no_optional_items", "too_many_items", "analytics_unavailable")
            && available == (reason == "available" || reason == "no_optional_items")
            && (if (available) version == "advice-evidence.v1" else version == null)
            && (if (reason == "available") inputVersion?.matches(Regex("^[0-9a-f]{64}$")) == true
                else inputVersion == null)) { "Invalid do-not-buy status" }
        val keys = mutableSetOf<String>()
        fun groups(field: String): List<FinanceAdviceGroup> {
            val array = json.getJSONArray(field)
            require(array.length() <= 25000) { "Too many do-not-buy groups" }
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val key = item.getString("productKey")
                val name = item.getString("productName")
                val count = exactInt(item, "count")
                val missing = exactInt(item, "missingAmountCount")
                val rule = exactInt(item, "ruleCount")
                val model = exactInt(item, "modelCount")
                val unmarked = exactInt(item, "unmarkedCount")
                val modelOnly = item.getBoolean("modelOnly")
                val amount = nullableString(item, "amount")
                val verdict = item.getString("latestVerdict")
                val advice = item.getString("latestAdvice")
                val purchased = item.getString("lastPurchasedAt")
                require(Regex("^[a-zа-я0-9]{1,256}$").matches(key) && keys.add(key)
                    && name.isNotBlank() && name.length <= 200 && count >= 2
                    && missing in 0..count && rule >= 0 && model >= 0 && unmarked >= 0
                    && rule + model + unmarked == count && modelOnly == (model == count)
                    && (field != "guesses" || rule == 0)
                    && (amount == null || Regex("^\\d{1,30}\\.\\d{2}$").matches(amount))
                    && verdict in setOf("harmful", "unnecessary") && advice.length <= 500) {
                    "Invalid do-not-buy group"
                }
                Instant.parse(purchased)
                FinanceAdviceGroup(key, name, count, amount, missing, rule, model, unmarked,
                    modelOnly, verdict, advice, purchased)
            }
        }
        val banned = groups("banned")
        val guesses = groups("guesses")
        require(available || banned.isEmpty() && guesses.isEmpty()) { "Unavailable advice cannot contain evidence" }
        require(reason != "no_optional_items" || banned.isEmpty() && guesses.isEmpty()) { "Empty advice has evidence" }
        return FinanceDoNotBuy(available, reason, banned, guesses)
    }

    fun adviceAnalyticsJob(json: JSONObject): FinanceAdviceAnalyticsJob {
        requireAdviceFields(json, setOf("id", "state", "inputWatermark", "algorithmVersion", "completeness",
            "errorCode", "report", "updatedAt"))
        val id = requiredNullableString(json, "id")
        val state = requiredNullableString(json, "state")
        val watermark = requiredNullableString(json, "inputWatermark")
        val algorithm = requiredNullableString(json, "algorithmVersion")
        val completeness = requiredNullableString(json, "completeness")
        val errorCode = requiredNullableString(json, "errorCode")
        val reportJson = requiredNullableObject(json, "report")
        val updatedAt = json.get("updatedAt") as? String
            ?: throw IllegalArgumentException("Invalid advice analytics updatedAt")
        require(runCatching { Instant.parse(updatedAt) }.isSuccess) { "Invalid advice analytics updatedAt" }

        if (id == null) {
            require(state == null && watermark == null && algorithm == null && completeness == null
                && errorCode == null && reportJson == null) { "Empty advice analytics job contains state" }
            return FinanceAdviceAnalyticsJob(null, null, null, null, null, null, null, updatedAt)
        }
        require(isCanonicalUuid(id) && state in setOf("pending", "processing", "ready", "failed", "stale")
            && watermark != null && isAdviceWatermark(watermark)
            && algorithm == "advice-f43.v1"
            && (completeness == null || completeness in setOf("complete", "partial"))) {
            "Invalid advice analytics job identity or state"
        }
        val report = reportJson?.let { adviceAnalyticsReport(it, watermark, completeness) }
        require((state == "ready") == (report != null) || state == "stale") {
            "Advice analytics ready state must carry its report"
        }
        require(if (state == "failed") errorCode != null else state == "stale" || errorCode == null) {
            "Advice analytics error state is inconsistent"
        }
        require(report == null || report.completeness == completeness) {
            "Advice analytics completeness does not match report"
        }
        return FinanceAdviceAnalyticsJob(id, state, watermark, algorithm, completeness, errorCode, report, updatedAt)
    }

    private fun adviceAnalyticsReport(
        json: JSONObject,
        jobWatermark: String,
        jobCompleteness: String?,
    ): FinanceAdviceAnalyticsReport {
        requireAdviceFields(json, setOf("algorithmVersion", "inputWatermark", "completeness", "reasonCode",
            "savings", "trend", "effects", "recalculation"))
        val algorithm = json.get("algorithmVersion") as? String
            ?: throw IllegalArgumentException("Invalid advice analytics algorithm")
        val watermark = json.get("inputWatermark") as? String
            ?: throw IllegalArgumentException("Invalid advice analytics watermark")
        val completeness = json.get("completeness") as? String
            ?: throw IllegalArgumentException("Invalid advice analytics completeness")
        val reason = json.get("reasonCode") as? String
            ?: throw IllegalArgumentException("Invalid advice analytics reason")
        require(algorithm == "advice-f43.v1" && watermark == jobWatermark && isAdviceWatermark(watermark)
            && completeness in setOf("complete", "partial") && (jobCompleteness == null || completeness == jobCompleteness)
            && reason in setOf("available", "missing_amounts", "insufficient_history")) {
            "Advice analytics report does not match job"
        }

        val savingsJson = json.getJSONObject("savings")
        requireAdviceFields(savingsJson, setOf("available", "reasonCode", "label", "days", "monthlyCeiling",
            "shareOfIncome", "shareOfLimit", "groups"))
        val savingsAvailable = requiredBoolean(savingsJson, "available")
        val savingsReason = adviceString(savingsJson, "reasonCode")
        val savingsLabel = adviceString(savingsJson, "label")
        val savingsDays = exactInt(savingsJson, "days")
        val monthlyCeiling = adviceDecimal(savingsJson, "monthlyCeiling", money = true)
        val shareOfIncome = adviceDecimal(savingsJson, "shareOfIncome")
        val shareOfLimit = adviceDecimal(savingsJson, "shareOfLimit")
        val savingsGroupsJson = savingsJson.getJSONArray("groups")
        require(savingsGroupsJson.length() <= 5) { "Too many advice savings groups" }
        val savingsGroups = (0 until savingsGroupsJson.length()).map { index ->
            val group = savingsGroupsJson.getJSONObject(index)
            requireAdviceFields(group, setOf("productKey", "name", "count", "spend", "monthlyCeiling"))
            val key = adviceString(group, "productKey")
            val name = adviceString(group, "name")
            val count = exactInt(group, "count")
            val spend = adviceDecimal(group, "spend", money = true)!!
            val monthly = adviceDecimal(group, "monthlyCeiling", money = true)
            require(key.isNotBlank() && name.isNotBlank() && name.length <= 200 && count >= 2) {
                "Invalid advice savings group"
            }
            FinanceAdviceSavingsGroup(key, name, count, spend, monthly)
        }
        require(savingsDays == 90 && savingsLabel == "theoretical_ceiling_not_actual_savings"
            && savingsReason in setOf("available", "missing_amounts", "insufficient_history")
            && (!savingsAvailable || savingsReason == "available" && monthlyCeiling != null && savingsGroups.isNotEmpty())
            && (monthlyCeiling != null || shareOfIncome == null && shareOfLimit == null)
            && (completeness != "partial" || !savingsAvailable)) { "Invalid advice savings report" }

        val trendJson = json.getJSONObject("trend")
        requireAdviceFields(trendJson, setOf("available", "reasonCode", "weeks", "delta", "direction"))
        val trendAvailable = requiredBoolean(trendJson, "available")
        val trendReason = adviceString(trendJson, "reasonCode")
        val trendDelta = adviceDecimal(trendJson, "delta")
        val direction = adviceString(trendJson, "direction")
        val weeksJson = trendJson.getJSONArray("weeks")
        require(weeksJson.length() <= 4) { "Too many advice trend weeks" }
        val weeks = (0 until weeksJson.length()).map { index ->
            val week = weeksJson.getJSONObject(index)
            requireAdviceFields(week, setOf("start", "end", "spend", "optionalSpend", "optionalShare", "itemCount", "recalculated"))
            val start = adviceString(week, "start")
            val end = adviceString(week, "end")
            runCatching { LocalDate.parse(start); LocalDate.parse(end) }
                .getOrElse { throw IllegalArgumentException("Invalid advice trend week date") }
            val spend = adviceDecimal(week, "spend", money = true)!!
            val optional = adviceDecimal(week, "optionalSpend", money = true)!!
            val share = adviceDecimal(week, "optionalShare")!!
            val count = exactInt(week, "itemCount")
            val recalculated = requiredBoolean(week, "recalculated")
            require(start <= end && count > 0) { "Invalid advice trend week" }
            FinanceAdviceWeek(start, end, spend, optional, share, count, recalculated)
        }
        require(trendReason in setOf("available", "missing_amounts", "insufficient_history")
            && (if (trendAvailable) trendReason == "available" && trendDelta != null && weeks.size >= 2
                && direction in setOf("up", "down", "steady")
                else trendDelta == null && (direction.isEmpty() || direction in setOf("up", "down", "steady")))
            && (completeness != "partial" || !trendAvailable)) { "Invalid advice trend report" }

        val effectsJson = json.getJSONObject("effects")
        requireAdviceFields(effectsJson, setOf("effects", "pending", "causalityClaim"))
        val causalityClaim = requiredBoolean(effectsJson, "causalityClaim")
        require(!causalityClaim) { "Advice analytics cannot claim causality" }
        val effectsArray = effectsJson.getJSONArray("effects")
        val pendingArray = effectsJson.getJSONArray("pending")
        require(effectsArray.length() <= 4 && pendingArray.length() <= 4) { "Too many advice effects" }
        val effects = (0 until effectsArray.length()).map { index ->
            val item = effectsArray.getJSONObject(index)
            requireAdviceFields(item, setOf("productKey", "name", "advice", "beforeCount", "afterCount", "daysBefore",
                "daysAfter", "intervalBefore", "intervalAfter", "change", "direction", "afterSpend"))
            val key = adviceString(item, "productKey")
            val name = adviceString(item, "name")
            val advice = adviceString(item, "advice")
            val before = exactInt(item, "beforeCount")
            val after = exactInt(item, "afterCount")
            val daysBefore = exactInt(item, "daysBefore")
            val daysAfter = exactInt(item, "daysAfter")
            val intervalBefore = adviceDecimal(item, "intervalBefore")!!
            val intervalAfter = adviceDecimal(item, "intervalAfter")
            val change = adviceDecimal(item, "change")!!
            val effectDirection = adviceString(item, "direction")
            val afterSpend = adviceDecimal(item, "afterSpend", money = true)
            require(key.isNotBlank() && name.isNotBlank() && advice.length <= 500 && before >= 2 && after >= 0
                && daysBefore > 0 && daysAfter >= 21 && effectDirection in setOf("less_often", "more_often", "same_frequency")) {
                "Invalid advice effect"
            }
            FinanceAdviceEffect(key, name, advice, before, after, daysBefore, daysAfter,
                intervalBefore, intervalAfter, change, effectDirection, afterSpend)
        }
        val pending = (0 until pendingArray.length()).map { index ->
            val item = pendingArray.getJSONObject(index)
            requireAdviceFields(item, setOf("productKey", "name", "daysAfter", "daysLeft"))
            val key = adviceString(item, "productKey")
            val name = adviceString(item, "name")
            val daysAfter = exactInt(item, "daysAfter")
            val daysLeft = exactInt(item, "daysLeft")
            require(key.isNotBlank() && name.isNotBlank() && daysAfter in 0..20 && daysLeft == 21 - daysAfter) {
                "Invalid pending advice effect"
            }
            FinanceAdvicePendingEffect(key, name, daysAfter, daysLeft)
        }

        val recalculationJson = json.getJSONObject("recalculation")
        requireAdviceFields(recalculationJson, setOf("available", "changedItemCount", "optionalSpendDelta", "windows"))
        val recalculationAvailable = requiredBoolean(recalculationJson, "available")
        val changedCount = exactInt(recalculationJson, "changedItemCount")
        val recalculationDelta = adviceDecimal(recalculationJson, "optionalSpendDelta", money = true)
        val windowsJson = recalculationJson.getJSONArray("windows")
        require(windowsJson.length() <= 5000) { "Too many advice recalculation windows" }
        val windows = (0 until windowsJson.length()).map { index ->
            val window = windowsJson.getJSONObject(index)
            requireAdviceFields(window, setOf("start", "end", "changedItemCount", "optionalSpendDelta"))
            val start = adviceString(window, "start")
            val end = adviceString(window, "end")
            runCatching { LocalDate.parse(start); LocalDate.parse(end) }
                .getOrElse { throw IllegalArgumentException("Invalid advice recalculation window date") }
            val count = exactInt(window, "changedItemCount")
            val delta = adviceDecimal(window, "optionalSpendDelta", money = true)
            require(start <= end && count >= 0) { "Invalid advice recalculation window" }
            FinanceAdviceRecalculationWindow(start, end, count, delta)
        }
        require(changedCount >= 0 && (if (recalculationAvailable) recalculationDelta != null
            else recalculationDelta == null) && (windows.isNotEmpty() || changedCount == 0)) {
            "Invalid advice recalculation report"
        }

        return FinanceAdviceAnalyticsReport(algorithm, watermark, completeness, reason,
            FinanceAdviceSavings(savingsAvailable, savingsReason, savingsLabel, savingsDays, monthlyCeiling,
                shareOfIncome, shareOfLimit, savingsGroups),
            FinanceAdviceTrend(trendAvailable, trendReason, trendDelta, direction, weeks),
            FinanceAdviceEffects(effects, pending, causalityClaim),
            FinanceAdviceRecalculation(recalculationAvailable, changedCount, recalculationDelta, windows))
    }

    private fun requireAdviceFields(json: JSONObject, expected: Set<String>) {
        require(json.keys().asSequence().toSet() == expected) { "Invalid advice analytics fields" }
    }

    private fun adviceString(json: JSONObject, field: String): String = json.get(field) as? String
        ?: throw IllegalArgumentException("Invalid advice analytics string: $field")

    private fun adviceDecimal(json: JSONObject, field: String, money: Boolean = false): String? {
        val raw = requiredNullableString(json, field) ?: return null
        val pattern = if (money) Regex("^-?(?:0|[1-9]\\d{0,29})\\.\\d{2}$")
            else Regex("^-?(?:0|[1-9]\\d{0,29})(?:\\.\\d{1,8})?$")
        require(pattern.matches(raw) && raw.toBigDecimalOrNull() != null) { "Invalid advice analytics decimal: $field" }
        return raw
    }

    private fun isAdviceWatermark(value: String): Boolean = value.matches(Regex("^[1-9]\\d{0,18}$"))
        && value.toLongOrNull() != null

    fun productDecisions(json: JSONObject): FinanceProductDecisions {
        val keys = mutableSetOf<String>()
        fun parse(field: String): List<String> {
            val array = json.getJSONArray(field)
            require(array.length() <= 50000) { "Too many product decisions" }
            return (0 until array.length()).map { index ->
                require(array.get(index) is String) { "Invalid product decision key" }
                val key = array.getString(index)
                require(Regex("^[a-zа-я0-9]{1,256}$").matches(key) && keys.add(key)) {
                    "Invalid product decision key"
                }
                key
            }
        }
        return FinanceProductDecisions(parse("productKeys"), parse("confirmedProductKeys"))
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

    fun recurringProjection(json: JSONObject): FinanceRecurringProjection {
        require(json.getString("algorithmVersion") == "recurring.v1" && json.getString("completeness") == "complete") {
            "Invalid recurring projection version"
        }
        val timezone = json.getString("timeZone")
        val zone = runCatching { ZoneId.of(timezone) }.getOrNull()
        val asOf = json.getString("asOf")
        val instant = runCatching { Instant.parse(asOf) }.getOrNull()
        require(timezone.isNotBlank() && timezone.length <= 64 && zone != null && instant != null
            && instant.atZone(zone).toLocalTime() == LocalTime.MIDNIGHT) { "Invalid recurring projection date" }
        val today = instant.atZone(zone).toLocalDate()
        val expenseJson = json.getJSONArray("expenseSeries")
        val incomeJson = json.getJSONArray("incomeSeries")
        val dueJson = json.getJSONArray("dueSoon")
        val overdueJson = json.getJSONArray("overdue")
        val mutedJson = json.getJSONArray("mutedSeries")
        require(expenseJson.length() <= 5000 && incomeJson.length() <= 5000
            && dueJson.length() <= 5000 && overdueJson.length() <= 5000 && mutedJson.length() <= 5000) {
            "Invalid recurring series count"
        }
        val ids = mutableSetOf<String>()

        fun parseSeries(item: JSONObject, expectedType: String, register: Boolean = true): FinanceRecurringSeries {
            val id = item.getString("id")
            val key = item.getString("key")
            val name = item.getString("name")
            val category = nullableString(item, "category")
            val type = item.getString("type")
            val currency = item.getString("currency")
            val amountRaw = item.getString("amount")
            val minRaw = item.getString("minAmount")
            val maxRaw = item.getString("maxAmount")
            val periodCode = item.getString("periodCode")
            val period = exactInt(item, "periodDays")
            val minInterval = exactInt(item, "minIntervalDays")
            val maxInterval = exactInt(item, "maxIntervalDays")
            val occurrences = exactInt(item, "occurrences")
            val lastRaw = item.getString("lastDate")
            val nextRaw = item.getString("nextDate")
            val daysUntil = exactInt(item, "daysUntil")
            val amountPattern = Regex("^(?:0|[1-9]\\d{0,17})\\.\\d{2}$")
            require(id.matches(Regex("^[0-9a-f]{32}$")) && (!register || ids.add(id))
                && key.isNotBlank() && key.length <= 512 && name.isNotBlank() && name.length <= 500
                && (category == null || category.length <= 64) && type == expectedType && currency == "RUB"
                && amountPattern.matches(amountRaw) && amountPattern.matches(minRaw) && amountPattern.matches(maxRaw)
                && periodCode in setOf("week", "month")
                && (if (periodCode == "week") period in 6..8 else period in 25..35)
                && minInterval in 1..period && maxInterval in period..3650 && occurrences in 3..5000
                && daysUntil in -3650..3650) { "Invalid recurring series" }
            val amount = amountRaw.toBigDecimal()
            val minimum = minRaw.toBigDecimal()
            val maximum = maxRaw.toBigDecimal()
            require(minimum <= amount && amount <= maximum
                && maximum.subtract(minimum) <= amount.multiply(BigDecimal("0.25"))) { "Invalid recurring amount range" }
            val last = runCatching { LocalDate.parse(lastRaw) }.getOrNull()
            val next = runCatching { LocalDate.parse(nextRaw) }.getOrNull()
            require(last != null && next != null && last.plusDays(period.toLong()) == next
                && ChronoUnit.DAYS.between(today, next) == daysUntil.toLong()) { "Invalid recurring dates" }
            return FinanceRecurringSeries(id, key, name, category, type, currency, amountRaw, minRaw, maxRaw,
                periodCode, period, minInterval, maxInterval, occurrences, lastRaw, nextRaw, daysUntil)
        }

        val expenses = (0 until expenseJson.length()).map { parseSeries(expenseJson.getJSONObject(it), "expense") }
        val incomes = (0 until incomeJson.length()).map { parseSeries(incomeJson.getJSONObject(it), "income") }
        val muted = (0 until mutedJson.length()).map { index ->
            val item = mutedJson.getJSONObject(index)
            val type = item.getString("type")
            require(type == "expense" || type == "income") { "Invalid muted recurring series type" }
            parseSeries(item, type)
        }
        val byExpenseId = expenses.associateBy { it.id }
        val expectedDueSoon = expenses.filter { it.daysUntil in 0..3 }
        val dueSoon = (0 until dueJson.length()).map { index ->
            val item = parseSeries(dueJson.getJSONObject(index), "expense", register = false)
            require(byExpenseId[item.id] == item) { "Recurring due-soon item is outside the expense series" }
            item
        }
        val overdue = (0 until overdueJson.length()).map { index ->
            val item = parseSeries(overdueJson.getJSONObject(index), "expense", register = false)
            require(item.daysUntil < 0 && byExpenseId[item.id] == item) { "Recurring overdue item is outside the expense series" }
            item
        }
        require(expectedDueSoon == dueSoon && overdue.map { it.id }.toSet() == expenses.filter { it.daysUntil < 0 }.map { it.id }.toSet()
            && overdue.size == overdue.map { it.id }.toSet().size) { "Invalid recurring warning groups" }
        val nextIncomeJson = if (json.isNull("nextIncome")) null else json.getJSONObject("nextIncome")
        val expectedIncome = incomes.filter { it.daysUntil >= 0 }.minByOrNull { it.daysUntil }
        val nextIncome = nextIncomeJson?.let { parseSeries(it, "income", register = false) }
        require(nextIncome == expectedIncome) { "Invalid next recurring income" }

        val estimatesJson = json.getJSONObject("monthlyExpenseEstimates")
        require(estimatesJson.length() <= 8) { "Invalid recurring currency count" }
        val estimates = estimatesJson.keys().asSequence().associateWith { estimatesJson.getString(it) }
        val expectedByCurrency = expenses.groupBy { it.currency }.mapValues { (_, series) ->
            series.fold(BigDecimal.ZERO) { total, item ->
                val amount = item.amount.toBigDecimal()
                val monthly = if (item.periodCode == "month") amount else amount.multiply(BigDecimal("30"))
                    .divide(BigDecimal(item.periodDays), 12, RoundingMode.HALF_UP)
                total.add(monthly)
            }.setScale(2, RoundingMode.HALF_UP)
        }
        require(estimates.keys == expectedByCurrency.keys
            && expectedByCurrency.all { (currency, expected) ->
                val raw = estimates[currency]
                raw != null && Regex("^(?:0|[1-9]\\d{0,17})\\.\\d{2}$").matches(raw)
                    && raw.toBigDecimal().compareTo(expected) == 0
            }) { "Invalid recurring monthly total" }
        val total = nullableString(json, "monthlyExpenseEstimate")
        val expectedTotal = expectedByCurrency.values.singleOrNull()
        require(if (expectedTotal == null) total == null else total != null && total.toBigDecimalOrNull()?.compareTo(expectedTotal) == 0) {
            "Invalid recurring monthly estimate"
        }
        return FinanceRecurringProjection("recurring.v1", "complete", timezone, asOf, expenses, incomes, dueSoon,
            overdue, nextIncome, total, estimates, muted)
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

    private fun exactLong(json: JSONObject, key: String): Long {
        val value = json.get(key) as? Number ?: throw IllegalArgumentException("Invalid receipt item version")
        return java.math.BigDecimal(value.toString()).longValueExact()
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

    private fun nullableDouble(json: JSONObject, key: String): Double? =
        if (!json.has(key) || json.isNull(key)) null else json.getDouble(key)
}

internal fun isValidProductCatalogQuery(query: String): Boolean {
    var index = 0
    var codePointCount = 0
    while (index < query.length) {
        val codePoint = Character.codePointAt(query, index)
        if (Character.isISOControl(codePoint)) return false
        codePointCount++
        if (codePointCount > 80) return false
        index += Character.charCount(codePoint)
    }
    return true
}
