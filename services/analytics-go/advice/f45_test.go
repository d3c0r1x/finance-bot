package advice

import (
	"testing"
	"time"
)

func TestBuildF45ProgressUsesGoalWindowAndExcludesFutureFacts(t *testing.T) {
	request := F45ProgressRequest{
		InputWatermark: "19", AsOf: f44Time("2026-10-21T12:00:00Z"),
		Goal: F45Goal{Key: "chips", Scope: "product", Unit: "count", AcceptedAt: f44Time("2026-10-01T12:00:00Z"),
			EndsAt: f44Time("2026-10-31T12:00:00Z"), CountTarget: 2},
		Purchases: []F45Purchase{
			{ProductKey: "chips", LineSum: f44Amount("10.00"), PurchasedAt: f44Time("2026-10-01T12:00:00Z")},
			{ProductKey: "chips", LineSum: f44Amount("20.00"), PurchasedAt: f44Time("2026-10-21T12:00:00Z")},
			{ProductKey: "chips", LineSum: f44Amount("999.00"), PurchasedAt: f44Time("2026-10-22T12:00:00Z")}, // after asOf
			{ProductKey: "chips", LineSum: f44Amount("999.00"), PurchasedAt: f44Time("2026-09-30T12:00:00Z")}, // before acceptance
			{ProductKey: "other", LineSum: f44Amount("999.00"), PurchasedAt: f44Time("2026-10-10T12:00:00Z")},
		},
	}
	got, err := BuildF45Progress(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.Bought != 2 || got.Spent == nil || *got.Spent != "30.00" || got.Over == nil || *got.Over ||
		got.Met == nil || !*got.Met || got.Finished {
		t.Fatalf("progress must include accepted in-window facts through asOf only: %#v", got)
	}
}

func TestBuildF45ProgressKeepsCountWhenAmountUnknownAndNeverFabricatesSpent(t *testing.T) {
	request := F45ProgressRequest{
		InputWatermark: "3", AsOf: f44Time("2026-10-31T12:00:00Z"),
		Goal: F45Goal{Key: "chips", Scope: "product", Unit: "count", AcceptedAt: f44Time("2026-10-01T12:00:00Z"),
			EndsAt: f44Time("2026-10-31T12:00:00Z"), CountTarget: 1},
		Purchases: []F45Purchase{
			{ProductKey: "chips", LineSum: nil, PurchasedAt: f44Time("2026-10-31T12:00:00Z")},
		},
	}
	got, err := BuildF45Progress(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.Bought != 1 || got.Spent != nil || !got.AmountsUnknown || got.Met == nil || !*got.Met || got.Finished {
		t.Fatalf("unknown amount must preserve count and remain null at the end instant: %#v", got)
	}
	request.AsOf = f44Time("2026-10-31T12:00:01Z")
	got, err = BuildF45Progress(request)
	if err != nil {
		t.Fatal(err)
	}
	if !got.Finished || got.Met == nil || !*got.Met {
		t.Fatalf("goal becomes finished only after end instant: %#v", got)
	}
}

func TestBuildF45ProgressSumOverrunAndGroupMembershipSnapshot(t *testing.T) {
	request := F45ProgressRequest{
		InputWatermark: "4", AsOf: f44Time("2026-10-20T12:00:00Z"),
		Goal: F45Goal{Key: "cat:sweets", Scope: "group", Unit: "count", AcceptedAt: f44Time("2026-10-01T12:00:00Z"),
			EndsAt: f44Time("2026-10-31T12:00:00Z"), CountTarget: 1, MemberProductKeys: []string{"candy", "choco"}},
		Purchases: []F45Purchase{
			{ProductKey: "candy", LineSum: f44Amount("70.00"), PurchasedAt: f44Time("2026-10-02T12:00:00Z")},
			{ProductKey: "choco", LineSum: f44Amount("60.00"), PurchasedAt: f44Time("2026-10-03T12:00:00Z")},
			{ProductKey: "cookie", LineSum: f44Amount("200.00"), PurchasedAt: f44Time("2026-10-04T12:00:00Z")},
		},
	}
	got, err := BuildF45Progress(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.Bought != 2 || got.Spent == nil || *got.Spent != "130.00" || got.Over == nil || !*got.Over ||
		got.Met == nil || *got.Met {
		t.Fatalf("group progress must use only captured members and expose count overrun: %#v", got)
	}
	request.Goal = F45Goal{Key: "candy", Scope: "product", Unit: "sum", AcceptedAt: f44Time("2026-10-01T12:00:00Z"),
		EndsAt: f44Time("2026-10-31T12:00:00Z"), MonthlyLimit: "50.00"}
	got, err = BuildF45Progress(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.Bought != 1 || got.Spent == nil || *got.Spent != "70.00" || got.Over == nil || !*got.Over || got.Met == nil || *got.Met {
		t.Fatalf("sum progress must report product-goal overrun: %#v", got)
	}
	request.Purchases[0].LineSum = nil
	got, err = BuildF45Progress(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.Bought != 1 || got.Spent != nil || !got.AmountsUnknown || got.Over != nil || got.Met != nil {
		t.Fatalf("unknown sum facts must not become zero spend or a false pass: %#v", got)
	}
}

func TestBuildF45ProgressRejectsMalformedAndOversizedSnapshots(t *testing.T) {
	request := F45ProgressRequest{
		InputWatermark: "1", AsOf: time.Now().UTC(),
		Goal: F45Goal{Key: "chips", Scope: "product", Unit: "count", AcceptedAt: time.Now().Add(-time.Hour),
			EndsAt: time.Now().Add(24 * time.Hour), CountTarget: 1},
	}
	request.InputWatermark = "0"
	if _, err := BuildF45Progress(request); err == nil {
		t.Fatal("zero watermark must be rejected")
	}
	request.InputWatermark = "1"
	request.Goal.Unit = "sum"
	request.Goal.MonthlyLimit = "1e3"
	if _, err := BuildF45Progress(request); err == nil {
		t.Fatal("non-canonical goal limit must be rejected")
	}
	request.Goal.Unit = "count"
	request.Purchases = make([]F45Purchase, f44MaxInputs+1)
	if _, err := BuildF45Progress(request); err == nil {
		t.Fatal("oversized progress snapshot must be rejected")
	}
	request.Purchases = nil
	request.AsOf = request.Goal.AcceptedAt.Add(-time.Second)
	if _, err := BuildF45Progress(request); err == nil {
		t.Fatal("progress cannot be calculated before goal acceptance")
	}
}
