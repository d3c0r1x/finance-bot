package com.decorix.finance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class BankImportFilePolicyTest {
    @Test fun acceptsPdfAtExactLimitAndReturnsOriginalBytes() {
        val bytes = pdfBytes(12)

        val result = BankImportFilePolicy.readBounded(ByteArrayInputStream(bytes), bytes.size)

        assertArrayEquals(bytes, result)
    }

    @Test fun rejectsFirstByteBeyondLimitWithoutReadingUnboundedInput() {
        val bytes = pdfBytes(13)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            BankImportFilePolicy.readBounded(ByteArrayInputStream(bytes), 12)
        }

        assertEquals("file_size", failure.message)
    }

    @Test fun rejectsEmptyAndNonPdfDocuments() {
        val empty = assertThrows(IllegalArgumentException::class.java) {
            BankImportFilePolicy.readBounded(ByteArrayInputStream(ByteArray(0)), 100)
        }
        val wrongFormat = assertThrows(IllegalArgumentException::class.java) {
            BankImportFilePolicy.readBounded(ByteArrayInputStream("not a PDF".toByteArray()), 100)
        }

        assertEquals("empty_file", empty.message)
        assertEquals("invalid_pdf", wrongFormat.message)
    }

    private fun pdfBytes(size: Int): ByteArray = ByteArray(size).also {
        "%PDF-".toByteArray().copyInto(it)
    }
}
