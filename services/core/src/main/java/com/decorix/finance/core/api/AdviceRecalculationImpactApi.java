package com.decorix.finance.core.api;

public final class AdviceRecalculationImpactApi {
    private AdviceRecalculationImpactApi() {}

    public record Request(AdviceWasteApi.Request before, AdviceWasteApi.Request after) {}

    public record Impact(String algorithmVersion, String inputVersion, String reasonCode, String completeness,
                         String optionalSpendBefore, String optionalSpendAfter, String optionalSpendDelta,
                         String currency) {
        public Impact(String algorithmVersion, String inputVersion, String reasonCode, String completeness,
                      String optionalSpendBefore, String optionalSpendAfter, String optionalSpendDelta) {
            this(algorithmVersion, inputVersion, reasonCode, completeness, optionalSpendBefore,
                    optionalSpendAfter, optionalSpendDelta, null);
        }

        public Impact withCurrency(String value) {
            return new Impact(algorithmVersion, inputVersion, reasonCode, completeness,
                    optionalSpendBefore, optionalSpendAfter, optionalSpendDelta, value);
        }
    }
}
