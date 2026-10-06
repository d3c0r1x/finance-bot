package advice

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math/big"
	"strings"
)

const RecalculationImpactAlgorithmVersion = "receipt-recalculation-impact.v1"

type RecalculationImpactRequest struct {
	Before *WasteRequest `json:"before"`
	After  *WasteRequest `json:"after"`
}

type RecalculationImpact struct {
	AlgorithmVersion    string  `json:"algorithmVersion"`
	InputVersion        string  `json:"inputVersion"`
	ReasonCode          string  `json:"reasonCode"`
	Completeness        string  `json:"completeness"`
	OptionalSpendBefore *string `json:"optionalSpendBefore"`
	OptionalSpendAfter  *string `json:"optionalSpendAfter"`
	OptionalSpendDelta  *string `json:"optionalSpendDelta"`
}

func BuildRecalculationImpact(request RecalculationImpactRequest) (RecalculationImpact, error) {
	if request.Before == nil || request.After == nil || !sameWasteWindow(*request.Before, *request.After) {
		return RecalculationImpact{}, errors.New("recalculation impact requires matching before and after windows")
	}
	before, err := BuildWasteReport(*request.Before)
	if err != nil {
		return RecalculationImpact{}, err
	}
	after, err := BuildWasteReport(*request.After)
	if err != nil {
		return RecalculationImpact{}, err
	}
	if !sameWasteFacts(request.Before.Items, request.After.Items) {
		return RecalculationImpact{}, errors.New("recalculation impact cannot change receipt facts")
	}
	result := RecalculationImpact{
		AlgorithmVersion: RecalculationImpactAlgorithmVersion,
		InputVersion:     recalculationImpactInputVersion(before.InputVersion, after.InputVersion),
		ReasonCode:       "available", Completeness: "complete",
	}
	if !before.Available || !after.Available {
		result.Completeness = "partial"
		result.ReasonCode = "analytics_unavailable"
		if before.ReasonCode == "missing_amounts" || after.ReasonCode == "missing_amounts" {
			result.ReasonCode = "missing_amounts"
		} else if before.ReasonCode == "no_reviewed_items" && after.ReasonCode == "no_reviewed_items" {
			result.Completeness = "complete"
			result.ReasonCode = "no_reviewed_items"
		}
		return result, nil
	}
	result.OptionalSpendBefore = before.OptionalSpend
	result.OptionalSpendAfter = after.OptionalSpend
	beforeAmount, okBefore := new(big.Rat).SetString(*before.OptionalSpend)
	afterAmount, okAfter := new(big.Rat).SetString(*after.OptionalSpend)
	if !okBefore || !okAfter {
		return RecalculationImpact{}, errors.New("waste analytics returned an invalid optional spend")
	}
	delta := new(big.Rat).Sub(afterAmount, beforeAmount)
	formatted := formatWasteMoney(delta)
	result.OptionalSpendDelta = &formatted
	return result, nil
}

func sameWasteWindow(before, after WasteRequest) bool {
	return before.FromDate == after.FromDate && before.ToDate == after.ToDate &&
		before.AsOf.Equal(after.AsOf) && before.TimeZone == after.TimeZone
}

func sameWasteFacts(before, after []WasteLine) bool {
	if len(before) != len(after) {
		return false
	}
	byID := make(map[string]WasteLine, len(before))
	for _, line := range before {
		byID[strings.ToLower(line.ItemID)] = line
	}
	for _, line := range after {
		old, ok := byID[strings.ToLower(line.ItemID)]
		if !ok || old.ProductKey != line.ProductKey || old.Name != line.Name ||
			!sameOptionalString(old.LineSum, line.LineSum) || !old.PurchasedAt.Equal(line.PurchasedAt) ||
			old.Allowed != line.Allowed || old.ItemVersion != line.ItemVersion || old.DecisionVersion != line.DecisionVersion {
			return false
		}
		delete(byID, strings.ToLower(line.ItemID))
	}
	return len(byID) == 0
}

func sameOptionalString(left, right *string) bool {
	if left == nil || right == nil {
		return left == nil && right == nil
	}
	return *left == *right
}

func recalculationImpactInputVersion(before, after string) string {
	encoded, _ := json.Marshal(struct {
		Before string `json:"before"`
		After  string `json:"after"`
	}{before, after})
	sum := sha256.Sum256(encoded)
	return hex.EncodeToString(sum[:])
}
