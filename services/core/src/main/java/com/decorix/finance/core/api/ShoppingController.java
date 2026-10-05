package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ProductApi.ShoppingList;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/shopping")
public class ShoppingController {
    private final ProductPriceHistoryService productHistory;

    public ShoppingController(ProductPriceHistoryService productHistory) {
        this.productHistory = productHistory;
    }

    @GetMapping
    ShoppingList shopping(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return productHistory.shopping(tenantId, jwt.getSubject());
    }
}
