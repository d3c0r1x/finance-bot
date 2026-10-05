package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class TelegramLinkCodeTest {
    private final SecureRandom random = new SecureRandom();

    @Test
    void generatesHighEntropyReadableCodeAndHashesNormalizedValue() {
        String first = TelegramLinkCode.generate(random);
        String second = TelegramLinkCode.generate(random);

        assertEquals(19, first.length());
        assertTrue(first.matches("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){3}"));
        assertNotEquals(first, second);
        assertEquals(TelegramLinkCode.hash(first), TelegramLinkCode.hash(first.toLowerCase()));
    }

    @Test
    void rejectsMalformedCodesBeforeHashing() {
        assertThrows(IllegalArgumentException.class, () -> TelegramLinkCode.hash(""));
        assertThrows(IllegalArgumentException.class, () -> TelegramLinkCode.hash("1234-O0OO"));
        assertThrows(IllegalArgumentException.class, () -> TelegramLinkCode.normalize("short"));
    }
}
