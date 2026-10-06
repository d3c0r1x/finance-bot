package advice

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestBuildWasteReportMatchesVersionedGoldenFixture(t *testing.T) {
	fixturePath := filepath.Join("..", "..", "..", "contracts", "analytics", "advice-waste.v1.json")
	fixtureData, err := os.ReadFile(fixturePath)
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		AlgorithmVersion string         `json:"algorithmVersion"`
		Input            WasteRequest   `json:"input"`
		Expected         map[string]any `json:"expected"`
	}
	if err := json.Unmarshal(fixtureData, &fixture); err != nil {
		t.Fatal(err)
	}
	if fixture.AlgorithmVersion != WasteAlgorithmVersion {
		t.Fatalf("fixture algorithm version = %q, want %q", fixture.AlgorithmVersion, WasteAlgorithmVersion)
	}

	got, err := BuildWasteReport(fixture.Input)
	if err != nil {
		t.Fatal(err)
	}
	actualData, err := json.Marshal(got)
	if err != nil {
		t.Fatal(err)
	}
	var actual map[string]any
	if err := json.Unmarshal(actualData, &actual); err != nil {
		t.Fatal(err)
	}
	for key, want := range fixture.Expected {
		if !reflect.DeepEqual(actual[key], want) {
			t.Errorf("%s = %#v, want %#v", key, actual[key], want)
		}
	}
	if got.InputVersion == "" {
		t.Fatal("input version is empty")
	}
}

func TestBuildWasteReportDoesNotTurnMissingVerdictsIntoNeutralEvidence(t *testing.T) {
	request := goldenRequest()
	request.Items = []WasteLine{
		{ItemID: "00000000-0000-4000-8000-000000000010", ProductKey: "known", Name: "Known", LineSum: testStringPointer("0.00"), Verdict: "neutral", VerdictSource: "unknown", PurchasedAt: mustTime("2026-10-04T12:00:00Z"), ItemVersion: 1},
		{ItemID: "00000000-0000-4000-8000-000000000011", ProductKey: "unknown", Name: "Unreviewed", LineSum: testStringPointer("800.00"), Verdict: "", VerdictSource: "default", PurchasedAt: mustTime("2026-10-04T13:00:00Z"), ItemVersion: 1},
	}

	got, err := BuildWasteReport(request)
	if err != nil {
		t.Fatal(err)
	}
	if !got.Available || got.ReviewedItemCount != 1 {
		t.Fatalf("reviewed evidence count = %d, available = %t", got.ReviewedItemCount, got.Available)
	}
	if got.ReviewedSpend == nil || *got.ReviewedSpend != "0.00" || got.OptionalShare == nil || *got.OptionalShare != "0.000" {
		t.Fatalf("zero reviewed spend result = %#v", got)
	}
	if len(got.ByVerdict) != 0 || len(got.BySource) != 0 {
		t.Fatalf("neutral or missing verdict became optional evidence: %#v", got)
	}
}

func TestBuildWasteReportReturnsNoNumbersWhenReviewedAmountIsMissing(t *testing.T) {
	request := goldenRequest()
	request.Items = []WasteLine{{
		ItemID: "00000000-0000-4000-8000-000000000020", ProductKey: "chips", Name: "Чипсы",
		Verdict: "harmful", VerdictSource: "model", PurchasedAt: mustTime("2026-10-04T12:00:00Z"), ItemVersion: 1,
	}}

	got, err := BuildWasteReport(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.Available || got.ReasonCode != "missing_amounts" || got.Completeness != "partial" || got.MissingAmountCount != 1 {
		t.Fatalf("missing amount result = %#v", got)
	}
	if got.ReviewedSpend != nil || got.OptionalSpend != nil || got.OptionalShare != nil || len(got.OptionalByDay) != 0 {
		t.Fatalf("partial data exposed fabricated totals: %#v", got)
	}
}

func TestBuildWasteReportPreservesAllVerdictSourcesAndRepeatedProducts(t *testing.T) {
	request := goldenRequest()
	request.Items = []WasteLine{
		wasteTestLine("00000000-0000-4000-8000-000000000040", "soda", "Газировка", "2.00", "rule", "2026-10-04T10:00:00Z"),
		wasteTestLine("00000000-0000-4000-8000-000000000041", "chips", "Чипсы Lays 120г", "3.00", "model", "2026-10-04T11:00:00Z"),
		wasteTestLine("00000000-0000-4000-8000-000000000042", "candy", "Сладость", "5.00", "default", "2026-10-04T12:00:00Z"),
		wasteTestLine("00000000-0000-4000-8000-000000000043", "juice", "Сок", "4.00", "human", "2026-10-04T13:00:00Z"),
		wasteTestLine("00000000-0000-4000-8000-000000000044", "other", "Другое", "6.00", "old-provider", "2026-10-04T14:00:00Z"),
		wasteTestLine("00000000-0000-4000-8000-000000000045", "chips", "ЧИПСЫ LAYS 120Г", "7.00", "model", "2026-10-05T14:00:00Z"),
	}

	got, err := BuildWasteReport(request)
	if err != nil {
		t.Fatal(err)
	}
	wantSources := map[string]string{"rule": "2.00", "model": "10.00", "default": "5.00", "human": "4.00", "unknown": "6.00"}
	if !reflect.DeepEqual(got.BySource, wantSources) {
		t.Fatalf("source totals = %#v, want %#v", got.BySource, wantSources)
	}
	if len(got.Repeats) != 1 || got.Repeats[0].ProductKey != "chips" ||
		got.Repeats[0].Count != 2 || got.Repeats[0].Amount != "10.00" ||
		got.Repeats[0].ProductName != "ЧИПСЫ LAYS 120Г" {
		t.Fatalf("repeat grouping = %#v", got.Repeats)
	}
}

func TestBuildWasteReportCountsUnicodeCharactersForNameLimit(t *testing.T) {
	request := goldenRequest()
	request.Items = []WasteLine{wasteTestLine(
		"00000000-0000-4000-8000-000000000046", "товар", strings.Repeat("я", 101), "1.00", "rule", "2026-10-04T12:00:00Z")}
	if _, err := BuildWasteReport(request); err != nil {
		t.Fatalf("valid 101-character Unicode name was rejected: %v", err)
	}
}

func TestBuildWasteReportRoundsShareHalfEvenAndKeepsExactLargeTotals(t *testing.T) {
	request := goldenRequest()
	request.Items = []WasteLine{
		wasteTestLine("00000000-0000-4000-8000-000000000050", "chips", "Чипсы", "1.00", "rule", "2026-10-04T12:00:00Z"),
		wasteTestLine("00000000-0000-4000-8000-000000000051", "milk", "Молоко", "1999.00", "neutral", "2026-10-04T13:00:00Z"),
	}
	request.Items[1].Verdict = "neutral"
	got, err := BuildWasteReport(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.OptionalShare == nil || *got.OptionalShare != "0.000" {
		t.Fatalf("share = %v, want 0.000", got.OptionalShare)
	}

	request.Items = []WasteLine{
		wasteTestLine("00000000-0000-4000-8000-000000000052", "chips", "Чипсы", "999999999999999999.99", "rule", "2026-10-04T12:00:00Z"),
		wasteTestLine("00000000-0000-4000-8000-000000000053", "other", "Товар", "1.00", "neutral", "2026-10-04T13:00:00Z"),
	}
	request.Items[1].Verdict = "neutral"
	got, err = BuildWasteReport(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.ReviewedSpend == nil || *got.ReviewedSpend != "1000000000000000000.99" {
		t.Fatalf("large exact total = %v", got.ReviewedSpend)
	}
}

func TestBuildWasteReportNoReviewedItemsHasNoFabricatedAmounts(t *testing.T) {
	request := goldenRequest()
	request.Items = []WasteLine{wasteTestLine(
		"00000000-0000-4000-8000-000000000060", "tea", "Чай", "900.00", "default", "2026-10-04T12:00:00Z")}
	request.Items[0].Verdict = ""

	got, err := BuildWasteReport(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.Available || got.ReasonCode != "no_reviewed_items" || got.ReviewedSpend != nil ||
		got.OptionalSpend != nil || got.OptionalShare != nil || len(got.OptionalByDay) != 0 {
		t.Fatalf("no-evidence result = %#v", got)
	}
}

func TestBuildWasteReportInputVersionIgnoresInputOrder(t *testing.T) {
	request := goldenRequest()
	first, err := BuildWasteReport(request)
	if err != nil {
		t.Fatal(err)
	}
	for left, right := 0, len(request.Items)-1; left < right; left, right = left+1, right-1 {
		request.Items[left], request.Items[right] = request.Items[right], request.Items[left]
	}
	second, err := BuildWasteReport(request)
	if err != nil {
		t.Fatal(err)
	}
	if first.InputVersion == "" || first.InputVersion != second.InputVersion {
		t.Fatalf("input versions differ by row order: %q != %q", first.InputVersion, second.InputVersion)
	}
}

func TestBuildWasteReportRejectsDuplicateIDsAndOutOfWindowItems(t *testing.T) {
	request := goldenRequest()
	request.Items = []WasteLine{
		{ItemID: "00000000-0000-4000-8000-000000000030", ProductKey: "one", Name: "One", LineSum: stringPointer("1.00"), Verdict: "harmful", PurchasedAt: mustTime("2026-10-04T12:00:00Z"), ItemVersion: 1},
		{ItemID: "00000000-0000-4000-8000-000000000030", ProductKey: "two", Name: "Two", LineSum: stringPointer("2.00"), Verdict: "useful", PurchasedAt: mustTime("2026-10-04T13:00:00Z"), ItemVersion: 1},
	}
	if _, err := BuildWasteReport(request); err == nil {
		t.Fatal("duplicate item IDs were accepted")
	}
	request.Items = request.Items[:1]
	request.Items[0].PurchasedAt = mustTime("2026-10-06T12:00:00Z")
	if _, err := BuildWasteReport(request); err == nil {
		t.Fatal("out-of-window item was accepted")
	}
}

func goldenRequest() WasteRequest {
	fixturePath := filepath.Join("..", "..", "..", "contracts", "analytics", "advice-waste.v1.json")
	fixtureData, err := os.ReadFile(fixturePath)
	if err != nil {
		panic(err)
	}
	var fixture struct {
		Input WasteRequest `json:"input"`
	}
	if err := json.Unmarshal(fixtureData, &fixture); err != nil {
		panic(err)
	}
	return fixture.Input
}

func testStringPointer(value string) *string { return &value }

func wasteTestLine(id, key, name, amount, source, purchasedAt string) WasteLine {
	return WasteLine{
		ItemID: id, ProductKey: key, Name: name, LineSum: testStringPointer(amount), Verdict: "harmful",
		VerdictSource: source, PurchasedAt: mustTime(purchasedAt), ItemVersion: 1,
	}
}

func mustTime(value string) time.Time {
	parsed, err := time.Parse(time.RFC3339, value)
	if err != nil {
		panic(err)
	}
	return parsed
}
