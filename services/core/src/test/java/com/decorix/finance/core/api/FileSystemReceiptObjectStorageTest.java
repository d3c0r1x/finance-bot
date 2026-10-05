package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemReceiptObjectStorageTest {
    @TempDir
    Path root;

    @Test
    void storesReadsAndDeletesOnlyUnderPrivateRoot() throws Exception {
        var storage = new FileSystemReceiptObjectStorage(root);
        String key = "quarantine/tenant/receipt.png";
        byte[] body = new byte[] {1, 2, 3};

        storage.put(key, body, "image/png");
        assertArrayEquals(body, storage.get(key));
        assertTrue(Files.exists(root.resolve(key)));
        storage.delete(key);
        assertFalse(Files.exists(root.resolve(key)));
    }

    @Test
    void rejectsTraversalAndAbsoluteKeys() {
        var storage = new FileSystemReceiptObjectStorage(root);

        assertThrows(IllegalArgumentException.class, () -> storage.put("../outside.png", new byte[] {1}, "image/png"));
        assertThrows(IllegalArgumentException.class, () -> storage.get(root.resolve("outside.png").toString()));
    }
}
