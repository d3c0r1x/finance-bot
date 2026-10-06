package advice

import (
	"errors"
	"math/big"
	"strings"
	"time"
)

const F45AlgorithmVersion = "goal-progress-f45.v1"

type F45ProgressRequest struct {
	InputWatermark string        `json:"inputWatermark"`
	AsOf           time.Time     `json:"asOf"`
	Goal           F45Goal       `json:"goal"`
	Purchases      []F45Purchase `json:"purchases"`
}

type F45Goal struct {
	Key               string    `json:"key"`
	Scope             string    `json:"scope"`
	Unit              string    `json:"unit"`
	AcceptedAt        time.Time `json:"acceptedAt"`
	EndsAt            time.Time `json:"endsAt"`
	CountTarget       int       `json:"countTarget"`
	MonthlyLimit      string    `json:"monthlyLimit,omitempty"`
	MemberProductKeys []string  `json:"memberProductKeys,omitempty"`
}

type F45Purchase struct {
	ProductKey  string    `json:"productKey"`
	LineSum     *string   `json:"lineSum"`
	PurchasedAt time.Time `json:"purchasedAt"`
}

type F45Progress struct {
	AlgorithmVersion string    `json:"algorithmVersion"`
	InputWatermark   string    `json:"inputWatermark"`
	Unit             string    `json:"unit"`
	Bought           int       `json:"bought"`
	Spent            *string   `json:"spent"`
	AmountsUnknown   bool      `json:"amountsUnknown"`
	Over             *bool     `json:"over"`
	Met              *bool     `json:"met"`
	Finished         bool      `json:"finished"`
	DaysLeft         int       `json:"daysLeft"`
	WindowStart      time.Time `json:"windowStart"`
	WindowEnd        time.Time `json:"windowEnd"`
}

func BuildF45Progress(request F45ProgressRequest) (F45Progress, error) {
	goal := request.Goal
	validKey := f44ProductKeyPattern.MatchString(goal.Key)
	if goal.Scope == "group" {
		validKey = strings.HasPrefix(goal.Key, "cat:") && f44ProductKeyPattern.MatchString(strings.TrimPrefix(goal.Key, "cat:"))
	}
	if !f44WatermarkPattern.MatchString(request.InputWatermark) || request.AsOf.IsZero() ||
		request.AsOf.Before(goal.AcceptedAt) || !validKey ||
		(goal.Scope != "product" && goal.Scope != "group") ||
		(goal.Unit != "count" && goal.Unit != "sum") || goal.AcceptedAt.IsZero() || goal.EndsAt.IsZero() ||
		!goal.EndsAt.After(goal.AcceptedAt) || goal.EndsAt.Sub(goal.AcceptedAt) != 30*24*time.Hour ||
		len(request.Purchases) > f44MaxInputs ||
		(goal.Unit == "count" && (goal.CountTarget < 1 || goal.MonthlyLimit != "")) ||
		(goal.Unit == "sum" && (goal.Scope != "product" || goal.CountTarget != 0 || !f44MoneyPattern.MatchString(goal.MonthlyLimit))) ||
		(goal.Scope == "product" && len(goal.MemberProductKeys) != 0) ||
		(goal.Scope == "group" && len(goal.MemberProductKeys) == 0) {
		return F45Progress{}, errors.New("invalid F45 progress request")
	}

	members := make(map[string]struct{}, len(goal.MemberProductKeys))
	for _, key := range goal.MemberProductKeys {
		if !f44ProductKeyPattern.MatchString(key) {
			return F45Progress{}, errors.New("invalid F45 goal membership")
		}
		if _, duplicate := members[key]; duplicate {
			return F45Progress{}, errors.New("duplicate F45 goal membership")
		}
		members[key] = struct{}{}
	}

	var total big.Rat
	unknown := false
	bought := 0
	for _, purchase := range request.Purchases {
		if !f44ProductKeyPattern.MatchString(purchase.ProductKey) || purchase.PurchasedAt.IsZero() {
			return F45Progress{}, errors.New("invalid F45 purchase")
		}
		var amount *big.Rat
		if purchase.LineSum != nil {
			var ok bool
			amount, ok = new(big.Rat).SetString(*purchase.LineSum)
			if !ok || !f44MoneyPattern.MatchString(*purchase.LineSum) {
				return F45Progress{}, errors.New("invalid F45 purchase amount")
			}
		}
		if purchase.PurchasedAt.Before(goal.AcceptedAt) || purchase.PurchasedAt.After(request.AsOf) ||
			purchase.PurchasedAt.After(goal.EndsAt) {
			continue
		}
		if goal.Scope == "product" && purchase.ProductKey != goal.Key {
			continue
		}
		if goal.Scope == "group" {
			if _, exists := members[purchase.ProductKey]; !exists {
				continue
			}
		}
		bought++
		if amount == nil {
			unknown = true
		} else {
			total.Add(&total, amount)
		}
	}

	finished := request.AsOf.After(goal.EndsAt)
	progress := F45Progress{
		AlgorithmVersion: F45AlgorithmVersion, InputWatermark: request.InputWatermark,
		Unit: goal.Unit, Bought: bought, AmountsUnknown: unknown, Finished: finished,
		DaysLeft:    max(0, int((goal.EndsAt.Sub(request.AsOf)+24*time.Hour-1)/(24*time.Hour))),
		WindowStart: goal.AcceptedAt, WindowEnd: goal.EndsAt,
	}
	if goal.Unit == "count" {
		over, met := bought > goal.CountTarget, bought <= goal.CountTarget
		progress.Over, progress.Met = &over, &met
	}
	if !unknown {
		spent := total.FloatString(2)
		if !f44MoneyPattern.MatchString(spent) {
			return F45Progress{}, errors.New("F45 progress amount exceeds supported precision")
		}
		progress.Spent = &spent
	}
	if goal.Unit == "sum" && !unknown {
		limit, _ := new(big.Rat).SetString(goal.MonthlyLimit)
		over, met := total.Cmp(limit) > 0, total.Cmp(limit) <= 0
		progress.Over, progress.Met = &over, &met
	}
	return progress, nil
}
