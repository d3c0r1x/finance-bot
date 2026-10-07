package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ExportApi.CreateRequest;
import com.decorix.finance.core.api.ExportApi.ExportJob;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/exports")
public class ExportController {
    private final ExportService exports;

    public ExportController(ExportService exports) { this.exports = exports; }

    @PostMapping
    ResponseEntity<ExportJob> create(@PathVariable UUID tenantId, @RequestBody CreateRequest request,
                                     @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(exports.create(tenantId, jwt.getSubject(), request));
    }

    @GetMapping("/{exportId}")
    ExportJob get(@PathVariable UUID tenantId, @PathVariable UUID exportId, @AuthenticationPrincipal Jwt jwt) {
        return exports.get(tenantId, jwt.getSubject(), exportId);
    }
}
