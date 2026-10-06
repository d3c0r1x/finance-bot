package advice

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
)

const maxF43RequestBytes = 32 << 20

type f43Handler struct{ serviceToken []byte }

func NewF43Handler(serviceToken string) (http.Handler, error) {
	if strings.TrimSpace(serviceToken) == "" {
		return nil, errors.New("F43 handler requires a service token")
	}
	return &f43Handler{serviceToken: []byte(serviceToken)}, nil
}

func (handler *f43Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !f43TokenMatches(r.Header.Get("Authorization"), handler.serviceToken) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxF43RequestBytes))
	decoder.DisallowUnknownFields()
	var request F43Request
	if err := decoder.Decode(&request); err != nil {
		var maxError *http.MaxBytesError
		if errors.As(err, &maxError) {
			http.Error(w, "request too large", http.StatusRequestEntityTooLarge)
		} else {
			http.Error(w, "invalid F43 request", http.StatusBadRequest)
		}
		return
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		http.Error(w, "invalid F43 request", http.StatusBadRequest)
		return
	}
	report, err := BuildF43Report(request)
	if err != nil {
		http.Error(w, "invalid F43 request", http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(report); err != nil {
		return
	}
}

func (request *F43Request) UnmarshalJSON(data []byte) error {
	type requestAlias F43Request
	var decoded requestAlias
	if err := unmarshalRequiredWasteObject(data,
		[]string{"inputWatermark", "asOf", "timeZone", "items", "recalculations"}, nil, &decoded); err != nil {
		return err
	}
	*request = F43Request(decoded)
	return nil
}

func (item *F43Item) UnmarshalJSON(data []byte) error {
	type itemAlias F43Item
	var decoded itemAlias
	if err := unmarshalRequiredWasteObject(data,
		[]string{"itemId", "productKey", "name", "lineSum", "verdict", "advice", "adviceGiven", "allowed", "purchasedAt"},
		map[string]bool{"lineSum": true, "verdict": true}, &decoded); err != nil {
		return err
	}
	*item = F43Item(decoded)
	return nil
}

func (recalculation *F43Recalculation) UnmarshalJSON(data []byte) error {
	type recalculationAlias F43Recalculation
	var decoded recalculationAlias
	if err := unmarshalRequiredWasteObject(data,
		[]string{"changedAt", "changedItemCount", "optionalSpendDelta"}, map[string]bool{"optionalSpendDelta": true}, &decoded); err != nil {
		return err
	}
	*recalculation = F43Recalculation(decoded)
	return nil
}

func f43TokenMatches(header string, expected []byte) bool {
	const prefix = "Bearer "
	if len(header) < len(prefix) || subtle.ConstantTimeCompare([]byte(header[:len(prefix)]), []byte(prefix)) != 1 {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(strings.TrimSpace(header[len(prefix):])), expected) == 1
}
