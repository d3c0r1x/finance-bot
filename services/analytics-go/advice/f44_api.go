package advice

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
)

type f44Handler struct{ serviceToken []byte }

func NewF44Handler(serviceToken string) (http.Handler, error) {
	if strings.TrimSpace(serviceToken) == "" {
		return nil, errors.New("F44 handler requires a service token")
	}
	return &f44Handler{serviceToken: []byte(serviceToken)}, nil
}

func (handler *f44Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !wasteServiceTokenMatches(r.Header.Get("Authorization"), handler.serviceToken) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxF43RequestBytes))
	decoder.DisallowUnknownFields()
	var request F44Request
	if err := decoder.Decode(&request); err != nil {
		var maxError *http.MaxBytesError
		if errors.As(err, &maxError) {
			http.Error(w, "request too large", http.StatusRequestEntityTooLarge)
		} else {
			http.Error(w, "invalid F44 request", http.StatusBadRequest)
		}
		return
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		http.Error(w, "invalid F44 request", http.StatusBadRequest)
		return
	}
	report, err := BuildF44Candidates(request)
	if err != nil {
		http.Error(w, "invalid F44 request", http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(report)
}

func (request *F44Request) UnmarshalJSON(data []byte) error {
	type requestAlias F44Request
	var decoded requestAlias
	if err := unmarshalRequiredWasteObject(data,
		[]string{"inputWatermark", "asOf", "unit", "decisions", "purchases"}, nil, &decoded); err != nil {
		return err
	}
	*request = F44Request(decoded)
	return nil
}

func (decision *F44Decision) UnmarshalJSON(data []byte) error {
	type decisionAlias F44Decision
	var decoded decisionAlias
	if err := unmarshalRequiredWasteObject(data,
		[]string{"productKey", "name", "harmfulCount", "modelGuess", "confirmed", "allowed"}, nil, &decoded); err != nil {
		return err
	}
	*decision = F44Decision(decoded)
	return nil
}

func (purchase *F44Purchase) UnmarshalJSON(data []byte) error {
	type purchaseAlias F44Purchase
	var decoded purchaseAlias
	if err := unmarshalRequiredWasteObject(data,
		[]string{"productKey", "name", "lineSum", "purchasedAt"}, map[string]bool{"lineSum": true}, &decoded); err != nil {
		return err
	}
	*purchase = F44Purchase(decoded)
	return nil
}
