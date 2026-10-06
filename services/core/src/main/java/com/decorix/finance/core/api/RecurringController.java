package com.decorix.finance.core.api;

import com.decorix.finance.core.api.RecurringApi.RecurringProjection;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/analytics/recurring")
public class RecurringController {
    private final ProductPriceHistoryService recurring;

    public RecurringController(ProductPriceHistoryService recurring) { this.recurring = recurring; }

    @GetMapping
    RecurringProjection get(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return recurring.recurring(tenantId, jwt.getSubject());
    }

    @PutMapping("/{seriesId}/mute")
    RecurringProjection mute(@PathVariable UUID tenantId, @PathVariable String seriesId,
                             @AuthenticationPrincipal Jwt jwt) {
        return recurring.muteRecurring(tenantId, jwt.getSubject(), seriesId);
    }

    @DeleteMapping("/{seriesId}/mute")
    RecurringProjection unmute(@PathVariable UUID tenantId, @PathVariable String seriesId,
                               @AuthenticationPrincipal Jwt jwt) {
        return recurring.unmuteRecurring(tenantId, jwt.getSubject(), seriesId);
    }
}
