package prices

import (
	"math/big"
	"sort"
	"strings"
	"time"
)

const (
	productCatalogMinimumPurchases = 3
	productSearchLimit             = 5
	productCatalogLimit            = 10
	productHistoryLimit            = 12
)

// ProductCard is a member-scoped summary made only from confirmed paid receipt
// lines. Baseline and trend fields stay absent until an earlier purchase exists.
type ProductCard struct {
	ProductName       string              `json:"productName"`
	PurchaseCount     int                 `json:"purchaseCount"`
	UsualUnitPrice    string              `json:"usualUnitPrice"`
	HasBaseline       bool                `json:"hasBaseline"`
	BaselineUnitPrice *string             `json:"baselineUnitPrice"`
	LastUnitPrice     string              `json:"lastUnitPrice"`
	LastPurchasedAt   time.Time           `json:"lastPurchasedAt"`
	LastMerchant      *string             `json:"lastMerchant"`
	CheapestUnitPrice string              `json:"cheapestUnitPrice"`
	CheapestMerchant  *string             `json:"cheapestMerchant"`
	TotalSpent        string              `json:"totalSpent"`
	Change            *string             `json:"change"`
	Relative          *string             `json:"relative"`
	Signal            bool                `json:"signal"`
	Direction         *Direction          `json:"direction"`
	PriorPurchases    int                 `json:"priorPurchases"`
	ChartAvailable    bool                `json:"chartAvailable"`
	History           []PriceHistoryPoint `json:"history"`
}

type scoredProduct struct {
	card  ProductCard
	score int
}

// ListProducts returns the catalog when query is blank and a purchase-backed
// search otherwise. A supplied slice must already be scoped to one member; the
// function also rejects rows outside its first valid point's scope defensively.
func ListProducts(points []PricePoint, query string) []ProductCard {
	valid := make([]PricePoint, 0, len(points))
	var tenantID, ownerID string
	for _, point := range points {
		if err := validatePricePoint(point); err != nil {
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
	if len(valid) == 0 {
		return []ProductCard{}
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

	search := strings.TrimSpace(query) != ""
	queryTokens := identityTokens(query)
	minimum := productCatalogMinimumPurchases
	if search {
		minimum = 1
		if len(queryTokens) == 0 {
			return []ProductCard{}
		}
	}
	results := make([]scoredProduct, 0, len(groups))
	for _, group := range groups {
		if len(group) < minimum {
			continue
		}
		score := 0
		if search {
			var matched bool
			score, matched = productSearchScore(group[len(group)-1].Name, queryTokens)
			if !matched {
				continue
			}
		}
		results = append(results, scoredProduct{card: summarizeProduct(group), score: score})
	}
	if search {
		hasExact := false
		for _, result := range results {
			if result.score >= 100 {
				hasExact = true
				break
			}
		}
		if hasExact {
			filtered := results[:0]
			for _, result := range results {
				if result.score >= 100 {
					filtered = append(filtered, result)
				}
			}
			results = filtered
		}
		sort.SliceStable(results, func(i, j int) bool {
			if results[i].score != results[j].score {
				return results[i].score > results[j].score
			}
			if !results[i].card.LastPurchasedAt.Equal(results[j].card.LastPurchasedAt) {
				return results[i].card.LastPurchasedAt.After(results[j].card.LastPurchasedAt)
			}
			return results[i].card.ProductName < results[j].card.ProductName
		})
	} else {
		sort.SliceStable(results, func(i, j int) bool {
			left, _ := new(big.Rat).SetString(results[i].card.TotalSpent)
			right, _ := new(big.Rat).SetString(results[j].card.TotalSpent)
			if comparison := left.Cmp(right); comparison != 0 {
				return comparison > 0
			}
			if !results[i].card.LastPurchasedAt.Equal(results[j].card.LastPurchasedAt) {
				return results[i].card.LastPurchasedAt.After(results[j].card.LastPurchasedAt)
			}
			return results[i].card.ProductName < results[j].card.ProductName
		})
	}
	limit := productCatalogLimit
	if search {
		limit = productSearchLimit
	}
	if len(results) > limit {
		results = results[:limit]
	}
	cards := make([]ProductCard, len(results))
	for index, result := range results {
		cards[index] = result.card
	}
	return cards
}

func summarizeProduct(group []PricePoint) ProductCard {
	prices := make([]*big.Rat, 0, len(group))
	totalSpent := new(big.Rat)
	cheapest := new(big.Rat)
	var cheapestPoint PricePoint
	observations := make([]Observation, 0, len(group))
	history := make([]PriceHistoryPoint, 0, min(len(group), productHistoryLimit))
	for index, point := range group {
		price, _ := new(big.Rat).SetString(point.UnitPrice)
		lineSum, _ := new(big.Rat).SetString(point.LineSum)
		prices = append(prices, price)
		totalSpent.Add(totalSpent, lineSum)
		if index == 0 || price.Cmp(cheapest) < 0 {
			cheapest, cheapestPoint = price, point
		}
		observations = append(observations, Observation{
			TenantID: point.TenantID, OwnerID: point.OwnerID, ReceiptID: point.ReceiptID,
			Name: point.Name, Quantity: point.Quantity, LineSum: point.LineSum, Purchased: point.PurchasedAt,
		})
		history = append(history, PriceHistoryPoint{
			ReceiptID: point.ReceiptID, ItemID: point.ItemID, PurchasedAt: point.PurchasedAt,
			Merchant: point.Merchant, Name: point.Name, UnitPrice: point.UnitPrice,
		})
	}
	if len(history) > productHistoryLimit {
		history = history[len(history)-productHistoryLimit:]
	}
	sorted := append([]*big.Rat(nil), prices...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].Cmp(sorted[j]) < 0 })
	last := group[len(group)-1]
	lastPrice, _ := new(big.Rat).SetString(last.UnitPrice)
	card := ProductCard{
		ProductName: last.Name, PurchaseCount: len(group), UsualUnitPrice: formatFixed(median(sorted), priceScale),
		LastUnitPrice: formatFixed(lastPrice, priceScale), LastPurchasedAt: last.PurchasedAt,
		LastMerchant: nonBlankStringPointer(last.Merchant), CheapestUnitPrice: formatFixed(cheapest, priceScale),
		CheapestMerchant: nonBlankStringPointer(cheapestPoint.Merchant), TotalSpent: formatFixed(totalSpent, 2),
		ChartAvailable: len(group) >= 2, History: history,
	}
	if len(group) >= 2 {
		prior := observations[:len(observations)-1]
		comparison, signal, err := Compare(observations[len(observations)-1], prior)
		if err == nil && comparison.HasBaseline {
			card.HasBaseline = true
			card.BaselineUnitPrice = stringPointer(comparison.Baseline)
			card.Change = stringPointer(comparison.Change)
			card.Relative = stringPointer(comparison.Relative)
			card.PriorPurchases = comparison.PriorPurchases
			card.Signal = signal
			if signal {
				card.Direction = &comparison.Direction
			}
		}
	}
	return card
}

func productSearchScore(name string, queryTokens []string) (int, bool) {
	productTokens := identityTokens(name)
	if len(productTokens) == 0 {
		return 0, false
	}
	matched := 0
	allExact := true
	for _, queryToken := range queryTokens {
		tokenExact := false
		for _, productToken := range productTokens {
			if queryToken == productToken || strings.Contains(productToken, queryToken) {
				tokenExact = true
				break
			}
		}
		if tokenExact {
			matched++
			continue
		}
		allExact = false
		best := 0.0
		for _, productToken := range productTokens {
			if len([]rune(queryToken)) >= 4 && sequenceRatio(queryToken, productToken) > best {
				best = sequenceRatio(queryToken, productToken)
			}
		}
		if best >= 0.75 {
			matched++
		}
	}
	if matched == 0 {
		return 0, false
	}
	if allExact && matched == len(queryTokens) {
		return 100 + matched, true
	}
	return matched, true
}

func nonBlankStringPointer(value *string) *string {
	if value == nil || strings.TrimSpace(*value) == "" {
		return nil
	}
	copy := *value
	return &copy
}
