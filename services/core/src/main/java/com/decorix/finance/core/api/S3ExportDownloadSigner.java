package com.decorix.finance.core.api;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
@ConditionalOnProperty(name = "finance.exports.storage.provider", havingValue = "s3")
public class S3ExportDownloadSigner implements ExportDownloadSigner, AutoCloseable {
    private static final Duration URL_LIFETIME = Duration.ofMinutes(5);
    private static final Pattern OBJECT_KEY = Pattern.compile(
            "tenants/[0-9a-fA-F-]{36}/exports/[0-9a-fA-F-]{36}/[0-9a-fA-F-]{36}\\.csv");

    private final S3Presigner presigner;
    private final String bucket;

    public S3ExportDownloadSigner(
            @Value("${finance.exports.storage.s3.endpoint:}") String endpoint,
            @Value("${finance.exports.storage.s3.region:us-east-1}") String region,
            @Value("${finance.exports.storage.s3.bucket:}") String bucket,
            @Value("${finance.exports.storage.s3.access-key:}") String accessKey,
            @Value("${finance.exports.storage.s3.secret-key:}") String secretKey,
            @Value("${finance.exports.storage.s3.path-style:true}") boolean pathStyle) {
        URI endpointUri;
        try {
            endpointUri = URI.create(endpoint == null ? "" : endpoint.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Export S3 endpoint must be an HTTP(S) origin");
        }
        if (!("http".equals(endpointUri.getScheme()) || "https".equals(endpointUri.getScheme()))
                || endpointUri.getHost() == null || endpointUri.getUserInfo() != null
                || endpointUri.getQuery() != null || endpointUri.getFragment() != null
                || (endpointUri.getPath() != null && !endpointUri.getPath().isEmpty()
                    && !"/".equals(endpointUri.getPath()))) {
            throw new IllegalArgumentException("Export S3 endpoint must be an HTTP(S) origin");
        }
        if (region == null || region.isBlank() || bucket == null || bucket.isBlank()
                || accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("Export S3 region, bucket, and credentials are required");
        }
        this.bucket = bucket;
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpointUri)
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                .build();
    }

    @Override
    public String sign(String objectKey, Duration lifetime) {
        if (objectKey == null || !OBJECT_KEY.matcher(objectKey).matches()
                || lifetime == null || lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(URL_LIFETIME) > 0) {
            throw new IllegalArgumentException("Invalid export object key");
        }
        var request = GetObjectRequest.builder().bucket(bucket).key(objectKey)
                .responseContentType("text/csv; charset=utf-8")
                .responseContentDisposition("attachment; filename=\"finance-export.csv\"")
                .build();
        var presignRequest = GetObjectPresignRequest.builder().signatureDuration(lifetime)
                .getObjectRequest(request).build();
        return presigner.presignGetObject(presignRequest).url().toExternalForm();
    }

    @Override
    public void close() {
        presigner.close();
    }
}
