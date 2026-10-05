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
	"unicode"
	"unicode/utf8"
)

const productCatalogQueryLimit = 80

type ProductCatalogRequest struct {
	TenantID string `json:"tenantId"`
	OwnerID  string `json:"ownerUserId"`
	Query    string `json:"query"`
}

type ProductCatalogResponse struct {
	Mode     string        `json:"mode"`
	Query    string        `json:"query"`
	Products []ProductCard `json:"products"`
}

type ProductCatalogReader interface {
	History(context.Context, string, string, time.Time, int) ([]PricePoint, error)
}

func NewProductCatalogHandler(serviceToken string, reader ProductCatalogReader) (http.Handler, error) {
	if strings.TrimSpace(serviceToken) == "" || reader == nil {
		return nil, errors.New("product catalog handler requires a service token and history reader")
	}
	handler := &productCatalogHandler{serviceToken: []byte(serviceToken), reader: reader}
	mux := http.NewServeMux()
	mux.Handle("/internal/v1/products/catalog", handler)
	return mux, nil
}

type productCatalogHandler struct {
	serviceToken []byte
	reader       ProductCatalogReader
}

func (handler *productCatalogHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !catalogBearerMatches(r.Header.Get("Authorization"), handler.serviceToken) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	request, err := decodeProductCatalogRequest(w, r)
	if err != nil || !validProductCatalogRequest(request) {
		http.Error(w, "invalid product catalog request", http.StatusBadRequest)
		return
	}
	request.Query = strings.TrimSpace(request.Query)
	points, err := handler.reader.History(r.Context(), request.TenantID, request.OwnerID,
		time.Now().UTC().Add(time.Second), priceHistoryLimit)
	if err != nil {
		http.Error(w, "product history is temporarily unavailable", http.StatusServiceUnavailable)
		return
	}
	for _, point := range points {
		if point.TenantID != request.TenantID || point.OwnerID != request.OwnerID {
			http.Error(w, "product history is temporarily unavailable", http.StatusServiceUnavailable)
			return
		}
	}
	mode := "search"
	if request.Query == "" {
		mode = "catalog"
	}
	response := ProductCatalogResponse{Mode: mode, Query: request.Query, Products: ListProducts(points, request.Query)}
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(response); err != nil {
		return
	}
}

func decodeProductCatalogRequest(w http.ResponseWriter, r *http.Request) (ProductCatalogRequest, error) {
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16*1024))
	decoder.DisallowUnknownFields()
	var request ProductCatalogRequest
	if err := decoder.Decode(&request); err != nil {
		return ProductCatalogRequest{}, err
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return ProductCatalogRequest{}, errors.New("request contains trailing JSON")
	}
	return request, nil
}

func validProductCatalogRequest(request ProductCatalogRequest) bool {
	if !eventUUIDPattern.MatchString(request.TenantID) || !eventUUIDPattern.MatchString(request.OwnerID) ||
		!utf8.ValidString(request.Query) || utf8.RuneCountInString(request.Query) > productCatalogQueryLimit {
		return false
	}
	for _, character := range request.Query {
		if unicode.IsControl(character) {
			return false
		}
	}
	return true
}

func catalogBearerMatches(header string, expected []byte) bool {
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) {
		return false
	}
	provided := []byte(strings.TrimPrefix(header, prefix))
	return len(provided) == len(expected) && subtle.ConstantTimeCompare(provided, expected) == 1
}
