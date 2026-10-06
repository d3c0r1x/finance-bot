package advice

import (
	"encoding/json"
	"fmt"
	"hash/crc32"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestBuildF43ReportMatchesVersionedGoldenFixture(t *testing.T) {
	fixturePath := filepath.Join("..", "..", "..", "contracts", "analytics", "advice-f43.v1.json")
	data, err := os.ReadFile(fixturePath)
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Input    F43Request     `json:"input"`
		Expected map[string]any `json:"expected"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	got, err := BuildF43Report(fixture.Input)
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(got)
	if err != nil {
		t.Fatal(err)
	}
	var actual map[string]any
	if err := json.Unmarshal(encoded, &actual); err != nil {
		t.Fatal(err)
	}
	for key, want := range fixture.Expected {
		assertF43JSONSubset(t, key, actual[key], want)
	}
}

func assertF43JSONSubset(t *testing.T, path string, actual, expected any) {
	t.Helper()
	switch want := expected.(type) {
	case map[string]any:
		got, ok := actual.(map[string]any)
		if !ok {
			t.Errorf("%s has type %T, want object", path, actual)
			return
		}
		for key, value := range want {
			assertF43JSONSubset(t, path+"."+key, got[key], value)
		}
	case []any:
		got, ok := actual.([]any)
		if !ok || len(got) != len(want) {
			t.Errorf("%s = %#v, want %d-item array", path, actual, len(want))
			return
		}
		for i := range want {
			assertF43JSONSubset(t, fmt.Sprintf("%s[%d]", path, i), got[i], want[i])
		}
	default:
		if !reflect.DeepEqual(actual, expected) {
			t.Errorf("%s = %#v, want %#v", path, actual, expected)
		}
	}
}

func TestBuildF43ReportSavingsCeilingRequiresRepeatedWasteAndExcludesAllowed(t *testing.T) {
	request := f43TestRequest("42", "2026-10-07T12:00:00Z", []F43Item{
		f43TestItem("soda-1", "soda", "Газировка", "180.00", "harmful", "2026-10-01T12:00:00Z"),
		f43TestItem("soda-2", "soda", "Газировка", "180.00", "unnecessary", "2026-09-01T12:00:00Z"),
		f43TestItem("candy-1", "candy", "Конфета", "500.00", "harmful", "2026-10-02T12:00:00Z"),
		f43TestItem("chips-1", "chips", "Чипсы", "900.00", "harmful", "2026-10-02T12:00:00Z"),
		f43TestItem("chips-2", "chips", "Чипсы", "900.00", "harmful", "2026-09-03T12:00:00Z"),
	})
	request.Items[3].Allowed = true
	request.Items[4].Allowed = true

	got, err := BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	if !got.Savings.Available || got.Savings.MonthlyCeiling == nil || *got.Savings.MonthlyCeiling != "120.00" {
		t.Fatalf("monthly theoretical ceiling = %#v, want 120.00", got.Savings)
	}
	if len(got.Savings.Groups) != 1 || got.Savings.Groups[0].ProductKey != "soda" || got.Savings.Groups[0].Count != 2 {
		t.Fatalf("eligible repeated groups = %#v, want only repeated non-allowed soda", got.Savings.Groups)
	}
	if got.Savings.Label == "" {
		t.Fatal("savings amount needs explicit theoretical-ceiling label")
	}
}

func TestBuildF43ReportTrendRequiresTwoWeeksAndUsesKnownSpendShares(t *testing.T) {
	request := f43TestRequest("43", "2026-10-07T12:00:00Z", []F43Item{
		f43TestItem("old-waste", "soda", "Газировка", "40.00", "harmful", "2026-09-17T12:00:00Z"),
		f43TestItem("old-useful", "bread", "Хлеб", "60.00", "useful", "2026-09-17T13:00:00Z"),
		f43TestItem("new-waste", "chips", "Чипсы", "20.00", "unnecessary", "2026-10-05T12:00:00Z"),
		f43TestItem("new-unknown", "tea", "Чай", "80.00", "", "2026-10-05T13:00:00Z"),
	})

	got, err := BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	if !got.Trend.Available || len(got.Trend.Weeks) != 2 {
		t.Fatalf("two data-bearing windows should produce trend: %#v", got.Trend)
	}
	if got.Trend.Weeks[0].OptionalShare != "0.400" || got.Trend.Weeks[1].OptionalShare != "0.200" {
		t.Fatalf("spend shares = %#v, want .400 then .200; unknown verdict must remain denominator-only", got.Trend.Weeks)
	}

	request.Items = request.Items[:2]
	got, err = BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.Trend.Available || got.Trend.ReasonCode != "insufficient_history" || len(got.Trend.Weeks) != 1 {
		t.Fatalf("one populated window must be unavailable, not a zero trend: %#v", got.Trend)
	}
}

func TestBuildF43ReportMissingAmountsNeverBecomeZero(t *testing.T) {
	request := f43TestRequest("44", "2026-10-07T12:00:00Z", []F43Item{
		f43TestItem("missing", "soda", "Газировка", "", "harmful", "2026-10-05T12:00:00Z"),
		f43TestItem("known", "bread", "Хлеб", "60.00", "useful", "2026-09-17T12:00:00Z"),
	})

	got, err := BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.Completeness != "partial" || got.ReasonCode != "missing_amounts" {
		t.Fatalf("missing amount status = %#v, want partial/missing_amounts", got)
	}
	if got.Savings.MonthlyCeiling != nil || got.Trend.Available {
		t.Fatalf("partial sums were presented as complete totals: %#v", got)
	}
}

func TestBuildF43ReportAdviceEffectNeedsHistoryAndWaitsTwentyOneDays(t *testing.T) {
	items := []F43Item{
		f43TestItem("before-1", "chips", "Чипсы", "20.00", "neutral", "2026-09-01T12:00:00Z"),
		f43TestItem("before-2", "chips", "Чипсы", "20.00", "harmful", "2026-09-15T12:00:00Z"),
		f43TestItem("advice", "chips", "Чипсы", "20.00", "harmful", "2026-10-01T12:00:00Z"),
	}
	items[1].AdviceGiven = false
	request := f43TestRequest("45", "2026-10-30T12:00:00Z", items)

	got, err := BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Effects.Effects) != 1 || got.Effects.Effects[0].Direction != "less_often" || got.Effects.Effects[0].AfterCount != 0 {
		t.Fatalf("zero purchases after a mature advice window = %#v", got.Effects)
	}
	if got.Effects.CausalityClaim {
		t.Fatal("effect report must never assert causality")
	}

	request.AsOf = time.Date(2026, 10, 15, 12, 0, 0, 0, time.UTC)
	got, err = BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Effects.Pending) != 1 || got.Effects.Pending[0].DaysLeft != 7 || len(got.Effects.Effects) != 0 {
		t.Fatalf("advice younger than 21 days should be pending: %#v", got.Effects)
	}

	request.Items = []F43Item{items[0], items[2]}
	request.AsOf = time.Date(2026, 10, 30, 12, 0, 0, 0, time.UTC)
	got, err = BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Effects.Effects) != 0 && len(got.Effects.Pending) != 0 {
		t.Fatalf("one purchase before advice must not create an effect: %#v", got.Effects)
	}
}

func TestBuildF43ReportCadenceUsesStrictTwentyPercentBoundary(t *testing.T) {
	base := []F43Item{
		f43TestItem("before-1", "tea", "Чай", "10.00", "neutral", "2026-09-11T12:00:00Z"),
		f43TestItem("before-2", "tea", "Чай", "10.00", "neutral", "2026-09-21T12:00:00Z"),
		f43TestItem("advice", "tea", "Чай", "10.00", "harmful", "2026-10-01T12:00:00Z"),
	}
	request := f43TestRequest("46", "2026-10-26T12:00:00Z", append([]F43Item(nil), base...))
	request.Items = append(request.Items,
		f43TestItem("after-1", "tea", "Чай", "10.00", "neutral", "2026-10-05T12:00:00Z"),
		f43TestItem("after-2", "tea", "Чай", "10.00", "neutral", "2026-10-20T12:00:00Z"),
	)
	got, err := BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Effects.Effects) != 1 || got.Effects.Effects[0].Change != "0.20" || got.Effects.Effects[0].Direction != "same_frequency" {
		t.Fatalf("exactly 20%% cadence movement must remain unchanged: %#v", got.Effects)
	}

	request.Items = append(request.Items,
		f43TestItem("after-3", "tea", "Чай", "10.00", "neutral", "2026-10-22T12:00:00Z"),
		f43TestItem("after-4", "tea", "Чай", "10.00", "neutral", "2026-10-23T12:00:00Z"),
	)
	got, err = BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.Effects.Effects[0].Direction != "more_often" {
		t.Fatalf("movement beyond 20%% should be more often: %#v", got.Effects.Effects[0])
	}
}

func TestBuildF43ReportF42AnnotationDoesNotChangePurchaseSpendOrCadence(t *testing.T) {
	request := f43TestRequest("47", "2026-10-30T12:00:00Z", []F43Item{
		f43TestItem("before-1", "chips", "Чипсы", "20.00", "neutral", "2026-09-01T12:00:00Z"),
		f43TestItem("before-2", "chips", "Чипсы", "20.00", "harmful", "2026-09-15T12:00:00Z"),
		f43TestItem("advice", "chips", "Чипсы", "20.00", "harmful", "2026-10-01T12:00:00Z"),
		f43TestItem("later", "bread", "Хлеб", "60.00", "useful", "2026-10-05T12:00:00Z"),
		f43TestItem("new-bread", "bread", "Хлеб", "30.00", "useful", "2026-10-10T12:00:00Z"),
	})
	request.Items[1].AdviceGiven = false
	baseline, err := BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	request.Recalculations = []F43Recalculation{{
		ChangedAt: mustTime("2026-10-05T12:00:00Z"), ChangedItemCount: 1,
		OptionalSpendDelta: testStringPointer("-12.00"),
	}}
	got, err := BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.Savings.MonthlyCeiling == nil || baseline.Savings.MonthlyCeiling == nil || *got.Savings.MonthlyCeiling != *baseline.Savings.MonthlyCeiling {
		t.Fatalf("classification edit changed purchase-based ceiling: before=%#v after=%#v", baseline.Savings, got.Savings)
	}
	if got.Trend.Delta == nil || baseline.Trend.Delta == nil || *got.Trend.Delta != *baseline.Trend.Delta || len(got.Trend.Weeks) != len(baseline.Trend.Weeks) {
		t.Fatalf("classification edit changed trend result: before=%#v after=%#v", baseline.Trend, got.Trend)
	}
	for index := range got.Trend.Weeks {
		if got.Trend.Weeks[index].Spend != baseline.Trend.Weeks[index].Spend ||
			got.Trend.Weeks[index].OptionalSpend != baseline.Trend.Weeks[index].OptionalSpend ||
			got.Trend.Weeks[index].OptionalShare != baseline.Trend.Weeks[index].OptionalShare {
			t.Fatalf("F42 annotation changed purchase spending in week %d: before=%#v after=%#v", index, baseline.Trend.Weeks[index], got.Trend.Weeks[index])
		}
	}
	if len(got.Effects.Effects) != len(baseline.Effects.Effects) || got.Effects.Effects[0].AfterCount != baseline.Effects.Effects[0].AfterCount {
		t.Fatalf("classification edit changed purchase cadence: before=%#v after=%#v", baseline.Effects, got.Effects)
	}
	if !got.Recalculation.Available || got.Recalculation.OptionalSpendDelta == nil || *got.Recalculation.OptionalSpendDelta != "-12.00" {
		t.Fatalf("F42 delta should remain a separate annotation: %#v", got.Recalculation)
	}
	foundMarkedWeek := false
	for _, week := range got.Trend.Weeks {
		foundMarkedWeek = foundMarkedWeek || week.Recalculated
	}
	if !foundMarkedWeek {
		t.Fatalf("F42 recomputation window was not marked: %#v", got.Trend.Weeks)
	}
}

func TestBuildF43ReportUsesMemberTimezoneForRollingWeeks(t *testing.T) {
	request := f43TestRequest("48", "2026-10-07T01:00:00Z", []F43Item{
		f43TestItem("local-today", "tea", "Чай", "10.00", "harmful", "2026-10-06T19:30:00Z"),
		f43TestItem("previous-window", "bread", "Хлеб", "10.00", "useful", "2026-09-29T18:30:00Z"),
	})
	request.TimeZone = "Asia/Vladivostok"
	got, err := BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	if !got.Trend.Available || len(got.Trend.Weeks) != 2 || got.Trend.Weeks[len(got.Trend.Weeks)-1].End != "2026-10-07" {
		t.Fatalf("rolling windows must use member-local date 2026-10-07: %#v", got.Trend)
	}
}

func TestBuildF43ReportMovesTrendAtExactFivePercentagePointBoundary(t *testing.T) {
	request := f43TestRequest("53", "2026-10-07T12:00:00Z", []F43Item{
		f43TestItem("old-waste", "soda", "Газировка", "5.00", "harmful", "2026-09-17T12:00:00Z"),
		f43TestItem("old-useful", "bread", "Хлеб", "95.00", "useful", "2026-09-17T13:00:00Z"),
		f43TestItem("new-useful", "milk", "Молоко", "100.00", "useful", "2026-10-05T12:00:00Z"),
	})
	got, err := BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.Trend.Delta == nil || *got.Trend.Delta != "-0.050" || got.Trend.Direction != "down" {
		t.Fatalf("exactly five percentage points is outside the <5pt noise band: %#v", got.Trend)
	}
}

func TestBuildF43ReportDoesNotReportUnknownF42AmountDeltaAsZero(t *testing.T) {
	request := f43TestRequest("54", "2026-10-07T12:00:00Z", []F43Item{})
	request.Recalculations = []F43Recalculation{{
		ChangedAt: mustTime("2026-10-05T12:00:00Z"), ChangedItemCount: 2,
	}}
	got, err := BuildF43Report(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.Recalculation.Available || got.Recalculation.OptionalSpendDelta != nil {
		t.Fatalf("missing F42 amount delta became a fabricated zero: %#v", got.Recalculation)
	}
	if len(got.Recalculation.Windows) != 1 || got.Recalculation.Windows[0].ChangedItemCount != 2 || got.Recalculation.Windows[0].OptionalSpendDelta != nil {
		t.Fatalf("recalculation window lost missing delta state: %#v", got.Recalculation.Windows)
	}
}

func TestBuildF43ReportRejectsDuplicateInputsAndBadWatermark(t *testing.T) {
	request := f43TestRequest("49", "2026-10-07T12:00:00Z", []F43Item{
		f43TestItem("same-id", "tea", "Чай", "10.00", "harmful", "2026-10-06T12:00:00Z"),
		f43TestItem("same-id", "bread", "Хлеб", "10.00", "useful", "2026-10-05T12:00:00Z"),
	})
	if _, err := BuildF43Report(request); err == nil || !strings.Contains(err.Error(), "duplicate item IDs") {
		t.Fatalf("duplicate input accepted or wrong error: %v", err)
	}
	request = f43TestRequest("049", "2026-10-07T12:00:00Z", nil)
	if _, err := BuildF43Report(request); err == nil || !strings.Contains(err.Error(), "watermark") {
		t.Fatalf("non-canonical watermark accepted or wrong error: %v", err)
	}
}

func f43TestRequest(watermark, asOf string, items []F43Item) F43Request {
	return F43Request{InputWatermark: watermark, AsOf: mustTime(asOf), TimeZone: "UTC", Items: items, Recalculations: []F43Recalculation{}}
}

func f43TestItem(id, key, name, amount, verdict, purchasedAt string) F43Item {
	var sum *string
	if amount != "" {
		sum = testStringPointer(amount)
	}
	return F43Item{ItemID: fmt.Sprintf("00000000-0000-4000-8000-%012x", crc32.ChecksumIEEE([]byte(id))), ProductKey: key, Name: name, LineSum: sum, Verdict: verdict,
		Advice: "Покупать реже", AdviceGiven: verdict == "harmful" || verdict == "unnecessary",
		PurchasedAt: mustTime(purchasedAt)}
}
