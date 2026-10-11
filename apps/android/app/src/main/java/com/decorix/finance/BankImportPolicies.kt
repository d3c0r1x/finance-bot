package com.decorix.finance

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object BankImportFilePolicy {
    const val MAX_PDF_BYTES = 12 * 1024 * 1024
    private val pdfHeader = "%PDF-".toByteArray(Charsets.US_ASCII)

    /** Reads at most the configured limit plus one byte, so a provider cannot force an unbounded read. */
    fun readBounded(input: InputStream, maxBytes: Int = MAX_PDF_BYTES): ByteArray {
        require(maxBytes > 0) { "Invalid file limit" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var remaining = maxBytes + 1
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                output.write(single)
                remaining--
            } else {
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        val bytes = output.toByteArray()
        require(bytes.size <= maxBytes) { "file_size" }
        require(bytes.isNotEmpty()) { "empty_file" }
        require(bytes.startsWith(pdfHeader)) { "invalid_pdf" }
        return bytes
    }

    fun validateUpload(bytes: ByteArray, fileName: String) {
        require(bytes.isNotEmpty()) { "empty_file" }
        require(bytes.size <= MAX_PDF_BYTES) { "file_size" }
        require(fileName.substringAfterLast('/').substringAfterLast('\\')
            .lowercase().endsWith(".pdf")) { "invalid_pdf" }
        require(bytes.startsWith(pdfHeader)) { "invalid_pdf" }
    }

    fun safeFileName(candidate: String?): String {
        val name = candidate.orEmpty().replace('\\', '/').substringAfterLast('/')
            .filterNot(Char::isISOControl).take(180)
        return name.takeIf { it.isNotBlank() && it.lowercase().endsWith(".pdf") } ?: "statement.pdf"
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

internal object BankImportResponsePolicy {
    fun canCachePreview(
        requestAuthGeneration: Long,
        currentAuthGeneration: Long,
        requestTenantId: String,
        currentTenantId: String?,
    ): Boolean = requestAuthGeneration == currentAuthGeneration && requestTenantId == currentTenantId

    fun canApply(
        requestAuthGeneration: Long,
        currentAuthGeneration: Long,
        requestTenantId: String,
        currentTenantId: String?,
        requestGeneration: Long,
        currentGeneration: Long,
        screenActive: Boolean,
    ): Boolean = requestAuthGeneration == currentAuthGeneration &&
        requestTenantId == currentTenantId && requestGeneration == currentGeneration && screenActive
}

internal object BankImportErrorPolicy {
    private val parserCodes = setOf(
        "invalid_pdf", "invalid_format", "no_text", "no_operations", "invalid_totals", "invalid_operation",
        "file_size", "empty_file",
    )

    fun code(status: Int?, detail: String?): String = when {
        status == 413 -> "file_size"
        status == 401 -> "unauthorized"
        status == 403 -> "forbidden"
        detail in parserCodes -> requireNotNull(detail)
        status != null && status >= 500 -> "unavailable"
        else -> "request_failed"
    }
}
