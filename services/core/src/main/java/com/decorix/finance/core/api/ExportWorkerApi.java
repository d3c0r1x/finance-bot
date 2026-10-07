package com.decorix.finance.core.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ExportWorkerApi {
    private ExportWorkerApi() {}

    public record Claim(UUID id, UUID tenantId, UUID requesterUserId, String formatVersion,
                        LocalDate fromDate, LocalDate toDate, UUID memberId, boolean allMembers,
                        String requesterTimezone, Instant snapshotAt, int rowCount, UUID leaseToken,
                        Instant leaseExpiresAt, int attemptCount) {}

    public record SnapshotRow(long rowNumber, UUID transactionId, UUID ownerUserId, Instant occurredAt,
                              String amount, String currency, String categoryCode, String subcategoryCode,
                              String description, String transactionType, String debtTarget, String source,
                              String telegramId) {}

    public record SnapshotPage(UUID exportId, String formatVersion, String requesterTimezone, Instant snapshotAt,
                               int rowCount, List<SnapshotRow> rows, long nextAfterRowNumber) {
        public SnapshotPage { rows = List.copyOf(rows); }
    }

    public record CompleteRequest(UUID leaseToken, String objectKey, long byteCount, String sha256) {}
    public record FailRequest(UUID leaseToken, String errorCode, boolean retryable) {}
}
