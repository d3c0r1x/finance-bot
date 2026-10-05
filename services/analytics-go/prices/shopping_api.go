package prices

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

type ShoppingCandidatesRequest struct {
	TenantID string `json:"tenantId"`
	OwnerID  string `json:"ownerUserId"`
}

type ShoppingHistoryReader interface {
	History(context.Context, string, string, time.Time, int) ([]PricePoint, error)
}

func NewShoppingCandidatesHandler(serviceToken string, reader ShoppingHistoryReader) (http.Handler, error) {
	if strings.TrimSpace(serviceToken) == "" || reader == nil {
		return nil, errors.New("shopping candidates handler requires a service token and history reader")
	}
	handler := &shoppingCandidatesHandler{serviceToken: []byte(serviceToken), reader: reader}
	mux := http.NewServeMux()
	mux.Handle("/internal/v1/shopping/candidates", handler)
	return mux, nil
}

type shoppingCandidatesHandler struct {
	serviceToken []byte
	reader       ShoppingHistoryReader
}

func (handler *shoppingCandidatesHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !shoppingBearerMatches(r.Header.Get("Authorization"), handler.serviceToken) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	request, err := decodeShoppingCandidatesRequest(w, r)
	if err != nil || !eventUUIDPattern.MatchString(request.TenantID) || !eventUUIDPattern.MatchString(request.OwnerID) {
		http.Error(w, "invalid shopping candidates request", http.StatusBadRequest)
		return
	}
	now := time.Now().UTC()
	points, err := handler.reader.History(r.Context(), request.TenantID, request.OwnerID,
		now.Add(time.Second), priceHistoryLimit)
	if err != nil {
		http.Error(w, "shopping history is temporarily unavailable", http.StatusServiceUnavailable)
		return
	}
	for _, point := range points {
		if point.TenantID != request.TenantID || point.OwnerID != request.OwnerID {
			http.Error(w, "shopping history is temporarily unavailable", http.StatusServiceUnavailable)
			return
		}
	}
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(BuildShoppingList(points, now)); err != nil {
		return
	}
}

func decodeShoppingCandidatesRequest(w http.ResponseWriter, r *http.Request) (ShoppingCandidatesRequest, error) {
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16*1024))
	decoder.DisallowUnknownFields()
	var request ShoppingCandidatesRequest
	if err := decoder.Decode(&request); err != nil {
		return ShoppingCandidatesRequest{}, err
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return ShoppingCandidatesRequest{}, errors.New("shopping request contains trailing JSON")
	}
	return request, nil
}

func shoppingBearerMatches(header string, expected []byte) bool {
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) {
		return false
	}
	provided := []byte(strings.TrimPrefix(header, prefix))
	return len(provided) == len(expected) && subtle.ConstantTimeCompare(provided, expected) == 1
}
