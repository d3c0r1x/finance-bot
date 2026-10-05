package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TelegramActorContextApi.ActorContextResponse;
import com.decorix.finance.core.api.TelegramActorContextApi.IssueRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.ResolveRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.ResolvedContextResponse;
import com.decorix.finance.core.api.TelegramActorContextApi.TenantListRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.TenantListResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/telegram")
public class TelegramActorContextController {
    private final TelegramActorContextService contexts;
    private final String serviceToken;

    public TelegramActorContextController(TelegramActorContextService contexts,
                                          @Value("${finance.telegram.service-token:}") String serviceToken) {
        this.contexts = contexts;
        this.serviceToken = serviceToken;
    }

    @PostMapping("/tenants")
    TenantListResponse listTenants(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody TenantListRequest request) {
        requireServiceCredential(suppliedToken);
        return contexts.listTenants(request == null ? null : request.telegramUserId(),
                request == null ? null : request.telegramDisplayName());
    }

    @PostMapping("/actor-contexts")
    @ResponseStatus(HttpStatus.CREATED)
    ActorContextResponse issue(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody IssueRequest request) {
        requireServiceCredential(suppliedToken);
        return contexts.issue(request);
    }

    @PostMapping("/actor-contexts/resolve")
    ResolvedContextResponse resolve(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody ResolveRequest request) {
        requireServiceCredential(suppliedToken);
        return contexts.resolveResponse(request == null ? null : request.token());
    }

    private void requireServiceCredential(String suppliedToken) {
        if (serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Telegram service credential is not configured");
        }
        if (!TelegramServiceCredential.matches(serviceToken, suppliedToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Telegram service credential");
        }
    }
}
