package prices

import (
	"fmt"
	"testing"
	"time"
)

func TestBuildPersonalInflationUsesPriorSpendWeightsAndSharedProductIdentity(t *testing.T) {
	asOf := time.Date(2026, 10, 6, 12, 0, 0, 0, time.UTC)
	points := []PricePoint{
		inflationPoint(1, 1, "Молоко Простоквашино 930мл", "100.00", "2026-06-01T09:00:00Z"),
		inflationPoint(2, 2, "МОЛОКО Простоквашино 930мл", "120.00", "2026-06-20T09:00:00Z"),
		inflationPoint(3, 3, "Молоко Простоквашино 930мл", "132.00", "2026-07-08T12:00:00Z"),
		inflationPoint(4, 4, "Хлеб Бородинский 400г", "40.00", "2026-06-01T09:00:00Z"),
		inflationPoint(5, 5, "Хлеб Бородинский 400г", "50.00", "2026-06-20T09:00:00Z"),
		inflationPoint(6, 6, "Хлеб Бородинский 400г", "45.00", "2026-08-01T09:00:00Z"),
		inflationPoint(7, 7, "Кофе арабика 250г", "1000.00", "2026-06-01T09:00:00Z"),
		inflationPoint(8, 8, "Кофе арабика 250г", "1200.00", "2026-06-20T09:00:00Z"),
		inflationPoint(9, 9, "Кофе арабика 250г", "990.00", "2026-08-01T09:00:00Z"),
		// Duplicate rows in one receipt count as spend but do not create two old purchases.
		inflationPoint(10, 10, "Чай Ассам 100г", "50.00", "2026-06-01T09:00:00Z"),
		inflationPoint(11, 10, "Чай Ассам 100г", "60.00", "2026-06-01T09:00:00Z"),
		inflationPoint(12, 11, "Чай Ассам 100г", "60.00", "2026-08-01T09:00:00Z"),
		// One old purchase is not a stable baseline; a future receipt is outside the snapshot.
		inflationPoint(13, 12, "Сыр Гауда 300г", "200.00", "2026-06-01T09:00:00Z"),
		inflationPoint(14, 13, "Сыр Гауда 300г", "240.00", "2026-08-01T09:00:00Z"),
		inflationPoint(15, 14, "Сыр Гауда 300г", "1000.00", "2026-10-07T09:00:00Z"),
	}
	points = append(points, inflationPoint(16, 15, "Кофе арабика 250г", "1.00", "2026-08-02T09:00:00Z"))
	points[len(points)-1].OwnerID = "00000000-0000-4000-8000-000000000099"

	result := BuildPersonalInflation(inflationTenantID, inflationOwnerID, points, asOf)
	if !result.Available || result.ReasonCode != "available" {
		t.Fatalf("availability = %t/%q, want available", result.Available, result.ReasonCode)
	}
	if result.WindowDays != 90 || result.ProductCount != 3 || result.BasketBefore == nil ||
		*result.BasketBefore != "2510.00" || result.BasketNow == nil || *result.BasketNow != "2334.00" ||
		result.IndexPercent == nil || *result.IndexPercent != "-7.01" {
		t.Fatalf("basket result = %+v, want 90-day, 3-product weighted basket 2510.00 → 2334.00 (-7.01%%)", result)
	}
	if len(result.Rising) != 1 || result.Rising[0].ProductName != "Молоко Простоквашино 930мл" ||
		result.Rising[0].OldUnitPrice != "110.00" || result.Rising[0].NewUnitPrice != "132.00" ||
		result.Rising[0].ChangePercent != "20.00" || result.Rising[0].OldSpendWeight != "220.00" {
		t.Fatalf("top rise = %+v, want median prices and prior-spend weight", result.Rising)
	}
	if len(result.Falling) != 1 || result.Falling[0].ProductName != "Кофе арабика 250г" ||
		result.Falling[0].ChangePercent != "-10.00" {
		t.Fatalf("top fall = %+v, want coffee down 10%%", result.Falling)
	}
}

func TestBuildPersonalInflationReturnsExplicitInsufficientHistoryWithoutTotals(t *testing.T) {
	points := []PricePoint{
		inflationPoint(1, 1, "Молоко 930мл", "100.00", "2026-06-01T09:00:00Z"),
		inflationPoint(2, 2, "Молоко 930мл", "110.00", "2026-08-01T09:00:00Z"),
	}
	result := BuildPersonalInflation(inflationTenantID, inflationOwnerID, points,
		time.Date(2026, 10, 6, 12, 0, 0, 0, time.UTC))
	if result.Available || result.ReasonCode != "insufficient_history" || result.BasketBefore != nil ||
		result.BasketNow != nil || result.IndexPercent != nil || len(result.Rising) != 0 || len(result.Falling) != 0 {
		t.Fatalf("insufficient-history result = %+v, must not invent basket totals", result)
	}
}

const (
	inflationTenantID = "00000000-0000-4000-8000-000000000002"
	inflationOwnerID  = "00000000-0000-4000-8000-000000000004"
)

func inflationPoint(item, receipt int, name, lineSum, purchasedAt string) PricePoint {
	purchased, err := time.Parse(time.RFC3339, purchasedAt)
	if err != nil {
		panic(err)
	}
	unitPrice, err := UnitPrice(lineSum, "1.000000")
	if err != nil {
		panic(err)
	}
	return PricePoint{
		TenantID: inflationTenantID, OwnerID: inflationOwnerID,
		ReceiptID:        fmt.Sprintf("00000000-0000-4000-8000-%012d", receipt),
		TransactionID:    "00000000-0000-4000-8000-000000000030",
		ItemID:           fmt.Sprintf("00000000-0000-4000-8000-%012d", item),
		EventID:          "00000000-0000-4000-8000-000000000031",
		AggregateVersion: 1, ReceiptDate: purchased.Format(time.DateOnly), PurchasedAt: purchased,
		RecordedAt: purchased, Name: name, Quantity: "1.000000", LineSum: lineSum, UnitPrice: unitPrice,
	}
}
