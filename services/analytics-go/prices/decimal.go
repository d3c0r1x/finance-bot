package prices

import (
	"errors"
	"fmt"
	"math/big"
	"regexp"
	"strings"
)

const priceScale = 6

var (
	lineSumPattern  = regexp.MustCompile(`^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,2})?$`)
	quantityPattern = regexp.MustCompile(`^(?:0|[1-9][0-9]{0,11})(?:\.[0-9]{1,6})?$`)
)

func UnitPrice(lineSum, quantity string) (string, error) {
	sum, err := parsePositive(lineSum, lineSumPattern, "line sum")
	if err != nil {
		return "", err
	}
	qty, err := parsePositive(quantity, quantityPattern, "quantity")
	if err != nil {
		return "", err
	}
	return formatFixed(new(big.Rat).Quo(sum, qty), priceScale), nil
}

func parsePositive(value string, pattern *regexp.Regexp, field string) (*big.Rat, error) {
	if !pattern.MatchString(value) {
		return nil, fmt.Errorf("%s must be a non-negative decimal with supported precision", field)
	}
	number, ok := new(big.Rat).SetString(value)
	if !ok || number.Sign() <= 0 {
		return nil, fmt.Errorf("%s must be positive", field)
	}
	return number, nil
}

func formatFixed(number *big.Rat, scale int) string {
	if number == nil {
		return strings.Repeat("0", 1) + "." + strings.Repeat("0", scale)
	}
	negative := number.Sign() < 0
	abs := new(big.Rat).Abs(number)
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
		return "-" + whole.String() + "." + decimal
	}
	return whole.String() + "." + decimal
}

func parseObservationPrice(observation Observation) (*big.Rat, error) {
	if observation.Quantity == "" || observation.LineSum == "" {
		return nil, errors.New("price observation requires quantity and paid line sum")
	}
	value, err := UnitPrice(observation.LineSum, observation.Quantity)
	if err != nil {
		return nil, err
	}
	price, ok := new(big.Rat).SetString(value)
	if !ok {
		return nil, errors.New("calculated unit price is invalid")
	}
	return price, nil
}
