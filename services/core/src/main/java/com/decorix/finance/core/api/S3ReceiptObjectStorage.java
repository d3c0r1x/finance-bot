package com.decorix.finance.core.api;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.S3Configuration;

@Component
@ConditionalOnProperty(name = "finance.receipts.storage.provider", havingValue = "s3")
public class S3ReceiptObjectStorage implements ReceiptObjectStorage, AutoCloseable {
    private final S3Client client;
    private final String bucket;

    public S3ReceiptObjectStorage(
            @Value("${finance.receipts.storage.s3.endpoint:}") String endpoint,
            @Value("${finance.receipts.storage.s3.region:us-east-1}") String region,
            @Value("${finance.receipts.storage.s3.bucket:}") String bucket,
            @Value("${finance.receipts.storage.s3.access-key:}") String accessKey,
            @Value("${finance.receipts.storage.s3.secret-key:}") String secretKey,
            @Value("${finance.receipts.storage.s3.path-style:true}") boolean pathStyle) {
        if (bucket == null || bucket.isBlank()) throw new IllegalStateException("Receipt S3 bucket is required");
        this.bucket = bucket;
        var builder = S3Client.builder().region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle)
                        .chunkedEncodingEnabled(false).build());
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        this.client = builder.build();
    }

    @Override
    public void put(String key, byte[] body, String contentType) {
        ReceiptObjectKeys.validate(key);
        if (body == null || body.length == 0 || !"image/png".equals(contentType) && !"image/jpeg".equals(contentType)) {
            throw new IllegalArgumentException("Receipt object is invalid");
        }
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(body));
    }

    @Override
    public byte[] get(String key) {
        ReceiptObjectKeys.validate(key);
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (S3Exception error) {
            throw new IllegalStateException("Receipt object could not be read", error);
        }
    }

    @Override
    public void delete(String key) {
        ReceiptObjectKeys.validate(key);
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void close() {
        client.close();
    }
}
