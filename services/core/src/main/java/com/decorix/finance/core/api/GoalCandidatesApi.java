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
                            String monthlyLimit, String estimatedReduction, int purchaseCount, int evidenceCount,
                            List<String> memberProductKeys) {}
    public record SkippedCandidate(String productKey, String name, String monthlySpend, String reasonCode) {}
    public record Overview(String unit, Goal active, String inputWatermark,
                           List<Candidate> candidates, List<Candidate> groups,
                           List<SkippedCandidate> skipped, GoalProgress activeProgress,
                           List<GoalOutcome> history) {}
    public record Goal(UUID id, String key, String scope, String name, String unit,
                       String monthlyRate, int countTarget, String monthlySpend, String monthlyLimit,
                       int evidenceCount, String inputWatermark, Instant acceptedAt, Instant endsAt,
                       String status, long version) {}
    public record GoalUnitRequest(String unit) {}
    public record GoalUnitResponse(String unit) {}
    public record AcceptRequest(String candidateKey, String inputWatermark) {}
    public record F45GoalInput(String key, String scope, String unit, Instant acceptedAt, Instant endsAt,
                               int countTarget, String monthlyLimit, List<String> memberProductKeys) {}
    public record F45PurchaseInput(String productKey, String lineSum, Instant purchasedAt) {}
    public record ProgressRequest(String inputWatermark, Instant asOf, F45GoalInput goal,
                                  List<F45PurchaseInput> purchases) {}
    public record GoalProgress(String algorithmVersion, String inputWatermark, String unit, int bought,
                               String spent, boolean amountsUnknown, Boolean over, Boolean met,
                               boolean finished, int daysLeft, Instant windowStart, Instant windowEnd) {}
    public record GoalOutcome(UUID id, UUID goalId, String key, String name, String scope, String unit,
                              int countTarget, String monthlyLimit, int bought, String spent, Boolean met,
                              Instant acceptedAt, Instant completedAt, String origin) {}
}
