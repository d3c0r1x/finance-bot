package com.decorix.finance

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

class FinanceApi(context: Context) {
    private val tokens = TokenVault(context)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).build()

    fun hasSession(): Boolean = tokens.read()?.optString("accessToken")?.isNotBlank() == true

    fun saveTokens(accessToken: String, refreshToken: String, expiresAtMillis: Long, idToken: String = "") {
        check(accessToken.isNotBlank()) { "Identity provider returned no access token" }
        tokens.save(JSONObject().put("accessToken", accessToken).put("refreshToken", refreshToken)
            .put("expiresAtMillis", expiresAtMillis).put("idToken", idToken))
    }

    fun clearSession() = tokens.clear()

    fun logout() {
        val session = tokens.read()
        try {
            val refresh = session?.optString("refreshToken").orEmpty()
            if (refresh.isNotBlank()) {
                val request = Request.Builder().url("${BuildConfig.OIDC_REALM_URL}/protocol/openid-connect/revoke")
                    .post(FormBody.Builder().add("client_id", BuildConfig.OIDC_CLIENT_ID)
                        .add("token", refresh).add("token_type_hint", "refresh_token").build()).build()
                client.newCall(request).execute().use { }
            }
        } finally {
            clearSession()
        }
    }

    fun tenants(): List<FinanceTenant> {
        val body = execute("/api/v1/me/tenants", "GET")
        val list = org.json.JSONArray(body)
        return (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            FinanceTenant(item.getString("tenantId"), item.getString("displayName"),
                item.getString("role"), item.optString("timezone", "UTC"))
        }
    }

    fun transactions(tenantId: String): List<String> {
        val body = JSONObject(execute("/api/v1/tenants/$tenantId/transactions?pageSize=50", "GET"))
        val list = body.getJSONArray("items")
        return (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            "${item.getString("occurredAt").take(10)}  ${item.getString("description")}  ${item.getString("amount")} ₽"
        }
    }

    fun budgets(tenantId: String): BudgetOverview = FinanceModels.budgetOverview(
        JSONObject(execute("/api/v1/tenants/$tenantId/budgets", "GET")),
    )

    fun dashboardSummary(tenantId: String): DashboardSummary = FinanceModels.dashboardSummary(
        JSONObject(execute("/api/v1/tenants/$tenantId/summary", "GET")),
    )

    fun report(tenantId: String, period: String, scope: String, month: String, from: String, to: String): FinanceReport {
        val endpoint = if (scope == "family") "family" else "period"
        val query = mutableListOf("period=${android.net.Uri.encode(period)}")
        if (period == "month") query += "month=${android.net.Uri.encode(month)}"
        if (period == "custom") {
            query += "from=${android.net.Uri.encode(from)}"
            query += "to=${android.net.Uri.encode(to)}"
        }
        if (scope != "family") query += "scope=personal"
        return FinanceModels.report(JSONObject(execute(
            "/api/v1/tenants/$tenantId/reports/$endpoint?${query.joinToString("&")}", "GET",
        )))
    }

    fun updateBudget(tenantId: String, budgetKey: String, scope: String, amount: String,
                     period: String, version: Long): BudgetOverview {
        val encodedKey = android.net.Uri.encode(budgetKey)
        val body = JSONObject().put("scope", scope).put("amount", amount.trim().replace(',', '.'))
            .put("period", period).toString()
        return FinanceModels.budgetOverview(JSONObject(execute(
            "/api/v1/tenants/$tenantId/budgets/$encodedKey", "PUT", body,
            UUID.randomUUID().toString(), version,
        )))
    }

    fun resetPersonalBudgets(tenantId: String): BudgetOverview = FinanceModels.budgetOverview(JSONObject(execute(
        "/api/v1/tenants/$tenantId/budgets/personal-overrides", "DELETE", idempotencyKey = UUID.randomUUID().toString(),
    )))

    fun proposeBudget(tenantId: String, monthlyIncome: String): BudgetProposal = FinanceModels.budgetProposal(JSONObject(execute(
        "/api/v1/tenants/$tenantId/budget-proposals", "POST",
        JSONObject().put("monthlyIncome", monthlyIncome.trim().replace(',', '.')).toString(), UUID.randomUUID().toString(),
    )))

    fun proposeBudgetFromHistory(tenantId: String): BudgetProposal = FinanceModels.budgetProposal(JSONObject(execute(
        "/api/v1/tenants/$tenantId/budget-proposals/history", "POST", idempotencyKey = UUID.randomUUID().toString(),
    )))

    fun applyBudgetProposal(tenantId: String, proposalId: String): BudgetOverview = FinanceModels.budgetOverview(JSONObject(execute(
        "/api/v1/tenants/$tenantId/budget-proposals/$proposalId/apply", "POST",
        idempotencyKey = UUID.randomUUID().toString(),
    )))

    fun debts(tenantId: String): List<FinanceDebt> = FinanceModels.debtPage(
        JSONObject(execute("/api/v1/tenants/$tenantId/debts", "GET")),
    )

    fun createDebt(tenantId: String, name: String, openingBalance: String,
                   interestRate: String?, minimumPayment: String): FinanceDebt {
        val data = JSONObject().put("name", name.trim())
            .put("openingBalance", openingBalance.trim().replace(',', '.'))
            .put("interestRate", interestRate?.trim()?.replace(',', '.')?.takeIf(String::isNotEmpty) ?: JSONObject.NULL)
            .put("minimumPayment", minimumPayment.trim().replace(',', '.'))
        return FinanceModels.debt(JSONObject(execute(
            "/api/v1/tenants/$tenantId/debts", "POST", data.toString(), UUID.randomUUID().toString(),
        )))
    }

    fun payDebt(tenantId: String, debtId: String, amount: String, version: Long): FinanceDebt {
        val data = JSONObject().put("amount", amount.trim().replace(',', '.')).put("occurredAt", Instant.now().toString())
        val response = JSONObject(execute("/api/v1/tenants/$tenantId/debts/$debtId/payments", "POST",
            data.toString(), UUID.randomUUID().toString(), version))
        return FinanceModels.debt(response.getJSONObject("debt"))
    }

    fun adjustDebtBalance(tenantId: String, debtId: String, currentBalance: String, version: Long): FinanceDebt {
        val data = JSONObject().put("currentBalance", currentBalance.trim().replace(',', '.')).toString()
        return FinanceModels.debt(JSONObject(execute("/api/v1/tenants/$tenantId/debts/$debtId/balance", "PUT",
            data, UUID.randomUUID().toString(), version)))
    }

    fun debtForecast(tenantId: String, debtId: String): DebtForecast = FinanceModels.debtForecast(JSONObject(
        execute("/api/v1/tenants/$tenantId/debts/$debtId/forecast", "GET"),
    ))

    fun shoppingCandidates(tenantId: String): FinanceShoppingList = FinanceModels.shoppingList(JSONObject(
        execute("/api/v1/tenants/$tenantId/shopping", "GET"),
    ))

    fun markShoppingBought(tenantId: String, productKey: String): FinanceShoppingList = FinanceModels.shoppingList(JSONObject(
        execute("/api/v1/tenants/$tenantId/shopping/${android.net.Uri.encode(productKey)}/bought", "POST", "{}"),
    ))

    fun muteShoppingSuggestion(tenantId: String, productKey: String): FinanceShoppingList = FinanceModels.shoppingList(JSONObject(
        execute("/api/v1/tenants/$tenantId/suggestions/shopping/${android.net.Uri.encode(productKey)}/mute", "PUT", "{}"),
    ))

    fun unmuteShoppingSuggestion(tenantId: String, productKey: String): FinanceShoppingList = FinanceModels.shoppingList(JSONObject(
        execute("/api/v1/tenants/$tenantId/suggestions/shopping/${android.net.Uri.encode(productKey)}/mute", "DELETE", "{}"),
    ))

    fun memberProfile(tenantId: String): FinanceMemberProfile = FinanceModels.memberProfile(JSONObject(
        execute("/api/v1/tenants/$tenantId/profile/me", "GET"),
    ))

    fun notificationPreferences(tenantId: String): FinanceNotificationPreferences = FinanceModels.notificationPreferences(JSONObject(
        execute("/api/v1/tenants/$tenantId/notification-preferences", "GET"),
    ))

    fun updateNotificationPreferences(tenantId: String, preferences: FinanceNotificationPreferences): FinanceNotificationPreferences {
        val data = JSONObject().put("language", preferences.language).put("dailyEnabled", preferences.dailyEnabled)
            .put("dailyLocalTime", preferences.dailyLocalTime).put("weeklyEnabled", preferences.weeklyEnabled)
            .put("weeklyDayOfWeek", preferences.weeklyDayOfWeek).put("weeklyLocalTime", preferences.weeklyLocalTime)
            .put("quietHoursStart", preferences.quietHoursStart ?: JSONObject.NULL)
            .put("quietHoursEnd", preferences.quietHoursEnd ?: JSONObject.NULL).toString()
        return FinanceModels.notificationPreferences(JSONObject(execute(
            "/api/v1/tenants/$tenantId/notification-preferences", "PATCH", data,
            ifMatchVersion = preferences.version,
        )))
    }

    fun createTelegramLinkCode(): FinanceTelegramLinkCode = FinanceModels.telegramLinkCode(JSONObject(
        execute("/api/v1/me/telegram-link", "POST"),
    ))

    fun updateMemberProfile(tenantId: String, name: String, plannedIncome: String?): FinanceMemberProfile {
        require(name.isNotBlank() && name.trim().length <= 120) { "Enter your name" }
        val income = normalizeOptionalIncome(plannedIncome)
        val data = JSONObject().put("displayName", name.trim())
            .put("plannedIncome", income?.let(::BigDecimal) ?: JSONObject.NULL)
            .put("onboardingState", "complete").toString()
        return FinanceModels.memberProfile(JSONObject(execute(
            "/api/v1/tenants/$tenantId/profile/me", "PATCH", data,
        )))
    }

    fun createTenant(name: String, memberName: String, plannedIncome: String?) {
        require(name.isNotBlank() && name.trim().length <= 120) { "Enter workspace name" }
        require(memberName.isNotBlank() && memberName.trim().length <= 120) { "Enter your name" }
        val income = normalizeOptionalIncome(plannedIncome)
        val data = JSONObject().put("displayName", name.trim())
            .put("memberDisplayName", memberName.trim()).put("timezone", TimeZone.getDefault().id)
            .put("plannedIncome", income?.let(::BigDecimal) ?: JSONObject.NULL)
        execute("/api/v1/tenants", "POST", data.toString(), idempotencyKey = UUID.randomUUID().toString())
    }

    private fun normalizeOptionalIncome(value: String?): String? {
        val input = value?.trim()?.replace(',', '.')?.takeIf(String::isNotEmpty) ?: return null
        require(Regex("^(?:0\\.(?:[0-9]?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\\.[0-9]{1,2})?)$").matches(input)) {
            "Enter a positive monthly income with up to two decimals"
        }
        return BigDecimal(input).setScale(2).toPlainString()
    }

    fun createTransactionDraft(tenantId: String, text: String, idempotencyKey: String): FinanceTransactionDraft {
        require(text.isNotBlank() && text.trim().length <= 500) { "Enter a transaction in up to 500 characters" }
        val response = execute("/api/v1/tenants/$tenantId/transaction-drafts", "POST",
            JSONObject().put("text", text.trim()).toString(), idempotencyKey)
        return FinanceModels.transactionDraft(JSONObject(response))
    }

    fun updateTransactionDraft(tenantId: String, edit: TransactionDraftEdit): FinanceTransactionDraft {
        val normalized = edit.amount.trim().replace(',', '.')
        require(Regex("^(?:0\\.(?:[0-9]?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\\.[0-9]{1,2})?)$")
            .matches(normalized)) { "Enter a positive amount with up to two decimals" }
        require(edit.categoryCode.isNotBlank() && edit.categoryCode.length <= 64) { "Enter a category" }
        require(edit.description.isNotBlank() && edit.description.length <= 500) { "Enter a description" }
        val data = JSONObject().put("type", edit.type).put("amount", normalized).put("currency", "RUB")
            .put("categoryCode", edit.categoryCode.trim())
            .put("subcategoryCode", edit.subcategoryCode?.trim()?.takeIf(String::isNotEmpty) ?: JSONObject.NULL)
            .put("description", edit.description.trim()).put("occurredAt", edit.occurredAt)
            .put("debtId", edit.debtId ?: JSONObject.NULL).toString()
        val response = execute("/api/v1/tenants/$tenantId/transaction-drafts/${edit.id}", "PATCH", data,
            ifMatchVersion = edit.version)
        return FinanceModels.transactionDraft(JSONObject(response))
    }

    fun confirmTransactionDraft(tenantId: String, draft: FinanceTransactionDraft): List<FinanceBudgetAlert> {
        val response = JSONObject(execute("/api/v1/tenants/$tenantId/transaction-drafts/${draft.id}/confirm", "POST",
            idempotencyKey = "android-draft-confirm-${draft.id}", ifMatchVersion = draft.version))
        return FinanceModels.budgetAlerts(response.optJSONArray("budgetAlerts"))
    }

    fun cancelTransactionDraft(tenantId: String, draftId: String, version: Long) {
        execute("/api/v1/tenants/$tenantId/transaction-drafts/$draftId", "DELETE",
            ifMatchVersion = version)
    }

    private fun execute(path: String, method: String, body: String? = null, idempotencyKey: String? = null,
                        ifMatchVersion: Long? = null): String {
        val original = tokens.read()
        var accessToken = original?.optString("accessToken")?.takeIf(String::isNotBlank)
        val expiresAt = original?.optLong("expiresAtMillis", 0L) ?: 0L
        if (accessToken != null && expiresAt > 0 && expiresAt <= System.currentTimeMillis() + 30_000) {
            accessToken = refresh(original ?: throw ApiFailure(401, "sign_in_required"))
        }
        var response = perform(path, method, body, idempotencyKey, ifMatchVersion, accessToken)
        if (response.code == 401 && accessToken != null) {
            response.close()
            accessToken = refresh(tokens.read() ?: throw ApiFailure(401, "sign_in_required"))
            response = perform(path, method, body, idempotencyKey, ifMatchVersion, accessToken)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("detail").ifBlank { JSONObject(text).optString("code") } }.getOrNull()
                if (BuildConfig.DEBUG) android.util.Log.w("FinanceApi",
                    "$method $path returned HTTP ${it.code}: ${detail?.takeIf(String::isNotBlank) ?: "no detail"}")
                throw ApiFailure(it.code, detail?.takeIf(String::isNotBlank) ?: "Request failed (${it.code})")
            }
            return text
        }
    }

    private fun refresh(session: JSONObject): String {
        val refreshToken = session.optString("refreshToken").takeIf(String::isNotBlank)
            ?: throw ApiFailure(401, "sign_in_required")
        val request = Request.Builder().url("${BuildConfig.OIDC_REALM_URL}/protocol/openid-connect/token")
            .post(FormBody.Builder().add("grant_type", "refresh_token")
                .add("client_id", BuildConfig.OIDC_CLIENT_ID).add("refresh_token", refreshToken).build()).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val invalidGrant = response.code == 400 && runCatching { JSONObject(body).optString("error") == "invalid_grant" }.getOrDefault(false)
                if (response.code == 401 || invalidGrant) {
                    clearSession()
                    throw ApiFailure(401, "sign_in_required")
                }
                throw ApiFailure(response.code, "Identity provider unavailable (${response.code})")
            }
            val result = JSONObject(body)
            val nextAccess = result.getString("access_token")
            val nextRefresh = result.optString("refresh_token").ifBlank { refreshToken }
            val nextId = result.optString("id_token").ifBlank { session.optString("idToken") }
            val expiry = System.currentTimeMillis() + result.optLong("expires_in", 300L) * 1000L
            tokens.save(JSONObject().put("accessToken", nextAccess).put("refreshToken", nextRefresh)
                .put("idToken", nextId).put("expiresAtMillis", expiry))
            return nextAccess
        }
    }

    private fun perform(path: String, method: String, body: String?, idempotencyKey: String?,
                        ifMatchVersion: Long?, accessToken: String?) = run {
        val builder = Request.Builder().url(BuildConfig.API_BASE_URL + path)
        val requestBody = body?.toRequestBody("application/json".toMediaType())
        when (method) {
            "POST" -> builder.post(requestBody ?: "{}".toRequestBody("application/json".toMediaType()))
            "PATCH" -> builder.patch(requestBody ?: "{}".toRequestBody("application/json".toMediaType()))
            "PUT" -> builder.put(requestBody ?: "{}".toRequestBody("application/json".toMediaType()))
            "DELETE" -> if (requestBody == null) builder.delete() else builder.delete(requestBody)
            else -> builder.get()
        }
        accessToken?.let { builder.header("Authorization", "Bearer $it") }
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        ifMatchVersion?.let { builder.header("If-Match", "\"$it\"") }
        client.newCall(builder.build()).execute()
    }
}

class ApiFailure(val status: Int, message: String) : IOException(message)
