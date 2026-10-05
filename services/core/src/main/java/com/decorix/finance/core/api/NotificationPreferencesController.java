package com.decorix.finance.core.api;

import com.decorix.finance.core.api.NotificationPreferencesApi.PreferencesResponse;
import com.decorix.finance.core.api.NotificationPreferencesApi.UpdateRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/notification-preferences")
public class NotificationPreferencesController {
    private final NotificationPreferencesService preferences;

    public NotificationPreferencesController(NotificationPreferencesService preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    PreferencesResponse get(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return preferences.get(tenantId, jwt.getSubject());
    }

    @PatchMapping
    PreferencesResponse update(@PathVariable UUID tenantId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody UpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return preferences.update(tenantId, jwt.getSubject(), parseVersion(ifMatch), request);
    }

    private static long parseVersion(String value) {
        if (value == null || !value.matches("\\\"(?:0|[1-9][0-9]*)\\\"")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must contain a quoted version");
        }
        try {
            return Long.parseLong(value.substring(1, value.length() - 1));
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match version is invalid", exception);
        }
    }
}
