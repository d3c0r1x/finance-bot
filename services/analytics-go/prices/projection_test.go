package prices

import (
	"testing"
	"time"
)

func TestUnitPriceUsesPaidLineSumAndFractionalQuantity(t *testing.T) {
	got, err := UnitPrice("4.00", "0.300000")
	if err != nil {
		t.Fatal(err)
	}
	if got != "13.333333" {
		t.Fatalf("UnitPrice() = %s, want 13.333333", got)
	}
}

func TestCompareUsesMedianOfEarlierPurchasesAndExcludesCurrentReceipt(t *testing.T) {
	current := observation("Молоко Простоквашино 930мл", "receipt-current", "2026-10-10", "130.00", "1")
	otherOwner := observation("Молоко Простоквашино 930мл", "receipt-other-user", "2026-10-01", "1.00", "1")
	otherOwner.OwnerID = "user-2"
	history := []Observation{
		otherOwner,
		observation("Молоко Простоквашино 930 мл", "receipt-1", "2026-10-01", "80.00", "1"),
		observation("Молоко Простоквашино 930мл", "receipt-2", "2026-10-05", "120.00", "1"),
		observation("Молоко Простоквашино 930мл", "receipt-current", "2026-10-09", "1000.00", "1"),
		observation("Молоко Простоквашино 930мл", "receipt-future", "2026-10-11", "2000.00", "1"),
	}

	got, found, err := Compare(current, history)
	if err != nil {
		t.Fatal(err)
	}
	if !found {
		t.Fatal("Compare() found no price change")
	}
	if got.Baseline != "100.000000" || got.Current != "130.000000" || got.Change != "30.000000" {
		t.Fatalf("Compare() = %+v, want baseline 100, current 130, change 30", got)
	}
	if got.Direction != DirectionUp || got.PriorPurchases != 2 {
		t.Fatalf("Compare() = %+v, want up from two prior purchases", got)
	}
	if got.AlgorithmVersion != AlgorithmVersion {
		t.Fatalf("AlgorithmVersion = %s, want %s", got.AlgorithmVersion, AlgorithmVersion)
	}
}

func TestSameProductDoesNotMergeDifferentPackageOrBrand(t *testing.T) {
	if SameProduct("Молоко Простоквашино 930мл", "Молоко Простоквашино 1л") {
		t.Fatal("different package sizes were automatically merged")
	}
	if SameProduct("Молоко Простоквашино 1л", "Молоко Домик в деревне 1л") {
		t.Fatal("different brands were automatically merged")
	}
	if !SameProduct("Молоко Простоквашино 3,2% 930мл", "МОЛОКО Простоквашино 3.2% 930МЛ") {
		t.Fatal("punctuation and case changes should preserve the same product identity")
	}
	if !SameProduct("Молоко Простоквашино 1л", "МОЛОКО Простоквашино 1000мл") {
		t.Fatal("equivalent package quantities in different units should preserve identity")
	}
}

func TestCompareRequiresBothLegacyChangeThresholds(t *testing.T) {
	current := observation("Хлеб Бородинский 400г", "current", "2026-10-10", "110.00", "1")
	history := []Observation{
		observation("Хлеб Бородинский 400г", "prior", "2026-10-01", "100.00", "1"),
	}
	got, found, err := Compare(current, history)
	if err != nil || found {
		t.Fatalf("exactly 10%% change should be below the 12%% signal threshold: found=%v err=%v", found, err)
	}
	if !got.HasBaseline || got.Baseline != "100.000000" || got.Current != "110.000000" ||
		got.Change != "10.000000" || got.Signal || got.Direction != "" {
		t.Fatalf("sub-threshold comparison = %+v, want an explicit baseline without a signal", got)
	}
	current.LineSum = "112.00"
	got, found, err = Compare(current, history)
	if err != nil || !found {
		t.Fatalf("12%% change should be reported: found=%v err=%v", found, err)
	}
	if got.Direction != DirectionUp {
		t.Fatalf("Direction = %s, want up", got.Direction)
	}
}

func TestCompareWithoutPriorPurchaseReturnsNoBaseline(t *testing.T) {
	current := observation("Сок Добрый 1л", "current", "2026-10-10", "100.00", "1")
	comparison, found, err := Compare(current, nil)
	if err != nil || found {
		t.Fatalf("Compare() = %+v, %v, %v; want no baseline without history", comparison, found, err)
	}
	if comparison.HasBaseline || comparison.Current != "" || comparison.Baseline != "" {
		t.Fatalf("no-history result = %+v, must not fabricate zero prices", comparison)
	}
}

func TestCompareReportsLowerPriceAgainstPriorMedian(t *testing.T) {
	current := observation("Хлеб Бородинский 400г", "current", "2026-10-10", "80.00", "1")
	history := []Observation{
		observation("Хлеб Бородинский 400г", "prior-1", "2026-10-01", "100.00", "1"),
	}
	got, found, err := Compare(current, history)
	if err != nil || !found {
		t.Fatalf("Compare() = %+v, %v, %v; want lower-price signal", got, found, err)
	}
	if got.Direction != DirectionDown || got.Change != "-20.000000" {
		t.Fatalf("Compare() = %+v, want a 20 RUB decrease", got)
	}
}

func TestUnitPriceRejectsUnknownOrInvalidQuantity(t *testing.T) {
	for _, quantity := range []string{"", "0", "-1", "not-a-number"} {
		if _, err := UnitPrice("12.00", quantity); err == nil {
			t.Errorf("UnitPrice(12.00, %q) accepted invalid quantity", quantity)
		}
	}
}

func observation(name, receiptID, day, lineSum, quantity string) Observation {
	date, err := time.Parse("2006-01-02", day)
	if err != nil {
		panic(err)
	}
	return Observation{
		TenantID:  "tenant-1",
		OwnerID:   "user-1",
		ReceiptID: receiptID,
		Name:      name,
		LineSum:   lineSum,
		Quantity:  quantity,
		Purchased: date,
	}
}
