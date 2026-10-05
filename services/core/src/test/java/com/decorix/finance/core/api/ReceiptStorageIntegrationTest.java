package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;

class ReceiptStorageIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "FINANCE_RECEIPT_IT_S3_ENDPOINT", matches = "https?://.+")
    void seaweedS3RoundTripsObjectsAndRejectsAnonymousReads() throws Exception {
        String endpoint = System.getenv("FINANCE_RECEIPT_IT_S3_ENDPOINT");
        String bucket = "finance-receipt-it-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "quarantine/tenant/" + UUID.randomUUID() + ".png";
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                System.getenv("FINANCE_RECEIPT_IT_S3_ACCESS_KEY"),
                System.getenv("FINANCE_RECEIPT_IT_S3_SECRET_KEY")));
        S3Configuration configuration = S3Configuration.builder().pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false).build();
        try (S3Client setup = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of("us-east-1"))
                .credentialsProvider(credentials).serviceConfiguration(configuration).build()) {
            setup.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            try (var storage = new S3ReceiptObjectStorage(endpoint, "us-east-1", bucket,
                    System.getenv("FINANCE_RECEIPT_IT_S3_ACCESS_KEY"),
                    System.getenv("FINANCE_RECEIPT_IT_S3_SECRET_KEY"), true)) {
                byte[] image = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
                storage.put(key, image, "image/png");
                assertArrayEquals(image, storage.get(key));

                HttpRequest anonymousRead = HttpRequest.newBuilder(URI.create(endpoint + "/" + bucket + "/" + key))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                HttpResponse<Void> response = HttpClient.newHttpClient().send(anonymousRead,
                        HttpResponse.BodyHandlers.discarding());
                assertTrue(response.statusCode() == 401 || response.statusCode() == 403,
                        "receipt objects must reject anonymous reads");
                storage.delete(key);
            }
            setup.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FINANCE_RECEIPT_IT_CLAMAV_HOST", matches = ".+")
    void clamAvAcceptsCleanContentAndDetectsEicar() {
        var scanner = new ClamAvReceiptScanner(System.getenv("FINANCE_RECEIPT_IT_CLAMAV_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("FINANCE_RECEIPT_IT_CLAMAV_PORT", "3310")),
                Duration.ofSeconds(15));
        assertEquals(ClamAvReceiptScanner.ScanResult.CLEAN,
                scanner.scan("ordinary receipt bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        byte[] eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(ClamAvReceiptScanner.ScanResult.INFECTED, scanner.scan(eicar));
    }
}
