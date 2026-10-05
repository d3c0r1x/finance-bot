package com.decorix.finance.core.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class TelegramServiceCredential {
    private TelegramServiceCredential() {}

    static boolean matches(String configuredToken, String suppliedToken) {
        if (configuredToken == null || configuredToken.isBlank() || suppliedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(configuredToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8));
    }
}
