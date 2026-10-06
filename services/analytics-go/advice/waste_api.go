package advice

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
)

const maxWasteRequestBytes = 32 << 20

type wasteHandler struct{ serviceToken []byte }

func NewWasteHandler(serviceToken string) (http.Handler, error) {
	if strings.TrimSpace(serviceToken) == "" {
		return nil, errors.New("advice analytics handler requires a service token")
	}
	return &wasteHandler{serviceToken: []byte(serviceToken)}, nil
}

func (handler *wasteHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !wasteServiceTokenMatches(r.Header.Get("Authorization"), handler.serviceToken) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxWasteRequestBytes))
	decoder.DisallowUnknownFields()
	var request WasteRequest
	if err := decoder.Decode(&request); err != nil {
		var maxError *http.MaxBytesError
		if errors.As(err, &maxError) {
			http.Error(w, "request too large", http.StatusRequestEntityTooLarge)
		} else {
			http.Error(w, "invalid advice analytics request", http.StatusBadRequest)
		}
		return
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		http.Error(w, "invalid advice analytics request", http.StatusBadRequest)
		return
	}
	result, err := BuildWasteReport(request)
	if err != nil {
		http.Error(w, "invalid advice analytics request", http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(result); err != nil {
		return
	}
}

func wasteServiceTokenMatches(header string, expected []byte) bool {
	const prefix = "Bearer "
	if len(header) < len(prefix) || subtle.ConstantTimeCompare([]byte(header[:len(prefix)]), []byte(prefix)) != 1 {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(strings.TrimSpace(header[len(prefix):])), expected) == 1
}
