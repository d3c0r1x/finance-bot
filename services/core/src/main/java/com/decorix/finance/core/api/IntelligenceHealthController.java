package com.decorix.finance.core.api;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IntelligenceHealthController {
    private final MemberProfileService profiles;
    private final IntelligenceHealthClient health;

    public IntelligenceHealthController(MemberProfileService profiles, IntelligenceHealthClient health) {
        this.profiles = profiles;
        this.health = health;
    }

    @GetMapping("/bff/tenants/{tenantId}/health")
    public HealthStatusApi.Response get(@PathVariable UUID tenantId, @AuthenticationPrincipal OidcUser user) {
        profiles.listMembers(tenantId, user.getSubject());
        return health.status();
    }
}
