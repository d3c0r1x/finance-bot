package com.decorix.finance.core.api;

import com.decorix.finance.core.api.MerchantReclassificationApi.ApplyRequest;
import com.decorix.finance.core.api.MerchantReclassificationApi.ApplyResult;
import com.decorix.finance.core.api.MerchantReclassificationApi.Preview;
import com.decorix.finance.core.api.MerchantReclassificationApi.PreviewRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MerchantReclassificationController {
    private final MerchantReclassificationService reclassifications;

    public MerchantReclassificationController(MerchantReclassificationService reclassifications) {
        this.reclassifications = reclassifications;
    }

    @PostMapping("/api/v1/tenants/{tenantId}/merchant-reclassifications/preview")
    Preview previewApi(@PathVariable UUID tenantId, @RequestBody PreviewRequest request,
                       @AuthenticationPrincipal Jwt jwt) {
        return reclassifications.preview(tenantId, jwt.getSubject(), request);
    }

    @PostMapping("/api/v1/tenants/{tenantId}/merchant-reclassifications/apply")
    ApplyResult applyApi(@PathVariable UUID tenantId, @RequestHeader("Idempotency-Key") String idempotencyKey,
                         @RequestBody ApplyRequest request, @AuthenticationPrincipal Jwt jwt) {
        return reclassifications.apply(tenantId, jwt.getSubject(), idempotencyKey, request);
    }

    @PostMapping("/bff/tenants/{tenantId}/merchant-reclassifications/preview")
    Preview previewBff(@PathVariable UUID tenantId, @RequestBody PreviewRequest request,
                       @AuthenticationPrincipal OidcUser user) {
        return reclassifications.preview(tenantId, user.getSubject(), request);
    }

    @PostMapping("/bff/tenants/{tenantId}/merchant-reclassifications/apply")
    ApplyResult applyBff(@PathVariable UUID tenantId, @RequestHeader("Idempotency-Key") String idempotencyKey,
                         @RequestBody ApplyRequest request, @AuthenticationPrincipal OidcUser user) {
        return reclassifications.apply(tenantId, user.getSubject(), idempotencyKey, request);
    }
}
