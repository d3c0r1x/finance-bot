package com.decorix.finance.core.api;

import java.util.UUID;
import java.net.URI;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class BudgetController {
    private final BudgetService budgets;

    public BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    @GetMapping("/budgets")
    BudgetApi.Overview get(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return budgets.get(tenantId, jwt.getSubject());
    }

    @PutMapping("/budgets/{budgetKey}")
    BudgetApi.Overview update(@PathVariable UUID tenantId, @PathVariable String budgetKey,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody BudgetApi.UpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (ifMatch == null || !ifMatch.matches("\"(?:0|[1-9][0-9]*)\"")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match must contain a quoted version");
        }
        try {
            long version = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
            return budgets.update(tenantId, jwt.getSubject(), budgetKey, key, version, request);
        } catch (NumberFormatException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match version is invalid", ex);
        }
    }

    @DeleteMapping("/budgets/personal-overrides")
    BudgetApi.Overview resetPersonal(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Jwt jwt) {
        return budgets.resetPersonal(tenantId, jwt.getSubject(), key);
    }

    @PostMapping("/budget-proposals")
    ResponseEntity<BudgetApi.BudgetProposalResponse> propose(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody BudgetApi.BudgetProposalRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        BudgetApi.BudgetProposalResponse proposal = budgets.propose(tenantId, jwt.getSubject(), key, request);
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + tenantId
                + "/budget-proposals/" + proposal.id())).body(proposal);
    }

    @PostMapping("/budget-proposals/history")
    ResponseEntity<BudgetApi.BudgetProposalResponse> proposeFromHistory(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Jwt jwt) {
        BudgetApi.BudgetProposalResponse proposal = budgets.proposeFromHistory(tenantId, jwt.getSubject(), key);
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + tenantId
                + "/budget-proposals/" + proposal.id())).body(proposal);
    }

    @PostMapping("/budget-proposals/{proposalId}/apply")
    BudgetApi.Overview applyProposal(@PathVariable UUID tenantId, @PathVariable UUID proposalId,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Jwt jwt) {
        return budgets.applyProposal(tenantId, jwt.getSubject(), proposalId, key);
    }
}
