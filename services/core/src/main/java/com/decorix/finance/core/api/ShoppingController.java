package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ProductApi.ShoppingList;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class ShoppingController {
    private final ProductPriceHistoryService productHistory;

    public ShoppingController(ProductPriceHistoryService productHistory) {
        this.productHistory = productHistory;
    }

    @GetMapping("/shopping")
    ShoppingList shopping(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return productHistory.shopping(tenantId, jwt.getSubject());
    }

    @PostMapping("/shopping/{productKey}/bought")
    ShoppingList markBought(@PathVariable UUID tenantId, @PathVariable String productKey,
                            @AuthenticationPrincipal Jwt jwt) {
        return productHistory.markShoppingBought(tenantId, jwt.getSubject(), productKey);
    }

    @PutMapping("/suggestions/shopping/{productKey}/mute")
    ShoppingList mute(@PathVariable UUID tenantId, @PathVariable String productKey,
                      @AuthenticationPrincipal Jwt jwt) {
        return productHistory.muteShopping(tenantId, jwt.getSubject(), productKey);
    }

    @DeleteMapping("/suggestions/shopping/{productKey}/mute")
    ShoppingList unmute(@PathVariable UUID tenantId, @PathVariable String productKey,
                        @AuthenticationPrincipal Jwt jwt) {
        return productHistory.unmuteShopping(tenantId, jwt.getSubject(), productKey);
    }
}
