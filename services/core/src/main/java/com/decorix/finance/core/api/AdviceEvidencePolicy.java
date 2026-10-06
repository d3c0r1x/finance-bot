package com.decorix.finance.core.api;

import com.decorix.finance.core.api.AdviceEvidenceApi.EvidenceGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Combines Go evidence with member-owned decisions without persisting inferred decisions. */
public final class AdviceEvidencePolicy {
    private AdviceEvidencePolicy() {}

    public static Classified apply(List<EvidenceGroup> groups, Map<String, String> decisions) {
        List<EvidenceGroup> banned = new ArrayList<>();
        List<EvidenceGroup> guesses = new ArrayList<>();
        for (EvidenceGroup group : groups) {
            String decision = decisions.get(group.productKey());
            if ("allowed".equals(decision)) continue;
            if ("confirmed".equals(decision) || group.ruleCount() > 0) banned.add(group);
            else guesses.add(group);
        }
        return new Classified(List.copyOf(banned), List.copyOf(guesses));
    }

    public record Classified(List<EvidenceGroup> banned, List<EvidenceGroup> guesses) {}
}
