package advice

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"testing"
	"time"
)

func TestEvidenceGoldenFixtureMatchesGo(t *testing.T) {
	body, err := os.ReadFile(filepath.Join("..", "..", "..", "contracts", "analytics", "advice-evidence.v1.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		AlgorithmVersion string           `json:"algorithmVersion"`
		Input            EvidenceRequest  `json:"input"`
		Expected         EvidenceResponse `json:"expected"`
	}
	if err := json.Unmarshal(body, &fixture); err != nil {
		t.Fatal(err)
	}
	got, err := BuildEvidenceGroups(fixture.Input)
	if err != nil || got.AlgorithmVersion != fixture.AlgorithmVersion || got.InputVersion == "" {
		t.Fatalf("golden request failed: %#v, %v", got, err)
	}
	got.InputVersion = "" // The hash is asserted separately and varies with the complete input.
	if !reflect.DeepEqual(got, fixture.Expected) {
		t.Fatalf("Go response differs from contract fixture:\n got: %#v\nwant: %#v", got, fixture.Expected)
	}
}

func TestBuildEvidenceGroupsKeepsModelOnlyHypothesesSeparate(t *testing.T) {
	request := EvidenceRequest{Items: []EvidenceLine{
		evidenceLine("00000000-0000-4000-8000-000000000001", "coffee", "Кофе", "13.00", "unnecessary", "model", "2026-09-01T10:00:00Z"),
		evidenceLine("00000000-0000-4000-8000-000000000002", "coffee", "Кофе 2", "14.00", "harmful", "model", "2026-09-10T10:00:00Z"),
		evidenceLine("00000000-0000-4000-8000-000000000003", "chips", "Чипсы", "1.00", "unnecessary", "rule", "2026-09-01T11:00:00Z"),
		evidenceLine("00000000-0000-4000-8000-000000000004", "chips", "Чипсы", "2.00", "unnecessary", "model", "2026-09-02T11:00:00Z"),
		evidenceLine("00000000-0000-4000-8000-000000000005", "chips", "Чипсы", "3.00", "harmful", "unknown", "2026-09-03T11:00:00Z"),
		evidenceLine("00000000-0000-4000-8000-000000000006", "milk", "Молоко", "4.00", "harmful", "rule", "2026-09-04T11:00:00Z"),
		evidenceLine("00000000-0000-4000-8000-000000000007", "coffee", "Кофе", "5.00", "useful", "rule", "2026-09-11T11:00:00Z"),
	}}
	request.Items[1].Advice = "Проверь привычку"

	got, err := BuildEvidenceGroups(request)
	if err != nil {
		t.Fatal(err)
	}
	if got.AlgorithmVersion != EvidenceAlgorithmVersion || len(got.Groups) != 2 || got.InputVersion == "" {
		t.Fatalf("unexpected evidence response: %#v", got)
	}
	chips, coffee := got.Groups[0], got.Groups[1]
	if chips.ProductKey != "chips" || chips.Count != 3 || chips.Amount == nil || *chips.Amount != "6.00" ||
		chips.RuleCount != 1 || chips.ModelCount != 1 || chips.UnmarkedCount != 1 || chips.ModelOnly {
		t.Fatalf("mixed evidence group = %#v", chips)
	}
	if coffee.ProductKey != "coffee" || coffee.ProductName != "Кофе 2" || coffee.Count != 2 ||
		coffee.Amount == nil || *coffee.Amount != "27.00" || coffee.ModelCount != 2 || !coffee.ModelOnly ||
		coffee.LatestAdvice != "Проверь привычку" {
		t.Fatalf("model-only evidence group = %#v", coffee)
	}
	reversed := EvidenceRequest{Items: slices.Clone(request.Items)}
	slices.Reverse(reversed.Items)
	reordered, err := BuildEvidenceGroups(reversed)
	if err != nil || reordered.InputVersion != got.InputVersion || reordered.Groups[0].ProductKey != "chips" {
		t.Fatalf("input order changed evidence result: %#v, %v", reordered, err)
	}
}

func TestBuildEvidenceGroupsDoesNotInventUnknownAmounts(t *testing.T) {
	request := EvidenceRequest{Items: []EvidenceLine{
		evidenceLine("00000000-0000-4000-8000-000000000011", "bread", "Хлеб", "2.00", "unnecessary", "model", "2026-09-01T10:00:00Z"),
		evidenceLine("00000000-0000-4000-8000-000000000012", "bread", "Хлеб", "", "unnecessary", "model", "2026-09-02T10:00:00Z"),
	}}
	got, err := BuildEvidenceGroups(request)
	if err != nil || len(got.Groups) != 1 || got.Groups[0].Amount != nil ||
		got.Groups[0].MissingAmountCount != 1 || !got.Groups[0].ModelOnly {
		t.Fatalf("missing amount was fabricated: %#v, %v", got, err)
	}
}

func TestBuildEvidenceGroupsRejectsMalformedFacts(t *testing.T) {
	first := evidenceLine("00000000-0000-4000-8000-000000000021", "tea", "Чай", "1.00", "harmful", "model", "2026-09-01T10:00:00Z")
	invalid := []EvidenceRequest{
		{Items: []EvidenceLine{first, first}},
		{Items: []EvidenceLine{evidenceLine("00000000-0000-4000-8000-000000000022", "tea", "Чай", "1.001", "harmful", "model", "2026-09-01T10:00:00Z")}},
		{Items: []EvidenceLine{evidenceLine("00000000-0000-4000-8000-000000000023", "tea", "Чай", "1.00", "unknown-verdict", "model", "2026-09-01T10:00:00Z")}},
	}
	for index, request := range invalid {
		if _, err := BuildEvidenceGroups(request); err == nil {
			t.Errorf("malformed fact %d accepted", index)
		}
	}
}

func evidenceLine(id, key, name, amount, verdict, source, purchasedAt string) EvidenceLine {
	moment, err := time.Parse(time.RFC3339, purchasedAt)
	if err != nil {
		panic(err)
	}
	var lineSum *string
	if amount != "" {
		lineSum = &amount
	}
	return EvidenceLine{ItemID: id, ProductKey: key, Name: name, LineSum: lineSum,
		Verdict: verdict, VerdictSource: source, PurchasedAt: moment, ItemVersion: 1}
}
