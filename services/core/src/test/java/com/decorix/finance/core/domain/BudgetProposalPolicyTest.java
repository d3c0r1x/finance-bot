package com.decorix.finance.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BudgetProposalPolicyTest {
    @Test
    void allocatesSeventyPercentByCurrentNonDebtSharesWithoutApplyingIt() {
        Map<String, String> current = Map.of(
                "еда", "20000.00", "транспорт", "5000.00", "жилье", "0.00", "досуг", "5000.00",
                "одежда", "10000.00", "здоровье", "0.00", "работа", "0.00", "техника", "10000.00",
                "долги", "0.00", "прочее", "5000.00");

        var proposal = BudgetProposalPolicy.propose("100000.00", current);

        assertThat(proposal.totalLimit()).isEqualTo("70000.00");
        assertThat(proposal.limits()).containsEntry("еда", "25500.00")
                .containsEntry("транспорт", "6400.00")
                .containsEntry("одежда", "12700.00")
                .containsEntry("долги", "0.00");
        assertThat(current.get("еда")).isEqualTo("20000.00");
    }

    @Test
    void rejectsInvalidIncomeAndDoesNotInventWeights() {
        assertThatThrownBy(() -> BudgetProposalPolicy.propose("-1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var proposal = BudgetProposalPolicy.propose("100000", Map.of());
        assertThat(proposal.totalLimit()).isEqualTo("70000.00");
        assertThat(proposal.limits().values()).containsOnly("0.00");
    }

    @Test
    void appliesValidatedHistorySharesInsideTheSeventyPercentIncomeCap() {
        var proposal = BudgetProposalPolicy.proposeFromHistoryShares("100000.00", Map.of(
                "еда", "20000.00", "транспорт", "5000.00", "жилье", "0.00", "досуг", "5000.00",
                "одежда", "10000.00", "здоровье", "0.00", "работа", "0.00", "техника", "10000.00",
                "прочее", "5000.00", "долги", "0.00"), Map.of(
                "еда", "40.00", "транспорт", "10.00", "жилье", "5.00", "досуг", "10.00",
                "одежда", "10.00", "здоровье", "5.00", "работа", "5.00", "техника", "10.00",
                "прочее", "5.00"));

        assertThat(proposal.totalLimit()).isEqualTo("70000.00");
        assertThat(proposal.limits()).containsEntry("еда", "28000.00")
                .containsEntry("транспорт", "7000.00").containsEntry("долги", "0.00");
        assertThat(proposal.limits().values().stream().map(java.math.BigDecimal::new)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)).isEqualByComparingTo("70000.00");
    }

    @Test
    void rejectsHistorySharesThatHaveMissingCategoriesInvalidValuesOrWrongTotal() {
        Map<String, String> current = Map.of(
                "еда", "20000.00", "транспорт", "5000.00", "жилье", "0.00", "досуг", "5000.00",
                "одежда", "10000.00", "здоровье", "0.00", "работа", "0.00", "техника", "10000.00",
                "прочее", "5000.00", "долги", "0.00");
        assertThatThrownBy(() -> BudgetProposalPolicy.proposeFromHistoryShares("100000", current,
                Map.of("intruder", "100.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetProposalPolicy.proposeFromHistoryShares("100000", current,
                Map.of("еда", "99.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
