package com.decorix.finance.core.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

final class TelegramLinkApi {
    private TelegramLinkApi() {}

    record LinkCodeResponse(String code, Instant expiresAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RedeemRequest(String code, Long telegramUserId, String telegramDisplayName) {}

    record RedeemResponse(String status) {}
}
