package prices

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"
)

const inflationHistoryLimit = 5000

type PersonalInflationRequest struct {
	TenantID string    `json:"tenantId"`
	OwnerID  string    `json:"ownerUserId"`
	AsOf     time.Time `json:"asOf"`
}

func NewPersonalInflationHandler(serviceToken string, reader PriceHistoryReader) (http.Handler, error) {
	if strings.TrimSpace(serviceToken) == "" || reader == nil {
		return nil, errors.New("personal inflation handler requires a service token and history reader")
	}
	handler := &personalInflationHandler{serviceToken: []byte(serviceToken), reader: reader}
	mux := http.NewServeMux()
	mux.Handle("/internal/v1/analytics/personal-inflation", handler)
	return mux, nil
}

type personalInflationHandler struct {
	serviceToken []byte
	reader       PriceHistoryReader
}

func (handler *personalInflationHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !constantTimeBearerMatch(r.Header.Get("Authorization"), handler.serviceToken) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	request, err := decodePersonalInflationRequest(w, r)
	if err != nil || !eventUUIDPattern.MatchString(request.TenantID) ||
		!eventUUIDPattern.MatchString(request.OwnerID) || request.AsOf.IsZero() {
		http.Error(w, "invalid personal inflation request", http.StatusBadRequest)
		return
	}
	asOf := request.AsOf.UTC()
	points, err := handler.reader.History(r.Context(), request.TenantID, request.OwnerID,
		asOf.Add(time.Microsecond), inflationHistoryLimit)
	if err != nil || len(points) >= inflationHistoryLimit {
		http.Error(w, "personal inflation is temporarily unavailable", http.StatusServiceUnavailable)
		return
	}
	for _, point := range points {
		if point.TenantID != request.TenantID || point.OwnerID != request.OwnerID || validatePricePoint(point) != nil {
			http.Error(w, "personal inflation is temporarily unavailable", http.StatusServiceUnavailable)
			return
		}
	}
	result := BuildPersonalInflation(request.TenantID, request.OwnerID, points, asOf)
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(result); err != nil {
		return
	}
}

func decodePersonalInflationRequest(w http.ResponseWriter, r *http.Request) (PersonalInflationRequest, error) {
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16*1024))
	decoder.DisallowUnknownFields()
	var request PersonalInflationRequest
	if err := decoder.Decode(&request); err != nil {
		return PersonalInflationRequest{}, err
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return PersonalInflationRequest{}, errors.New("request contains trailing JSON")
	}
	return request, nil
}
