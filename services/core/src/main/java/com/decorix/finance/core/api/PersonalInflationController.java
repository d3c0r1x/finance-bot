package com.decorix.finance.core.api;

import com.decorix.finance.core.api.InflationApi.PersonalInflation;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/analytics/personal-inflation")
public class PersonalInflationController {
    private final ProductPriceHistoryService productHistory;

    public PersonalInflationController(ProductPriceHistoryService productHistory) {
        this.productHistory = productHistory;
    }

    @GetMapping
    PersonalInflation personalInflation(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return productHistory.personalInflation(tenantId, jwt.getSubject());
    }
}
