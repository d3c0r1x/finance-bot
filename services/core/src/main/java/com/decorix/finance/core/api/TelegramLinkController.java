package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TelegramLinkApi.RedeemRequest;
import com.decorix.finance.core.api.TelegramLinkApi.RedeemResponse;
import com.decorix.finance.core.api.TelegramLinkService.RedemptionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/telegram")
public class TelegramLinkController {
    private final TelegramLinkService links;
    private final String serviceToken;

    public TelegramLinkController(TelegramLinkService links,
                                  @Value("${finance.telegram.service-token:}") String serviceToken) {
        this.links = links;
        this.serviceToken = serviceToken;
    }

    @PostMapping("/link-codes/redeem")
    RedeemResponse redeem(@RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
                          @RequestBody RedeemRequest request) {
        if (serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telegram service credential is not configured");
        }
        if (!TelegramServiceCredential.matches(serviceToken, suppliedToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Telegram service credential");
        }
        RedemptionResult result = links.redeem(request);
        return switch (result) {
            case LINKED -> new RedeemResponse("linked");
            case INVALID -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Link code is invalid or expired");
            case CONFLICT -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Telegram account cannot be linked");
            case RATE_LIMITED -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Link attempts are temporarily limited");
        };
    }
}
