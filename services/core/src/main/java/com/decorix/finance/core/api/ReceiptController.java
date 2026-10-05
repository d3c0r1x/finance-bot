package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ReceiptApi.CreateRequest;
import com.decorix.finance.core.api.ReceiptApi.CategorySelection;
import com.decorix.finance.core.api.ReceiptApi.ItemInput;
import com.decorix.finance.core.api.ReceiptApi.ReceiptItemPage;
import com.decorix.finance.core.api.ReceiptApi.ReceiptResponse;
import com.decorix.finance.core.api.ReceiptApi.RepeatWarnings;
import com.decorix.finance.core.api.ReceiptApi.DuplicateCandidates;
import com.decorix.finance.core.api.ReceiptApi.DuplicateDecisionSelection;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/receipts")
public class ReceiptController {
    private final ReceiptService receipts;
    private final ReceiptReadingService readings;

    public ReceiptController(ReceiptService receipts, ReceiptReadingService readings) {
        this.receipts = receipts;
        this.readings = readings;
    }

    @PostMapping
    ResponseEntity<ReceiptResponse> create(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody CreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ReceiptResponse created = receipts.create(tenantId, jwt.getSubject(), key, request);
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + tenantId + "/receipts/" + created.id()))
                .body(created);
    }

    @GetMapping("/{receiptId}")
    ReceiptResponse get(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
                        @AuthenticationPrincipal Jwt jwt) {
        return receipts.get(tenantId, jwt.getSubject(), receiptId);
    }

    @GetMapping("/{receiptId}/readings")
    ReceiptReadingApi.ReceiptOcrReading reading(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @AuthenticationPrincipal Jwt jwt) {
        return readings.get(tenantId, jwt.getSubject(), receiptId);
    }

    @GetMapping("/{receiptId}/items")
    ReceiptItemPage getItems(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestParam(defaultValue = "1") int page, @AuthenticationPrincipal Jwt jwt) {
        return receipts.getItems(tenantId, jwt.getSubject(), receiptId, page);
    }

    @GetMapping("/{receiptId}/disputed-items")
    ReceiptItemPage disputedItems(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestParam(defaultValue = "1") int page, @AuthenticationPrincipal Jwt jwt) {
        return receipts.disputedItems(tenantId, jwt.getSubject(), receiptId, page);
    }

    @GetMapping("/{receiptId}/repeat-warnings")
    RepeatWarnings repeatWarnings(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @AuthenticationPrincipal Jwt jwt) {
        return receipts.repeatWarnings(tenantId, jwt.getSubject(), receiptId);
    }

    @GetMapping("/{receiptId}/duplicate-candidates")
    DuplicateCandidates duplicateCandidates(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @AuthenticationPrincipal Jwt jwt) {
        return receipts.duplicateCandidates(tenantId, jwt.getSubject(), receiptId);
    }

    @org.springframework.web.bind.annotation.PutMapping("/{receiptId}/duplicate-decision")
    ReceiptResponse decideDuplicate(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody DuplicateDecisionSelection request,
            @AuthenticationPrincipal Jwt jwt) {
        return receipts.decideDuplicate(tenantId, jwt.getSubject(), receiptId, parseVersion(ifMatch), request);
    }

    @PostMapping("/{receiptId}/confirm")
    ReceiptResponse confirm(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader("If-Match") String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        return receipts.confirm(tenantId, jwt.getSubject(), receiptId, idempotencyKey, parseVersion(ifMatch));
    }

    @PostMapping("/{receiptId}/items")
    ResponseEntity<ReceiptResponse> addItem(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody ItemInput request,
            @AuthenticationPrincipal Jwt jwt) {
        ReceiptResponse updated = receipts.addItem(tenantId, jwt.getSubject(), receiptId, parseVersion(ifMatch), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(updated);
    }

    @PatchMapping("/{receiptId}/items/{itemId}")
    ReceiptResponse updateItem(@PathVariable UUID tenantId, @PathVariable UUID receiptId, @PathVariable UUID itemId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody ItemInput request,
            @AuthenticationPrincipal Jwt jwt) {
        return receipts.updateItem(tenantId, jwt.getSubject(), receiptId, itemId, parseVersion(ifMatch), request);
    }

    @DeleteMapping("/{receiptId}/items/{itemId}")
    ResponseEntity<Void> deleteItem(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @PathVariable UUID itemId, @RequestHeader("If-Match") String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        receipts.deleteItem(tenantId, jwt.getSubject(), receiptId, itemId, parseVersion(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{receiptId}/sync-total")
    ReceiptResponse syncTotal(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestHeader("If-Match") String ifMatch, @AuthenticationPrincipal Jwt jwt) {
        return receipts.syncTotal(tenantId, jwt.getSubject(), receiptId, parseVersion(ifMatch));
    }

    @PostMapping("/{receiptId}/basket-review")
    ReceiptResponse reviewBasket(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestHeader("If-Match") String ifMatch, @AuthenticationPrincipal Jwt jwt) {
        return receipts.reviewBasket(tenantId, jwt.getSubject(), receiptId, parseVersion(ifMatch));
    }

    @PatchMapping("/{receiptId}/category")
    ReceiptResponse selectCategory(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody CategorySelection request,
            @AuthenticationPrincipal Jwt jwt) {
        return receipts.selectCategory(tenantId, jwt.getSubject(), receiptId, parseVersion(ifMatch), request);
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
