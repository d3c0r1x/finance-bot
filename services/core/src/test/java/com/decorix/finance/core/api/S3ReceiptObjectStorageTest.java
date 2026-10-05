package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class S3ReceiptObjectStorageTest {
    @Test
    void usesPrivatePathStyleS3RequestsForUploadReadAndDelete() throws Exception {
        byte[] image = new byte[] {8, 9, 10};
        List<String> methods = new ArrayList<>();
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/private-receipts/quarantine/tenant/receipt.png", exchange -> {
            methods.add(exchange.getRequestMethod());
            if ("PUT".equals(exchange.getRequestMethod())) {
                uploaded.set(exchange.getRequestBody().readAllBytes());
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                exchange.sendResponseHeaders(200, -1);
            } else if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, image.length);
                exchange.getResponseBody().write(image);
            } else {
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.start();
        var storage = new S3ReceiptObjectStorage("http://127.0.0.1:" + server.getAddress().getPort(),
                "us-east-1", "private-receipts", "test-access", "test-secret", true);
        try {
            String key = "quarantine/tenant/receipt.png";
            storage.put(key, image, "image/png");
            assertArrayEquals(image, uploaded.get());
            assertEquals("image/png", contentType.get());
            assertArrayEquals(image, storage.get(key));
            storage.delete(key);
            assertEquals(List.of("PUT", "GET", "DELETE"), methods);
            assertThrows(IllegalArgumentException.class,
                    () -> storage.get("../private-receipts/receipt.png"));
        } finally {
            storage.close();
            server.stop(0);
        }
    }
}
