package advice

import (
	"errors"
	"math/big"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	F43AlgorithmVersion = "advice-f43.v1"
	f43SavingsDays      = 90
	f43TrendWindows     = 4
	f43WindowDays       = 7
	f43MinEffectDays    = 21
	f43MinBeforeBuys    = 2
	f43TopSavings       = 5
	f43TopEffects       = 4
)

var f43SignedMoneyPattern = regexp.MustCompile(`^-?(?:0|[1-9][0-9]{0,17})\.[0-9]{2}$`)

type F43Request struct {
	InputWatermark string             `json:"inputWatermark"`
	AsOf           time.Time          `json:"asOf"`
	TimeZone       string             `json:"timeZone"`
	Income         *string            `json:"income,omitempty"`
	MonthlyLimit   *string            `json:"monthlyLimit,omitempty"`
	Items          []F43Item          `json:"items"`
	Recalculations []F43Recalculation `json:"recalculations"`
}

// F43Item is a confirmed personal purchase fact. AdviceGiven is set by Core only
// when a harmful/unnecessary verdict was shown at purchase time, never by an F42 edit.
type F43Item struct {
	ItemID      string    `json:"itemId"`
	ProductKey  string    `json:"productKey"`
	Name        string    `json:"name"`
	LineSum     *string   `json:"lineSum"`
	Verdict     string    `json:"verdict"`
	Advice      string    `json:"advice"`
	AdviceGiven bool      `json:"adviceGiven"`
	Allowed     bool      `json:"allowed"`
	PurchasedAt time.Time `json:"purchasedAt"`
}

type F43Recalculation struct {
	ChangedAt          time.Time `json:"changedAt"`
	ChangedItemCount   int       `json:"changedItemCount"`
	OptionalSpendDelta *string   `json:"optionalSpendDelta"`
}

type F43Report struct {
	AlgorithmVersion string                 `json:"algorithmVersion"`
	InputWatermark   string                 `json:"inputWatermark"`
	Completeness     string                 `json:"completeness"`
	ReasonCode       string                 `json:"reasonCode"`
	Savings          F43SavingsReport       `json:"savings"`
	Trend            F43TrendReport         `json:"trend"`
	Effects          F43EffectsReport       `json:"effects"`
	Recalculation    F43RecalculationReport `json:"recalculation"`
}

type F43SavingsReport struct {
	Available      bool              `json:"available"`
	ReasonCode     string            `json:"reasonCode"`
	Label          string            `json:"label"`
	Days           int               `json:"days"`
	MonthlyCeiling *string           `json:"monthlyCeiling"`
	ShareOfIncome  *string           `json:"shareOfIncome"`
	ShareOfLimit   *string           `json:"shareOfLimit"`
	Groups         []F43SavingsGroup `json:"groups"`
}

type F43SavingsGroup struct {
	ProductKey     string `json:"productKey"`
	Name           string `json:"name"`
	Count          int    `json:"count"`
	Spend          string `json:"spend"`
	MonthlyCeiling string `json:"monthlyCeiling"`
}

type F43TrendReport struct {
	Available  bool           `json:"available"`
	ReasonCode string         `json:"reasonCode"`
	Weeks      []F43TrendWeek `json:"weeks"`
	Delta      *string        `json:"delta"`
	Direction  string         `json:"direction"`
}

type F43TrendWeek struct {
	Start         string `json:"start"`
	End           string `json:"end"`
	Spend         string `json:"spend"`
	OptionalSpend string `json:"optionalSpend"`
	OptionalShare string `json:"optionalShare"`
	ItemCount     int    `json:"itemCount"`
	Recalculated  bool   `json:"recalculated"`
}

type F43EffectsReport struct {
	Effects        []F43Effect        `json:"effects"`
	Pending        []F43PendingEffect `json:"pending"`
	CausalityClaim bool               `json:"causalityClaim"`
}

type F43Effect struct {
	ProductKey     string  `json:"productKey"`
	Name           string  `json:"name"`
	Advice         string  `json:"advice"`
	BeforeCount    int     `json:"beforeCount"`
	AfterCount     int     `json:"afterCount"`
	DaysBefore     int     `json:"daysBefore"`
	DaysAfter      int     `json:"daysAfter"`
	IntervalBefore string  `json:"intervalBefore"`
	IntervalAfter  *string `json:"intervalAfter"`
	Change         string  `json:"change"`
	Direction      string  `json:"direction"`
	AfterSpend     *string `json:"afterSpend"`
}

type F43PendingEffect struct {
	ProductKey string `json:"productKey"`
	Name       string `json:"name"`
	DaysAfter  int    `json:"daysAfter"`
	DaysLeft   int    `json:"daysLeft"`
}

type F43RecalculationReport struct {
	Available          bool                     `json:"available"`
	ChangedItemCount   int                      `json:"changedItemCount"`
	OptionalSpendDelta *string                  `json:"optionalSpendDelta"`
	Windows            []F43RecalculationWindow `json:"windows"`
}

type F43RecalculationWindow struct {
	Start              string  `json:"start"`
	End                string  `json:"end"`
	ChangedItemCount   int     `json:"changedItemCount"`
	OptionalSpendDelta *string `json:"optionalSpendDelta"`
}

type f43ParsedItem struct {
	item   F43Item
	amount *big.Rat
}

type f43Group struct {
	key      string
	name     string
	count    int
	spend    *big.Rat
	latestAt time.Time
}

func BuildF43Report(request F43Request) (F43Report, error) {
	location, err := validateF43Request(request)
	if err != nil {
		return F43Report{}, err
	}
	report := F43Report{
		AlgorithmVersion: F43AlgorithmVersion,
		InputWatermark:   request.InputWatermark,
		Completeness:     "complete",
		ReasonCode:       "available",
		Savings: F43SavingsReport{
			ReasonCode: "insufficient_history", Label: "theoretical_ceiling_not_actual_savings",
			Days: f43SavingsDays, Groups: []F43SavingsGroup{},
		},
		Trend:         F43TrendReport{ReasonCode: "insufficient_history", Weeks: []F43TrendWeek{}},
		Effects:       F43EffectsReport{Effects: []F43Effect{}, Pending: []F43PendingEffect{}, CausalityClaim: false},
		Recalculation: F43RecalculationReport{Windows: []F43RecalculationWindow{}},
	}
	parsed := make([]f43ParsedItem, 0, len(request.Items))
	missingAmount := false
	for _, item := range request.Items {
		var amount *big.Rat
		if item.LineSum == nil {
			missingAmount = true
		} else {
			amount, _ = new(big.Rat).SetString(*item.LineSum)
		}
		parsed = append(parsed, f43ParsedItem{item: item, amount: amount})
	}

	f43BuildEffects(&report, request, parsed, location)
	f43BuildRecalculation(&report, request.Recalculations, location)
	if missingAmount {
		report.Completeness = "partial"
		report.ReasonCode = "missing_amounts"
		report.Savings.ReasonCode = "missing_amounts"
		report.Trend.ReasonCode = "missing_amounts"
		return report, nil
	}
	f43BuildSavings(&report, request, parsed)
	f43BuildTrend(&report, request, parsed, location)
	if report.Savings.Available || report.Trend.Available || len(report.Effects.Effects) > 0 || len(report.Effects.Pending) > 0 {
		return report, nil
	}
	report.ReasonCode = "insufficient_history"
	return report, nil
}

func validateF43Request(request F43Request) (*time.Location, error) {
	if request.AsOf.IsZero() || request.InputWatermark == "" || strings.TrimSpace(request.TimeZone) == "" || len(request.TimeZone) > 64 {
		return nil, errors.New("F43 request requires asOf, watermark and time zone")
	}
	watermark, err := strconv.ParseUint(request.InputWatermark, 10, 64)
	if err != nil || watermark == 0 || strconv.FormatUint(watermark, 10) != request.InputWatermark {
		return nil, errors.New("F43 input watermark is invalid")
	}
	location, err := time.LoadLocation(request.TimeZone)
	if err != nil {
		return nil, errors.New("F43 time zone is invalid")
	}
	if len(request.Items) > maxWasteItems {
		return nil, errors.New("F43 request has too many items")
	}
	if len(request.Recalculations) > maxWasteItems {
		return nil, errors.New("F43 request has too many recalculation records")
	}
	seen := make(map[string]struct{}, len(request.Items))
	for _, item := range request.Items {
		if !wasteUUIDPattern.MatchString(item.ItemID) || strings.TrimSpace(item.Name) == "" ||
			utf8.RuneCountInString(item.Name) > 200 || utf8.RuneCountInString(item.ProductKey) > 256 ||
			!wasteKeyPattern.MatchString(item.ProductKey) || utf8.RuneCountInString(item.Advice) > 500 ||
			item.PurchasedAt.IsZero() || item.PurchasedAt.After(request.AsOf) {
			return nil, errors.New("F43 request contains an invalid purchase")
		}
		key := strings.ToLower(item.ItemID)
		if _, exists := seen[key]; exists {
			return nil, errors.New("F43 request contains duplicate item IDs")
		}
		seen[key] = struct{}{}
		if item.Verdict != "" && item.Verdict != "useful" && item.Verdict != "neutral" && item.Verdict != "harmful" && item.Verdict != "unnecessary" {
			return nil, errors.New("F43 request contains an unsupported verdict")
		}
		if item.LineSum != nil && !wasteMoneyPattern.MatchString(*item.LineSum) {
			return nil, errors.New("F43 request contains an invalid amount")
		}
	}
	for _, recalculation := range request.Recalculations {
		if recalculation.ChangedAt.IsZero() || recalculation.ChangedAt.After(request.AsOf) || recalculation.ChangedItemCount < 0 || recalculation.ChangedItemCount > maxWasteItems ||
			recalculation.OptionalSpendDelta != nil && !f43SignedMoneyPattern.MatchString(*recalculation.OptionalSpendDelta) {
			return nil, errors.New("F43 request contains an invalid recalculation")
		}
	}
	for _, value := range []*string{request.Income, request.MonthlyLimit} {
		if value != nil && !wasteMoneyPattern.MatchString(*value) {
			return nil, errors.New("F43 request contains an invalid profile amount")
		}
	}
	return location, nil
}

func f43BuildSavings(report *F43Report, request F43Request, items []f43ParsedItem) {
	cutoff := request.AsOf.AddDate(0, 0, -f43SavingsDays)
	groups := make(map[string]*f43Group)
	for _, parsed := range items {
		item := parsed.item
		if item.PurchasedAt.Before(cutoff) || item.Allowed || (item.Verdict != "harmful" && item.Verdict != "unnecessary") || item.ProductKey == "" {
			continue
		}
		group := groups[item.ProductKey]
		if group == nil {
			group = &f43Group{key: item.ProductKey, name: item.Name, spend: new(big.Rat)}
			groups[item.ProductKey] = group
		}
		group.count++
		group.spend.Add(group.spend, parsed.amount)
		if item.PurchasedAt.After(group.latestAt) {
			group.name, group.latestAt = item.Name, item.PurchasedAt
		}
	}
	eligible := make([]*f43Group, 0, len(groups))
	for _, group := range groups {
		if group.count >= f43MinBeforeBuys && group.spend.Sign() > 0 {
			eligible = append(eligible, group)
		}
	}
	sort.Slice(eligible, func(i, j int) bool {
		if eligible[i].count != eligible[j].count {
			return eligible[i].count > eligible[j].count
		}
		if comparison := eligible[i].spend.Cmp(eligible[j].spend); comparison != 0 {
			return comparison > 0
		}
		return eligible[i].key < eligible[j].key
	})
	if len(eligible) == 0 {
		return
	}
	groupResults := make([]F43SavingsGroup, 0, min(len(eligible), f43TopSavings))
	totalMonthly := new(big.Rat)
	for _, group := range eligible {
		monthly := new(big.Rat).Mul(group.spend, big.NewRat(30, f43SavingsDays))
		roundedMonthly := new(big.Rat)
		roundedMonthly.SetString(formatWasteMoney(monthly))
		totalMonthly.Add(totalMonthly, roundedMonthly)
		if len(groupResults) < f43TopSavings {
			groupResults = append(groupResults, F43SavingsGroup{
				ProductKey: group.key, Name: group.name, Count: group.count, Spend: formatWasteMoney(group.spend),
				MonthlyCeiling: formatWasteMoney(monthly),
			})
		}
	}
	report.Savings.Available = totalMonthly.Sign() > 0
	if !report.Savings.Available {
		return
	}
	report.Savings.ReasonCode = "available"
	report.Savings.MonthlyCeiling = stringPointer(formatWasteMoney(totalMonthly))
	report.Savings.Groups = groupResults
	if request.Income != nil {
		report.Savings.ShareOfIncome = f43RatioPercent(totalMonthly, *request.Income)
	}
	if request.MonthlyLimit != nil {
		report.Savings.ShareOfLimit = f43RatioPercent(totalMonthly, *request.MonthlyLimit)
	}
}

func f43BuildTrend(report *F43Report, request F43Request, items []f43ParsedItem, location *time.Location) {
	nowLocal := request.AsOf.In(location)
	nowYear, nowMonth, nowDay := nowLocal.Date()
	nowDate := time.Date(nowYear, nowMonth, nowDay, 0, 0, 0, 0, time.UTC)
	type bucket struct {
		spend    *big.Rat
		optional *big.Rat
		count    int
		found    bool
	}
	buckets := [f43TrendWindows]bucket{}
	for i := range buckets {
		buckets[i] = bucket{spend: new(big.Rat), optional: new(big.Rat)}
	}
	for _, parsed := range items {
		purchased := parsed.item.PurchasedAt.In(location)
		year, month, day := purchased.Date()
		purchaseDate := time.Date(year, month, day, 0, 0, 0, 0, time.UTC)
		ageDays := int(nowDate.Sub(purchaseDate) / (24 * time.Hour))
		if ageDays < 0 || ageDays >= f43TrendWindows*f43WindowDays {
			continue
		}
		index := ageDays / f43WindowDays
		bucket := &buckets[index]
		bucket.found = true
		bucket.count++
		bucket.spend.Add(bucket.spend, parsed.amount)
		if parsed.item.Verdict == "harmful" || parsed.item.Verdict == "unnecessary" {
			bucket.optional.Add(bucket.optional, parsed.amount)
		}
	}
	weeks := make([]F43TrendWeek, 0, f43TrendWindows)
	weekByIndex := make(map[int]int, f43TrendWindows)
	for index := f43TrendWindows - 1; index >= 0; index-- {
		bucket := buckets[index]
		if !bucket.found || bucket.spend.Sign() <= 0 {
			continue
		}
		share := new(big.Rat).Quo(bucket.optional, bucket.spend)
		end := nowDate.AddDate(0, 0, -index*f43WindowDays)
		start := end.AddDate(0, 0, -(f43WindowDays - 1))
		weekByIndex[index] = len(weeks)
		weeks = append(weeks, F43TrendWeek{
			Start: start.Format("2006-01-02"), End: end.Format("2006-01-02"),
			Spend: formatWasteMoney(bucket.spend), OptionalSpend: formatWasteMoney(bucket.optional),
			OptionalShare: formatWasteFixed(share, 3), ItemCount: bucket.count,
		})
	}
	for _, recalculation := range request.Recalculations {
		recalcLocal := recalculation.ChangedAt.In(location)
		year, month, day := recalcLocal.Date()
		date := time.Date(year, month, day, 0, 0, 0, 0, time.UTC)
		ageDays := int(nowDate.Sub(date) / (24 * time.Hour))
		if ageDays < 0 || ageDays >= f43TrendWindows*f43WindowDays {
			continue
		}
		if weekIndex, found := weekByIndex[ageDays/f43WindowDays]; found {
			weeks[weekIndex].Recalculated = true
		}
	}
	if len(weeks) < 2 {
		report.Trend.Weeks = weeks
		return
	}
	firstShare, _ := new(big.Rat).SetString(weeks[0].OptionalShare)
	lastShare, _ := new(big.Rat).SetString(weeks[len(weeks)-1].OptionalShare)
	delta := new(big.Rat).Sub(lastShare, firstShare)
	report.Trend.Available = true
	report.Trend.ReasonCode = "available"
	report.Trend.Weeks = weeks
	report.Trend.Delta = stringPointer(formatWasteFixed(delta, 3))
	noise := big.NewRat(5, 100)
	switch {
	case new(big.Rat).Abs(delta).Cmp(noise) < 0:
		report.Trend.Direction = "steady"
	case delta.Sign() < 0:
		report.Trend.Direction = "down"
	default:
		report.Trend.Direction = "up"
	}
	report.Trend.Weeks = weeks
}

func f43BuildEffects(report *F43Report, request F43Request, items []f43ParsedItem, location *time.Location) {
	type adviceCut struct {
		item F43Item
		at   time.Time
	}
	cuts := make(map[string]adviceCut)
	groups := make(map[string][]f43ParsedItem)
	for _, parsed := range items {
		item := parsed.item
		if item.ProductKey == "" {
			continue
		}
		groups[item.ProductKey] = append(groups[item.ProductKey], parsed)
		if !item.AdviceGiven || (item.Verdict != "harmful" && item.Verdict != "unnecessary") {
			continue
		}
		cut, exists := cuts[item.ProductKey]
		if !exists || item.PurchasedAt.Before(cut.at) {
			cuts[item.ProductKey] = adviceCut{item: item, at: item.PurchasedAt}
		}
	}
	for key, cut := range cuts {
		purchases := groups[key]
		before := make([]f43ParsedItem, 0)
		after := make([]f43ParsedItem, 0)
		var firstBefore time.Time
		latestName := cut.item.Name
		for _, purchase := range purchases {
			if purchase.item.PurchasedAt.Before(cut.at) {
				before = append(before, purchase)
				if firstBefore.IsZero() || purchase.item.PurchasedAt.Before(firstBefore) {
					firstBefore = purchase.item.PurchasedAt
				}
			} else if purchase.item.PurchasedAt.After(cut.at) {
				after = append(after, purchase)
			}
			if purchase.item.PurchasedAt.After(cut.at) {
				latestName = purchase.item.Name
			}
		}
		if len(before) < f43MinBeforeBuys {
			continue
		}
		daysAfter := f43CalendarDays(cut.at, request.AsOf, location)
		if daysAfter < f43MinEffectDays {
			report.Effects.Pending = append(report.Effects.Pending, F43PendingEffect{
				ProductKey: key, Name: latestName, DaysAfter: daysAfter, DaysLeft: f43MinEffectDays - daysAfter,
			})
			continue
		}
		daysBefore := f43CalendarDays(firstBefore, cut.at, location)
		if daysBefore < 1 {
			daysBefore = 1
		}
		beforeRate := big.NewRat(int64(len(before)), int64(daysBefore))
		afterRate := big.NewRat(int64(len(after)), int64(max(daysAfter, 1)))
		change := new(big.Rat).Quo(new(big.Rat).Sub(beforeRate, afterRate), beforeRate)
		change = f43RoundedRat(change, 2)
		direction := "same_frequency"
		if change.Cmp(big.NewRat(1, 5)) > 0 {
			direction = "less_often"
		} else if change.Cmp(big.NewRat(-1, 5)) < 0 {
			direction = "more_often"
		}
		intervalBefore := formatWasteFixed(big.NewRat(int64(daysBefore), int64(len(before))), 1)
		var intervalAfter *string
		if len(after) > 0 {
			intervalAfter = stringPointer(formatWasteFixed(big.NewRat(int64(max(daysAfter, 1)), int64(len(after))), 1))
		}
		afterSpend := new(big.Rat)
		completeAfterSpend := true
		for _, purchase := range after {
			if purchase.amount == nil {
				completeAfterSpend = false
				continue
			}
			afterSpend.Add(afterSpend, purchase.amount)
		}
		var afterSpendValue *string
		if completeAfterSpend {
			afterSpendValue = stringPointer(formatWasteMoney(afterSpend))
		}
		report.Effects.Effects = append(report.Effects.Effects, F43Effect{
			ProductKey: key, Name: latestName, Advice: cut.item.Advice, BeforeCount: len(before), AfterCount: len(after),
			DaysBefore: daysBefore, DaysAfter: daysAfter, IntervalBefore: intervalBefore, IntervalAfter: intervalAfter,
			Change: formatWasteFixed(change, 2), Direction: direction, AfterSpend: afterSpendValue,
		})
	}
	sort.Slice(report.Effects.Effects, func(i, j int) bool {
		left, _ := new(big.Rat).SetString(report.Effects.Effects[i].Change)
		right, _ := new(big.Rat).SetString(report.Effects.Effects[j].Change)
		if comparison := left.Cmp(right); comparison != 0 {
			return comparison > 0
		}
		return report.Effects.Effects[i].ProductKey < report.Effects.Effects[j].ProductKey
	})
	sort.Slice(report.Effects.Pending, func(i, j int) bool {
		if report.Effects.Pending[i].DaysLeft != report.Effects.Pending[j].DaysLeft {
			return report.Effects.Pending[i].DaysLeft < report.Effects.Pending[j].DaysLeft
		}
		return report.Effects.Pending[i].ProductKey < report.Effects.Pending[j].ProductKey
	})
	if len(report.Effects.Effects) > f43TopEffects {
		report.Effects.Effects = report.Effects.Effects[:f43TopEffects]
	}
	if len(report.Effects.Pending) > f43TopEffects {
		report.Effects.Pending = report.Effects.Pending[:f43TopEffects]
	}
}

func f43BuildRecalculation(report *F43Report, changes []F43Recalculation, location *time.Location) {
	if len(changes) == 0 {
		return
	}
	totalDelta := new(big.Rat)
	completeDelta := true
	type recalcWindow struct {
		result F43RecalculationWindow
		delta  *big.Rat
	}
	windowMap := make(map[string]*recalcWindow)
	for _, change := range changes {
		report.Recalculation.ChangedItemCount += change.ChangedItemCount
		var delta *big.Rat
		if change.OptionalSpendDelta == nil {
			completeDelta = false
		} else {
			delta, _ = new(big.Rat).SetString(*change.OptionalSpendDelta)
			totalDelta.Add(totalDelta, delta)
		}
		local := change.ChangedAt.In(location)
		year, month, day := local.Date()
		localDate := time.Date(year, month, day, 0, 0, 0, 0, time.UTC)
		weekdayOffset := (int(local.Weekday()) + 6) % 7
		startDate := localDate.AddDate(0, 0, -weekdayOffset)
		endDate := startDate.AddDate(0, 0, f43WindowDays-1)
		key := startDate.Format("2006-01-02")
		window := windowMap[key]
		if window == nil {
			window = &recalcWindow{result: F43RecalculationWindow{Start: key, End: endDate.Format("2006-01-02")}, delta: new(big.Rat)}
			windowMap[key] = window
		}
		window.result.ChangedItemCount += change.ChangedItemCount
		if delta != nil {
			window.delta.Add(window.delta, delta)
		}
	}
	if completeDelta {
		report.Recalculation.Available = true
		report.Recalculation.OptionalSpendDelta = stringPointer(formatWasteMoney(totalDelta))
	}
	for _, window := range windowMap {
		if completeDelta {
			window.result.OptionalSpendDelta = stringPointer(formatWasteMoney(window.delta))
		}
		report.Recalculation.Windows = append(report.Recalculation.Windows, window.result)
	}
	sort.Slice(report.Recalculation.Windows, func(i, j int) bool {
		return report.Recalculation.Windows[i].Start < report.Recalculation.Windows[j].Start
	})
}

func f43RatioPercent(numerator *big.Rat, denominatorText string) *string {
	denominator, ok := new(big.Rat).SetString(denominatorText)
	if !ok || denominator.Sign() <= 0 {
		return nil
	}
	percent := new(big.Rat).Mul(new(big.Rat).Quo(numerator, denominator), big.NewRat(100, 1))
	return stringPointer(formatWasteFixed(percent, 1))
}

func f43RoundedRat(value *big.Rat, scale int) *big.Rat {
	rounded, _ := new(big.Rat).SetString(formatWasteFixed(value, scale))
	return rounded
}

func f43CalendarDays(from, to time.Time, location *time.Location) int {
	fromLocal, toLocal := from.In(location), to.In(location)
	fy, fm, fd := fromLocal.Date()
	ty, tm, td := toLocal.Date()
	fromDate := time.Date(fy, fm, fd, 0, 0, 0, 0, time.UTC)
	toDate := time.Date(ty, tm, td, 0, 0, 0, 0, time.UTC)
	return int(toDate.Sub(fromDate) / (24 * time.Hour))
}
