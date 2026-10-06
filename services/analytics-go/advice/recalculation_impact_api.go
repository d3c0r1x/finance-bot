package advice

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
)

type recalculationImpactHandler struct{ serviceToken []byte }

func NewRecalculationImpactHandler(serviceToken string) (http.Handler, error) {
	if strings.TrimSpace(serviceToken) == "" {
		return nil, errors.New("recalculation impact handler requires a service token")
	}
	return &recalculationImpactHandler{serviceToken: []byte(serviceToken)}, nil
}

func (handler *recalculationImpactHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !wasteServiceTokenMatches(r.Header.Get("Authorization"), handler.serviceToken) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxWasteRequestBytes*2))
	decoder.DisallowUnknownFields()
	var request RecalculationImpactRequest
	if err := decoder.Decode(&request); err != nil {
		var maxError *http.MaxBytesError
		if errors.As(err, &maxError) {
			http.Error(w, "request too large", http.StatusRequestEntityTooLarge)
		} else {
			http.Error(w, "invalid recalculation impact request", http.StatusBadRequest)
		}
		return
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		http.Error(w, "invalid recalculation impact request", http.StatusBadRequest)
		return
	}
	result, err := BuildRecalculationImpact(request)
	if err != nil {
		http.Error(w, "invalid recalculation impact request", http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(result); err != nil {
		return
	}
}
