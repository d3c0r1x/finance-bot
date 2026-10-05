package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ReportApi.Report;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reports")
public class ReportController {
    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/month")
    Report month(@PathVariable UUID tenantId,
                 @RequestParam(required = false) YearMonth month,
                 @RequestParam(defaultValue = "personal") String scope,
                 @AuthenticationPrincipal Jwt jwt) {
        return reports.get(tenantId, jwt.getSubject(), "month", month, null, null, family(scope));
    }

    @GetMapping("/period")
    Report period(@PathVariable UUID tenantId,
                  @RequestParam String period,
                  @RequestParam(required = false) YearMonth month,
                  @RequestParam(required = false) LocalDate from,
                  @RequestParam(required = false) LocalDate to,
                  @RequestParam(defaultValue = "personal") String scope,
                  @AuthenticationPrincipal Jwt jwt) {
        return reports.get(tenantId, jwt.getSubject(), period, month, from, to, family(scope));
    }

    @GetMapping("/family")
    Report family(@PathVariable UUID tenantId,
                  @RequestParam(defaultValue = "month") String period,
                  @RequestParam(required = false) YearMonth month,
                  @RequestParam(required = false) LocalDate from,
                  @RequestParam(required = false) LocalDate to,
                  @AuthenticationPrincipal Jwt jwt) {
        return reports.get(tenantId, jwt.getSubject(), period, month, from, to, true);
    }

    private static boolean family(String scope) {
        if ("family".equals(scope)) return true;
        if ("personal".equals(scope)) return false;
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Report scope must be personal or family");
    }
}
