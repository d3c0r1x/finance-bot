package advice

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/d3c0r1x/finance-bot/services/analytics-go/prices"
)

const (
	WasteAlgorithmVersion = "advice-waste.v1"
	maxWasteItems         = 50000
	wasteTopItems         = 5
	wasteTopRepeats       = 3
)

var (
	wasteMoneyPattern = regexp.MustCompile(`^(?:0|[1-9][0-9]{0,17})\.[0-9]{2}$`)
	wasteUUIDPattern  = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	wasteKeyPattern   = regexp.MustCompile(`^[a-zа-я0-9]{0,256}$`)
)

type WasteRequest struct {
	FromDate string      `json:"fromDate"`
	ToDate   string      `json:"toDate"`
	AsOf     time.Time   `json:"asOf"`
	TimeZone string      `json:"timeZone"`
	Items    []WasteLine `json:"items"`
}

// WasteLine is a receipt fact already scoped and overlaid by Core.
type WasteLine struct {
	ItemID          string    `json:"itemId"`
	ProductKey      string    `json:"productKey"`
	Name            string    `json:"name"`
	LineSum         *string   `json:"lineSum"`
	Verdict         string    `json:"verdict"`
	VerdictSource   string    `json:"verdictSource"`
	PurchasedAt     time.Time `json:"purchasedAt"`
	Allowed         bool      `json:"allowed"`
	ItemVersion     int64     `json:"itemVersion"`
	DecisionVersion int64     `json:"decisionVersion"`
}

func (request *WasteRequest) UnmarshalJSON(data []byte) error {
	type requestAlias WasteRequest
	var decoded requestAlias
	if err := unmarshalRequiredWasteObject(data,
		[]string{"fromDate", "toDate", "asOf", "timeZone", "items"}, nil, &decoded); err != nil {
		return err
	}
	*request = WasteRequest(decoded)
	return nil
}

func (line *WasteLine) UnmarshalJSON(data []byte) error {
	type lineAlias WasteLine
	var decoded lineAlias
	if err := unmarshalRequiredWasteObject(data,
		[]string{"itemId", "productKey", "name", "lineSum", "verdict", "verdictSource", "purchasedAt", "allowed", "itemVersion", "decisionVersion"},
		map[string]bool{"lineSum": true, "verdict": true}, &decoded); err != nil {
		return err
	}
	*line = WasteLine(decoded)
	return nil
}

func unmarshalRequiredWasteObject(data []byte, required []string, nullable map[string]bool, target any) error {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(data, &fields); err != nil {
		return err
	}
	if fields == nil {
		return errors.New("expected JSON object")
	}
	for _, key := range required {
		raw, exists := fields[key]
		if !exists {
			return fmt.Errorf("missing required field %q", key)
		}
		if !nullable[key] && bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
			return fmt.Errorf("required field %q cannot be null", key)
		}
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	return decoder.Decode(target)
}

type WasteReport struct {
	Available          bool                `json:"available"`
	ReasonCode         string              `json:"reasonCode"`
	AlgorithmVersion   string              `json:"algorithmVersion"`
	Completeness       string              `json:"completeness"`
	AsOf               time.Time           `json:"asOf"`
	InputVersion       string              `json:"inputVersion"`
	FromDate           string              `json:"fromDate"`
	ToDate             string              `json:"toDate"`
	ReviewedSpend      *string             `json:"reviewedSpend"`
	OptionalSpend      *string             `json:"optionalSpend"`
	OptionalShare      *string             `json:"optionalShare"`
	ReviewedItemCount  int                 `json:"reviewedItemCount"`
	OptionalItemCount  int                 `json:"optionalItemCount"`
	MissingAmountCount int                 `json:"missingAmountCount"`
	ByVerdict          []WasteVerdictTotal `json:"byVerdict"`
	BySource           map[string]string   `json:"bySource"`
	TopItems           []WasteItemSummary  `json:"topItems"`
	Repeats            []WasteProductGroup `json:"repeats"`
	Corrected          []WasteProductGroup `json:"corrected"`
	OptionalByDay      map[string]string   `json:"optionalByDay"`
}

type WasteVerdictTotal struct {
	Verdict string `json:"verdict"`
	Amount  string `json:"amount"`
	Count   int    `json:"count"`
}

type WasteItemSummary struct {
	ItemID  string `json:"itemId"`
	Name    string `json:"name"`
	Amount  string `json:"amount"`
	Verdict string `json:"verdict"`
	Source  string `json:"source"`
}

type WasteProductGroup struct {
	ProductKey  string `json:"productKey"`
	ProductName string `json:"productName"`
	Count       int    `json:"count"`
	Amount      string `json:"amount"`
}

type parsedWasteLine struct {
	line   WasteLine
	amount *big.Rat
}

type wasteGroup struct {
	key      string
	name     string
	count    int
	amount   *big.Rat
	latestAt time.Time
}

func BuildWasteReport(request WasteRequest) (WasteReport, error) {
	location, from, to, err := validateWasteRequest(request)
	if err != nil {
		return WasteReport{}, err
	}
	result := WasteReport{
		ReasonCode: "no_reviewed_items", AlgorithmVersion: WasteAlgorithmVersion, Completeness: "complete",
		AsOf: request.AsOf.UTC(), InputVersion: wasteInputVersion(request), FromDate: request.FromDate, ToDate: request.ToDate,
		ByVerdict: []WasteVerdictTotal{}, BySource: map[string]string{}, TopItems: []WasteItemSummary{},
		Repeats: []WasteProductGroup{}, Corrected: []WasteProductGroup{}, OptionalByDay: map[string]string{},
	}

	reviewed := make([]parsedWasteLine, 0, len(request.Items))
	for _, line := range request.Items {
		if line.Verdict == "" {
			continue
		}
		result.ReviewedItemCount++
		if line.LineSum == nil {
			result.MissingAmountCount++
			continue
		}
		amount, ok := new(big.Rat).SetString(*line.LineSum)
		if !ok {
			return WasteReport{}, errors.New("invalid receipt line amount")
		}
		reviewed = append(reviewed, parsedWasteLine{line: line, amount: amount})
	}
	if result.MissingAmountCount > 0 {
		result.ReasonCode = "missing_amounts"
		result.Completeness = "partial"
		return result, nil
	}
	if result.ReviewedItemCount == 0 {
		return result, nil
	}

	result.Available = true
	result.ReasonCode = "available"
	resultedTotal := new(big.Rat)
	optionalTotal := new(big.Rat)
	bySource := map[string]*big.Rat{}
	byVerdictAmount := map[string]*big.Rat{}
	byVerdictCount := map[string]int{}
	byDay := make(map[string]*big.Rat)
	for day := from; !day.After(to); day = day.AddDate(0, 0, 1) {
		byDay[day.Format("2006-01-02")] = new(big.Rat)
	}
	wasteLines := make([]parsedWasteLine, 0)
	correctedGroups := map[string]*wasteGroup{}
	for _, parsed := range reviewed {
		line := parsed.line
		resultedTotal.Add(resultedTotal, parsed.amount)
		if line.Verdict != "harmful" && line.Verdict != "unnecessary" {
			continue
		}
		if line.Allowed {
			if group := correctedGroups[line.ProductKey]; group != nil {
				group.count++
				group.amount.Add(group.amount, parsed.amount)
				if line.PurchasedAt.After(group.latestAt) {
					group.name, group.latestAt = line.Name, line.PurchasedAt
				}
			} else {
				correctedGroups[line.ProductKey] = &wasteGroup{
					key: line.ProductKey, name: line.Name, count: 1, amount: new(big.Rat).Set(parsed.amount), latestAt: line.PurchasedAt,
				}
			}
			continue
		}
		result.OptionalItemCount++
		optionalTotal.Add(optionalTotal, parsed.amount)
		wasteLines = append(wasteLines, parsed)
		day := line.PurchasedAt.In(location).Format("2006-01-02")
		byDay[day].Add(byDay[day], parsed.amount)
		source := normalizedWasteSource(line.VerdictSource)
		if bySource[source] == nil {
			bySource[source] = new(big.Rat)
		}
		bySource[source].Add(bySource[source], parsed.amount)
		if byVerdictAmount[line.Verdict] == nil {
			byVerdictAmount[line.Verdict] = new(big.Rat)
		}
		byVerdictAmount[line.Verdict].Add(byVerdictAmount[line.Verdict], parsed.amount)
		byVerdictCount[line.Verdict]++
	}

	result.ReviewedSpend = stringPointer(formatWasteMoney(resultedTotal))
	result.OptionalSpend = stringPointer(formatWasteMoney(optionalTotal))
	share := new(big.Rat)
	if resultedTotal.Sign() > 0 {
		share.Quo(new(big.Rat).Set(optionalTotal), resultedTotal)
	}
	result.OptionalShare = stringPointer(formatWasteFixed(share, 3))
	for source, amount := range bySource {
		result.BySource[source] = formatWasteMoney(amount)
	}
	for _, verdict := range []string{"harmful", "unnecessary"} {
		if byVerdictCount[verdict] > 0 {
			result.ByVerdict = append(result.ByVerdict, WasteVerdictTotal{
				Verdict: verdict, Amount: formatWasteMoney(byVerdictAmount[verdict]), Count: byVerdictCount[verdict],
			})
		}
	}
	for day, amount := range byDay {
		result.OptionalByDay[day] = formatWasteMoney(amount)
	}
	result.TopItems = summarizeWasteItems(wasteLines)
	result.Repeats = summarizeWasteRepeats(wasteLines)
	result.Corrected = summarizeCorrected(correctedGroups)
	return result, nil
}

func validateWasteRequest(request WasteRequest) (*time.Location, time.Time, time.Time, error) {
	if request.AsOf.IsZero() || strings.TrimSpace(request.TimeZone) == "" || len(request.TimeZone) > 64 {
		return nil, time.Time{}, time.Time{}, errors.New("waste report requires asOf and time zone")
	}
	location, err := time.LoadLocation(request.TimeZone)
	if err != nil {
		return nil, time.Time{}, time.Time{}, errors.New("waste report time zone is invalid")
	}
	from, err := time.Parse("2006-01-02", request.FromDate)
	if err != nil || from.Format("2006-01-02") != request.FromDate {
		return nil, time.Time{}, time.Time{}, errors.New("waste report start date is invalid")
	}
	to, err := time.Parse("2006-01-02", request.ToDate)
	if err != nil || to.Format("2006-01-02") != request.ToDate || from.After(to) || to.Sub(from)/(24*time.Hour) >= 366 {
		return nil, time.Time{}, time.Time{}, errors.New("waste report date range is invalid")
	}
	if len(request.Items) > maxWasteItems {
		return nil, time.Time{}, time.Time{}, errors.New("waste report has too many receipt items")
	}
	fromLocal := time.Date(from.Year(), from.Month(), from.Day(), 0, 0, 0, 0, location)
	toLocal := time.Date(to.Year(), to.Month(), to.Day(), 0, 0, 0, 0, location).AddDate(0, 0, 1)
	seen := make(map[string]struct{}, len(request.Items))
	for _, line := range request.Items {
		if !wasteUUIDPattern.MatchString(line.ItemID) || strings.TrimSpace(line.Name) == "" || utf8.RuneCountInString(line.Name) > 200 ||
			utf8.RuneCountInString(line.ProductKey) > 256 || !wasteKeyPattern.MatchString(line.ProductKey) || line.ItemVersion < 1 || line.DecisionVersion < 0 {
			return nil, time.Time{}, time.Time{}, errors.New("waste report contains an invalid receipt item")
		}
		if _, exists := seen[strings.ToLower(line.ItemID)]; exists {
			return nil, time.Time{}, time.Time{}, errors.New("waste report contains duplicate item IDs")
		}
		seen[strings.ToLower(line.ItemID)] = struct{}{}
		if line.Verdict != "" && line.Verdict != "useful" && line.Verdict != "neutral" &&
			line.Verdict != "harmful" && line.Verdict != "unnecessary" {
			return nil, time.Time{}, time.Time{}, errors.New("waste report contains an unsupported verdict")
		}
		if utf8.RuneCountInString(line.VerdictSource) > 32 {
			return nil, time.Time{}, time.Time{}, errors.New("waste report source is too long")
		}
		if line.PurchasedAt.IsZero() || line.PurchasedAt.After(request.AsOf) ||
			line.PurchasedAt.Before(fromLocal) || !line.PurchasedAt.Before(toLocal) ||
			line.PurchasedAt.In(location).Format("2006-01-02") < request.FromDate ||
			line.PurchasedAt.In(location).Format("2006-01-02") > request.ToDate {
			return nil, time.Time{}, time.Time{}, errors.New("waste report item is outside its date window")
		}
		if line.Allowed && line.ProductKey == "" {
			return nil, time.Time{}, time.Time{}, errors.New("allowed receipt item requires product key")
		}
		if line.LineSum != nil && !wasteMoneyPattern.MatchString(*line.LineSum) {
			return nil, time.Time{}, time.Time{}, errors.New("waste report amount is invalid")
		}
	}
	return location, from, to, nil
}

func wasteInputVersion(request WasteRequest) string {
	type versionedLine struct {
		ItemID          string  `json:"itemId"`
		ProductKey      string  `json:"productKey"`
		Name            string  `json:"name"`
		LineSum         *string `json:"lineSum"`
		Verdict         string  `json:"verdict"`
		VerdictSource   string  `json:"verdictSource"`
		PurchasedAt     string  `json:"purchasedAt"`
		Allowed         bool    `json:"allowed"`
		ItemVersion     int64   `json:"itemVersion"`
		DecisionVersion int64   `json:"decisionVersion"`
	}
	lines := make([]versionedLine, 0, len(request.Items))
	for _, line := range request.Items {
		var amount *string
		if line.LineSum != nil {
			value := *line.LineSum
			amount = &value
		}
		lines = append(lines, versionedLine{
			ItemID: strings.ToLower(line.ItemID), ProductKey: line.ProductKey, Name: line.Name,
			LineSum: amount, Verdict: line.Verdict, VerdictSource: normalizedWasteSource(line.VerdictSource),
			PurchasedAt: line.PurchasedAt.UTC().Format(time.RFC3339Nano), Allowed: line.Allowed,
			ItemVersion: line.ItemVersion, DecisionVersion: line.DecisionVersion,
		})
	}
	sort.Slice(lines, func(i, j int) bool { return lines[i].ItemID < lines[j].ItemID })
	canonical := struct {
		FromDate string          `json:"fromDate"`
		ToDate   string          `json:"toDate"`
		AsOf     string          `json:"asOf"`
		TimeZone string          `json:"timeZone"`
		Items    []versionedLine `json:"items"`
	}{request.FromDate, request.ToDate, request.AsOf.UTC().Format(time.RFC3339Nano), request.TimeZone, lines}
	body, _ := json.Marshal(canonical)
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}

func normalizedWasteSource(source string) string {
	switch source {
	case "rule", "model", "default", "human":
		return source
	default:
		return "unknown"
	}
}

func summarizeWasteItems(lines []parsedWasteLine) []WasteItemSummary {
	sort.Slice(lines, func(i, j int) bool {
		if comparison := lines[i].amount.Cmp(lines[j].amount); comparison != 0 {
			return comparison > 0
		}
		if lines[i].line.Name != lines[j].line.Name {
			return lines[i].line.Name < lines[j].line.Name
		}
		return lines[i].line.ItemID < lines[j].line.ItemID
	})
	if len(lines) > wasteTopItems {
		lines = lines[:wasteTopItems]
	}
	items := make([]WasteItemSummary, 0, len(lines))
	for _, line := range lines {
		items = append(items, WasteItemSummary{
			ItemID: line.line.ItemID, Name: line.line.Name, Amount: formatWasteMoney(line.amount),
			Verdict: line.line.Verdict, Source: normalizedWasteSource(line.line.VerdictSource),
		})
	}
	return items
}

func summarizeWasteRepeats(lines []parsedWasteLine) []WasteProductGroup {
	ordered := append([]parsedWasteLine(nil), lines...)
	sort.Slice(ordered, func(i, j int) bool {
		if !ordered[i].line.PurchasedAt.Equal(ordered[j].line.PurchasedAt) {
			return ordered[i].line.PurchasedAt.After(ordered[j].line.PurchasedAt)
		}
		return ordered[i].line.ItemID < ordered[j].line.ItemID
	})
	groups := make([]*wasteGroup, 0)
	for _, line := range ordered {
		matched := -1
		for index, group := range groups {
			if prices.SameProduct(group.name, line.line.Name) {
				matched = index
				break
			}
		}
		if matched < 0 {
			groups = append(groups, &wasteGroup{
				key: line.line.ProductKey, name: line.line.Name, count: 1,
				amount: new(big.Rat).Set(line.amount), latestAt: line.line.PurchasedAt,
			})
			continue
		}
		groups[matched].count++
		groups[matched].amount.Add(groups[matched].amount, line.amount)
	}
	result := make([]WasteProductGroup, 0, len(groups))
	for _, group := range groups {
		if group.count > 1 {
			result = append(result, WasteProductGroup{
				ProductKey: group.key, ProductName: group.name, Count: group.count, Amount: formatWasteMoney(group.amount),
			})
		}
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].Count != result[j].Count {
			return result[i].Count > result[j].Count
		}
		left, _ := new(big.Rat).SetString(result[i].Amount)
		right, _ := new(big.Rat).SetString(result[j].Amount)
		if comparison := left.Cmp(right); comparison != 0 {
			return comparison > 0
		}
		return result[i].ProductName < result[j].ProductName
	})
	if len(result) > wasteTopRepeats {
		result = result[:wasteTopRepeats]
	}
	return result
}

func summarizeCorrected(groups map[string]*wasteGroup) []WasteProductGroup {
	result := make([]WasteProductGroup, 0, len(groups))
	for _, group := range groups {
		result = append(result, WasteProductGroup{
			ProductKey: group.key, ProductName: group.name, Count: group.count, Amount: formatWasteMoney(group.amount),
		})
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].Count != result[j].Count {
			return result[i].Count > result[j].Count
		}
		left, _ := new(big.Rat).SetString(result[i].Amount)
		right, _ := new(big.Rat).SetString(result[j].Amount)
		if comparison := left.Cmp(right); comparison != 0 {
			return comparison > 0
		}
		return result[i].ProductKey < result[j].ProductKey
	})
	if len(result) > wasteTopItems {
		result = result[:wasteTopItems]
	}
	return result
}

func formatWasteMoney(amount *big.Rat) string { return formatWasteFixed(amount, 2) }

func formatWasteFixed(amount *big.Rat, scale int) string {
	if amount == nil {
		return strings.Repeat("0", 1) + "." + strings.Repeat("0", scale)
	}
	negative := amount.Sign() < 0
	abs := new(big.Rat).Abs(amount)
	factor := new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(scale)), nil)
	scaledNumerator := new(big.Int).Mul(abs.Num(), factor)
	quotient, remainder := new(big.Int), new(big.Int)
	quotient.QuoRem(scaledNumerator, abs.Denom(), remainder)
	doubleRemainder := new(big.Int).Lsh(new(big.Int).Set(remainder), 1)
	comparison := doubleRemainder.Cmp(abs.Denom())
	if comparison > 0 || comparison == 0 && quotient.Bit(0) == 1 {
		quotient.Add(quotient, big.NewInt(1))
	}
	whole, fraction := new(big.Int), new(big.Int)
	whole.QuoRem(quotient, factor, fraction)
	decimal := fraction.String()
	decimal = strings.Repeat("0", scale-len(decimal)) + decimal
	if negative && quotient.Sign() != 0 {
		return fmt.Sprintf("-%s.%s", whole.String(), decimal)
	}
	return fmt.Sprintf("%s.%s", whole.String(), decimal)
}

func stringPointer(value string) *string { return &value }
