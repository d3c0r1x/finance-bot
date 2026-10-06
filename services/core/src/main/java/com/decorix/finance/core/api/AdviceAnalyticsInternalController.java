package com.decorix.finance.core.api;

import com.decorix.finance.core.api.AdviceAnalyticsApi.ClaimedJob;
import com.decorix.finance.core.api.AdviceAnalyticsApi.JobResult;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/analytics/advice-jobs")
public class AdviceAnalyticsInternalController {
    private final AdviceAnalyticsService service;
    private final String serviceToken;

    public AdviceAnalyticsInternalController(AdviceAnalyticsService service,
            @Value("${finance.analytics.price-history.service-token:}") String serviceToken) {
        this.service = service;
        this.serviceToken = serviceToken;
    }

    @PostMapping("/claim")
    ResponseEntity<ClaimedJob> claim(@RequestHeader(name = "X-Analytics-Service-Token", required = false) String suppliedToken) {
        authenticate(suppliedToken);
        ClaimedJob job = service.claim();
        return job == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(job);
    }

    @PostMapping("/{jobId}/result")
    ResponseEntity<Void> result(@PathVariable UUID jobId, @RequestHeader(name = "X-Analytics-Service-Token", required = false) String suppliedToken,
            @RequestBody JobResult result) {
        authenticate(suppliedToken);
        if (!service.complete(jobId, result)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Job lease is stale");
        return ResponseEntity.noContent().build();
    }

    private void authenticate(String suppliedToken) {
        if (!TelegramServiceCredential.matches(serviceToken, suppliedToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid analytics service credential");
        }
    }
}
