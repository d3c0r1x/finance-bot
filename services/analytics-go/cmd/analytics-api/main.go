package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/d3c0r1x/finance-bot/services/analytics-go/prices"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	store, err := prices.NewClickHouseHTTPStore(prices.ClickHouseHTTPConfig{
		Endpoint: os.Getenv("CLICKHOUSE_URL"),
		Database: envOr("CLICKHOUSE_DATABASE", "finance_analytics"),
		Username: os.Getenv("CLICKHOUSE_USERNAME"),
		Password: os.Getenv("CLICKHOUSE_PASSWORD"),
	})
	if err != nil {
		return err
	}
	priceHistory, err := prices.NewPriceHistoryHandler(os.Getenv("FINANCE_ANALYTICS_SERVICE_TOKEN"), store)
	if err != nil {
		return err
	}
	productCatalog, err := prices.NewProductCatalogHandler(os.Getenv("FINANCE_ANALYTICS_SERVICE_TOKEN"), store)
	if err != nil {
		return err
	}
	shoppingCandidates, err := prices.NewShoppingCandidatesHandler(os.Getenv("FINANCE_ANALYTICS_SERVICE_TOKEN"), store)
	if err != nil {
		return err
	}
	mux := http.NewServeMux()
	mux.Handle("/internal/v1/prices/compare", priceHistory)
	mux.Handle("/internal/v1/products/catalog", productCatalog)
	mux.Handle("/internal/v1/shopping/candidates", shoppingCandidates)
	mux.HandleFunc("/healthz", healthHandler)
	mux.HandleFunc("/readyz", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			w.Header().Set("Allow", http.MethodGet)
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()
		if err := store.Ping(ctx); err != nil {
			http.Error(w, "analytics storage unavailable", http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
	server := &http.Server{
		Addr: envOr("ANALYTICS_HTTP_ADDR", "127.0.0.1:8090"), Handler: mux,
		ReadHeaderTimeout: 3 * time.Second, ReadTimeout: 10 * time.Second,
		WriteTimeout: 15 * time.Second, IdleTimeout: 60 * time.Second,
	}
	serverErrors := make(chan error, 1)
	go func() {
		if err := server.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
			serverErrors <- err
		}
	}()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	select {
	case err := <-serverErrors:
		return err
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return server.Shutdown(shutdown)
	}
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func envOr(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}
