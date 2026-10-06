package recurring

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	AlgorithmVersion   = "recurring.v1"
	minimumOccurrences = 3
	amountTolerance    = 0.25
	intervalTolerance  = 0.25
	intervalShare      = 0.60
	upcomingDays       = 3
)

var tokenSplit = regexp.MustCompile(`[a-zа-я0-9]+`)

var stopWords = map[string]struct{}{
	"пятерочка": {}, "смaк": {}, "тема": {}, "папа": {}, "мож": {}, "катти": {}, "pur": {},
	"felix": {}, "корм": {}, "пакет": {}, "майка": {}, "покупка": {}, "товар": {}, "шт": {},
	"руб": {}, "р": {},
}

// Transaction is the normalized full-state row consumed by the recurring policy.
type Transaction struct {
	TenantID, OwnerID, ID, EventID                            string
	Type, Amount, Currency, CategoryCode, Description, Status string
	AggregateVersion                                          uint64
	OccurredAt, RecordedAt                                    time.Time
}

// Series contains a detected recurring expense or income and the evidence used to find it.
type Series struct {
	ID              string `json:"id"`
	Key             string `json:"key"`
	Name            string `json:"name"`
	Category        string `json:"category,omitempty"`
	Type            string `json:"type"`
	Currency        string `json:"currency"`
	Amount          string `json:"amount"`
	MinAmount       string `json:"min_amount"`
	MaxAmount       string `json:"max_amount"`
	PeriodCode      string `json:"period_code"`
	PeriodDays      int    `json:"period_days"`
	MinIntervalDays int    `json:"min_interval_days"`
	MaxIntervalDays int    `json:"max_interval_days"`
	Occurrences     int    `json:"occurrences"`
	LastDate        string `json:"last_date"`
	NextDate        string `json:"next_date"`
	DaysUntil       int    `json:"days_until"`
}

// Projection carries its date and algorithm identity alongside the computed values.
type Projection struct {
	AlgorithmVersion        string            `json:"algorithm_version"`
	Completeness            string            `json:"completeness"`
	TimeZone                string            `json:"time_zone"`
	AsOf                    time.Time         `json:"as_of"`
	ExpenseSeries           []Series          `json:"expense_series"`
	IncomeSeries            []Series          `json:"income_series"`
	DueSoon                 []Series          `json:"due_soon"`
	Overdue                 []Series          `json:"overdue"`
	NextIncome              *Series           `json:"next_income"`
	MonthlyExpenseEstimate  *string           `json:"monthly_expense_estimate"`
	MonthlyExpenseEstimates map[string]string `json:"monthly_expense_estimates"`
}

type occurrence struct {
	transaction Transaction
	amount      float64
	localDate   time.Time
}

// BuildProjection implements the conservative v1 recurrence rules. Rows are full-state
// snapshots: the highest aggregate version for each transaction wins before grouping.
func BuildProjection(tenantID, ownerID string, transactions []Transaction, asOf time.Time, location *time.Location) Projection {
	if location == nil {
		location = time.UTC
	}
	localAsOf := dateOnly(asOf.In(location), location)
	projection := Projection{
		AlgorithmVersion:        AlgorithmVersion,
		Completeness:            "complete",
		TimeZone:                location.String(),
		AsOf:                    localAsOf,
		ExpenseSeries:           []Series{},
		IncomeSeries:            []Series{},
		DueSoon:                 []Series{},
		Overdue:                 []Series{},
		MonthlyExpenseEstimates: map[string]string{},
	}

	latest := make(map[string]Transaction)
	for _, tx := range transactions {
		if tx.TenantID != tenantID || tx.OwnerID != ownerID || tx.ID == "" || tx.AggregateVersion == 0 {
			continue
		}
		prior, exists := latest[tx.ID]
		if !exists || tx.AggregateVersion > prior.AggregateVersion ||
			(tx.AggregateVersion == prior.AggregateVersion && tx.RecordedAt.After(prior.RecordedAt)) {
			latest[tx.ID] = tx
		}
	}

	groups := make(map[string][]occurrence)
	for _, tx := range latest {
		if tx.Status != "posted" || tx.OccurredAt.IsZero() || tx.OccurredAt.After(asOf) ||
			(tx.Type != "expense" && tx.Type != "income") {
			continue
		}
		amount, ok := parseAmount(tx.Amount)
		if !ok || amount <= 0 {
			continue
		}
		tx.Currency = strings.ToUpper(strings.TrimSpace(tx.Currency))
		if tx.Currency == "" {
			tx.Currency = "RUB"
		}
		key := seriesKey(tx.Description, tx.CategoryCode)
		if key == "" {
			continue
		}
		groupKey := strings.Join([]string{tx.Type, tx.Currency, key}, "\x00")
		groups[groupKey] = append(groups[groupKey], occurrence{
			transaction: tx,
			amount:      amount,
			localDate:   dateOnly(tx.OccurredAt.In(location), location),
		})
	}

	monthlyByCurrency := make(map[string]float64)
	for groupKey, rows := range groups {
		if len(rows) < minimumOccurrences {
			continue
		}
		sort.Slice(rows, func(i, j int) bool {
			if rows[i].localDate.Equal(rows[j].localDate) {
				return rows[i].transaction.ID < rows[j].transaction.ID
			}
			return rows[i].localDate.Before(rows[j].localDate)
		})
		sum, minimum, maximum := 0.0, math.MaxFloat64, 0.0
		for _, row := range rows {
			sum += row.amount
			minimum = math.Min(minimum, row.amount)
			maximum = math.Max(maximum, row.amount)
		}
		average := sum / float64(len(rows))
		if average <= 0 || maximum-minimum > average*amountTolerance {
			continue
		}
		deltas := make([]int, 0, len(rows)-1)
		for i := 1; i < len(rows); i++ {
			days := calendarDaysBetween(rows[i-1].localDate, rows[i].localDate)
			if days > 0 {
				deltas = append(deltas, days)
			}
		}
		if len(deltas) < minimumOccurrences-1 {
			continue
		}
		sortedDeltas := append([]int(nil), deltas...)
		sort.Ints(sortedDeltas)
		median := sortedDeltas[len(sortedDeltas)/2] // Matches the legacy upper median.
		periodCode := periodCode(median)
		if periodCode == "" || !regular(deltas, median) {
			continue
		}
		last := rows[len(rows)-1]
		nextDate := last.localDate.AddDate(0, 0, median)
		daysUntil := calendarDaysBetween(localAsOf, nextDate)
		parts := strings.Split(groupKey, "\x00")
		name := strings.TrimSpace(last.transaction.Description)
		if name == "" {
			name = strings.TrimSpace(last.transaction.CategoryCode)
		}
		if name == "" {
			name = "Платёж"
		}
		key := parts[2]
		identity := strings.Join([]string{tenantID, ownerID, parts[0], parts[1], key}, "\x00")
		idHash := sha256.Sum256([]byte(identity))
		intervalMin, intervalMax := minInt(deltas), maxInt(deltas)
		series := Series{
			ID:              hex.EncodeToString(idHash[:16]),
			Key:             key,
			Name:            name,
			Category:        last.transaction.CategoryCode,
			Type:            parts[0],
			Currency:        parts[1],
			Amount:          money(average),
			MinAmount:       money(minimum),
			MaxAmount:       money(maximum),
			PeriodCode:      periodCode,
			PeriodDays:      median,
			MinIntervalDays: intervalMin,
			MaxIntervalDays: intervalMax,
			Occurrences:     len(rows),
			LastDate:        last.localDate.Format(time.DateOnly),
			NextDate:        nextDate.Format(time.DateOnly),
			DaysUntil:       daysUntil,
		}
		if series.Type == "expense" {
			projection.ExpenseSeries = append(projection.ExpenseSeries, series)
			if daysUntil >= 0 && daysUntil <= upcomingDays {
				projection.DueSoon = append(projection.DueSoon, series)
			}
			if daysUntil < 0 {
				projection.Overdue = append(projection.Overdue, series)
			}
			monthly := average
			if median < 25 || median > 35 {
				monthly = average * 30 / float64(maxInt([]int{median, 1}))
			}
			monthlyByCurrency[series.Currency] += monthly
		} else {
			projection.IncomeSeries = append(projection.IncomeSeries, series)
			if daysUntil >= 0 && (projection.NextIncome == nil || daysUntil < projection.NextIncome.DaysUntil) {
				copy := series
				projection.NextIncome = &copy
			}
		}
	}

	for currency, total := range monthlyByCurrency {
		projection.MonthlyExpenseEstimates[currency] = money(total)
	}
	if len(projection.MonthlyExpenseEstimates) == 1 {
		for _, total := range projection.MonthlyExpenseEstimates {
			projection.MonthlyExpenseEstimate = &total
		}
	}
	sortSeries(projection.ExpenseSeries)
	sortSeries(projection.IncomeSeries)
	sortSeries(projection.DueSoon)
	sort.Slice(projection.Overdue, func(i, j int) bool {
		if projection.Overdue[i].DaysUntil != projection.Overdue[j].DaysUntil {
			return projection.Overdue[i].DaysUntil > projection.Overdue[j].DaysUntil
		}
		return projection.Overdue[i].Amount > projection.Overdue[j].Amount
	})
	return projection
}

func seriesKey(description, category string) string {
	text := strings.NewReplacer("ё", "е", "Ё", "е").Replace(strings.ToLower(description + " " + category))
	seen := make(map[string]struct{})
	words := make([]string, 0)
	for _, word := range tokenSplit.FindAllString(text, -1) {
		if len([]rune(word)) < 3 || allDigits(word) {
			continue
		}
		if _, stop := stopWords[word]; stop {
			continue
		}
		if _, exists := seen[word]; exists {
			continue
		}
		seen[word] = struct{}{}
		words = append(words, word)
	}
	sort.Strings(words)
	return strings.Join(words, " ")
}

func periodCode(days int) string {
	switch {
	case days >= 6 && days <= 8:
		return "week"
	case days >= 25 && days <= 35:
		return "month"
	default:
		return ""
	}
}

func regular(deltas []int, median int) bool {
	if median <= 0 {
		return false
	}
	even := 0
	for _, delta := range deltas {
		if math.Abs(float64(delta-median)) <= float64(median)*intervalTolerance {
			even++
		}
	}
	return float64(even) >= float64(len(deltas))*intervalShare
}

func dateOnly(value time.Time, location *time.Location) time.Time {
	local := value.In(location)
	year, month, day := local.Date()
	return time.Date(year, month, day, 0, 0, 0, 0, location)
}

func calendarDaysBetween(from, to time.Time) int {
	fromYear, fromMonth, fromDay := from.Date()
	toYear, toMonth, toDay := to.Date()
	fromUTC := time.Date(fromYear, fromMonth, fromDay, 0, 0, 0, 0, time.UTC)
	toUTC := time.Date(toYear, toMonth, toDay, 0, 0, 0, 0, time.UTC)
	return int(toUTC.Sub(fromUTC).Hours() / 24)
}

func parseAmount(value string) (float64, bool) {
	parsed, err := strconv.ParseFloat(strings.TrimSpace(value), 64)
	if err != nil || math.IsNaN(parsed) || math.IsInf(parsed, 0) {
		return 0, false
	}
	return parsed, true
}

func money(value float64) string       { return fmt.Sprintf("%.2f", math.Round(value*100)/100) }
func moneyValue(value float64) float64 { return math.Round(value*100) / 100 }

func allDigits(value string) bool {
	for _, r := range value {
		if r < '0' || r > '9' {
			return false
		}
	}
	return value != ""
}

func minInt(values []int) int {
	result := values[0]
	for _, value := range values[1:] {
		if value < result {
			result = value
		}
	}
	return result
}

func maxInt(values []int) int {
	result := values[0]
	for _, value := range values[1:] {
		if value > result {
			result = value
		}
	}
	return result
}

func sortSeries(series []Series) {
	sort.Slice(series, func(i, j int) bool {
		left, right := series[i], series[j]
		if (left.DaysUntil < 0) != (right.DaysUntil < 0) {
			return left.DaysUntil >= 0
		}
		if left.DaysUntil != right.DaysUntil {
			return left.DaysUntil < right.DaysUntil
		}
		if left.Amount != right.Amount {
			return left.Amount > right.Amount
		}
		return left.Key < right.Key
	})
}
