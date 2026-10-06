package com.decorix.finance.core.api;

import com.decorix.finance.core.api.GoalCandidatesApi.AcceptRequest;
import com.decorix.finance.core.api.GoalCandidatesApi.Goal;
import com.decorix.finance.core.api.GoalCandidatesApi.GoalUnitRequest;
import com.decorix.finance.core.api.GoalCandidatesApi.GoalUnitResponse;
import com.decorix.finance.core.api.GoalCandidatesApi.Overview;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/goals")
public class GoalController {
    private final GoalService goals;

    public GoalController(GoalService goals) { this.goals = goals; }

    @GetMapping
    Overview get(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return goals.get(tenantId, jwt.getSubject());
    }

    @PostMapping
    ResponseEntity<Goal> accept(@PathVariable UUID tenantId, @RequestBody AcceptRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        Goal created = goals.accept(tenantId, jwt.getSubject(), request);
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + tenantId + "/goals/" + created.id()))
                .body(created);
    }

    @PutMapping("/unit")
    GoalUnitResponse unit(@PathVariable UUID tenantId, @RequestBody GoalUnitRequest request,
                          @AuthenticationPrincipal Jwt jwt) {
        return goals.updateUnit(tenantId, jwt.getSubject(), request);
    }

    @PostMapping("/{goalId}/cancel")
    Goal cancel(@PathVariable UUID tenantId, @PathVariable UUID goalId, @AuthenticationPrincipal Jwt jwt) {
        return goals.cancel(tenantId, jwt.getSubject(), goalId);
    }
}
