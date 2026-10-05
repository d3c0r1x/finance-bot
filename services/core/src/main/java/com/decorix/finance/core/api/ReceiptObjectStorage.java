package com.decorix.finance.core.api;

public interface ReceiptObjectStorage {
    void put(String key, byte[] body, String contentType);

    byte[] get(String key);

    void delete(String key);
}
