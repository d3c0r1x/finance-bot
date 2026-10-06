package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ReceiptRecalculationApi.ApplyRequest;
import com.decorix.finance.core.api.ReceiptRecalculationApi.ApplyResult;
import com.decorix.finance.core.api.ReceiptRecalculationApi.HistoryPage;
import com.decorix.finance.core.api.ReceiptRecalculationApi.Preview;
import com.decorix.finance.core.api.ReceiptRecalculationApi.RunDetail;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/api/v1/tenants/{tenantId}/review-recalculations")
    HistoryPage historyApi(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt,
                           @RequestParam(defaultValue = "20") int limit,
                           @RequestParam(required = false) String cursor) {
        return recalculations.history(tenantId, jwt.getSubject(), limit, cursor);
    }

    @GetMapping("/api/v1/tenants/{tenantId}/review-recalculations/{runId}")
    RunDetail historyDetailApi(@PathVariable UUID tenantId, @PathVariable UUID runId,
                               @AuthenticationPrincipal Jwt jwt,
                               @RequestParam(defaultValue = "100") int limit,
                               @RequestParam(required = false) String cursor) {
        return recalculations.historyDetail(tenantId, jwt.getSubject(), runId, limit, cursor);
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

    @GetMapping("/bff/tenants/{tenantId}/review-recalculations")
    HistoryPage historyBff(@PathVariable UUID tenantId, @AuthenticationPrincipal OidcUser user,
                           @RequestParam(defaultValue = "20") int limit,
                           @RequestParam(required = false) String cursor) {
        return recalculations.history(tenantId, user.getSubject(), limit, cursor);
    }

    @GetMapping("/bff/tenants/{tenantId}/review-recalculations/{runId}")
    RunDetail historyDetailBff(@PathVariable UUID tenantId, @PathVariable UUID runId,
                               @AuthenticationPrincipal OidcUser user,
                               @RequestParam(defaultValue = "100") int limit,
                               @RequestParam(required = false) String cursor) {
        return recalculations.historyDetail(tenantId, user.getSubject(), runId, limit, cursor);
    }
}
