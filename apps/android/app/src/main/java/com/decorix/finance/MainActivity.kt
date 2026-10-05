package com.decorix.finance

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.connectivity.DefaultConnectionBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.security.MessageDigest
import java.time.YearMonth
import java.time.ZoneId
import java.time.LocalDate
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var authService: AuthorizationService
    private val api by lazy { FinanceApi(this) }
    private val executor = Executors.newSingleThreadExecutor()
    private var ui by mutableStateOf(FinanceUiState())
    private var language by mutableStateOf("ru")
    private val loginResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val response = net.openid.appauth.AuthorizationResponse.fromIntent(result.data ?: Intent())
        val error = net.openid.appauth.AuthorizationException.fromIntent(result.data ?: Intent())
        if (error != null) ui = ui.copy(error = error.errorDescription ?: "Sign in cancelled")
        else if (response != null) exchangeCode(response)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val connectionBuilder = if (BuildConfig.DEBUG) DevelopmentConnectionBuilder
            else DefaultConnectionBuilder.INSTANCE
        authService = AuthorizationService(this,
            AppAuthConfiguration.Builder().setConnectionBuilder(connectionBuilder).build())
        if (api.hasSession()) {
            ui = ui.copy(authenticated = true)
            loadTenants()
        }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    FinanceScreen(ui, language, onLanguage = { language = it }, onLogin = ::startLogin,
                        onRefresh = ::loadTenants, onCreate = ::createTenantAndRefresh,
                        onProfileSave = ::saveMemberProfile, onCreateTelegramLink = ::createTelegramLinkCode,
                        onNotificationPreferencesSave = ::saveNotificationPreferences,
                        onCreateDraft = ::createTransactionDraft, onUpdateDraft = ::updateTransactionDraft,
                        onConfirmDraft = ::confirmTransactionDraft, onCancelDraft = ::cancelTransactionDraft,
                        onLogout = ::logout, onBudgetUpdate = ::updateBudget, onBudgetReset = ::resetPersonalBudgets,
                        onBudgetProposal = ::createBudgetProposal, onBudgetApply = ::applyBudgetProposal,
                        onDebtCreate = ::createDebt, onDebtPay = ::payDebt, onDebtAdjust = ::adjustDebt,
                        onDebtForecast = ::loadDebtForecast, onReportLoad = ::loadReport,
                        onShoppingLoad = ::loadShoppingCandidates)
                }
            }
        }
    }

    private fun startLogin() {
        executor.execute {
            runCatching {
                val discovery = JSONObject(OkHttpClient().newCall(Request.Builder()
                    .url("${BuildConfig.OIDC_REALM_URL}/.well-known/openid-configuration").build()).execute().use {
                    if (!it.isSuccessful) error("Identity provider unavailable")
                    it.body?.string() ?: error("Identity metadata is empty")
                })
                fun endpoint(name: String): Uri = Uri.parse(discovery.getString(name))
                val config = AuthorizationServiceConfiguration(endpoint("authorization_endpoint"), endpoint("token_endpoint"),
                    endpoint("revocation_endpoint"), endpoint("end_session_endpoint"))
                val redirect = Uri.parse("com.decorix.finance:/oauth2redirect")
                val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
                val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
                AuthorizationRequest.Builder(config, BuildConfig.OIDC_CLIENT_ID, ResponseTypeValues.CODE, redirect)
                    .setScopes("openid")
                    .setCodeVerifier(verifier, challenge, "S256").build()
            }.onSuccess { request -> runOnUiThread { loginResult.launch(authService.getAuthorizationRequestIntent(request)) } }
                .onFailure { error -> runOnUiThread { ui = ui.copy(error = error.message ?: "Identity provider unavailable") } }
        }
    }

    private fun exchangeCode(response: net.openid.appauth.AuthorizationResponse) {
        val request = response.createTokenExchangeRequest()
        authService.performTokenRequest(request) { tokenResponse, exception ->
            if (exception != null || tokenResponse == null) {
                ui = ui.copy(error = exception?.errorDescription ?: "Sign in failed")
            } else {
                api.saveTokens(tokenResponse.accessToken.orEmpty(), tokenResponse.refreshToken.orEmpty(),
                    tokenResponse.accessTokenExpirationTime ?: 0L, tokenResponse.idToken.orEmpty())
                ui = ui.copy(authenticated = true)
                loadTenants()
            }
        }
    }

    private fun loadTenants() = runApi { workspaceData() }
    private fun createTenantAndRefresh(name: String, memberName: String, plannedIncome: String?) = runApi {
        api.createTenant(name, memberName, plannedIncome)
        workspaceData()
    }
    private fun saveMemberProfile(name: String, plannedIncome: String?) = runApi {
        val profile = api.updateMemberProfile(activeTenantId(), name, plannedIncome)
        workspaceData(memberProfile = profile)
    }
    private fun saveNotificationPreferences(preferences: FinanceNotificationPreferences) = runApi {
        val updated = api.updateNotificationPreferences(activeTenantId(), preferences)
        workspaceData(notificationPreferences = updated)
    }
    private fun createTelegramLinkCode() {
        ui = ui.copy(busy = true, error = null, telegramLinkCode = null)
        executor.execute {
            runCatching { api.createTelegramLinkCode() }
                .onSuccess { code -> ui = ui.copy(busy = false, telegramLinkCode = code) }
                .onFailure { error -> ui = ui.copy(busy = false, error = error.message ?: "Request failed") }
        }
    }
    private fun createTransactionDraft(text: String, idempotencyKey: String) = runApi {
        val tenantId = activeTenantId()
        workspaceData(draft = api.createTransactionDraft(tenantId, text, idempotencyKey))
    }

    private fun updateTransactionDraft(edit: TransactionDraftEdit) = runApi {
        val updated = api.updateTransactionDraft(activeTenantId(), edit)
        workspaceData(draft = updated)
    }

    private fun confirmTransactionDraft(id: String, version: Long) = runApi {
        val draft = ui.transactionDraft?.takeIf { it.id == id && it.version == version }
            ?: error("Draft changed. Refresh and review it again.")
        val alerts = api.confirmTransactionDraft(activeTenantId(), draft)
        workspaceData(draft = null, budgetAlerts = alerts)
    }

    private fun cancelTransactionDraft(id: String, version: Long) = runApi {
        api.cancelTransactionDraft(activeTenantId(), id, version)
        workspaceData(draft = null)
    }

    private fun workspaceData(proposal: BudgetProposal? = ui.budgetProposal,
                              draft: FinanceTransactionDraft? = ui.transactionDraft,
                              memberProfile: FinanceMemberProfile? = null,
                              notificationPreferences: FinanceNotificationPreferences? = null,
                              budgetAlerts: List<FinanceBudgetAlert> = emptyList()): FinanceWorkspaceSnapshot {
        val tenants = api.tenants()
        val tenant = tenants.firstOrNull()
        return if (tenant == null) FinanceWorkspaceSnapshot(tenants, emptyList(), null, emptyList(), proposal, null, null,
            draft, null, null, budgetAlerts)
        else {
            val month = YearMonth.now(ZoneId.of(tenant.timezone)).toString()
            FinanceWorkspaceSnapshot(tenants, api.transactions(tenant.id), api.budgets(tenant.id), api.debts(tenant.id),
                proposal, api.dashboardSummary(tenant.id), api.report(tenant.id, "month", "personal", month, "", ""),
                draft, memberProfile ?: api.memberProfile(tenant.id),
                notificationPreferences ?: api.notificationPreferences(tenant.id), budgetAlerts)
        }
    }

    private fun runApi(action: () -> FinanceWorkspaceSnapshot) {
        ui = ui.copy(busy = true, error = null)
        executor.execute {
            runCatching(action).onSuccess { snapshot -> ui = ui.copy(busy = false, tenants = snapshot.tenants,
                transactions = snapshot.transactions, budgets = snapshot.budgets, debts = snapshot.debts,
                budgetProposal = snapshot.proposal, dashboardSummary = snapshot.dashboardSummary,
                report = snapshot.report, transactionDraft = snapshot.transactionDraft,
                memberProfile = snapshot.memberProfile, notificationPreferences = snapshot.notificationPreferences,
                budgetAlerts = snapshot.budgetAlerts, error = null) }
                .onFailure { error ->
                    if (error is ApiFailure && error.status == 401) ui = FinanceUiState(error = "Sign in again")
                    else ui = ui.copy(busy = false, error = error.message ?: "Request failed")
                }
        }
    }

    private fun activeTenantId(): String = ui.tenants.firstOrNull()?.id ?: error("Create a workspace first")

    private fun updateBudget(key: String, scope: String, amount: String, period: String, version: Long) = runApi {
        val tenantId = activeTenantId()
        api.updateBudget(tenantId, key, scope, amount, period, version)
        workspaceData(proposal = null)
    }

    private fun resetPersonalBudgets() = runApi {
        api.resetPersonalBudgets(activeTenantId())
        workspaceData(proposal = null)
    }

    private fun createBudgetProposal(monthlyIncome: String?) = runApi {
        val tenantId = activeTenantId()
        val proposal = if (monthlyIncome == null) api.proposeBudgetFromHistory(tenantId)
        else api.proposeBudget(tenantId, monthlyIncome)
        workspaceData(proposal)
    }

    private fun applyBudgetProposal(proposalId: String) = runApi {
        api.applyBudgetProposal(activeTenantId(), proposalId)
        workspaceData(proposal = null)
    }

    private fun createDebt(name: String, openingBalance: String, interestRate: String?, minimumPayment: String) = runApi {
        val tenantId = activeTenantId()
        api.createDebt(tenantId, name, openingBalance, interestRate, minimumPayment)
        workspaceData()
    }

    private fun payDebt(debtId: String, amount: String, version: Long) = runApi {
        val tenantId = activeTenantId()
        api.payDebt(tenantId, debtId, amount, version)
        workspaceData()
    }

    private fun adjustDebt(debtId: String, balance: String, version: Long) = runApi {
        val tenantId = activeTenantId()
        api.adjustDebtBalance(tenantId, debtId, balance, version)
        workspaceData()
    }

    private fun loadDebtForecast(debtId: String) {
        val tenantId = activeTenantId()
        ui = ui.copy(busy = true, error = null)
        executor.execute {
            runCatching { api.debtForecast(tenantId, debtId) }
                .onSuccess { forecast -> ui = ui.copy(busy = false, debtForecasts = ui.debtForecasts + (debtId to forecast)) }
                .onFailure { error -> ui = ui.copy(busy = false, error = error.message ?: "Request failed") }
        }
    }

    private fun loadReport(period: String, scope: String, month: String, from: String, to: String) {
        val tenantId = activeTenantId()
        ui = ui.copy(busy = true, error = null)
        executor.execute {
            runCatching { api.report(tenantId, period, scope, month, from, to) }
                .onSuccess { report -> ui = ui.copy(busy = false, report = report) }
                .onFailure { error ->
                    if (error is ApiFailure && error.status == 401) ui = FinanceUiState(error = "Sign in again")
                    else ui = ui.copy(busy = false, error = error.message ?: "Request failed")
                }
        }
    }

    private fun loadShoppingCandidates() {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        ui = ui.copy(shoppingLoading = true, shoppingError = null)
        executor.execute {
            runCatching { api.shoppingCandidates(tenantId) }
                .onSuccess { shopping ->
                    if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(shoppingLoading = false, shoppingList = shopping, shoppingError = null)
                    }
                }
                .onFailure { error ->
                    if (error is ApiFailure && error.status == 401) ui = FinanceUiState(error = "Sign in again")
                    else if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(shoppingLoading = false, shoppingError = error.message ?: "Request failed")
                    }
                }
        }
    }

    private fun logout() {
        ui = ui.copy(busy = true, error = null)
        executor.execute {
            runCatching { api.logout() }
            runOnUiThread { ui = FinanceUiState() }
        }
    }

    override fun onDestroy() {
        authService.dispose()
        executor.shutdownNow()
        super.onDestroy()
    }
}

data class FinanceUiState(
    val authenticated: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val tenants: List<FinanceTenant> = emptyList(),
    val transactions: List<String> = emptyList(),
    val budgets: BudgetOverview? = null,
    val debts: List<FinanceDebt> = emptyList(),
    val budgetProposal: BudgetProposal? = null,
    val debtForecasts: Map<String, DebtForecast> = emptyMap(),
    val dashboardSummary: DashboardSummary? = null,
    val report: FinanceReport? = null,
    val transactionDraft: FinanceTransactionDraft? = null,
    val memberProfile: FinanceMemberProfile? = null,
    val telegramLinkCode: FinanceTelegramLinkCode? = null,
    val notificationPreferences: FinanceNotificationPreferences? = null,
    val budgetAlerts: List<FinanceBudgetAlert> = emptyList(),
    val shoppingList: FinanceShoppingList? = null,
    val shoppingLoading: Boolean = false,
    val shoppingError: String? = null,
)

private data class FinanceWorkspaceSnapshot(
    val tenants: List<FinanceTenant>,
    val transactions: List<String>,
    val budgets: BudgetOverview?,
    val debts: List<FinanceDebt>,
    val proposal: BudgetProposal?,
    val dashboardSummary: DashboardSummary?,
    val report: FinanceReport?,
    val transactionDraft: FinanceTransactionDraft?,
    val memberProfile: FinanceMemberProfile?,
    val notificationPreferences: FinanceNotificationPreferences?,
    val budgetAlerts: List<FinanceBudgetAlert>,
)

data class FinanceTenant(val id: String, val name: String, val role: String, val timezone: String)

@androidx.compose.runtime.Composable
internal fun FinanceScreen(state: FinanceUiState, language: String, onLanguage: (String) -> Unit,
                          onLogin: () -> Unit, onRefresh: () -> Unit,
                          onCreate: (String, String, String?) -> Unit, onProfileSave: (String, String?) -> Unit,
                          onCreateDraft: (String, String) -> Unit,
                          onUpdateDraft: (TransactionDraftEdit) -> Unit,
                          onConfirmDraft: (String, Long) -> Unit,
                          onCancelDraft: (String, Long) -> Unit,
                          onLogout: () -> Unit,
                          onBudgetUpdate: (String, String, String, String, Long) -> Unit,
                          onBudgetReset: () -> Unit, onBudgetProposal: (String?) -> Unit,
                          onBudgetApply: (String) -> Unit,
                          onDebtCreate: (String, String, String?, String) -> Unit,
                          onDebtPay: (String, String, Long) -> Unit,
                          onDebtAdjust: (String, String, Long) -> Unit,
                          onDebtForecast: (String) -> Unit,
                          onReportLoad: (String, String, String, String, String) -> Unit,
                          onCreateTelegramLink: () -> Unit = {},
                          onNotificationPreferencesSave: (FinanceNotificationPreferences) -> Unit = {},
                          onShoppingLoad: () -> Unit = {}) {
    val russian = language == "ru"
    var workspace by androidx.compose.runtime.remember { mutableStateOf("") }
    var memberName by androidx.compose.runtime.remember { mutableStateOf("") }
    var plannedIncome by androidx.compose.runtime.remember { mutableStateOf("") }
    var transactionText by androidx.compose.runtime.remember { mutableStateOf("") }
    var draftCreateKey by androidx.compose.runtime.remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var activeScreen by androidx.compose.runtime.remember { mutableStateOf("overview") }
    val canWriteTransactions = state.tenants.firstOrNull()?.role != "viewer"
    var previousDraftId by androidx.compose.runtime.remember { mutableStateOf(state.transactionDraft?.id) }
    LaunchedEffect(state.transactionDraft?.id) {
        if (previousDraftId != null && state.transactionDraft == null) {
            transactionText = ""
            draftCreateKey = java.util.UUID.randomUUID().toString()
        }
        previousDraftId = state.transactionDraft?.id
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("FINANCE", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onLanguage("ru") }) { Text("RU") }
            TextButton(onClick = { onLanguage("en") }) { Text("EN") }
        }
        state.budgetAlerts.forEach { alert ->
            val isTotal = alert.budgetKey == "__total__"
            val label = when {
                isTotal && russian -> "Общий лимит"
                isTotal -> "Total limit"
                else -> alert.budgetKey
            }
            val threshold = when (alert.threshold) {
                "near" -> if (russian) "Почти достигнут" else "Near limit"
                else -> if (russian) "Лимит исчерпан" else "Limit reached"
            }
            Text("${if (alert.threshold == "near") "⚠️" else "🚨"} $threshold: $label · ${alert.spent} / ${alert.limit} RUB",
                color = if (alert.threshold == "near") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
        }
        if (!state.authenticated) {
            Text(if (russian) "Финансы под контролем" else "Your finances, clearly", style = MaterialTheme.typography.headlineMedium)
            Text(if (russian) "Войдите через защищённый аккаунт Finance." else "Sign in with your secure Finance account.")
            Button(onClick = onLogin) { Text(if (russian) "Войти" else "Sign in") }
        } else {
            Row(Modifier.fillMaxWidth()) {
                Text(if (russian) "Пространства" else "Workspaces", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text(if (russian) "Обновить" else "Refresh") }
                TextButton(onClick = onLogout) { Text(if (russian) "Выйти" else "Sign out") }
            }
            if (state.tenants.isEmpty()) {
                OutlinedTextField(workspace, { workspace = it }, label = { Text(if (russian) "Название пространства" else "Workspace name") })
                OutlinedTextField(memberName, { memberName = it }, label = { Text(if (russian) "Ваше имя" else "Your name") })
                OutlinedTextField(plannedIncome, { plannedIncome = it }, singleLine = true,
                    label = { Text(if (russian) "Плановый доход в месяц, ₽" else "Planned monthly income, RUB") })
                TextButton(onClick = { plannedIncome = "" }, enabled = !state.busy) {
                    Text(if (russian) "Пропустить доход" else "Skip income")
                }
                val normalizedIncome = plannedIncome.trim().replace(',', '.')
                val validIncome = normalizedIncome.isBlank() || Regex("^(?:0\\.(?:[0-9]?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\\.[0-9]{1,2})?)$")
                    .matches(normalizedIncome)
                Button(onClick = { onCreate(workspace, memberName, normalizedIncomeInput(plannedIncome)) },
                    enabled = !state.busy && workspace.isNotBlank() && memberName.isNotBlank() && validIncome) {
                    Text(if (russian) "Создать" else "Create")
                }
            } else {
                Text(state.tenants.first().name, style = MaterialTheme.typography.headlineSmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("overview", "transactions", "shopping", "budgets", "debts", "reports", "profile").forEach { screen ->
                        TextButton(onClick = {
                            activeScreen = screen
                            if (screen == "shopping" && state.shoppingList == null && !state.shoppingLoading) onShoppingLoad()
                        }) {
                            Text(when (screen) {
                                "overview" -> if (russian) "Обзор" else "Overview"
                                "shopping" -> if (russian) "Покупки" else "Shopping"
                                "budgets" -> if (russian) "Бюджеты" else "Budgets"
                                "debts" -> if (russian) "Долги" else "Debts"
                                "reports" -> if (russian) "Отчёты" else "Reports"
                                "profile" -> if (russian) "Профиль" else "Profile"
                                else -> if (russian) "Операции" else "Transactions"
                            })
                        }
                    }
                }
                when (activeScreen) {
                    "overview" -> DashboardScreen(state, language)
                    "shopping" -> ShoppingScreen(Modifier.weight(1f), state, language, onShoppingLoad)
                    "budgets" -> BudgetScreen(state, language, onBudgetUpdate, onBudgetReset, onBudgetProposal, onBudgetApply)
                    "debts" -> DebtScreen(state, language, onDebtCreate, onDebtPay, onDebtAdjust, onDebtForecast)
                    "reports" -> ReportScreen(state, language, onReportLoad)
                    "profile" -> ProfileScreen(Modifier.weight(1f), state, language, onProfileSave, onCreateTelegramLink,
                        onNotificationPreferencesSave,
                        onBack = { activeScreen = "overview" })
                    else -> {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                val draft = state.transactionDraft?.takeIf { it.state == "pending" }
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (draft == null) {
                                            Text(if (russian) "Новая операция" else "New transaction",
                                                style = MaterialTheme.typography.titleMedium)
                                            OutlinedTextField(transactionText, { transactionText = it }, enabled = canWriteTransactions,
                                                label = { Text(if (russian) "Опишите операцию" else "Describe the transaction") })
                                            Button(onClick = { onCreateDraft(transactionText, draftCreateKey) }, enabled = canWriteTransactions &&
                                                !state.busy && transactionText.isNotBlank()) {
                                                Text(if (russian) "Разобрать текст" else "Review text")
                                            }
                                        } else {
                                            TransactionDraftEditor(draft, state.debts, state.busy, russian,
                                                onUpdateDraft, onConfirmDraft, onCancelDraft)
                                        }
                                    }
                                }
                            }
                            item {
                                Text(if (russian) "Недавние операции" else "Recent transactions",
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            items(state.transactions) { operation ->
                                Card(Modifier.fillMaxWidth()) { Text(operation, Modifier.padding(14.dp)) }
                            }
                        }
                    }
                }
            }
        }
        if (state.busy) androidx.compose.material3.CircularProgressIndicator()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@androidx.compose.runtime.Composable
private fun ShoppingScreen(modifier: Modifier, state: FinanceUiState, language: String, onRetry: () -> Unit) {
    val russian = language == "ru"
    val shopping = state.shoppingList
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(if (russian) "Пора купить" else "Shopping list", style = MaterialTheme.typography.titleLarge)
        }
        when {
            state.shoppingLoading -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            state.shoppingError != null -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (russian) "Список покупок временно недоступен." else "Shopping suggestions are temporarily unavailable.")
                    Button(onClick = onRetry) { Text(if (russian) "Повторить" else "Retry") }
                }
            }
            shopping == null -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            shopping.candidates.isEmpty() -> item {
                Text(if (russian) "Пока нечего добавить: нужны минимум три покупки с интервалами от трёх дней."
                    else "Nothing to suggest yet: at least three purchases with gaps of three days or more are needed.")
            }
            else -> shopping.candidates.forEach { candidate ->
                item {
                    val due = when {
                        candidate.daysUntilDue < 0 -> if (russian) "Просрочено на ${-candidate.daysUntilDue} дн."
                            else "${-candidate.daysUntilDue} days overdue"
                        candidate.daysUntilDue == 0 -> if (russian) "Пора" else "Due now"
                        else -> if (russian) "Через ${candidate.daysUntilDue} дн." else "In ${candidate.daysUntilDue} days"
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(candidate.productName, style = MaterialTheme.typography.titleMedium)
                            Text(due)
                            Text(if (russian) "Медиана: раз в ${candidate.medianIntervalDays} дн. · ${candidate.purchaseCount} покупки"
                                else "Median: every ${candidate.medianIntervalDays} days · ${candidate.purchaseCount} purchases")
                            Text(if (russian) "Оценка: ${candidate.estimatedCost} ₽" else "Estimate: ${candidate.estimatedCost} RUB")
                            Text(if (russian) "Последняя покупка: ${candidate.lastPurchasedAt.take(10)}"
                                else "Last purchased: ${candidate.lastPurchasedAt.take(10)}")
                        }
                    }
                }
            }
        }
        if (shopping != null && state.shoppingError == null) {
            item {
                Text(if (russian) "Оценка списка: ${shopping.estimatedListCost} ₽"
                    else "Estimated list cost: ${shopping.estimatedListCost} RUB",
                    style = MaterialTheme.typography.titleMedium)
            }
            item {
                Text(if (russian) "Это подсказка по чекам, не учёт запасов."
                    else "Not home inventory: suggestions use your confirmed receipt rhythm.")
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ProfileScreen(modifier: Modifier, state: FinanceUiState, language: String,
                          onSave: (String, String?) -> Unit, onCreateTelegramLink: () -> Unit,
                          onNotificationPreferencesSave: (FinanceNotificationPreferences) -> Unit, onBack: () -> Unit) {
    val russian = language == "ru"
    val profile = state.memberProfile
    if (profile == null) {
        Text(if (russian) "Профиль пока не загружен" else "Profile is not loaded")
        return
    }
    var name by androidx.compose.runtime.remember(profile.displayName) { mutableStateOf(profile.displayName) }
    var income by androidx.compose.runtime.remember(profile.plannedIncome) {
        mutableStateOf(profile.plannedIncome.orEmpty())
    }
    val notificationPreferences = state.notificationPreferences
    var digestLanguage by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.language ?: "ru")
    }
    var dailyEnabled by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.dailyEnabled ?: true)
    }
    var dailyTime by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.dailyLocalTime ?: "21:00")
    }
    var weeklyEnabled by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.weeklyEnabled ?: true)
    }
    var weeklyDay by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.weeklyDayOfWeek ?: 7)
    }
    var weeklyTime by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.weeklyLocalTime ?: "19:00")
    }
    var quietStart by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.quietHoursStart.orEmpty())
    }
    var quietEnd by androidx.compose.runtime.remember(notificationPreferences?.version) {
        mutableStateOf(notificationPreferences?.quietHoursEnd.orEmpty())
    }
    var showWeekdayMenu by androidx.compose.runtime.remember { mutableStateOf(false) }
    val normalizedIncome = income.trim().replace(',', '.')
    val validIncome = normalizedIncome.isBlank() || Regex("^(?:0\\.(?:[0-9]?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\\.[0-9]{1,2})?)$")
        .matches(normalizedIncome)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (russian) "Мой профиль" else "My profile", style = MaterialTheme.typography.titleLarge)
        if (profile.onboardingState == "started") {
            Text(if (russian) "Доход можно добавить сейчас или указать позже." else "Add planned income now or leave it for later.")
        }
        OutlinedTextField(name, { name = it }, enabled = !state.busy, singleLine = true,
            label = { Text(if (russian) "Ваше имя" else "Your name") })
        OutlinedTextField(income, { income = it }, enabled = !state.busy, singleLine = true,
            label = { Text(if (russian) "Плановый доход в месяц, ₽" else "Planned monthly income, RUB") })
        TextButton(onClick = { income = "" }, enabled = !state.busy) {
            Text(if (russian) "Пропустить доход" else "Skip income")
        }
        Text(if (russian) "Часовой пояс: ${profile.timezone} · Валюта: ${profile.currency}"
            else "Timezone: ${profile.timezone} · Currency: ${profile.currency}")
        Text(if (russian) "Откройте личный чат с ботом, отправьте /link и код ниже. Код действует 10 минут."
            else "Open a private chat with the bot, then send /link and the code below. Code expires in 10 minutes.")
        state.telegramLinkCode?.let { Text(if (russian) "Код: ${it.code}" else "Code: ${it.code}") }
        TextButton(onClick = onCreateTelegramLink, enabled = !state.busy) {
            Text(if (russian) "Подключить Telegram" else "Connect Telegram")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(name.trim(), normalizedIncomeInput(income)) },
                enabled = !state.busy && name.isNotBlank() && validIncome) {
                Text(if (russian) "Сохранить профиль" else "Save profile")
            }
            TextButton(onClick = onBack) { Text(if (russian) "Назад" else "Back") }
        }
        Text(if (russian) "Сводки в Telegram" else "Telegram digests", style = MaterialTheme.typography.titleMedium)
        if (notificationPreferences == null) {
            Text(if (russian) "Настройки сводок загружаются…" else "Digest settings are loading…")
        } else {
            Text(if (notificationPreferences.telegramLinked) {
                if (russian) "Telegram подключён" else "Telegram is connected"
            } else {
                if (russian) "Сначала подключите Telegram, чтобы получать сводки." else "Connect Telegram to receive digests."
            })
            Text(if (russian) "Часовой пояс: ${notificationPreferences.timezone}"
                else "Time zone: ${notificationPreferences.timezone}")
            Text(if (russian) "Язык сводки" else "Digest language")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { digestLanguage = "ru" }, enabled = !state.busy) { Text("Русский") }
                TextButton(onClick = { digestLanguage = "en" }, enabled = !state.busy) { Text("English") }
            }
            Row {
                Checkbox(checked = dailyEnabled, onCheckedChange = { dailyEnabled = it }, enabled = !state.busy)
                Text(if (russian) "Ежедневная сводка" else "Daily digest", Modifier.padding(top = 12.dp))
            }
            OutlinedTextField(dailyTime, { dailyTime = it }, enabled = !state.busy, singleLine = true,
                label = { Text(if (russian) "Время ежедневной сводки" else "Daily digest time") })
            Row {
                Checkbox(checked = weeklyEnabled, onCheckedChange = { weeklyEnabled = it }, enabled = !state.busy)
                Text(if (russian) "Недельная сводка" else "Weekly digest", Modifier.padding(top = 12.dp))
            }
            val weekdays = if (russian) listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
                else listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
            Text(if (russian) "День недельного дайджеста" else "Weekly digest day")
            TextButton(onClick = { showWeekdayMenu = true }, enabled = !state.busy) {
                Text(weekdays[weeklyDay - 1])
            }
            DropdownMenu(expanded = showWeekdayMenu, onDismissRequest = { showWeekdayMenu = false }) {
                weekdays.forEachIndexed { index, day ->
                    DropdownMenuItem(text = { Text(day) }, onClick = { weeklyDay = index + 1; showWeekdayMenu = false })
                }
            }
            OutlinedTextField(weeklyTime, { weeklyTime = it }, enabled = !state.busy, singleLine = true,
                label = { Text(if (russian) "Время недельной сводки" else "Weekly digest time") })
            OutlinedTextField(quietStart, { quietStart = it }, enabled = !state.busy, singleLine = true,
                label = { Text(if (russian) "Начало тихих часов" else "Quiet hours start") })
            OutlinedTextField(quietEnd, { quietEnd = it }, enabled = !state.busy, singleLine = true,
                label = { Text(if (russian) "Конец тихих часов" else "Quiet hours end") })
            val timePattern = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
            val quietHoursValid = quietStart.isBlank() && quietEnd.isBlank() ||
                timePattern.matches(quietStart) && timePattern.matches(quietEnd)
            val scheduleValid = timePattern.matches(dailyTime) && timePattern.matches(weeklyTime) &&
                weeklyDay in 1..7 && quietHoursValid
            if (!quietHoursValid) Text(if (russian) "Укажите обе границы тихих часов или оставьте обе пустыми."
                else "Set both quiet-hours times or leave both empty.", color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                onNotificationPreferencesSave(notificationPreferences.copy(language = digestLanguage,
                    dailyEnabled = dailyEnabled, dailyLocalTime = dailyTime, weeklyEnabled = weeklyEnabled,
                    weeklyDayOfWeek = weeklyDay, weeklyLocalTime = weeklyTime,
                    quietHoursStart = quietStart.takeIf(String::isNotBlank), quietHoursEnd = quietEnd.takeIf(String::isNotBlank)))
            }, enabled = !state.busy && scheduleValid) {
                Text(if (russian) "Сохранить расписание" else "Save schedule")
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TransactionDraftEditor(draft: FinanceTransactionDraft, debts: List<FinanceDebt>, busy: Boolean,
                                  russian: Boolean, onUpdate: (TransactionDraftEdit) -> Unit,
                                  onConfirm: (String, Long) -> Unit, onCancel: (String, Long) -> Unit) {
    var type by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(draft.type) }
    var amount by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(draft.amount) }
    var category by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(draft.categoryCode) }
    var subcategory by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(draft.subcategoryCode.orEmpty()) }
    var description by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(draft.description) }
    var debtId by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(draft.debtId) }
    var dirty by androidx.compose.runtime.remember(draft.id, draft.version) { mutableStateOf(false) }
    val typeLabel = when (type) {
        "income" -> if (russian) "Доход" else "Income"
        "debt_payment" -> if (russian) "Платёж по долгу" else "Debt payment"
        else -> if (russian) "Расход" else "Expense"
    }
    Text(if (russian) "Предложение операции" else "Transaction suggestion",
        style = MaterialTheme.typography.titleMedium)
    Text(if (russian) "Тип: $typeLabel" else "Type: $typeLabel")
    Text(if (russian) "Распознано: ${draft.occurredAt}" else "Detected date: ${draft.occurredAt}")
    Text(if (russian) "Провайдер: ${draft.provider} · модель: ${draft.modelVersion} · промпт: ${draft.promptVersion}"
        else "Provider: ${draft.provider} · model: ${draft.modelVersion} · prompt: ${draft.promptVersion}")
    OutlinedTextField(amount, { amount = it; dirty = true }, enabled = !busy, singleLine = true,
        label = { Text(if (russian) "Сумма, ₽" else "Amount, RUB") })
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("500" to "500 ₽", "1000" to "1 000 ₽", "2000" to "2 000 ₽", "5000" to "5 000 ₽").forEach { (value, label) ->
            TextButton(onClick = { amount = "$value.00"; dirty = true }, enabled = !busy) { Text(label) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("expense", "income", "debt_payment").forEach { option ->
            val label = when (option) {
                "income" -> if (russian) "Доход" else "Income"
                "debt_payment" -> if (russian) "Долг" else "Debt"
                else -> if (russian) "Расход" else "Expense"
            }
            TextButton(onClick = {
                type = option
                if (option == "debt_payment") category = "долги"
                if (option != "debt_payment") debtId = null
                dirty = true
            },
                enabled = !busy) { Text(label) }
        }
    }
    OutlinedTextField(category, { category = it; dirty = true }, enabled = !busy, singleLine = true,
        label = { Text(if (russian) "Категория" else "Category") })
    OutlinedTextField(subcategory, { subcategory = it; dirty = true }, enabled = !busy, singleLine = true,
        label = { Text(if (russian) "Подкатегория" else "Subcategory") })
    OutlinedTextField(description, { description = it; dirty = true }, enabled = !busy, singleLine = true,
        label = { Text(if (russian) "Описание" else "Description") })
    if (type == "debt_payment") {
        Text(if (russian) "Выберите долг" else "Choose a debt")
        debts.filter { it.status == "open" }.forEach { debt ->
            TextButton(onClick = { debtId = debt.id; dirty = true }, enabled = !busy) {
                Text(if (debtId == debt.id) "✓ ${debt.name}" else debt.name)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { onUpdate(TransactionDraftEdit(draft.id, draft.version, type, amount,
            category, subcategory.takeIf(String::isNotBlank), description, draft.occurredAt, debtId)) },
            enabled = dirty && !busy && amount.isNotBlank() && category.isNotBlank() && description.isNotBlank()) {
            Text(if (russian) "Сохранить изменения" else "Save changes")
        }
        Button(onClick = { onConfirm(draft.id, draft.version) },
            enabled = !dirty && !busy && (type != "debt_payment" || debtId != null)) {
            Text(if (russian) "Подтвердить" else "Confirm")
        }
    }
    TextButton(onClick = { onCancel(draft.id, draft.version) }, enabled = !busy) {
        Text(if (russian) "Отменить" else "Cancel")
    }
}

@androidx.compose.runtime.Composable
private fun DashboardScreen(state: FinanceUiState, language: String) {
    val russian = language == "ru"
    val summary = state.dashboardSummary
    val budget = state.budgets
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (russian) "Сводка" else "Summary", style = MaterialTheme.typography.titleLarge)
        if (summary == null) {
            Text(if (russian) "Сводка пока не загружена" else "Summary is not loaded")
            return@Column
        }
        Text(if (russian) "За ${summary.month} · на ${summary.asOfDate}" else "${summary.month} · as of ${summary.asOfDate}")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (russian) "Доходы месяца: ${summary.incomeTotal} ₽" else "Monthly income: ${summary.incomeTotal} RUB")
                Text(if (russian) "Расходы месяца: ${summary.expenseTotal} ₽" else "Monthly expenses: ${summary.expenseTotal} RUB")
                Text(if (russian) "Операций: ${summary.transactionCount}" else "Transactions: ${summary.transactionCount}")
            }
        }
        summary.dailyExpensePace?.let {
            Text(if (russian) "Средний расход в день: $it ₽" else "Daily expense pace: $it RUB")
        }
        summary.projectedExpenseTotal?.let {
            Text(if (russian) "Прогноз расходов за месяц: $it ₽" else "Projected monthly expenses: $it RUB")
        }
        summary.safeToSpend?.let { cash ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (russian) "Безопасно тратить в день: ${cash.safePerDay} ₽" else "Safe to spend per day: ${cash.safePerDay} RUB")
                    Text(if (russian) "Свободно до ${cash.horizonDate}: ${cash.safeTotal} ₽" else "Available through ${cash.horizonDate}: ${cash.safeTotal} RUB")
                    Text(if (russian) "Резерв 10%: ${cash.reserve} ₽" else "10% reserve: ${cash.reserve} RUB")
                    Text(if (russian) "Обязательные списания: ${cash.promisedPayments} ₽" else "Committed payments: ${cash.promisedPayments} RUB")
                }
            }
        }
        budget?.let {
            Text(if (russian) "Лимит месяца: ${it.effectiveTotalLimit} ₽ · потрачено ${it.totalMonthlySpent} ₽ · ${it.totalLimitStatus}"
            else "Monthly budget: ${it.effectiveTotalLimit} RUB · spent ${it.totalMonthlySpent} RUB · ${it.totalLimitStatus}")
            LinearProgressIndicator(progress = { amountFraction(it.totalMonthlySpent, it.effectiveTotalLimit) },
                modifier = Modifier.fillMaxWidth())
            val food = summary.rolling7FoodStatus
            Text(if (russian) "Еда за 7 дней: ${food.spent} / ${food.limit} ₽ · ${food.paceStatus}"
            else "Food over 7 days: ${food.spent} / ${food.limit} RUB · ${food.paceStatus}")
        }
    }
}

@androidx.compose.runtime.Composable
private fun ReportScreen(state: FinanceUiState, language: String,
                         onLoad: (String, String, String, String, String) -> Unit) {
    val russian = language == "ru"
    val initialMonth = state.report?.fromDate?.take(7) ?: YearMonth.now().toString()
    var period by androidx.compose.runtime.remember { mutableStateOf("month") }
    var scope by androidx.compose.runtime.remember { mutableStateOf("personal") }
    var month by androidx.compose.runtime.remember { mutableStateOf(initialMonth) }
    var from by androidx.compose.runtime.remember { mutableStateOf(YearMonth.now().atDay(1).toString()) }
    var to by androidx.compose.runtime.remember { mutableStateOf(LocalDate.now().toString()) }
    val customValid = period != "custom" || runCatching {
        !LocalDate.parse(from).isAfter(LocalDate.parse(to))
    }.getOrDefault(false)
    val periods = listOf("month", "week", "90d", "custom")
    val periodLabel = when (period) {
        "week" -> if (russian) "Неделя" else "Week"
        "90d" -> if (russian) "90 дней" else "90 days"
        "custom" -> if (russian) "Произвольный период" else "Custom period"
        else -> if (russian) "Месяц" else "Month"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(if (russian) "Детали отчёта" else "Report details", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { period = periods[(periods.indexOf(period) + 1) % periods.size] }) {
                Text("$periodLabel ▾")
            }
            TextButton(onClick = { scope = if (scope == "personal") "family" else "personal" }) {
                Text(if (scope == "personal") {
                    if (russian) "Личные" else "Personal"
                } else if (russian) "Семейные" else "Family")
            }
        }
        if (period == "month") {
            OutlinedTextField(month, { month = it }, singleLine = true,
                label = { Text(if (russian) "Месяц, ГГГГ-ММ" else "Month, YYYY-MM") })
        }
        if (period == "custom") {
            OutlinedTextField(from, { from = it }, singleLine = true,
                label = { Text(if (russian) "С даты, ГГГГ-ММ-ДД" else "From, YYYY-MM-DD") })
            OutlinedTextField(to, { to = it }, singleLine = true,
                label = { Text(if (russian) "По дату, ГГГГ-ММ-ДД" else "To, YYYY-MM-DD") })
        }
        Button(onClick = { onLoad(period, scope, month, from, to) },
            enabled = !state.busy && customValid && (period != "month" || month.matches(Regex("^\\d{4}-\\d{2}$")))) {
            Text(if (russian) "Показать отчёт" else "Show report")
        }
        val report = state.report
        if (report == null) {
            Text(if (russian) "Выберите период и загрузите отчёт" else "Choose a period and load a report")
        } else {
            Text(if (russian) "${report.fromDate} — ${report.toDate} · ${report.timezone}" else "${report.fromDate} — ${report.toDate} · ${report.timezone}")
            val categoryMaximum = report.expenseByCategory.values.maxByOrNull {
                it.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            } ?: "0.00"
            val dailyMaximum = report.expenseByDay.values.maxByOrNull {
                it.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            } ?: "0.00"
            LazyColumn(Modifier.weight(1f).testTag("report-results"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (russian) "Доходы: ${report.incomeTotal} ₽" else "Income: ${report.incomeTotal} ${report.currency}")
                            Text(if (russian) "Расходы: ${report.expenseTotal} ₽" else "Expenses: ${report.expenseTotal} ${report.currency}")
                            Text(if (russian) "Платежи по долгам: ${report.debtPaymentTotal} ₽" else "Debt payments: ${report.debtPaymentTotal} ${report.currency}")
                            Text(if (russian) "Возвраты: ${report.refundTotal} ₽" else "Refunds: ${report.refundTotal} ${report.currency}")
                            Text(if (russian) "Операций: ${report.transactionCount}" else "Transactions: ${report.transactionCount}")
                            report.weekendSharePercent?.let {
                                Text(if (russian) "Доля расходов в выходные: $it%" else "Weekend expense share: $it%")
                            }
                            report.monthlyBudgetLimit?.let { limit ->
                                Text(if (russian) "Лимит месяца: $limit ₽ · остаток ${report.monthlyBudgetRemaining ?: "—"} ₽"
                                else "Monthly budget: $limit ${report.currency} · remaining ${report.monthlyBudgetRemaining ?: "—"} ${report.currency}")
                            }
                            val food = report.rolling7FoodStatus
                            Text(if (russian) "Еда за 7 дней: ${food.spent} / ${food.limit} ₽ · ${food.paceStatus}"
                            else "Food over 7 days: ${food.spent} / ${food.limit} ${report.currency} · ${food.paceStatus}")
                        }
                    }
                }
                items(report.expenseByCategory.toSortedMap().entries.toList()) { entry ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${entry.key}: ${entry.value} ${report.currency}")
                        LinearProgressIndicator(progress = { amountFraction(entry.value, categoryMaximum) },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                item {
                    Text(if (russian) "Расходы по дням" else "Expenses by day",
                        style = MaterialTheme.typography.titleMedium)
                }
                items(report.expenseByDay.toSortedMap().entries.toList()) { entry ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${entry.key}: ${entry.value} ${report.currency}")
                        LinearProgressIndicator(progress = { amountFraction(entry.value, dailyMaximum) },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

private fun amountFraction(value: String, maximum: String): Float {
    val current = value.toBigDecimalOrNull() ?: return 0f
    val limit = maximum.toBigDecimalOrNull() ?: return 0f
    if (limit <= java.math.BigDecimal.ZERO || current <= java.math.BigDecimal.ZERO) return 0f
    return current.divide(limit, 6, java.math.RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
}

private fun normalizedIncomeInput(value: String): String? {
    val normalized = value.trim().replace(',', '.').takeIf(String::isNotBlank) ?: return null
    return normalized.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
}

@androidx.compose.runtime.Composable
private fun BudgetScreen(state: FinanceUiState, language: String,
                         onUpdate: (String, String, String, String, Long) -> Unit,
                         onReset: () -> Unit, onPropose: (String?) -> Unit, onApply: (String) -> Unit) {
    val russian = language == "ru"
    val budget = state.budgets
    val tenant = state.tenants.firstOrNull()
    val canEditFamily = tenant?.role in listOf("owner", "admin")
    val canEditPersonal = tenant?.role != "viewer"
    var scope by androidx.compose.runtime.remember { mutableStateOf("family") }
    var period by androidx.compose.runtime.remember { mutableStateOf("monthly") }
    var key by androidx.compose.runtime.remember { mutableStateOf("__total__") }
    var amount by androidx.compose.runtime.remember { mutableStateOf("") }
    var income by androidx.compose.runtime.remember { mutableStateOf("") }
    val canEdit = if (scope == "family") canEditFamily else canEditPersonal

    if (budget == null) {
        Text(if (russian) "Бюджет пока не загружен" else "Budget is not loaded")
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (russian) "Лимиты · ${budget.month}" else "Limits · ${budget.month}", style = MaterialTheme.typography.titleLarge)
        Text(if (russian) "Расход еды за 7 дней: ${budget.rolling7FoodStatus.spent} / ${budget.rolling7FoodStatus.limit} ₽ · ${budget.rolling7FoodStatus.paceStatus}"
            else "Food over 7 days: ${budget.rolling7FoodStatus.spent} / ${budget.rolling7FoodStatus.limit} RUB · ${budget.rolling7FoodStatus.paceStatus}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { scope = "family" }) { Text(if (russian) "Семейный" else "Family") }
            TextButton(onClick = { scope = "personal" }) { Text(if (russian) "Личный" else "Personal") }
            TextButton(onClick = { period = "monthly" }) { Text(if (russian) "Месяц" else "Monthly") }
            TextButton(onClick = { period = "rolling7"; key = "еда" }) { Text(if (russian) "7 дней" else "7 days") }
        }
        OutlinedTextField(key, { key = it }, enabled = period == "monthly",
            label = { Text(if (russian) "Категория или __total__" else "Category or __total__") })
        OutlinedTextField(amount, { amount = it }, enabled = canEdit,
            label = { Text(if (russian) "Лимит, ₽" else "Limit, RUB") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val version = when {
                    period == "rolling7" && scope == "personal" -> budget.personalRolling7FoodVersion
                    period == "rolling7" -> budget.familyRolling7FoodVersion
                    key == "__total__" && scope == "personal" -> budget.personalTotalVersion
                    key == "__total__" -> budget.familyTotalVersion
                    scope == "personal" -> budget.personalVersions[key] ?: 0L
                    else -> budget.familyVersions[key] ?: 0L
                }
                onUpdate(key, scope, amount, period, version)
            }, enabled = canEdit && !state.busy && amount.matches(Regex("^\\d{1,8}(?:[.,]\\d{1,2})?$"))
                    && (period != "rolling7" || key == "еда")) {
                Text(if (russian) "Сохранить лимит" else "Save limit")
            }
            if (scope == "personal") TextButton(onClick = onReset, enabled = !state.busy && canEditPersonal) {
                Text(if (russian) "Сбросить личные" else "Reset personal")
            }
        }
        if (!canEdit) Text(if (russian) "У вас нет прав изменять этот бюджет" else "You cannot change this budget")
        Text(if (russian) "Предложение лимитов" else "Budget proposal", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(income, { income = it }, enabled = canEditFamily,
            label = { Text(if (russian) "Доход в месяц, ₽" else "Monthly income, RUB") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onPropose(income.trim().takeIf(String::isNotEmpty)) },
                enabled = canEditFamily && !state.busy && (income.isBlank() || income.matches(Regex("^\\d{1,8}(?:[.,]\\d{1,2})?$")))) {
                Text(if (income.isBlank()) {
                    if (russian) "Предложить по истории" else "Suggest from history"
                } else if (russian) "Рассчитать по доходу" else "Suggest from income")
            }
            state.budgetProposal?.takeIf { it.status == "pending" }?.let { proposal ->
                Button(onClick = { onApply(proposal.id) }, enabled = canEditFamily && !state.busy) {
                    Text(if (russian) "Применить" else "Apply")
                }
            }
        }
        state.budgetProposal?.let { proposal ->
            Text(if (russian) "Предложен лимит ${proposal.totalLimit} ₽ · ${proposal.proposalSource} · ${proposal.historyDays} дн."
            else "Suggested total ${proposal.totalLimit} RUB · ${proposal.proposalSource} · ${proposal.historyDays} days")
        }
        Text(if (russian) "Лимит за месяц: ${budget.effectiveTotalLimit} ₽ · потрачено ${budget.totalMonthlySpent} ₽ · ${budget.totalLimitStatus}"
            else "Monthly limit: ${budget.effectiveTotalLimit} RUB · spent ${budget.totalMonthlySpent} RUB · ${budget.totalLimitStatus}")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(budget.effectiveLimits.toSortedMap().entries.toList()) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(entry.key, style = MaterialTheme.typography.titleMedium)
                        Text(if (russian) "Действует ${entry.value} ₽ · потрачено ${budget.monthlySpent[entry.key] ?: "0.00"} ₽ · ${budget.limitStatus[entry.key] ?: "disabled"}"
                        else "Effective ${entry.value} RUB · spent ${budget.monthlySpent[entry.key] ?: "0.00"} RUB · ${budget.limitStatus[entry.key] ?: "disabled"}")
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DebtScreen(state: FinanceUiState, language: String,
                       onCreate: (String, String, String?, String) -> Unit,
                       onPay: (String, String, Long) -> Unit,
                       onAdjust: (String, String, Long) -> Unit,
                       onForecast: (String) -> Unit) {
    val russian = language == "ru"
    val canManage = state.tenants.firstOrNull()?.role != "viewer"
    var name by androidx.compose.runtime.remember { mutableStateOf("") }
    var openingBalance by androidx.compose.runtime.remember { mutableStateOf("") }
    var interestRate by androidx.compose.runtime.remember { mutableStateOf("") }
    var minimumPayment by androidx.compose.runtime.remember { mutableStateOf("") }
    var paymentAmount by androidx.compose.runtime.remember { mutableStateOf("") }
    var adjustedBalance by androidx.compose.runtime.remember { mutableStateOf("") }
    var addingDebt by androidx.compose.runtime.remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (russian) "Долги" else "Debts", style = MaterialTheme.typography.titleLarge)
        if (canManage) {
            TextButton(onClick = { addingDebt = !addingDebt }) {
                Text(if (addingDebt) {
                    if (russian) "Отмена" else "Cancel"
                } else if (russian) "Создать долг" else "Create debt")
            }
            if (addingDebt) {
                OutlinedTextField(name, { name = it }, label = { Text(if (russian) "Название" else "Name") })
                OutlinedTextField(openingBalance, { openingBalance = it }, label = { Text(if (russian) "Начальный остаток, ₽" else "Opening balance, RUB") })
                OutlinedTextField(interestRate, { interestRate = it }, label = { Text(if (russian) "Ставка, % (необязательно)" else "Interest rate, % (optional)") })
                OutlinedTextField(minimumPayment, { minimumPayment = it }, label = { Text(if (russian) "Минимальный платёж, ₽" else "Minimum payment, RUB") })
                Button(onClick = {
                    onCreate(name, openingBalance, interestRate.takeIf(String::isNotBlank), minimumPayment)
                    addingDebt = false
                }, enabled = !state.busy && name.isNotBlank() && openingBalance.isNotBlank() && minimumPayment.isNotBlank()) {
                    Text(if (russian) "Сохранить долг" else "Save debt")
                }
            }
        } else Text(if (russian) "Просмотр только для чтения" else "Read-only access")
        if (state.debts.isEmpty()) Text(if (russian) "Долгов пока нет" else "No debts yet")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.debts) { debt ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(debt.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (russian) "Остаток ${debt.currentBalance} ₽ · ${if (debt.status == "open") "открыт" else "закрыт"} · версия ${debt.version}"
                        else "Balance ${debt.currentBalance} RUB · ${debt.status} · version ${debt.version}")
                        if (canManage && debt.status == "open") {
                            OutlinedTextField(paymentAmount, { paymentAmount = it }, label = { Text(if (russian) "Платёж, ₽" else "Payment, RUB") })
                            Button(onClick = { onPay(debt.id, paymentAmount, debt.version) }, enabled = !state.busy && paymentAmount.isNotBlank()) {
                                Text(if (russian) "Записать платёж" else "Record payment")
                            }
                            OutlinedTextField(adjustedBalance, { adjustedBalance = it }, label = { Text(if (russian) "Исправить остаток, ₽" else "Adjust balance, RUB") })
                            Button(onClick = { onAdjust(debt.id, adjustedBalance, debt.version) }, enabled = !state.busy && adjustedBalance.isNotBlank()) {
                                Text(if (russian) "Сохранить остаток" else "Save balance")
                            }
                        }
                        Button(onClick = { onForecast(debt.id) }, enabled = !state.busy) {
                            Text(if (russian) "Прогноз выплаты" else "Payoff forecast")
                        }
                        state.debtForecasts[debt.id]?.let { forecast ->
                            Text(if (russian) "${forecast.monthsToPayoff?.let { "$it мес." } ?: "Срок не рассчитан"} · ${forecast.estimateBasis}"
                            else "${forecast.monthsToPayoff?.let { "$it months" } ?: "No estimate"} · ${forecast.estimateBasis}")
                        }
                    }
                }
            }
        }
    }
}
