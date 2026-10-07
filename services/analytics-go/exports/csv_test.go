package exports

import (
	"bytes"
	"encoding/csv"
	"encoding/json"
	"io"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestCSVV1MatchesPublishedExportContract(t *testing.T) {
	contractBytes, err := os.ReadFile("../../../contracts/exports/csv-v1.json")
	if err != nil {
		t.Fatal(err)
	}
	var contract struct {
		FormatVersion string   `json:"formatVersion"`
		Encoding      string   `json:"encoding"`
		BOM           bool     `json:"bom"`
		Delimiter     string   `json:"delimiter"`
		DateFormat    string   `json:"dateFormat"`
		DateTimezone  string   `json:"dateTimezone"`
		Headers       []string `json:"headers"`
		AmountPattern string   `json:"amountPattern"`
		FormulaEscape string   `json:"formulaEscape"`
		Prefixes      []string `json:"formulaPrefixes"`
		Whitespace    string   `json:"formulaLeadingWhitespace"`
	}
	if err := json.Unmarshal(contractBytes, &contract); err != nil {
		t.Fatal(err)
	}
	if contract.FormatVersion != FormatVersionV1 || contract.Encoding != "UTF-8" || !contract.BOM ||
		contract.Delimiter != ";" || contract.DateFormat != "yyyy-MM-dd HH:mm" ||
		contract.DateTimezone != "requester profile timezone" ||
		contract.AmountPattern != `^(?:0|[1-9][0-9]{0,17})\.[0-9]{2}$` ||
		contract.FormulaEscape != "apostrophe-for-leading-formula-text" ||
		contract.Whitespace != " \\t\\r\\n" ||
		!reflect.DeepEqual(contract.Prefixes, []string{"=", "+", "-", "@"}) {
		t.Fatalf("unexpected CSV v1 contract: %+v", contract)
	}
	if !reflect.DeepEqual(contract.Headers, legacyHeaders) {
		t.Fatalf("contract headers %q do not match writer headers %q", contract.Headers, legacyHeaders)
	}
}

func TestCSVV1MatchesLegacyExcelFormatAndTimezone(t *testing.T) {
	var output bytes.Buffer
	zone, err := time.LoadLocation("Europe/Moscow")
	if err != nil {
		t.Fatal(err)
	}
	writer, err := NewCSVWriter(&output, zone)
	if err != nil {
		t.Fatal(err)
	}
	row := Transaction{
		OccurredAt:  time.Date(2026, time.January, 2, 8, 4, 59, 0, time.UTC),
		Amount:      "1250.50",
		Category:    "Продукты",
		Subcategory: "Молочное",
		Description: "Кефир 2,5%",
		Type:        "expense",
		DebtTarget:  "",
		Source:      "manual",
		TelegramID:  "123456789",
	}
	if err := writer.Write(row); err != nil {
		t.Fatal(err)
	}
	if err := writer.Flush(); err != nil {
		t.Fatal(err)
	}

	if !bytes.HasPrefix(output.Bytes(), []byte{0xef, 0xbb, 0xbf}) {
		t.Fatalf("missing UTF-8 BOM: %x", output.Bytes()[:min(3, output.Len())])
	}
	reader := csv.NewReader(bytes.NewReader(bytes.TrimPrefix(output.Bytes(), []byte{0xef, 0xbb, 0xbf})))
	reader.Comma = ';'
	records, err := reader.ReadAll()
	if err != nil {
		t.Fatal(err)
	}
	want := [][]string{
		{"Дата", "Сумма", "Категория", "Подкатегория", "Описание", "Тип", "Долг", "Источник", "Telegram ID"},
		{"2026-01-02 11:04", "1250.50", "Продукты", "Молочное", "Кефир 2,5%", "expense", "", "manual", "123456789"},
	}
	if len(records) != len(want) {
		t.Fatalf("got %d CSV records; want %d: %#v", len(records), len(want), records)
	}
	for rowIndex := range want {
		if len(records[rowIndex]) != len(want[rowIndex]) {
			t.Fatalf("row %d has %d columns; want %d", rowIndex, len(records[rowIndex]), len(want[rowIndex]))
		}
		for colIndex := range want[rowIndex] {
			if records[rowIndex][colIndex] != want[rowIndex][colIndex] {
				t.Errorf("row %d column %d = %q; want %q", rowIndex, colIndex,
					records[rowIndex][colIndex], want[rowIndex][colIndex])
			}
		}
	}
}

func TestCSVV1QuotesCSVControlsAndEscapesFormulaLikeTextOnly(t *testing.T) {
	var output bytes.Buffer
	writer, err := NewCSVWriter(&output, time.UTC)
	if err != nil {
		t.Fatal(err)
	}
	row := Transaction{
		OccurredAt:  time.Date(2026, time.January, 2, 8, 4, 0, 0, time.UTC),
		Amount:      "0.50",
		Category:    "+category",
		Subcategory: "sub;category",
		Description: "=HYPERLINK(\"https://bad.example\";\"open\")\nsecond line",
		Type:        "expense",
		DebtTarget:  "-debt",
		Source:      "\t=cmd",
		TelegramID:  "@formula-like-id",
	}
	if err := writer.Write(row); err != nil {
		t.Fatal(err)
	}
	if err := writer.Flush(); err != nil {
		t.Fatal(err)
	}
	reader := csv.NewReader(bytes.NewReader(bytes.TrimPrefix(output.Bytes(), []byte{0xef, 0xbb, 0xbf})))
	reader.Comma = ';'
	if _, err := reader.Read(); err != nil {
		t.Fatal(err)
	}
	record, err := reader.Read()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := reader.Read(); err != io.EOF {
		t.Fatalf("expected one logical CSV row, got trailing row/error %v", err)
	}
	if record[1] != "0.50" {
		t.Fatalf("numeric amount changed: got %q", record[1])
	}
	for index, want := range map[int]string{
		2: "'+category",
		3: "sub;category",
		4: "'=HYPERLINK(\"https://bad.example\";\"open\")\nsecond line",
		6: "'-debt",
		7: "'\t=cmd",
		8: "'@formula-like-id",
	} {
		if record[index] != want {
			t.Errorf("text column %d = %q; want %q", index, record[index], want)
		}
	}
	if !strings.Contains(output.String(), "\"=HYPERLINK") && !strings.Contains(output.String(), "\"'=HYPERLINK") {
		t.Fatal("expected the quoted multiline formula field")
	}
}

func TestCSVV1RejectsInvalidAmountAndIncompleteTimestamp(t *testing.T) {
	for name, row := range map[string]Transaction{
		"formula amount":    {OccurredAt: time.Now(), Amount: "=1+1"},
		"negative amount":   {OccurredAt: time.Now(), Amount: "-1.00"},
		"missing timestamp": {Amount: "1.00"},
	} {
		t.Run(name, func(t *testing.T) {
			var output bytes.Buffer
			writer, err := NewCSVWriter(&output, time.UTC)
			if err != nil {
				t.Fatal(err)
			}
			if err := writer.Write(row); err == nil {
				t.Fatal("expected invalid transaction row to fail")
			}
		})
	}
}
