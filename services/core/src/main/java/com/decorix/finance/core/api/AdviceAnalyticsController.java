package com.decorix.finance.core.api;

import com.decorix.finance.core.api.AdviceAnalyticsApi.Error;
import com.decorix.finance.core.api.AdviceAnalyticsApi.Job;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/analytics/advice")
public class AdviceAnalyticsController {
    private final AdviceAnalyticsService service;

    public AdviceAnalyticsController(AdviceAnalyticsService service) { this.service = service; }

    @GetMapping
    Job latest(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return service.latest(tenantId, jwt.getSubject());
    }

    @PostMapping
    ResponseEntity<?> request(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        try { return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.request(tenantId, jwt.getSubject())); }
        catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.PAYLOAD_TOO_LARGE)
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new Error("too_many_items"));
            throw exception;
        }
    }

    @GetMapping("/jobs/{jobId}")
    Job get(@PathVariable UUID tenantId, @PathVariable UUID jobId, @AuthenticationPrincipal Jwt jwt) {
        return service.get(tenantId, jwt.getSubject(), jobId);
    }
}
