package com.decorix.finance.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GoalCandidatesApi {
    private GoalCandidatesApi() {}

    public record Request(String inputWatermark, Instant asOf, String unit,
                          List<Decision> decisions, List<Purchase> purchases) {}
    public record Decision(String productKey, String name, int harmfulCount,
                           boolean modelGuess, boolean confirmed, boolean allowed) {}
    public record Purchase(String productKey, String name, String lineSum, Instant purchasedAt) {}
    public record CandidateReport(String algorithmVersion, String inputWatermark, String unit,
                                  List<Candidate> products, List<Candidate> groups, List<SkippedCandidate> skipped) {}
    public record Candidate(String key, String productKey, String name, String unit,
                            String monthlyRate, int countTarget, String monthlySpend,
                            String monthlyLimit, String estimatedReduction, int purchaseCount, int evidenceCount) {}
    public record SkippedCandidate(String productKey, String name, String monthlySpend, String reasonCode) {}
    public record Overview(String unit, Goal active, String inputWatermark,
                           List<Candidate> candidates, List<Candidate> groups,
                           List<SkippedCandidate> skipped) {}
    public record Goal(UUID id, String key, String scope, String name, String unit,
                       String monthlyRate, int countTarget, String monthlySpend, String monthlyLimit,
                       int evidenceCount, String inputWatermark, Instant acceptedAt, Instant endsAt,
                       String status, long version) {}
    public record GoalUnitRequest(String unit) {}
    public record GoalUnitResponse(String unit) {}
    public record AcceptRequest(String candidateKey, String inputWatermark) {}
}
