package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TransactionApi.TransactionResponse;
import com.decorix.finance.core.api.TransactionDraftApi.CreateRequest;
import com.decorix.finance.core.api.TransactionDraftApi.DraftResponse;
import com.decorix.finance.core.api.TransactionDraftApi.UpdateRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/transaction-drafts")
public class TransactionDraftController {
    private final TransactionDraftService drafts;

    public TransactionDraftController(TransactionDraftService drafts) {
        this.drafts = drafts;
    }

    @PostMapping
    ResponseEntity<DraftResponse> create(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody CreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        DraftResponse draft = drafts.create(tenantId, jwt.getSubject(), key, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(draft);
    }

    @PatchMapping("/{draftId}")
    DraftResponse update(@PathVariable UUID tenantId, @PathVariable UUID draftId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody UpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return drafts.update(tenantId, jwt.getSubject(), draftId, parseVersion(ifMatch), request);
    }

    @PostMapping("/{draftId}/confirm")
    TransactionResponse confirm(@PathVariable UUID tenantId, @PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        return drafts.confirm(tenantId, jwt.getSubject(), draftId, key, parseVersion(ifMatch));
    }

    @DeleteMapping("/{draftId}")
    ResponseEntity<Void> cancel(@PathVariable UUID tenantId, @PathVariable UUID draftId,
            @RequestHeader("If-Match") String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        drafts.cancel(tenantId, jwt.getSubject(), draftId, parseVersion(ifMatch));
        return ResponseEntity.noContent().build();
    }

    private static long parseVersion(String value) {
        if (value == null || !value.matches("\\\"[1-9][0-9]*\\\"")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must contain a quoted version");
        }
        try {
            return Long.parseLong(value.substring(1, value.length() - 1));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match version is invalid", ex);
        }
    }
}
