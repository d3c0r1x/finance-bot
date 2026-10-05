package com.decorix.finance.core.api;

import java.math.BigDecimal;

public final class MemberProfileApi {
    private MemberProfileApi() {}
    public record MemberResponse(java.util.UUID userId, String displayName, String role) {}
    public record UpdateProfileRequest(String displayName, BigDecimal plannedIncome, String onboardingState) {}
    public record ProfileResponse(String displayName, BigDecimal plannedIncome, String onboardingState,
                                  String timezone, String currency) {}
}
