package prices

import (
	"math/big"
	"sort"
	"time"
)

const (
	PersonalInflationWindowDays = 90
	inflationMinimumProducts    = 3
	inflationMinimumRiseRatio   = 1.005
	inflationMaximumFallRatio   = 0.995
	inflationTopLimit           = 3
)

type PersonalInflation struct {
	Available    bool                    `json:"available"`
	ReasonCode   string                  `json:"reasonCode"`
	AsOf         time.Time               `json:"asOf"`
	WindowDays   int                     `json:"windowDays"`
	ProductCount int                     `json:"productCount"`
	BasketBefore *string                 `json:"basketBefore"`
	BasketNow    *string                 `json:"basketNow"`
	IndexPercent *string                 `json:"indexPercent"`
	Rising       []PersonalInflationItem `json:"rising"`
	Falling      []PersonalInflationItem `json:"falling"`
}

type PersonalInflationItem struct {
	ProductName         string `json:"productName"`
	OldUnitPrice        string `json:"oldUnitPrice"`
	NewUnitPrice        string `json:"newUnitPrice"`
	OldSpendWeight      string `json:"oldSpendWeight"`
	ChangePercent       string `json:"changePercent"`
	OlderPurchaseCount  int    `json:"olderPurchaseCount"`
	WindowPurchaseCount int    `json:"windowPurchaseCount"`
	ratio               *big.Rat
	contribution        *big.Rat
}

type inflationGroup struct {
	points    []PricePoint
	firstName string
	lastName  string
}

// BuildPersonalInflation mirrors the legacy personal basket: a previous-spend
// weighted index of median receipt unit prices, using the shared product match.
func BuildPersonalInflation(tenantID, ownerID string, points []PricePoint, asOf time.Time) PersonalInflation {
	result := PersonalInflation{
		ReasonCode: "insufficient_history", AsOf: asOf.UTC(), WindowDays: PersonalInflationWindowDays,
		Rising: []PersonalInflationItem{}, Falling: []PersonalInflationItem{},
	}
	if tenantID == "" || ownerID == "" || asOf.IsZero() {
		return result
	}
	cutoff := asOf.Add(-PersonalInflationWindowDays * 24 * time.Hour)
	valid := make([]PricePoint, 0, len(points))
	for _, point := range points {
		if validatePricePoint(point) != nil || point.TenantID != tenantID || point.OwnerID != ownerID ||
			point.PurchasedAt.After(asOf) {
			continue
		}
		valid = append(valid, point)
	}
	sort.SliceStable(valid, func(i, j int) bool {
		if valid[i].PurchasedAt.Equal(valid[j].PurchasedAt) {
			if valid[i].ReceiptID == valid[j].ReceiptID {
				return valid[i].ItemID < valid[j].ItemID
			}
			return valid[i].ReceiptID < valid[j].ReceiptID
		}
		return valid[i].PurchasedAt.Before(valid[j].PurchasedAt)
	})

	groups := make([]inflationGroup, 0)
	for _, point := range valid {
		matched := -1
		for index := range groups {
			group := &groups[index]
			if SameProduct(group.firstName, point.Name) || SameProduct(group.lastName, point.Name) {
				matched = index
				break
			}
		}
		if matched < 0 {
			groups = append(groups, inflationGroup{points: []PricePoint{point}, firstName: point.Name, lastName: point.Name})
			continue
		}
		groups[matched].points = append(groups[matched].points, point)
		groups[matched].lastName = point.Name
	}

	items := make([]PersonalInflationItem, 0, len(groups))
	basketBefore := new(big.Rat)
	basketNow := new(big.Rat)
	for _, group := range groups {
		item, weight, weightedCurrent, ok := summarizeInflationGroup(group, cutoff)
		if !ok {
			continue
		}
		items = append(items, item)
		basketBefore.Add(basketBefore, weight)
		basketNow.Add(basketNow, weightedCurrent)
	}
	if len(items) < inflationMinimumProducts || basketBefore.Sign() <= 0 {
		return result
	}

	basketBefore = roundMoney(basketBefore)
	basketNow = roundMoney(basketNow)
	index := new(big.Rat).Sub(new(big.Rat).Quo(new(big.Rat).Set(basketNow), basketBefore), big.NewRat(1, 1))
	index = rounded(index, 4)
	index.Mul(index, big.NewRat(100, 1))
	result.Available = true
	result.ReasonCode = "available"
	result.ProductCount = len(items)
	result.BasketBefore = stringPointer(formatFixed(basketBefore, 2))
	result.BasketNow = stringPointer(formatFixed(basketNow, 2))
	result.IndexPercent = stringPointer(formatFixed(index, 2))

	rising := make([]PersonalInflationItem, 0)
	falling := make([]PersonalInflationItem, 0)
	for _, item := range items {
		if item.ratio.Cmp(big.NewRat(1005, 1000)) > 0 {
			rising = append(rising, item)
		}
		if item.ratio.Cmp(big.NewRat(995, 1000)) < 0 {
			falling = append(falling, item)
		}
	}
	sort.SliceStable(rising, func(i, j int) bool {
		if comparison := rising[i].contribution.Cmp(rising[j].contribution); comparison != 0 {
			return comparison > 0
		}
		return rising[i].ProductName < rising[j].ProductName
	})
	sort.SliceStable(falling, func(i, j int) bool {
		if comparison := falling[i].ratio.Cmp(falling[j].ratio); comparison != 0 {
			return comparison < 0
		}
		return falling[i].ProductName < falling[j].ProductName
	})
	if len(rising) > inflationTopLimit {
		rising = rising[:inflationTopLimit]
	}
	if len(falling) > inflationTopLimit {
		falling = falling[:inflationTopLimit]
	}
	result.Rising = publicInflationItems(rising)
	result.Falling = publicInflationItems(falling)
	return result
}

func summarizeInflationGroup(group inflationGroup, cutoff time.Time) (PersonalInflationItem, *big.Rat, *big.Rat, bool) {
	olderPrices := make([]*big.Rat, 0)
	windowPrices := make([]*big.Rat, 0)
	olderReceipts := make(map[string]struct{})
	windowReceipts := make(map[string]struct{})
	olderWeight := new(big.Rat)
	for _, point := range group.points {
		unitPrice, ok := new(big.Rat).SetString(point.UnitPrice)
		if !ok || unitPrice.Sign() <= 0 {
			continue
		}
		lineSum, ok := new(big.Rat).SetString(point.LineSum)
		if !ok || lineSum.Sign() <= 0 {
			continue
		}
		if point.PurchasedAt.Before(cutoff) {
			olderPrices = append(olderPrices, unitPrice)
			olderReceipts[point.ReceiptID] = struct{}{}
			olderWeight.Add(olderWeight, lineSum)
		} else {
			windowPrices = append(windowPrices, unitPrice)
			windowReceipts[point.ReceiptID] = struct{}{}
		}
	}
	if len(olderReceipts) < 2 || len(windowReceipts) == 0 || olderWeight.Sign() <= 0 {
		return PersonalInflationItem{}, nil, nil, false
	}
	oldPrice := rounded(median(olderPrices), 2)
	newPrice := rounded(median(windowPrices), 2)
	if oldPrice.Sign() <= 0 || newPrice.Sign() <= 0 {
		return PersonalInflationItem{}, nil, nil, false
	}
	ratio := rounded(new(big.Rat).Quo(new(big.Rat).Set(newPrice), oldPrice), 4)
	if ratio.Sign() <= 0 {
		return PersonalInflationItem{}, nil, nil, false
	}
	changePercent := new(big.Rat).Mul(new(big.Rat).Sub(new(big.Rat).Set(ratio), big.NewRat(1, 1)), big.NewRat(100, 1))
	contribution := new(big.Rat).Mul(new(big.Rat).Set(olderWeight),
		new(big.Rat).Sub(new(big.Rat).Set(ratio), big.NewRat(1, 1)))
	name := group.lastName
	if name == "" {
		name = group.firstName
	}
	item := PersonalInflationItem{
		ProductName: name, OldUnitPrice: formatFixed(oldPrice, 2), NewUnitPrice: formatFixed(newPrice, 2),
		OldSpendWeight: formatFixed(roundMoney(olderWeight), 2), ChangePercent: formatFixed(changePercent, 2),
		OlderPurchaseCount: len(olderReceipts), WindowPurchaseCount: len(windowReceipts),
		ratio: ratio, contribution: contribution,
	}
	return item, olderWeight, new(big.Rat).Mul(new(big.Rat).Set(olderWeight), ratio), true
}

func publicInflationItems(items []PersonalInflationItem) []PersonalInflationItem {
	result := make([]PersonalInflationItem, len(items))
	for index, item := range items {
		item.ratio = nil
		item.contribution = nil
		result[index] = item
	}
	return result
}

func rounded(value *big.Rat, scale int) *big.Rat {
	text := formatFixed(value, scale)
	result, ok := new(big.Rat).SetString(text)
	if !ok {
		return new(big.Rat)
	}
	return result
}

func roundMoney(value *big.Rat) *big.Rat {
	return rounded(value, 2)
}
