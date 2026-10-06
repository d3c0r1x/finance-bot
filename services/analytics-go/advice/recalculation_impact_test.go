package advice

import "testing"

func TestBuildRecalculationImpactCalculatesExactBeforeAfterDelta(t *testing.T) {
	before := goldenRequest()
	before.Items = []WasteLine{wasteTestLine("123e4567-e89b-42d3-a456-426614174000", "chips", "Chips", "125.00", "model", "2026-10-04T10:00:00Z")}
	after := before
	after.Items = append([]WasteLine(nil), before.Items...)
	after.Items[0].Verdict = "useful"
	after.Items[0].VerdictSource = "rule"

	impact, err := BuildRecalculationImpact(RecalculationImpactRequest{Before: &before, After: &after})
	if err != nil {
		t.Fatal(err)
	}
	if impact.AlgorithmVersion != RecalculationImpactAlgorithmVersion || impact.ReasonCode != "available" ||
		impact.OptionalSpendBefore == nil || *impact.OptionalSpendBefore != "125.00" ||
		impact.OptionalSpendAfter == nil || *impact.OptionalSpendAfter != "0.00" ||
		impact.OptionalSpendDelta == nil || *impact.OptionalSpendDelta != "-125.00" {
		t.Fatalf("impact = %#v", impact)
	}
}

func TestBuildRecalculationImpactDoesNotInventDeltaWhenAmountMissing(t *testing.T) {
	before := goldenRequest()
	line := wasteTestLine("123e4567-e89b-42d3-a456-426614174000", "chips", "Chips", "125.00", "model", "2026-10-04T10:00:00Z")
	line.LineSum = nil
	before.Items = []WasteLine{line}
	after := before
	after.Items = append([]WasteLine(nil), before.Items...)
	after.Items[0].Verdict = "useful"
	after.Items[0].VerdictSource = "rule"

	impact, err := BuildRecalculationImpact(RecalculationImpactRequest{Before: &before, After: &after})
	if err != nil {
		t.Fatal(err)
	}
	if impact.ReasonCode != "missing_amounts" || impact.OptionalSpendBefore != nil ||
		impact.OptionalSpendAfter != nil || impact.OptionalSpendDelta != nil {
		t.Fatalf("missing amounts produced a fabricated impact: %#v", impact)
	}
}

func TestBuildRecalculationImpactRejectsFinancialFactChanges(t *testing.T) {
	before := goldenRequest()
	before.Items = []WasteLine{wasteTestLine("123e4567-e89b-42d3-a456-426614174000", "chips", "Chips", "125.00", "model", "2026-10-04T10:00:00Z")}
	after := before
	after.Items = append([]WasteLine(nil), before.Items...)
	after.Items[0].LineSum = testStringPointer("126.00")

	if _, err := BuildRecalculationImpact(RecalculationImpactRequest{Before: &before, After: &after}); err == nil {
		t.Fatal("expected changed receipt amount to be rejected")
	}
}
