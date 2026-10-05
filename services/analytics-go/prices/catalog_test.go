package prices

import (
	"fmt"
	"testing"
	"time"
)

func TestProductSearchAndCatalogUseDifferentPurchaseThresholds(t *testing.T) {
	first := catalogPoint(1, "Tea Green 500g", "2026-09-01", "100.00", "Market A")
	onePurchase := ListProducts([]PricePoint{first}, "tea")
	if len(onePurchase) != 1 {
		t.Fatalf("one-purchase search returned %d products, want one", len(onePurchase))
	}
	if onePurchase[0].PurchaseCount != 1 || onePurchase[0].HasBaseline || onePurchase[0].BaselineUnitPrice != nil ||
		onePurchase[0].ChartAvailable || len(onePurchase[0].History) != 1 {
		t.Fatalf("one-purchase search = %+v, want current-only result without baseline/chart", onePurchase[0])
	}
	if catalog := ListProducts([]PricePoint{first}, ""); len(catalog) != 0 {
		t.Fatalf("catalog returned %d products after one purchase, want none", len(catalog))
	}

	second := catalogPoint(2, "Tea Green 500g", "2026-09-10", "120.00", "Market B")
	twoPurchase := ListProducts([]PricePoint{first, second}, "tea")
	if len(twoPurchase) != 1 || twoPurchase[0].PurchaseCount != 2 || !twoPurchase[0].HasBaseline ||
		!twoPurchase[0].ChartAvailable || len(twoPurchase[0].History) != 2 {
		t.Fatalf("two-purchase search = %+v, want a two-point chart and prior baseline", twoPurchase)
	}
	if twoPurchase[0].BaselineUnitPrice == nil || *twoPurchase[0].BaselineUnitPrice != "100.000000" {
		t.Fatalf("two-purchase prior baseline = %v, want 100.000000", twoPurchase[0].BaselineUnitPrice)
	}
	if catalog := ListProducts([]PricePoint{first, second}, ""); len(catalog) != 0 {
		t.Fatalf("catalog returned %d products after two purchases, want none", len(catalog))
	}
}

func TestCatalogUsesPriorMedianAndReportsCheapestMerchant(t *testing.T) {
	points := []PricePoint{
		catalogPoint(1, "Tea Green 500g", "2026-09-01", "100.00", "Market A"),
		catalogPoint(2, "Tea Green 500g", "2026-09-10", "120.00", "Market B"),
		catalogPoint(3, "Tea Green 500g", "2026-09-20", "160.00", "Market C"),
	}
	products := ListProducts(points, "")
	if len(products) != 1 {
		t.Fatalf("catalog returned %d products, want one after three purchases", len(products))
	}
	product := products[0]
	if product.PurchaseCount != 3 || product.UsualUnitPrice != "120.000000" || product.LastUnitPrice != "160.000000" {
		t.Fatalf("catalog summary = %+v, want count 3, median 120 and latest 160", product)
	}
	if product.BaselineUnitPrice == nil || *product.BaselineUnitPrice != "110.000000" {
		t.Fatalf("prior median = %v, want 110.000000", product.BaselineUnitPrice)
	}
	if product.CheapestUnitPrice != "100.000000" || product.CheapestMerchant == nil || *product.CheapestMerchant != "Market A" {
		t.Fatalf("cheapest purchase = %s at %v, want 100 at Market A", product.CheapestUnitPrice, product.CheapestMerchant)
	}
	if product.TotalSpent != "380.00" || product.Change == nil || *product.Change != "50.000000" ||
		product.Relative == nil || *product.Relative != "0.454545" || !product.Signal || product.Direction == nil ||
		*product.Direction != DirectionUp {
		t.Fatalf("catalog trend/spend = %+v, want spend 380 and upward change against prior median", product)
	}
}

func TestSearchKeepsDifferentBrandsAndPackagesAsSeparateProducts(t *testing.T) {
	points := []PricePoint{
		catalogPoint(1, "Milk Prostokvashino 930ml", "2026-09-01", "90.00", "Market A"),
		catalogPoint(2, "Milk Domik 930ml", "2026-09-02", "95.00", "Market B"),
		catalogPoint(3, "Milk Prostokvashino 1l", "2026-09-03", "100.00", "Market C"),
	}
	products := ListProducts(points, "milk")
	if len(products) != 3 {
		t.Fatalf("search merged brand/package variants into %d products, want three", len(products))
	}
	for _, product := range products {
		if product.PurchaseCount != 1 || product.ChartAvailable || product.HasBaseline {
			t.Fatalf("variant summary = %+v, each variant must retain its own single-purchase history", product)
		}
	}
}

func catalogPoint(id int, name, day, lineSum, merchant string) PricePoint {
	purchased, err := time.Parse(time.DateOnly, day)
	if err != nil {
		panic(err)
	}
	store := merchant
	unitPrice, err := UnitPrice(lineSum, "1.000000")
	if err != nil {
		panic(err)
	}
	return PricePoint{
		TenantID:         fmt.Sprintf("00000000-0000-4000-8000-%012d", 1),
		OwnerID:          fmt.Sprintf("00000000-0000-4000-8000-%012d", 2),
		ReceiptID:        fmt.Sprintf("00000000-0000-4000-8000-%012d", id+10),
		TransactionID:    fmt.Sprintf("00000000-0000-4000-8000-%012d", id+20),
		ItemID:           fmt.Sprintf("00000000-0000-4000-8000-%012d", id+30),
		EventID:          fmt.Sprintf("00000000-0000-4000-8000-%012d", id+40),
		AggregateVersion: 1, ReceiptDate: day, PurchasedAt: purchased.UTC().Add(time.Hour),
		RecordedAt: purchased.UTC().Add(2 * time.Hour), Merchant: &store, Name: name,
		Quantity: "1.000000", LineSum: lineSum, UnitPrice: unitPrice,
	}
}
