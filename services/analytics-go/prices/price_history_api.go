package prices

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

const priceHistoryLimit = 5000

type PriceHistoryReader interface {
	History(context.Context, string, string, time.Time, int) ([]PricePoint, error)
}

type PriceCompareRequest struct {
	TenantID    string `json:"tenantId"`
	OwnerID     string `json:"ownerUserId"`
	ReceiptID   string `json:"receiptId"`
	ItemID      string `json:"itemId"`
	Name        string `json:"name"`
	Quantity    string `json:"quantity"`
	LineSum     string `json:"lineSum"`
	Currency    string `json:"currency"`
	PurchasedAt string `json:"purchasedAt"`
}

type PriceHistoryPoint struct {
	ReceiptID   string    `json:"receiptId"`
	ItemID      string    `json:"itemId"`
	PurchasedAt time.Time `json:"purchasedAt"`
	Merchant    *string   `json:"merchant"`
	Name        string    `json:"name"`
	UnitPrice   string    `json:"unitPrice"`
	Current     bool      `json:"current"`
}

type PriceComparisonResponse struct {
	AlgorithmVersion  string              `json:"algorithmVersion"`
	ProductName       string              `json:"productName"`
	HasBaseline       bool                `json:"hasBaseline"`
	CurrentUnitPrice  string              `json:"currentUnitPrice"`
	BaselineUnitPrice *string             `json:"baselineUnitPrice"`
	Change            *string             `json:"change"`
	Relative          *string             `json:"relative"`
	Signal            bool                `json:"signal"`
	Direction         *Direction          `json:"direction"`
	PriorPurchases    int                 `json:"priorPurchases"`
	History           []PriceHistoryPoint `json:"history"`
}

func NewPriceHistoryHandler(serviceToken string, reader PriceHistoryReader) (http.Handler, error) {
	if strings.TrimSpace(serviceToken) == "" || reader == nil {
		return nil, errors.New("price history handler requires a service token and history reader")
	}
	handler := &priceHistoryHandler{serviceToken: []byte(serviceToken), reader: reader}
	mux := http.NewServeMux()
	mux.Handle("/internal/v1/prices/compare", handler)
	return mux, nil
}

type priceHistoryHandler struct {
	serviceToken []byte
	reader       PriceHistoryReader
}

func (handler *priceHistoryHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !constantTimeBearerMatch(r.Header.Get("Authorization"), handler.serviceToken) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	request, err := decodePriceCompareRequest(w, r)
	if err != nil {
		http.Error(w, "invalid price comparison request", http.StatusBadRequest)
		return
	}
	current, purchasedAt, currentUnitPrice, err := validatePriceCompareRequest(request)
	if err != nil {
		http.Error(w, "price comparison requires a valid paid item", http.StatusUnprocessableEntity)
		return
	}
	history, err := handler.reader.History(r.Context(), request.TenantID, request.OwnerID, purchasedAt, priceHistoryLimit)
	if err != nil {
		http.Error(w, "price history is temporarily unavailable", http.StatusServiceUnavailable)
		return
	}
	observations := make([]Observation, 0, len(history))
	chart := make([]PriceHistoryPoint, 0, len(history)+1)
	for _, point := range history {
		if point.TenantID != request.TenantID || point.OwnerID != request.OwnerID {
			http.Error(w, "price history is temporarily unavailable", http.StatusServiceUnavailable)
			return
		}
		observation := Observation{
			TenantID: point.TenantID, OwnerID: point.OwnerID, ReceiptID: point.ReceiptID,
			Name: point.Name, Quantity: point.Quantity, LineSum: point.LineSum, Purchased: point.PurchasedAt,
		}
		observations = append(observations, observation)
		if point.ReceiptID == current.ReceiptID || !point.PurchasedAt.Before(purchasedAt) ||
			!SameProduct(current.Name, point.Name) {
			continue
		}
		chart = append(chart, PriceHistoryPoint{
			ReceiptID: point.ReceiptID, ItemID: point.ItemID, PurchasedAt: point.PurchasedAt,
			Merchant: point.Merchant, Name: point.Name, UnitPrice: point.UnitPrice,
		})
	}
	comparison, signal, err := Compare(current, observations)
	if err != nil {
		http.Error(w, "price comparison is temporarily unavailable", http.StatusServiceUnavailable)
		return
	}
	sort.Slice(chart, func(i, j int) bool {
		if chart[i].PurchasedAt.Equal(chart[j].PurchasedAt) {
			if chart[i].ReceiptID == chart[j].ReceiptID {
				return chart[i].ItemID < chart[j].ItemID
			}
			return chart[i].ReceiptID < chart[j].ReceiptID
		}
		return chart[i].PurchasedAt.Before(chart[j].PurchasedAt)
	})
	chart = append(chart, PriceHistoryPoint{
		ReceiptID: current.ReceiptID, ItemID: request.ItemID, PurchasedAt: purchasedAt,
		Name: current.Name, UnitPrice: currentUnitPrice, Current: true,
	})
	response := PriceComparisonResponse{
		AlgorithmVersion: AlgorithmVersion, ProductName: current.Name, HasBaseline: comparison.HasBaseline,
		CurrentUnitPrice: currentUnitPrice, Signal: signal, PriorPurchases: comparison.PriorPurchases,
		History: chart,
	}
	if comparison.HasBaseline {
		response.BaselineUnitPrice = stringPointer(comparison.Baseline)
		response.Change = stringPointer(comparison.Change)
		response.Relative = stringPointer(comparison.Relative)
	}
	if signal {
		response.Direction = &comparison.Direction
	}
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(response); err != nil {
		return
	}
}

func decodePriceCompareRequest(w http.ResponseWriter, r *http.Request) (PriceCompareRequest, error) {
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 32*1024))
	decoder.DisallowUnknownFields()
	var request PriceCompareRequest
	if err := decoder.Decode(&request); err != nil {
		return PriceCompareRequest{}, err
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return PriceCompareRequest{}, errors.New("request contains trailing JSON")
	}
	return request, nil
}

func validatePriceCompareRequest(request PriceCompareRequest) (Observation, time.Time, string, error) {
	for _, value := range []string{request.TenantID, request.OwnerID, request.ReceiptID, request.ItemID} {
		if !eventUUIDPattern.MatchString(value) {
			return Observation{}, time.Time{}, "", errors.New("invalid price comparison scope")
		}
	}
	name := strings.TrimSpace(request.Name)
	if utf8.RuneCountInString(name) < 1 || utf8.RuneCountInString(name) > 200 || request.Currency != "RUB" {
		return Observation{}, time.Time{}, "", errors.New("unsupported product name or currency")
	}
	unitPrice, err := UnitPrice(request.LineSum, request.Quantity)
	if err != nil {
		return Observation{}, time.Time{}, "", err
	}
	purchasedAt, err := time.Parse(time.RFC3339Nano, request.PurchasedAt)
	if err != nil {
		return Observation{}, time.Time{}, "", errors.New("invalid purchase time")
	}
	return Observation{
		TenantID: request.TenantID, OwnerID: request.OwnerID, ReceiptID: request.ReceiptID,
		Name: name, Quantity: request.Quantity, LineSum: request.LineSum, Purchased: purchasedAt,
	}, purchasedAt, unitPrice, nil
}

func constantTimeBearerMatch(header string, expected []byte) bool {
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) {
		return false
	}
	provided := []byte(strings.TrimPrefix(header, prefix))
	return len(provided) == len(expected) && subtle.ConstantTimeCompare(provided, expected) == 1
}

func stringPointer(value string) *string {
	return &value
}
