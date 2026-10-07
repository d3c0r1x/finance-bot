package com.decorix.finance.core.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class ExportApi {
    private ExportApi() {}

    public record CreateRequest(String formatVersion, LocalDate fromDate, LocalDate toDate, String memberId) {}

    public record ExportJob(UUID id, String status, String formatVersion, LocalDate fromDate, LocalDate toDate,
                            UUID memberId, boolean allMembers, int rowCount, Instant snapshotAt,
                            Instant createdAt, Instant expiresAt, String downloadUrl) {}
}
