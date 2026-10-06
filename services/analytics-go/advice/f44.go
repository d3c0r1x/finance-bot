package advice

import (
	"errors"
	"math/big"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	F44AlgorithmVersion = "goal-candidates-f44.v1"
	f44MonthDays        = 30
	f44MinMonthlyRate   = 2
	f44MinMoneySaving   = 100
	f44MaxInputs        = 50000
)

var f44MoneyPattern = regexp.MustCompile(`^(?:0|[1-9][0-9]{0,14})\.[0-9]{2}$`)
var f44WordsPattern = regexp.MustCompile(`[a-zа-я0-9-]+`)
var f44WatermarkPattern = regexp.MustCompile(`^[1-9][0-9]{0,19}$`)
var f44ProductKeyPattern = regexp.MustCompile(`^[a-zа-я0-9]+$`)

type F44Request struct {
	InputWatermark string        `json:"inputWatermark"`
	AsOf           time.Time     `json:"asOf"`
	Unit           string        `json:"unit"`
	Decisions      []F44Decision `json:"decisions"`
	Purchases      []F44Purchase `json:"purchases"`
}

type F44Decision struct {
	ProductKey   string `json:"productKey"`
	Name         string `json:"name"`
	HarmfulCount int    `json:"harmfulCount"`
	ModelGuess   bool   `json:"modelGuess"`
	Confirmed    bool   `json:"confirmed"`
	Allowed      bool   `json:"allowed"`
}

type F44Purchase struct {
	ProductKey  string    `json:"productKey"`
	Name        string    `json:"name"`
	LineSum     *string   `json:"lineSum"`
	PurchasedAt time.Time `json:"purchasedAt"`
}

type F44Report struct {
	AlgorithmVersion string                `json:"algorithmVersion"`
	InputWatermark   string                `json:"inputWatermark"`
	Unit             string                `json:"unit"`
	Products         []F44Candidate        `json:"products"`
	Groups           []F44Candidate        `json:"groups"`
	Skipped          []F44SkippedCandidate `json:"skipped"`
}

type F44Candidate struct {
	Key                string   `json:"key"`
	ProductKey         string   `json:"productKey,omitempty"`
	MemberProductKeys  []string `json:"memberProductKeys,omitempty"`
	Name               string   `json:"name"`
	Unit               string   `json:"unit"`
	MonthlyRate        string   `json:"monthlyRate"`
	CountTarget        int      `json:"countTarget"`
	MonthlySpend       *string  `json:"monthlySpend"`
	MonthlyLimit       string   `json:"monthlyLimit,omitempty"`
	EstimatedReduction *string  `json:"estimatedReduction"`
	PurchaseCount      int      `json:"purchaseCount"`
	EvidenceCount      int      `json:"evidenceCount"`
}

type F44SkippedCandidate struct {
	ProductKey   string  `json:"productKey"`
	Name         string  `json:"name"`
	MonthlySpend *string `json:"monthlySpend"`
	ReasonCode   string  `json:"reasonCode"`
}

type f44Category struct {
	key   string
	name  string
	stems []string
}

var f44Categories = []f44Category{
	{key: "сладкое", name: "Сладкое", stems: []string{"шоколад", "конфет", "карамел", "ирис", "зефир", "халв", "печень", "пряник", "вафл", "рулет", "торт", "пирог", "пончик", "мармелад", "пастил", "суфле", "морожен", "эскимо", "джем", "варень", "сникерс", "баунти", "твикс"}},
	{key: "снеки", name: "Снеки и чипсы", stems: []string{"чипс", "сухарик", "снек", "попкорн", "соломк", "кириешки", "начос", "арахис", "фисташ", "кукуруз", "взлет"}},
	{key: "сладкие напитки", name: "Сладкие напитки", stems: []string{"кока", "пепси", "спрайт", "фанта", "лимонад", "энергет", "адреналин", "байкал", "таранто", "газирова", "juice", "морс", "=кола", "=сок", "нектар"}},
	{key: "фастфуд", name: "Фастфуд и перекусы", stems: []string{"бургер", "шаурм", "пицца", "наггетс", "фри", "хот-дог", "хотдог", "доширак", "роллтон", "ролл", "лапша"}},
	{key: "пакеты", name: "Пакеты и упаковка", stems: []string{"пакет", "упаковк", "фольг", "плен", "скотч"}},
}

type f44Purchase struct {
	F44Purchase
	amount *big.Rat
}

func BuildF44Candidates(request F44Request) (F44Report, error) {
	if !f44WatermarkPattern.MatchString(request.InputWatermark) || request.AsOf.IsZero() ||
		(request.Unit != "count" && request.Unit != "sum") || len(request.Decisions)+len(request.Purchases) > f44MaxInputs {
		return F44Report{}, errors.New("invalid F44 request bounds")
	}
	decisions := make(map[string]F44Decision, len(request.Decisions))
	for _, decision := range request.Decisions {
		if !f44ValidIdentity(decision.ProductKey, decision.Name) || decision.HarmfulCount < 0 || decision.HarmfulCount > 100000 {
			return F44Report{}, errors.New("invalid F44 decision")
		}
		if _, exists := decisions[decision.ProductKey]; exists {
			return F44Report{}, errors.New("duplicate F44 decision")
		}
		decisions[decision.ProductKey] = decision
	}
	purchases := make([]f44Purchase, 0, len(request.Purchases))
	byProduct := make(map[string][]f44Purchase)
	for _, purchase := range request.Purchases {
		var amount *big.Rat
		if purchase.LineSum != nil {
			var ok bool
			amount, ok = new(big.Rat).SetString(*purchase.LineSum)
			if !ok || !f44MoneyPattern.MatchString(*purchase.LineSum) || amount.Sign() < 0 {
				return F44Report{}, errors.New("invalid F44 purchase")
			}
		}
		if !f44ValidIdentity(purchase.ProductKey, purchase.Name) ||
			purchase.PurchasedAt.IsZero() || purchase.PurchasedAt.After(request.AsOf) {
			return F44Report{}, errors.New("invalid F44 purchase")
		}
		parsed := f44Purchase{F44Purchase: purchase, amount: amount}
		purchases = append(purchases, parsed)
		byProduct[purchase.ProductKey] = append(byProduct[purchase.ProductKey], parsed)
	}
	report := F44Report{
		AlgorithmVersion: F44AlgorithmVersion, InputWatermark: request.InputWatermark, Unit: request.Unit,
		Products: []F44Candidate{}, Groups: []F44Candidate{}, Skipped: []F44SkippedCandidate{},
	}
	for key, decision := range decisions {
		if !f44Eligible(decision) || decision.HarmfulCount < 2 {
			continue
		}
		entries := byProduct[key]
		candidate, skipped := f44Candidate(key, decision.Name, entries, request.Unit, decision.HarmfulCount)
		if candidate != nil {
			report.Products = append(report.Products, *candidate)
		} else if skipped != nil {
			report.Skipped = append(report.Skipped, *skipped)
		}
	}
	for _, category := range f44Categories {
		var evidence int
		eligibleProducts := make(map[string]struct{})
		for _, decision := range decisions {
			if f44Eligible(decision) && f44MatchesCategory(decision.Name, category) {
				evidence += decision.HarmfulCount
				eligibleProducts[decision.ProductKey] = struct{}{}
			}
		}
		if evidence < 2 {
			continue
		}
		var entries []f44Purchase
		members := make(map[string]struct{})
		for _, purchase := range purchases {
			if _, eligible := eligibleProducts[purchase.ProductKey]; eligible && f44MatchesCategory(purchase.Name, category) {
				entries = append(entries, purchase)
				members[purchase.ProductKey] = struct{}{}
			}
		}
		candidate, _ := f44Candidate("cat:"+category.key, category.name, entries, "count", evidence)
		if candidate == nil {
			continue
		}
		candidate.Unit = "count"
		candidate.ProductKey = ""
		candidate.MemberProductKeys = make([]string, 0, len(members))
		for key := range members {
			candidate.MemberProductKeys = append(candidate.MemberProductKeys, key)
		}
		sort.Strings(candidate.MemberProductKeys)
		report.Groups = append(report.Groups, *candidate)
	}
	f44SortCandidates(report.Products)
	f44SortCandidates(report.Groups)
	f44SortSkipped(report.Skipped)
	if len(report.Products) > 3 {
		report.Products = report.Products[:3]
	}
	if len(report.Groups) > 2 {
		report.Groups = report.Groups[:2]
	}
	if len(report.Skipped) > 3 {
		report.Skipped = report.Skipped[:3]
	}
	return report, nil
}

func f44ValidIdentity(key, name string) bool {
	return f44ProductKeyPattern.MatchString(key) && utf8.RuneCountInString(key) <= 256 &&
		utf8.ValidString(name) && strings.TrimSpace(name) != "" && utf8.RuneCountInString(name) <= 200
}

func f44Eligible(decision F44Decision) bool {
	return !decision.Allowed && decision.HarmfulCount > 0 && (!decision.ModelGuess || decision.Confirmed)
}

func f44Candidate(key, name string, entries []f44Purchase, unit string, evidence int) (*F44Candidate, *F44SkippedCandidate) {
	if len(entries) == 0 {
		return nil, nil
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].PurchasedAt.Before(entries[j].PurchasedAt) })
	span := int(entries[len(entries)-1].PurchasedAt.Sub(entries[0].PurchasedAt).Hours() / 24)
	if span < f44MonthDays {
		span = f44MonthDays
	}
	rate := f44Round(new(big.Rat).SetFrac(big.NewInt(int64(len(entries)*f44MonthDays)), big.NewInt(int64(span))), 2)
	if rate.Cmp(big.NewRat(f44MinMonthlyRate, 1)) < 0 {
		return nil, nil
	}
	target := max(1, f44RoundInt(new(big.Rat).Quo(rate, big.NewRat(2, 1))))
	if target >= f44RoundInt(rate) {
		return nil, nil
	}
	start := max(0, len(entries)-6)
	usual := new(big.Rat)
	unknownAmount := false
	for _, entry := range entries[start:] {
		if entry.amount == nil {
			unknownAmount = true
		} else {
			usual.Add(usual, entry.amount)
		}
	}
	if !unknownAmount {
		usual.Quo(usual, big.NewRat(int64(len(entries[start:])), 1))
	}
	countTarget := 0
	if unit == "count" {
		countTarget = int(target)
	}
	candidate := &F44Candidate{
		Key: key, ProductKey: key, Name: name, Unit: unit, MonthlyRate: rate.FloatString(2), CountTarget: countTarget,
		PurchaseCount: len(entries), EvidenceCount: evidence,
	}
	if unit == "count" {
		if !unknownAmount {
			monthlySpend := f44Round(new(big.Rat).Mul(rate, usual), 2).FloatString(2)
			reduction := f44Round(new(big.Rat).Mul(usual, new(big.Rat).Sub(rate, big.NewRat(int64(target), 1))), 2).FloatString(2)
			candidate.MonthlySpend = &monthlySpend
			candidate.EstimatedReduction = &reduction
		}
		return candidate, nil
	}
	if unknownAmount {
		return nil, &F44SkippedCandidate{ProductKey: key, Name: name, ReasonCode: "missing_amounts"}
	}
	monthlySpend := f44Round(new(big.Rat).Mul(rate, usual), 2)
	monthlySpendText := monthlySpend.FloatString(2)
	candidate.MonthlySpend = &monthlySpendText
	limit := f44MoneyStep(monthlySpend, usual)
	if limit == nil || new(big.Rat).Sub(monthlySpend, limit).Cmp(big.NewRat(f44MinMoneySaving, 1)) < 0 {
		return nil, &F44SkippedCandidate{ProductKey: key, Name: name, MonthlySpend: candidate.MonthlySpend, ReasonCode: "minimum_savings"}
	}
	candidate.MonthlyLimit = limit.FloatString(2)
	reduction := f44Round(new(big.Rat).Sub(monthlySpend, limit), 2).FloatString(2)
	candidate.EstimatedReduction = &reduction
	return candidate, nil
}

func f44MoneyStep(monthlySpend, usual *big.Rat) *big.Rat {
	if monthlySpend.Sign() <= 0 || usual.Sign() <= 0 {
		return nil
	}
	half := new(big.Rat).Quo(monthlySpend, big.NewRat(2, 1))
	step := int64(10)
	if half.Cmp(big.NewRat(1000, 1)) >= 0 {
		step = 50
	}
	units := f44RoundInt(new(big.Rat).Quo(half, big.NewRat(step, 1)))
	rounded := big.NewRat(units*step, 1)
	minimum := big.NewRat(f44RoundInt(usual), 1)
	if rounded.Cmp(minimum) < 0 {
		rounded = minimum
	}
	if rounded.Cmp(monthlySpend) >= 0 {
		return nil
	}
	return rounded
}

func f44MatchesCategory(name string, category f44Category) bool {
	lowered := strings.ReplaceAll(strings.ToLower(name), "ё", "е")
	for _, word := range f44WordsPattern.FindAllString(lowered, -1) {
		for _, stem := range category.stems {
			if strings.HasPrefix(stem, "=") {
				if word == strings.TrimPrefix(stem, "=") {
					return true
				}
			} else if strings.HasPrefix(word, stem) {
				return true
			}
		}
	}
	return false
}

func f44Round(value *big.Rat, places int) *big.Rat {
	factor := new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(places)), nil)
	scaled := new(big.Rat).Mul(value, new(big.Rat).SetInt(factor))
	return new(big.Rat).SetFrac(big.NewInt(f44RoundInt(scaled)), factor)
}

func f44RoundInt(value *big.Rat) int64 {
	quotient := new(big.Int).Quo(value.Num(), value.Denom())
	remainder := new(big.Int).Rem(value.Num(), value.Denom())
	if new(big.Int).Lsh(new(big.Int).Abs(remainder), 1).Cmp(value.Denom()) > 0 {
		if value.Sign() < 0 {
			quotient.Sub(quotient, big.NewInt(1))
		} else {
			quotient.Add(quotient, big.NewInt(1))
		}
	} else if new(big.Int).Lsh(new(big.Int).Abs(remainder), 1).Cmp(value.Denom()) == 0 && quotient.Bit(0) == 1 {
		if value.Sign() < 0 {
			quotient.Sub(quotient, big.NewInt(1))
		} else {
			quotient.Add(quotient, big.NewInt(1))
		}
	}
	return quotient.Int64()
}

func f44SortCandidates(candidates []F44Candidate) {
	sort.Slice(candidates, func(i, j int) bool {
		if (candidates[i].EstimatedReduction == nil) != (candidates[j].EstimatedReduction == nil) {
			return candidates[i].EstimatedReduction != nil
		}
		if candidates[i].EstimatedReduction == nil {
			a, _ := new(big.Rat).SetString(candidates[i].MonthlyRate)
			b, _ := new(big.Rat).SetString(candidates[j].MonthlyRate)
			if cmp := a.Cmp(b); cmp != 0 {
				return cmp > 0
			}
			if candidates[i].PurchaseCount != candidates[j].PurchaseCount {
				return candidates[i].PurchaseCount > candidates[j].PurchaseCount
			}
			return candidates[i].Key < candidates[j].Key
		}
		a, _ := new(big.Rat).SetString(*candidates[i].EstimatedReduction)
		b, _ := new(big.Rat).SetString(*candidates[j].EstimatedReduction)
		if cmp := a.Cmp(b); cmp != 0 {
			return cmp > 0
		}
		if candidates[i].PurchaseCount != candidates[j].PurchaseCount {
			return candidates[i].PurchaseCount > candidates[j].PurchaseCount
		}
		return candidates[i].Key < candidates[j].Key
	})
}

func f44SortSkipped(candidates []F44SkippedCandidate) {
	sort.Slice(candidates, func(i, j int) bool { return candidates[i].ProductKey < candidates[j].ProductKey })
}
