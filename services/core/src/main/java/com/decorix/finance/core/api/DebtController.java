package com.decorix.finance.core.api;

import com.decorix.finance.core.api.DebtApi.CreateRequest;
import com.decorix.finance.core.api.DebtApi.BalanceAdjustmentRequest;
import com.decorix.finance.core.api.DebtApi.DebtResponse;
import com.decorix.finance.core.api.DebtApi.ForecastResponse;
import com.decorix.finance.core.api.DebtApi.Page;
import com.decorix.finance.core.api.DebtApi.PaymentRequest;
import com.decorix.finance.core.api.DebtApi.PaymentResponse;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/debts")
public class DebtController {
    private final DebtService debts;

    public DebtController(DebtService debts) {
        this.debts = debts;
    }

    @GetMapping
    Page list(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return debts.list(tenantId, jwt.getSubject());
    }

    @PostMapping
    ResponseEntity<DebtResponse> create(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody CreateRequest request, @AuthenticationPrincipal Jwt jwt) {
        DebtResponse created = debts.create(tenantId, jwt.getSubject(), key, request);
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + tenantId + "/debts/" + created.id())).body(created);
    }

    @GetMapping("/{debtId}")
    DebtResponse get(@PathVariable UUID tenantId, @PathVariable UUID debtId, @AuthenticationPrincipal Jwt jwt) {
        return debts.get(tenantId, jwt.getSubject(), debtId);
    }

    @PutMapping("/{debtId}/balance")
    DebtResponse adjustBalance(@PathVariable UUID tenantId, @PathVariable UUID debtId,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch,
            @RequestBody BalanceAdjustmentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return debts.adjustBalance(tenantId, jwt.getSubject(), debtId, key, parseVersion(ifMatch), request);
    }

    @PostMapping("/{debtId}/payments")
    PaymentResponse pay(@PathVariable UUID tenantId, @PathVariable UUID debtId,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch,
            @RequestBody PaymentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return debts.pay(tenantId, jwt.getSubject(), debtId, key, parseVersion(ifMatch), request);
    }

    @GetMapping("/{debtId}/forecast")
    ForecastResponse forecast(@PathVariable UUID tenantId, @PathVariable UUID debtId,
                              @AuthenticationPrincipal Jwt jwt) {
        return debts.forecast(tenantId, jwt.getSubject(), debtId);
    }

    private static long parseVersion(String value) {
        if (value == null || !value.matches("\"[1-9][0-9]*\"")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match must contain a quoted version");
        }
        try { return Long.parseLong(value.substring(1, value.length() - 1)); }
        catch (NumberFormatException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match version is invalid", ex);
        }
    }
}
