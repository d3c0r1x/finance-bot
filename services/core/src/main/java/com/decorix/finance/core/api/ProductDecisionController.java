package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ProductApi.ProductDecisionResponse;
import com.decorix.finance.core.api.ProductApi.ProductDecisionSelection;
import com.decorix.finance.core.api.ProductApi.ProductDecisionKeys;
import com.decorix.finance.core.api.ProductApi.PriceComparison;
import com.decorix.finance.core.api.ProductApi.ProductCatalogResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/products")
public class ProductDecisionController {
    private final ReceiptService receipts;
    private final ProductPriceHistoryService priceHistory;

    public ProductDecisionController(ReceiptService receipts, ProductPriceHistoryService priceHistory) {
        this.receipts = receipts;
        this.priceHistory = priceHistory;
    }

    @GetMapping("/price-history")
    PriceComparison priceHistory(@PathVariable UUID tenantId, @RequestParam UUID receiptId,
                                 @RequestParam UUID itemId, @AuthenticationPrincipal Jwt jwt) {
        return priceHistory.get(tenantId, jwt.getSubject(), receiptId, itemId);
    }

    @GetMapping
    ProductCatalogResponse catalog(@PathVariable UUID tenantId, @RequestParam(required = false) String query,
                                   @AuthenticationPrincipal Jwt jwt) {
        return priceHistory.catalog(tenantId, jwt.getSubject(), query);
    }

    @GetMapping("/decisions")
    ProductDecisionKeys allowedProducts(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return receipts.allowedProducts(tenantId, jwt.getSubject());
    }

    @PutMapping("/{productKey}/decision")
    ProductDecisionResponse allow(@PathVariable UUID tenantId, @PathVariable String productKey,
            @RequestBody ProductDecisionSelection request, @AuthenticationPrincipal Jwt jwt) {
        return receipts.allowProduct(tenantId, jwt.getSubject(), productKey, request);
    }

    @DeleteMapping("/{productKey}/decision")
    ResponseEntity<Void> revoke(@PathVariable UUID tenantId, @PathVariable String productKey,
            @AuthenticationPrincipal Jwt jwt) {
        receipts.revokeProduct(tenantId, jwt.getSubject(), productKey);
        return ResponseEntity.noContent().build();
    }
}
