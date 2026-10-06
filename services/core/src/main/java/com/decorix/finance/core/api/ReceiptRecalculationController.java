package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ReceiptRecalculationApi.ApplyRequest;
import com.decorix.finance.core.api.ReceiptRecalculationApi.ApplyResult;
import com.decorix.finance.core.api.ReceiptRecalculationApi.Preview;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReceiptRecalculationController {
    private final ReceiptRecalculationService recalculations;

    public ReceiptRecalculationController(ReceiptRecalculationService recalculations) {
        this.recalculations = recalculations;
    }

    @PostMapping("/api/v1/tenants/{tenantId}/review-recalculations/preview")
    Preview previewApi(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return recalculations.preview(tenantId, jwt.getSubject());
    }

    @PostMapping("/api/v1/tenants/{tenantId}/review-recalculations/apply")
    ApplyResult applyApi(@PathVariable UUID tenantId, @RequestBody ApplyRequest request,
                         @AuthenticationPrincipal Jwt jwt) {
        return recalculations.apply(tenantId, jwt.getSubject(), request);
    }

    @PostMapping("/bff/tenants/{tenantId}/review-recalculations/preview")
    Preview previewBff(@PathVariable UUID tenantId, @AuthenticationPrincipal OidcUser user) {
        return recalculations.preview(tenantId, user.getSubject());
    }

    @PostMapping("/bff/tenants/{tenantId}/review-recalculations/apply")
    ApplyResult applyBff(@PathVariable UUID tenantId, @RequestBody ApplyRequest request,
                         @AuthenticationPrincipal OidcUser user) {
        return recalculations.apply(tenantId, user.getSubject(), request);
    }
}
