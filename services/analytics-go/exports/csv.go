package exports

import (
	"encoding/csv"
	"errors"
	"io"
	"regexp"
	"strings"
	"time"
)

const FormatVersionV1 = "csv-v1"

var amountPattern = regexp.MustCompile(`^(?:0|[1-9][0-9]{0,17})\.[0-9]{2}$`)

var legacyHeaders = []string{
	"Дата",
	"Сумма",
	"Категория",
	"Подкатегория",
	"Описание",
	"Тип",
	"Долг",
	"Источник",
	"Telegram ID",
}

// Transaction contains the already-authorized, presentation-ready values for one CSV row.
type Transaction struct {
	OccurredAt  time.Time
	Amount      string
	Category    string
	Subcategory string
	Description string
	Type        string
	DebtTarget  string
	Source      string
	TelegramID  string
}

// CSVWriter writes CSV v1 incrementally so the export worker can stream keyset pages.
type CSVWriter struct {
	writer *csv.Writer
	zone   *time.Location
}

func NewCSVWriter(output io.Writer, zone *time.Location) (*CSVWriter, error) {
	if output == nil {
		return nil, errors.New("CSV output is required")
	}
	if zone == nil {
		return nil, errors.New("CSV timezone is required")
	}
	if _, err := io.WriteString(output, "\uFEFF"); err != nil {
		return nil, err
	}
	writer := csv.NewWriter(output)
	writer.Comma = ';'
	if err := writer.Write(legacyHeaders); err != nil {
		return nil, err
	}
	writer.Flush()
	if err := writer.Error(); err != nil {
		return nil, err
	}
	return &CSVWriter{writer: writer, zone: zone}, nil
}

func (writer *CSVWriter) Write(row Transaction) error {
	if writer == nil || writer.writer == nil {
		return errors.New("CSV writer is not initialized")
	}
	if row.OccurredAt.IsZero() {
		return errors.New("CSV transaction timestamp is required")
	}
	if !amountPattern.MatchString(row.Amount) {
		return errors.New("CSV transaction amount must be an exact non-negative decimal")
	}
	record := []string{
		row.OccurredAt.In(writer.zone).Format("2006-01-02 15:04"),
		row.Amount,
		escapeFormulaText(row.Category),
		escapeFormulaText(row.Subcategory),
		escapeFormulaText(row.Description),
		escapeFormulaText(row.Type),
		escapeFormulaText(row.DebtTarget),
		escapeFormulaText(row.Source),
		escapeFormulaText(row.TelegramID),
	}
	return writer.writer.Write(record)
}

func (writer *CSVWriter) Flush() error {
	if writer == nil || writer.writer == nil {
		return errors.New("CSV writer is not initialized")
	}
	writer.writer.Flush()
	return writer.writer.Error()
}

func escapeFormulaText(value string) string {
	trimmed := strings.TrimLeft(value, " \t\r\n")
	if trimmed == "" {
		return value
	}
	switch trimmed[0] {
	case '=', '+', '-', '@':
		return "'" + value
	default:
		return value
	}
}
