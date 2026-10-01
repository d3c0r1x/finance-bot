package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TransactionApi.CreateRequest;
import com.decorix.finance.core.api.TransactionApi.Page;
import com.decorix.finance.core.api.TransactionApi.TransactionResponse;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping
    Page list(@PathVariable UUID tenantId,
              @RequestParam(defaultValue = "50") int pageSize,
              @RequestParam(required = false) String cursor,
              @AuthenticationPrincipal Jwt jwt) {
        return transactions.list(tenantId, jwt.getSubject(), pageSize, cursor);
    }
}
