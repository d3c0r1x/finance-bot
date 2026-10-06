package recurring

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"
)

const historyLimit = 5000

type TransactionReader interface {
	Transactions(context.Context, string, string, int) ([]Transaction, error)
}

type Request struct {
	TenantID string    `json:"tenantId"`
	OwnerID  string    `json:"ownerUserId"`
	AsOf     time.Time `json:"asOf"`
	TimeZone string    `json:"timeZone"`
}

func NewHandler(serviceToken string, reader TransactionReader) (http.Handler, error) {
	if strings.TrimSpace(serviceToken) == "" || reader == nil {
		return nil, errors.New("recurring handler requires a service token and transaction reader")
	}
	handler := &handler{serviceToken: []byte(serviceToken), reader: reader}
	mux := http.NewServeMux()
	mux.Handle("/internal/v1/analytics/recurring", handler)
	return mux, nil
}

type handler struct {
	serviceToken []byte
	reader       TransactionReader
}

func (handler *handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !serviceTokenMatches(r.Header.Get("Authorization"), handler.serviceToken) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	request, err := decodeRequest(w, r)
	if err != nil || !uuidPattern.MatchString(request.TenantID) || !uuidPattern.MatchString(request.OwnerID) ||
		request.AsOf.IsZero() || strings.TrimSpace(request.TimeZone) == "" {
		http.Error(w, "invalid recurring request", http.StatusBadRequest)
		return
	}
	location, err := time.LoadLocation(request.TimeZone)
	if err != nil {
		http.Error(w, "invalid recurring time zone", http.StatusBadRequest)
		return
	}
	transactions, err := handler.reader.Transactions(r.Context(), strings.ToLower(request.TenantID),
		strings.ToLower(request.OwnerID), historyLimit)
	if err != nil || len(transactions) >= historyLimit {
		http.Error(w, "recurring projection is temporarily unavailable", http.StatusServiceUnavailable)
		return
	}
	for _, transaction := range transactions {
		if transaction.TenantID != strings.ToLower(request.TenantID) || transaction.OwnerID != strings.ToLower(request.OwnerID) ||
			transaction.ID == "" || transaction.AggregateVersion == 0 {
			http.Error(w, "recurring projection is temporarily unavailable", http.StatusServiceUnavailable)
			return
		}
	}
	projection := BuildProjection(request.TenantID, request.OwnerID, transactions, request.AsOf, location)
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(projection); err != nil {
		return
	}
}

func decodeRequest(w http.ResponseWriter, r *http.Request) (Request, error) {
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16*1024))
	decoder.DisallowUnknownFields()
	var request Request
	if err := decoder.Decode(&request); err != nil {
		return Request{}, err
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return Request{}, errors.New("request contains trailing JSON")
	}
	return request, nil
}

func serviceTokenMatches(header string, expected []byte) bool {
	const prefix = "Bearer "
	if len(header) < len(prefix) || subtle.ConstantTimeCompare([]byte(header[:len(prefix)]), []byte(prefix)) != 1 {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(strings.TrimSpace(header[len(prefix):])), expected) == 1
}
