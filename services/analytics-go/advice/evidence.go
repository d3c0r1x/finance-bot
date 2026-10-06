package advice

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math/big"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

const EvidenceAlgorithmVersion = "advice-evidence.v1"

// EvidenceRequest contains receipt facts selected and authorized by Core.
type EvidenceRequest struct {
	Items []EvidenceLine `json:"items"`
}

type EvidenceLine struct {
	ItemID        string    `json:"itemId"`
	ProductKey    string    `json:"productKey"`
	Name          string    `json:"name"`
	LineSum       *string   `json:"lineSum"`
	Verdict       string    `json:"verdict"`
	VerdictSource string    `json:"verdictSource"`
	Advice        string    `json:"advice"`
	PurchasedAt   time.Time `json:"purchasedAt"`
	ItemVersion   int64     `json:"itemVersion"`
}

func (request *EvidenceRequest) UnmarshalJSON(data []byte) error {
	type alias EvidenceRequest
	var decoded alias
	if err := unmarshalRequiredWasteObject(data, []string{"items"}, nil, &decoded); err != nil {
		return err
	}
	*request = EvidenceRequest(decoded)
	return nil
}

func (line *EvidenceLine) UnmarshalJSON(data []byte) error {
	type alias EvidenceLine
	var decoded alias
	if err := unmarshalRequiredWasteObject(data,
		[]string{"itemId", "productKey", "name", "lineSum", "verdict", "verdictSource", "advice", "purchasedAt", "itemVersion"},
		map[string]bool{"lineSum": true}, &decoded); err != nil {
		return err
	}
	*line = EvidenceLine(decoded)
	return nil
}

type EvidenceResponse struct {
	AlgorithmVersion string          `json:"algorithmVersion"`
	InputVersion     string          `json:"inputVersion"`
	Groups           []EvidenceGroup `json:"groups"`
}

// ModelOnly is evidence classification, never a user decision or a block.
type EvidenceGroup struct {
	ProductKey         string    `json:"productKey"`
	ProductName        string    `json:"productName"`
	Count              int       `json:"count"`
	Amount             *string   `json:"amount"`
	MissingAmountCount int       `json:"missingAmountCount"`
	RuleCount          int       `json:"ruleCount"`
	ModelCount         int       `json:"modelCount"`
	UnmarkedCount      int       `json:"unmarkedCount"`
	ModelOnly          bool      `json:"modelOnly"`
	LatestVerdict      string    `json:"latestVerdict"`
	LatestAdvice       string    `json:"latestAdvice"`
	LastPurchasedAt    time.Time `json:"lastPurchasedAt"`
}

type evidenceAccumulator struct {
	group        EvidenceGroup
	knownAmount  *big.Rat
	latestItemID string
}

func BuildEvidenceGroups(request EvidenceRequest) (EvidenceResponse, error) {
	if err := validateEvidenceRequest(request); err != nil {
		return EvidenceResponse{}, err
	}
	result := EvidenceResponse{
		AlgorithmVersion: EvidenceAlgorithmVersion,
		InputVersion:     evidenceInputVersion(request),
		Groups:           []EvidenceGroup{},
	}
	byKey := make(map[string]*evidenceAccumulator)
	for _, line := range request.Items {
		if line.ProductKey == "" || (line.Verdict != "harmful" && line.Verdict != "unnecessary") {
			continue
		}
		group := byKey[line.ProductKey]
		if group == nil {
			group = &evidenceAccumulator{group: EvidenceGroup{ProductKey: line.ProductKey}, knownAmount: new(big.Rat)}
			byKey[line.ProductKey] = group
		}
		group.group.Count++
		switch normalizedWasteSource(line.VerdictSource) {
		case "rule":
			group.group.RuleCount++
		case "model":
			group.group.ModelCount++
		default:
			group.group.UnmarkedCount++
		}
		if line.LineSum == nil {
			group.group.MissingAmountCount++
		} else {
			amount, _ := new(big.Rat).SetString(*line.LineSum)
			group.knownAmount.Add(group.knownAmount, amount)
		}
		if group.group.LastPurchasedAt.IsZero() || line.PurchasedAt.After(group.group.LastPurchasedAt) ||
			(line.PurchasedAt.Equal(group.group.LastPurchasedAt) && strings.ToLower(line.ItemID) < group.latestItemID) {
			group.group.ProductName = line.Name
			group.group.LatestVerdict = line.Verdict
			group.group.LatestAdvice = line.Advice
			group.group.LastPurchasedAt = line.PurchasedAt.UTC()
			group.latestItemID = strings.ToLower(line.ItemID)
		}
	}
	groups := make([]*evidenceAccumulator, 0, len(byKey))
	for _, group := range byKey {
		if group.group.Count < 2 {
			continue
		}
		group.group.ModelOnly = group.group.ModelCount == group.group.Count
		if group.group.MissingAmountCount == 0 {
			amount := formatWasteMoney(group.knownAmount)
			group.group.Amount = &amount
		}
		groups = append(groups, group)
	}
	sort.Slice(groups, func(i, j int) bool {
		if groups[i].group.Count != groups[j].group.Count {
			return groups[i].group.Count > groups[j].group.Count
		}
		if groups[i].group.Amount == nil || groups[j].group.Amount == nil {
			if groups[i].group.Amount != nil || groups[j].group.Amount != nil {
				return groups[i].group.Amount != nil
			}
		} else if cmp := groups[i].knownAmount.Cmp(groups[j].knownAmount); cmp != 0 {
			return cmp > 0
		}
		return groups[i].group.ProductKey < groups[j].group.ProductKey
	})
	for _, group := range groups {
		result.Groups = append(result.Groups, group.group)
	}
	return result, nil
}

func validateEvidenceRequest(request EvidenceRequest) error {
	if request.Items == nil || len(request.Items) > maxWasteItems {
		return errors.New("evidence request has no item array or too many items")
	}
	seen := make(map[string]struct{}, len(request.Items))
	for _, line := range request.Items {
		if !wasteUUIDPattern.MatchString(line.ItemID) || strings.TrimSpace(line.Name) == "" ||
			utf8.RuneCountInString(line.Name) > 200 || utf8.RuneCountInString(line.ProductKey) > 256 ||
			!wasteKeyPattern.MatchString(line.ProductKey) || utf8.RuneCountInString(line.Advice) > 500 ||
			line.PurchasedAt.IsZero() || line.ItemVersion < 1 {
			return errors.New("evidence request contains an invalid receipt item")
		}
		id := strings.ToLower(line.ItemID)
		if _, exists := seen[id]; exists {
			return errors.New("evidence request contains duplicate item IDs")
		}
		seen[id] = struct{}{}
		if line.Verdict != "" && line.Verdict != "useful" && line.Verdict != "neutral" &&
			line.Verdict != "harmful" && line.Verdict != "unnecessary" {
			return errors.New("evidence request contains an unsupported verdict")
		}
		if utf8.RuneCountInString(line.VerdictSource) > 32 ||
			(line.LineSum != nil && !wasteMoneyPattern.MatchString(*line.LineSum)) {
			return errors.New("evidence request contains invalid source or amount")
		}
	}
	return nil
}

func evidenceInputVersion(request EvidenceRequest) string {
	lines := append([]EvidenceLine(nil), request.Items...)
	for index := range lines {
		lines[index].ItemID = strings.ToLower(lines[index].ItemID)
		lines[index].VerdictSource = normalizedWasteSource(lines[index].VerdictSource)
		lines[index].PurchasedAt = lines[index].PurchasedAt.UTC()
	}
	sort.Slice(lines, func(i, j int) bool { return lines[i].ItemID < lines[j].ItemID })
	canonical, _ := json.Marshal(lines)
	sum := sha256.Sum256(canonical)
	return hex.EncodeToString(sum[:])
}
