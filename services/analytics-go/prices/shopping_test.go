package prices

import (
	"testing"
	"time"
)

func TestShoppingListUsesThreeConfirmedPurchasesAndMedianInterval(t *testing.T) {
	points := []PricePoint{
		catalogPoint(1, "Tea Green 500g", "2026-08-01", "80.00", "Market A"),
		catalogPoint(2, "Tea Green 500g", "2026-08-11", "120.00", "Market B"),
		catalogPoint(3, "Tea Green 500g", "2026-08-21", "100.00", "Market C"),
	}

	tooFew := BuildShoppingList(points[:2], time.Date(2026, 8, 30, 12, 0, 0, 0, time.UTC))
	if len(tooFew.Candidates) != 0 || tooFew.EstimatedListCost != "0.00" {
		t.Fatalf("two purchases produced shopping suggestions: %+v", tooFew)
	}

	list := BuildShoppingList(points, time.Date(2026, 8, 30, 12, 0, 0, 0, time.UTC))
	if len(list.Candidates) != 1 {
		t.Fatalf("shopping list contains %d candidates, want one due after three purchases", len(list.Candidates))
	}
	candidate := list.Candidates[0]
	if candidate.ProductName != "Tea Green 500g" || candidate.PurchaseCount != 3 ||
		candidate.MedianIntervalDays != 10 || candidate.DaysUntilDue != 0 ||
		candidate.UsualUnitPrice != "100.000000" || candidate.EstimatedCost != "100.00" ||
		list.EstimatedListCost != "100.00" {
		t.Fatalf("shopping candidate = %+v, total=%s; want median 10 days, usual price 100 and due today",
			candidate, list.EstimatedListCost)
	}
}

func TestShoppingListCountsReceiptsAndExpiresAfterTwoOverdueIntervals(t *testing.T) {
	first := catalogPoint(1, "Tea Green 500g", "2026-08-01", "80.00", "Market A")
	duplicateLine := catalogPoint(11, "Tea Green 500g", "2026-08-01", "80.00", "Market A")
	duplicateLine.ReceiptID = first.ReceiptID
	points := []PricePoint{
		first,
		duplicateLine,
		catalogPoint(2, "Tea Green 500g", "2026-08-11", "120.00", "Market B"),
		catalogPoint(3, "Tea Green 500g", "2026-08-21", "100.00", "Market C"),
	}
	beforeExpiry := BuildShoppingList(points, time.Date(2026, 9, 19, 12, 0, 0, 0, time.UTC))
	if len(beforeExpiry.Candidates) != 1 || beforeExpiry.Candidates[0].PurchaseCount != 3 {
		t.Fatalf("same-receipt lines changed purchase count or stale boundary: %+v", beforeExpiry)
	}
	afterExpiry := BuildShoppingList(points, time.Date(2026, 9, 20, 12, 0, 0, 0, time.UTC))
	if len(afterExpiry.Candidates) != 0 || afterExpiry.EstimatedListCost != "0.00" {
		t.Fatalf("stale product remained after two full overdue intervals: %+v", afterExpiry)
	}
}

func TestShoppingListSeparatesTenantAndMemberHistories(t *testing.T) {
	points := []PricePoint{
		catalogPoint(1, "Tea Green 500g", "2026-08-01", "80.00", "Market A"),
		catalogPoint(2, "Tea Green 500g", "2026-08-11", "120.00", "Market B"),
		catalogPoint(3, "Tea Green 500g", "2026-08-21", "100.00", "Market C"),
	}
	foreign := catalogPoint(4, "Tea Green 500g", "2026-08-22", "1.00", "Other")
	foreign.OwnerID = "00000000-0000-4000-8000-000000000099"
	points = append(points, foreign)

	list := BuildShoppingList(points, time.Date(2026, 8, 30, 12, 0, 0, 0, time.UTC))
	if len(list.Candidates) != 1 || list.Candidates[0].PurchaseCount != 3 ||
		list.Candidates[0].UsualUnitPrice != "100.000000" {
		t.Fatalf("tenant/member histories merged into shopping candidate: %+v", list)
	}
}
