package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TenantApi.CreateTenantRequest;
import com.decorix.finance.core.api.TenantApi.TenantResponse;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TenantController {
    private final TenantService tenants;

    public TenantController(TenantService tenants) {
        this.tenants = tenants;
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
}
