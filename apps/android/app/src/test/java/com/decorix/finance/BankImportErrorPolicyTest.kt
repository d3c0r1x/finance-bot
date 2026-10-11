package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class BankImportErrorPolicyTest {
    @Test fun preservesOnlyKnownParserCodesForLocalizedCopy() {
        listOf("invalid_pdf", "invalid_format", "no_text", "no_operations", "invalid_totals", "invalid_operation")
            .forEach { code -> assertEquals(code, BankImportErrorPolicy.code(422, code)) }
    }

    @Test fun mapsTransportStatusesAndNeverReturnsRawUnknownServerBody() {
        assertEquals("file_size", BankImportErrorPolicy.code(413, "upstream-private details"))
        assertEquals("file_size", BankImportErrorPolicy.code(null, "file_size"))
        assertEquals("empty_file", BankImportErrorPolicy.code(null, "empty_file"))
        assertEquals("unauthorized", BankImportErrorPolicy.code(401, "upstream-private details"))
        assertEquals("forbidden", BankImportErrorPolicy.code(403, "upstream-private details"))
        assertEquals("unavailable", BankImportErrorPolicy.code(503, "db password leaked"))
        assertEquals("request_failed", BankImportErrorPolicy.code(422, "db password leaked"))
    }
}
