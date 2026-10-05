package prices

import (
	"math/big"
	"regexp"
	"sort"
	"strings"
)

var (
	identityTokenPattern = regexp.MustCompile(`[\p{L}0-9]{3,}`)
	packagePattern       = regexp.MustCompile(`(?i)([0-9]+(?:[.,][0-9]+)?)\s*(кг|kg|г|g|мл|ml|л|l|шт|pcs)`)
	stopWords            = map[string]struct{}{
		"пятерочка": {}, "смaк": {}, "тема": {}, "папа": {}, "мож": {}, "катти": {},
		"pur": {}, "felix": {}, "корм": {}, "пакет": {}, "майка": {}, "покупка": {},
		"товар": {}, "шт": {}, "руб": {}, "р": {},
	}
)

func SameProduct(first, second string) bool {
	firstTokens, secondTokens := identityTokens(first), identityTokens(second)
	if len(firstTokens) == 0 || len(secondTokens) == 0 || packageConflict(first, second) {
		return false
	}
	firstSet := make(map[string]struct{}, len(firstTokens))
	for _, token := range firstTokens {
		firstSet[token] = struct{}{}
	}
	shared := 0
	for _, token := range secondTokens {
		if _, exists := firstSet[token]; exists {
			shared++
		}
	}
	overlap := float64(shared) / float64(max(len(firstTokens), len(secondTokens)))
	firstSignature, secondSignature := strings.Join(firstTokens, ""), strings.Join(secondTokens, "")
	return overlap >= 0.75 || overlap >= 0.5 && sequenceRatio(firstSignature, secondSignature) >= 0.82
}

func identityTokens(name string) []string {
	normalized := strings.ToLower(strings.ReplaceAll(name, "ё", "е"))
	unique := map[string]struct{}{}
	for _, token := range identityTokenPattern.FindAllString(normalized, -1) {
		if _, stop := stopWords[token]; stop || digitsOnly(token) {
			continue
		}
		unique[token] = struct{}{}
	}
	tokens := make([]string, 0, len(unique))
	for token := range unique {
		tokens = append(tokens, token)
	}
	sort.Strings(tokens)
	return tokens
}

func digitsOnly(value string) bool {
	for _, character := range value {
		if character < '0' || character > '9' {
			return false
		}
	}
	return true
}

func packageConflict(first, second string) bool {
	firstPackages, secondPackages := packageFingerprints(first), packageFingerprints(second)
	if len(firstPackages) == 0 || len(secondPackages) == 0 {
		return false
	}
	for fingerprint := range firstPackages {
		if _, same := secondPackages[fingerprint]; same {
			return false
		}
	}
	return true
}

func packageFingerprints(name string) map[string]struct{} {
	fingerprints := map[string]struct{}{}
	normalized := strings.ToLower(strings.ReplaceAll(name, "ё", "е"))
	for _, match := range packagePattern.FindAllStringSubmatch(normalized, -1) {
		quantity, ok := new(big.Rat).SetString(strings.ReplaceAll(match[1], ",", "."))
		if !ok || quantity.Sign() <= 0 {
			continue
		}
		unit := match[2]
		factor := int64(1)
		switch unit {
		case "кг", "kg":
			unit, factor = "g", 1000
		case "г", "g":
			unit = "g"
		case "л", "l":
			unit, factor = "ml", 1000
		case "мл", "ml":
			unit = "ml"
		case "шт", "pcs":
			unit = "count"
		}
		quantity.Mul(quantity, new(big.Rat).SetInt64(factor))
		fingerprints[unit+":"+quantity.RatString()] = struct{}{}
	}
	return fingerprints
}

// sequenceRatio follows the matching-block ratio used by Python SequenceMatcher.
func sequenceRatio(first, second string) float64 {
	if first == "" && second == "" {
		return 1
	}
	firstRunes, secondRunes := []rune(first), []rune(second)
	work := [][4]int{{0, len(firstRunes), 0, len(secondRunes)}}
	matched := 0
	for len(work) > 0 {
		last := len(work) - 1
		rangeToCheck := work[last]
		work = work[:last]
		startA, endA, startB, endB := rangeToCheck[0], rangeToCheck[1], rangeToCheck[2], rangeToCheck[3]
		matchA, matchB, length := longestMatch(firstRunes, secondRunes, startA, endA, startB, endB)
		if length == 0 {
			continue
		}
		matched += length
		if startA < matchA && startB < matchB {
			work = append(work, [4]int{startA, matchA, startB, matchB})
		}
		afterA, afterB := matchA+length, matchB+length
		if afterA < endA && afterB < endB {
			work = append(work, [4]int{afterA, endA, afterB, endB})
		}
	}
	return 2 * float64(matched) / float64(len(firstRunes)+len(secondRunes))
}

func longestMatch(first, second []rune, startA, endA, startB, endB int) (int, int, int) {
	previous := map[int]int{}
	bestA, bestB, bestLength := startA, startB, 0
	for indexA := startA; indexA < endA; indexA++ {
		current := map[int]int{}
		for indexB := startB; indexB < endB; indexB++ {
			if first[indexA] != second[indexB] {
				continue
			}
			length := previous[indexB-1] + 1
			current[indexB] = length
			if length > bestLength {
				bestA, bestB, bestLength = indexA-length+1, indexB-length+1, length
			}
		}
		previous = current
	}
	return bestA, bestB, bestLength
}
