package com.decorix.finance

import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

internal data class FinanceExportRequest(
    val formatVersion: String,
    val fromDate: String,
    val toDate: String,
    val memberId: String?,
) {
    init {
        require(formatVersion == "csv-v1") { "Unsupported export format" }
        val from = parseExportDate(fromDate)
        val to = parseExportDate(toDate)
        require(!to.isBefore(from) && ChronoUnit.DAYS.between(from, to) < 366) {
            "Export period must contain 1 to 366 days"
        }
        require(memberId == null || memberId == "all" || isExportUuid(memberId)) { "Invalid export member scope" }
    }

    internal fun json(): JSONObject = JSONObject()
        .put("formatVersion", formatVersion)
        .put("fromDate", fromDate)
        .put("toDate", toDate)
        .also { json -> memberId?.let { json.put("memberId", it) } }
}

data class FinanceExportJob(
    val id: String,
    val status: String,
    val formatVersion: String,
    val fromDate: String,
    val toDate: String,
    val memberId: String?,
    val allMembers: Boolean,
    val rowCount: Int,
    val snapshotAt: String,
    val createdAt: String,
    val expiresAt: String,
    val downloadUrl: String?,
)

internal data class FinanceExportDownloadAttempt(
    val tenantId: String,
    val exportId: String,
    val sessionGeneration: Long,
    val generation: Long,
)

internal data class FinanceExportDownloadContext(
    val tenantId: String?,
    val exportId: String?,
    val sessionGeneration: Long,
    val generation: Long,
    val authenticated: Boolean,
    val screenActive: Boolean,
    val screenResumed: Boolean,
)

internal data class FinanceExportStatusIdentity(val tenantId: String, val sessionGeneration: Long)

internal data class FinanceExportStatusRequest(
    val generation: Long,
    val identity: FinanceExportStatusIdentity,
)

internal data class FinanceExportStatusCompletion(
    val publishStatus: Boolean,
    val openDownload: Boolean,
    val clearBusy: Boolean,
)

internal object FinanceExportStatusPolicy {
    fun isCurrent(
        request: FinanceExportStatusRequest,
        currentGeneration: Long,
        currentIdentity: FinanceExportStatusIdentity?,
        screenActive: Boolean,
    ): Boolean = screenActive && request.generation == currentGeneration &&
        request.identity == currentIdentity

    fun ownsStatusBusy(request: FinanceExportStatusRequest, currentGeneration: Long, busyKind: String?): Boolean =
        request.generation == currentGeneration && busyKind == "status"

    fun completion(
        request: FinanceExportStatusRequest,
        currentGeneration: Long,
        currentIdentity: FinanceExportStatusIdentity?,
        screenActive: Boolean,
        authenticated: Boolean,
        currentExportId: String?,
        requestedExportId: String,
        currentSessionGeneration: Long,
        busyKind: String?,
        responseStatus: String?,
        responseDownloadUrl: String?,
    ): FinanceExportStatusCompletion {
        val publishStatus = isCurrent(request, currentGeneration, currentIdentity, screenActive) &&
            authenticated && request.identity.sessionGeneration == currentSessionGeneration &&
            currentIdentity?.tenantId == request.identity.tenantId && currentExportId == requestedExportId
        return FinanceExportStatusCompletion(
            publishStatus = publishStatus,
            openDownload = publishStatus && responseStatus == "ready" && responseDownloadUrl != null,
            clearBusy = ownsStatusBusy(request, currentGeneration, busyKind),
        )
    }
}

internal object FinanceExportDownloadPolicy {
    fun isCurrent(attempt: FinanceExportDownloadAttempt, context: FinanceExportDownloadContext): Boolean =
        context.authenticated && context.screenActive && context.screenResumed &&
            attempt.tenantId == context.tenantId && attempt.exportId == context.exportId &&
            attempt.sessionGeneration == context.sessionGeneration && attempt.generation == context.generation

    fun freshUrlIfCurrent(
        attempt: FinanceExportDownloadAttempt,
        context: FinanceExportDownloadContext,
        status: String,
        downloadUrl: String?,
    ): String? {
        if (!isCurrent(attempt, context) || status != "ready") return null
        val url = downloadUrl ?: return null
        return runCatching { FinanceExportModels.requireSafeDownloadUrl(url) }.fold(
            onSuccess = { url },
            onFailure = { null },
        )
    }
}

internal object FinanceExportModels {
    fun request(fromDate: String, toDate: String, memberId: String? = null): FinanceExportRequest =
        FinanceExportRequest("csv-v1", fromDate, toDate, memberId)

    fun status(json: JSONObject, requestedExportId: String): FinanceExportJob = job(json).also {
        require(it.id.equals(requestedExportId, ignoreCase = true)) {
            "Export response identifier does not match request"
        }
    }

    fun job(json: JSONObject): FinanceExportJob {
        val id = json.getString("id")
        val status = json.getString("status")
        val format = json.getString("formatVersion")
        require(isExportUuid(id)) { "Invalid export identifier" }
        require(status in STATUSES) { "Unsupported export status" }
        require(format == "csv-v1") { "Unsupported export format" }

        val from = json.getString("fromDate")
        val to = json.getString("toDate")
        FinanceExportRequest(format, from, to, null)
        require(json.has("memberId")) { "Missing export member scope" }
        val member = json.opt("memberId")?.let { value ->
            if (value == JSONObject.NULL) null else value as? String ?: error("Invalid export member scope")
        }
        val allMembers = json.get("allMembers") as? Boolean ?: error("Invalid export member scope")
        require(if (allMembers) member == null else member != null && isExportUuid(member)) {
            "Invalid export member scope"
        }
        val countValue = json.get("rowCount")
        val rowCount = countValue as? Int ?: error("Invalid export row count")
        require(rowCount >= 0) { "Invalid export row count" }

        val snapshotAt = json.getString("snapshotAt").also { Instant.parse(it) }
        val createdAt = json.getString("createdAt").also { Instant.parse(it) }
        val expiresAt = json.getString("expiresAt").also { Instant.parse(it) }
        require(json.has("downloadUrl")) { "Missing export download URL field" }
        val rawUrl = json.opt("downloadUrl")
        val downloadUrl = when {
            rawUrl == null || rawUrl == JSONObject.NULL -> null
            status != "ready" -> error("Download is unavailable before export is ready")
            else -> (rawUrl as? String)?.also(::requireSafeDownloadUrl)
                ?: error("Invalid export download URL")
        }
        return FinanceExportJob(id, status, format, from, to, member, allMembers, rowCount,
            snapshotAt, createdAt, expiresAt, downloadUrl)
    }

    fun requireSafeDownloadUrl(value: String) {
        val uri = runCatching { URI(value) }.getOrElse { error("Invalid export download URL") }
        require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null) {
            "Invalid export download URL"
        }
    }

    private val STATUSES = setOf("queued", "processing", "ready", "failed", "expired")
}

internal fun financeExportErrorCode(error: Throwable): String {
    val status = (error as? ApiFailure)?.status
    return when {
        status == 400 -> "invalid_request"
        status == 401 -> "unauthorized"
        status == 403 -> "forbidden"
        status == 413 -> "too_many_rows"
        status != null && status in 500..599 -> "unavailable"
        else -> "network"
    }
}

internal object FinanceExportPollingPolicy {
    const val INTERVAL_MILLIS = 5_000L
    const val MAX_POLLS_PER_WINDOW = 12

    fun shouldPoll(status: String?, completedPolls: Int, screenResumed: Boolean): Boolean =
        screenResumed && status in setOf("queued", "processing") && completedPolls in 0 until MAX_POLLS_PER_WINDOW
}

private fun parseExportDate(value: String): LocalDate = try {
    LocalDate.parse(value).also { require(it.toString() == value) }
} catch (_: Exception) {
    throw IllegalArgumentException("Invalid export date")
}

private fun isExportUuid(value: String): Boolean =
    runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }.getOrDefault(false)
