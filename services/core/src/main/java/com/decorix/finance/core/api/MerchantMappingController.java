package com.decorix.finance.core.api;

import com.decorix.finance.core.api.MerchantMappingApi.Mapping;
import com.decorix.finance.core.api.MerchantMappingApi.MappingList;
import com.decorix.finance.core.api.MerchantMappingApi.MappingRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MerchantMappingController {
    private final MerchantMappingService mappings;

    public MerchantMappingController(MerchantMappingService mappings) {
        this.mappings = mappings;
    }

    @GetMapping("/api/v1/tenants/{tenantId}/merchant-mappings")
    MappingList listApi(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return new MappingList(mappings.list(tenantId, jwt.getSubject()));
    }

    @PutMapping("/api/v1/tenants/{tenantId}/merchant-mappings")
    Mapping saveApi(@PathVariable UUID tenantId, @RequestBody MappingRequest request,
                    @AuthenticationPrincipal Jwt jwt) {
        return mappings.save(tenantId, jwt.getSubject(), request);
    }

    @GetMapping("/bff/tenants/{tenantId}/merchant-mappings")
    MappingList listBff(@PathVariable UUID tenantId, @AuthenticationPrincipal OidcUser user) {
        return new MappingList(mappings.list(tenantId, user.getSubject()));
    }

    @PutMapping("/bff/tenants/{tenantId}/merchant-mappings")
    Mapping saveBff(@PathVariable UUID tenantId, @RequestBody MappingRequest request,
                    @AuthenticationPrincipal OidcUser user) {
        return mappings.save(tenantId, user.getSubject(), request);
    }
}
