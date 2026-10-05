package com.decorix.finance.core.api;

import com.decorix.finance.core.api.NotificationDeliveryApi.ClaimRequest;
import com.decorix.finance.core.api.NotificationDeliveryApi.ClaimResponse;
import com.decorix.finance.core.api.NotificationDeliveryApi.DeliveryRequest;
import com.decorix.finance.core.api.NotificationDeliveryApi.DeliveryResponse;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/telegram/notifications")
public class NotificationDeliveryController {
    private final NotificationDeliveryService deliveries;
    private final String serviceToken;

    public NotificationDeliveryController(NotificationDeliveryService deliveries,
            @Value("${finance.telegram.service-token:}") String serviceToken) {
        this.deliveries = deliveries;
        this.serviceToken = serviceToken;
    }

    @PostMapping("/claim")
    ClaimResponse claim(@RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody(required = false) ClaimRequest request) {
        requireServiceCredential(suppliedToken);
        return new ClaimResponse(deliveries.claim(request == null ? null : request.limit()));
    }

    @PostMapping("/{intentId}/delivery")
    DeliveryResponse delivery(@RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @PathVariable UUID intentId, @RequestBody DeliveryRequest request) {
        requireServiceCredential(suppliedToken);
        return deliveries.acknowledge(intentId, request);
    }

    private void requireServiceCredential(String suppliedToken) {
        if (serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Telegram service credential is not configured");
        }
        if (!TelegramServiceCredential.matches(serviceToken, suppliedToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Telegram service credential");
        }
    }
}
