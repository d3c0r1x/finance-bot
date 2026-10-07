package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class S3ExportDownloadSignerTest {
    @Test
    void createsFiveMinuteSignedGetUrlForValidatedExportObject() {
        try (var signer = new S3ExportDownloadSigner("http://127.0.0.1:8333", "us-east-1",
                "finance-exports-private", "access", "secret", true)) {
            String key = "tenants/00000000-0000-4000-8000-000000000002/exports/"
                    + "00000000-0000-4000-8000-000000000001/00000000-0000-4000-8000-000000000004.csv";
            URI uri = URI.create(signer.sign(key, Duration.ofMinutes(5)));
            var query = java.util.Arrays.stream(uri.getRawQuery().split("&"))
                    .map(part -> part.split("=", 2)).collect(java.util.stream.Collectors.toMap(
                            item -> item[0], item -> item.length == 2 ? item[1] : ""));
            assertEquals("/finance-exports-private/" + key, uri.getRawPath());
            assertEquals("AWS4-HMAC-SHA256", query.get("X-Amz-Algorithm"));
            assertEquals("300", query.get("X-Amz-Expires"));
            assertFalse(query.getOrDefault("X-Amz-Signature", "").isBlank());
            assertEquals("attachment; filename=\"finance-export.csv\"",
                    java.net.URLDecoder.decode(query.get("response-content-disposition"), java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> signer.sign(key, Duration.ofMinutes(6)));
            assertThrows(IllegalArgumentException.class, () -> signer.sign(key, Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> signer.sign(key, Duration.ofMillis(999)));
        }
    }

    @Test
    void rejectsInvalidS3ConfigurationAndNonTenantExportKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> new S3ExportDownloadSigner("file:///tmp", "us-east-1", "private", "a", "b", true));
        try (var signer = new S3ExportDownloadSigner("http://127.0.0.1:8333", "us-east-1",
                "finance-exports-private", "access", "secret", true)) {
            assertThrows(IllegalArgumentException.class, () -> signer.sign("public/report.csv", Duration.ofMinutes(1)));
        }
    }
}
