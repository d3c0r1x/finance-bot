package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TelegramAccessPolicyTest {
    @Test
    void allowsOnlyConfiguredPositiveTelegramIdsAndExposesExplicitAccountMapping() {
        TelegramAccessPolicy policy = new TelegramAccessPolicy("42, 73", "42=android-subject");

        assertTrue(policy.isAllowed(42L));
        assertTrue(policy.isAllowed(73L));
        assertFalse(policy.isAllowed(99L));
        assertFalse(policy.isAllowed(null));
        assertEquals("android-subject", policy.accountSubject(42L).orElseThrow());
        assertTrue(policy.accountSubject(73L).isEmpty());
    }

    @Test
    void rejectsDuplicateAllowedIdsAndDuplicateConfiguredMappings() {
        assertThrows(IllegalArgumentException.class,
                () -> new TelegramAccessPolicy("42,42", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new TelegramAccessPolicy("42", "42=first,42=second"));
        assertThrows(IllegalArgumentException.class,
                () -> new TelegramAccessPolicy("42,73", "42=same-subject,73=same-subject"));
    }

    @Test
    void rejectsMalformedIdsAndMappingsThatAreNotAllowlisted() {
        assertThrows(IllegalArgumentException.class,
                () -> new TelegramAccessPolicy("42,not-a-number", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new TelegramAccessPolicy("0", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new TelegramAccessPolicy("42", "73=android-subject"));
        assertThrows(IllegalArgumentException.class,
                () -> new TelegramAccessPolicy("42", "42="));
    }
}
