package prices

import (
	"errors"
	"math/big"
	"sort"
	"time"
)

type Direction string

const AlgorithmVersion = "price-projection.v1"

const (
	DirectionUp   Direction = "up"
	DirectionDown Direction = "down"
)

type Observation struct {
	TenantID  string
	OwnerID   string
	ReceiptID string
	Name      string
	Quantity  string
	LineSum   string
	Purchased time.Time
}

type Comparison struct {
	AlgorithmVersion string
	HasBaseline      bool
	Signal           bool
	Current          string
	Baseline         string
	Change           string
	Relative         string
	Direction        Direction
	PriorPurchases   int
}

func Compare(current Observation, history []Observation) (Comparison, bool, error) {
	if current.TenantID == "" || current.OwnerID == "" || current.ReceiptID == "" || current.Name == "" || current.Purchased.IsZero() {
		return Comparison{}, false, errors.New("current purchase requires tenant, owner, receipt, product, and time")
	}
	currentPrice, err := parseObservationPrice(current)
	if err != nil {
		return Comparison{}, false, err
	}

	previousPrices := make([]*big.Rat, 0, len(history))
	for _, observation := range history {
		if observation.TenantID != current.TenantID || observation.OwnerID != current.OwnerID ||
			observation.ReceiptID == current.ReceiptID || !observation.Purchased.Before(current.Purchased) ||
			!SameProduct(current.Name, observation.Name) {
			continue
		}
		price, priceErr := parseObservationPrice(observation)
		if priceErr != nil {
			continue
		}
		previousPrices = append(previousPrices, price)
	}
	if len(previousPrices) == 0 {
		return Comparison{}, false, nil
	}
	sort.Slice(previousPrices, func(i, j int) bool { return previousPrices[i].Cmp(previousPrices[j]) < 0 })
	baseline := median(previousPrices)
	change := new(big.Rat).Sub(currentPrice, baseline)
	absChange := new(big.Rat).Abs(new(big.Rat).Set(change))
	absRelative := new(big.Rat).Abs(new(big.Rat).Quo(new(big.Rat).Set(change), baseline))
	minAmount := new(big.Rat).SetInt64(10)
	minRelative := new(big.Rat).SetFrac64(12, 100)
	comparison := Comparison{
		AlgorithmVersion: AlgorithmVersion,
		HasBaseline:      true,
		Current:          formatFixed(currentPrice, priceScale),
		Baseline:         formatFixed(baseline, priceScale),
		Change:           formatFixed(change, priceScale),
		Relative:         formatFixed(new(big.Rat).Quo(change, baseline), priceScale),
		PriorPurchases:   len(previousPrices),
	}
	if absChange.Cmp(minAmount) < 0 || absRelative.Cmp(minRelative) < 0 {
		return comparison, false, nil
	}
	comparison.Direction = DirectionUp
	if change.Sign() < 0 {
		comparison.Direction = DirectionDown
	}
	comparison.Signal = true
	return comparison, true, nil
}

func median(sortedPrices []*big.Rat) *big.Rat {
	middle := len(sortedPrices) / 2
	if len(sortedPrices)%2 == 1 {
		return new(big.Rat).Set(sortedPrices[middle])
	}
	sum := new(big.Rat).Add(sortedPrices[middle-1], sortedPrices[middle])
	return sum.Quo(sum, big.NewRat(2, 1))
}
