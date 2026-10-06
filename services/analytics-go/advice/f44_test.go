package advice

import (
	"encoding/json"
	"os"
	"reflect"
	"testing"
	"time"
)

func TestBuildF44CandidatesMatchesGoldenFixture(t *testing.T) {
	data, err := os.ReadFile("../../../contracts/analytics/goal-candidates-f44.v1.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Input    F44Request `json:"input"`
		Expected F44Report  `json:"expected"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	got, err := BuildF44Candidates(fixture.Input)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, fixture.Expected) {
		t.Fatalf("F44 fixture mismatch\n got: %#v\nwant: %#v", got, fixture.Expected)
	}
}

func f44Time(day string) time.Time {
	value, err := time.Parse(time.RFC3339, day)
	if err != nil {
		panic(err)
	}
	return value
}

func f44Amount(value string) *string {
	return &value
}

func f44ProductRequest(unit string) F44Request {
	return F44Request{
		InputWatermark: "8", AsOf: f44Time("2026-10-07T12:00:00Z"), Unit: unit,
		Decisions: []F44Decision{
			{ProductKey: "snack", Name: "Снеки", HarmfulCount: 2},
			{ProductKey: "guess", Name: "Гадание", HarmfulCount: 4, ModelGuess: true},
			{ProductKey: "confirmedguess", Name: "Подтверждено", HarmfulCount: 2, ModelGuess: true, Confirmed: true},
			{ProductKey: "allowed", Name: "Разрешено", HarmfulCount: 3, Allowed: true},
		},
		Purchases: []F44Purchase{
			{ProductKey: "snack", Name: "Снеки", LineSum: f44Amount("100.00"), PurchasedAt: f44Time("2026-09-07T12:00:00Z")},
			{ProductKey: "snack", Name: "Снеки", LineSum: f44Amount("100.00"), PurchasedAt: f44Time("2026-09-17T12:00:00Z")},
			{ProductKey: "snack", Name: "Снеки", LineSum: f44Amount("100.00"), PurchasedAt: f44Time("2026-09-27T12:00:00Z")},
			{ProductKey: "snack", Name: "Снеки", LineSum: f44Amount("100.00"), PurchasedAt: f44Time("2026-10-07T12:00:00Z")},
			{ProductKey: "guess", Name: "Гадание", LineSum: f44Amount("500.00"), PurchasedAt: f44Time("2026-09-07T12:00:00Z")},
			{ProductKey: "guess", Name: "Гадание", LineSum: f44Amount("500.00"), PurchasedAt: f44Time("2026-10-07T12:00:00Z")},
			{ProductKey: "confirmedguess", Name: "Подтверждено", LineSum: f44Amount("120.00"), PurchasedAt: f44Time("2026-09-07T12:00:00Z")},
			{ProductKey: "confirmedguess", Name: "Подтверждено", LineSum: f44Amount("120.00"), PurchasedAt: f44Time("2026-10-07T12:00:00Z")},
			{ProductKey: "allowed", Name: "Разрешено", LineSum: f44Amount("1000.00"), PurchasedAt: f44Time("2026-09-07T12:00:00Z")},
			{ProductKey: "allowed", Name: "Разрешено", LineSum: f44Amount("1000.00"), PurchasedAt: f44Time("2026-10-07T12:00:00Z")},
		},
	}
}

func TestBuildF44CandidatesUsesConfirmedFactsAndFixedCountTarget(t *testing.T) {
	request := f44ProductRequest("count")
	got, err := BuildF44Candidates(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Products) != 2 {
		t.Fatalf("expected only established and explicitly confirmed products, got %#v", got.Products)
	}
	if got.Products[0].ProductKey != "snack" || got.Products[0].MonthlyRate != "4.00" || got.Products[0].CountTarget != 2 {
		t.Fatalf("unexpected count candidate: %#v", got.Products[0])
	}
	if got.Products[1].ProductKey != "confirmedguess" || got.Products[1].MonthlyRate != "2.00" || got.Products[1].CountTarget != 1 {
		t.Fatalf("two purchases per 30 days is an eligible inclusive boundary: %#v", got.Products[1])
	}
	for _, candidate := range got.Products {
		if candidate.ProductKey == "guess" || candidate.ProductKey == "allowed" {
			t.Fatalf("unconfirmed guess or allowed product leaked into candidates: %#v", candidate)
		}
	}
}

func TestBuildF44CandidatesRejectsBelowTwoPurchasesPerMonthEquivalent(t *testing.T) {
	request := F44Request{
		InputWatermark: "1", AsOf: f44Time("2026-10-07T12:00:00Z"), Unit: "count",
		Decisions: []F44Decision{{ProductKey: "rare", Name: "Редкое", HarmfulCount: 2}},
		Purchases: []F44Purchase{
			{ProductKey: "rare", Name: "Редкое", LineSum: f44Amount("400.00"), PurchasedAt: f44Time("2026-08-08T12:00:00Z")},
			{ProductKey: "rare", Name: "Редкое", LineSum: f44Amount("400.00"), PurchasedAt: f44Time("2026-10-07T12:00:00Z")},
		},
	}
	got, err := BuildF44Candidates(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Products) != 0 {
		t.Fatalf("one purchase per 30 days must not produce a reduction target: %#v", got.Products)
	}
}

func TestBuildF44CandidatesNeverTurnsUnknownAmountsIntoZero(t *testing.T) {
	request := F44Request{
		InputWatermark: "9", AsOf: f44Time("2026-10-07T12:00:00Z"), Unit: "count",
		Decisions: []F44Decision{{ProductKey: "snack", Name: "Снеки", HarmfulCount: 2}},
		Purchases: []F44Purchase{
			{ProductKey: "snack", Name: "Снеки", LineSum: nil, PurchasedAt: f44Time("2026-09-07T12:00:00Z")},
			{ProductKey: "snack", Name: "Снеки", LineSum: nil, PurchasedAt: f44Time("2026-10-07T12:00:00Z")},
		},
	}
	countReport, err := BuildF44Candidates(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(countReport.Products) != 1 || countReport.Products[0].MonthlySpend != nil || countReport.Products[0].EstimatedReduction != nil {
		t.Fatalf("count goal may use confirmed purchase frequency, but unknown amounts must remain unknown: %#v", countReport.Products)
	}

	request.Unit = "sum"
	sumReport, err := BuildF44Candidates(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(sumReport.Products) != 0 || len(sumReport.Skipped) != 1 || sumReport.Skipped[0].ReasonCode != "missing_amounts" {
		t.Fatalf("sum goal must expose missing amounts instead of fabricating zero: %#v", sumReport)
	}
}

func TestBuildF44CandidatesBuildsOnlyCountGroupsFromCombinedEvidence(t *testing.T) {
	request := F44Request{
		InputWatermark: "3", AsOf: f44Time("2026-10-07T12:00:00Z"), Unit: "sum",
		Decisions: []F44Decision{
			{ProductKey: "choco", Name: "Шоколад горький", HarmfulCount: 1},
			{ProductKey: "cookie", Name: "Печенье овсяное", HarmfulCount: 1},
			{ProductKey: "cola", Name: "Кола", HarmfulCount: 2, Allowed: true},
			{ProductKey: "sausage", Name: "Колбаса", HarmfulCount: 2},
		},
		Purchases: []F44Purchase{
			{ProductKey: "choco", Name: "Шоколад горький", LineSum: f44Amount("300.00"), PurchasedAt: f44Time("2026-09-07T12:00:00Z")},
			{ProductKey: "cookie", Name: "Печенье овсяное", LineSum: f44Amount("200.00"), PurchasedAt: f44Time("2026-09-22T12:00:00Z")},
			{ProductKey: "cola", Name: "Кола", LineSum: f44Amount("100.00"), PurchasedAt: f44Time("2026-09-07T12:00:00Z")},
			{ProductKey: "cola", Name: "Кола", LineSum: f44Amount("100.00"), PurchasedAt: f44Time("2026-10-07T12:00:00Z")},
			{ProductKey: "sausage", Name: "Колбаса", LineSum: f44Amount("100.00"), PurchasedAt: f44Time("2026-09-07T12:00:00Z")},
			{ProductKey: "sausage", Name: "Колбаса", LineSum: f44Amount("100.00"), PurchasedAt: f44Time("2026-10-07T12:00:00Z")},
		},
	}
	got, err := BuildF44Candidates(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Groups) != 1 || got.Groups[0].Key != "cat:сладкое" || got.Groups[0].Unit != "count" {
		t.Fatalf("expected one sweets count candidate without allowed/substring false positives: %#v", got.Groups)
	}
	if !reflect.DeepEqual(got.Groups[0].MemberProductKeys, []string{"choco", "cookie"}) {
		t.Fatalf("accepted group must snapshot only eligible product keys, excluding allowed cola: %#v", got.Groups[0].MemberProductKeys)
	}
	if got.Groups[0].MonthlyLimit != "" {
		t.Fatalf("sum mode must not return a group money target: %#v", got.Groups[0])
	}
}

func TestBuildF44CandidatesSumTargetHasMinimumMeaningfulSavings(t *testing.T) {
	request := F44Request{
		InputWatermark: "2", AsOf: f44Time("2026-10-07T12:00:00Z"), Unit: "sum",
		Decisions: []F44Decision{{ProductKey: "small", Name: "Дешёвый товар", HarmfulCount: 2}},
		Purchases: []F44Purchase{
			{ProductKey: "small", Name: "Дешёвый товар", LineSum: f44Amount("30.00"), PurchasedAt: f44Time("2026-09-07T12:00:00Z")},
			{ProductKey: "small", Name: "Дешёвый товар", LineSum: f44Amount("30.00"), PurchasedAt: f44Time("2026-09-17T12:00:00Z")},
			{ProductKey: "small", Name: "Дешёвый товар", LineSum: f44Amount("30.00"), PurchasedAt: f44Time("2026-09-27T12:00:00Z")},
			{ProductKey: "small", Name: "Дешёвый товар", LineSum: f44Amount("30.00"), PurchasedAt: f44Time("2026-10-07T12:00:00Z")},
		},
	}
	got, err := BuildF44Candidates(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Products) != 0 || len(got.Skipped) != 1 || got.Skipped[0].ProductKey != "small" {
		t.Fatalf("sum mode must explain a missing meaningful step instead of silently dropping it: %#v", got)
	}
	request.Purchases = []F44Purchase{
		{ProductKey: "small", Name: "Дешёвый товар", LineSum: f44Amount("410.00"), PurchasedAt: f44Time("2026-09-07T12:00:00Z")},
		{ProductKey: "small", Name: "Дешёвый товар", LineSum: f44Amount("410.00"), PurchasedAt: f44Time("2026-09-17T12:00:00Z")},
		{ProductKey: "small", Name: "Дешёвый товар", LineSum: f44Amount("410.00"), PurchasedAt: f44Time("2026-09-27T12:00:00Z")},
		{ProductKey: "small", Name: "Дешёвый товар", LineSum: f44Amount("410.00"), PurchasedAt: f44Time("2026-10-07T12:00:00Z")},
	}
	got, err = BuildF44Candidates(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Products) != 1 || got.Products[0].MonthlyLimit != "820.00" {
		t.Fatalf("expected a rounded actionable ceiling of 820 RUB, got %#v", got.Products)
	}
}

func TestBuildF44CandidatesRejectsInvalidInputs(t *testing.T) {
	request := f44ProductRequest("count")
	request.Unit = "weekly"
	if _, err := BuildF44Candidates(request); err == nil {
		t.Fatal("expected unsupported goal unit to be rejected")
	}
	request = f44ProductRequest("count")
	request.InputWatermark = "0"
	if _, err := BuildF44Candidates(request); err == nil {
		t.Fatal("expected non-monotonic watermark to be rejected")
	}
	request = f44ProductRequest("count")
	request.Purchases[0].LineSum = f44Amount("1e6")
	if _, err := BuildF44Candidates(request); err == nil {
		t.Fatal("expected non-canonical money input to be rejected")
	}
	request = f44ProductRequest("count")
	request.Decisions = append(request.Decisions, request.Decisions[0])
	if _, err := BuildF44Candidates(request); err == nil {
		t.Fatal("expected duplicate decisions to be rejected")
	}
	request = f44ProductRequest("count")
	request.Decisions = make([]F44Decision, f44MaxInputs/2+1)
	request.Purchases = make([]F44Purchase, f44MaxInputs/2)
	if _, err := BuildF44Candidates(request); err == nil {
		t.Fatal("expected oversized snapshot to be rejected before calculation")
	}
}

func TestBuildF44CandidatesSortsByReductionThenPurchaseCountAndCapsProducts(t *testing.T) {
	request := F44Request{InputWatermark: "5", AsOf: f44Time("2026-10-07T12:00:00Z"), Unit: "count"}
	counts := []int{2, 3, 2, 4}
	for index, count := range counts {
		key := []string{"a", "b", "c", "d"}[index]
		request.Decisions = append(request.Decisions, F44Decision{ProductKey: key, Name: "Товар " + key, HarmfulCount: 2})
		for purchase := 0; purchase < count; purchase++ {
			day := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC).AddDate(0, 0, -(count-1-purchase)*30/(count-1))
			request.Purchases = append(request.Purchases, F44Purchase{ProductKey: key, Name: "Товар " + key, LineSum: f44Amount("100.00"), PurchasedAt: day})
		}
	}
	got, err := BuildF44Candidates(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Products) != 3 {
		t.Fatalf("expected top three products, got %d", len(got.Products))
	}
	if got.Products[0].ProductKey != "d" || got.Products[1].ProductKey != "b" || got.Products[2].ProductKey != "a" {
		t.Fatalf("unexpected stable candidate order: %#v", got.Products)
	}
}
