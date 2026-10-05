package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TenantApi.CreateTenantRequest;
import com.decorix.finance.core.api.TenantApi.TenantResponse;
import java.net.URI;
import java.util.List;
import java.time.YearMonth;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TenantController {
    private final TenantService tenants;
    private final MemberProfileService profiles;
    private final TransactionService transactions;
    private final TelegramLinkService telegramLinks;

    public TenantController(TenantService tenants, MemberProfileService profiles, TransactionService transactions,
                            TelegramLinkService telegramLinks) {
        this.tenants = tenants;
        this.profiles = profiles;
        this.transactions = transactions;
        this.telegramLinks = telegramLinks;
    }

    @PostMapping("/tenants")
    ResponseEntity<TenantResponse> create(@RequestBody CreateTenantRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        TenantResponse created = tenants.create(jwt.getSubject(), request);
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + created.tenantId())).body(created);
    }

    @GetMapping("/me/tenants")
    List<TenantResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return tenants.list(jwt.getSubject());
    }

    @GetMapping("/tenants/{tenantId}/members")
    List<MemberProfileApi.MemberResponse> listMembers(@PathVariable java.util.UUID tenantId,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return profiles.listMembers(tenantId, jwt.getSubject());
    }

    @PostMapping("/me/telegram-link")
    TelegramLinkApi.LinkCodeResponse createTelegramLinkCode(@AuthenticationPrincipal Jwt jwt) {
        return telegramLinks.createCode(jwt.getSubject());
    }

    @GetMapping("/tenants/{tenantId}/summary")
    TransactionApi.DashboardSummary summary(@PathVariable java.util.UUID tenantId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) YearMonth month,
            @AuthenticationPrincipal Jwt jwt) {
        return transactions.summary(tenantId, jwt.getSubject(), month);
    }

    @GetMapping("/tenants/{tenantId}/profile/me")
    MemberProfileApi.ProfileResponse getProfile(@PathVariable java.util.UUID tenantId,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return profiles.get(tenantId, jwt.getSubject());
    }

    @PatchMapping("/tenants/{tenantId}/profile/me")
    MemberProfileApi.ProfileResponse updateProfile(@PathVariable java.util.UUID tenantId,
            @RequestBody MemberProfileApi.UpdateProfileRequest request, @AuthenticationPrincipal Jwt jwt) {
        return profiles.update(tenantId, jwt.getSubject(), request);
    }
}
