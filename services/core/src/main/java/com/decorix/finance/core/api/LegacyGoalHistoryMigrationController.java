package com.decorix.finance.core.api;

import com.decorix.finance.core.api.LegacyGoalHistoryMigrationApi.ImportRequest;
import com.decorix.finance.core.api.LegacyGoalHistoryMigrationApi.ImportResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/migrations/goal-history")
public class LegacyGoalHistoryMigrationController {
    private final LegacyGoalHistoryMigrationService migration;
    private final String serviceToken;

    public LegacyGoalHistoryMigrationController(LegacyGoalHistoryMigrationService migration,
            @Value("${finance.migration.service-token:}") String serviceToken) {
        this.migration = migration;
        this.serviceToken = serviceToken;
    }

    @PostMapping
    ImportResponse importHistory(
            @RequestHeader(name = "X-Finance-Migration-Token", required = false) String suppliedToken,
            @RequestBody(required = false) ImportRequest request) {
        if (serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Migration service credential is not configured");
        }
        if (!TelegramServiceCredential.matches(serviceToken, suppliedToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid migration service credential");
        }
        return migration.importHistory(request);
    }
}
