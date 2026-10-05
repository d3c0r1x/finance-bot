package com.decorix.finance.core.api;

final class ReceiptObjectKeys {
    private ReceiptObjectKeys() {}

    static void validate(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.(?:png|jpg)")) {
            throw new IllegalArgumentException("Receipt object key is invalid");
        }
    }
}
