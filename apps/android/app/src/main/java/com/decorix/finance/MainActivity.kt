package com.decorix.finance

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.security.MessageDigest
import java.time.YearMonth
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.ZoneId
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
    private lateinit var authService: AuthorizationService
    private val api by lazy { FinanceApi(this) }
    private val receiptCheckpointStore by lazy { ReceiptUploadCheckpointStore(this) }
    private val budgetIdempotencyKeyStore by lazy { BudgetIdempotencyKeyStore(this) }
    private val executor = Executors.newSingleThreadExecutor()
    private val receiptPollExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val receiptPollGeneration = AtomicLong(0L)
    private val receiptReadingGeneration = AtomicLong(0L)
    private val receiptItemsGeneration = AtomicLong(0L)
    private val receiptItemEditGeneration = AtomicLong(0L)
    private val receiptItemAddGeneration = AtomicLong(0L)
    private val receiptTotalSyncGeneration = AtomicLong(0L)
    private val receiptDuplicateGeneration = AtomicLong(0L)
    private val receiptConfirmGeneration = AtomicLong(0L)
    private val receiptPriceComparisonGeneration = AtomicLong(0L)
    private val productCatalogGeneration = AtomicLong(0L)
    private val productCatalogStateLock = Any()
    private val receiptCategoryGeneration = AtomicLong(0L)
    private val receiptBasketReviewGeneration = AtomicLong(0L)
    private val receiptDisputedItemsGeneration = AtomicLong(0L)
    private val receiptRepeatWarningsGeneration = AtomicLong(0L)
    private val reportRequestGeneration = AtomicLong(0L)
    private val receiptRecalculationGeneration = AtomicLong(0L)
    private val familyBudgetFoodRequestGeneration = AtomicLong(0L)
    private val shoppingRequestGeneration = AtomicLong(0L)
    @Volatile private var receiptPollTask: ScheduledFuture<*>? = null
    private var ui by mutableStateOf(FinanceUiState())
    private var language by mutableStateOf("ru")
    private var pendingTransactionEdit: FinanceTransactionEdit? = null
    private var pendingTransactionEditKey: String? = null
    private var pendingReceiptUri: Uri? = null
    private var pendingReceiptKey: String? = null
    private var receiptRestoreInProgressTenantId: String? = null
    private var receiptPickerOperationToken: Long? = null
    private val loginResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val response = net.openid.appauth.AuthorizationResponse.fromIntent(result.data ?: Intent())
        val error = net.openid.appauth.AuthorizationException.fromIntent(result.data ?: Intent())
        if (error != null) ui = ui.copy(error = error.errorDescription ?: "Sign in cancelled")
        else if (response != null) exchangeCode(response)
    }
    private val receiptPhotoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val operationToken = receiptPickerOperationToken
        receiptPickerOperationToken = null
        if (uri != null && operationToken != null && ReceiptOperationGeneration.isCurrent(operationToken)) {
            acceptReceiptPhoto(uri, operationToken)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startupCheckpoint = runCatching { receiptCheckpointStore.load() }.getOrNull()
        reconcilePersistedReceiptUriGrants(startupCheckpoint)
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
                        onProfileSave = ::saveMemberProfile, onRepeatProfileSave = ::saveMemberProfileForRepeat,
                        onCreateTelegramLink = ::createTelegramLinkCode,
                        onNotificationPreferencesSave = ::saveNotificationPreferences,
                        onBudgetKeep = ::keepBudgetProposal,
                        onCreateDraft = ::createTransactionDraft, onUpdateDraft = ::updateTransactionDraft,
                        onConfirmDraft = ::confirmTransactionDraft, onCancelDraft = ::cancelTransactionDraft,
                        onLogout = ::logout, onBudgetUpdate = ::updateBudget, onBudgetReset = ::resetPersonalBudgets,
                        onBudgetProposal = ::createBudgetProposal, onBudgetApply = ::applyBudgetProposal,
                        onDebtCreate = ::createDebt, onDebtPay = ::payDebt, onDebtAdjust = ::adjustDebt,
                        onDebtForecast = ::loadDebtForecast, onReportLoad = ::loadReport,
                        onRecalculationPreview = ::previewReceiptRecalculation,
                        onRecalculationApply = ::applyReceiptRecalculation,
                        onRecalculationHistory = ::loadReceiptRecalculationHistory,
                        onRecalculationDetail = ::loadReceiptRecalculationDetail,
                        onFamilyBudgetFoodStatusLoad = ::loadFamilyBudgetFoodStatus,
                        onShoppingLoad = ::loadShoppingCandidates, onShoppingDecision = ::applyShoppingDecision,
                        onShoppingCopy = ::copyShoppingList, onPersonalInflationLoad = ::loadPersonalInflation,
                        onDoNotBuyLoad = ::loadDoNotBuy, onDoNotBuyDecision = ::applyDoNotBuyDecision,
                        onRecurringLoad = ::loadRecurring, onRecurringDecision = ::applyRecurringDecision,
                        onProductCatalogLoad = ::loadProductCatalog,
                        onRepeatTransaction = ::repeatTransaction, onVoidTransaction = ::voidTransaction,
                        onTransactionFilter = ::filterTransactions, onTransactionLoadMore = ::loadMoreTransactions,
                        onUpdateTransaction = ::updateTransaction,
                        onReceiptPick = ::launchReceiptPhotoPicker,
                        onReceiptRefresh = ::refreshReceiptJob,
                        onReceiptRetry = ::retryReceiptPhotoUpload,
                        onReceiptDiscard = ::discardPendingReceiptPhoto,
                        onReceiptReading = ::loadReceiptReading,
                        onReceiptItemsPage = ::loadReceiptItems,
                        onReceiptItemUpdate = ::updateReceiptItem,
                        onReceiptItemRefresh = ::refreshReceiptAfterItemConflict,
                        onReceiptItemAdd = ::addReceiptItem,
                        onReceiptItemAddRefresh = ::refreshReceiptItemAdd,
                        onReceiptTotalSync = ::syncReceiptTotal,
                        onReceiptTotalSyncRefresh = ::refreshReceiptTotalSync,
                        onReceiptDuplicateCandidates = ::loadReceiptDuplicateCandidates,
                        onReceiptDuplicateDecision = ::decideReceiptDuplicate,
                        onReceiptConfirm = ::confirmReceipt,
                        onReceiptCategorySelect = ::selectReceiptCategory,
                        onReceiptBasketReview = ::reviewReceiptBasket,
                        onReceiptDisputedItemsPage = ::loadReceiptDisputedItems,
                        onReceiptRepeatWarningsRefresh = ::loadReceiptRepeatWarnings,
                        onReceiptDisputedProductDecision = ::decideReceiptDisputedProduct,
                        onReceiptPriceComparison = ::loadReceiptPriceComparison)
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
                invalidateProductCatalogRequests()
                api.saveTokens(tokenResponse.accessToken.orEmpty(), tokenResponse.refreshToken.orEmpty(),
                    tokenResponse.accessTokenExpirationTime ?: 0L, tokenResponse.idToken.orEmpty())
                ui = ui.copy(authenticated = true)
                loadTenants()
            }
        }
    }

    private fun launchReceiptPhotoPicker() {
        if (ui.tenants.firstOrNull()?.role == "viewer" || hasUnresolvedReceiptCheckpoint()) return
        val operationToken = ReceiptOperationGeneration.capture()
        receiptPickerOperationToken = operationToken
        receiptPhotoPicker.launch(arrayOf("image/jpeg", "image/png"))
    }

    private fun acceptReceiptPhoto(uri: Uri, operationToken: Long) {
        if (!ReceiptOperationGeneration.isCurrent(operationToken)) return
        if (ui.tenants.firstOrNull()?.role == "viewer" || hasUnresolvedReceiptCheckpoint()) return
        ReceiptOperationGeneration.runIfCurrent(operationToken) {
            invalidateReceiptPoll()
            receiptReadingGeneration.incrementAndGet()
            receiptItemsGeneration.incrementAndGet()
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pendingReceiptUri = uri
            pendingReceiptKey = java.util.UUID.randomUUID().toString()
            ui = ui.copy(receiptJob = null, receiptDraft = null, receiptUploadError = null,
                receiptReading = null, receiptReadingReceiptId = null, receiptReadingLoading = false,
                receiptReadingError = null,
                receiptItemsPage = null, receiptItemsReceiptId = null, receiptItemsRequestedPage = null,
                receiptItemsLoading = false, receiptItemsError = null,
                receiptCanRetryUpload = true, receiptCheckpointUnresolved = true)
        } ?: return
        uploadSelectedReceiptPhoto(operationToken)
    }

    private fun retryReceiptPhotoUpload() {
        if (pendingReceiptUri != null && pendingReceiptKey != null) uploadSelectedReceiptPhoto()
    }

    private fun discardPendingReceiptPhoto() {
        val operationToken = ReceiptOperationGeneration.capture()
        val uri = pendingReceiptUri ?: return
        val uploadError = ui.receiptUploadError ?: return
        if (pendingReceiptKey == null || !ui.receiptCanRetryUpload || ui.receiptJob != null ||
            uploadError !in LOCAL_RECEIPT_FILE_ERRORS) return
        ReceiptOperationGeneration.runIfCurrent(operationToken) { ui = ui.copy(busy = true) } ?: return
        executor.execute {
            if (!ReceiptOperationGeneration.isCurrent(operationToken)) return@execute
            val cleared = ReceiptOperationGeneration.runIfCurrent(operationToken) {
                runCatching { receiptCheckpointStore.clear() }.getOrDefault(false)
            } ?: return@execute
            if (!cleared) {
                ReceiptOperationGeneration.runIfCurrent(operationToken) {
                    ui = ui.copy(busy = false, receiptUploadError = "checkpoint",
                        receiptCheckpointUnresolved = true)
                }
                return@execute
            }
            ReceiptOperationGeneration.runIfCurrent(operationToken) {
                runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                pendingReceiptUri = null
                pendingReceiptKey = null
                receiptRestoreInProgressTenantId = null
                ui = ui.copy(busy = false, receiptJob = null, receiptDraft = null,
                    receiptUploadInProgress = false, receiptUploadError = null, receiptCanRetryUpload = false,
                    receiptCheckpointUnresolved = false)
            } ?: return@execute
            runOnUiThread {
                if (ReceiptOperationGeneration.isCurrent(operationToken)) launchReceiptPhotoPicker()
            }
        }
    }

    private fun uploadSelectedReceiptPhoto(operationToken: Long = ReceiptOperationGeneration.capture()) {
        if (!ReceiptOperationGeneration.isCurrent(operationToken)) return
        val tenant = ui.tenants.firstOrNull() ?: return
        if (tenant.role == "viewer") return
        val uri = pendingReceiptUri ?: return
        val key = pendingReceiptKey ?: return
        ReceiptOperationGeneration.runIfCurrent(operationToken) {
            ui = ui.copy(busy = true, error = null, receiptUploadInProgress = true,
                receiptUploadError = null, receiptJob = null, receiptDraft = null,
                receiptCheckpointUnresolved = true)
        } ?: return
        executor.execute {
            if (!ReceiptOperationGeneration.isCurrent(operationToken)) return@execute
            try {
                val pendingSaved = ReceiptOperationGeneration.runIfCurrent(operationToken) {
                    runCatching { receiptCheckpointStore.savePending(tenant.id, uri.toString(), key) }
                        .getOrDefault(false)
                } ?: return@execute
                if (!pendingSaved) error("checkpoint")
                val contentType = receiptContentType(uri)
                val fileName = receiptFileName(uri, contentType)
                val bytes = readReceiptPhoto(uri)
                if (!ReceiptOperationGeneration.isCurrent(operationToken)) return@execute
                val job = api.uploadReceiptPhoto(tenant.id, bytes, fileName, contentType, key)
                if (!ReceiptOperationGeneration.isCurrent(operationToken)) return@execute
                val jobSaved = ReceiptOperationGeneration.runIfCurrent(operationToken) {
                    runCatching { receiptCheckpointStore.saveJob(tenant.id, job.id) }.getOrDefault(false)
                } ?: return@execute
                if (!jobSaved) error("checkpoint")
                ReceiptOperationGeneration.runIfCurrent(operationToken) {
                    runCatching {
                        contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    pendingReceiptUri = null
                    pendingReceiptKey = null
                    ui = ui.copy(receiptJob = job, receiptCanRetryUpload = false,
                        receiptCheckpointUnresolved = true)
                } ?: return@execute
                pollReceiptJob(tenant.id, job, operationToken)
            } catch (failure: Throwable) {
                ReceiptOperationGeneration.runIfCurrent(operationToken) {
                    ui = ui.copy(busy = false, receiptUploadInProgress = false,
                        receiptUploadError = receiptUploadErrorCode(failure),
                        receiptCanRetryUpload = pendingReceiptUri != null && pendingReceiptKey != null)
                }
            }
        }
    }

    private fun refreshReceiptJob() {
        val operationToken = ReceiptOperationGeneration.capture()
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        if (pendingReceiptUri != null && pendingReceiptKey != null) {
            uploadSelectedReceiptPhoto(operationToken)
            return
        }
        ReceiptOperationGeneration.runIfCurrent(operationToken) {
            invalidateReceiptPoll()
            ui = ui.copy(busy = true, receiptUploadInProgress = true, receiptUploadError = null)
        } ?: return
        executor.execute {
            if (!ReceiptOperationGeneration.isCurrent(operationToken)) return@execute
            val checkpointResult = ReceiptOperationGeneration.runIfCurrent(operationToken) {
                runCatching { receiptCheckpointStore.load() }
            } ?: return@execute
            val checkpoint = checkpointResult.getOrElse { failure ->
                ReceiptOperationGeneration.runIfCurrent(operationToken) {
                    ui = ui.copy(busy = false, receiptUploadInProgress = false,
                        receiptUploadError = receiptUploadErrorCode(failure), receiptCheckpointUnresolved = true)
                }
                return@execute
            }
            if (checkpoint?.tenantId == tenantId && checkpoint.photoUri != null &&
                checkpoint.idempotencyKey != null) {
                ReceiptOperationGeneration.runIfCurrent(operationToken) {
                    pendingReceiptUri = Uri.parse(checkpoint.photoUri)
                    pendingReceiptKey = checkpoint.idempotencyKey
                    ui = ui.copy(busy = false, receiptUploadInProgress = false,
                        receiptCanRetryUpload = true, receiptCheckpointUnresolved = true)
                } ?: return@execute
                uploadSelectedReceiptPhoto(operationToken)
                return@execute
            }
            runCatching {
                if (!ReceiptOperationGeneration.isCurrent(operationToken)) return@execute
                val jobId = ui.receiptJob?.id
                    ?: checkpoint?.takeIf { it.tenantId == tenantId }?.jobId
                    ?: error("checkpoint")
                val job = api.receiptJob(tenantId, jobId)
                if (!ReceiptOperationGeneration.isCurrent(operationToken)) return@execute
                ReceiptOperationGeneration.runIfCurrent(operationToken) { ui = ui.copy(receiptJob = job) }
                    ?: return@execute
                pollReceiptJob(tenantId, job, operationToken)
            }.onFailure { failure ->
                ReceiptOperationGeneration.runIfCurrent(operationToken) {
                    ui = ui.copy(busy = false, receiptUploadInProgress = false,
                        receiptUploadError = receiptUploadErrorCode(failure))
                }
            }
        }
    }

    private fun loadReceiptReading(receiptId: String) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        val receipt = ui.receiptDraft?.takeIf { it.id == receiptId } ?: return
        if (receipt.documentId == null || receipt.selectedReader == "manual") return
        val generation = receiptReadingGeneration.incrementAndGet()
        ui = ui.copy(receiptReading = null, receiptReadingReceiptId = receiptId,
            receiptReadingLoading = true, receiptReadingError = null)
        executor.execute {
            if (receiptReadingGeneration.get() != generation) return@execute
            runCatching { api.receiptReading(tenantId, receiptId) }
                .onSuccess { reading ->
                    if (receiptReadingGeneration.get() == generation && ui.authenticated &&
                        ui.tenants.firstOrNull()?.id == tenantId && ui.receiptDraft?.id == receiptId) {
                        ui = ui.copy(receiptReading = reading, receiptReadingLoading = false,
                            receiptReadingError = null)
                    }
                }
                .onFailure {
                    if (receiptReadingGeneration.get() == generation && ui.authenticated &&
                        ui.tenants.firstOrNull()?.id == tenantId && ui.receiptDraft?.id == receiptId) {
                        ui = ui.copy(receiptReading = null, receiptReadingLoading = false,
                            receiptReadingError = "unavailable")
                    }
                }
        }
    }

    private fun loadReceiptDuplicateCandidates(receiptId: String) {
        val tenant = ui.tenants.firstOrNull() ?: return
        if (!ui.authenticated || tenant.role == "viewer" || ui.receiptDraft?.id != receiptId ||
            ui.receiptDraft?.state == "confirmed" ||
            (ui.receiptDuplicateCandidatesLoading && ui.receiptDuplicateCandidatesReceiptId == receiptId)) return
        val generation = receiptDuplicateGeneration.incrementAndGet()
        ui = ui.copy(receiptDuplicateCandidates = null, receiptDuplicateCandidatesReceiptId = receiptId,
            receiptDuplicateCandidatesLoading = true, receiptDuplicateCandidatesError = null)
        executor.execute {
            runCatching {
                val latestReceipt = api.receipt(tenant.id, receiptId)
                latestReceipt to api.receiptDuplicateCandidates(tenant.id, receiptId)
            }.onSuccess { (latestReceipt, candidates) ->
                    if (receiptDuplicateGeneration.get() == generation && ui.authenticated &&
                        ui.tenants.firstOrNull()?.id == tenant.id && ui.receiptDraft?.id == receiptId) {
                        ui = ui.copy(receiptDraft = latestReceipt, receiptDuplicateCandidates = candidates,
                            receiptDuplicateCandidatesReceiptId = receiptId,
                            receiptDuplicateCandidatesLoading = false, receiptDuplicateCandidatesError = null)
                    }
                }
                .onFailure {
                    if (receiptDuplicateGeneration.get() == generation && ui.authenticated &&
                        ui.tenants.firstOrNull()?.id == tenant.id && ui.receiptDraft?.id == receiptId) {
                        ui = ui.copy(receiptDuplicateCandidates = null,
                            receiptDuplicateCandidatesReceiptId = receiptId,
                            receiptDuplicateCandidatesLoading = false, receiptDuplicateCandidatesError = "unavailable")
                    }
                }
        }
    }

    private fun decideReceiptDuplicate(receiptId: String, version: Long, decision: String,
                                       duplicateReceiptId: String?) {
        val tenant = ui.tenants.firstOrNull() ?: return
        val receipt = ui.receiptDraft?.takeIf { it.id == receiptId } ?: return
        val candidates = ui.receiptDuplicateCandidates?.takeIf { it.receiptId == receiptId } ?: return
        if (!ui.authenticated || tenant.role == "viewer" || ui.busy || ui.receiptDuplicateDecisionInProgress ||
            ui.receiptConfirming || receipt.version != version || decision !in setOf("independent", "duplicate") ||
            (decision == "independent" && duplicateReceiptId != null) ||
            (decision == "duplicate" && candidates.candidates.none { it.id == duplicateReceiptId })) return
        val generation = receiptDuplicateGeneration.incrementAndGet()
        ui = ui.copy(receiptDuplicateDecisionInProgress = true, receiptDuplicateDecisionError = null)
        executor.execute {
            runCatching { api.decideReceiptDuplicate(tenant.id, receiptId, version, decision, duplicateReceiptId) }
                .onSuccess { updated ->
                    if (receiptDuplicateGeneration.get() == generation && ui.authenticated &&
                        ui.tenants.firstOrNull()?.id == tenant.id && ui.receiptDraft?.id == receiptId) {
                        ui = ui.copy(receiptDraft = updated,
                            receiptDuplicateCandidates = candidates.copy(decision = updated.duplicateDecision),
                            receiptDuplicateCandidatesReceiptId = receiptId,
                            receiptDuplicateCandidatesLoading = false, receiptDuplicateCandidatesError = null,
                            receiptDuplicateDecisionInProgress = false, receiptDuplicateDecisionError = null)
                    }
                }
                .onFailure { failure ->
                    if (receiptDuplicateGeneration.get() != generation || !ui.authenticated ||
                        ui.tenants.firstOrNull()?.id != tenant.id || ui.receiptDraft?.id != receiptId) return@onFailure
                    val status = (failure as? ApiFailure)?.status
                    if (ReceiptReviewRecoveryPolicy.actionFor(status) ==
                        ReceiptReviewRecoveryAction.REFRESH_RECEIPT_AND_CANDIDATES) {
                        refreshReceiptDuplicateReview(tenant.id, receiptId, generation,
                            decisionError = if (status == 409) "candidate_conflict" else "stale_version",
                            confirmationAttempt = false)
                    } else {
                        ui = ui.copy(receiptDuplicateDecisionInProgress = false,
                            receiptDuplicateDecisionError = "unavailable")
                    }
                }
        }
    }

    private fun confirmReceipt(receiptId: String, version: Long, idempotencyKey: String) {
        val tenant = ui.tenants.firstOrNull() ?: return
        val receipt = ui.receiptDraft?.takeIf { it.id == receiptId } ?: return
        val candidates = ui.receiptDuplicateCandidates?.takeIf { it.receiptId == receiptId } ?: return
        val cash = receipt.cashTotal?.toBigDecimalOrNull()
        val items = receipt.itemsTotal?.toBigDecimalOrNull()
        val totalsReconciled = cash != null && items != null && cash.compareTo(items) == 0
        val unresolved = candidates.candidates.isNotEmpty() && receipt.duplicateDecision == "unknown"
        if (!ui.authenticated || tenant.role == "viewer" || ui.busy || ui.receiptConfirming ||
            ui.receiptDuplicateDecisionInProgress || receipt.version != version || !totalsReconciled ||
            receipt.state !in setOf("draft", "review_required") || candidates.decision != receipt.duplicateDecision ||
            receipt.duplicateDecision == "duplicate" || unresolved ||
            ui.receiptDuplicateCandidatesLoading || ui.receiptDuplicateCandidatesError != null ||
            ui.receiptDuplicateCandidatesReceiptId != receiptId || idempotencyKey.length !in 16..128 ||
            idempotencyKey.any(Char::isISOControl)) return
        val generation = receiptConfirmGeneration.incrementAndGet()
        ui = ui.copy(receiptConfirming = true, receiptConfirmError = null)
        executor.execute {
            runCatching { api.confirmReceipt(tenant.id, receiptId, version, idempotencyKey) }
                .onSuccess { confirmed ->
                    if (receiptConfirmGeneration.get() == generation && ui.authenticated &&
                        ui.tenants.firstOrNull()?.id == tenant.id && ui.receiptDraft?.id == receiptId) {
                        ui = ui.copy(receiptDraft = confirmed, receiptConfirming = false, receiptConfirmError = null)
                    }
                }
                .onFailure { failure ->
                    if (receiptConfirmGeneration.get() != generation || !ui.authenticated ||
                        ui.tenants.firstOrNull()?.id != tenant.id || ui.receiptDraft?.id != receiptId) return@onFailure
                    val status = (failure as? ApiFailure)?.status
                    if (ReceiptReviewRecoveryPolicy.actionFor(status) ==
                        ReceiptReviewRecoveryAction.REFRESH_RECEIPT_AND_CANDIDATES) {
                        refreshReceiptDuplicateReview(tenant.id, receiptId, generation,
                            confirmError = if (status == 409) "candidate_conflict" else "stale_version",
                            confirmationAttempt = true)
                    } else {
                        ui = ui.copy(receiptConfirming = false, receiptConfirmError = "unavailable")
                    }
                }
        }
    }

    private fun selectReceiptCategory(receiptId: String, version: Long, categoryCode: String) {
        val tenant = ui.tenants.firstOrNull() ?: return
        val receipt = ui.receiptDraft?.takeIf { it.id == receiptId } ?: return
        val allowedCategories = setOf("еда", "транспорт", "жилье", "досуг", "одежда", "здоровье",
            "работа", "техника", "долги", "прочее")
        if (!ui.authenticated || !canWriteReceiptCategory(tenant.role) || ui.busy || ui.receiptCategorySaving ||
            receipt.state !in setOf("draft", "review_required") || receipt.version != version ||
            categoryCode !in allowedCategories) return
        val generation = receiptCategoryGeneration.incrementAndGet()
        ui = ui.copy(receiptCategorySaving = true, receiptCategoryError = null)
        executor.execute {
            runCatching { api.selectReceiptCategory(tenant.id, receiptId, version, categoryCode) }
                .onSuccess { updated ->
                    if (receiptCategoryGeneration.get() == generation && ui.authenticated &&
                        ui.tenants.firstOrNull()?.id == tenant.id && ui.receiptDraft?.id == receiptId) {
                        ui = ui.copy(receiptDraft = updated, receiptCategorySaving = false,
                            receiptCategoryError = null)
                    }
                }
                .onFailure { failure ->
                    if (receiptCategoryGeneration.get() != generation || !ui.authenticated ||
                        ui.tenants.firstOrNull()?.id != tenant.id || ui.receiptDraft?.id != receiptId) return@onFailure
                    val status = (failure as? ApiFailure)?.status
                    if (status == 409 || status == 412) {
                        runCatching { api.receipt(tenant.id, receiptId) }
                            .onSuccess { latest ->
                                if (receiptCategoryGeneration.get() == generation && ui.authenticated &&
                                    ui.tenants.firstOrNull()?.id == tenant.id && ui.receiptDraft?.id == receiptId) {
                                    ui = ui.copy(receiptDraft = latest, receiptCategorySaving = false,
                                        receiptCategoryError = "category_conflict")
                                }
                            }
                            .onFailure {
                                if (receiptCategoryGeneration.get() == generation && ui.authenticated &&
                                    ui.tenants.firstOrNull()?.id == tenant.id && ui.receiptDraft?.id == receiptId) {
                                    ui = ui.copy(receiptCategorySaving = false,
                                        receiptCategoryError = "category_refresh_unavailable")
                                }
                            }
                    } else {
                        ui = ui.copy(receiptCategorySaving = false, receiptCategoryError = "unavailable")
                    }
                }
        }
    }

    private fun reviewReceiptBasket(receiptId: String, version: Long) {
        val tenant = ui.tenants.firstOrNull() ?: return
        val receipt = ui.receiptDraft?.takeIf { it.id == receiptId } ?: return
        if (!ui.authenticated || !canReviewReceiptBasket(tenant.role) || ui.busy ||
            (ui.receiptBasketReviewing && ui.receiptBasketReviewingReceiptId == receiptId &&
                ui.receiptBasketReviewingTenantId == tenant.id) || receipt.transactionId != null || receipt.itemCount <= 0 ||
            receipt.state !in setOf("draft", "review_required") || receipt.version != version) return
        val generation = receiptBasketReviewGeneration.incrementAndGet()
        ui = ui.copy(receiptBasketReviewing = true, receiptBasketReviewingReceiptId = receiptId,
            receiptBasketReviewingTenantId = tenant.id, receiptBasketReviewError = null)
        executor.execute {
            runCatching { api.reviewReceiptBasket(tenant.id, receiptId, version) }
                .onSuccess { reviewed ->
                    if (receiptBasketReviewGeneration.get() == generation && ui.authenticated &&
                        ui.tenants.firstOrNull()?.id == tenant.id && ui.receiptDraft?.id == receiptId) {
                        ui = ui.copy(receiptDraft = reviewed, receiptItemsPage = null,
                            receiptItemsReceiptId = null, receiptItemsRequestedPage = null,
                            receiptItemsError = null, receiptBasketReviewError = null)
                    }
                }
                .onFailure { failure ->
                    if (receiptBasketReviewGeneration.get() != generation || !ui.authenticated ||
                        ui.tenants.firstOrNull()?.id != tenant.id || ui.receiptDraft?.id != receiptId) return@onFailure
                    val status = (failure as? ApiFailure)?.status
                    if (status == 409 || status == 412) {
                        runCatching { api.receipt(tenant.id, receiptId) }
                            .onSuccess { latest ->
                                if (receiptBasketReviewGeneration.get() == generation && ui.authenticated &&
                                    ui.tenants.firstOrNull()?.id == tenant.id && ui.receiptDraft?.id == receiptId) {
                                    ui = ui.copy(receiptDraft = latest, receiptItemsPage = null,
                                        receiptItemsReceiptId = null, receiptItemsRequestedPage = null,
                                        receiptBasketReviewError = if (status == 412) "stale_version" else "conflict")
                                }
                            }
                            .onFailure {
                                if (receiptBasketReviewGeneration.get() == generation && ui.authenticated &&
                                    ui.tenants.firstOrNull()?.id == tenant.id && ui.receiptDraft?.id == receiptId) {
                                    ui = ui.copy(receiptBasketReviewError = "refresh_unavailable")
                                }
                            }
                    } else {
                        ui = ui.copy(receiptBasketReviewError = "unavailable")
                    }
                }
                .also {
                    if (receiptBasketReviewGeneration.get() == generation &&
                        ui.receiptBasketReviewingReceiptId == receiptId &&
                        ui.receiptBasketReviewingTenantId == tenant.id) {
                        ui = ui.copy(receiptBasketReviewing = false, receiptBasketReviewingReceiptId = null,
                            receiptBasketReviewingTenantId = null)
                    }
                }
        }
    }

    private fun refreshReceiptDuplicateReview(tenantId: String, receiptId: String, generation: Long,
                                              confirmError: String? = null, decisionError: String? = null,
                                              confirmationAttempt: Boolean) {
        runCatching {
            val latestReceipt = api.receipt(tenantId, receiptId)
            latestReceipt to api.receiptDuplicateCandidates(tenantId, receiptId)
        }.onSuccess { (latestReceipt, candidates) ->
            val currentGeneration = if (confirmationAttempt) receiptConfirmGeneration else receiptDuplicateGeneration
            if (currentGeneration.get() != generation ||
                !ui.authenticated || ui.tenants.firstOrNull()?.id != tenantId || ui.receiptDraft?.id != receiptId) return
            ui = ui.copy(receiptDraft = latestReceipt, receiptDuplicateCandidates = candidates,
                receiptDuplicateCandidatesReceiptId = receiptId, receiptDuplicateCandidatesLoading = false,
                receiptDuplicateCandidatesError = null, receiptDuplicateDecisionInProgress = false,
                receiptDuplicateDecisionError = decisionError, receiptConfirming = false,
                receiptConfirmError = confirmError)
        }.onFailure {
            val currentGeneration = if (confirmationAttempt) receiptConfirmGeneration else receiptDuplicateGeneration
            if (currentGeneration.get() != generation) return@onFailure
            if (!ui.authenticated || ui.tenants.firstOrNull()?.id != tenantId || ui.receiptDraft?.id != receiptId) return@onFailure
            ui = ui.copy(receiptDuplicateCandidates = null, receiptDuplicateCandidatesLoading = false,
                receiptDuplicateCandidatesError = "unavailable", receiptDuplicateDecisionInProgress = false,
                receiptDuplicateDecisionError = if (decisionError != null) "refresh_unavailable" else decisionError,
                receiptConfirming = false,
                receiptConfirmError = if (confirmError != null) "refresh_unavailable" else confirmError)
        }
    }

    private fun loadReceiptItems(receiptId: String, page: Int) {
        if (page < 1) return
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        if (ui.receiptDraft?.id != receiptId) return
        val generation = receiptItemsGeneration.incrementAndGet()
        ui = ui.copy(receiptItemsReceiptId = receiptId, receiptItemsRequestedPage = page,
            receiptItemsLoading = true, receiptItemsError = null)
        executor.execute {
            if (receiptItemsGeneration.get() != generation) return@execute
            runCatching { api.receiptItems(tenantId, receiptId, page) }
                .onSuccess { itemPage ->
                    if (receiptItemsGeneration.get() == generation && ui.authenticated &&
                        ui.tenants.firstOrNull()?.id == tenantId && ui.receiptDraft?.id == receiptId &&
                        ui.receiptItemsRequestedPage == page) {
                        ui = ui.copy(receiptItemsPage = itemPage, receiptItemsReceiptId = receiptId,
                            receiptItemsRequestedPage = page, receiptItemsLoading = false, receiptItemsError = null)
                    }
                }
                .onFailure {
                    if (receiptItemsGeneration.get() == generation && ui.authenticated &&
                        ui.tenants.firstOrNull()?.id == tenantId && ui.receiptDraft?.id == receiptId &&
                        ui.receiptItemsRequestedPage == page) {
                        ui = ui.copy(receiptItemsLoading = false, receiptItemsError = "unavailable")
                    }
                }
        }
    }

    private fun loadReceiptPriceComparison(requestedTenantId: String, receiptId: String, itemId: String) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        if (requestedTenantId != tenantId) return
        val receipt = ui.receiptDraft?.takeIf { it.id == receiptId && it.state == "confirmed" } ?: return
        val itemLoaded = receipt.items.any { it.id == itemId } ||
            (ui.receiptItemsReceiptId == receiptId && ui.receiptItemsPage?.items?.any { it.id == itemId } == true)
        if (!ui.authenticated || !itemLoaded) return
        if (ui.productPriceComparisonReceiptId == receiptId && ui.productPriceComparisonItemId == itemId &&
            ui.productPriceComparison != null) return
        if (ui.productPriceComparisonReceiptId == receiptId && ui.productPriceComparisonItemId == itemId &&
            ui.productPriceComparisonLoading) return
        val generation = receiptPriceComparisonGeneration.incrementAndGet()
        ui = ui.copy(productPriceComparison = null, productPriceComparisonReceiptId = receiptId,
            productPriceComparisonItemId = itemId, productPriceComparisonLoading = true,
            productPriceComparisonError = null)
        executor.execute {
            runCatching { api.receiptPriceHistory(tenantId, receiptId, itemId) }
                .onSuccess { comparison ->
                    if (receiptPriceComparisonGeneration.get() == generation && ui.authenticated &&
                        ui.tenants.firstOrNull()?.id == tenantId && ui.receiptDraft?.let {
                            it.id == receiptId && it.state == "confirmed"
                        } == true && ui.productPriceComparisonReceiptId == receiptId &&
                        ui.productPriceComparisonItemId == itemId) {
                        ui = ui.copy(productPriceComparison = comparison,
                            productPriceComparisonLoading = false, productPriceComparisonError = null)
                    }
                }
                .onFailure { failure ->
                    if (receiptPriceComparisonGeneration.get() != generation || !ui.authenticated ||
                        ui.tenants.firstOrNull()?.id != tenantId || ui.receiptDraft?.let {
                            it.id == receiptId && it.state == "confirmed"
                        } != true || ui.productPriceComparisonReceiptId != receiptId ||
                        ui.productPriceComparisonItemId != itemId) return@onFailure
                    if (failure is ApiFailure && failure.status == 401) {
                        expireAuthenticatedSession(failure)
                    } else {
                        ui = ui.copy(productPriceComparisonLoading = false,
                            productPriceComparisonError = "unavailable")
                    }
                }
        }
    }

    private fun loadProductCatalog(requestedTenantId: String, query: String) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        if (!ui.authenticated || requestedTenantId != tenantId) return
        val normalizedQuery = query.trim()
        val generation = synchronized(productCatalogStateLock) {
            val nextGeneration = productCatalogGeneration.incrementAndGet()
            ui = ui.copy(productCatalog = null, productCatalogTenantId = tenantId,
                productCatalogRequestedQuery = normalizedQuery, productCatalogLoading = true,
                productCatalogError = null)
            nextGeneration
        }
        executor.execute {
            runCatching { api.productCatalog(tenantId, normalizedQuery) }
                .onSuccess { catalog ->
                    runOnUiThread {
                        synchronized(productCatalogStateLock) {
                            if (isCurrentProductCatalogRequest(generation, tenantId, normalizedQuery) &&
                                catalog.query == normalizedQuery) {
                                ui = ui.copy(productCatalog = catalog, productCatalogLoading = false,
                                    productCatalogError = null)
                            }
                        }
                    }
                }
                .onFailure { failure ->
                    runOnUiThread {
                        synchronized(productCatalogStateLock) {
                            if (!isCurrentProductCatalogRequest(generation, tenantId, normalizedQuery)) return@synchronized
                            if (failure is ApiFailure && failure.status == 401) {
                                expireAuthenticatedSession(failure)
                            } else {
                                ui = ui.copy(productCatalogLoading = false, productCatalogError = "unavailable")
                            }
                        }
                    }
                }
        }
    }

    private fun isCurrentProductCatalogRequest(generation: Long, tenantId: String, query: String): Boolean =
        productCatalogGeneration.get() == generation && ui.authenticated &&
            ui.tenants.firstOrNull()?.id == tenantId && ui.productCatalogTenantId == tenantId &&
            ui.productCatalogRequestedQuery == query

    private fun invalidateProductCatalogRequests() {
        synchronized(productCatalogStateLock) {
            productCatalogGeneration.incrementAndGet()
            ui = ui.copy(productCatalog = null, productCatalogTenantId = null,
                productCatalogRequestedQuery = "", productCatalogLoading = false,
                productCatalogError = null)
        }
    }

    private fun expireAuthenticatedSession(failure: ApiFailure? = null) {
        runOnUiThread {
            synchronized(productCatalogStateLock) {
                if (failure?.authSessionGeneration != null && api.hasSession() &&
                    failure.authSessionGeneration != api.authSessionGeneration) return@synchronized
                productCatalogGeneration.incrementAndGet()
                receiptRecalculationGeneration.incrementAndGet()
                ui = FinanceUiState(error = "Sign in again")
            }
        }
    }

    private fun loadReceiptDisputedItems(receiptId: String, page: Int) {
        if (page < 1) return
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        if (!ui.authenticated || ui.receiptDraft?.id != receiptId) return
        val generation = receiptDisputedItemsGeneration.incrementAndGet()
        ui = ui.copy(receiptDisputedItemsReceiptId = receiptId,
            receiptDisputedItemsRequestedPage = page, receiptDisputedItemsLoading = true,
            receiptDisputedItemsError = null)
        executor.execute {
            if (receiptDisputedItemsGeneration.get() != generation) return@execute
            runCatching {
                api.disputedReceiptItems(tenantId, receiptId, page) to api.productDecisions(tenantId)
            }.onSuccess { (items, decisions) ->
                if (receiptDisputedItemsGeneration.get() == generation && ui.authenticated &&
                    ui.tenants.firstOrNull()?.id == tenantId && ui.receiptDraft?.id == receiptId &&
                    ui.receiptDisputedItemsRequestedPage == page) {
                    ui = ui.copy(receiptDisputedItemsPage = items, receiptDisputedItemsReceiptId = receiptId,
                        receiptDisputedItemsRequestedPage = page, receiptDisputedItemsLoading = false,
                        receiptDisputedItemsError = null, productDecisions = decisions)
                }
            }.onFailure {
                if (receiptDisputedItemsGeneration.get() == generation && ui.authenticated &&
                    ui.tenants.firstOrNull()?.id == tenantId && ui.receiptDraft?.id == receiptId &&
                    ui.receiptDisputedItemsRequestedPage == page) {
                    // Keep the last Core page visible; retry can recover without losing the user's place.
                    ui = ui.copy(receiptDisputedItemsLoading = false, receiptDisputedItemsError = "unavailable")
                }
            }
        }
    }

    private fun loadReceiptRepeatWarnings(receiptId: String) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        val receipt = ui.receiptDraft?.takeIf { it.id == receiptId } ?: return
        if (!ui.authenticated || receipt.state != "review_required" || receipt.itemCount <= 0) return
        val revision = receiptRepeatWarningsRevision(receipt)
        if (ui.receiptRepeatWarningsLoading && ui.receiptRepeatWarningsReceiptId == receiptId &&
            ui.receiptRepeatWarningsRevision == revision) return
        val generation = receiptRepeatWarningsGeneration.incrementAndGet()
        ui = ui.copy(receiptRepeatWarningsReceiptId = receiptId,
            receiptRepeatWarnings = ui.receiptRepeatWarnings.takeIf {
                ui.receiptRepeatWarningsRevision == revision
            },
            receiptRepeatWarningsRevision = revision,
            receiptRepeatWarningsLoading = true, receiptRepeatWarningsError = null)
        executor.execute {
            if (receiptRepeatWarningsGeneration.get() != generation) return@execute
            runCatching { api.receiptRepeatWarnings(tenantId, receiptId) }
                .onSuccess { warnings ->
                    if (ui.authenticated && ui.tenants.firstOrNull()?.id == tenantId &&
                        receiptRepeatWarningsResponseMatches(generation, receiptRepeatWarningsGeneration.get(),
                            revision, ui.receiptDraft?.takeIf { it.id == receiptId }
                                ?.let(::receiptRepeatWarningsRevision)) &&
                        ui.receiptDraft?.state == "review_required") {
                        ui = ui.copy(receiptRepeatWarnings = warnings,
                            receiptRepeatWarningsReceiptId = receiptId,
                            receiptRepeatWarningsRevision = revision,
                            receiptRepeatWarningsLoading = false, receiptRepeatWarningsError = null)
                    }
                }
                .onFailure {
                    if (ui.authenticated && ui.tenants.firstOrNull()?.id == tenantId &&
                        receiptRepeatWarningsResponseMatches(generation, receiptRepeatWarningsGeneration.get(),
                            revision, ui.receiptDraft?.takeIf { it.id == receiptId }
                                ?.let(::receiptRepeatWarningsRevision)) &&
                        ui.receiptDraft?.state == "review_required") {
                        ui = ui.copy(receiptRepeatWarningsLoading = false,
                            receiptRepeatWarningsError = "unavailable")
                    }
                }
        }
    }

    private fun decideReceiptDisputedProduct(productKey: String, action: String) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        val receiptId = ui.receiptDraft?.id ?: return
        if (!ui.authenticated || !canReviewReceiptBasket(ui.tenants.firstOrNull()?.role) ||
            ui.receiptDisputedItemsLoading || action !in setOf("allow", "revoke") || productKey.isBlank()) return
        val page = ui.receiptDisputedItemsPage?.page?.takeIf {
            ui.receiptDisputedItemsReceiptId == receiptId
        } ?: 1
        val refreshDoNotBuy = ui.doNotBuy != null
        val refreshShopping = ui.shoppingList != null
        val refreshRepeatWarnings = shouldRefreshReceiptRepeatWarnings(receiptId,
            ui.receiptRepeatWarningsReceiptId, ui.receiptRepeatWarnings,
            loading = ui.receiptRepeatWarningsLoading)
        val generation = receiptDisputedItemsGeneration.incrementAndGet()
        val sessionGeneration = ReceiptOperationGeneration.capture()
        ui = ui.copy(receiptDisputedItemsReceiptId = receiptId,
            receiptDisputedItemsRequestedPage = page, receiptDisputedItemsLoading = true,
            receiptDisputedItemsError = null)
        executor.execute {
            if (receiptDisputedItemsGeneration.get() != generation) return@execute
            var warningRefreshGeneration: Long? = null
            val result = runCatching {
                applyReceiptDisputedDecisionAndRefresh(
                    applyDecision = { api.decideDoNotBuy(tenantId, productKey, action) },
                    loadPage = { api.disputedReceiptItems(tenantId, receiptId, page) },
                    loadDecisions = { api.productDecisions(tenantId) },
                    invalidateWarnings = if (refreshRepeatWarnings) {
                        { warningRefreshGeneration = receiptRepeatWarningsGeneration.incrementAndGet() }
                    } else null,
                    loadWarnings = if (refreshRepeatWarnings) {
                        {
                            val currentReceipt = ui.receiptDraft?.takeIf { it.id == receiptId }
                                ?: error("Receipt changed before repeat warnings refresh")
                            val revision = receiptRepeatWarningsRevision(currentReceipt)
                            val warningGeneration = receiptRepeatWarningsGeneration.incrementAndGet()
                            warningRefreshGeneration = warningGeneration
                            ui = ui.copy(receiptRepeatWarningsReceiptId = receiptId,
                                receiptRepeatWarningsRevision = revision,
                                receiptRepeatWarningsLoading = true, receiptRepeatWarningsError = null)
                            val warnings = api.receiptRepeatWarnings(tenantId, receiptId)
                            if (!receiptRepeatWarningsResponseMatches(warningGeneration,
                                    receiptRepeatWarningsGeneration.get(), revision,
                                    ui.receiptDraft?.takeIf { it.id == receiptId }
                                        ?.let(::receiptRepeatWarningsRevision))) {
                                error("Receipt revision changed while refreshing repeat warnings")
                            }
                            ui = ui.copy(receiptRepeatWarnings = warnings,
                                receiptRepeatWarningsReceiptId = receiptId,
                                receiptRepeatWarningsRevision = revision,
                                receiptRepeatWarningsLoading = false, receiptRepeatWarningsError = null)
                            warnings
                        }
                    } else null,
                )
            }
            val mutationError = result.exceptionOrNull() as? ReceiptDisputedDecisionMutationFailure
            val mutationMayHaveApplied = result.isSuccess ||
                result.exceptionOrNull() is ReceiptDisputedDecisionRefreshFailure ||
                mutationError?.mayHaveApplied == true
            val refreshedDoNotBuy = if (mutationMayHaveApplied && refreshDoNotBuy) {
                runCatching { api.doNotBuy(tenantId) }
            } else null
            val shoppingRequest = if (mutationMayHaveApplied && refreshShopping) {
                ShoppingResponsePolicy.beginRequest(shoppingRequestGeneration)
            } else null
            val refreshedShopping = if (shoppingRequest != null) {
                runCatching { api.shoppingCandidates(tenantId) }
            } else null
            fun publishShoppingRefresh() {
                val response = refreshedShopping ?: return
                val request = shoppingRequest ?: return
                ShoppingResponsePolicy.applyIfCurrent(
                    sessionGeneration = sessionGeneration,
                    requestTenantId = tenantId,
                    activeTenantId = { ui.tenants.firstOrNull()?.id },
                    authenticated = { ui.authenticated },
                    requestGeneration = request,
                    currentRequestGeneration = { shoppingRequestGeneration.get() },
                ) {
                    response.fold(
                        onSuccess = { shopping -> ui = ui.copy(shoppingList = shopping, shoppingError = null) },
                        onFailure = { ui = ui.copy(shoppingError = "unavailable") },
                    )
                }
            }
            result.onSuccess { snapshot ->
                if (receiptDisputedItemsGeneration.get() == generation && ui.authenticated &&
                    ui.tenants.firstOrNull()?.id == tenantId && ui.receiptDraft?.id == receiptId) {
                    ui = ui.copy(receiptDisputedItemsPage = snapshot.page, receiptDisputedItemsReceiptId = receiptId,
                        receiptDisputedItemsRequestedPage = page, receiptDisputedItemsLoading = false,
                        receiptDisputedItemsError = null, productDecisions = snapshot.decisions,
                        receiptRepeatWarnings = if (warningRefreshGeneration == receiptRepeatWarningsGeneration.get())
                            snapshot.warnings ?: ui.receiptRepeatWarnings else ui.receiptRepeatWarnings,
                        receiptRepeatWarningsReceiptId = if (warningRefreshGeneration == receiptRepeatWarningsGeneration.get() &&
                            snapshot.warnings != null) receiptId else ui.receiptRepeatWarningsReceiptId,
                        receiptRepeatWarningsLoading = if (warningRefreshGeneration == receiptRepeatWarningsGeneration.get())
                            false else ui.receiptRepeatWarningsLoading,
                        receiptRepeatWarningsError = if (warningRefreshGeneration == receiptRepeatWarningsGeneration.get())
                            receiptRepeatWarningErrorAfterDecision(ui.receiptRepeatWarningsError,
                                refreshRepeatWarnings, mutationMayHaveApplied, snapshot.warnings)
                            else ui.receiptRepeatWarningsError,
                        doNotBuy = refreshedDoNotBuy?.getOrNull() ?: ui.doNotBuy,
                        doNotBuyError = when {
                            refreshedDoNotBuy?.isFailure == true -> "unavailable"
                            refreshedDoNotBuy?.isSuccess == true -> null
                            else -> ui.doNotBuyError
                        },
                        )
                    publishShoppingRefresh()
                }
            }.onFailure { error ->
                if (receiptDisputedItemsGeneration.get() == generation && ui.authenticated &&
                    ui.tenants.firstOrNull()?.id == tenantId && ui.receiptDraft?.id == receiptId) {
                    // Preserve prior Core data; a later refresh error never implies the mutation was rolled back.
                    val errorCode = when (error) {
                        is ReceiptDisputedDecisionRefreshFailure -> "decision_refresh"
                        else -> "decision_failed"
                    }
                    val reconciled = (error as? ReceiptDisputedDecisionMutationFailure)?.snapshot
                    ui = ui.copy(receiptDisputedItemsPage = reconciled?.page ?: ui.receiptDisputedItemsPage,
                        receiptDisputedItemsReceiptId = if (reconciled != null) receiptId else ui.receiptDisputedItemsReceiptId,
                        receiptDisputedItemsRequestedPage = if (reconciled != null) page else ui.receiptDisputedItemsRequestedPage,
                        productDecisions = reconciled?.decisions ?: ui.productDecisions,
                        receiptRepeatWarnings = if (warningRefreshGeneration == receiptRepeatWarningsGeneration.get())
                            reconciled?.warnings ?: ui.receiptRepeatWarnings else ui.receiptRepeatWarnings,
                        receiptRepeatWarningsReceiptId = if (warningRefreshGeneration == receiptRepeatWarningsGeneration.get() &&
                            reconciled?.warnings != null) receiptId else ui.receiptRepeatWarningsReceiptId,
                        receiptRepeatWarningsLoading = if (warningRefreshGeneration == receiptRepeatWarningsGeneration.get() &&
                            refreshRepeatWarnings && mutationMayHaveApplied) false else ui.receiptRepeatWarningsLoading,
                        receiptRepeatWarningsError = if (warningRefreshGeneration == receiptRepeatWarningsGeneration.get())
                            receiptRepeatWarningErrorAfterDecision(ui.receiptRepeatWarningsError,
                                refreshRepeatWarnings, mutationMayHaveApplied, reconciled?.warnings)
                            else ui.receiptRepeatWarningsError,
                        receiptDisputedItemsLoading = false, receiptDisputedItemsError = errorCode,
                        doNotBuy = refreshedDoNotBuy?.getOrNull() ?: ui.doNotBuy,
                        doNotBuyError = when {
                            refreshedDoNotBuy?.isFailure == true -> "unavailable"
                            refreshedDoNotBuy?.isSuccess == true -> null
                            else -> ui.doNotBuyError
                        },
                        )
                    publishShoppingRefresh()
                }
            }
        }
    }

    private fun updateReceiptItem(receiptId: String, itemId: String, receiptVersion: Long,
                                  name: String, quantity: String, unitPrice: String, lineSum: String) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        if (!ui.authenticated || ui.receiptDraft?.id != receiptId || ui.busy) return
        val generation = receiptItemEditGeneration.incrementAndGet()
        val page = ui.receiptItemsPage?.page?.takeIf { ui.receiptItemsReceiptId == receiptId } ?: 1
        ui = ui.copy(busy = true, receiptItemEditError = null, receiptItemEditSavedToken = null)
        executor.execute {
            if (receiptItemEditGeneration.get() != generation) return@execute
            val result = updateReceiptItemWithPageRefresh(
                update = {
                    api.updateReceiptItem(tenantId, receiptId, itemId, receiptVersion,
                        name, quantity.takeIf { it.isNotBlank() }, unitPrice.takeIf { it.isNotBlank() },
                        lineSum.takeIf { it.isNotBlank() })
                },
                refreshPage = { updated ->
                    if (page == 1) FinanceReceiptItemPage(updated.items.take(8), 1,
                        updated.itemCount, updated.itemCount > 8)
                    else api.receiptItems(tenantId, receiptId, page)
                },
            )
            if (result.mutationError == null) {
                val updated = requireNotNull(result.updatedReceipt)
                val updatedPage = result.updatedPage
                if (receiptItemEditGeneration.get() == generation && ui.authenticated &&
                    ui.tenants.firstOrNull()?.id == tenantId && ui.receiptDraft?.id == receiptId) {
                    ui = ui.copy(busy = false, receiptDraft = updated,
                        receiptItemsPage = updatedPage,
                        receiptItemsReceiptId = receiptId, receiptItemsRequestedPage = page,
                        receiptItemsLoading = false,
                        receiptItemsError = if (result.pageRefreshError == null) null else "unavailable",
                        receiptItemEditError = null, receiptTotalSyncError = null,
                        receiptItemEditSavedToken = "$receiptId:$itemId:${updated.version}")
                }
            } else {
                val error = requireNotNull(result.mutationError)
                if (receiptItemEditGeneration.get() == generation && ui.authenticated &&
                    ui.tenants.firstOrNull()?.id == tenantId && ui.receiptDraft?.id == receiptId) {
                    ui = ui.copy(busy = false,
                        receiptItemEditError = if ((error as? ApiFailure)?.status == 412) "stale_version" else "unavailable")
                }
            }
        }
    }

    private fun addReceiptItem(receiptId: String, receiptVersion: Long, name: String, quantity: String,
                               unitPrice: String, lineSum: String) {
        val tenant = ui.tenants.firstOrNull() ?: return
        val receipt = ui.receiptDraft?.takeIf { it.id == receiptId } ?: return
        if (!ui.authenticated || tenant.role == "viewer" || ui.busy) return
        if (receipt.itemCount >= 200) {
            ui = ui.copy(receiptItemAddError = "item_limit", receiptItemAddNeedsRefresh = false)
            return
        }
        val explicitAmbiguousRetry = ui.receiptItemAddNeedsRefresh && ui.receiptItemAddError == "ambiguous"
        if (ui.receiptItemAddNeedsRefresh && !explicitAmbiguousRetry) return
        val baseline = pendingReceiptItemAdd?.takeIf { it.receiptId == receiptId }
        val attempt = if (explicitAmbiguousRetry && baseline != null) baseline.copy(
            baselineVersion = receiptVersion,
            baselineItemCount = receipt.itemCount,
            baselineItemIds = (baseline.baselineItemIds + receipt.items.map { it.id } + ui.receiptItemsPage
                ?.takeIf { ui.receiptItemsReceiptId == receiptId }?.items.orEmpty().map { it.id }).toSet(),
            name = name, quantity = quantity, unitPrice = unitPrice, lineSum = lineSum,
        ) else ReceiptItemAddAttempt(tenant.id, receiptId, receiptVersion, receipt.itemCount,
            (receipt.items + ui.receiptItemsPage
                ?.takeIf { ui.receiptItemsReceiptId == receiptId }?.items.orEmpty()).mapTo(mutableSetOf()) { it.id },
            name, quantity, unitPrice, lineSum)
        pendingReceiptItemAdd = attempt
        val generation = receiptItemAddGeneration.incrementAndGet()
        val page = ui.receiptItemsPage?.page?.takeIf { ui.receiptItemsReceiptId == receiptId } ?: 1
        ui = ui.copy(busy = true, receiptItemAddError = null, receiptItemAddNeedsRefresh = false,
            receiptItemAddSavedToken = null, receiptItemsError = null)
        executor.execute {
            if (receiptItemAddGeneration.get() != generation) return@execute
            var postStarted = false
            try {
                val baselineItems = loadAllReceiptItems(tenant.id, receipt, generation)
                val observedAttempt = attempt.copy(baselineItemIds =
                    (attempt.baselineItemIds + baselineItems.map { it.id }).toSet())
                pendingReceiptItemAdd = observedAttempt
                postStarted = true
                val updated = api.addReceiptItem(tenant.id, receiptId, receiptVersion, name,
                    quantity.takeIf { it.isNotBlank() },
                    unitPrice.takeIf { it.isNotBlank() },
                    lineSum.takeIf { it.isNotBlank() })
                if (!isCurrentReceiptItemAdd(generation, tenant.id, receiptId)) return@execute
                val firstPage = FinanceReceiptItemPage(updated.items.take(8), 1, updated.itemCount,
                    updated.itemCount > 8)
                val previousPage = ui.receiptItemsPage?.takeIf { ui.receiptItemsReceiptId == receiptId }
                ui = ui.copy(busy = false, receiptDraft = updated,
                    receiptItemsPage = if (page == 1) firstPage else previousPage,
                    receiptItemsReceiptId = receiptId, receiptItemsRequestedPage = page,
                    receiptItemsLoading = page > 1, receiptItemsError = null,
                    receiptItemAddError = null, receiptItemAddNeedsRefresh = false,
                    receiptItemAddSavedToken = "$receiptId:${updated.version}")
                pendingReceiptItemAdd = null
                if (page > 1) {
                    runCatching { api.receiptItems(tenant.id, receiptId, page) }
                        .onSuccess { currentPage ->
                            if (isCurrentReceiptItemAdd(generation, tenant.id, receiptId)) {
                                ui = ui.copy(receiptItemsPage = currentPage, receiptItemsReceiptId = receiptId,
                                    receiptItemsRequestedPage = page, receiptItemsLoading = false,
                                    receiptItemsError = null)
                            }
                        }
                        .onFailure {
                            if (isCurrentReceiptItemAdd(generation, tenant.id, receiptId)) {
                                ui = ui.copy(receiptItemsLoading = false, receiptItemsError = "unavailable")
                            }
                        }
                }
            } catch (error: Throwable) {
                if (!isCurrentReceiptItemAdd(generation, tenant.id, receiptId)) return@execute
                val status = (error as? ApiFailure)?.status
                val baselineReadFailed = !postStarted
                val requiresRefresh = !baselineReadFailed &&
                    (status == 409 || status == 412 || status == null || status >= 500)
                ui = ui.copy(busy = false,
                    receiptItemAddError = when (status) {
                        400, 422 -> "invalid_item"
                        409 -> "conflict"
                        412 -> "stale_version"
                        else -> "network_unavailable"
                    }, receiptItemAddNeedsRefresh = requiresRefresh)
                if (!requiresRefresh) pendingReceiptItemAdd = null
            }
        }
    }

    private var pendingReceiptItemAdd: ReceiptItemAddAttempt? = null

    private fun isCurrentReceiptItemAdd(generation: Long, tenantId: String, receiptId: String): Boolean =
        receiptItemAddGeneration.get() == generation && ui.authenticated &&
            ui.tenants.firstOrNull()?.id == tenantId && ui.receiptDraft?.id == receiptId

    private fun refreshReceiptItemAdd(receiptId: String) {
        val attempt = pendingReceiptItemAdd?.takeIf { it.receiptId == receiptId } ?: return
        if (!ui.authenticated || ui.receiptDraft?.id != receiptId || ui.busy) return
        val generation = receiptItemAddGeneration.incrementAndGet()
        ui = ui.copy(busy = true)
        executor.execute {
            if (!isCurrentReceiptItemAdd(generation, attempt.tenantId, receiptId)) return@execute
            val refreshed = runCatching { api.receipt(attempt.tenantId, receiptId) }
            if (refreshed.isFailure) {
                if (isCurrentReceiptItemAdd(generation, attempt.tenantId, receiptId)) {
                    ui = ui.copy(busy = false, receiptItemAddError = "refresh_unavailable",
                        receiptItemAddNeedsRefresh = true)
                }
                return@execute
            }
            val receipt = refreshed.getOrThrow()
            val currentPage = ui.receiptItemsPage?.page?.takeIf { ui.receiptItemsReceiptId == receiptId } ?: 1
            val observedItems = runCatching { loadAllReceiptItems(attempt.tenantId, receipt, generation) }
            if (receiptItemAddGeneration.get() != generation || !ui.authenticated ||
                ui.tenants.firstOrNull()?.id != attempt.tenantId || ui.receiptDraft?.id != receiptId) return@execute
            if (observedItems.isFailure) {
                ui = ui.copy(busy = false, receiptDraft = receipt, receiptItemAddError = "refresh_unavailable",
                    receiptItemAddNeedsRefresh = true, receiptItemsError = "unavailable")
                return@execute
            }
            val allObservedItems = observedItems.getOrThrow()
            val observedIds = allObservedItems.mapTo(mutableSetOf()) { it.id }
            val newItems = allObservedItems.filterNot { it.id in attempt.baselineItemIds }
            val matchingAddedItems = newItems.filter { it.matches(attempt) }
            val committed = receipt.itemCount == attempt.baselineItemCount + 1 && matchingAddedItems.size == 1
            val unchanged = receipt.itemCount == attempt.baselineItemCount &&
                newItems.isEmpty() && observedIds == attempt.baselineItemIds
            val pageCount = (receipt.itemCount + 7) / 8
            val pageItems = allObservedItems.drop((currentPage - 1) * 8).take(8)
            val page = FinanceReceiptItemPage(pageItems, currentPage, receipt.itemCount, currentPage < pageCount)
            if (committed) {
                pendingReceiptItemAdd = null
                ui = ui.copy(busy = false, receiptDraft = receipt, receiptItemsPage = page,
                    receiptItemsReceiptId = receiptId, receiptItemsRequestedPage = currentPage,
                    receiptItemsLoading = false, receiptItemsError = null, receiptItemAddError = null,
                    receiptItemAddNeedsRefresh = false,
                    receiptItemAddSavedToken = "$receiptId:${receipt.version}")
            } else if (unchanged) {
                pendingReceiptItemAdd = attempt.copy(baselineVersion = receipt.version,
                    baselineItemCount = receipt.itemCount,
                    baselineItemIds = observedIds)
                ui = ui.copy(busy = false, receiptDraft = receipt, receiptItemsPage = page,
                    receiptItemsReceiptId = receiptId, receiptItemsRequestedPage = currentPage,
                    receiptItemsLoading = false, receiptItemsError = null, receiptItemAddError = null,
                    receiptItemAddNeedsRefresh = false)
            } else {
                pendingReceiptItemAdd = attempt.copy(baselineVersion = receipt.version,
                    baselineItemCount = receipt.itemCount,
                    baselineItemIds = observedIds)
                ui = ui.copy(busy = false, receiptDraft = receipt, receiptItemsPage = page,
                    receiptItemsReceiptId = receiptId, receiptItemsRequestedPage = currentPage,
                    receiptItemsLoading = false, receiptItemsError = null,
                    receiptItemAddError = "ambiguous", receiptItemAddNeedsRefresh = true)
            }
        }
    }

    private data class ReceiptItemAddAttempt(
        val tenantId: String,
        val receiptId: String,
        val baselineVersion: Long,
        val baselineItemCount: Int,
        val baselineItemIds: Set<String>,
        val name: String,
        val quantity: String,
        val unitPrice: String,
        val lineSum: String,
    )

    private fun loadAllReceiptItems(tenantId: String, receipt: FinanceReceipt, generation: Long): List<FinanceReceiptItem> {
        val items = receipt.items.toMutableList()
        val pageCount = (receipt.itemCount + 7) / 8
        for (page in 2..pageCount) {
            if (receiptItemAddGeneration.get() != generation) throw java.util.concurrent.CancellationException()
            items += api.receiptItems(tenantId, receipt.id, page).items
        }
        return items.distinctBy { it.id }
    }

    private fun FinanceReceiptItem.matches(attempt: ReceiptItemAddAttempt): Boolean =
        name == attempt.name.trim() && quantity.sameReceiptNumber(attempt.quantity) &&
            unitPrice.sameReceiptNumber(attempt.unitPrice) && lineSum.sameReceiptNumber(attempt.lineSum)

    private fun String?.sameReceiptNumber(input: String): Boolean {
        val expected = input.takeIf { it.isNotBlank() }
        if (this == null || expected == null) return this == expected
        val actualNumber = toBigDecimalOrNull() ?: return false
        val expectedNumber = expected.toBigDecimalOrNull() ?: return false
        return actualNumber.compareTo(expectedNumber) == 0
    }

    private fun refreshReceiptAfterItemConflict(receiptId: String) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        if (!ui.authenticated || ui.receiptDraft?.id != receiptId || ui.busy) return
        val generation = receiptItemEditGeneration.incrementAndGet()
        val page = ui.receiptItemsPage?.page?.takeIf { ui.receiptItemsReceiptId == receiptId } ?: 1
        val previousPage = ui.receiptItemsPage?.takeIf { ui.receiptItemsReceiptId == receiptId }
        ui = ui.copy(busy = true, receiptItemEditError = null)
        executor.execute {
            if (receiptItemEditGeneration.get() != generation) return@execute
            val result = refreshReceiptAfterConflictWithPage(
                currentPage = previousPage,
                refreshReceipt = { api.receipt(tenantId, receiptId) },
                refreshPage = { updated ->
                    if (page == 1) FinanceReceiptItemPage(updated.items.take(8), 1,
                        updated.itemCount, updated.itemCount > 8)
                    else api.receiptItems(tenantId, receiptId, page)
                },
            )
            if (receiptItemEditGeneration.get() != generation || !ui.authenticated ||
                ui.tenants.firstOrNull()?.id != tenantId || ui.receiptDraft?.id != receiptId) return@execute
            if (result.receiptRefreshError != null) {
                ui = ui.copy(busy = false, receiptItemEditError = "refresh_unavailable")
            } else {
                val updated = requireNotNull(result.updatedReceipt)
                ui = ui.copy(busy = false, receiptDraft = updated, receiptItemEditError = null,
                    receiptItemsPage = result.page, receiptItemsReceiptId = receiptId,
                    receiptItemsRequestedPage = page, receiptItemsLoading = false,
                    receiptItemsError = if (result.pageRefreshError == null) null else "unavailable")
            }
        }
    }

    private fun syncReceiptTotal(receiptId: String, receiptVersion: Long) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        if (!ui.authenticated || ui.receiptDraft?.id != receiptId || ui.receiptDraft?.version != receiptVersion ||
            ui.tenants.firstOrNull()?.role == "viewer" || ui.busy) return
        val generation = receiptTotalSyncGeneration.incrementAndGet()
        val page = ui.receiptItemsPage?.page?.takeIf { ui.receiptItemsReceiptId == receiptId } ?: 1
        val previousPage = ui.receiptItemsPage?.takeIf { ui.receiptItemsReceiptId == receiptId }
        ui = ui.copy(receiptTotalSyncInProgress = true, receiptTotalSyncError = null)
        executor.execute {
            if (receiptTotalSyncGeneration.get() != generation) return@execute
            val result = updateReceiptItemWithPageRefresh(
                update = { api.syncReceiptTotal(tenantId, receiptId, receiptVersion) },
                refreshPage = { updated ->
                    if (page == 1) FinanceReceiptItemPage(updated.items.take(8), 1,
                        updated.itemCount, updated.itemCount > 8)
                    else api.receiptItems(tenantId, receiptId, page)
                },
            )
            if (receiptTotalSyncGeneration.get() != generation || !ui.authenticated ||
                ui.tenants.firstOrNull()?.id != tenantId || ui.receiptDraft?.id != receiptId) return@execute
            if (result.mutationError != null) {
                val status = (result.mutationError as? ApiFailure)?.status
                ui = ui.copy(receiptTotalSyncInProgress = false,
                    receiptTotalSyncError = when (status) {
                        409 -> "incomplete_items"
                        412 -> "stale_version"
                        else -> "unavailable"
                    })
            } else {
                val updated = requireNotNull(result.updatedReceipt)
                ui = ui.copy(receiptTotalSyncInProgress = false, receiptDraft = updated,
                    receiptItemsPage = result.updatedPage ?: previousPage,
                    receiptItemsReceiptId = receiptId, receiptItemsRequestedPage = page,
                    receiptItemsLoading = false,
                    receiptItemsError = if (result.pageRefreshError == null) null else "unavailable",
                    receiptTotalSyncError = null)
            }
        }
    }

    private fun refreshReceiptTotalSync(receiptId: String) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        if (!ui.authenticated || ui.receiptDraft?.id != receiptId || ui.busy) return
        val generation = receiptTotalSyncGeneration.incrementAndGet()
        val page = ui.receiptItemsPage?.page?.takeIf { ui.receiptItemsReceiptId == receiptId } ?: 1
        val previousPage = ui.receiptItemsPage?.takeIf { ui.receiptItemsReceiptId == receiptId }
        ui = ui.copy(receiptTotalSyncInProgress = true)
        executor.execute {
            if (receiptTotalSyncGeneration.get() != generation) return@execute
            val result = refreshReceiptAfterConflictWithPage(
                currentPage = previousPage,
                refreshReceipt = { api.receipt(tenantId, receiptId) },
                refreshPage = { updated ->
                    if (page == 1) FinanceReceiptItemPage(updated.items.take(8), 1,
                        updated.itemCount, updated.itemCount > 8)
                    else api.receiptItems(tenantId, receiptId, page)
                },
            )
            if (receiptTotalSyncGeneration.get() != generation || !ui.authenticated ||
                ui.tenants.firstOrNull()?.id != tenantId || ui.receiptDraft?.id != receiptId) return@execute
            if (result.receiptRefreshError != null) {
                ui = ui.copy(receiptTotalSyncInProgress = false, receiptTotalSyncError = "refresh_unavailable")
            } else {
                val updated = requireNotNull(result.updatedReceipt)
                ui = ui.copy(receiptTotalSyncInProgress = false, receiptDraft = updated,
                    receiptItemsPage = result.page, receiptItemsReceiptId = receiptId,
                    receiptItemsRequestedPage = page, receiptItemsLoading = false,
                    receiptItemsError = if (result.pageRefreshError == null) null else "unavailable",
                    receiptTotalSyncError = null)
            }
        }
    }

    private fun restoreReceiptCheckpointForTenant(tenantId: String?, operationToken: Long = ReceiptOperationGeneration.capture()) {
        if (!ReceiptOperationGeneration.isCurrent(operationToken)) return
        val checkpointResult = ReceiptOperationGeneration.runIfCurrent(operationToken) {
            runCatching { receiptCheckpointStore.load() }
        } ?: return
        val checkpoint = checkpointResult.getOrElse {
            ReceiptOperationGeneration.runIfCurrent(operationToken) {
                ui = ui.copy(receiptUploadError = "checkpoint", receiptCheckpointUnresolved = true)
            }
            return
        }
        ReceiptOperationGeneration.runIfCurrent(operationToken) {
            reconcilePersistedReceiptUriGrants(checkpoint)
        } ?: return
        if (checkpoint == null) {
            ReceiptOperationGeneration.runIfCurrent(operationToken) {
                if (ui.receiptJob == null) ui = ui.copy(receiptCheckpointUnresolved = false)
            }
            return
        }

        ReceiptOperationGeneration.runIfCurrent(operationToken) {
            ui = ui.copy(receiptCheckpointUnresolved = true)
        } ?: return
        if (tenantId == null || checkpoint.tenantId != tenantId) {
            ReceiptOperationGeneration.runIfCurrent(operationToken) { ui = ui.copy(receiptUploadError = "checkpoint_workspace") }
            return
        }

        val jobId = checkpoint.jobId
        if (jobId != null) {
            if (ui.receiptJob?.id == jobId || receiptRestoreInProgressTenantId == tenantId) return
            ReceiptOperationGeneration.runIfCurrent(operationToken) { receiptRestoreInProgressTenantId = tenantId }
                ?: return
            ReceiptOperationGeneration.runIfCurrent(operationToken) {
                ui = ui.copy(busy = true, receiptUploadInProgress = false, receiptUploadError = null)
            } ?: return
            executor.execute {
                if (!ReceiptOperationGeneration.isCurrent(operationToken)) return@execute
                runCatching {
                    if (!ReceiptOperationGeneration.isCurrent(operationToken)) return@execute
                    api.receiptJob(tenantId, jobId)
                }
                    .onSuccess { job ->
                        if (ReceiptOperationGeneration.isCurrent(operationToken) && ui.authenticated &&
                            ui.tenants.firstOrNull()?.id == tenantId && ui.receiptCheckpointUnresolved) {
                            ReceiptOperationGeneration.runIfCurrent(operationToken) {
                                ui = ui.copy(receiptJob = job, receiptDraft = null, receiptCanRetryUpload = false)
                            } ?: return@onSuccess
                            pollReceiptJob(tenantId, job, operationToken)
                        }
                    }
                    .onFailure { failure ->
                        if (ReceiptOperationGeneration.isCurrent(operationToken) && ui.authenticated && ui.tenants.firstOrNull()?.id == tenantId) {
                            ReceiptOperationGeneration.runIfCurrent(operationToken) {
                                ui = ui.copy(busy = false, receiptUploadInProgress = false,
                                    receiptUploadError = receiptUploadErrorCode(failure),
                                    receiptCheckpointUnresolved = true)
                            }
                        }
                    }
                ReceiptOperationGeneration.runIfCurrent(operationToken) { receiptRestoreInProgressTenantId = null }
            }
            return
        }

        val photoUri = checkpoint.photoUri
        val idempotencyKey = checkpoint.idempotencyKey
        if (photoUri != null && idempotencyKey != null) {
            ReceiptOperationGeneration.runIfCurrent(operationToken) {
                pendingReceiptUri = Uri.parse(photoUri)
                pendingReceiptKey = idempotencyKey
                ui = ui.copy(receiptJob = null, receiptDraft = null, receiptUploadError = null,
                    receiptCanRetryUpload = true)
            } ?: return
            uploadSelectedReceiptPhoto(operationToken)
        }
    }

    private fun pollReceiptJob(tenantId: String, initial: FinanceReceiptProcessingJob,
                               operationToken: Long = ReceiptOperationGeneration.capture()) {
        if (!ReceiptOperationGeneration.isCurrent(operationToken)) return
        val generation = ReceiptOperationGeneration.runIfCurrent(operationToken) {
            val pollGeneration = invalidateReceiptPoll()
            ui = ui.copy(busy = false, receiptJob = initial)
            pollGeneration
        } ?: return
        scheduleReceiptPoll(tenantId, initial, 0, generation, operationToken)
    }

    private fun scheduleReceiptPoll(tenantId: String, job: FinanceReceiptProcessingJob, attempt: Int,
                                    generation: Long, operationToken: Long) {
        if (!ReceiptOperationGeneration.isCurrent(operationToken) || receiptPollGeneration.get() != generation ||
            !isCurrentReceiptJob(tenantId, job.id)) return
        when (job.state) {
            "completed" -> {
                receiptPollExecutor.execute {
                    if (!ReceiptOperationGeneration.isCurrent(operationToken)) return@execute
                    runCatching {
                        val receiptId = job.receiptId ?: error("receipt_missing")
                        if (!ReceiptOperationGeneration.isCurrent(operationToken)) return@execute
                        api.receipt(tenantId, receiptId)
                    }.onSuccess { receipt ->
                        ReceiptOperationGeneration.runIfCurrent(operationToken) {
                            if (receiptPollGeneration.get() == generation && isCurrentReceiptJob(tenantId, job.id)) {
                                val cleared = clearReceiptCheckpoint()
                                ui = ui.copy(busy = false, receiptUploadInProgress = false, receiptJob = job,
                                    receiptDraft = receipt, receiptUploadError = if (cleared) null else "checkpoint",
                                    receiptCanRetryUpload = false, receiptCheckpointUnresolved = !cleared)
                            }
                        }
                    }.onFailure { failure ->
                        if (ReceiptOperationGeneration.isCurrent(operationToken) && receiptPollGeneration.get() == generation && isCurrentReceiptJob(tenantId, job.id)) {
                            ui = ui.copy(busy = false, receiptUploadInProgress = false,
                                receiptUploadError = receiptUploadErrorCode(failure), receiptCheckpointUnresolved = true)
                        }
                    }
                }
                return
            }
            "rejected" -> {
                ReceiptOperationGeneration.runIfCurrent(operationToken) {
                    if (receiptPollGeneration.get() == generation && isCurrentReceiptJob(tenantId, job.id)) {
                        val cleared = clearReceiptCheckpoint()
                        ui = ui.copy(busy = false, receiptUploadInProgress = false, receiptJob = job,
                            receiptUploadError = if (cleared) "rejected" else "checkpoint",
                            receiptCheckpointUnresolved = !cleared)
                    }
                }
                return
            }
        }

        val delayMillis = receiptPollDelayMillis(job.state, attempt)
        if (delayMillis == null) {
            ReceiptOperationGeneration.runIfCurrent(operationToken) {
                ui = ui.copy(busy = false, receiptUploadInProgress = false, receiptJob = job,
                    receiptUploadError = "timeout", receiptCanRetryUpload = false,
                    receiptCheckpointUnresolved = true)
            }
            return
        }
        ReceiptOperationGeneration.runIfCurrent(operationToken) {
            ui = ui.copy(receiptJob = job, receiptUploadError = if (job.state == "retryable") "retryable" else null)
        } ?: return
        val future = receiptPollExecutor.schedule({
            if (!ReceiptOperationGeneration.isCurrent(operationToken) || receiptPollGeneration.get() != generation ||
                !isCurrentReceiptJob(tenantId, job.id)) return@schedule
            if (receiptPollGeneration.get() == generation) receiptPollTask = null
            runCatching {
                if (!ReceiptOperationGeneration.isCurrent(operationToken)) return@schedule
                api.receiptJob(tenantId, job.id)
            }
                .onSuccess { updated ->
                    if (ReceiptOperationGeneration.isCurrent(operationToken) && receiptPollGeneration.get() == generation && isCurrentReceiptJob(tenantId, job.id)) {
                        scheduleReceiptPoll(tenantId, updated, attempt + 1, generation, operationToken)
                    }
                }
                .onFailure { failure ->
                    if (ReceiptOperationGeneration.isCurrent(operationToken) &&
                        receiptPollGeneration.get() == generation && isCurrentReceiptJob(tenantId, job.id)) {
                        ui = ui.copy(busy = false, receiptUploadInProgress = false,
                            receiptUploadError = receiptUploadErrorCode(failure),
                            receiptCheckpointUnresolved = true)
                    }
                }
        }, delayMillis, TimeUnit.MILLISECONDS)
        if (receiptPollGeneration.get() == generation) receiptPollTask = future else future.cancel(false)
    }

    @Synchronized
    private fun invalidateReceiptPoll(): Long {
        val generation = receiptPollGeneration.incrementAndGet()
        receiptPollTask?.cancel(false)
        receiptPollTask = null
        return generation
    }

    private fun isCurrentReceiptJob(tenantId: String, jobId: String): Boolean =
        ui.tenants.firstOrNull()?.id == tenantId && ui.receiptJob?.id == jobId

    private fun hasUnresolvedReceiptCheckpoint(): Boolean = ui.receiptCheckpointUnresolved ||
        ui.receiptJob?.state in setOf<String?>("queued", "running", "retryable") ||
        (ui.receiptJob?.state == "completed" && ui.receiptDraft == null)

    private fun clearReceiptCheckpoint(): Boolean =
        runCatching { receiptCheckpointStore.clear() }.getOrDefault(false)

    private fun reconcilePersistedReceiptUriGrants(
        checkpoint: ReceiptUploadCheckpoint?,
        additionalUris: List<Uri> = emptyList(),
    ) {
        // Persisted document grants are used only by the receipt photo picker in this app.
        val persistedUris = runCatching {
            contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri.toString() }
        }.getOrDefault(emptyList())
        val toRelease = ReceiptUriCleanupPolicy.urisToRelease(persistedUris, checkpoint).toMutableSet()
        additionalUris.mapTo(toRelease) { it.toString() }
        toRelease.forEach { value ->
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(value),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    private fun receiptContentType(uri: Uri): String {
        val mime = contentResolver.getType(uri)?.lowercase()
        val type = when (mime) {
            "image/jpeg", "image/jpg" -> "image/jpeg"
            "image/png" -> "image/png"
            else -> error("file_type")
        }
        return type
    }

    private fun receiptFileName(uri: Uri, contentType: String): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return name?.takeIf(String::isNotBlank)
            ?: if (contentType == "image/png") "receipt.png" else "receipt.jpg"
    }

    private fun readReceiptPhoto(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: error("file_read")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > 10 * 1024 * 1024) error("file_size")
                output.write(buffer, 0, read)
            }
            if (total == 0) error("file_empty")
            return output.toByteArray()
        }
    }

    private fun receiptUploadErrorCode(failure: Throwable): String = when {
        failure.message in setOf("file_type", "file_size", "file_empty", "file_read", "receipt_missing",
            "checkpoint", "checkpoint_workspace") -> failure.message!!
        failure is ApiFailure && failure.status == 401 -> "unauthorized"
        failure is ApiFailure && failure.status == 403 -> "forbidden"
        failure is ApiFailure && failure.status == 413 -> "file_size"
        else -> "request"
    }

    private fun loadTenants() = runApi(restoreReceiptCheckpoint = true) { workspaceData() }
    private fun createTenantAndRefresh(name: String, memberName: String, plannedIncome: String?) = runApi {
        api.createTenant(name, memberName, plannedIncome)
        workspaceData()
    }
    private fun saveMemberProfile(name: String, plannedIncome: String?) = runApi {
        val profile = api.updateMemberProfile(activeTenantId(), name, plannedIncome)
        workspaceData(memberProfile = profile)
    }
    private fun saveMemberProfileForRepeat(name: String, plannedIncome: String?, onFinished: (Boolean) -> Unit) =
        runApi(onFinished = onFinished) {
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

    private fun repeatTransaction(transaction: FinanceTransaction) = runApi {
        val tenant = ui.tenants.firstOrNull() ?: error("Create a workspace first")
        val zone = ZoneId.of(tenant.timezone)
        val occurredAt = LocalDate.now(zone).atTime(12, 0).atZone(zone).toInstant().toString()
        api.repeatTransaction(tenant.id, transaction, occurredAt)
        workspaceData()
    }

    private fun voidTransaction(transaction: FinanceTransaction) = runApi {
        api.voidTransaction(activeTenantId(), transaction)
        workspaceData()
    }

    private fun updateTransaction(edit: FinanceTransactionEdit) = runApi {
        if (pendingTransactionEdit != edit || pendingTransactionEditKey == null) {
            pendingTransactionEdit = edit
            pendingTransactionEditKey = java.util.UUID.randomUUID().toString()
        }
        api.updateTransaction(activeTenantId(), edit, pendingTransactionEditKey!!)
        workspaceData(transactionEditSavedToken = java.util.UUID.randomUUID().toString())
    }

    private fun filterTransactions(search: String, type: String, from: String, to: String, memberId: String) = runApi {
        workspaceData(transactionSearch = search, transactionType = type,
            transactionFrom = from, transactionTo = to, transactionMemberId = memberId)
    }

    private fun loadMoreTransactions() = runApi {
        val cursor = ui.transactionNextCursor ?: return@runApi workspaceData()
        workspaceData(transactionCursor = cursor, appendTransactions = true)
    }

    private fun workspaceData(proposal: BudgetProposal? = ui.budgetProposal,
                              draft: FinanceTransactionDraft? = ui.transactionDraft,
                              memberProfile: FinanceMemberProfile? = null,
                              notificationPreferences: FinanceNotificationPreferences? = null,
                              budgetAlerts: List<FinanceBudgetAlert> = emptyList(),
                              transactionSearch: String = ui.transactionSearch,
                              transactionType: String = ui.transactionTypeFilter,
                              transactionFrom: String = ui.transactionFrom,
                              transactionTo: String = ui.transactionTo,
                              transactionMemberId: String = ui.transactionMemberId,
                              transactionCursor: String? = null,
                              appendTransactions: Boolean = false,
                              transactionEditSavedToken: String? = null): FinanceWorkspaceSnapshot {
        val tenants = api.tenants()
        val tenant = tenants.firstOrNull()
        return if (tenant == null) FinanceWorkspaceSnapshot(tenants, emptyList(), emptyList(), null, transactionSearch,
            transactionType, transactionFrom, transactionTo, transactionMemberId, null, emptyList(), proposal,
            null, null, draft, null, null, budgetAlerts, transactionEditSavedToken)
        else {
            val month = YearMonth.now(ZoneId.of(tenant.timezone)).toString()
            val members = api.members(tenant.id)
            val transactionPage = api.transactions(tenant.id, transactionSearch, transactionType, transactionCursor,
                transactionFrom.trim().ifBlank { null }, transactionTo.trim().ifBlank { null },
                transactionMemberId.trim().ifBlank { null })
            val transactions = (if (appendTransactions) ui.transactions else emptyList()) + transactionPage.items
            FinanceWorkspaceSnapshot(tenants, transactions, members, transactionPage.nextCursor, transactionSearch, transactionType,
                transactionFrom, transactionTo, transactionMemberId,
                api.budgets(tenant.id), api.debts(tenant.id),
                proposal, api.dashboardSummary(tenant.id), api.report(tenant.id, "month", "personal", month, "", ""),
                draft, memberProfile ?: api.memberProfile(tenant.id),
                notificationPreferences ?: api.notificationPreferences(tenant.id), budgetAlerts,
                transactionEditSavedToken)
        }
    }

    private fun runApi(restoreReceiptCheckpoint: Boolean = false,
                       onFinished: ((Boolean) -> Unit)? = null,
                       action: () -> FinanceWorkspaceSnapshot) {
        val receiptOperationToken = if (restoreReceiptCheckpoint) ReceiptOperationGeneration.capture() else null
        ui = ui.copy(busy = true, error = null)
        executor.execute {
            if (receiptOperationToken != null && !ReceiptOperationGeneration.isCurrent(receiptOperationToken)) {
                return@execute
            }
            runCatching(action).onSuccess { snapshot ->
                val publishSnapshot = {
                    familyBudgetFoodRequestGeneration.incrementAndGet()
                    if (snapshot.transactionEditSavedToken != null) {
                        pendingTransactionEdit = null
                        pendingTransactionEditKey = null
                    }
                    val sameTenant = ui.tenants.firstOrNull()?.id == snapshot.tenants.firstOrNull()?.id
                    if (!sameTenant) {
                        receiptRecalculationGeneration.incrementAndGet()
                        synchronized(productCatalogStateLock) { productCatalogGeneration.incrementAndGet() }
                        ShoppingResponsePolicy.beginRequest(shoppingRequestGeneration)
                    }
                    ui = ui.copy(busy = false, tenants = snapshot.tenants,
                        transactions = snapshot.transactions, transactionNextCursor = snapshot.transactionNextCursor,
                        transactionMembers = snapshot.transactionMembers,
                        transactionSearch = snapshot.transactionSearch, transactionTypeFilter = snapshot.transactionTypeFilter,
                        transactionFrom = snapshot.transactionFrom, transactionTo = snapshot.transactionTo,
                        transactionMemberId = snapshot.transactionMemberId,
                        budgets = snapshot.budgets, debts = snapshot.debts,
                        budgetProposal = snapshot.proposal, dashboardSummary = snapshot.dashboardSummary,
                        report = snapshot.report, transactionDraft = snapshot.transactionDraft,
                        memberProfile = snapshot.memberProfile, notificationPreferences = snapshot.notificationPreferences,
                        budgetAlerts = snapshot.budgetAlerts, transactionEditSavedToken = snapshot.transactionEditSavedToken,
                        error = null,
                        familyBudgetFoodRefreshToken = ui.familyBudgetFoodRefreshToken + 1,
                        doNotBuy = ui.doNotBuy.takeIf { sameTenant },
                        productDecisions = ui.productDecisions.takeIf { sameTenant },
                        productCatalog = ui.productCatalog.takeIf { sameTenant },
                        productCatalogTenantId = ui.productCatalogTenantId.takeIf { sameTenant },
                        productCatalogRequestedQuery = ui.productCatalogRequestedQuery.takeIf { sameTenant }.orEmpty(),
                        productCatalogLoading = ui.productCatalogLoading && sameTenant,
                        productCatalogError = ui.productCatalogError.takeIf { sameTenant },
                        doNotBuyError = ui.doNotBuyError.takeIf { sameTenant },
                        doNotBuyLoading = ui.doNotBuyLoading && sameTenant,
                        shoppingList = ui.shoppingList.takeIf { sameTenant },
                        shoppingLoading = ui.shoppingLoading && sameTenant,
                        shoppingError = ui.shoppingError.takeIf { sameTenant },
                        receiptRecalculationPreview = ui.receiptRecalculationPreview.takeIf { sameTenant },
                        receiptRecalculationApplyResult = ui.receiptRecalculationApplyResult.takeIf { sameTenant },
                        receiptRecalculationHistory = ui.receiptRecalculationHistory.takeIf { sameTenant },
                        receiptRecalculationDetail = ui.receiptRecalculationDetail.takeIf { sameTenant },
                        receiptRecalculationBusy = ui.receiptRecalculationBusy && sameTenant,
                        receiptRecalculationError = ui.receiptRecalculationError.takeIf { sameTenant },
                        receiptDisputedItemsPage = ui.receiptDisputedItemsPage.takeIf { sameTenant },
                        receiptDisputedItemsReceiptId = ui.receiptDisputedItemsReceiptId.takeIf { sameTenant },
                        receiptDisputedItemsRequestedPage = ui.receiptDisputedItemsRequestedPage.takeIf { sameTenant },
                        receiptDisputedItemsLoading = ui.receiptDisputedItemsLoading && sameTenant,
                        receiptDisputedItemsError = ui.receiptDisputedItemsError.takeIf { sameTenant })
                    if (restoreReceiptCheckpoint) {
                        restoreReceiptCheckpointForTenant(snapshot.tenants.firstOrNull()?.id, receiptOperationToken!!)
                    }
                }
                if (receiptOperationToken == null) publishSnapshot()
                else ReceiptOperationGeneration.runIfCurrent(receiptOperationToken, publishSnapshot)
                    ?: return@onSuccess
                if (onFinished != null) runOnUiThread { onFinished(true) }
            }
            .onFailure { error ->
                    val publishError = {
                        if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                        else ui = ui.copy(busy = false, error = apiErrorMessage(error))
                    }
                    if (receiptOperationToken == null) publishError()
                    else ReceiptOperationGeneration.runIfCurrent(receiptOperationToken, publishError)
                if (onFinished != null) runOnUiThread { onFinished(false) }
            }
        }
    }

    private fun apiErrorMessage(error: Throwable): String {
        return when ((error as? ApiFailure)?.status) {
            412 -> if (language == "ru") "Операция уже изменилась. Обновите список и откройте её снова."
                else "This transaction changed. Refresh the list and open it again."
            409 -> if (language == "ru") "Не удалось повторить изменение. Обновите список и попробуйте снова."
                else "The update could not be retried. Refresh the list and try again."
            403 -> if (language == "ru") "Нет прав на изменение этой операции."
                else "You cannot edit this transaction."
            else -> error.message ?: "Request failed"
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
        val normalizedIncome = monthlyIncome?.trim()?.replace(',', '.')
        val operationId = buildString {
            append(tenantId)
            append("|budget-proposal|")
            append(normalizedIncome ?: "history")
        }
        val idempotencyKey = budgetIdempotencyKeyStore.keyFor(operationId)
        val proposal = if (monthlyIncome == null) api.proposeBudgetFromHistory(tenantId, idempotencyKey)
        else api.proposeBudget(tenantId, normalizedIncome.orEmpty(), idempotencyKey)
        val snapshot = workspaceData(proposal)
        check(budgetIdempotencyKeyStore.clear(operationId)) {
            "Could not clear completed budget proposal key"
        }
        snapshot
    }

    private fun applyBudgetProposal(proposalId: String) = runApi {
        val tenantId = activeTenantId()
        val operationId = "$tenantId|budget-proposal-apply|$proposalId"
        val idempotencyKey = budgetIdempotencyKeyStore.keyFor(operationId)
        try {
            api.applyBudgetProposal(tenantId, proposalId, idempotencyKey)
        } catch (failure: ApiFailure) {
            val message = BudgetApplyErrorMessages.message(failure.status, language)
                ?: throw failure
            throw IllegalStateException(message, failure)
        }
        val snapshot = workspaceData(proposal = null)
        check(budgetIdempotencyKeyStore.clear(operationId)) {
            "Could not clear completed budget apply key"
        }
        snapshot
    }

    private fun keepBudgetProposal() {
        ui = ui.copy(budgetProposal = null)
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
        val requestGeneration = reportRequestGeneration.incrementAndGet()
        val sessionGeneration = ReceiptOperationGeneration.capture()
        ui = ui.copy(busy = true, error = null)
        executor.execute {
            runCatching { api.report(tenantId, period, scope, month, from, to) }
                .onSuccess { report ->
                    if (ReportResponsePolicy.canApply(
                            requestTenantId = tenantId,
                            activeTenantId = ui.tenants.firstOrNull()?.id,
                            authenticated = ui.authenticated,
                            requestGeneration = requestGeneration,
                            currentRequestGeneration = reportRequestGeneration.get(),
                            sessionCurrent = ReceiptOperationGeneration.isCurrent(sessionGeneration),
                        )) {
                        ui = ui.copy(busy = false, report = report)
                    }
                }
                .onFailure { error ->
                    if (ReportResponsePolicy.canApply(
                            requestTenantId = tenantId,
                            activeTenantId = ui.tenants.firstOrNull()?.id,
                            authenticated = ui.authenticated,
                            requestGeneration = requestGeneration,
                            currentRequestGeneration = reportRequestGeneration.get(),
                            sessionCurrent = ReceiptOperationGeneration.isCurrent(sessionGeneration),
                        )) {
                        if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                        else ui = ui.copy(busy = false, error = error.message ?: "Request failed")
                    }
                }
        }
    }

    private fun previewReceiptRecalculation() {
        val tenantId = activeTenantId()
        val requestGeneration = receiptRecalculationGeneration.incrementAndGet()
        val sessionGeneration = ReceiptOperationGeneration.capture()
        ui = ui.copy(receiptRecalculationBusy = true, receiptRecalculationError = null,
            receiptRecalculationPreview = null, receiptRecalculationApplyResult = null)
        executor.execute {
            runCatching { api.previewReceiptRecalculation(tenantId) }
                .onSuccess { preview -> if (receiptRecalculationResponseIsCurrent(tenantId, requestGeneration, sessionGeneration)) {
                    ui = ui.copy(receiptRecalculationBusy = false, receiptRecalculationPreview = preview,
                        receiptRecalculationError = null)
                } }
                .onFailure { error -> if (receiptRecalculationResponseIsCurrent(tenantId, requestGeneration, sessionGeneration)) {
                    if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                    else ui = ui.copy(receiptRecalculationBusy = false,
                        receiptRecalculationError = receiptRecalculationErrorCode(error))
                } }
        }
    }

    private fun applyReceiptRecalculation(runId: String) {
        val tenantId = activeTenantId()
        val requestGeneration = receiptRecalculationGeneration.incrementAndGet()
        val sessionGeneration = ReceiptOperationGeneration.capture()
        ui = ui.copy(receiptRecalculationBusy = true, receiptRecalculationError = null,
            receiptRecalculationPreview = null, receiptRecalculationApplyResult = null)
        executor.execute {
            runCatching { api.applyReceiptRecalculation(tenantId, runId) }
                .onSuccess { result -> if (receiptRecalculationResponseIsCurrent(tenantId, requestGeneration, sessionGeneration)) {
                    ui = ui.copy(receiptRecalculationBusy = false, receiptRecalculationPreview = null,
                        receiptRecalculationApplyResult = result,
                        receiptRecalculationHistory = null, receiptRecalculationDetail = null,
                        receiptRecalculationError = null)
                } }
                .onFailure { error -> if (receiptRecalculationResponseIsCurrent(tenantId, requestGeneration, sessionGeneration)) {
                    if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                    else ui = ui.copy(receiptRecalculationBusy = false,
                        receiptRecalculationError = receiptRecalculationErrorCode(error),
                        receiptRecalculationPreview = null)
                } }
        }
    }

    private fun loadReceiptRecalculationHistory(cursor: String?) {
        val tenantId = activeTenantId()
        val requestGeneration = if (cursor == null) receiptRecalculationGeneration.incrementAndGet()
            else receiptRecalculationGeneration.get()
        val sessionGeneration = ReceiptOperationGeneration.capture()
        ui = ui.copy(receiptRecalculationBusy = true, receiptRecalculationError = null)
        executor.execute {
            runCatching { api.receiptRecalculationHistory(tenantId, cursor = cursor) }
                .onSuccess { page -> if (receiptRecalculationResponseIsCurrent(tenantId, requestGeneration, sessionGeneration)) {
                    val runs = if (cursor == null) page.runs else
                        (ui.receiptRecalculationHistory?.runs.orEmpty() + page.runs).distinctBy { it.runId }
                    ui = ui.copy(receiptRecalculationBusy = false,
                        receiptRecalculationHistory = page.copy(runs = runs), receiptRecalculationError = null)
                } }
                .onFailure { error -> if (receiptRecalculationResponseIsCurrent(tenantId, requestGeneration, sessionGeneration)) {
                    if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                    else ui = ui.copy(receiptRecalculationBusy = false,
                        receiptRecalculationError = receiptRecalculationErrorCode(error))
                } }
        }
    }

    private fun loadReceiptRecalculationDetail(runId: String, cursor: String?) {
        val tenantId = activeTenantId()
        val requestGeneration = if (cursor == null) receiptRecalculationGeneration.incrementAndGet()
            else receiptRecalculationGeneration.get()
        val sessionGeneration = ReceiptOperationGeneration.capture()
        ui = ui.copy(receiptRecalculationBusy = true, receiptRecalculationError = null)
        executor.execute {
            runCatching { api.receiptRecalculationDetail(tenantId, runId, cursor = cursor) }
                .onSuccess { page -> if (receiptRecalculationResponseIsCurrent(tenantId, requestGeneration, sessionGeneration)) {
                    val previous = ui.receiptRecalculationDetail?.takeIf { cursor != null && it.run.runId == runId }
                    val changes = if (previous == null) page.changes else
                        (previous.changes + page.changes).distinctBy { it.itemId }
                    ui = ui.copy(receiptRecalculationBusy = false,
                        receiptRecalculationDetail = page.copy(changes = changes), receiptRecalculationError = null)
                } }
                .onFailure { error -> if (receiptRecalculationResponseIsCurrent(tenantId, requestGeneration, sessionGeneration)) {
                    if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                    else ui = ui.copy(receiptRecalculationBusy = false,
                        receiptRecalculationError = receiptRecalculationErrorCode(error))
                } }
        }
    }

    private fun receiptRecalculationResponseIsCurrent(tenantId: String, requestGeneration: Long,
                                                       sessionGeneration: Long): Boolean =
        ReportResponsePolicy.canApply(
            requestTenantId = tenantId,
            activeTenantId = ui.tenants.firstOrNull()?.id,
            authenticated = ui.authenticated,
            requestGeneration = requestGeneration,
            currentRequestGeneration = receiptRecalculationGeneration.get(),
            sessionCurrent = ReceiptOperationGeneration.isCurrent(sessionGeneration),
        )

    private fun receiptRecalculationErrorCode(error: Throwable): String = when ((error as? ApiFailure)?.status) {
        412 -> "stale_preview"
        403 -> "forbidden"
        else -> "unavailable"
    }

    private fun loadFamilyBudgetFoodStatus(month: String) {
        val tenantId = activeTenantId()
        val requestGeneration = familyBudgetFoodRequestGeneration.incrementAndGet()
        val sessionGeneration = ReceiptOperationGeneration.capture()
        ui = ui.copy(familyBudgetFoodStatus = null, familyBudgetFoodMonth = month,
            familyBudgetFoodTenantId = tenantId, familyBudgetFoodLoading = true, familyBudgetFoodError = null)
        executor.execute {
            runCatching { api.report(tenantId, "month", "family", month, "", "") }
                .onSuccess { report ->
                    if (FamilyBudgetFoodResponsePolicy.isCurrentRequest(
                            requestTenantId = tenantId,
                            activeTenantId = ui.tenants.firstOrNull()?.id,
                            authenticated = ui.authenticated,
                            requestMonth = month,
                            currentMonth = ui.budgets?.month,
                            requestGeneration = requestGeneration,
                            currentRequestGeneration = familyBudgetFoodRequestGeneration.get(),
                            sessionCurrent = ReceiptOperationGeneration.isCurrent(sessionGeneration),
                        )) {
                        if (FamilyBudgetFoodResponsePolicy.canApply(
                                requestTenantId = tenantId,
                                activeTenantId = ui.tenants.firstOrNull()?.id,
                                authenticated = ui.authenticated,
                                requestMonth = month,
                                currentMonth = ui.budgets?.month,
                                responsePeriod = report.period,
                                responseScope = report.scope,
                                responseMonth = report.fromDate.take(7),
                                requestGeneration = requestGeneration,
                                currentRequestGeneration = familyBudgetFoodRequestGeneration.get(),
                                sessionCurrent = ReceiptOperationGeneration.isCurrent(sessionGeneration),
                            )) {
                            ui = ui.copy(familyBudgetFoodStatus = report.rolling7FoodStatus,
                                familyBudgetFoodMonth = month, familyBudgetFoodTenantId = tenantId,
                                familyBudgetFoodLoading = false, familyBudgetFoodError = null)
                        } else if (FamilyBudgetFoodResponsePolicy.isCurrentRequest(
                                requestTenantId = tenantId,
                                activeTenantId = ui.tenants.firstOrNull()?.id,
                                authenticated = ui.authenticated,
                                requestMonth = month,
                                currentMonth = ui.budgets?.month,
                                requestGeneration = requestGeneration,
                                currentRequestGeneration = familyBudgetFoodRequestGeneration.get(),
                                sessionCurrent = ReceiptOperationGeneration.isCurrent(sessionGeneration),
                            )) {
                            ui = ui.copy(familyBudgetFoodStatus = null, familyBudgetFoodLoading = false,
                                familyBudgetFoodError = "Family report scope did not match the request")
                        }
                    }
                }
                .onFailure { error ->
                    if (FamilyBudgetFoodResponsePolicy.isCurrentRequest(
                            requestTenantId = tenantId,
                            activeTenantId = ui.tenants.firstOrNull()?.id,
                            authenticated = ui.authenticated,
                            requestMonth = month,
                            currentMonth = ui.budgets?.month,
                            requestGeneration = requestGeneration,
                            currentRequestGeneration = familyBudgetFoodRequestGeneration.get(),
                            sessionCurrent = ReceiptOperationGeneration.isCurrent(sessionGeneration),
                        )) {
                    if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                        else ui = ui.copy(familyBudgetFoodStatus = null, familyBudgetFoodMonth = month,
                            familyBudgetFoodTenantId = tenantId, familyBudgetFoodLoading = false,
                            familyBudgetFoodError = error.message ?: "Request failed")
                    }
                }
        }
    }

    private fun loadShoppingCandidates() {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        if (!ui.authenticated) return
        val requestGeneration = ShoppingResponsePolicy.beginRequest(shoppingRequestGeneration)
        val sessionGeneration = ReceiptOperationGeneration.capture()
        ui = ui.copy(shoppingLoading = true, shoppingError = null)
        executor.execute {
            if (!ReceiptOperationGeneration.isCurrent(sessionGeneration) ||
                shoppingRequestGeneration.get() != requestGeneration) return@execute
            runCatching { api.shoppingCandidates(tenantId) }
                .onSuccess { shopping ->
                    ShoppingResponsePolicy.applyIfCurrent(
                        sessionGeneration = sessionGeneration,
                        requestTenantId = tenantId,
                        activeTenantId = { ui.tenants.firstOrNull()?.id },
                        authenticated = { ui.authenticated },
                        requestGeneration = requestGeneration,
                        currentRequestGeneration = { shoppingRequestGeneration.get() },
                    ) {
                        ui = ui.copy(shoppingLoading = false, shoppingList = shopping, shoppingError = null)
                    }
                }
                .onFailure { error ->
                    ShoppingResponsePolicy.applyIfCurrent(
                        sessionGeneration = sessionGeneration,
                        requestTenantId = tenantId,
                        activeTenantId = { ui.tenants.firstOrNull()?.id },
                        authenticated = { ui.authenticated },
                        requestGeneration = requestGeneration,
                        currentRequestGeneration = { shoppingRequestGeneration.get() },
                    ) {
                        if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                        else {
                            ui = ui.copy(shoppingLoading = false, shoppingError = error.message ?: "Request failed")
                        }
                    }
                }
        }
    }

    private fun loadDoNotBuy() {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        ui = ui.copy(doNotBuyLoading = true, doNotBuyError = null)
        executor.execute {
            runCatching { api.doNotBuy(tenantId) to api.productDecisions(tenantId) }
                .onSuccess { (report, decisions) ->
                    if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(doNotBuyLoading = false, doNotBuy = report,
                            productDecisions = decisions, doNotBuyError = null)
                    }
                }
                .onFailure { error ->
                    if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                    else if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(doNotBuyLoading = false, doNotBuyError = error.message ?: "Request failed")
                    }
                }
        }
    }

    private fun applyDoNotBuyDecision(productKey: String, action: String) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        val sessionGeneration = ReceiptOperationGeneration.capture()
        ui = ui.copy(doNotBuyLoading = true, doNotBuyError = null)
        executor.execute {
            runCatching {
                api.decideDoNotBuy(tenantId, productKey, action)
                api.doNotBuy(tenantId) to api.productDecisions(tenantId)
            }.onSuccess { (report, decisions) ->
                if (ui.tenants.firstOrNull()?.id == tenantId) {
                    ui = ui.copy(doNotBuyLoading = false, doNotBuy = report,
                        productDecisions = decisions, doNotBuyError = null)
                    if (ui.shoppingList != null && ReceiptOperationGeneration.isCurrent(sessionGeneration) &&
                        ui.authenticated && ui.tenants.firstOrNull()?.id == tenantId) {
                        val shoppingGeneration = ShoppingResponsePolicy.beginRequest(shoppingRequestGeneration)
                        runCatching { api.shoppingCandidates(tenantId) }
                            .onSuccess { shopping ->
                                ShoppingResponsePolicy.applyIfCurrent(
                                    sessionGeneration = sessionGeneration,
                                    requestTenantId = tenantId,
                                    activeTenantId = { ui.tenants.firstOrNull()?.id },
                                    authenticated = { ui.authenticated },
                                    requestGeneration = shoppingGeneration,
                                    currentRequestGeneration = { shoppingRequestGeneration.get() },
                                ) { ui = ui.copy(shoppingList = shopping, shoppingError = null) }
                            }
                            .onFailure {
                                ShoppingResponsePolicy.applyIfCurrent(
                                    sessionGeneration = sessionGeneration,
                                    requestTenantId = tenantId,
                                    activeTenantId = { ui.tenants.firstOrNull()?.id },
                                    authenticated = { ui.authenticated },
                                    requestGeneration = shoppingGeneration,
                                    currentRequestGeneration = { shoppingRequestGeneration.get() },
                                ) { ui = ui.copy(shoppingError = "unavailable") }
                            }
                    }
                }
            }.onFailure { error ->
                if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                else if (ui.tenants.firstOrNull()?.id == tenantId) {
                    ui = ui.copy(doNotBuyLoading = false, doNotBuyError = error.message ?: "Request failed")
                }
            }
        }
    }

    private fun loadPersonalInflation() {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        ui = ui.copy(personalInflationLoading = true, personalInflationError = null)
        executor.execute {
            runCatching { api.personalInflation(tenantId) }
                .onSuccess { inflation ->
                    if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(personalInflationLoading = false, personalInflation = inflation,
                            personalInflationError = null)
                    }
                }
                .onFailure { error ->
                    if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                    else if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(personalInflationLoading = false,
                            personalInflationError = error.message ?: "Request failed")
                    }
                }
        }
    }

    private fun loadRecurring() {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        ui = ui.copy(recurringLoading = true, recurringError = null)
        executor.execute {
            runCatching { api.recurringProjection(tenantId) }
                .onSuccess { projection ->
                    if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(recurringLoading = false, recurringProjection = projection, recurringError = null)
                    }
                }
                .onFailure { error ->
                    if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                    else if (ui.tenants.firstOrNull()?.id == tenantId) {
                        ui = ui.copy(recurringLoading = false, recurringError = error.message ?: "Request failed")
                    }
                }
        }
    }

    private fun applyRecurringDecision(seriesId: String, muted: Boolean) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        ui = ui.copy(recurringLoading = true, recurringError = null)
        executor.execute {
            runCatching {
                if (muted) api.muteRecurringSeries(tenantId, seriesId)
                else api.unmuteRecurringSeries(tenantId, seriesId)
            }.onSuccess { projection ->
                if (ui.tenants.firstOrNull()?.id == tenantId) {
                    ui = ui.copy(recurringLoading = false, recurringProjection = projection, recurringError = null)
                }
            }.onFailure { error ->
                if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                else if (ui.tenants.firstOrNull()?.id == tenantId) {
                    ui = ui.copy(recurringLoading = false, recurringError = error.message ?: "Request failed")
                }
            }
        }
    }

    private fun applyShoppingDecision(productKey: String, action: String) {
        val tenantId = ui.tenants.firstOrNull()?.id ?: return
        if (!ui.authenticated) return
        val requestGeneration = ShoppingResponsePolicy.beginRequest(shoppingRequestGeneration)
        val sessionGeneration = ReceiptOperationGeneration.capture()
        ui = ui.copy(shoppingLoading = true, shoppingError = null)
        executor.execute {
            if (!ReceiptOperationGeneration.isCurrent(sessionGeneration) ||
                shoppingRequestGeneration.get() != requestGeneration) return@execute
            runCatching {
                when (action) {
                    "bought" -> api.markShoppingBought(tenantId, productKey)
                    "mute" -> api.muteShoppingSuggestion(tenantId, productKey)
                    "unmute" -> api.unmuteShoppingSuggestion(tenantId, productKey)
                    else -> error("Unknown shopping decision")
                }
            }.onSuccess { shopping ->
                ShoppingResponsePolicy.applyIfCurrent(
                    sessionGeneration = sessionGeneration,
                    requestTenantId = tenantId,
                    activeTenantId = { ui.tenants.firstOrNull()?.id },
                    authenticated = { ui.authenticated },
                    requestGeneration = requestGeneration,
                    currentRequestGeneration = { shoppingRequestGeneration.get() },
                ) {
                    ui = ui.copy(shoppingLoading = false, shoppingList = shopping, shoppingError = null)
                }
            }.onFailure { error ->
                ShoppingResponsePolicy.applyIfCurrent(
                    sessionGeneration = sessionGeneration,
                    requestTenantId = tenantId,
                    activeTenantId = { ui.tenants.firstOrNull()?.id },
                    authenticated = { ui.authenticated },
                    requestGeneration = requestGeneration,
                    currentRequestGeneration = { shoppingRequestGeneration.get() },
                ) {
                    if (error is ApiFailure && error.status == 401) expireAuthenticatedSession(error)
                    else {
                        ui = ui.copy(shoppingLoading = false, shoppingError = error.message ?: "Request failed")
                    }
                }
            }
        }
    }

    private fun copyShoppingList(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Finance shopping list", text))
        Toast.makeText(this, if (language == "ru") "Список скопирован" else "List copied", Toast.LENGTH_SHORT).show()
    }

    private fun logout() {
        receiptRecalculationGeneration.incrementAndGet()
        invalidateProductCatalogRequests()
        ReceiptOperationGeneration.invalidate()
        receiptDisputedItemsGeneration.incrementAndGet()
        receiptReadingGeneration.incrementAndGet()
        receiptItemsGeneration.incrementAndGet()
        receiptItemAddGeneration.incrementAndGet()
        pendingReceiptItemAdd = null
        receiptPickerOperationToken = null
        invalidateReceiptPoll()
        ui = ui.copy(busy = true, error = null)
        executor.execute {
            val storedCheckpoint = runCatching { receiptCheckpointStore.load() }.getOrNull()
            val inMemoryUri = pendingReceiptUri
            runCatching { api.logout() }
            val checkpointCleared = clearReceiptCheckpoint()
            val urisToRelease = listOfNotNull(
                storedCheckpoint?.photoUri?.let { value -> runCatching { Uri.parse(value) }.getOrNull() },
                inMemoryUri,
            ).distinct()
            reconcilePersistedReceiptUriGrants(checkpoint = null, additionalUris = urisToRelease)
            pendingReceiptUri = null
            pendingReceiptKey = null
            receiptRestoreInProgressTenantId = null
            runOnUiThread {
                ui = FinanceUiState(error = if (checkpointCleared) null else
                    if (language == "ru") "Не удалось очистить сохранённую загрузку чека."
                    else "Could not clear the saved receipt upload.")
            }
        }
    }

    override fun onDestroy() {
        authService.dispose()
        invalidateReceiptPoll()
        executor.shutdownNow()
        receiptPollExecutor.shutdownNow()
        super.onDestroy()
    }
}

data class FinanceUiState(
    val authenticated: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val tenants: List<FinanceTenant> = emptyList(),
    val transactions: List<FinanceTransaction> = emptyList(),
    val transactionMembers: List<FinanceTenantMember> = emptyList(),
    val transactionNextCursor: String? = null,
    val transactionSearch: String = "",
    val transactionTypeFilter: String = "all",
    val transactionFrom: String = "",
    val transactionTo: String = "",
    val transactionMemberId: String = "",
    val budgets: BudgetOverview? = null,
    val debts: List<FinanceDebt> = emptyList(),
    val budgetProposal: BudgetProposal? = null,
    val debtForecasts: Map<String, DebtForecast> = emptyMap(),
    val dashboardSummary: DashboardSummary? = null,
    val report: FinanceReport? = null,
    val receiptRecalculationPreview: FinanceReceiptRecalculationPreview? = null,
    val receiptRecalculationApplyResult: FinanceReceiptRecalculationApplyResult? = null,
    val receiptRecalculationHistory: FinanceReceiptRecalculationHistoryPage? = null,
    val receiptRecalculationDetail: FinanceReceiptRecalculationDetail? = null,
    val receiptRecalculationBusy: Boolean = false,
    val receiptRecalculationError: String? = null,
    val familyBudgetFoodStatus: RollingFoodStatus? = null,
    val familyBudgetFoodMonth: String? = null,
    val familyBudgetFoodTenantId: String? = null,
    val familyBudgetFoodLoading: Boolean = false,
    val familyBudgetFoodError: String? = null,
    val familyBudgetFoodRefreshToken: Long = 0L,
    val transactionDraft: FinanceTransactionDraft? = null,
    val memberProfile: FinanceMemberProfile? = null,
    val telegramLinkCode: FinanceTelegramLinkCode? = null,
    val notificationPreferences: FinanceNotificationPreferences? = null,
    val budgetAlerts: List<FinanceBudgetAlert> = emptyList(),
    val shoppingList: FinanceShoppingList? = null,
    val shoppingLoading: Boolean = false,
    val shoppingError: String? = null,
    val doNotBuy: FinanceDoNotBuy? = null,
    val productDecisions: FinanceProductDecisions? = null,
    val doNotBuyLoading: Boolean = false,
    val doNotBuyError: String? = null,
    val personalInflation: FinancePersonalInflation? = null,
    val personalInflationLoading: Boolean = false,
    val personalInflationError: String? = null,
    val productPriceComparison: FinanceProductPriceComparison? = null,
    val productPriceComparisonReceiptId: String? = null,
    val productPriceComparisonItemId: String? = null,
    val productPriceComparisonLoading: Boolean = false,
    val productPriceComparisonError: String? = null,
    val productCatalog: FinanceProductCatalog? = null,
    val productCatalogTenantId: String? = null,
    val productCatalogRequestedQuery: String = "",
    val productCatalogLoading: Boolean = false,
    val productCatalogError: String? = null,
    val recurringProjection: FinanceRecurringProjection? = null,
    val recurringLoading: Boolean = false,
    val recurringError: String? = null,
    val transactionEditSavedToken: String? = null,
    val receiptJob: FinanceReceiptProcessingJob? = null,
    val receiptDraft: FinanceReceipt? = null,
    val receiptReading: FinanceReceiptReading? = null,
    val receiptReadingReceiptId: String? = null,
    val receiptReadingLoading: Boolean = false,
    val receiptReadingError: String? = null,
    val receiptItemsPage: FinanceReceiptItemPage? = null,
    val receiptItemsReceiptId: String? = null,
    val receiptItemsRequestedPage: Int? = null,
    val receiptItemsLoading: Boolean = false,
    val receiptItemsError: String? = null,
    val receiptItemEditError: String? = null,
    val receiptItemEditSavedToken: String? = null,
    val receiptItemAddError: String? = null,
    val receiptItemAddNeedsRefresh: Boolean = false,
    val receiptItemAddSavedToken: String? = null,
    val receiptTotalSyncError: String? = null,
    val receiptTotalSyncInProgress: Boolean = false,
    val receiptDuplicateCandidates: FinanceReceiptDuplicateCandidates? = null,
    val receiptDuplicateCandidatesReceiptId: String? = null,
    val receiptDuplicateCandidatesLoading: Boolean = false,
    val receiptDuplicateCandidatesError: String? = null,
    val receiptDuplicateDecisionInProgress: Boolean = false,
    val receiptDuplicateDecisionError: String? = null,
    val receiptConfirming: Boolean = false,
    val receiptConfirmError: String? = null,
    val receiptCategorySaving: Boolean = false,
    val receiptCategoryError: String? = null,
    val receiptBasketReviewing: Boolean = false,
    val receiptBasketReviewingReceiptId: String? = null,
    val receiptBasketReviewingTenantId: String? = null,
    val receiptBasketReviewError: String? = null,
    val receiptDisputedItemsPage: FinanceReceiptItemPage? = null,
    val receiptDisputedItemsReceiptId: String? = null,
    val receiptDisputedItemsRequestedPage: Int? = null,
    val receiptDisputedItemsLoading: Boolean = false,
    val receiptDisputedItemsError: String? = null,
    val receiptRepeatWarnings: FinanceReceiptRepeatWarnings? = null,
    val receiptRepeatWarningsReceiptId: String? = null,
    val receiptRepeatWarningsRevision: String? = null,
    val receiptRepeatWarningsLoading: Boolean = false,
    val receiptRepeatWarningsError: String? = null,
    val receiptUploadInProgress: Boolean = false,
    val receiptUploadError: String? = null,
    val receiptCanRetryUpload: Boolean = false,
    val receiptCheckpointUnresolved: Boolean = false,
)

internal data class ReceiptDisputedDecisionSnapshot(
    val page: FinanceReceiptItemPage,
    val decisions: FinanceProductDecisions,
    val warnings: FinanceReceiptRepeatWarnings? = null,
)

internal class ReceiptDisputedDecisionMutationFailure(
    cause: Throwable,
    val snapshot: ReceiptDisputedDecisionSnapshot?,
) : RuntimeException("Could not apply the disputed-product decision", cause) {
    val mayHaveApplied: Boolean = when (cause) {
        is ApiFailure -> cause.status !in 400..499 || cause.status in setOf(408, 425, 429)
        else -> true
    }
}

internal class ReceiptDisputedDecisionRefreshFailure(cause: Throwable) :
    RuntimeException("Could not refresh disputed products after the decision", cause)

internal fun shouldRefreshReceiptRepeatWarnings(
    receiptId: String,
    warningsReceiptId: String?,
    warnings: FinanceReceiptRepeatWarnings?,
    loading: Boolean,
): Boolean = warningsReceiptId == receiptId && (warnings != null || loading)

internal fun receiptRepeatWarningErrorAfterDecision(
    previousError: String?,
    refreshRequested: Boolean,
    mutationMayHaveApplied: Boolean,
    refreshedWarnings: FinanceReceiptRepeatWarnings?,
): String? = when {
    refreshedWarnings != null -> null
    refreshRequested && mutationMayHaveApplied -> "unavailable"
    else -> previousError
}

internal fun receiptRepeatWarningsRevision(receipt: FinanceReceipt): String = buildString {
    val values = buildList {
        add(receipt.version.toString())
        add(receipt.state)
        add(receipt.itemCount.toString())
        receipt.items.forEach { item ->
            add(item.id)
            add(item.version.toString())
            add(item.name)
            add(item.productKey.orEmpty())
            add(item.verdict.orEmpty())
            add(item.advice.orEmpty())
        }
    }
    values.forEach { value -> append(value.length).append(':').append(value) }
}

internal fun receiptRepeatWarningsResponseMatches(
    requestedGeneration: Long,
    currentGeneration: Long,
    requestedRevision: String,
    currentRevision: String?,
): Boolean = requestedGeneration == currentGeneration && requestedRevision == currentRevision

internal fun applyReceiptDisputedDecisionAndRefresh(
    applyDecision: () -> Unit,
    loadPage: () -> FinanceReceiptItemPage,
    loadDecisions: () -> FinanceProductDecisions,
    invalidateWarnings: (() -> Unit)? = null,
    loadWarnings: (() -> FinanceReceiptRepeatWarnings)? = null,
): ReceiptDisputedDecisionSnapshot {
    fun loadSnapshot(includeWarnings: Boolean = true): ReceiptDisputedDecisionSnapshot {
        val page = loadPage()
        val decisions = loadDecisions()
        val warnings = if (includeWarnings) loadWarnings?.let { runCatching(it).getOrNull() } else null
        return ReceiptDisputedDecisionSnapshot(page, decisions, warnings)
    }

    try {
        applyDecision()
    } catch (error: Exception) {
        val mayHaveApplied = when (error) {
            is ApiFailure -> error.status !in 400..499 || error.status in setOf(408, 425, 429)
            else -> true
        }
        if (mayHaveApplied) runCatching { invalidateWarnings?.invoke() }
        val reconciled = runCatching { loadSnapshot(includeWarnings = mayHaveApplied) }.getOrNull()
        throw ReceiptDisputedDecisionMutationFailure(error, reconciled)
    }
    try {
        invalidateWarnings?.invoke()
        return loadSnapshot()
    } catch (error: Exception) {
        throw ReceiptDisputedDecisionRefreshFailure(error)
    }
}

internal fun canWriteReceiptCategory(role: String?): Boolean = role != null && role != "viewer"

internal fun canReviewReceiptBasket(role: String?): Boolean = role in setOf("owner", "admin", "member")

private val RECEIPT_CATEGORY_CODES = listOf(
    "еда", "транспорт", "жилье", "досуг", "одежда", "здоровье", "работа", "техника", "долги", "прочее",
)

private fun receiptCategoryLabel(code: String, language: String): String {
    val russian = language == "ru"
    return when (code) {
        "еда" -> if (russian) "Еда" else "Food"
        "транспорт" -> if (russian) "Транспорт" else "Transport"
        "жилье" -> if (russian) "Жильё" else "Housing"
        "досуг" -> if (russian) "Досуг" else "Leisure"
        "одежда" -> if (russian) "Одежда" else "Clothing"
        "здоровье" -> if (russian) "Здоровье" else "Health"
        "работа" -> if (russian) "Работа" else "Work"
        "техника" -> if (russian) "Техника" else "Technology"
        "долги" -> if (russian) "Долги" else "Debt"
        "прочее" -> if (russian) "Прочее" else "Other"
        else -> code
    }
}

private fun receiptCategoryErrorMessage(code: String, language: String): String = when (code) {
    "category_conflict" -> if (language == "ru")
        "Категория чека изменилась. Проверьте данные и выберите категорию снова."
    else "Receipt category changed. Review the receipt and choose a category again."
    "category_refresh_unavailable" -> if (language == "ru")
        "Не удалось обновить чек после конфликта. Проверьте связь и обновите чек."
    else "Could not refresh the receipt after a conflict. Check your connection and refresh it."
    else -> if (language == "ru") "Не удалось сохранить категорию. Проверьте связь и повторите попытку."
    else "Could not save category. Check your connection and retry."
}

private data class FinanceWorkspaceSnapshot(
    val tenants: List<FinanceTenant>,
    val transactions: List<FinanceTransaction>,
    val transactionMembers: List<FinanceTenantMember>,
    val transactionNextCursor: String?,
    val transactionSearch: String,
    val transactionTypeFilter: String,
    val transactionFrom: String,
    val transactionTo: String,
    val transactionMemberId: String,
    val budgets: BudgetOverview?,
    val debts: List<FinanceDebt>,
    val proposal: BudgetProposal?,
    val dashboardSummary: DashboardSummary?,
    val report: FinanceReport?,
    val transactionDraft: FinanceTransactionDraft?,
    val memberProfile: FinanceMemberProfile?,
    val notificationPreferences: FinanceNotificationPreferences?,
    val budgetAlerts: List<FinanceBudgetAlert>,
    val transactionEditSavedToken: String? = null,
)

internal data class ReceiptItemUpdateWithPageRefreshResult(
    val updatedReceipt: FinanceReceipt? = null,
    val updatedPage: FinanceReceiptItemPage? = null,
    val mutationError: Throwable? = null,
    val pageRefreshError: Throwable? = null,
)

internal data class ReceiptConflictRefreshResult(
    val updatedReceipt: FinanceReceipt? = null,
    val page: FinanceReceiptItemPage? = null,
    val receiptRefreshError: Throwable? = null,
    val pageRefreshError: Throwable? = null,
)

internal fun refreshReceiptAfterConflictWithPage(
    currentPage: FinanceReceiptItemPage?,
    refreshReceipt: () -> FinanceReceipt,
    refreshPage: (FinanceReceipt) -> FinanceReceiptItemPage,
): ReceiptConflictRefreshResult {
    val updated = try {
        refreshReceipt()
    } catch (error: Exception) {
        return ReceiptConflictRefreshResult(page = currentPage, receiptRefreshError = error)
    }
    return try {
        ReceiptConflictRefreshResult(updatedReceipt = updated, page = refreshPage(updated))
    } catch (error: Exception) {
        ReceiptConflictRefreshResult(updatedReceipt = updated, page = currentPage, pageRefreshError = error)
    }
}

internal fun updateReceiptItemWithPageRefresh(
    update: () -> FinanceReceipt,
    refreshPage: (FinanceReceipt) -> FinanceReceiptItemPage,
): ReceiptItemUpdateWithPageRefreshResult {
    val updated = try {
        update()
    } catch (error: Exception) {
        return ReceiptItemUpdateWithPageRefreshResult(mutationError = error)
    }
    return try {
        ReceiptItemUpdateWithPageRefreshResult(updatedReceipt = updated, updatedPage = refreshPage(updated))
    } catch (error: Exception) {
        ReceiptItemUpdateWithPageRefreshResult(updatedReceipt = updated, pageRefreshError = error)
    }
}

data class FinanceTenant(val id: String, val name: String, val role: String, val timezone: String)

internal enum class ReceiptReviewRecoveryAction {
    REFRESH_RECEIPT_AND_CANDIDATES,
    SHOW_ERROR,
}

internal object ReceiptReviewRecoveryPolicy {
    fun actionFor(httpStatus: Int?): ReceiptReviewRecoveryAction = when (httpStatus) {
        409, 412 -> ReceiptReviewRecoveryAction.REFRESH_RECEIPT_AND_CANDIDATES
        else -> ReceiptReviewRecoveryAction.SHOW_ERROR
    }
}

@androidx.compose.runtime.Composable
internal fun FinanceScreen(state: FinanceUiState, language: String, onLanguage: (String) -> Unit,
                          onLogin: () -> Unit, onRefresh: () -> Unit,
                          onCreate: (String, String, String?) -> Unit, onProfileSave: (String, String?) -> Unit,
                          onRepeatProfileSave: (String, String?, (Boolean) -> Unit) -> Unit =
                              { name, income, onFinished -> onProfileSave(name, income); onFinished(true) },
                          onCreateDraft: (String, String) -> Unit,
                          onUpdateDraft: (TransactionDraftEdit) -> Unit,
                          onConfirmDraft: (String, Long) -> Unit,
                          onCancelDraft: (String, Long) -> Unit,
                          onLogout: () -> Unit,
                          onBudgetUpdate: (String, String, String, String, Long) -> Unit,
                          onBudgetReset: () -> Unit, onBudgetProposal: (String?) -> Unit,
                          onBudgetApply: (String) -> Unit,
                          onBudgetKeep: () -> Unit = {},
                          onDebtCreate: (String, String, String?, String) -> Unit,
                          onDebtPay: (String, String, Long) -> Unit,
                          onDebtAdjust: (String, String, Long) -> Unit,
                          onDebtForecast: (String) -> Unit,
                          onReportLoad: (String, String, String, String, String) -> Unit,
                          onRecalculationPreview: () -> Unit = {},
                          onRecalculationApply: (String) -> Unit = {},
                          onRecalculationHistory: (String?) -> Unit = {},
                          onRecalculationDetail: (String, String?) -> Unit = { _, _ -> },
                          onFamilyBudgetFoodStatusLoad: (String) -> Unit = {},
                          onCreateTelegramLink: () -> Unit = {},
                          onNotificationPreferencesSave: (FinanceNotificationPreferences) -> Unit = {},
                          onShoppingLoad: () -> Unit = {},
                          onShoppingDecision: (String, String) -> Unit = { _, _ -> },
                          onShoppingCopy: (String) -> Unit = {},
                          onDoNotBuyLoad: () -> Unit = {},
                          onDoNotBuyDecision: (String, String) -> Unit = { _, _ -> },
                          onPersonalInflationLoad: () -> Unit = {},
                          onProductCatalogLoad: (String, String) -> Unit = { _, _ -> },
                          onRecurringLoad: () -> Unit = {},
                          onRecurringDecision: (String, Boolean) -> Unit = { _, _ -> },
                          onRepeatTransaction: (FinanceTransaction) -> Unit = {},
                          onVoidTransaction: (FinanceTransaction) -> Unit = {},
                          onTransactionFilter: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
                          onTransactionLoadMore: () -> Unit = {},
                           onUpdateTransaction: (FinanceTransactionEdit) -> Unit = {},
                          onReceiptPick: () -> Unit = {},
                          onReceiptRefresh: () -> Unit = {},
                          onReceiptRetry: () -> Unit = {},
                          onReceiptDiscard: () -> Unit = {},
                          onReceiptReading: (String) -> Unit = {},
                          onReceiptItemsPage: (String, Int) -> Unit = { _, _ -> },
                          onReceiptItemUpdate: (String, String, Long, String, String, String, String) -> Unit =
                              { _, _, _, _, _, _, _ -> },
                          onReceiptItemRefresh: (String) -> Unit = {},
                          onReceiptTotalSync: (String, Long) -> Unit = { _, _ -> },
                          onReceiptTotalSyncRefresh: (String) -> Unit = {},
                          onReceiptItemAdd: (String, Long, String, String, String, String) -> Unit =
                              { _, _, _, _, _, _ -> },
                          onReceiptItemAddRefresh: (String) -> Unit = {},
                          onReceiptDuplicateCandidates: (String) -> Unit = {},
                          onReceiptDuplicateDecision: (String, Long, String, String?) -> Unit = { _, _, _, _ -> },
                          onReceiptConfirm: (String, Long, String) -> Unit = { _, _, _ -> },
                          onReceiptCategorySelect: (String, Long, String) -> Unit = { _, _, _ -> },
                          onReceiptBasketReview: (String, Long) -> Unit = { _, _ -> },
                          onReceiptDisputedItemsPage: (String, Int) -> Unit = { _, _ -> },
                          onReceiptRepeatWarningsRefresh: (String) -> Unit = {},
                          onReceiptDisputedProductDecision: (String, String) -> Unit = { _, _ -> },
                          onReceiptPriceComparison: (String, String, String) -> Unit = { _, _, _ -> }) {
    val russian = language == "ru"
    var workspace by androidx.compose.runtime.remember { mutableStateOf("") }
    var memberName by androidx.compose.runtime.remember { mutableStateOf("") }
    var plannedIncome by androidx.compose.runtime.remember { mutableStateOf("") }
    var onboardingStep by androidx.compose.runtime.remember { mutableStateOf("welcome") }
    var onboardingCreatePending by androidx.compose.runtime.remember { mutableStateOf(false) }
    var onboardingIncomeForProposal by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var onboardingProfileSavePending by androidx.compose.runtime.remember { mutableStateOf(false) }
    var onboardingProfileIncomeForProposal by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var repeatOnboarding by androidx.compose.runtime.remember { mutableStateOf(false) }
    var onboardingBudgetTenantId by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var onboardingApplyPending by androidx.compose.runtime.remember { mutableStateOf(false) }
    var transactionText by androidx.compose.runtime.remember { mutableStateOf("") }
    var transactionSearch by androidx.compose.runtime.remember(state.transactionSearch) { mutableStateOf(state.transactionSearch) }
    var transactionTypeFilter by androidx.compose.runtime.remember(state.transactionTypeFilter) {
        mutableStateOf(state.transactionTypeFilter)
    }
    var transactionFrom by androidx.compose.runtime.remember(state.transactionFrom) { mutableStateOf(state.transactionFrom) }
    var transactionTo by androidx.compose.runtime.remember(state.transactionTo) { mutableStateOf(state.transactionTo) }
    var transactionMemberId by androidx.compose.runtime.remember(state.transactionMemberId) {
        mutableStateOf(state.transactionMemberId)
    }
    var transactionMemberPickerExpanded by androidx.compose.runtime.remember { mutableStateOf(false) }
    var editingTransaction by androidx.compose.runtime.remember { mutableStateOf<FinanceTransaction?>(null) }
    var validateTransactionDates by androidx.compose.runtime.remember { mutableStateOf(false) }
    val transactionDateError = if (validateTransactionDates)
        transactionDateValidationError(transactionFrom, transactionTo, russian) else null
    var draftCreateKey by androidx.compose.runtime.remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var activeScreen by androidx.compose.runtime.remember { mutableStateOf("overview") }
    val canWriteTransactions = state.tenants.firstOrNull()?.role != "viewer"
    val role = state.tenants.firstOrNull()?.role
    val canManageFamilyTransactions = role == "owner" || role == "admin"
    val currentUserId = state.transactionMembers.singleOrNull()?.userId
    LaunchedEffect(state.tenants.firstOrNull()?.id, state.budgetProposal?.id, state.busy,
        state.memberProfile?.displayName, state.memberProfile?.plannedIncome, state.memberProfile?.onboardingState,
        onboardingCreatePending, onboardingApplyPending) {
        val tenant = state.tenants.firstOrNull()
        if (onboardingCreatePending && tenant != null && !state.busy) {
            onboardingCreatePending = false
            onboardingBudgetTenantId = tenant.id
            val income = onboardingIncomeForProposal
            if (income != null) {
                onboardingStep = "budget"
                if (state.budgetProposal == null) onBudgetProposal(income)
            } else {
                onboardingStep = "complete"
                onboardingBudgetTenantId = null
            }
        }
        if (onboardingCreatePending && tenant == null && !state.busy && state.error != null) {
            onboardingCreatePending = false
        }
        if (onboardingApplyPending && !state.busy && state.budgetProposal == null) {
            onboardingApplyPending = false
            onboardingStep = "complete"
            onboardingBudgetTenantId = null
            if (repeatOnboarding) {
                repeatOnboarding = false
                activeScreen = "profile"
            }
        }
    }
    LaunchedEffect(state.transactionEditSavedToken) {
        if (state.transactionEditSavedToken != null) editingTransaction = null
    }
    LaunchedEffect(state.receiptDraft?.id, state.receiptDraft?.documentId, state.receiptDraft?.selectedReader,
        state.receiptReadingReceiptId, state.receiptReadingLoading) {
        val receipt = state.receiptDraft
        if (receipt?.documentId != null && receipt.selectedReader != "manual" &&
            state.receiptReadingReceiptId != receipt.id) {
            onReceiptReading(receipt.id)
        }
    }
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
            Text("${if (alert.threshold == "near") "⚠️" else "🚨"} $threshold: $label · " +
                "${formatMoney(alert.spent, language)} / ${formatMoney(alert.limit, language)}",
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
            if (state.tenants.isEmpty() || (repeatOnboarding && onboardingStep != "budget")) {
                when (onboardingStep) {
                    "welcome" -> Column(Modifier.weight(1f).testTag("onboarding-welcome"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (repeatOnboarding) {
                            if (russian) "Повторная настройка" else "Repeat setup"
                        } else if (russian) "Добро пожаловать" else "Welcome",
                            style = MaterialTheme.typography.titleLarge)
                        Text(if (repeatOnboarding) {
                            if (russian) "Проверьте имя и плановый доход в текущем профиле. История операций сохранится."
                            else "Review your name and planned income in your current profile. Your transaction history stays in place."
                        } else if (russian) "Сначала создадим личное пространство. Семью можно добавить позже."
                            else "Start with a personal workspace. You can add family later.")
                        Button(modifier = Modifier.testTag("onboarding-start"), enabled = !state.busy,
                            onClick = { onboardingStep = "identity" }) {
                            Text(if (russian) "Начать настройку" else "Start setup")
                        }
                        if (repeatOnboarding) {
                            TextButton(modifier = Modifier.testTag("repeat-onboarding-cancel"),
                                enabled = !state.busy,
                                onClick = {
                                    repeatOnboarding = false
                                    onboardingStep = "complete"
                                    activeScreen = "profile"
                                }) {
                                Text(if (russian) "Отмена" else "Cancel")
                            }
                        }
                    }
                    "identity" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .testTag("onboarding-identity"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (repeatOnboarding) {
                            if (russian) "Ваш профиль" else "Your profile"
                        } else if (russian) "Личное пространство" else "Personal workspace",
                            style = MaterialTheme.typography.titleLarge)
                        if (!repeatOnboarding) {
                            OutlinedTextField(workspace, { workspace = it }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().testTag("onboarding-workspace-name"),
                                label = { Text(if (russian) "Название пространства" else "Workspace name") }, singleLine = true)
                        }
                        OutlinedTextField(memberName, { memberName = it }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag("onboarding-member-name"),
                            label = { Text(if (russian) "Ваше имя" else "Your name") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !state.busy, onClick = { onboardingStep = "welcome" }) {
                                Text(if (russian) "Назад" else "Back")
                            }
                            Button(modifier = Modifier.testTag("onboarding-identity-next"),
                                enabled = !state.busy && (repeatOnboarding || workspace.isNotBlank()) && memberName.isNotBlank(),
                                onClick = { onboardingStep = "income" }) {
                                Text(if (russian) "Далее" else "Next")
                            }
                        }
                    }
                    "income" -> {
                        val normalizedIncome = plannedIncome.trim().replace(',', '.')
                        val validIncome = normalizedIncome.isBlank() || Regex("^(?:0\\.(?:[0-9]?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\\.[0-9]{1,2})?)$")
                            .matches(normalizedIncome)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                            .testTag("onboarding-income"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (russian) "Плановый доход" else "Planned income",
                                style = MaterialTheme.typography.titleLarge)
                            Text(if (russian) "Доход необязателен. Он поможет предложить лимиты, но не изменит их без вашего решения."
                                else "Income is optional. It can suggest limits, but never changes them without your choice.")
                            OutlinedTextField(plannedIncome, { plannedIncome = it }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().testTag("onboarding-income-value"),
                                singleLine = true, label = { Text(if (russian) "Плановый доход в месяц, ₽"
                                    else "Planned monthly income, RUB") })
                            TextButton(modifier = Modifier.testTag("onboarding-income-skip"),
                                onClick = { plannedIncome = "" }, enabled = !state.busy) {
                                Text(if (russian) "Пропустить доход" else "Skip income")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(modifier = Modifier.testTag("onboarding-income-back"),
                                    onClick = { onboardingStep = "identity" }, enabled = !state.busy) {
                                    Text(if (russian) "Назад" else "Back")
                                }
                                Button(modifier = Modifier.testTag("onboarding-income-next"),
                                    enabled = !state.busy && !onboardingCreatePending &&
                                        !onboardingProfileSavePending && validIncome,
                                    onClick = {
                                        if (repeatOnboarding) {
                                            onboardingProfileIncomeForProposal = normalizedIncomeInput(plannedIncome)
                                            onboardingProfileSavePending = true
                                            onRepeatProfileSave(memberName.trim(), onboardingProfileIncomeForProposal) {
                                                onboardingProfileSavePending = false
                                                if (!it) return@onRepeatProfileSave
                                                val tenant = state.tenants.firstOrNull()
                                                val income = onboardingProfileIncomeForProposal
                                                if (income != null && tenant != null) {
                                                    onboardingBudgetTenantId = tenant.id
                                                    onboardingStep = "budget"
                                                    onBudgetProposal(income)
                                                } else {
                                                    onboardingStep = "complete"
                                                    onboardingBudgetTenantId = null
                                                    repeatOnboarding = false
                                                    activeScreen = "profile"
                                                }
                                            }
                                        } else {
                                            onboardingIncomeForProposal = normalizedIncomeInput(plannedIncome)
                                            onboardingCreatePending = true
                                            onCreate(workspace.trim(), memberName.trim(), onboardingIncomeForProposal)
                                        }
                                    }) {
                                    Text(if (repeatOnboarding) {
                                        if (russian) "Сохранить профиль" else "Save profile"
                                    } else if (russian) "Создать пространство" else "Create workspace")
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            } else {
                Text(state.tenants.first().name, style = MaterialTheme.typography.headlineSmall)
                if (onboardingStep == "budget" && onboardingBudgetTenantId == state.tenants.first().id) {
                    OnboardingBudgetChoiceScreen(language, state.budgetProposal?.takeIf { it.status == "pending" },
                        state.busy, state.error,
                        onApply = { proposal ->
                            onboardingApplyPending = true
                            onBudgetApply(proposal.id)
                        },
                        onKeep = {
                            onBudgetKeep()
                            onboardingStep = "complete"
                            onboardingBudgetTenantId = null
                            if (repeatOnboarding) {
                                repeatOnboarding = false
                                activeScreen = "profile"
                            }
                        })
                } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("overview", "transactions", "receipts", "shopping", "budgets", "debts", "reports", "profile", "inflation", "recurring", "nobuy", "products").forEach { screen ->
                        TextButton(onClick = {
                            activeScreen = screen
                            val catalogTenantId = state.tenants.firstOrNull()?.id
                            if (screen == "products" && catalogTenantId != null &&
                                (state.productCatalogTenantId != catalogTenantId ||
                                    state.productCatalog == null && !state.productCatalogLoading)) {
                                onProductCatalogLoad(catalogTenantId, "")
                            }
                            if (screen == "shopping" && state.shoppingList == null && !state.shoppingLoading) onShoppingLoad()
                            if (screen == "nobuy" && state.doNotBuy == null && !state.doNotBuyLoading) onDoNotBuyLoad()
                            if (screen == "inflation" && state.personalInflation == null && !state.personalInflationLoading) {
                                onPersonalInflationLoad()
                            }
                            if (screen == "recurring" && state.recurringProjection == null && !state.recurringLoading) {
                                onRecurringLoad()
                            }
                        }) {
                            Text(when (screen) {
                                "overview" -> if (russian) "Обзор" else "Overview"
                                "receipts" -> if (russian) "Чеки" else "Receipts"
                                "products" -> if (russian) "Товары" else "Products"
                                "shopping" -> if (russian) "Покупки" else "Shopping"
                                "nobuy" -> if (russian) "Не брать" else "Do not buy"
                                "inflation" -> if (russian) "Динамика цен" else "Price trend"
                                "recurring" -> if (russian) "Регулярные" else "Recurring"
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
                    "receipts" -> ReceiptUploadScreen(Modifier.weight(1f), language,
                        state.tenants.firstOrNull()?.role != "viewer",
                        canWriteReceiptCategory(state.tenants.firstOrNull()?.role),
                        state.receiptJob, state.receiptDraft, state.receiptUploadInProgress,
                        state.receiptUploadError, state.receiptCanRetryUpload, state.busy,
                        state.receiptCheckpointUnresolved, state.receiptReading,
                        state.receiptReadingReceiptId, state.receiptReadingLoading, state.receiptReadingError,
                        state.receiptItemsPage, state.receiptItemsReceiptId, state.receiptItemsRequestedPage,
                        state.receiptItemsLoading, state.receiptItemsError,
                        state.receiptItemEditError, state.receiptItemEditSavedToken,
                        state.receiptTotalSyncError, state.receiptTotalSyncInProgress,
                        state.receiptItemAddError, state.receiptItemAddNeedsRefresh,
                        state.receiptItemAddSavedToken, state.receiptDuplicateCandidates,
                        state.receiptDuplicateCandidatesReceiptId, state.receiptDuplicateCandidatesLoading,
                        state.receiptDuplicateCandidatesError, state.receiptDuplicateDecisionInProgress,
                        state.receiptDuplicateDecisionError, state.receiptConfirming, state.receiptConfirmError,
                        state.receiptCategorySaving, state.receiptCategoryError,
                        state.receiptBasketReviewing &&
                            state.receiptBasketReviewingReceiptId == state.receiptDraft?.id &&
                            state.receiptBasketReviewingTenantId == state.tenants.firstOrNull()?.id,
                        state.receiptBasketReviewError,
                        state.receiptDisputedItemsPage, state.receiptDisputedItemsReceiptId,
                        state.receiptDisputedItemsRequestedPage, state.receiptDisputedItemsLoading,
                        state.receiptDisputedItemsError, state.productDecisions,
                        state.receiptRepeatWarnings, state.receiptRepeatWarningsReceiptId,
                        state.receiptRepeatWarningsRevision, state.receiptRepeatWarningsLoading,
                        state.receiptRepeatWarningsError,
                        canReviewReceiptBasket(state.tenants.firstOrNull()?.role),
                        onReceiptPick, onReceiptRefresh, onReceiptRetry, onReceiptDiscard, onReceiptReading,
                        onReceiptItemsPage, onReceiptItemUpdate, onReceiptItemRefresh,
                        onReceiptTotalSync, onReceiptTotalSyncRefresh, onReceiptItemAdd, onReceiptItemAddRefresh,
                        onReceiptDuplicateCandidates, onReceiptDuplicateDecision, onReceiptConfirm,
                        onReceiptCategorySelect, onReceiptBasketReview,
                        onReceiptDisputedItemsPage, onReceiptRepeatWarningsRefresh, onReceiptDisputedProductDecision,
                        state.productPriceComparison, state.productPriceComparisonReceiptId,
                        state.productPriceComparisonItemId, state.productPriceComparisonLoading,
                        state.productPriceComparisonError, onReceiptPriceComparison)
                    "shopping" -> ShoppingScreen(Modifier.weight(1f), state, language, onShoppingLoad,
                        onShoppingDecision, onShoppingCopy)
                    "nobuy" -> DoNotBuyScreen(Modifier.weight(1f), state, language, onDoNotBuyLoad,
                        onDoNotBuyDecision)
                    "inflation" -> PersonalInflationScreen(Modifier.weight(1f), state, language,
                        onRetry = onPersonalInflationLoad)
                    "products" -> ProductCatalogScreen(Modifier.weight(1f), language,
                        state.memberProfile?.currency ?: "RUB",
                        state.productCatalog?.takeIf { state.productCatalogTenantId == state.tenants.firstOrNull()?.id },
                        state.productCatalogRequestedQuery, state.productCatalogLoading, state.productCatalogError,
                        onSearch = { query ->
                            state.tenants.firstOrNull()?.let { onProductCatalogLoad(it.id, query) }
                        },
                        onRetry = {
                            state.tenants.firstOrNull()?.let {
                                onProductCatalogLoad(it.id, state.productCatalogRequestedQuery)
                            }
                        })
                    "recurring" -> RecurringScreen(Modifier.weight(1f), state, language, onRetry = onRecurringLoad,
                        onRecurringDecision = onRecurringDecision)
                    "budgets" -> BudgetScreen(Modifier.weight(1f), state, language, onBudgetUpdate, onBudgetReset,
                        onBudgetProposal, onBudgetApply, onFamilyBudgetFoodStatusLoad)
                    "debts" -> DebtScreen(state, language, onDebtCreate, onDebtPay, onDebtAdjust, onDebtForecast)
                    "reports" -> ReportScreen(state, language, onReportLoad,
                        onRecalculationPreview, onRecalculationApply,
                        onRecalculationHistory, onRecalculationDetail)
                    "profile" -> ProfileScreen(Modifier.weight(1f), state, language, onProfileSave,
                        onRepeatSetup = { name, income ->
                            memberName = name
                            plannedIncome = income.orEmpty()
                            onboardingProfileIncomeForProposal = null
                            onboardingProfileSavePending = false
                            repeatOnboarding = true
                            onboardingStep = "welcome"
                        }, onCreateTelegramLink,
                        onNotificationPreferencesSave,
                        onBack = { activeScreen = "overview" })
                    else -> {
                        LazyColumn(Modifier.weight(1f).testTag("transaction-history"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = transactionSearch,
                                        onValueChange = { transactionSearch = it },
                                        modifier = Modifier.fillMaxWidth().testTag("transaction-search"),
                                        label = { Text(if (russian) "Поиск операций" else "Search transactions") },
                                        singleLine = true,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = transactionTypeFilter == "all",
                                            modifier = Modifier.testTag("transaction-filter-all"),
                                            onClick = { transactionTypeFilter = "all" },
                                            label = { Text(if (russian) "Все" else "All") })
                                        FilterChip(selected = transactionTypeFilter == "expense",
                                            modifier = Modifier.testTag("transaction-filter-expense"),
                                            onClick = { transactionTypeFilter = "expense" },
                                            label = { Text(if (russian) "Расходы" else "Expenses") })
                                        FilterChip(selected = transactionTypeFilter == "income",
                                            modifier = Modifier.testTag("transaction-filter-income"),
                                            onClick = { transactionTypeFilter = "income" },
                                            label = { Text(if (russian) "Доходы" else "Income") })
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(value = transactionFrom, onValueChange = { transactionFrom = it },
                                            modifier = Modifier.weight(1f).testTag("transaction-filter-from"),
                                            label = { Text(if (russian) "С даты" else "From date") }, singleLine = true)
                                        OutlinedTextField(value = transactionTo, onValueChange = { transactionTo = it },
                                            modifier = Modifier.weight(1f).testTag("transaction-filter-to"),
                                            label = { Text(if (russian) "По дату" else "To date") }, singleLine = true)
                                    }
                                    transactionDateError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    val role = state.tenants.firstOrNull()?.role
                                    val canSeeAllMembers = role == "owner" || role == "admin"
                                    val selectedMemberId = transactionMemberId
                                    val selectedMemberLabel = when {
                                        selectedMemberId == "all" -> if (russian) "Все участники" else "All members"
                                        selectedMemberId.isBlank() -> if (russian) "Мои операции" else "My transactions"
                                        else -> state.transactionMembers.firstOrNull { it.userId == selectedMemberId }
                                            ?.displayName ?: if (russian) "Выберите участника" else "Select member"
                                    }
                                    Box {
                                        TextButton(modifier = Modifier.testTag("transaction-filter-member-picker"),
                                            onClick = { transactionMemberPickerExpanded = true }) {
                                            Text("${if (russian) "Участник" else "Member"}: $selectedMemberLabel")
                                        }
                                        DropdownMenu(expanded = transactionMemberPickerExpanded,
                                            onDismissRequest = { transactionMemberPickerExpanded = false }) {
                                            DropdownMenuItem(
                                                modifier = Modifier.testTag("transaction-member-option-self"),
                                                text = { Text(if (russian) "Мои операции" else "My transactions") },
                                                onClick = {
                                                    transactionMemberId = ""
                                                    transactionMemberPickerExpanded = false
                                                })
                                            if (canSeeAllMembers) DropdownMenuItem(
                                                modifier = Modifier.testTag("transaction-member-option-all"),
                                                text = { Text(if (russian) "Все участники" else "All members") },
                                                onClick = {
                                                    transactionMemberId = "all"
                                                    transactionMemberPickerExpanded = false
                                                })
                                            state.transactionMembers.forEach { member ->
                                                DropdownMenuItem(
                                                    modifier = Modifier.testTag("transaction-member-option-${member.userId}"),
                                                    text = { Text(member.displayName) },
                                                    onClick = {
                                                        transactionMemberId = member.userId
                                                        transactionMemberPickerExpanded = false
                                                    })
                                            }
                                        }
                                    }
                                    Button(enabled = !state.busy,
                                        onClick = {
                                            validateTransactionDates = true
                                            if (transactionDateValidationError(transactionFrom, transactionTo, russian) == null)
                                                onTransactionFilter(transactionSearch,
                                                transactionTypeFilter, transactionFrom, transactionTo, selectedMemberId)
                                        }) {
                                        Text(if (russian) "Применить фильтры" else "Apply filters")
                                    }
                                }
                            }
                            editingTransaction?.let { transaction ->
                                item(key = "transaction-edit-${transaction.id}") {
                                    TransactionEditEditor(transaction, state.debts, state.tenants.first().timezone,
                                        state.transactionMembers, canManageFamilyTransactions, language, state.busy,
                                        onSave = onUpdateTransaction,
                                        onCancel = { editingTransaction = null })
                                }
                            }
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
                            if (state.transactions.isEmpty() && !state.busy) item {
                                Text(if (russian) "Нет операций по выбранным фильтрам"
                                    else "No transactions match these filters")
                            }
                            items(state.transactions) { transaction ->
                                val canEditThisTransaction = canWriteTransactions && transaction.status == "posted" &&
                                    (canManageFamilyTransactions || transaction.ownerUserId == currentUserId)
                                TransactionHistoryCard(transaction, language, canWriteTransactions, state.busy,
                                    canEditThisTransaction,
                                    onEdit = { editingTransaction = it },
                                    onRepeat = onRepeatTransaction, onVoid = onVoidTransaction)
                            }
                            if (state.transactionNextCursor != null) item {
                                Button(modifier = Modifier.testTag("transaction-load-more"), enabled = !state.busy,
                                    onClick = onTransactionLoadMore) {
                                    Text(if (russian) "Загрузить ещё" else "Load more")
                                }
                            }
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

private val LOCAL_RECEIPT_FILE_ERRORS = setOf("file_read", "file_type", "file_size", "file_empty")

@androidx.compose.runtime.Composable
private fun OnboardingBudgetChoiceScreen(language: String, proposal: BudgetProposal?, pending: Boolean,
                                         error: String?, onApply: (BudgetProposal) -> Unit,
                                         onKeep: () -> Unit) {
    val russian = language == "ru"
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .testTag("onboarding-budget-choice"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (russian) "03 / 03 · Лимиты" else "03 / 03 · Budget limits",
            style = MaterialTheme.typography.titleLarge)
        Text(if (russian) "Предложение не меняет лимиты. Примените его только если суммы вам подходят."
            else "This suggestion does not change your limits. Apply it only if the amounts work for you.")
        if (pending && proposal == null) {
            Text(if (russian) "Готовим предложение…" else "Preparing suggestion…")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        proposal?.let { current ->
            Text(if (russian) "Предложенный общий лимит: ${formatMoney(current.totalLimit, language)}"
                else "Suggested total limit: ${formatMoney(current.totalLimit, language)}",
                modifier = Modifier.testTag("onboarding-budget-proposal-total"))
            current.limits.forEach { (category, amount) ->
                Text("$category · ${formatMoney(amount, language)}")
            }
            Button(modifier = Modifier.testTag("onboarding-budget-apply"),
                enabled = !pending && current.status == "pending", onClick = { onApply(current) }) {
                Text(if (russian) "Применить лимиты" else "Apply limits")
            }
        }
        TextButton(modifier = Modifier.testTag("onboarding-budget-keep"), enabled = !pending,
            onClick = onKeep) {
            Text(if (russian) "Оставить текущие лимиты" else "Keep current limits")
        }
    }
}

@androidx.compose.runtime.Composable
private fun ReceiptUploadScreen(modifier: Modifier, language: String, canWrite: Boolean,
                                canEditCategory: Boolean, job: FinanceReceiptProcessingJob?,
                                receipt: FinanceReceipt?, uploading: Boolean, errorCode: String?,
                                canRetryUpload: Boolean, busy: Boolean, checkpointUnresolved: Boolean,
                                reading: FinanceReceiptReading?, readingReceiptId: String?, readingLoading: Boolean,
                                readingError: String?,
                                receiptItemsPage: FinanceReceiptItemPage?, receiptItemsReceiptId: String?,
                                receiptItemsRequestedPage: Int?, receiptItemsLoading: Boolean,
                                receiptItemsError: String?, receiptItemEditError: String?,
                                receiptItemEditSavedToken: String?,
                                receiptTotalSyncError: String?, receiptTotalSyncInProgress: Boolean,
                                receiptItemAddError: String?, receiptItemAddNeedsRefresh: Boolean,
                                receiptItemAddSavedToken: String?,
                                duplicateCandidates: FinanceReceiptDuplicateCandidates?,
                                duplicateCandidatesReceiptId: String?, duplicateCandidatesLoading: Boolean,
                                duplicateCandidatesError: String?, duplicateDecisionInProgress: Boolean,
                                duplicateDecisionError: String?, receiptConfirming: Boolean,
                                receiptConfirmError: String?,
                                categorySaving: Boolean, categoryError: String?,
                                basketReviewing: Boolean, basketReviewError: String?,
                                disputedItemsPage: FinanceReceiptItemPage?, disputedItemsReceiptId: String?,
                                disputedItemsRequestedPage: Int?, disputedItemsLoading: Boolean,
                                disputedItemsError: String?, productDecisions: FinanceProductDecisions?,
                                repeatWarnings: FinanceReceiptRepeatWarnings?, repeatWarningsReceiptId: String?,
                                repeatWarningsRevision: String?,
                                repeatWarningsLoading: Boolean, repeatWarningsError: String?,
                                canReviewBasket: Boolean,
                                onPick: () -> Unit,
                                onRefresh: () -> Unit, onRetry: () -> Unit, onDiscard: () -> Unit,
                                onLoadReading: (String) -> Unit,
                                onLoadItemsPage: (String, Int) -> Unit,
                                onUpdateItem: (String, String, Long, String, String, String, String) -> Unit,
                                onRefreshItem: (String) -> Unit,
                                onSyncTotal: (String, Long) -> Unit,
                                onRefreshTotalSync: (String) -> Unit,
                                onAddItem: (String, Long, String, String, String, String) -> Unit,
                                onRefreshAddItem: (String) -> Unit,
                                onLoadDuplicateCandidates: (String) -> Unit,
                                onDuplicateDecision: (String, Long, String, String?) -> Unit,
                                onConfirmReceipt: (String, Long, String) -> Unit,
                                onSelectCategory: (String, Long, String) -> Unit,
                                onReviewBasket: (String, Long) -> Unit,
                                onDisputedItemsPage: (String, Int) -> Unit,
                                onRepeatWarningsRefresh: (String) -> Unit,
                                onDisputedProductDecision: (String, String) -> Unit,
                                productPriceComparison: FinanceProductPriceComparison?,
                                productPriceComparisonReceiptId: String?, productPriceComparisonItemId: String?,
                                productPriceComparisonLoading: Boolean, productPriceComparisonError: String?,
                                onPriceComparison: (String, String, String) -> Unit) {
    val confirmationIdempotencyKey = androidx.compose.runtime.remember(receipt?.tenantId, receipt?.id) {
        receipt?.let { receiptConfirmationIdempotencyKey(it.tenantId, it.id) }.orEmpty()
    }
    var pageNumber by androidx.compose.runtime.remember(receipt?.id) {
        androidx.compose.runtime.mutableIntStateOf(1)
    }
    var disputedPageNumber by androidx.compose.runtime.remember(receipt?.id) {
        androidx.compose.runtime.mutableIntStateOf(disputedItemsPage?.page?.takeIf {
            disputedItemsReceiptId == receipt?.id
        } ?: 1)
    }
    var editingItemId by androidx.compose.runtime.remember(receipt?.id) {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    var editName by androidx.compose.runtime.remember(receipt?.id) {
        androidx.compose.runtime.mutableStateOf("")
    }
    var editQuantity by androidx.compose.runtime.remember(receipt?.id) {
        androidx.compose.runtime.mutableStateOf("")
    }
    var editUnitPrice by androidx.compose.runtime.remember(receipt?.id) {
        androidx.compose.runtime.mutableStateOf("")
    }
    var editLineSum by androidx.compose.runtime.remember(receipt?.id) {
        androidx.compose.runtime.mutableStateOf("")
    }
    var addingItem by androidx.compose.runtime.remember(receipt?.id) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var addName by androidx.compose.runtime.remember(receipt?.id) { androidx.compose.runtime.mutableStateOf("") }
    var addQuantity by androidx.compose.runtime.remember(receipt?.id) { androidx.compose.runtime.mutableStateOf("1") }
    var addUnitPrice by androidx.compose.runtime.remember(receipt?.id) { androidx.compose.runtime.mutableStateOf("") }
    var addLineSum by androidx.compose.runtime.remember(receipt?.id) { androidx.compose.runtime.mutableStateOf("") }
    var categoryMenuExpanded by androidx.compose.runtime.remember(receipt?.id) { mutableStateOf(false) }
    var selectedCategory by androidx.compose.runtime.remember(receipt?.id, receipt?.version, receipt?.categoryCode) {
        mutableStateOf(receipt?.categoryCode)
    }
    LaunchedEffect(receipt?.id, canWrite, duplicateCandidatesReceiptId,
        duplicateCandidatesLoading, duplicateCandidatesError) {
        val draft = receipt ?: return@LaunchedEffect
        if (canWrite && draft.state != "confirmed" && duplicateCandidatesReceiptId != draft.id &&
            !duplicateCandidatesLoading && duplicateCandidatesError == null) {
            onLoadDuplicateCandidates(draft.id)
        }
    }
    androidx.compose.runtime.LaunchedEffect(receiptItemAddSavedToken) {
        if (receiptItemAddSavedToken != null) {
            addingItem = false
            addName = ""
            addQuantity = "1"
            addUnitPrice = ""
            addLineSum = ""
        }
    }
    androidx.compose.runtime.LaunchedEffect(receiptItemEditSavedToken) {
        if (receiptItemEditSavedToken != null) editingItemId = null
    }
    var lastRequestedPage by androidx.compose.runtime.remember(receipt?.id) {
        androidx.compose.runtime.mutableStateOf<Int?>(null)
    }
    var lastRequestedDisputedPage by androidx.compose.runtime.remember(receipt?.id) {
        androidx.compose.runtime.mutableStateOf<Int?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(receipt?.id, receipt?.itemCount, pageNumber,
        receiptItemsPage?.page, receiptItemsReceiptId, receiptItemsRequestedPage, receiptItemsLoading) {
        val currentReceipt = receipt ?: return@LaunchedEffect
        val pageLoaded = receiptItemsReceiptId == currentReceipt.id && receiptItemsPage?.page == pageNumber
        val embeddedFirstPageAvailable = pageNumber == 1 && currentReceipt.items.size >= minOf(8, currentReceipt.itemCount)
        val requestInProgress = receiptItemsLoading && receiptItemsReceiptId == currentReceipt.id &&
            receiptItemsRequestedPage == pageNumber
        if (!pageLoaded && !embeddedFirstPageAvailable && !requestInProgress && lastRequestedPage != pageNumber) {
            lastRequestedPage = pageNumber
            onLoadItemsPage(currentReceipt.id, pageNumber)
        }
    }
    androidx.compose.runtime.LaunchedEffect(receipt?.id, receipt?.state, receipt?.itemCount,
        disputedPageNumber, disputedItemsPage?.page, disputedItemsReceiptId,
        disputedItemsRequestedPage, disputedItemsLoading) {
        val currentReceipt = receipt ?: return@LaunchedEffect
        if (currentReceipt.state != "review_required" || currentReceipt.itemCount <= 0) return@LaunchedEffect
        val pageLoaded = disputedItemsReceiptId == currentReceipt.id &&
            disputedItemsPage?.page == disputedPageNumber
        val requestInProgress = disputedItemsLoading && disputedItemsReceiptId == currentReceipt.id &&
            disputedItemsRequestedPage == disputedPageNumber
        if (!pageLoaded && !requestInProgress && lastRequestedDisputedPage != disputedPageNumber) {
            lastRequestedDisputedPage = disputedPageNumber
            onDisputedItemsPage(currentReceipt.id, disputedPageNumber)
        }
    }
    val currentRepeatWarningsRevision = receipt?.let(::receiptRepeatWarningsRevision)
    var observedRepeatWarningsRevision by androidx.compose.runtime.remember(receipt?.id) {
        androidx.compose.runtime.mutableStateOf(currentRepeatWarningsRevision)
    }
    androidx.compose.runtime.LaunchedEffect(receipt?.id, receipt?.state, receipt?.itemCount,
        currentRepeatWarningsRevision, repeatWarnings, repeatWarningsReceiptId,
        repeatWarningsRevision, repeatWarningsLoading, repeatWarningsError) {
        val currentReceipt = receipt ?: return@LaunchedEffect
        if (currentReceipt.state != "review_required" || currentReceipt.itemCount <= 0) return@LaunchedEffect
        if (observedRepeatWarningsRevision != currentRepeatWarningsRevision) {
            observedRepeatWarningsRevision = currentRepeatWarningsRevision
            onRepeatWarningsRefresh(currentReceipt.id)
            return@LaunchedEffect
        }
        val loadedOrFailed = repeatWarningsReceiptId == currentReceipt.id &&
            (repeatWarnings != null || repeatWarningsError != null) &&
            (repeatWarningsRevision == null || repeatWarningsRevision == currentRepeatWarningsRevision)
        val requestInProgress = repeatWarningsLoading && repeatWarningsReceiptId == currentReceipt.id &&
            repeatWarningsRevision == currentRepeatWarningsRevision
        if (!loadedOrFailed && !requestInProgress) {
            onRepeatWarningsRefresh(currentReceipt.id)
        }
    }
    val russian = language == "ru"
    val activeJob = job?.state in setOf<String?>("queued", "running", "retryable") ||
        (job?.state == "completed" && receipt == null && errorCode != null)
    Card(modifier.fillMaxWidth().testTag("receipt-upload-screen")) {
        Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (russian) "Чеки" else "Receipts", style = MaterialTheme.typography.titleMedium)
            if (canWrite) {
                Text(if (russian) "Добавьте фото чека для распознавания. Операция не будет создана автоматически."
                    else "Add a receipt photo for recognition. No transaction will be created automatically.")
                Button(modifier = Modifier.testTag("receipt-open-document"),
                    enabled = !busy && !checkpointUnresolved && !activeJob, onClick = onPick) {
                    Text(if (russian) "Загрузить фото чека" else "Upload receipt photo")
                }
                if (uploading && job == null) {
                    Card(Modifier.fillMaxWidth().testTag("receipt-upload-progress")) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.CircularProgressIndicator()
                            Text(if (russian) "Загружаем фото…" else "Uploading photo…")
                        }
                    }
                }
                job?.takeIf { it.state == "queued" || it.state == "running" }?.let { current ->
                    val stage = receiptStageLabel(current.stage, language)
                    Card(Modifier.fillMaxWidth().testTag("receipt-upload-progress")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${current.progressPercent}% · $stage")
                            LinearProgressIndicator(progress = { (current.progressPercent.coerceIn(0, 100) / 100f) },
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                errorCode?.let { code ->
                    Text(receiptUploadErrorMessage(code, language), color = MaterialTheme.colorScheme.error)
                    if (canRetryUpload) Button(enabled = !busy, onClick = onRetry) {
                        Text(if (russian) "Повторить загрузку" else "Retry upload")
                    }
                    if (canRetryUpload && job == null && code in LOCAL_RECEIPT_FILE_ERRORS) {
                        TextButton(modifier = Modifier.testTag("receipt-discard-upload"),
                            enabled = !busy, onClick = onDiscard) {
                            Text(if (russian) "Выбрать другое фото" else "Discard and choose another photo")
                        }
                    }
                }
                val canRefreshJob = job?.let {
                    it.state == "queued" || it.state == "running" || it.state == "retryable" ||
                        (it.state == "completed" && errorCode != null) ||
                        (it.state == "rejected" && errorCode == "checkpoint") || errorCode == "timeout"
                } == true
                if (canRefreshJob || (job == null && checkpointUnresolved && !canRetryUpload)) {
                    TextButton(modifier = Modifier.testTag("receipt-refresh-status"),
                        enabled = !busy || job?.state == "retryable", onClick = onRefresh) {
                        Text(if (russian) "Обновить статус" else "Refresh status")
                    }
                }
                receipt?.let { draft ->
                    Card(Modifier.fillMaxWidth().testTag("receipt-draft")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (draft.state == "draft" || draft.state == "review_required")
                                if (russian) "Черновик · проверьте данные" else "Draft · review details"
                            else if (russian) "Чек" else "Receipt", style = MaterialTheme.typography.titleSmall)
                            draft.merchant?.let { Text(it) }
                            draft.receiptDate?.let { Text(it) }
                            draft.cashTotal?.let { Text(formatMoney(it, language, draft.currency)) }
                            Text(if (russian) "Позиций: ${draft.itemCount}" else "Items: ${draft.itemCount}")
                        }
                    }
                }
            } else {
                Text(if (russian) "Загрузка чеков недоступна для просмотра."
                    else "Receipt upload is unavailable in read-only mode.")
                receipt?.let { draft ->
                    Card(Modifier.fillMaxWidth().testTag("receipt-draft")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (russian) "Чек" else "Receipt", style = MaterialTheme.typography.titleSmall)
                            draft.merchant?.let { Text(it) }
                            draft.receiptDate?.let { Text(it) }
                            draft.cashTotal?.let { Text(formatMoney(it, language, draft.currency)) }
                        }
                    }
                }
            }
            receipt?.let { draft ->
                Card(Modifier.fillMaxWidth().testTag("receipt-totals")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${if (russian) "По чеку" else "Cash total"}: ${draft.cashTotal?.let {
                            formatMoney(it, language, draft.currency)
                        } ?: "—"}", modifier = Modifier.testTag("receipt-cash-total"))
                        Text("${if (russian) "По позициям" else "Items total"}: ${draft.itemsTotal?.let {
                            formatMoney(it, language, draft.currency)
                        } ?: "—"}", modifier = Modifier.testTag("receipt-items-total"))
                        if (receiptTotalSyncError != null) {
                            val message = when (receiptTotalSyncError) {
                                "incomplete_items" -> if (russian)
                                    "Итог позиций должен быть полным и больше нуля. Проверьте чек перед синхронизацией."
                                else "Item total must be complete and greater than zero. Check the receipt before syncing."
                                "stale_version" -> if (russian)
                                    "Чек изменился. Обновите чек перед повторной синхронизацией."
                                else "Receipt changed. Refresh it before syncing again."
                                "refresh_unavailable" -> if (russian)
                                    "Не удалось обновить чек. Проверьте связь и повторите попытку."
                                else "Could not refresh the receipt. Check your connection and retry."
                                else -> if (russian) "Не удалось синхронизировать итог чека. Повторите попытку."
                                else "Could not sync the receipt total. Please retry."
                            }
                            Text(message, modifier = Modifier.testTag("receipt-sync-error"),
                                color = MaterialTheme.colorScheme.error)
                            if (receiptTotalSyncError == "stale_version" ||
                                receiptTotalSyncError == "refresh_unavailable") {
                                TextButton(modifier = Modifier.testTag("receipt-sync-refresh"),
                                    enabled = !receiptTotalSyncInProgress, onClick = {
                                        onRefreshTotalSync(draft.id)
                                    }) {
                                    Text(if (russian) "Обновить чек" else "Refresh receipt")
                                }
                            }
                        }
                        val cashValue = draft.cashTotal?.toBigDecimalOrNull()
                        val itemsValue = draft.itemsTotal?.toBigDecimalOrNull()
                        val totalsDiffer = if (cashValue != null && itemsValue != null)
                            cashValue.compareTo(itemsValue) != 0
                        else draft.cashTotal != draft.itemsTotal
                        if (canWrite && draft.transactionId == null && draft.state != "confirmed" && totalsDiffer) {
                            Button(modifier = Modifier.testTag("receipt-sync-total"),
                                enabled = !receiptTotalSyncInProgress && !busy,
                                onClick = { onSyncTotal(draft.id, draft.version) }) {
                                Text(if (russian) "Синхронизировать итог" else "Sync receipt total")
                            }
                        }
                    }
                }
            }
            receipt?.let { draft ->
                val review = duplicateCandidates?.takeIf {
                    it.receiptId == draft.id && duplicateCandidatesReceiptId == draft.id
                }
                val matches = review?.candidates.orEmpty()
                val cash = draft.cashTotal?.toBigDecimalOrNull()
                val items = draft.itemsTotal?.toBigDecimalOrNull()
                val totalsReconciled = cash != null && items != null && cash.compareTo(items) == 0
                val candidatesReady = review != null && !duplicateCandidatesLoading && duplicateCandidatesError == null
                val unresolvedCandidate = matches.isNotEmpty() && draft.duplicateDecision == "unknown"
                val canConfirmReceipt = canWrite && draft.state in setOf("draft", "review_required") &&
                    draft.transactionId == null && totalsReconciled && candidatesReady &&
                    review?.decision == draft.duplicateDecision && !unresolvedCandidate &&
                    draft.duplicateDecision != "duplicate" && !busy && !duplicateDecisionInProgress &&
                    !receiptConfirming

                if (canWrite && draft.state != "confirmed") {
                    Card(Modifier.fillMaxWidth().testTag("receipt-duplicate-review")) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (russian) "Проверка возможного дубля" else "Possible duplicate review",
                                style = MaterialTheme.typography.titleSmall)
                            when {
                                duplicateCandidatesLoading -> Text(if (russian) "Ищем похожие чеки…"
                                    else "Checking for matching receipts…",
                                    modifier = Modifier.testTag("receipt-duplicate-loading"))
                                duplicateCandidatesError != null || review == null -> {
                                    Text(if (russian) "Не удалось проверить похожие чеки. Подтверждение временно недоступно."
                                        else "Could not check for matching receipts. Confirmation is unavailable.",
                                        modifier = Modifier.testTag("receipt-duplicate-error"),
                                        color = MaterialTheme.colorScheme.error)
                                    TextButton(modifier = Modifier.testTag("receipt-duplicate-refresh"),
                                        enabled = !busy && !duplicateCandidatesLoading,
                                        onClick = { onLoadDuplicateCandidates(draft.id) }) {
                                        Text(if (russian) "Повторить проверку" else "Retry check")
                                    }
                                }
                                matches.isEmpty() -> {
                                    Text(if (russian) "Совпадений за последние 10 минут нет."
                                        else "No matches in the last 10 minutes.",
                                        modifier = Modifier.testTag("receipt-duplicate-empty"))
                                    if (draft.duplicateDecision == "duplicate") {
                                        TextButton(modifier = Modifier.testTag("receipt-independent"),
                                            enabled = !busy && !duplicateDecisionInProgress && !receiptConfirming,
                                            onClick = { onDuplicateDecision(draft.id, draft.version,
                                                "independent", null) }) {
                                            Text(if (russian) "Это отдельная покупка" else "This is a separate purchase")
                                        }
                                    }
                                }
                                else -> {
                                    Text(if (russian) "Возможные совпадения за последние 10 минут:"
                                        else "Possible matches from the last 10 minutes:")
                                    matches.forEach { candidate ->
                                        Column(Modifier.fillMaxWidth().testTag("receipt-duplicate-candidate-${candidate.id}")) {
                                            Text("${candidate.merchant ?: if (russian) "Чек" else "Receipt"} · " +
                                                formatMoney(candidate.cashTotal, language, draft.currency))
                                            if (draft.duplicateOfReceiptId == candidate.id &&
                                                draft.duplicateDecision == "duplicate") {
                                                Text(if (russian) "Отмечен как дубль" else "Marked as duplicate")
                                            }
                                            TextButton(
                                                modifier = Modifier.testTag("receipt-duplicate-mark-${candidate.id}"),
                                                enabled = !busy && !duplicateDecisionInProgress && !receiptConfirming,
                                                onClick = { onDuplicateDecision(draft.id, draft.version,
                                                    "duplicate", candidate.id) }) {
                                                Text(if (russian) "Это дубль" else "This is a duplicate")
                                            }
                                        }
                                    }
                                    TextButton(modifier = Modifier.testTag("receipt-independent"),
                                        enabled = !busy && !duplicateDecisionInProgress && !receiptConfirming,
                                        onClick = { onDuplicateDecision(draft.id, draft.version,
                                            "independent", null) }) {
                                        Text(if (russian) "Это отдельная покупка" else "This is a separate purchase")
                                    }
                                }
                            }
                            if (review != null && !duplicateCandidatesLoading && duplicateCandidatesError == null) {
                                val decisionText = when (draft.duplicateDecision) {
                                    "independent" -> if (russian) "Решение: отдельная покупка"
                                        else "Decision: separate purchase"
                                    "duplicate" -> if (russian) "Решение: дубль — расход не будет создан"
                                        else "Decision: duplicate — no expense will be created"
                                    else -> if (russian) "Решение не выбрано"
                                        else "No decision selected"
                                }
                                Text(decisionText, modifier = Modifier.testTag("receipt-duplicate-decision"))
                            }
                            duplicateDecisionError?.let { code ->
                                Text(if (russian) when (code) {
                                    "candidate_conflict" -> "Список возможных дублей изменился. Проверьте его и выберите решение снова."
                                    "stale_version" -> "Чек изменился. Данные обновлены; проверьте решение ещё раз."
                                    "refresh_unavailable" -> "Не удалось обновить чек. Повторите проверку."
                                    else -> "Не удалось сохранить решение. Повторите попытку."
                                } else when (code) {
                                    "candidate_conflict" -> "Possible matches changed. Review them and choose your decision again."
                                    "stale_version" -> "Receipt changed. Details refreshed; review your decision again."
                                    "refresh_unavailable" -> "Could not refresh the receipt. Retry the check."
                                    else -> "Could not save the decision. Please retry."
                                }, modifier = Modifier.testTag("receipt-duplicate-decision-error"),
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                if (canReviewBasket && draft.transactionId == null &&
                    draft.state in setOf("draft", "review_required") && draft.itemCount > 0) {
                    basketReviewError?.let { code ->
                        val message = when (code) {
                            "stale_version" -> if (russian) "Чек обновлён. Проверьте позиции перед повторной проверкой корзины."
                                else "Receipt refreshed. Review its items before checking the basket again."
                            "conflict" -> if (russian) "Состояние чека изменилось. Проверьте его перед повтором."
                                else "Receipt state changed. Review it before retrying."
                            "refresh_unavailable" -> if (russian) "Не удалось обновить чек после изменения. Повторите позже."
                                else "Could not refresh the changed receipt. Please retry later."
                            else -> if (russian) "Не удалось проверить корзину. Повторите попытку."
                                else "Could not review the basket. Please retry."
                        }
                        Text(message, modifier = Modifier.testTag("receipt-basket-review-error"),
                            color = MaterialTheme.colorScheme.error)
                    }
                    Button(modifier = Modifier.fillMaxWidth().testTag("receipt-basket-review"),
                        enabled = !busy && !basketReviewing && !receiptConfirming && !duplicateDecisionInProgress &&
                            !categorySaving && !receiptTotalSyncInProgress,
                        onClick = { onReviewBasket(draft.id, draft.version) }) {
                        Text(if (basketReviewing) {
                            if (russian) "Проверяем корзину…" else "Reviewing basket…"
                        } else if (russian) "Проверить корзину" else "Review basket")
                    }
                }

                if (canWrite && draft.state == "confirmed") {
                    Text(if (russian) "Расход подтверждён" else "Expense confirmed",
                        modifier = Modifier.fillMaxWidth().testTag("receipt-confirmed"))
                    draft.transactionId?.let { id ->
                        Text(id, modifier = Modifier.testTag("receipt-transaction-id"))
                    }
                } else if (canWrite && draft.state in setOf("draft", "review_required")) {
                    receiptConfirmError?.let { code ->
                        Text(if (russian) when (code) {
                            "candidate_conflict" -> "Список возможных дублей изменился. Проверьте чек и подтвердите расход снова."
                            "stale_version" -> "Чек изменился. Данные обновлены; проверьте его перед повтором."
                            "refresh_unavailable" -> "Не удалось обновить чек. Повторите проверку перед подтверждением."
                            else -> "Не удалось подтвердить расход. Повторите попытку."
                        } else when (code) {
                            "candidate_conflict" -> "Possible matches changed. Review the receipt and confirm the expense again."
                            "stale_version" -> "Receipt changed. Details refreshed; review it before retrying."
                            "refresh_unavailable" -> "Could not refresh the receipt. Retry the check before confirming."
                            else -> "Could not confirm the expense. Please retry."
                        }, modifier = Modifier.testTag("receipt-confirm-error"),
                            color = MaterialTheme.colorScheme.error)
                    }
                    Button(modifier = Modifier.testTag(if (receiptConfirmError == null)
                        "receipt-confirm" else "receipt-confirm-retry"),
                        enabled = canConfirmReceipt && receiptConfirmError != "refresh_unavailable",
                        onClick = { onConfirmReceipt(draft.id, draft.version, confirmationIdempotencyKey) }) {
                        Text(if (receiptConfirmError != null) {
                            if (russian) "Повторить подтверждение" else "Retry confirmation"
                        } else if (russian) "Подтвердить расход" else "Confirm expense")
                    }
                }
            }
            receipt?.let { draft ->
                val addAllowed = canWrite && draft.transactionId == null &&
                    draft.state in setOf("draft", "review_required")
                if (addAllowed) {
                    if (draft.itemCount >= 200) {
                        Text(if (russian) "Нельзя добавить больше 200 позиций в чек."
                            else "A receipt cannot have more than 200 items.",
                            modifier = Modifier.testTag("receipt-item-add-limit"),
                            color = MaterialTheme.colorScheme.error)
                    } else {
                        TextButton(modifier = Modifier.testTag("receipt-item-add-open"),
                            enabled = !busy, onClick = { addingItem = true }) {
                            Text(if (russian) "Добавить позицию" else "Add item")
                        }
                    }
                    if (addingItem && draft.itemCount < 200) {
                        Column(Modifier.fillMaxWidth().testTag("receipt-item-add-form"),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(addName, { addName = it }, modifier = Modifier.fillMaxWidth()
                                .testTag("receipt-item-add-name"),
                                label = { Text(if (russian) "Название" else "Name") }, singleLine = true)
                            OutlinedTextField(addQuantity, { addQuantity = it }, modifier = Modifier.fillMaxWidth()
                                .testTag("receipt-item-add-quantity"),
                                label = { Text(if (russian) "Количество" else "Quantity") }, singleLine = true)
                            OutlinedTextField(addUnitPrice, { addUnitPrice = it }, modifier = Modifier.fillMaxWidth()
                                .testTag("receipt-item-add-unit-price"),
                                label = { Text(if (russian) "Цена за единицу" else "Unit price") }, singleLine = true)
                            OutlinedTextField(addLineSum, { addLineSum = it }, modifier = Modifier.fillMaxWidth()
                                .testTag("receipt-item-add-line-sum"),
                                label = { Text(if (russian) "Сумма позиции" else "Line total") }, singleLine = true)
                            if (receiptItemAddError != null) {
                                val message = when (receiptItemAddError) {
                                    "item_limit" -> if (russian) "Нельзя добавить больше 200 позиций в чек." else "A receipt cannot have more than 200 items."
                                    "invalid_item" -> if (russian) "Проверьте название и суммы позиции." else "Check the item name and amounts."
                                    "conflict" -> if (russian) "Чек изменился. Обновите его перед повторной отправкой." else "The receipt changed. Refresh it before submitting again."
                                    "stale_version" -> if (russian) "Чек изменился. Значения формы сохранены. Обновите чек." else "The receipt changed. Form values are preserved. Refresh the receipt."
                                    "refresh_unavailable" -> if (russian) "Не удалось проверить чек. Повторите обновление." else "Could not verify the receipt. Retry refresh."
                                    "ambiguous" -> if (russian) "Изменения не удалось подтвердить. Проверьте позиции перед повторной отправкой." else "The result is unclear. Check the items before submitting again."
                                    else -> if (russian) "Не удалось проверить отправку. Обновите чек перед повтором." else "Submission could not be confirmed. Refresh the receipt before retrying."
                                }
                                Text(message, modifier = Modifier.testTag("receipt-item-add-error"),
                                    color = MaterialTheme.colorScheme.error)
                                if (receiptItemAddNeedsRefresh) {
                                    TextButton(modifier = Modifier.testTag("receipt-item-add-refresh"),
                                        enabled = !busy, onClick = { onRefreshAddItem(draft.id) }) {
                                        Text(if (russian) "Обновить чек" else "Refresh receipt")
                                    }
                                }
                                if (receiptItemAddError == "ambiguous" && receiptItemAddNeedsRefresh) {
                                    TextButton(modifier = Modifier.testTag("receipt-item-add-retry-confirm"),
                                        enabled = !busy && addName.isNotBlank(), onClick = {
                                            onAddItem(draft.id, draft.version, addName, addQuantity, addUnitPrice, addLineSum)
                                        }) {
                                        Text(if (russian) "Проверил: позиции нет, повторить" else "I checked: item is missing, retry")
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(modifier = Modifier.testTag("receipt-item-add-save"),
                                    enabled = !busy && !receiptItemAddNeedsRefresh && addName.isNotBlank(), onClick = {
                                        onAddItem(draft.id, draft.version, addName, addQuantity, addUnitPrice, addLineSum)
                                    }) {
                                    Text(if (russian) "Сохранить" else "Save")
                                }
                                TextButton(modifier = Modifier.testTag("receipt-item-add-cancel"), enabled = !busy,
                                    onClick = {
                                        addingItem = false
                                        addName = ""
                                        addQuantity = "1"
                                        addUnitPrice = ""
                                        addLineSum = ""
                                    }) {
                                    Text(if (russian) "Отмена" else "Cancel")
                                }
                            }
                        }
                    }
                }
            }
            receipt?.let { draft ->
                val currentItemsPage = receiptItemsPage?.takeIf {
                    receiptItemsReceiptId == draft.id && it.page == pageNumber
                } ?: if (pageNumber == 1 && draft.items.size >= minOf(8, draft.itemCount)) {
                    FinanceReceiptItemPage(draft.items.take(8), 1, draft.itemCount, draft.itemCount > 8)
                } else null
                if (draft.itemCount > 0 || currentItemsPage != null) {
                    Card(Modifier.fillMaxWidth().testTag("receipt-items")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (russian) "Позиции чека" else "Receipt items",
                                style = MaterialTheme.typography.titleSmall)
                            Text(if (russian) "Страница $pageNumber" else "Page $pageNumber",
                                modifier = Modifier.testTag("receipt-items-page"))
                            currentItemsPage?.items?.forEach { item ->
                                val quantity = item.quantity?.let { "$it × " }.orEmpty()
                                val unitPrice = item.unitPrice?.let { "$it · " }.orEmpty()
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text("${item.name} · $quantity$unitPrice${item.lineSum ?: "—"} ₽",
                                        modifier = Modifier.weight(1f).testTag("receipt-item-${item.id}"))
                                    if (canWrite) TextButton(
                                        modifier = Modifier.testTag("receipt-item-edit-${item.id}"),
                                        enabled = !busy, onClick = {
                                            editingItemId = item.id
                                            editName = item.name
                                            editQuantity = item.quantity.orEmpty()
                                            editUnitPrice = item.unitPrice.orEmpty()
                                            editLineSum = item.lineSum.orEmpty()
                                        }) {
                                        Text(if (russian) "Изменить" else "Edit")
                                    }
                                }
                                if (draft.state == "confirmed") {
                                    val numericPattern = Regex("^\\d+(?:\\.\\d+)?$")
                                    val usableQuantity = item.quantity?.takeIf(numericPattern::matches)
                                        ?.toBigDecimalOrNull()?.signum()?.let { it > 0 } == true
                                    val usableLineSum = item.lineSum?.takeIf(numericPattern::matches)
                                        ?.toBigDecimalOrNull()?.signum()?.let { it > 0 } == true
                                    if (!usableQuantity || !usableLineSum) {
                                        Text(if (russian) "Для сравнения нужны корректные количество и сумма позиции."
                                            else "A valid item quantity and total are required for comparison.",
                                            modifier = Modifier.testTag("receipt-price-inputs-${item.id}"))
                                    } else {
                                        TextButton(modifier = Modifier.testTag("receipt-price-compare-${item.id}"),
                                            enabled = !productPriceComparisonLoading,
                                            onClick = { onPriceComparison(draft.tenantId, draft.id, item.id) }) {
                                            Text(if (russian) "Сравнить цену" else "Compare price")
                                        }
                                        val comparisonMatches = productPriceComparisonReceiptId == draft.id &&
                                            productPriceComparisonItemId == item.id
                                        if (comparisonMatches && productPriceComparisonLoading) {
                                            Text(if (russian) "Загрузка сравнения…" else "Loading comparison…",
                                                modifier = Modifier.testTag("receipt-price-loading-${item.id}"))
                                        }
                                        if (comparisonMatches && productPriceComparisonError != null) {
                                            Column {
                                                Text(if (russian) "Не удалось загрузить сравнение цены."
                                                    else "Could not load the price comparison.",
                                                    modifier = Modifier.testTag("receipt-price-error-${item.id}"))
                                                TextButton(modifier = Modifier.testTag("receipt-price-retry-${item.id}"),
                                                    onClick = {
                                                    onPriceComparison(draft.tenantId, draft.id, item.id)
                                                }) { Text(if (russian) "Повторить" else "Retry") }
                                            }
                                        }
                                        val comparison = productPriceComparison?.takeIf {
                                            comparisonMatches && !productPriceComparisonLoading
                                        }
                                        if (comparison != null) {
                                            Column(Modifier.testTag("receipt-price-result-${item.id}"),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text("${if (russian) "Сейчас" else "Current"}: " +
                                                    "${formatMoney(comparison.currentUnitPrice, language, draft.currency)}/" +
                                                    (if (russian) "ед." else "unit"))
                                                if (comparison.hasBaseline) {
                                                    val baseline = requireNotNull(comparison.baselineUnitPrice)
                                                    Text("${if (russian) "Обычно" else "Typical"}: " +
                                                        "${formatMoney(baseline, language, draft.currency)}/" +
                                                        (if (russian) "ед." else "unit") + " · " +
                                                        "${comparison.priorPurchases} " +
                                                        (if (russian) "покупок раньше" else "prior purchases"))
                                                    val change = requireNotNull(comparison.change)
                                                    val relative = requireNotNull(comparison.relative).toBigDecimal()
                                                        .movePointRight(2).stripTrailingZeros().toPlainString()
                                                        .let { if (russian) it.replace('.', ',') else it }
                                                    val movement = when {
                                                        !comparison.signal -> null
                                                        comparison.direction == "up" -> if (russian) "Подорожание" else "Price increased"
                                                        comparison.direction == "down" -> if (russian) "Снижение цены" else "Price decreased"
                                                        else -> null
                                                    }
                                                    Text("${if (russian) "Изменение" else "Change"}: " +
                                                        "${if (change.startsWith("-") || change == "0.000000") "" else "+"}" +
                                                        "${formatMoney(change, language, draft.currency)}/" +
                                                        (if (russian) "ед." else "unit") + " · $relative%" +
                                                        movement?.let { " · $it" }.orEmpty())
                                                } else {
                                                    Text(if (russian) "Пока нет сопоставимых покупок."
                                                        else "No comparable purchases yet.",
                                                        modifier = Modifier.testTag("receipt-price-no-baseline-${item.id}"))
                                                }
                                            }
                                        }
                                    }
                                }
                                val hasReview = item.verdict != null || item.reviewReason != null ||
                                    item.reviewAction != null || item.advice != null ||
                                    item.verdictSource in setOf("rule", "model", "default")
                                if (hasReview) {
                                    val verdictLabel = when (item.verdict) {
                                        "harmful" -> if (russian) "неблагоприятная" else "harmful"
                                        "unnecessary" -> if (russian) "необязательная" else "unnecessary"
                                        "useful" -> if (russian) "полезная" else "useful"
                                        "neutral" -> if (russian) "нейтральная" else "neutral"
                                        null -> if (russian) "не указана" else "not provided"
                                        else -> if (russian) "неизвестная" else "unknown"
                                    }
                                    val sourceLabel = when (item.verdictSource) {
                                        "rule" -> if (russian) "правило" else "rule"
                                        "model" -> if (russian) "модель" else "model"
                                        "default" -> if (russian) "по умолчанию" else "default"
                                        "unknown" -> if (russian) "неизвестен" else "unknown"
                                        "human" -> if (russian) "человек" else "human"
                                        null -> if (russian) "не указан" else "not provided"
                                        else -> if (russian) "неизвестен" else "unknown"
                                    }
                                    val verdictText = verdictLabel
                                    Column(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 6.dp)
                                        .testTag("receipt-item-review-${item.id}"),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("${if (russian) "Оценка" else "Verdict"}: $verdictText",
                                            modifier = Modifier.testTag("receipt-item-review-verdict-${item.id}"))
                                        Text("${if (russian) "Причина" else "Reason"}: " +
                                            (item.reviewReason ?: if (russian) "не указана" else "not provided"),
                                            modifier = Modifier.testTag("receipt-item-review-reason-${item.id}"))
                                        Text("${if (russian) "Действие" else "Action"}: " +
                                            (item.reviewAction ?: if (russian) "не указано" else "not provided"),
                                            modifier = Modifier.testTag("receipt-item-review-action-${item.id}"))
                                        Text("${if (russian) "Совет" else "Advice"}: " +
                                            (item.advice ?: if (russian) "не указан" else "not provided"),
                                            modifier = Modifier.testTag("receipt-item-review-advice-${item.id}"))
                                        val provider = item.reviewProvider ?: if (russian) "не указан" else "not provided"
                                        val model = item.reviewModelVersion ?: if (russian) "не указана" else "not provided"
                                        val prompt = item.reviewPromptVersion ?: if (russian) "не указана" else "not provided"
                                        val algorithm = item.reviewAlgorithmVersion ?: if (russian) "не указан" else "not provided"
                                        Text("${if (russian) "Источник" else "Source"}: $sourceLabel · " +
                                            "${if (russian) "провайдер" else "provider"} $provider · " +
                                            "${if (russian) "модель" else "model"} $model · " +
                                            "${if (russian) "промпт" else "prompt"} $prompt · " +
                                            "${if (russian) "алгоритм" else "algorithm"} $algorithm",
                                            modifier = Modifier.testTag("receipt-item-review-source-${item.id}"))
                                    }
                                }
                                if (canWrite && editingItemId == item.id) {
                                    Column(Modifier.fillMaxWidth().padding(top = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedTextField(editName, { editName = it }, modifier = Modifier
                                            .fillMaxWidth().testTag("receipt-item-edit-name"),
                                            label = { Text(if (russian) "Название" else "Name") }, singleLine = true)
                                        OutlinedTextField(editQuantity, { editQuantity = it }, modifier = Modifier
                                            .fillMaxWidth().testTag("receipt-item-edit-quantity"),
                                            label = { Text(if (russian) "Количество" else "Quantity") }, singleLine = true)
                                        OutlinedTextField(editUnitPrice, { editUnitPrice = it }, modifier = Modifier
                                            .fillMaxWidth().testTag("receipt-item-edit-unit-price"),
                                            label = { Text(if (russian) "Цена за единицу" else "Unit price") }, singleLine = true)
                                        OutlinedTextField(editLineSum, { editLineSum = it }, modifier = Modifier
                                            .fillMaxWidth().testTag("receipt-item-edit-line-sum"),
                                            label = { Text(if (russian) "Сумма позиции" else "Line total") }, singleLine = true)
                                        if (receiptItemEditError != null) {
                                            val message = when (receiptItemEditError) {
                                                "stale_version" -> if (russian)
                                                    "Чек изменился. Ваши значения сохранены в форме. Обновите чек перед повторным сохранением."
                                                else "The receipt changed. Your edits are preserved in the form. Refresh the receipt before saving again."
                                                "refresh_unavailable" -> if (russian)
                                                    "Не удалось обновить чек. Проверьте связь и повторите попытку."
                                                else "Could not refresh the receipt. Check your connection and retry."
                                                else -> if (russian) "Не удалось сохранить позицию. Проверьте связь и повторите попытку."
                                                else "Could not save this item. Check your connection and retry."
                                            }
                                            Text(message, modifier = Modifier.testTag("receipt-item-edit-error"),
                                                color = MaterialTheme.colorScheme.error)
                                            if (receiptItemEditError == "stale_version" ||
                                                receiptItemEditError == "refresh_unavailable") {
                                                TextButton(modifier = Modifier.testTag("receipt-item-edit-refresh"),
                                                    enabled = !busy, onClick = { onRefreshItem(draft.id) }) {
                                                    Text(if (russian) "Обновить чек" else "Refresh receipt")
                                                }
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(modifier = Modifier.testTag("receipt-item-edit-save"),
                                                enabled = !busy && editName.isNotBlank(), onClick = {
                                                    onUpdateItem(draft.id, item.id, draft.version, editName,
                                                        editQuantity, editUnitPrice, editLineSum)
                                                }) {
                                                Text(if (russian) "Сохранить" else "Save")
                                            }
                                            TextButton(enabled = !busy, onClick = { editingItemId = null }) {
                                                Text(if (russian) "Отмена" else "Cancel")
                                            }
                                        }
                                    }
                                }
                            }
                            if (receiptItemsLoading && (receiptItemsReceiptId == null || receiptItemsReceiptId == draft.id) &&
                                (receiptItemsRequestedPage == null || receiptItemsRequestedPage == pageNumber)) {
                                Text(if (russian) "Загружаем позиции…" else "Loading items…",
                                    modifier = Modifier.testTag("receipt-items-loading"))
                            }
                            if (receiptItemsError != null &&
                                (receiptItemsReceiptId == null || receiptItemsReceiptId == draft.id) &&
                                (receiptItemsRequestedPage == null || receiptItemsRequestedPage == pageNumber)) {
                                Text(if (russian) "Не удалось загрузить позиции чека."
                                    else "Could not load receipt items.",
                                    modifier = Modifier.testTag("receipt-items-error"),
                                    color = MaterialTheme.colorScheme.error)
                                TextButton(modifier = Modifier.testTag("receipt-items-retry"),
                                    enabled = !receiptItemsLoading, onClick = {
                                        // Keep the page marked as requested: this click already retries it.
                                        // Clearing the guard lets LaunchedEffect issue a hidden duplicate.
                                        lastRequestedPage = pageNumber
                                        onLoadItemsPage(draft.id, pageNumber)
                                    }) {
                                    Text(if (russian) "Повторить" else "Retry")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(modifier = Modifier.testTag("receipt-items-previous"),
                                    enabled = pageNumber > 1 && !receiptItemsLoading, onClick = {
                                        val previous = (pageNumber - 1).coerceAtLeast(1)
                                        pageNumber = previous
                                        lastRequestedPage = previous
                                        onLoadItemsPage(draft.id, previous)
                                    }) {
                                    Text(if (russian) "Назад" else "Previous")
                                }
                                TextButton(modifier = Modifier.testTag("receipt-items-next"),
                                    enabled = currentItemsPage?.hasMore == true && !receiptItemsLoading,
                                    onClick = {
                                        val next = pageNumber + 1
                                        pageNumber = next
                                        lastRequestedPage = next
                                        onLoadItemsPage(draft.id, next)
                                    }) {
                                    Text(if (russian) "Далее" else "Next")
                                }
                            }
                        }
                    }
                }
                if (draft.state == "review_required" && draft.itemCount > 0) {
                    ReceiptDisputedItemsSection(
                        receiptId = draft.id,
                        language = language,
                        currency = draft.currency,
                        canManage = canReviewBasket,
                        pageNumber = disputedPageNumber,
                        page = disputedItemsPage,
                        pageReceiptId = disputedItemsReceiptId,
                        requestedPage = disputedItemsRequestedPage,
                        loading = disputedItemsLoading,
                        error = disputedItemsError,
                        decisions = productDecisions,
                        productNames = (draft.items + receiptItemsPage
                            ?.takeIf { receiptItemsReceiptId == draft.id }?.items.orEmpty())
                            .filter { it.productKey != null }.associate { it.productKey!! to it.name },
                        onPageChange = { page ->
                            disputedPageNumber = page
                            lastRequestedDisputedPage = page
                            onDisputedItemsPage(draft.id, page)
                        },
                        onDecision = onDisputedProductDecision,
                    )
                    ReceiptRepeatWarningsSection(
                        receipt = draft,
                        language = language,
                        warnings = repeatWarnings?.takeIf { repeatWarningsReceiptId == draft.id &&
                            (repeatWarningsRevision == null || repeatWarningsRevision == currentRepeatWarningsRevision) },
                        loading = repeatWarningsLoading && repeatWarningsReceiptId == draft.id &&
                            repeatWarningsRevision == currentRepeatWarningsRevision,
                        error = repeatWarningsError.takeIf { repeatWarningsReceiptId == draft.id &&
                            (repeatWarningsRevision == null || repeatWarningsRevision == currentRepeatWarningsRevision) },
                        onRetry = { onRepeatWarningsRefresh(draft.id) },
                    )
                }
                val currentReading = reading.takeIf { readingReceiptId == null || readingReceiptId == draft.id }
                val sourceLabel = when (draft.categorySource) {
                    "human" -> if (russian) "вручную" else "manual"
                    "rule" -> if (russian) "правило" else "rule"
                    "model" -> if (russian) "модель" else "model"
                    "default" -> if (russian) "по умолчанию" else "default"
                    else -> if (russian) "не определён" else "unknown"
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val categoryLabel = draft.categoryCode?.let { receiptCategoryLabel(it, language) }
                    if (draft.categoryCode == null || draft.categorySource == "unknown") {
                        Text(if (russian) "Категория не определена"
                            else "Category unknown", modifier = Modifier.testTag("receipt-reading-category-unknown"))
                    } else {
                        Text(if (russian) "Категория: $categoryLabel (${draft.categoryCode})"
                            else "Category: $categoryLabel (${draft.categoryCode})",
                            modifier = Modifier.testTag("receipt-category-value"))
                    }
                    Text(if (russian) "Источник категории: $sourceLabel · ${draft.categoryAlgorithmVersion}"
                        else "Category source: $sourceLabel · ${draft.categoryAlgorithmVersion}",
                        modifier = Modifier.testTag("receipt-category-source"))
                    draft.alcoholShare?.let { share ->
                        Text(if (russian) "Доля алкоголя: $share" else "Alcohol share: $share",
                            modifier = Modifier.testTag("receipt-alcohol-share"))
                    }
                    draft.leisureShare?.let { share ->
                        Text(if (russian) "Доля досуга: $share" else "Leisure share: $share",
                            modifier = Modifier.testTag("receipt-leisure-share"))
                    }
                    if (draft.leisure) {
                        Text(if (russian) "Отнесено к досуговым расходам"
                            else "Classified as leisure spending",
                            modifier = Modifier.testTag("receipt-leisure-status"))
                    }
                    if (canEditCategory && draft.state in setOf("draft", "review_required")) {
                        Box {
                            TextButton(modifier = Modifier.testTag("receipt-category-select"),
                                enabled = !categorySaving && !busy, onClick = { categoryMenuExpanded = true }) {
                                Text(if (russian) "Изменить категорию" else "Change category")
                            }
                            DropdownMenu(expanded = categoryMenuExpanded,
                                onDismissRequest = { categoryMenuExpanded = false }) {
                                RECEIPT_CATEGORY_CODES.forEach { code ->
                                    DropdownMenuItem(
                                        modifier = Modifier.testTag("receipt-category-option-$code"),
                                        text = { Text(receiptCategoryLabel(code, language)) },
                                        onClick = {
                                            selectedCategory = code
                                            categoryMenuExpanded = false
                                        })
                                }
                            }
                        }
                        Button(modifier = Modifier.testTag("receipt-category-save"),
                            enabled = selectedCategory != null && selectedCategory != draft.categoryCode &&
                                !categorySaving && !busy,
                            onClick = { selectedCategory?.let { onSelectCategory(draft.id, draft.version, it) } }) {
                            Text(if (categorySaving) {
                                if (russian) "Сохраняем…" else "Saving…"
                            } else if (russian) "Сохранить категорию" else "Save category")
                        }
                        categoryError?.let { code ->
                            Text(receiptCategoryErrorMessage(code, language),
                                modifier = Modifier.testTag("receipt-category-error"),
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (draft.documentId != null && draft.selectedReader != "manual") {
                    when {
                        currentReading != null -> {
                            Card(Modifier.fillMaxWidth().testTag("receipt-reading")) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(if (russian) "Исходное чтение" else "Original reading",
                                        style = MaterialTheme.typography.titleSmall)
                                    Text("OCR · ${currentReading.provider ?: "—"} · ${currentReading.modelVersion ?: "—"} · ${currentReading.promptVersion ?: "—"}")
                                    currentReading.confidence?.let { Text(if (russian) "Уверенность чтения: $it" else "Reading confidence: $it") }
                                    currentReading.visionFallbackReason?.let {
                                        Text(if (russian) "Резерв Vision: ${localizedReceiptFallbackReason(it, "vision", language)}"
                                            else "Vision fallback: ${localizedReceiptFallbackReason(it, "vision", language)}")
                                    }
                                    currentReading.ocrFallbackReason?.let {
                                        Text(if (russian) "Резерв OCR: ${localizedReceiptFallbackReason(it, "ocr", language)}"
                                            else "OCR fallback: ${localizedReceiptFallbackReason(it, "ocr", language)}")
                                    }
                                    Text(currentReading.text.ifBlank { "—" }, modifier = Modifier
                                        .fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                                        .testTag("receipt-reading-ocr-text"))
                                    if (currentReading.words.isNotEmpty()) {
                                        Column(Modifier.fillMaxWidth().heightIn(max = 160.dp)
                                            .verticalScroll(rememberScrollState())) {
                                            currentReading.words.forEach { word ->
                                                val box = word["box"] as? Map<*, *>
                                                val text = word["text"]?.toString().orEmpty()
                                                val confidence = word["confidence"]?.toString()
                                                val coordinates = box?.let {
                                                    "${it["x"]}, ${it["y"]}, ${it["width"]}, ${it["height"]}"
                                                }
                                                if (text.isNotBlank()) Text("$text · ${confidence ?: "—"}% · (${coordinates ?: "—"})")
                                            }
                                        }
                                    }
                                    currentReading.vision?.let { vision ->
                                        Text(if (russian) "Непроверенное чтение Vision" else "Unverified Vision reading",
                                            style = MaterialTheme.typography.titleSmall)
                                        Text(if (russian) "Эти значения не подтверждены и не заменяют данные чека."
                                            else "These values are unverified and do not replace receipt fields.")
                                        Text(if (russian) "Магазин в чтении" else "Vision store")
                                        Text(vision.store ?: "—", modifier = Modifier.testTag("receipt-reading-vision-store"))
                                        Text(if (russian) "Дата в чтении" else "Vision date")
                                        Text(vision.date ?: "—", modifier = Modifier.testTag("receipt-reading-vision-date"))
                                        Text(if (russian) "Сумма в чтении" else "Vision total")
                                        Text(vision.total ?: "—", modifier = Modifier.testTag("receipt-reading-vision-total"))
                                        Text(if (russian) "Источник Vision: ${vision.provider ?: "—"} · ${vision.modelVersion ?: "—"} · ${vision.promptVersion ?: "—"}"
                                            else "Vision source: ${vision.provider ?: "—"} · ${vision.modelVersion ?: "—"} · ${vision.promptVersion ?: "—"}")
                                        vision.fallbackReason?.let {
                                            Text(if (russian) "Резерв модели Vision: ${localizedReceiptFallbackReason(it, "vision_model", language)}"
                                                else "Vision model fallback: ${localizedReceiptFallbackReason(it, "vision_model", language)}")
                                        }
                                    }
                                    val reconciliation = currentReading.reconciliation
                                    val reconciliationDecision = when (reconciliation.decision.lowercase()) {
                                        "auto_selected" -> if (russian) "чтения согласованы" else "readings agree"
                                        "review_required" -> if (russian) "нужно проверить расхождения" else "review differences"
                                        "insufficient_data" -> if (russian) "недостаточно данных" else "not enough data"
                                        else -> if (russian) "неизвестный результат" else "unknown result"
                                    }
                                    val selectedReader = when (reconciliation.selectedReader?.lowercase()) {
                                        "ocr" -> "OCR"
                                        "vision" -> "Vision"
                                        else -> if (russian) "не выбран" else "none selected"
                                    }
                                    val mismatchLabels = reconciliation.mismatchFields.map { field ->
                                        when (field.lowercase()) {
                                            "total" -> if (russian) "итог" else "total"
                                            "merchant" -> if (russian) "магазин" else "merchant"
                                            "date" -> if (russian) "дата" else "date"
                                            "items" -> if (russian) "позиции" else "items"
                                            else -> if (russian) "другое расхождение" else "other difference"
                                        }
                                    }
                                    Column(Modifier.fillMaxWidth().testTag("receipt-reading-reconciliation"),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(if (russian) "Сверка: $reconciliationDecision"
                                            else "Reconciliation: $reconciliationDecision",
                                            style = MaterialTheme.typography.titleSmall)
                                        Text(if (russian) "Источник расчёта: $selectedReader"
                                            else "Selected reading: $selectedReader")
                                        Text(if (russian) "Версия сверки: ${reconciliation.algorithmVersion}"
                                            else "Reconciliation version: ${reconciliation.algorithmVersion}")
                                        if (mismatchLabels.isNotEmpty()) {
                                            Text(if (russian) "Расхождения: ${mismatchLabels.joinToString(", ")}"
                                                else "Differences: ${mismatchLabels.joinToString(", ")}")
                                        }
                                        Text(if (russian) "Позиции OCR: ${formatMoney(reconciliation.ocrItemsTotal, language, draft.currency)}"
                                            else "OCR items total: ${formatMoney(reconciliation.ocrItemsTotal, language, draft.currency)}")
                                        Text(if (russian) "Позиции Vision: ${formatMoney(reconciliation.visionItemsTotal, language, draft.currency)}"
                                            else "Vision items total: ${formatMoney(reconciliation.visionItemsTotal, language, draft.currency)}")
                                        Text(if (russian) "Допустимая разница: ${formatMoney(reconciliation.allowedDifference, language, draft.currency)}"
                                            else "Allowed difference: ${formatMoney(reconciliation.allowedDifference, language, draft.currency)}")
                                        if (reconciliation.itemEvidence.isNotEmpty()) {
                                            Text(if (russian) "Сопоставление позиций" else "Item matching",
                                                style = MaterialTheme.typography.titleSmall)
                                            reconciliation.itemEvidence.forEach { evidence ->
                                                val status = when (evidence.status.lowercase()) {
                                                    "corroborated" -> if (russian) "суммы совпали" else "amounts agree"
                                                    "amount_disagrees" -> if (russian) "суммы расходятся" else "amounts differ"
                                                    "amount_unknown" -> if (russian) "сумма неизвестна" else "amount unknown"
                                                    "reader_only" -> if (russian) "есть только у одного читателя"
                                                        else "found by one reader only"
                                                    else -> if (russian) "неизвестно" else "unknown"
                                                }
                                                Text("Vision #${evidence.visionOrdinal} ↔ OCR #${evidence.ocrOrdinal ?: "—"}: $status",
                                                    modifier = Modifier.testTag("receipt-reading-item-evidence-${evidence.visionOrdinal}"))
                                            }
                                        }
                                        if (reconciliation.suggestedTopUps.isNotEmpty()) {
                                            Text(if (russian) "Подсказки добора из OCR" else "OCR items to review",
                                                style = MaterialTheme.typography.titleSmall)
                                            reconciliation.suggestedTopUps.forEach { suggestion ->
                                                Text("OCR #${suggestion.ocrOrdinal}: ${suggestion.name} · ${formatMoney(suggestion.lineSum, language, draft.currency)}",
                                                    modifier = Modifier.testTag("receipt-reading-top-up-${suggestion.ocrOrdinal}"))
                                            }
                                            Text(if (russian) "Предложение только для проверки; чек не изменён."
                                                else "Suggestion for review only; receipt unchanged.")
                                        }
                                    }
                                }
                            }
                        }
                                readingLoading && (readingReceiptId == null || readingReceiptId == draft.id) -> {
                            Text(if (russian) "Загружаем исходное чтение…" else "Loading original reading…",
                                modifier = Modifier.testTag("receipt-reading-loading"))
                        }
                        readingError != null && (readingReceiptId == null || readingReceiptId == draft.id) -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (russian) "Исходное чтение недоступно. Данные чека остались без изменений."
                                    else "Original reading is unavailable. Receipt fields were left unchanged.",
                                    modifier = Modifier.testTag("receipt-reading-unavailable"),
                                    color = MaterialTheme.colorScheme.error)
                                TextButton(modifier = Modifier.testTag("receipt-reading-retry"),
                                    enabled = !readingLoading, onClick = { onLoadReading(draft.id) }) {
                                    Text(if (russian) "Повторить загрузку чтения" else "Retry loading reading")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ReceiptDisputedItemsSection(
    receiptId: String,
    language: String,
    currency: String,
    canManage: Boolean,
    pageNumber: Int,
    page: FinanceReceiptItemPage?,
    pageReceiptId: String?,
    requestedPage: Int?,
    loading: Boolean,
    error: String?,
    decisions: FinanceProductDecisions?,
    productNames: Map<String, String>,
    onPageChange: (Int) -> Unit,
    onDecision: (String, String) -> Unit,
) {
    val russian = language == "ru"
    val currentPage = page?.takeIf { pageReceiptId == receiptId }
    val visiblePage = currentPage?.page ?: pageNumber
    val (sortedAllowedKeys, allowedKeys) = androidx.compose.runtime.remember(decisions) {
        val sorted = decisions?.productKeys.orEmpty().sorted()
        sorted to sorted.toHashSet()
    }
    val allowedPageSize = 20
    var allowedPageNumber by androidx.compose.runtime.remember(receiptId, decisions) {
        androidx.compose.runtime.mutableIntStateOf(1)
    }
    val allowedPageCount = ((sortedAllowedKeys.size + allowedPageSize - 1) / allowedPageSize).coerceAtLeast(1)
    val boundedAllowedPage = allowedPageNumber.coerceIn(1, allowedPageCount)
    val allowedPageStart = (boundedAllowedPage - 1) * allowedPageSize
    val visibleAllowedKeys = sortedAllowedKeys.subList(
        allowedPageStart, minOf(allowedPageStart + allowedPageSize, sortedAllowedKeys.size))
    val errorForReceipt = error != null && (pageReceiptId == null || pageReceiptId == receiptId)
    Card(Modifier.fillMaxWidth().testTag("receipt-disputed-items")) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (russian) "Спорные товары" else "Disputed items",
                style = MaterialTheme.typography.titleSmall)
            if (loading && (pageReceiptId == null || pageReceiptId == receiptId) &&
                (requestedPage == null || requestedPage == pageNumber)) {
                Text(if (russian) "Загружаем спорные товары…" else "Loading disputed items…",
                    modifier = Modifier.testTag("receipt-disputed-items-loading"))
            }
            if (errorForReceipt) {
                Text(receiptDisputedItemsErrorMessage(error, language),
                    modifier = Modifier.testTag("receipt-disputed-items-error"),
                    color = MaterialTheme.colorScheme.error)
                TextButton(modifier = Modifier.testTag("receipt-disputed-items-retry"),
                    enabled = !loading, onClick = { onPageChange(requestedPage ?: visiblePage) }) {
                    Text(if (russian) "Повторить" else "Retry")
                }
            }
            if (!loading && !errorForReceipt && currentPage != null && currentPage.items.isEmpty()) {
                Text(if (russian) "Нет спорных товаров" else "No disputed items",
                    modifier = Modifier.testTag("receipt-disputed-items-empty"))
            }
            currentPage?.items?.forEach { item ->
                Column(Modifier.fillMaxWidth().testTag("receipt-disputed-item-${item.id}"),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium)
                    item.lineSum?.let { amount ->
                        Text(formatMoney(amount, language, currency),
                            modifier = Modifier.testTag("receipt-disputed-item-amount-${item.id}"))
                    }
                    Text("${if (russian) "Оценка" else "Verdict"}: " +
                        disputedVerdictLabel(item.verdict, language),
                        modifier = Modifier.testTag("receipt-disputed-item-verdict-${item.id}"))
                    Text("${if (russian) "Причина" else "Reason"}: " +
                        safeReceiptReviewText(item.reviewReason, language, "reason"),
                        modifier = Modifier.testTag("receipt-disputed-item-reason-${item.id}"))
                    Text("${if (russian) "Действие" else "Action"}: " +
                        disputedActionLabel(item.reviewAction, language),
                        modifier = Modifier.testTag("receipt-disputed-item-action-${item.id}"))
                    Text("${if (russian) "Совет" else "Advice"}: " +
                        safeReceiptReviewText(item.advice, language, "advice"),
                        modifier = Modifier.testTag("receipt-disputed-item-advice-${item.id}"))
                    val sourceLabel = disputedSourceLabel(item.verdictSource, language)
                    val provider = safeReceiptProvenance(item.reviewProvider, language)
                    val model = safeReceiptProvenance(item.reviewModelVersion, language)
                    val prompt = safeReceiptProvenance(item.reviewPromptVersion, language)
                    val algorithm = safeReceiptProvenance(item.reviewAlgorithmVersion, language)
                    Text("${if (russian) "Источник" else "Source"}: $sourceLabel · " +
                        "${if (russian) "провайдер" else "provider"} $provider · " +
                        "${if (russian) "модель" else "model"} $model · " +
                        "${if (russian) "промпт" else "prompt"} $prompt · " +
                        "${if (russian) "алгоритм" else "algorithm"} $algorithm",
                        modifier = Modifier.testTag("receipt-disputed-item-source-${item.id}"))
                    Text("${if (russian) "Происхождение позиции" else "Item provenance"}: " +
                        disputedItemProvenanceLabel(item.provenance, language),
                        modifier = Modifier.testTag("receipt-disputed-item-provenance-${item.id}"))
                    val productKey = item.productKey?.takeIf(String::isNotBlank)
                    if (canManage && productKey != null && productKey !in allowedKeys) {
                        TextButton(
                            modifier = Modifier.testTag("receipt-disputed-item-allow-$productKey"),
                            enabled = !loading,
                            onClick = { onDecision(productKey, "allow") },
                        ) {
                            Text(if (russian) "Разрешить товар" else "Allow product")
                        }
                    }
                }
            }
            if (sortedAllowedKeys.isNotEmpty()) {
                Text(if (russian) "Разрешённые товары" else "Allowed products",
                    style = MaterialTheme.typography.labelLarge)
                visibleAllowedKeys.forEach { productKey ->
                    Row(Modifier.fillMaxWidth().testTag("receipt-disputed-allowed-item-$productKey"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(productNames[productKey] ?: if (russian) "Товар" else "Product",
                            modifier = Modifier.weight(1f))
                        if (canManage) TextButton(
                            modifier = Modifier.testTag("receipt-disputed-item-revoke-$productKey"),
                            enabled = !loading,
                            onClick = { onDecision(productKey, "revoke") },
                        ) {
                            Text(if (russian) "Вернуть в спорные" else "Revoke allow")
                        }
                    }
                }
                Text(if (russian) "Страница $boundedAllowedPage из $allowedPageCount"
                    else "Page $boundedAllowedPage of $allowedPageCount",
                    modifier = Modifier.testTag("receipt-disputed-allowed-page"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(modifier = Modifier.testTag("receipt-disputed-allowed-previous"),
                        enabled = boundedAllowedPage > 1 && !loading,
                        onClick = { allowedPageNumber = (boundedAllowedPage - 1).coerceAtLeast(1) }) {
                        Text(if (russian) "Назад" else "Previous")
                    }
                    TextButton(modifier = Modifier.testTag("receipt-disputed-allowed-next"),
                        enabled = boundedAllowedPage < allowedPageCount && !loading,
                        onClick = { allowedPageNumber = (boundedAllowedPage + 1).coerceAtMost(allowedPageCount) }) {
                        Text(if (russian) "Далее" else "Next")
                    }
                }
            }
            Text(if (russian) "Страница $visiblePage" else "Page $visiblePage",
                modifier = Modifier.testTag("receipt-disputed-items-page"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(modifier = Modifier.testTag("receipt-disputed-items-previous"),
                    enabled = visiblePage > 1 && !loading,
                    onClick = { onPageChange((visiblePage - 1).coerceAtLeast(1)) }) {
                    Text(if (russian) "Назад" else "Previous")
                }
                TextButton(modifier = Modifier.testTag("receipt-disputed-items-next"),
                    enabled = currentPage?.hasMore == true && !loading,
                    onClick = { onPageChange(visiblePage + 1) }) {
                    Text(if (russian) "Далее" else "Next")
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ReceiptRepeatWarningsSection(
    receipt: FinanceReceipt,
    language: String,
    warnings: FinanceReceiptRepeatWarnings?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    val russian = language == "ru"
    Card(Modifier.fillMaxWidth().testTag("receipt-repeat-warnings")) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (russian) "Повторные покупки" else "Repeat purchases",
                modifier = Modifier.testTag("receipt-repeat-warnings-title"),
                style = MaterialTheme.typography.titleSmall)
            if (loading) {
                Text(if (russian) "Загружаем предупреждения…" else "Loading repeat warnings…",
                    modifier = Modifier.testTag("receipt-repeat-warnings-loading"))
            } else if (error != null) {
                Text(if (russian) "Повторные предупреждения временно недоступны"
                    else "Repeat warnings are temporarily unavailable",
                    modifier = Modifier.testTag("receipt-repeat-warnings-error"),
                    color = MaterialTheme.colorScheme.error)
                TextButton(modifier = Modifier.testTag("receipt-repeat-warnings-retry"),
                    onClick = onRetry) {
                    Text(if (russian) "Повторить" else "Retry")
                }
            } else if (warnings != null && warnings.warnings.isEmpty()) {
                Text(if (russian) "Повторных предупреждений нет" else "No repeat warnings",
                    modifier = Modifier.testTag("receipt-repeat-warnings-empty"))
            } else if (warnings == null) {
                Text(if (russian) "Загружаем предупреждения…" else "Loading repeat warnings…",
                    modifier = Modifier.testTag("receipt-repeat-warnings-loading"))
            } else {
                warnings.warnings.forEach { warning ->
                    Column(Modifier.fillMaxWidth().testTag("receipt-repeat-warning-${warning.itemId}"),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val title = if (russian) warning.title else when (warning.verdict) {
                            "harmful" -> "Consider reducing"
                            "unnecessary" -> "Could skip this purchase"
                            else -> "Repeat warning"
                        }
                        Text(title, modifier = Modifier.testTag("receipt-repeat-warning-title-${warning.itemId}"),
                            style = MaterialTheme.typography.bodyMedium)
                        Text("${if (russian) "Товар" else "Product"}: ${warning.name}")
                        Text("${if (russian) "Оценка" else "Verdict"}: " +
                            repeatWarningVerdictLabel(warning.verdict, language))
                        Text("${if (russian) "Совпадений ранее" else "Prior purchases"}: ${warning.count}")
                        Text("${if (russian) "Последняя сумма" else "Last amount"}: " +
                            formatMoney(warning.lastSum, language, receipt.currency))
                        warning.advice?.takeIf(String::isNotBlank)?.let { advice ->
                            Text("${if (russian) "Совет" else "Advice"}: $advice")
                        }
                    }
                }
            }
        }
    }
}

private fun repeatWarningVerdictLabel(verdict: String, language: String): String =
    if (language == "ru") when (verdict) {
        "harmful" -> "Вредно"
        "unnecessary" -> "Необязательно"
        else -> "Неизвестно"
    } else when (verdict) {
        "harmful" -> "Harmful"
        "unnecessary" -> "Optional"
        else -> "Unknown"
    }

internal fun receiptPollDelayMillis(state: String, attempt: Int): Long? {
    if (attempt >= 60) return null
    return when (state) {
        "queued", "running" -> 1_000L
        "retryable" -> 5_000L
        else -> null
    }
}

private fun receiptConfirmationIdempotencyKey(tenantId: String, receiptId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$tenantId:$receiptId".toByteArray(Charsets.UTF_8))
    return "receipt-confirm-${digest.joinToString("") { "%02x".format(it) }}"
}

private fun receiptDisputedItemsErrorMessage(error: String?, language: String): String =
    if (language == "ru") when (error) {
        "decision_failed" -> "Не удалось проверить изменение решения. Обновите список и проверьте результат."
        "decision_refresh" -> "Решение могло сохраниться, но список не обновился. Повторите обновление."
        else -> "Не удалось загрузить спорные товары."
    } else when (error) {
        "decision_failed" -> "Could not verify the decision change. Refresh the list to check its result."
        "decision_refresh" -> "The decision may have been saved, but the list did not refresh. Retry refresh."
        else -> "Could not load disputed items."
    }

private fun disputedVerdictLabel(verdict: String?, language: String): String =
    if (language == "ru") when (verdict) {
        "harmful" -> "неблагоприятная"
        "unnecessary" -> "необязательная"
        "useful" -> "полезная"
        "neutral" -> "нейтральная"
        null, "" -> "не указана"
        else -> "неизвестная"
    } else when (verdict) {
        "harmful" -> "harmful"
        "unnecessary" -> "unnecessary"
        "useful" -> "useful"
        "neutral" -> "neutral"
        null, "" -> "not provided"
        else -> "unknown"
    }

private fun disputedActionLabel(action: String?, language: String): String {
    val russian = language == "ru"
    val value = action?.trim()?.takeIf(String::isNotEmpty) ?: return if (russian) "не указано" else "not provided"
    return when (value.lowercase()) {
        "avoid" -> if (russian) "не брать" else "avoid"
        "unknown", "null", "not_provided" -> if (russian) "не указано" else "not provided"
        else -> if (RECEIPT_MACHINE_REVIEW_VALUE.matches(value)) {
            if (russian) "не указано" else "not provided"
        } else value
    }
}

private fun disputedSourceLabel(source: String?, language: String): String =
    if (language == "ru") when (source) {
        "rule" -> "правило"
        "model" -> "модель"
        "default" -> "по умолчанию"
        "human" -> "человек"
        "unknown", null, "" -> "неизвестен"
        else -> "неизвестен"
    } else when (source) {
        "rule" -> "rule"
        "model" -> "model"
        "default" -> "default"
        "human" -> "human"
        "unknown", null, "" -> "unknown"
        else -> "unknown"
    }

private fun disputedItemProvenanceLabel(provenance: String?, language: String): String =
    if (language == "ru") when (provenance) {
        "ocr" -> "распознавание чека"
        "receipt_review" -> "проверка чека"
        "manual" -> "вручную"
        "unknown", null, "" -> "неизвестно"
        else -> "неизвестно"
    } else when (provenance) {
        "ocr" -> "receipt recognition"
        "receipt_review" -> "receipt review"
        "manual" -> "manual"
        "unknown", null, "" -> "unknown"
        else -> "unknown"
    }

private fun safeReceiptReviewText(value: String?, language: String, kind: String): String {
    val russian = language == "ru"
    val fallback = when (kind) {
        "reason" -> if (russian) "не указана" else "not provided"
        else -> if (russian) "не указан" else "not provided"
    }
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return fallback
    return if (text.lowercase() in setOf("unknown", "null", "not_provided", "not-provided") ||
        RECEIPT_MACHINE_REVIEW_VALUE.matches(text)) fallback else text
}

private fun safeReceiptProvenance(value: String?, language: String): String {
    val text = value?.trim()?.takeIf(String::isNotEmpty)
    return if (text == null || text.lowercase() in setOf("unknown", "null", "not_provided")) {
        if (language == "ru") "не указано" else "not provided"
    } else text
}

private val RECEIPT_MACHINE_REVIEW_VALUE = Regex("^[A-Za-z][A-Za-z0-9]*(?:[._][A-Za-z0-9]+)+$")

private fun receiptStageLabel(stage: String, language: String): String = if (language == "ru") when (stage) {
    "queued" -> "В очереди"
    "scanning" -> "Проверка файла"
    "vision" -> "Проверка изображения"
    "ocr" -> "Распознавание чека"
    "draft" -> "Подготовка черновика"
    "complete" -> "Готово"
    else -> "Обработка чека"
} else when (stage) {
    "queued" -> "Queued"
    "scanning" -> "File scan"
    "vision" -> "Image analysis"
    "ocr" -> "Receipt recognition"
    "draft" -> "Preparing draft"
    "complete" -> "Complete"
    else -> "Processing receipt"
}

private fun localizedReceiptFallbackReason(code: String, source: String, language: String): String {
    val normalized = code.uppercase()
    val russian = language == "ru"
    return when (source to normalized) {
        "vision" to "VISION_UNAVAILABLE", "vision" to "UNAVAILABLE" ->
            if (russian) "распознавание Vision временно недоступно" else "Vision recognition is temporarily unavailable"
        "vision" to "VISION_NOT_CONFIGURED" ->
            if (russian) "распознавание Vision не настроено" else "Vision recognition is not configured"
        "vision" to "VISION_RESPONSE_TOO_LARGE" ->
            if (russian) "ответ Vision слишком велик для обработки" else "Vision response is too large to process"
        "vision" to "VISION_UNSUPPORTED" ->
            if (russian) "распознавание Vision не поддерживается" else "Vision recognition is unsupported"
        "vision" to "VISION_INVALID_RESPONSE" ->
            if (russian) "Vision вернул некорректный результат" else "Vision returned an invalid result"
        "vision" to "VISION_TIMEOUT" ->
            if (russian) "время распознавания Vision истекло" else "Vision recognition timed out"
        "vision" to "VISION_INTERRUPTED" ->
            if (russian) "распознавание Vision было прервано" else "Vision recognition was interrupted"
        "vision_model" to "VISION_MODEL_UNAVAILABLE" ->
            if (russian) "выбранная модель Vision недоступна" else "selected Vision model is unavailable"
        "ocr" to "OCR_INVALID_RESPONSE" ->
            if (russian) "OCR не смог обработать изображение" else "OCR could not process the image"
        "ocr" to "OCR_UNAVAILABLE", "ocr" to "UNAVAILABLE" ->
            if (russian) "OCR временно недоступен" else "OCR is temporarily unavailable"
        else -> if (russian) "Дополнительная информация о распознавании недоступна"
            else "Additional recognition details are unavailable"
    }
}

private fun receiptUploadErrorMessage(code: String, language: String): String = if (language == "ru") when (code) {
    "file_type" -> "Выберите изображение JPEG или PNG."
    "file_size" -> "Размер фото не должен превышать 10 МиБ."
    "file_empty", "file_read" -> "Не удалось прочитать фото. Выберите файл ещё раз."
    "forbidden" -> "Нет прав на загрузку чека."
    "unauthorized" -> "Войдите снова, чтобы продолжить."
    "rejected" -> "Фото не прошло проверку. Выберите другой файл."
    "retryable" -> "Обработка временно не завершена. Обновите статус позже."
    "timeout" -> "Обработка ещё идёт. Обновите статус позже."
    "receipt_missing" -> "Черновик чека пока недоступен. Обновите статус."
    "checkpoint" -> "Не удалось сохранить состояние загрузки. Повторите действие позже."
    "checkpoint_workspace" -> "Незавершённая загрузка относится к другому пространству. Сначала переключитесь на него."
    else -> "Не удалось обработать фото. Проверьте соединение и повторите."
} else when (code) {
    "file_type" -> "Choose a JPEG or PNG image."
    "file_size" -> "Photo must be 10 MiB or smaller."
    "file_empty", "file_read" -> "Could not read photo. Choose the file again."
    "forbidden" -> "You do not have permission to upload receipts."
    "unauthorized" -> "Sign in again to continue."
    "rejected" -> "Photo failed validation. Choose another file."
    "retryable" -> "Processing has not finished. Refresh status later."
    "timeout" -> "Processing is still running. Refresh status later."
    "receipt_missing" -> "Receipt draft is not ready. Refresh status."
    "checkpoint" -> "Could not save upload state. Try again later."
    "checkpoint_workspace" -> "Pending upload belongs to another workspace. Switch to that workspace first."
    else -> "Could not process photo. Check connection and retry."
}

@androidx.compose.runtime.Composable
private fun TransactionEditEditor(transaction: FinanceTransaction, debts: List<FinanceDebt>, timezone: String,
                                 members: List<FinanceTenantMember>, canReassignOwner: Boolean,
                                 language: String, busy: Boolean, onSave: (FinanceTransactionEdit) -> Unit,
                                 onCancel: () -> Unit) {
    val russian = language == "ru"
    var type by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.type) }
    var amount by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.amount) }
    var category by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.categoryCode) }
    var subcategory by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.subcategoryCode.orEmpty()) }
    var description by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.description) }
    var source by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.source) }
    var date by androidx.compose.runtime.remember(transaction.id, timezone) {
        mutableStateOf(runCatching {
            java.time.Instant.parse(transaction.occurredAt).atZone(ZoneId.of(timezone)).toLocalDate().toString()
        }.getOrDefault(transaction.occurredAt.take(10)))
    }
    var ownerUserId by androidx.compose.runtime.remember(transaction.id) {
        mutableStateOf(transaction.ownerUserId.orEmpty())
    }
    var ownerMenuExpanded by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(false) }
    var debtId by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(transaction.debtId) }
    var typeMenuExpanded by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(false) }
    var debtMenuExpanded by androidx.compose.runtime.remember(transaction.id) { mutableStateOf(false) }
    val occurredAt = runCatching {
        LocalDate.parse(date).atTime(12, 0).atZone(ZoneId.of(timezone)).toInstant().toString()
    }.getOrNull()
    val validAmount = Regex("^(?:0\\.(?:0?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\\.[0-9]{1,2})?)$")
        .matches(amount.trim().replace(',', '.'))
    val categoryValue = category.trim()
    val subcategoryValue = subcategory.trim()
    val descriptionValue = description.trim()
    val sourceValue = source.trim()
    val valid = validAmount && categoryValue.length in 1..64 && subcategoryValue.length <= 64
        && descriptionValue.length in 1..500 && sourceValue.length in 1..64 && occurredAt != null
        && (type != "debt_payment" || debtId != null)
    Card(Modifier.fillMaxWidth().testTag("transaction-edit-form")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (russian) "Изменить операцию" else "Edit transaction", style = MaterialTheme.typography.titleMedium)
            Box {
                TextButton(modifier = Modifier.testTag("transaction-edit-type"), onClick = { typeMenuExpanded = true }) {
                    Text(if (russian) "Тип: $type" else "Type: $type")
                }
                DropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                    listOf("expense", "income", "refund", "transfer", "debt_payment").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = {
                            type = option
                            typeMenuExpanded = false
                        })
                    }
                }
            }
            OutlinedTextField(value = amount, onValueChange = { amount = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("transaction-edit-amount"),
                label = { Text(if (russian) "Сумма, ₽" else "Amount, RUB") }, singleLine = true)
            OutlinedTextField(value = category, onValueChange = { category = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("transaction-edit-category"),
                label = { Text(if (russian) "Категория" else "Category") }, singleLine = true)
            OutlinedTextField(value = subcategory, onValueChange = { subcategory = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("transaction-edit-subcategory"),
                label = { Text(if (russian) "Подкатегория" else "Subcategory") }, singleLine = true)
            OutlinedTextField(value = description, onValueChange = { description = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("transaction-edit-description"),
                label = { Text(if (russian) "Описание" else "Description") }, singleLine = true)
            OutlinedTextField(value = source, onValueChange = { source = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("transaction-edit-source"),
                label = { Text(if (russian) "Источник" else "Source") }, singleLine = true)
            OutlinedTextField(value = date, onValueChange = { date = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("transaction-edit-date"),
                label = { Text(if (russian) "Дата (ГГГГ-ММ-ДД)" else "Date (YYYY-MM-DD)") }, singleLine = true)
            if (canReassignOwner) {
                val selectedOwner = members.firstOrNull { it.userId == ownerUserId }
                Box {
                    TextButton(modifier = Modifier.testTag("transaction-edit-owner-picker"),
                        enabled = !busy, onClick = { ownerMenuExpanded = true }) {
                        Text("${if (russian) "Пользователь операции" else "Transaction member"}: " +
                            (selectedOwner?.displayName ?: if (russian) "Выберите участника" else "Select member"))
                    }
                    DropdownMenu(expanded = ownerMenuExpanded, onDismissRequest = { ownerMenuExpanded = false }) {
                        members.forEach { member ->
                            DropdownMenuItem(
                                modifier = Modifier.testTag("transaction-edit-owner-option-${member.userId}"),
                                text = { Text(member.displayName) },
                                onClick = {
                                    ownerUserId = member.userId
                                    ownerMenuExpanded = false
                                })
                        }
                    }
                }
            }
            if (!valid) Text(if (russian)
                "Проверьте сумму и поля: категория и источник обязательны (до 64 символов), подкатегория — до 64, описание — до 500."
            else "Check amount and fields: category and source are required (up to 64 characters), subcategory up to 64, description up to 500.",
                color = MaterialTheme.colorScheme.error)
            if (type == "debt_payment") {
                Box {
                    TextButton(modifier = Modifier.testTag("transaction-edit-debt"),
                        onClick = { debtMenuExpanded = true }) {
                        Text(debts.firstOrNull { it.id == debtId }?.name
                            ?: if (russian) "Выберите долг" else "Select debt")
                    }
                    DropdownMenu(expanded = debtMenuExpanded, onDismissRequest = { debtMenuExpanded = false }) {
                        debts.filter { it.status == "open" || it.id == transaction.debtId }.forEach { debt ->
                            DropdownMenuItem(text = { Text(debt.name) }, onClick = {
                                debtId = debt.id
                                debtMenuExpanded = false
                            })
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.testTag("transaction-edit-save"), enabled = !busy && valid,
                    onClick = {
                        val instant = occurredAt ?: return@Button
                        onSave(FinanceTransactionEdit(
                            id = transaction.id, version = transaction.version, type = type,
                            amount = amount.trim().replace(',', '.'), currency = transaction.currency,
                            categoryCode = categoryValue, subcategoryCode = subcategoryValue.takeIf(String::isNotEmpty),
                            description = descriptionValue, source = sourceValue, occurredAt = instant,
                            debtId = debtId, ownerUserId = ownerUserId.takeIf(String::isNotBlank),
                            accountId = transaction.accountId,
                        ))
                    }) { Text(if (russian) "Сохранить" else "Save") }
                TextButton(modifier = Modifier.testTag("transaction-edit-cancel"), enabled = !busy,
                    onClick = onCancel) { Text(if (russian) "Закрыть" else "Cancel") }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TransactionHistoryCard(transaction: FinanceTransaction, language: String, canWrite: Boolean, busy: Boolean,
                                   canEdit: Boolean, onEdit: (FinanceTransaction) -> Unit,
                                   onRepeat: (FinanceTransaction) -> Unit, onVoid: (FinanceTransaction) -> Unit) {
    val russian = language == "ru"
    val posted = transaction.status == "posted"
    val income = transaction.type == "income" || transaction.type == "refund"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(transaction.description.ifBlank { transaction.categoryCode }, style = MaterialTheme.typography.titleMedium)
            Text("${transaction.occurredAt.take(10)} · ${if (income) "+" else "−"}${formatMoney(transaction.amount, language, transaction.currency)}")
            Text(if (russian) {
                "${transaction.memberName?.let { "$it · " } ?: ""}${transaction.categoryCode} · " +
                    if (posted) "Проведена" else "Отменена"
            } else {
                "${transaction.memberName?.let { "$it · " } ?: ""}${transaction.categoryCode} · " +
                    if (posted) "Posted" else "Voided"
            })
            if ((canWrite || canEdit) && posted) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canEdit) TextButton(
                        modifier = Modifier.testTag("transaction-edit-${transaction.id}"),
                        enabled = !busy, onClick = { onEdit(transaction) },
                    ) { Text(if (russian) "Изменить" else "Edit") }
                    if (transaction.type != "debt_payment") TextButton(
                        modifier = Modifier.testTag("transaction-repeat-${transaction.id}"),
                        enabled = !busy, onClick = { onRepeat(transaction) },
                    ) { Text(if (russian) "Повторить" else "Repeat") }
                    TextButton(
                        modifier = Modifier.testTag("transaction-void-${transaction.id}"),
                        enabled = !busy, onClick = { onVoid(transaction) },
                    ) { Text(if (russian) "Отменить" else "Void") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RecurringScreen(modifier: Modifier, state: FinanceUiState, language: String, onRetry: () -> Unit,
                            onRecurringDecision: (String, Boolean) -> Unit) {
    val russian = language == "ru"
    val projection = state.recurringProjection
    LazyColumn(modifier.testTag("recurring-projection"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (russian) "Регулярные доходы и расходы" else "Recurring income and expenses",
                    style = MaterialTheme.typography.titleLarge)
                Text(if (russian) "Оценка по повторяющимся операциям из вашей истории."
                    else "Estimate from repeated transactions in your history.")
            }
        }
        when {
            state.recurringLoading -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            state.recurringError != null -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (russian) "Регулярные операции временно недоступны." else "Recurring transactions are unavailable.")
                    Button(onClick = onRetry) { Text(if (russian) "Повторить" else "Retry") }
                }
            }
            projection == null -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            projection.expenseSeries.isEmpty() && projection.incomeSeries.isEmpty() && projection.mutedSeries.isEmpty() -> item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (russian) "Пока нет найденных регулярных операций." else "No recurring transactions found yet.")
                    Text(if (russian) "Для серии нужны минимум три похожие операции." else "Each series needs at least three similar transactions.")
                }
            }
            else -> {
                if (projection.dueSoon.isNotEmpty()) {
                    item { Text(if (russian) "Скоро · следующие 3 дня" else "Due soon · next 3 days",
                        style = MaterialTheme.typography.titleMedium) }
                    items(projection.dueSoon, key = { "due-${it.id}" }) { series ->
                        RecurringSeriesCard(series, russian, warning = true)
                    }
                }
                if (projection.monthlyExpenseEstimate != null) item {
                    Card(Modifier.fillMaxWidth()) {
                        Text("${if (russian) "Оценка расходов в месяц" else "Estimated monthly expenses"}: " +
                            "${projection.monthlyExpenseEstimate} ${if (russian) "₽" else "RUB"}",
                            Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
                item { Text(if (russian) "Регулярные расходы" else "Recurring expenses", style = MaterialTheme.typography.titleMedium) }
                if (projection.expenseSeries.isEmpty()) item { Text(if (russian) "Нет подтверждённых серий расходов." else "No confirmed expense series.") }
                items(projection.expenseSeries, key = { "expense-${it.id}" }) { series ->
                    RecurringSeriesCard(series, russian, actionLabel = if (russian) "Отключить напоминание" else "Mute reminder") {
                        onRecurringDecision(series.id, true)
                    }
                }
                item { Text(if (russian) "Регулярные доходы" else "Recurring income", style = MaterialTheme.typography.titleMedium) }
                if (projection.incomeSeries.isEmpty()) item { Text(if (russian) "Нет подтверждённых серий доходов." else "No confirmed income series.") }
                items(projection.incomeSeries, key = { "income-${it.id}" }) { series ->
                    RecurringSeriesCard(series, russian, actionLabel = if (russian) "Отключить напоминание" else "Mute reminder") {
                        onRecurringDecision(series.id, true)
                    }
                }
                if (projection.overdue.isNotEmpty()) {
                    item { Text(if (russian) "Просрочено · не входит в ближайшие списания" else "Overdue · excluded from upcoming charges",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error) }
                    items(projection.overdue, key = { "overdue-${it.id}" }) { series ->
                        RecurringSeriesCard(series, russian, warning = false, overdue = true)
                    }
                }
                if (projection.mutedSeries.isNotEmpty()) {
                    item { Text(if (russian) "Отключённые напоминания" else "Muted reminders",
                        style = MaterialTheme.typography.titleMedium) }
                    items(projection.mutedSeries, key = { "muted-${it.id}" }) { series ->
                        RecurringSeriesCard(series, russian,
                            actionLabel = if (russian) "Восстановить напоминание" else "Restore reminder") {
                            onRecurringDecision(series.id, false)
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RecurringSeriesCard(series: FinanceRecurringSeries, russian: Boolean, warning: Boolean = false,
                                overdue: Boolean = false, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(series.name, style = MaterialTheme.typography.titleMedium)
            Text("${series.amount} ${if (russian) "₽" else series.currency} · " +
                (if (series.periodCode == "week") { if (russian) "еженедельно" else "weekly" }
                else { if (russian) "ежемесячно" else "monthly" }))
            Text("${if (russian) "Обычно" else "Typical range"}: ${series.minAmount}–${series.maxAmount} " +
                "${if (russian) "₽" else series.currency} · ${if (russian) "интервал" else "interval"}: " +
                "${series.minIntervalDays}–${series.maxIntervalDays} ${if (russian) "дн." else "days"}")
            Text(if (overdue) {
                if (russian) "Просрочено на ${-series.daysUntil} дн. · ${series.nextDate}" else "Overdue ${-series.daysUntil} days · ${series.nextDate}"
            } else if (warning) {
                if (russian) "Через ${series.daysUntil} дн. · ${series.nextDate}" else "In ${series.daysUntil} days · ${series.nextDate}"
            } else {
                if (russian) "Следующая дата: ${series.nextDate} · ${series.occurrences} операций"
                else "Next: ${series.nextDate} · ${series.occurrences} transactions"
            })
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PersonalInflationScreen(modifier: Modifier, state: FinanceUiState, language: String, onRetry: () -> Unit) {
    val russian = language == "ru"
    val inflation = state.personalInflation
    LazyColumn(modifier = modifier.testTag("personal-inflation"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (russian) "Личная динамика цен · 90 дней" else "Personal price trend · 90 days",
                    style = MaterialTheme.typography.titleLarge)
                Text(if (russian) "Цены только из ваших чеков, не официальная статистика."
                    else "Receipt prices only; not official inflation statistics.")
            }
        }
        when {
            state.personalInflationLoading -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            state.personalInflationError != null -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (russian) "Динамика цен временно недоступна." else "Price trend is temporarily unavailable.")
                    Button(onClick = onRetry) { Text(if (russian) "Повторить" else "Retry") }
                }
            }
            inflation == null -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            !inflation.available -> item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (russian) "Недостаточно истории для расчёта." else "There is not enough purchase history.")
                    Text(if (russian) "Нужно минимум 3 товара: для каждого — 2 покупки до окна и 1 внутри 90-дневного окна."
                        else "At least 3 products are needed: each must have 2 purchases before the window and 1 within it.")
                }
            }
            else -> {
                val basketBefore = inflation.basketBefore ?: return@LazyColumn
                val basketNow = inflation.basketNow ?: return@LazyColumn
                val indexPercent = inflation.indexPercent ?: return@LazyColumn
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${if (russian) "Корзина по старым ценам" else "Basket at earlier prices"}: $basketBefore ${if (russian) "₽" else "RUB"}")
                            Text("${if (russian) "Та же корзина по новым ценам" else "Same basket at recent prices"}: $basketNow ${if (russian) "₽" else "RUB"}")
                            Text("${if (russian) "Личный индекс" else "Personal index"}: $indexPercent% · " +
                                if (russian) "${inflation.productCount} товара" else "${inflation.productCount} products")
                        }
                    }
                }
                val priceChanges = inflation.rising.map { it to true } + inflation.falling.map { it to false }
                if (priceChanges.isNotEmpty()) item {
                    Card(Modifier.fillMaxWidth().testTag("personal-price-change-chart")) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (russian) "Изменение цен" else "Price changes",
                                style = MaterialTheme.typography.titleMedium)
                            Text(if (russian) "Полосы показывают модуль изменения; максимум шкалы — 100%."
                                else "Bars show change magnitude; the scale tops out at 100%.")
                            priceChanges.forEachIndexed { index, (product, isRising) ->
                                val direction = if (isRising) {
                                    if (russian) "рост" else "increase"
                                } else if (russian) "снижение" else "decrease"
                                val accessibleLabel = "${product.productName} · $direction · ${product.changePercent}%"
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(accessibleLabel)
                                    LinearProgressIndicator(
                                        progress = { priceChangeMagnitudeFraction(product.changePercent) },
                                        modifier = Modifier.fillMaxWidth().testTag(
                                            if (isRising) "personal-price-increase-$index" else "personal-price-decrease-${index - inflation.rising.size}",
                                        ).semantics { contentDescription = accessibleLabel },
                                    )
                                }
                            }
                        }
                    }
                }
                item { Text(if (russian) "Сильнее подорожали" else "Largest increases", style = MaterialTheme.typography.titleMedium) }
                if (inflation.rising.isEmpty()) item { Text(if (russian) "Нет заметных изменений." else "No notable changes.") }
                items(inflation.rising) { product ->
                    Card(Modifier.fillMaxWidth()) {
                        Text("${product.productName} · ${product.oldUnitPrice} → ${product.newUnitPrice} " +
                            "${if (russian) "₽" else "RUB"} · ${product.changePercent}% · " +
                            "${if (russian) "вес" else "weight"} ${product.oldSpendWeight} ${if (russian) "₽" else "RUB"}",
                            Modifier.padding(14.dp))
                    }
                }
                item { Text(if (russian) "Сильнее подешевели" else "Largest decreases", style = MaterialTheme.typography.titleMedium) }
                if (inflation.falling.isEmpty()) item { Text(if (russian) "Нет заметных изменений." else "No notable changes.") }
                items(inflation.falling) { product ->
                    Card(Modifier.fillMaxWidth()) {
                        Text("${product.productName} · ${product.oldUnitPrice} → ${product.newUnitPrice} " +
                            "${if (russian) "₽" else "RUB"} · ${product.changePercent}% · " +
                            "${if (russian) "вес" else "weight"} ${product.oldSpendWeight} ${if (russian) "₽" else "RUB"}",
                            Modifier.padding(14.dp))
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
internal fun ProductCatalogScreen(
    modifier: Modifier,
    language: String,
    currency: String,
    catalog: FinanceProductCatalog?,
    requestedQuery: String,
    loading: Boolean,
    error: String?,
    onSearch: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val russian = language == "ru"
    var draftQuery by androidx.compose.runtime.remember(requestedQuery) { mutableStateOf(requestedQuery) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(if (russian) "Товары" else "Products", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = draftQuery,
                onValueChange = { value ->
                    if (isValidProductCatalogQuery(value)) draftQuery = value
                },
                modifier = Modifier.fillMaxWidth().testTag("product-catalog-query"),
                label = { Text(if (russian) "Поиск товаров" else "Search products") },
                singleLine = true,
            )
            Button(onClick = { onSearch(draftQuery.trim()) }, enabled = !loading,
                modifier = Modifier.testTag("product-catalog-search")) {
                Text(if (russian) "Найти" else "Search")
            }
        }
        when {
            loading -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            error != null -> item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (russian) "История цен временно недоступна." else "Price history is temporarily unavailable.")
                    TextButton(onClick = onRetry) { Text(if (russian) "Повторить" else "Retry") }
                }
            }
            catalog == null || catalog.products.isEmpty() -> item {
                Text(if (requestedQuery.isBlank()) {
                    if (russian) "Каталог появится после трёх подтверждённых покупок товара."
                    else "Catalog appears after three confirmed purchases of a product."
                } else {
                    if (russian) "Совпадений нет." else "No matches."
                })
            }
            else -> items(catalog.products, key = { "${it.productName}:${it.lastPurchasedAt}" }) { product ->
                ProductCatalogCardView(product, language, currency)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ProductCatalogCardView(product: FinanceProductCard, language: String, currency: String) {
    val russian = language == "ru"
    val locale = Locale.forLanguageTag(if (russian) "ru-RU" else "en-US")
    val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", locale).withZone(ZoneOffset.UTC)
    val date = runCatching { dateFormatter.format(Instant.parse(product.lastPurchasedAt)) }
        .getOrDefault(product.lastPurchasedAt.take(10))
    val purchaseLabel = if (russian) when (product.purchaseCount % 10) {
        1 -> "покупка"
        2, 3, 4 -> "покупки"
        else -> "покупок"
    } else if (product.purchaseCount == 1) "purchase" else "purchases"
    val unknownMerchant = if (russian) "магазин не указан" else "store not listed"

    Card(Modifier.fillMaxWidth().testTag("product-card-${product.productName}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(product.productName, style = MaterialTheme.typography.titleMedium)
                Text("${product.purchaseCount} $purchaseLabel")
            }
            ProductCatalogMetric("usual-${product.productName}", if (russian) "Обычная цена" else "Usual price",
                formatMoney(product.usualUnitPrice, language, currency))
            ProductCatalogMetric("last-${product.productName}", if (russian) "Последняя покупка" else "Last purchase",
                "${formatMoney(product.lastUnitPrice, language, currency)} · $date · ${product.lastMerchant ?: unknownMerchant}")
            ProductCatalogMetric("cheapest-${product.productName}", if (russian) "Самая низкая цена" else "Lowest price",
                "${formatMoney(product.cheapestUnitPrice, language, currency)} · ${product.cheapestMerchant ?: unknownMerchant}")
            ProductCatalogMetric("spent-${product.productName}", if (russian) "Потрачено" else "Spent",
                formatMoney(product.totalSpent, language, currency))
            if (product.hasBaseline && product.baselineUnitPrice != null) {
                ProductCatalogMetric("baseline-${product.productName}", if (russian) "До последней покупки" else "Before latest purchase",
                    formatMoney(product.baselineUnitPrice, language, currency))
            }
            if (!product.hasBaseline) {
                Text(if (russian) "Недостаточно сопоставимых покупок."
                    else "Not enough comparable purchases.")
            }
            if (product.chartAvailable && product.history.size >= 2) {
                val chartDescription = if (russian) "История цены: ${product.productName}"
                    else "Price history: ${product.productName}"
                val prices = product.history.map { BigDecimal(it.unitPrice) }
                val minimum = prices.minOrNull() ?: BigDecimal.ZERO
                val range = (prices.maxOrNull() ?: minimum).subtract(minimum)
                val chartColor = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(84.dp)
                    .semantics { contentDescription = chartDescription }) {
                    val coordinates = prices.mapIndexed { index, price ->
                        val x = size.width * index / (prices.size - 1)
                        val relative = if (range.signum() == 0) 0.5f else price.subtract(minimum)
                            .divide(range, 8, RoundingMode.HALF_UP).toFloat()
                        Offset(x, size.height - relative * size.height)
                    }
                    val chartPath = Path().apply {
                        moveTo(coordinates.first().x, coordinates.first().y)
                        coordinates.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(chartPath, chartColor,
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
                    coordinates.forEach { point ->
                        drawCircle(chartColor, radius = 5.dp.toPx(), center = point)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (russian) "Раньше" else "Earlier")
                    Text(if (russian) "Сейчас" else "Now")
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ProductCatalogMetric(tag: String, label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, modifier = Modifier.testTag("product-metric-$tag"))
    }
}

@androidx.compose.runtime.Composable
private fun ShoppingScreen(modifier: Modifier, state: FinanceUiState, language: String, onRetry: () -> Unit,
                           onDecision: (String, String) -> Unit, onCopy: (String) -> Unit) {
    val russian = language == "ru"
    val shopping = state.shoppingList
    LazyColumn(modifier = modifier.testTag("shopping-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (russian) "Пора купить" else "Shopping list", style = MaterialTheme.typography.titleLarge)
                if (shopping != null && state.shoppingError == null && shopping.candidates.isNotEmpty()) {
                    Button(onClick = { onCopy(shoppingClipboardText(shopping, russian)) }) {
                        Text(if (russian) "Скопировать список" else "Copy list")
                    }
                }
            }
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
                val hasHidden = shopping.boughtCandidates.isNotEmpty() || shopping.mutedCandidates.isNotEmpty()
                    || shopping.blockedCandidates.isNotEmpty()
                Text(if (hasHidden) {
                    if (russian) "Активных подсказок пока нет." else "No active suggestions right now."
                } else if (russian) "Пока нечего добавить: нужны минимум три покупки с интервалами от трёх дней."
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
                            Text(if (russian) "Медиана: раз в ${candidate.medianIntervalDays} дн. · ${formatShoppingPurchaseCount(candidate.purchaseCount, language)}"
                                else "Median: every ${candidate.medianIntervalDays} days · ${formatShoppingPurchaseCount(candidate.purchaseCount, language)}")
                            Text(if (russian) "Оценка: ${candidate.estimatedCost} ₽" else "Estimate: ${candidate.estimatedCost} RUB")
                            Text(if (russian) "Последняя покупка: ${candidate.lastPurchasedAt.take(10)}"
                                else "Last purchased: ${candidate.lastPurchasedAt.take(10)}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onDecision(candidate.productKey, "bought") }) {
                                    Text(if (russian) "Уже купил" else "Already bought")
                                }
                                TextButton(onClick = { onDecision(candidate.productKey, "mute") }) {
                                    Text(if (russian) "Скрыть" else "Hide")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (shopping != null && state.shoppingError == null) {
            if (shopping.boughtCandidates.isNotEmpty()) {
                item {
                    Text(if (russian) "Уже куплено" else "Already bought", style = MaterialTheme.typography.titleMedium)
                    Text(if (russian) "Отметка действует до следующего обычного интервала покупки."
                        else "This mark expires after the next usual purchase interval.")
                    shopping.boughtCandidates.forEach { Text(it.productName) }
                }
            }
            if (shopping.mutedCandidates.isNotEmpty()) {
                item { Text(if (russian) "Скрытые подсказки" else "Hidden suggestions", style = MaterialTheme.typography.titleMedium) }
                shopping.mutedCandidates.forEach { candidate ->
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.productName)
                                Text(if (russian) "Вы скрыли эту подсказку." else "You hid this suggestion.")
                            }
                            TextButton(onClick = { onDecision(candidate.productKey, "unmute") }) {
                                Text(if (russian) "Вернуть подсказку" else "Restore suggestion")
                            }
                        }
                    }
                }
            }
            if (shopping.blockedCandidates.isNotEmpty()) {
                item { Text(if (russian) "Не брать" else "Do not buy", style = MaterialTheme.typography.titleMedium) }
                shopping.blockedCandidates.forEach { candidate ->
                    item {
                        val reason = if (candidate.reasonCode == "confirmed_not_to_buy") {
                            if (russian) "Вы отметили как «не брать»." else "You marked this as do not buy."
                        } else if (russian) "Основано на проверке чеков." else "Based on receipt review."
                        Text("${candidate.productName} · $reason")
                    }
                }
            }
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
private fun DoNotBuyScreen(modifier: Modifier, state: FinanceUiState, language: String, onRetry: () -> Unit,
                           onDecision: (String, String) -> Unit) {
    val russian = language == "ru"
    val report = state.doNotBuy
    val decisions = state.productDecisions
    val canWrite = state.tenants.firstOrNull()?.role != "viewer" && decisions != null && !state.doNotBuyLoading
    LazyColumn(modifier = modifier.testTag("do-not-buy-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(if (russian) "Не брать" else "Do not buy", style = MaterialTheme.typography.titleLarge) }
        when {
            state.doNotBuyLoading -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            state.doNotBuyError != null -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (russian) "Советы по чекам временно недоступны." else "Receipt advice is temporarily unavailable.")
                    Button(onClick = onRetry) { Text(if (russian) "Повторить" else "Retry") }
                }
            }
            report == null || decisions == null -> item { Text(if (russian) "Загрузка…" else "Loading…") }
            !report.available -> item {
                Text(if (russian) "Советы по чекам временно недоступны." else "Receipt advice is temporarily unavailable.")
                Button(onClick = onRetry) { Text(if (russian) "Повторить" else "Retry") }
            }
            else -> {
                if (report.banned.isEmpty() && report.guesses.isEmpty()) item {
                    Text(if (russian) "Повторяющихся отметок «вредно» или «лишнее» пока нет."
                        else "No repeated harmful or unnecessary verdicts yet.")
                }
                if (report.banned.isNotEmpty()) item {
                    Text(if (russian) "По правилам или вашему решению" else "By rule or your decision",
                        style = MaterialTheme.typography.titleMedium)
                }
                report.banned.forEach { group ->
                    item { DoNotBuyGroup(group, false, decisions, russian, canWrite, onDecision) }
                }
                if (report.guesses.isNotEmpty()) item {
                    Column {
                        val uncertain = report.guesses.any { !it.modelOnly }
                        Text(if (uncertain) {
                            if (russian) "Непроверенные отметки" else "Unverified evidence"
                        } else if (russian) "Догадки модели" else "Model guesses",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (russian) "Без вашего подтверждения эти отметки не скрывают покупку."
                            else "These verdicts do not hide a purchase until you confirm them.")
                    }
                }
                report.guesses.forEach { group ->
                    item { DoNotBuyGroup(group, true, decisions, russian, canWrite, onDecision) }
                }
                if (decisions.productKeys.isNotEmpty()) item {
                    Text(if (russian) "Вы разрешили покупать" else "You allowed purchases",
                        style = MaterialTheme.typography.titleMedium)
                }
                decisions.productKeys.forEach { key ->
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(key, Modifier.weight(1f))
                            if (canWrite) TextButton(onClick = { onDecision(key, "revoke") }) {
                                Text(if (russian) "Отменить решение" else "Undo decision")
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DoNotBuyGroup(group: FinanceAdviceGroup, guess: Boolean, decisions: FinanceProductDecisions,
                          russian: Boolean, canWrite: Boolean, onDecision: (String, String) -> Unit) {
    val confirmed = group.productKey in decisions.confirmedProductKeys
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(group.productName, style = MaterialTheme.typography.titleMedium)
            Text(when {
                confirmed -> if (russian) "Подтверждено вами" else "Confirmed by you"
                guess && group.modelOnly -> if (russian) "Догадка модели" else "Model guess"
                guess -> if (russian) "Источник части отметок неизвестен" else "Some verdict sources are unknown"
                else -> if (russian) "Основано на проверке чеков" else "Based on receipt review"
            })
            Text(if (russian) "Отмечено в чеках: ${group.count}" else "Flagged receipts: ${group.count}")
            Text(if (group.amount == null) {
                if (russian) "Сумма неизвестна" else "Amount unavailable"
            } else if (russian) "Сумма: ${group.amount} ₽" else "Amount: ${group.amount} RUB")
            if (group.latestAdvice.isNotBlank()) Text(group.latestAdvice)
            if (canWrite) {
                if (guess && !confirmed) TextButton(onClick = { onDecision(group.productKey, "confirm") }) {
                    Text(if (russian) "Подтвердить «не брать»" else "Confirm do not buy")
                }
                TextButton(onClick = { onDecision(group.productKey, "allow") }) {
                    Text(if (russian) "Можно брать" else "Allow purchase")
                }
                if (confirmed) TextButton(onClick = { onDecision(group.productKey, "revoke") }) {
                    Text(if (russian) "Отменить решение" else "Undo decision")
                }
            }
        }
    }
}

private fun shoppingClipboardText(shopping: FinanceShoppingList, russian: Boolean): String = buildString {
    shopping.candidates.forEach { appendLine("${it.productName} — ${it.estimatedCost} RUB") }
    append(if (russian) "Оценка списка: ${shopping.estimatedListCost} ₽"
        else "Estimated list cost: ${shopping.estimatedListCost} RUB")
}

@androidx.compose.runtime.Composable
private fun ProfileScreen(modifier: Modifier, state: FinanceUiState, language: String,
                          onSave: (String, String?) -> Unit, onRepeatSetup: (String, String?) -> Unit,
                          onCreateTelegramLink: () -> Unit,
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
        TextButton(modifier = Modifier.testTag("profile-repeat-setup"), enabled = !state.busy,
            onClick = { onRepeatSetup(profile.displayName, profile.plannedIncome) }) {
            Text(if (russian) "Пройти настройку заново" else "Repeat setup")
        }
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
                Checkbox(checked = dailyEnabled, onCheckedChange = { dailyEnabled = it }, enabled = !state.busy,
                    modifier = Modifier.testTag("daily-digest-enabled"))
                Text(if (russian) "Ежедневная сводка" else "Daily digest", Modifier.padding(top = 12.dp))
            }
            OutlinedTextField(dailyTime, { dailyTime = it }, enabled = !state.busy, singleLine = true,
                label = { Text(if (russian) "Время ежедневной сводки" else "Daily digest time") })
            Row {
                Checkbox(checked = weeklyEnabled, onCheckedChange = { weeklyEnabled = it }, enabled = !state.busy,
                    modifier = Modifier.testTag("weekly-digest-enabled"))
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
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (russian) "Сводка" else "Summary", style = MaterialTheme.typography.titleLarge)
        if (summary == null) {
            Text(if (russian) "Сводка пока не загружена" else "Summary is not loaded")
            return@Column
        }
        Text(if (russian) "За ${summary.month} · на ${summary.asOfDate}" else "${summary.month} · as of ${summary.asOfDate}")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (russian) "Доходы месяца: ${formatMoney(summary.incomeTotal, language)}"
                    else "Monthly income: ${formatMoney(summary.incomeTotal, language)}")
                Text(if (russian) "Расходы месяца: ${formatMoney(summary.expenseTotal, language)}"
                    else "Monthly expenses: ${formatMoney(summary.expenseTotal, language)}")
                Text(if (russian) "Операций: ${summary.transactionCount}" else "Transactions: ${summary.transactionCount}")
            }
        }
        state.report?.takeIf { report ->
            report.period == "month" && report.scope == "personal" &&
                report.fromDate.startsWith(summary.month) && report.toDate.startsWith(summary.month)
        }?.let { report ->
            if (report.monthlyBudgetLimit != null) {
                report.monthlyBudgetRemaining?.let { remaining ->
                    Text(if (russian) "Остаток лимита месяца: ${formatMoney(remaining, language, report.currency)}"
                        else "Monthly budget remaining: ${formatMoney(remaining, language, report.currency)}")
                }
            }
        }
        summary.dailyExpensePace?.let {
            Text(if (russian) "Средний расход в день: ${formatMoney(it, language)}"
                else "Daily expense pace: ${formatMoney(it, language)}")
        }
        summary.projectedExpenseTotal?.let {
            Text(if (russian) "Прогноз расходов за месяц: ${formatMoney(it, language)}"
                else "Projected monthly expenses: ${formatMoney(it, language)}")
        }
        summary.safeToSpend?.let { cash ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val incomeBasis = when (cash.incomeBasis) {
                        "actual_income" -> if (russian) "фактическому доходу" else "actual income"
                        "planned_income" -> if (russian) "плановому доходу" else "planned income"
                        else -> if (russian) "доходу" else "income"
                    }
                    Text(if (russian) "Расчёт по $incomeBasis: ${formatMoney(cash.incomeBase, language)}"
                        else "Calculated from $incomeBasis: ${formatMoney(cash.incomeBase, language)}")
                    Text(if (russian) "Расходы в расчёте: ${formatMoney(cash.monthlyExpenses, language)}"
                        else "Expenses used: ${formatMoney(cash.monthlyExpenses, language)}")
                    Text(if (russian) "Горизонт расчёта: ${cash.horizonDate} · ${cash.daysRemaining} дн."
                        else "Calculation horizon: ${cash.horizonDate} · ${cash.daysRemaining} days")
                    Text(if (russian) "Безопасно тратить в день: ${formatMoney(cash.safePerDay, language)}"
                        else "Safe to spend per day: ${formatMoney(cash.safePerDay, language)}")
                    Text(if (russian) "Свободно до ${cash.horizonDate}: ${formatMoney(cash.safeTotal, language)}"
                        else "Available through ${cash.horizonDate}: ${formatMoney(cash.safeTotal, language)}")
                    Text(if (russian) "Резерв 10%: ${formatMoney(cash.reserve, language)}"
                        else "10% reserve: ${formatMoney(cash.reserve, language)}")
                    Text(if (russian) "Обязательные списания: ${formatMoney(cash.promisedPayments, language)}"
                        else "Committed payments: ${formatMoney(cash.promisedPayments, language)}")
                }
            }
        }
        budget?.let {
            if (it.totalLimitStatus == "disabled") {
                Text(if (russian) "Лимит месяца отключён" else "Monthly budget disabled")
            } else {
                Text(if (russian) "Лимит месяца: ${formatMoney(it.effectiveTotalLimit, language)} · потрачено " +
                    "${formatMoney(it.totalMonthlySpent, language)} · ${formatSemanticStatus("limitStatus", it.totalLimitStatus, language)}"
                else "Monthly budget: ${formatMoney(it.effectiveTotalLimit, language)} · spent " +
                    "${formatMoney(it.totalMonthlySpent, language)} · ${formatSemanticStatus("limitStatus", it.totalLimitStatus, language)}")
                LinearProgressIndicator(progress = { amountFraction(it.totalMonthlySpent, it.effectiveTotalLimit) },
                    modifier = Modifier.fillMaxWidth().testTag("dashboard-month-budget-progress"))
            }
            val food = summary.rolling7FoodStatus
            Text(formatRollingFoodStatus(food, language))
        }
    }
}

private fun transactionDateValidationError(from: String, to: String, russian: Boolean): String? {
    val fromValue = from.trim()
    val toValue = to.trim()
    val parsedFrom = fromValue.takeIf(String::isNotEmpty)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val parsedTo = toValue.takeIf(String::isNotEmpty)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return when {
        fromValue.isNotEmpty() && parsedFrom == null || toValue.isNotEmpty() && parsedTo == null ->
            if (russian) "Введите дату в формате ГГГГ-ММ-ДД" else "Enter dates as YYYY-MM-DD"
        parsedFrom != null && parsedTo != null && parsedFrom.isAfter(parsedTo) ->
            if (russian) "Дата начала должна быть не позже даты окончания"
            else "Start date must be on or before end date"
        else -> null
    }
}

@androidx.compose.runtime.Composable
private fun ReportScreen(state: FinanceUiState, language: String,
                         onLoad: (String, String, String, String, String) -> Unit,
                         onRecalculationPreview: () -> Unit,
                         onRecalculationApply: (String) -> Unit,
                         onRecalculationHistory: (String?) -> Unit,
                         onRecalculationDetail: (String, String?) -> Unit) {
    val russian = language == "ru"
    val initialMonth = state.report?.fromDate?.take(7) ?: YearMonth.now().toString()
    var period by androidx.compose.runtime.remember { mutableStateOf("month") }
    var scope by androidx.compose.runtime.remember {
        mutableStateOf(if (state.report?.scope == "family") "family" else "personal")
    }
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
        Button(onClick = {
            onLoad(period, scope, month, if (period == "custom") from else "",
                if (period == "custom") to else "")
        },
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
            val optionalDailyMaximum = report.waste.optionalByDay.values.maxByOrNull {
                it.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            } ?: "0.00"
            LazyColumn(Modifier.weight(1f).testTag("report-results"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (russian) "Доходы: ${formatMoney(report.incomeTotal, language, report.currency)}"
                                else "Income: ${formatMoney(report.incomeTotal, language, report.currency)}")
                            Text(if (russian) "Расходы: ${formatMoney(report.expenseTotal, language, report.currency)}"
                                else "Expenses: ${formatMoney(report.expenseTotal, language, report.currency)}")
                            Text(if (russian) "Платежи по долгам: ${formatMoney(report.debtPaymentTotal, language, report.currency)}"
                                else "Debt payments: ${formatMoney(report.debtPaymentTotal, language, report.currency)}")
                            Text(if (russian) "Возвраты: ${formatMoney(report.refundTotal, language, report.currency)}"
                                else "Refunds: ${formatMoney(report.refundTotal, language, report.currency)}")
                            Text(if (russian) "Операций: ${report.transactionCount}" else "Transactions: ${report.transactionCount}")
                            report.weekendSharePercent?.let {
                                Text(if (russian) "Доля расходов в выходные: $it%" else "Weekend expense share: $it%")
                            }
                            report.monthlyBudgetLimit?.let { limit ->
                                Text(if (russian) "Лимит месяца: ${formatMoney(limit, language, report.currency)} · остаток " +
                                    formatMoney(report.monthlyBudgetRemaining, language, report.currency)
                                else "Monthly budget: ${formatMoney(limit, language, report.currency)} · remaining " +
                                    formatMoney(report.monthlyBudgetRemaining, language, report.currency))
                            }
                            if (report.scope == scope) {
                                Text(formatRollingFoodStatus(report.rolling7FoodStatus, language, report.currency))
                            }
                        }
                    }
                }
                item {
                    val waste = report.waste
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (russian) "Необязательные покупки" else "Optional purchases",
                                style = MaterialTheme.typography.titleMedium)
                            if (!waste.available) {
                                Text(wasteUnavailableMessage(waste, russian))
                            } else {
                                val rawShare = waste.optionalShare?.toBigDecimalOrNull()?.multiply(java.math.BigDecimal(100))
                                    ?.setScale(1, java.math.RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()
                                val share = rawShare?.let { if (russian) it.replace('.', ',') else it } ?: "—"
                                val optional = formatMoney(waste.optionalSpend, language, report.currency)
                                val reviewed = formatMoney(waste.reviewedSpend, language, report.currency)
                                Text(if (russian) "Необязательные покупки: $optional · $share% от проверенных $reviewed"
                                else "Optional purchases: $optional · $share% of reviewed $reviewed")
                                Text(if (russian) "Проверено: ${waste.reviewedItemCount} · необязательных: ${waste.optionalItemCount}"
                                else "Reviewed: ${waste.reviewedItemCount} · optional: ${waste.optionalItemCount}")
                                waste.bySource.toSortedMap().forEach { (source, amount) ->
                                    Text(if (russian) "Источник $source: ${formatMoney(amount, language, report.currency)}"
                                        else "Source $source: ${formatMoney(amount, language, report.currency)}")
                                }
                                waste.topItems.forEach { item ->
                                    Text("${item.name}: ${formatMoney(item.amount, language, report.currency)} · ${item.source}")
                                }
                                waste.corrected.forEach { item ->
                                    Text(if (russian) "Исправлено: ${item.productName} · ${formatMoney(item.amount, language, report.currency)}"
                                        else "Corrected: ${item.productName} · ${formatMoney(item.amount, language, report.currency)}")
                                }
                            }
                        }
                    }
                }
                items(report.expenseByCategory.toSortedMap().entries.toList()) { entry ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${entry.key}: ${formatMoney(entry.value, language, report.currency)}")
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
                        Text("${entry.key}: ${formatMoney(entry.value, language, report.currency)}")
                        LinearProgressIndicator(progress = { amountFraction(entry.value, dailyMaximum) },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                if (report.waste.available && report.waste.optionalByDay.isNotEmpty()) {
                    item {
                        Text(if (russian) "Необязательные покупки по дням" else "Optional purchases by day",
                            style = MaterialTheme.typography.titleMedium)
                    }
                    items(report.waste.optionalByDay.toSortedMap().entries.toList()) { entry ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${entry.key}: ${formatMoney(entry.value, language, report.currency)}")
                            LinearProgressIndicator(progress = { amountFraction(entry.value, optionalDailyMaximum) },
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                item {
                    ReceiptRecalculationSection(
                        role = state.tenants.firstOrNull()?.role,
                        language = language,
                        currency = state.report?.currency ?: state.memberProfile?.currency ?: "RUB",
                        preview = state.receiptRecalculationPreview,
                        applied = state.receiptRecalculationApplyResult,
                        history = state.receiptRecalculationHistory,
                        selectedDetail = state.receiptRecalculationDetail,
                        busy = state.receiptRecalculationBusy,
                        error = state.receiptRecalculationError,
                        onPreview = onRecalculationPreview,
                        onApply = onRecalculationApply,
                        onLoadHistory = onRecalculationHistory,
                        onLoadDetail = onRecalculationDetail,
                    )
                }
            }
        }
    }
}

private fun wasteUnavailableMessage(waste: FinanceWasteReport, russian: Boolean): String = when (waste.reasonCode) {
    "missing_amounts" -> if (russian) "Не все позиции чеков имеют сумму (${waste.missingAmountCount}); итоги не рассчитаны."
        else "Some receipt items have no amount (${waste.missingAmountCount}); totals not calculated."
    "no_reviewed_items" -> if (russian) "Нет проверенных позиций чеков за период." else "No reviewed receipt items for this period."
    "too_many_items" -> if (russian) "Слишком много позиций чеков для анализа." else "Too many receipt items to analyze."
    else -> if (russian) "Аналитика временно недоступна." else "Analytics is temporarily unavailable."
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
private fun BudgetScreen(modifier: Modifier, state: FinanceUiState, language: String,
                         onUpdate: (String, String, String, String, Long) -> Unit,
                         onReset: () -> Unit, onPropose: (String?) -> Unit, onApply: (String) -> Unit,
                         onFamilyFoodStatusLoad: (String) -> Unit) {
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
    val familyScope = scope == "family"
    androidx.compose.runtime.LaunchedEffect(familyScope, budget.month, tenant?.id,
        state.familyBudgetFoodRefreshToken) {
        if (familyScope && tenant != null) onFamilyFoodStatusLoad(budget.month)
    }
    val displayedTotalLimit = if (familyScope) budget.familyTotalLimit else budget.effectiveTotalLimit
    val displayedCategoryLimits = if (familyScope) budget.familyLimits else budget.effectiveLimits
    val rollingFoodLimit = if (familyScope) budget.rolling7FoodLimit else budget.rolling7FoodStatus.limit
    val totalLimitStatus = if (familyScope) "" else " · ${formatSemanticStatus("limitStatus", budget.totalLimitStatus, language)}"
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (russian) "Лимиты · ${budget.month}" else "Limits · ${budget.month}", style = MaterialTheme.typography.titleLarge)
        Text(if (familyScope) {
            if (russian) "Семейный лимит за месяц: ${formatMoney(displayedTotalLimit, language)}"
            else "Family monthly limit: ${formatMoney(displayedTotalLimit, language)}"
        } else {
            if (russian) "Лимит за месяц: ${formatMoney(displayedTotalLimit, language)} · потрачено " +
                "${formatMoney(budget.totalMonthlySpent, language)}$totalLimitStatus"
            else "Monthly limit: ${formatMoney(displayedTotalLimit, language)} · spent " +
                "${formatMoney(budget.totalMonthlySpent, language)}$totalLimitStatus"
        })
        if (familyScope) {
            val familyFood = state.familyBudgetFoodStatus?.takeIf {
                state.familyBudgetFoodTenantId == tenant?.id && state.familyBudgetFoodMonth == budget.month
            }
            if (familyFood != null) Text(formatRollingFoodStatus(familyFood, language))
            else Text(if (state.familyBudgetFoodLoading) {
                if (russian) "Загружаем семейный отчёт по еде…" else "Loading family food report…"
            } else if (state.familyBudgetFoodError != null) {
                if (russian) "Семейный отчёт по еде недоступен" else "Family food report is unavailable"
            } else if (russian) "Семейный отчёт по еде недоступен" else "Family food report is unavailable")
        } else {
            Text(formatRollingFoodStatus(budget.rolling7FoodStatus, language))
        }
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
            Text(if (russian) "Предложен лимит ${formatMoney(proposal.totalLimit, language)} · ${proposal.proposalSource} · ${proposal.historyDays} дн."
            else "Suggested total ${formatMoney(proposal.totalLimit, language)} · ${proposal.proposalSource} · ${proposal.historyDays} days")
        }
        displayedCategoryLimits.toSortedMap().forEach { (key, limit) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(key, style = MaterialTheme.typography.titleMedium)
                    if (familyScope) {
                        Text(if (russian) "Семейный лимит: ${formatMoney(limit, language)}"
                            else "Family limit: ${formatMoney(limit, language)}")
                    } else {
                        val status = budget.limitStatus[key] ?: "disabled"
                        val statusSuffix = " · ${formatSemanticStatus("limitStatus", status, language)}"
                        Text(if (russian) "Действует ${formatMoney(limit, language)} · потрачено " +
                            "${formatMoney(budget.monthlySpent[key] ?: "0.00", language)}$statusSuffix"
                            else "Effective ${formatMoney(limit, language)} · spent " +
                                "${formatMoney(budget.monthlySpent[key] ?: "0.00", language)}$statusSuffix")
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
        LazyColumn(Modifier.weight(1f).testTag("debt-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.debts) { debt ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(debt.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (russian) "Остаток ${debt.currentBalance} ₽ · ${if (debt.status == "open") "открыт" else "закрыт"} · версия ${debt.version}"
                        else "Balance ${debt.currentBalance} RUB · ${debt.status} · version ${debt.version}")
                        val rate = debt.interestRate ?: "0.0000"
                        val localizedRate = if (russian) rate.replace('.', ',') else rate
                        Text(if (russian) "Ставка: $localizedRate% · Минимальный платёж: ${formatMoney(debt.minimumPayment, language)}"
                        else "Interest rate: $localizedRate% · Minimum payment: ${formatMoney(debt.minimumPayment, language)}")
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
                            val months = if (russian) {
                                forecast.monthsToPayoff?.let { "$it мес." } ?: "Срок не рассчитан"
                            } else {
                                forecast.monthsToPayoff?.let { "$it months" } ?: "No estimate"
                            }
                            Text("$months · ${formatDebtForecastBasis(forecast.estimateBasis, language)}")
                        }
                    }
                }
            }
        }
    }
}
