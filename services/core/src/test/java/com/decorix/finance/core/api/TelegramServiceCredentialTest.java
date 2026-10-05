package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TelegramServiceCredentialTest {
    @Test
    void acceptsOnlyTheConfiguredNonBlankCredential() {
        assertTrue(TelegramServiceCredential.matches("secret-token", "secret-token"));
        assertFalse(TelegramServiceCredential.matches("secret-token", "wrong-token"));
        assertFalse(TelegramServiceCredential.matches("secret-token", null));
        assertFalse(TelegramServiceCredential.matches("", "secret-token"));
        assertFalse(TelegramServiceCredential.matches("   ", "   "));
    }
}
