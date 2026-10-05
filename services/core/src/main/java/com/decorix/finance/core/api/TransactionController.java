package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TransactionApi.CreateRequest;
import com.decorix.finance.core.api.TransactionApi.Page;
import com.decorix.finance.core.api.TransactionApi.TransactionResponse;
import com.decorix.finance.core.api.TransactionApi.UpdateRequest;
import java.net.URI;
import java.util.UUID;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/transactions")
public class TransactionController {
    private final TransactionService transactions;

    public TransactionController(TransactionService transactions) {
        this.transactions = transactions;
    }

    @PostMapping
    ResponseEntity<TransactionResponse> create(
            @PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody CreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        TransactionResponse created = transactions.create(tenantId, jwt.getSubject(), key, request);
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + tenantId + "/transactions/" + created.id()))
                .body(created);
    }

    @GetMapping("/{transactionId}")
    TransactionResponse get(@PathVariable UUID tenantId,
                            @PathVariable UUID transactionId,
                            @AuthenticationPrincipal Jwt jwt) {
        return transactions.get(tenantId, jwt.getSubject(), transactionId);
    }

    @PatchMapping("/{transactionId}")
    TransactionResponse update(@PathVariable UUID tenantId,
                               @PathVariable UUID transactionId,
                               @RequestHeader("Idempotency-Key") String key,
                               @RequestHeader("If-Match") String ifMatch,
                               @RequestBody UpdateRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        if (ifMatch == null || !ifMatch.matches("\\\"[1-9][0-9]*\\\"")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must contain a quoted version");
        }
        try {
            long version = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
            return transactions.update(tenantId, jwt.getSubject(), transactionId, key, version, request);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match version is invalid", ex);
        }
    }

    @PostMapping("/{transactionId}/void")
    TransactionResponse voidTransaction(
            @PathVariable UUID tenantId,
            @PathVariable UUID transactionId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        if (ifMatch == null || !ifMatch.matches("\\\"[1-9][0-9]*\\\"")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must contain a quoted version");
        }
        try {
            long version = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
            return transactions.voidTransaction(tenantId, jwt.getSubject(), transactionId, key, version);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match version is invalid", ex);
        }
    }

    @GetMapping
    Page list(@PathVariable UUID tenantId,
              @RequestParam(defaultValue = "50") int pageSize,
              @RequestParam(required = false) String cursor,
              @RequestParam(required = false) LocalDate from,
              @RequestParam(required = false) LocalDate to,
              @RequestParam(required = false) String type,
              @RequestParam(required = false) String search,
              @RequestParam(required = false) String memberId,
              @AuthenticationPrincipal Jwt jwt) {
        return transactions.list(tenantId, jwt.getSubject(), pageSize, cursor, from, to, type, search, memberId);
    }
}
