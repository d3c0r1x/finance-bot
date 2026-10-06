package advice

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
)

type f45Handler struct{ serviceToken []byte }

func NewF45Handler(serviceToken string) (http.Handler, error) {
	if strings.TrimSpace(serviceToken) == "" {
		return nil, errors.New("F45 handler requires a service token")
	}
	return &f45Handler{serviceToken: []byte(serviceToken)}, nil
}

func (handler *f45Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
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
	var request F45ProgressRequest
	if err := decoder.Decode(&request); err != nil {
		var maxError *http.MaxBytesError
		if errors.As(err, &maxError) {
			http.Error(w, "request too large", http.StatusRequestEntityTooLarge)
		} else {
			http.Error(w, "invalid F45 request", http.StatusBadRequest)
		}
		return
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		http.Error(w, "invalid F45 request", http.StatusBadRequest)
		return
	}
	report, err := BuildF45Progress(request)
	if err != nil {
		http.Error(w, "invalid F45 request", http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(report)
}

func (request *F45ProgressRequest) UnmarshalJSON(data []byte) error {
	type requestAlias F45ProgressRequest
	var decoded requestAlias
	if err := unmarshalRequiredWasteObject(data,
		[]string{"inputWatermark", "asOf", "goal", "purchases"}, nil, &decoded); err != nil {
		return err
	}
	*request = F45ProgressRequest(decoded)
	return nil
}

func (goal *F45Goal) UnmarshalJSON(data []byte) error {
	type goalAlias F45Goal
	var decoded goalAlias
	if err := unmarshalRequiredWasteObject(data,
		[]string{"key", "scope", "unit", "acceptedAt", "endsAt", "countTarget"},
		map[string]bool{"monthlyLimit": true, "memberProductKeys": true}, &decoded); err != nil {
		return err
	}
	*goal = F45Goal(decoded)
	return nil
}

func (purchase *F45Purchase) UnmarshalJSON(data []byte) error {
	type purchaseAlias F45Purchase
	var decoded purchaseAlias
	if err := unmarshalRequiredWasteObject(data,
		[]string{"productKey", "lineSum", "purchasedAt"}, map[string]bool{"lineSum": true}, &decoded); err != nil {
		return err
	}
	*purchase = F45Purchase(decoded)
	return nil
}
