package com.decorix.finance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptOperationGenerationTest {
    @Test fun invalidatedLogoutGenerationRejectsOldWorkAndAllowsFreshWork() {
        val beforeLogout = ReceiptOperationGeneration.capture()
        assertTrue(ReceiptOperationGeneration.isCurrent(beforeLogout))

        ReceiptOperationGeneration.invalidate()

        assertFalse(ReceiptOperationGeneration.isCurrent(beforeLogout))
        val afterLogout = ReceiptOperationGeneration.capture()
        assertTrue(ReceiptOperationGeneration.isCurrent(afterLogout))
        assertFalse(ReceiptOperationGeneration.isCurrent(beforeLogout))
    }
}
