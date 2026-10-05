package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ReportApi.Report;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class NotificationDeliveryApi {
    private NotificationDeliveryApi() {}

    public record ClaimRequest(Integer limit) {}
    public record ClaimResponse(List<DeliveryClaim> items) {}
    public record DeliveryClaim(UUID intentId, long telegramUserId, String digestKind,
            LocalDate scheduledLocalDate, String language, int attemptNumber, UUID leaseToken,
            Report report) {}
    public record DeliveryRequest(UUID leaseToken, String outcome, String errorCode, String providerMessageId) {}
    public record DeliveryResponse(String state) {}
}
