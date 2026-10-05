package prices

import (
	"math"
	"math/big"
	"sort"
	"time"
)

const (
	shoppingMinimumPurchases = 3
	shoppingMinimumGapDays   = 3
	shoppingDueShare         = 0.85
	shoppingDueHorizonDays   = 3
	shoppingStaleIntervals   = 2
	shoppingCandidateLimit   = 10
)

type ShoppingCandidate struct {
	ProductName        string    `json:"productName"`
	PurchaseCount      int       `json:"purchaseCount"`
	MedianIntervalDays int       `json:"medianIntervalDays"`
	UsualUnitPrice     string    `json:"usualUnitPrice"`
	EstimatedCost      string    `json:"estimatedCost"`
	LastPurchasedAt    time.Time `json:"lastPurchasedAt"`
	DueAt              time.Time `json:"dueAt"`
	DaysUntilDue       int       `json:"daysUntilDue"`
}

type ShoppingList struct {
	Candidates        []ShoppingCandidate `json:"candidates"`
	EstimatedListCost string              `json:"estimatedListCost"`
	InventoryTracked  bool                `json:"inventoryTracked"`
}

type shoppingPurchase struct {
	receiptID string
	purchased time.Time
	name      string
	prices    []*big.Rat
}

// BuildShoppingList derives due suggestions from confirmed receipt history.
// A receipt counts as one purchase even when it contains duplicate product lines.
func BuildShoppingList(points []PricePoint, asOf time.Time) ShoppingList {
	list := ShoppingList{Candidates: []ShoppingCandidate{}, EstimatedListCost: "0.00"}
	if asOf.IsZero() {
		asOf = time.Now().UTC()
	}
	cutoff := asOf.UTC()
	day := utcDay(cutoff)

	valid := make([]PricePoint, 0, len(points))
	var tenantID, ownerID string
	for _, point := range points {
		if err := validatePricePoint(point); err != nil || point.PurchasedAt.After(cutoff) {
			continue
		}
		if tenantID == "" {
			tenantID, ownerID = point.TenantID, point.OwnerID
		}
		if point.TenantID != tenantID || point.OwnerID != ownerID {
			continue
		}
		valid = append(valid, point)
	}
	sort.Slice(valid, func(i, j int) bool {
		if valid[i].PurchasedAt.Equal(valid[j].PurchasedAt) {
			if valid[i].ReceiptID == valid[j].ReceiptID {
				return valid[i].ItemID < valid[j].ItemID
			}
			return valid[i].ReceiptID < valid[j].ReceiptID
		}
		return valid[i].PurchasedAt.Before(valid[j].PurchasedAt)
	})

	groups := make([][]PricePoint, 0)
	for _, point := range valid {
		matched := -1
		for index := range groups {
			if SameProduct(groups[index][0].Name, point.Name) {
				matched = index
				break
			}
		}
		if matched < 0 {
			groups = append(groups, []PricePoint{point})
			continue
		}
		groups[matched] = append(groups[matched], point)
	}

	for _, group := range groups {
		candidate, usualPrice, ok := candidateForShoppingGroup(group, day)
		if !ok {
			continue
		}
		list.Candidates = append(list.Candidates, candidate)
		usualPrice, _ = new(big.Rat).SetString(candidate.EstimatedCost)
		if usualPrice != nil {
			listCost, _ := new(big.Rat).SetString(list.EstimatedListCost)
			list.EstimatedListCost = formatFixed(new(big.Rat).Add(listCost, usualPrice), 2)
		}
	}
	sort.SliceStable(list.Candidates, func(i, j int) bool {
		left, _ := new(big.Rat).SetString(list.Candidates[i].EstimatedCost)
		right, _ := new(big.Rat).SetString(list.Candidates[j].EstimatedCost)
		if list.Candidates[i].DaysUntilDue != list.Candidates[j].DaysUntilDue {
			return list.Candidates[i].DaysUntilDue < list.Candidates[j].DaysUntilDue
		}
		if comparison := left.Cmp(right); comparison != 0 {
			return comparison > 0
		}
		return list.Candidates[i].ProductName < list.Candidates[j].ProductName
	})
	if len(list.Candidates) > shoppingCandidateLimit {
		list.Candidates = list.Candidates[:shoppingCandidateLimit]
		list.EstimatedListCost = sumShoppingEstimates(list.Candidates)
	}
	return list
}

func candidateForShoppingGroup(group []PricePoint, today time.Time) (ShoppingCandidate, *big.Rat, bool) {
	byReceipt := make(map[string]*shoppingPurchase)
	for _, point := range group {
		price, ok := new(big.Rat).SetString(point.UnitPrice)
		if !ok {
			continue
		}
		purchase := byReceipt[point.ReceiptID]
		if purchase == nil {
			purchase = &shoppingPurchase{receiptID: point.ReceiptID, purchased: point.PurchasedAt,
				name: point.Name}
			byReceipt[point.ReceiptID] = purchase
		}
		if point.PurchasedAt.After(purchase.purchased) {
			purchase.purchased = point.PurchasedAt
			purchase.name = point.Name
		}
		purchase.prices = append(purchase.prices, price)
	}
	if len(byReceipt) < shoppingMinimumPurchases {
		return ShoppingCandidate{}, nil, false
	}
	purchases := make([]shoppingPurchase, 0, len(byReceipt))
	for _, purchase := range byReceipt {
		sort.Slice(purchase.prices, func(i, j int) bool { return purchase.prices[i].Cmp(purchase.prices[j]) < 0 })
		purchases = append(purchases, *purchase)
	}
	sort.Slice(purchases, func(i, j int) bool {
		if purchases[i].purchased.Equal(purchases[j].purchased) {
			return purchases[i].receiptID < purchases[j].receiptID
		}
		return purchases[i].purchased.Before(purchases[j].purchased)
	})

	intervals := make([]int, 0, len(purchases)-1)
	prices := make([]*big.Rat, 0, len(purchases))
	for index, purchase := range purchases {
		prices = append(prices, median(purchase.prices))
		if index == 0 {
			continue
		}
		gap := int(utcDay(purchase.purchased).Sub(utcDay(purchases[index-1].purchased)).Hours() / 24)
		if gap >= shoppingMinimumGapDays {
			intervals = append(intervals, gap)
		}
	}
	if len(intervals) == 0 {
		return ShoppingCandidate{}, nil, false
	}
	sort.Ints(intervals)
	medianInterval := intervals[len(intervals)/2]
	if len(intervals)%2 == 0 {
		medianInterval = int(math.Round(float64(intervals[len(intervals)/2-1]+intervals[len(intervals)/2]) / 2))
	}
	if medianInterval < shoppingMinimumGapDays {
		medianInterval = shoppingMinimumGapDays
	}
	sort.Slice(prices, func(i, j int) bool { return prices[i].Cmp(prices[j]) < 0 })
	usualPrice := median(prices)
	last := purchases[len(purchases)-1]
	dueDays := int(math.Round(float64(medianInterval) * shoppingDueShare))
	if dueDays < 1 {
		dueDays = 1
	}
	dueAt := utcDay(last.purchased).AddDate(0, 0, dueDays)
	daysUntilDue := int(dueAt.Sub(today).Hours() / 24)
	if daysUntilDue > shoppingDueHorizonDays || daysUntilDue < -medianInterval*shoppingStaleIntervals {
		return ShoppingCandidate{}, nil, false
	}
	return ShoppingCandidate{
		ProductName: last.name, PurchaseCount: len(purchases), MedianIntervalDays: medianInterval,
		UsualUnitPrice: formatFixed(usualPrice, priceScale), EstimatedCost: formatFixed(usualPrice, 2),
		LastPurchasedAt: last.purchased, DueAt: dueAt, DaysUntilDue: daysUntilDue,
	}, usualPrice, true
}

func utcDay(value time.Time) time.Time {
	year, month, day := value.UTC().Date()
	return time.Date(year, month, day, 0, 0, 0, 0, time.UTC)
}

func sumShoppingEstimates(candidates []ShoppingCandidate) string {
	total := new(big.Rat)
	for _, candidate := range candidates {
		if amount, ok := new(big.Rat).SetString(candidate.EstimatedCost); ok {
			total.Add(total, amount)
		}
	}
	return formatFixed(total, 2)
}
