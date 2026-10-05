package com.decorix.finance.core.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

final class TelegramLinkCode {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 16;

    private TelegramLinkCode() {}

    static String generate(SecureRandom random) {
        StringBuilder raw = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            raw.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12)
                + "-" + raw.substring(12, 16);
    }

    static String normalize(String code) {
        String normalized = code == null ? "" : code.trim().replace("-", "").toUpperCase(Locale.ROOT);
        if (normalized.length() != CODE_LENGTH) {
            throw new IllegalArgumentException("Invalid Telegram link code");
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (ALPHABET.indexOf(normalized.charAt(i)) < 0) {
                throw new IllegalArgumentException("Invalid Telegram link code");
            }
        }
        return normalized;
    }

    static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalize(code).getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
