package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/d3c0r1x/finance-bot/services/analytics-go/advice"
	"github.com/d3c0r1x/finance-bot/services/analytics-go/exports"
	"github.com/d3c0r1x/finance-bot/services/analytics-go/prices"
	"github.com/d3c0r1x/finance-bot/services/analytics-go/recurring"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	worker, err := configuredF43Worker(os.Getenv("ADVICE_ANALYTICS_WORKER_ENABLED"),
		os.Getenv("ANALYTICS_CORE_URL"), os.Getenv("FINANCE_ANALYTICS_SERVICE_TOKEN"),
		os.Getenv("ADVICE_ANALYTICS_WORKER_POLL_INTERVAL"))
	if err != nil {
		return err
	}
	exportWorker, err := configuredExportWorker(os.Getenv)
	if err != nil {
		return err
	}
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
	personalInflation, err := prices.NewPersonalInflationHandler(os.Getenv("FINANCE_ANALYTICS_SERVICE_TOKEN"), store)
	if err != nil {
		return err
	}
	recurringAnalytics, err := recurring.NewHandler(os.Getenv("FINANCE_ANALYTICS_SERVICE_TOKEN"), store)
	if err != nil {
		return err
	}
	wasteAnalytics, err := advice.NewWasteHandler(os.Getenv("FINANCE_ANALYTICS_SERVICE_TOKEN"))
	if err != nil {
		return err
	}
	evidenceAnalytics, err := advice.NewEvidenceHandler(os.Getenv("FINANCE_ANALYTICS_SERVICE_TOKEN"))
	if err != nil {
		return err
	}
	recalculationImpact, err := advice.NewRecalculationImpactHandler(os.Getenv("FINANCE_ANALYTICS_SERVICE_TOKEN"))
	if err != nil {
		return err
	}
	f43Analytics, err := advice.NewF43Handler(os.Getenv("FINANCE_ANALYTICS_SERVICE_TOKEN"))
	if err != nil {
		return err
	}
	f44Goals, err := advice.NewF44Handler(os.Getenv("FINANCE_ANALYTICS_SERVICE_TOKEN"))
	if err != nil {
		return err
	}
	f45Goals, err := advice.NewF45Handler(os.Getenv("FINANCE_ANALYTICS_SERVICE_TOKEN"))
	if err != nil {
		return err
	}
	mux := http.NewServeMux()
	mux.Handle("/internal/v1/prices/compare", priceHistory)
	mux.Handle("/internal/v1/products/catalog", productCatalog)
	mux.Handle("/internal/v1/shopping/candidates", shoppingCandidates)
	mux.Handle("/internal/v1/analytics/personal-inflation", personalInflation)
	mux.Handle("/internal/v1/analytics/recurring", recurringAnalytics)
	mux.Handle("/internal/v1/analytics/waste", wasteAnalytics)
	mux.Handle("/internal/v1/analytics/advice/evidence-groups", evidenceAnalytics)
	mux.Handle("/internal/v1/analytics/recalculation-impact", recalculationImpact)
	mux.Handle("/internal/v1/analytics/advice/f43", f43Analytics)
	mux.Handle("/internal/v1/analytics/goals/candidates", f44Goals)
	mux.Handle("/internal/v1/analytics/goals/progress", f45Goals)
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
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	serverErrors := make(chan error, 1)
	go func() {
		if err := server.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
			serverErrors <- err
		}
	}()
	workerErrors := make(chan error, 1)
	if worker != nil {
		go func() {
			if err := worker.Run(ctx); err != nil && ctx.Err() == nil {
				workerErrors <- err
			}
		}()
	}
	if exportWorker != nil {
		go func() {
			if err := exportWorker.Run(ctx); err != nil && ctx.Err() == nil {
				workerErrors <- err
			}
		}()
	}
	select {
	case err := <-serverErrors:
		stop()
		return err
	case err := <-workerErrors:
		stop()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
		return err
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return server.Shutdown(shutdown)
	}
}

func configuredF43Worker(enabledValue, coreURL, serviceToken, pollValue string) (*advice.F43Worker, error) {
	enabled, err := strconv.ParseBool(strings.TrimSpace(enabledValue))
	if enabledValue == "" {
		enabled = false
	} else if err != nil {
		return nil, errors.New("ADVICE_ANALYTICS_WORKER_ENABLED must be true or false")
	}
	if !enabled {
		return nil, nil
	}
	pollInterval := time.Second
	if strings.TrimSpace(pollValue) != "" {
		pollInterval, err = time.ParseDuration(strings.TrimSpace(pollValue))
		if err != nil || pollInterval <= 0 {
			return nil, errors.New("ADVICE_ANALYTICS_WORKER_POLL_INTERVAL must be a positive duration")
		}
	}
	core, err := advice.NewF43CoreClient(coreURL, serviceToken, 10*time.Second)
	if err != nil {
		return nil, err
	}
	return advice.NewF43Worker(core, pollInterval)
}

func configuredExportWorker(getenv func(string) string) (*exports.Worker, error) {
	enabledValue := strings.TrimSpace(getenv("CSV_EXPORT_WORKER_ENABLED"))
	enabled, err := strconv.ParseBool(enabledValue)
	if enabledValue == "" {
		enabled = false
	} else if err != nil {
		return nil, errors.New("CSV_EXPORT_WORKER_ENABLED must be true or false")
	}
	if !enabled {
		return nil, nil
	}
	pollInterval := time.Second
	if value := strings.TrimSpace(getenv("CSV_EXPORT_WORKER_POLL_INTERVAL")); value != "" {
		pollInterval, err = time.ParseDuration(value)
		if err != nil || pollInterval <= 0 {
			return nil, errors.New("CSV_EXPORT_WORKER_POLL_INTERVAL must be a positive duration")
		}
	}
	pageSize := 500
	if value := strings.TrimSpace(getenv("CSV_EXPORT_WORKER_PAGE_SIZE")); value != "" {
		pageSize, err = strconv.Atoi(value)
		if err != nil || pageSize < 1 || pageSize > 1000 {
			return nil, errors.New("CSV_EXPORT_WORKER_PAGE_SIZE must be from 1 to 1000")
		}
	}
	pathStyle := true
	if value := strings.TrimSpace(getenv("FINANCE_EXPORTS_S3_PATH_STYLE")); value != "" {
		pathStyle, err = strconv.ParseBool(value)
		if err != nil {
			return nil, errors.New("FINANCE_EXPORTS_S3_PATH_STYLE must be true or false")
		}
	}
	core, err := exports.NewCoreClient(getenv("ANALYTICS_CORE_URL"), getenv("FINANCE_EXPORTS_SERVICE_TOKEN"), 15*time.Second)
	if err != nil {
		return nil, err
	}
	storage, err := exports.NewS3ObjectStorage(exports.S3Config{
		Endpoint: getenv("FINANCE_EXPORTS_S3_ENDPOINT"), Region: envOrValue(getenv("FINANCE_EXPORTS_S3_REGION"), "us-east-1"),
		Bucket: getenv("FINANCE_EXPORTS_S3_BUCKET"), AccessKey: getenv("FINANCE_EXPORTS_S3_ACCESS_KEY"),
		SecretKey: getenv("FINANCE_EXPORTS_S3_SECRET_KEY"), PathStyle: pathStyle,
	})
	if err != nil {
		return nil, err
	}
	return exports.NewWorker(core, storage, exports.WorkerConfig{
		PageSize: pageSize, PollInterval: pollInterval, TempDir: strings.TrimSpace(getenv("CSV_EXPORT_WORKER_TEMP_DIR")),
	})
}

func envOrValue(value, fallback string) string {
	if strings.TrimSpace(value) != "" {
		return strings.TrimSpace(value)
	}
	return fallback
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
