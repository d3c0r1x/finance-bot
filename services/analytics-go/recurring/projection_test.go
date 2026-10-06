package recurring

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

const (
	recurringTenantID = "00000000-0000-4000-8000-000000000101"
	recurringOwnerID  = "00000000-0000-4000-8000-000000000102"
)

func TestBuildProjectionFindsWeeklyAndMonthlySeriesWithAmountAndIntervalRanges(t *testing.T) {
	zone, err := time.LoadLocation("Europe/Moscow")
	if err != nil {
		t.Fatal(err)
	}
	asOf := time.Date(2026, 9, 1, 0, 0, 0, 0, zone)
	rows := []Transaction{
		recurringTransaction(1, "expense", "999.00", "Netflix", "досуг", "2026-05-05T10:00:00+03:00"),
		recurringTransaction(2, "expense", "999.00", "Netflix", "досуг", "2026-06-05T10:00:00+03:00"),
		recurringTransaction(3, "expense", "999.00", "Netflix", "досуг", "2026-07-05T10:00:00+03:00"),
		recurringTransaction(4, "expense", "999.00", "Netflix", "досуг", "2026-08-05T10:00:00+03:00"),
		recurringTransaction(5, "expense", "500.00", "Спортзал", "здоровье", "2026-06-01T10:00:00+03:00"),
		recurringTransaction(6, "expense", "550.00", "Спортзал", "здоровье", "2026-06-08T10:00:00+03:00"),
		recurringTransaction(7, "expense", "600.00", "Спортзал", "здоровье", "2026-06-15T10:00:00+03:00"),
	}
	rows[4].Status = "posted"
	rows[4].Amount = "500.00"
	rows[5].Amount = "550.00"
	rows[6].Amount = "600.00"
	projection := BuildProjection(recurringTenantID, recurringOwnerID, rows, asOf, zone)

	if projection.AlgorithmVersion != "recurring.v1" || projection.Completeness != "complete" ||
		projection.TimeZone != "Europe/Moscow" || projection.AsOf != asOf {
		t.Fatalf("projection metadata = %+v", projection)
	}
	if len(projection.ExpenseSeries) != 2 {
		t.Fatalf("expense series = %+v, want Netflix and weekly gym", projection.ExpenseSeries)
	}
	monthly, weekly := projection.ExpenseSeries[0], projection.ExpenseSeries[1]
	if monthly.Name != "Netflix" || monthly.Amount != "999.00" || monthly.MinAmount != "999.00" ||
		monthly.MaxAmount != "999.00" || monthly.PeriodCode != "month" || monthly.PeriodDays != 31 ||
		monthly.MinIntervalDays != 30 || monthly.MaxIntervalDays != 31 || monthly.Occurrences != 4 ||
		monthly.LastDate != "2026-08-05" || monthly.NextDate != "2026-09-05" || monthly.DaysUntil != 4 {
		t.Fatalf("monthly series = %+v", monthly)
	}
	if weekly.Name != "Спортзал" || weekly.Amount != "550.00" || weekly.MinAmount != "500.00" ||
		weekly.MaxAmount != "600.00" || weekly.PeriodCode != "week" || weekly.PeriodDays != 7 ||
		weekly.MinIntervalDays != 7 || weekly.MaxIntervalDays != 7 || weekly.DaysUntil >= 0 {
		t.Fatalf("weekly overdue series = %+v", weekly)
	}
	if projection.MonthlyExpenseEstimate == nil || *projection.MonthlyExpenseEstimate != "3356.14" {
		t.Fatalf("monthly expense estimate = %v, want 3356.14", projection.MonthlyExpenseEstimate)
	}
}

func TestBuildProjectionWarnsOnlyForUpcomingExpensesAndDoesNotTreatOverdueIncomeAsNext(t *testing.T) {
	zone, _ := time.LoadLocation("Europe/Moscow")
	asOf := time.Date(2026, 8, 20, 0, 0, 0, 0, zone)
	rows := []Transaction{
		recurringTransaction(1, "expense", "200.00", "Phone plan", "связь", "2026-08-01T10:00:00+03:00"),
		recurringTransaction(2, "expense", "200.00", "Phone plan", "связь", "2026-08-08T10:00:00+03:00"),
		recurringTransaction(3, "expense", "200.00", "Phone plan", "связь", "2026-08-15T10:00:00+03:00"),
		recurringTransaction(4, "expense", "100.00", "Old payment", "услуги", "2026-07-01T10:00:00+03:00"),
		recurringTransaction(5, "expense", "100.00", "Old payment", "услуги", "2026-07-08T10:00:00+03:00"),
		recurringTransaction(6, "expense", "100.00", "Old payment", "услуги", "2026-07-15T10:00:00+03:00"),
		recurringTransaction(7, "income", "90000.00", "Salary", "зарплата", "2026-05-05T09:00:00+03:00"),
		recurringTransaction(8, "income", "90000.00", "Salary", "зарплата", "2026-06-05T09:00:00+03:00"),
		recurringTransaction(9, "income", "90000.00", "Salary", "зарплата", "2026-07-05T09:00:00+03:00"),
		recurringTransaction(10, "income", "5000.00", "Consulting", "доход", "2026-08-05T09:00:00+03:00"),
		recurringTransaction(11, "income", "5000.00", "Consulting", "доход", "2026-08-12T09:00:00+03:00"),
		recurringTransaction(12, "income", "5000.00", "Consulting", "доход", "2026-08-19T09:00:00+03:00"),
	}
	projection := BuildProjection(recurringTenantID, recurringOwnerID, rows, asOf, zone)

	if len(projection.DueSoon) != 1 || projection.DueSoon[0].Name != "Phone plan" || projection.DueSoon[0].DaysUntil != 2 {
		t.Fatalf("due-soon series = %+v, want only phone plan in 2 days", projection.DueSoon)
	}
	if len(projection.Overdue) != 1 || projection.Overdue[0].Name != "Old payment" || projection.Overdue[0].DaysUntil >= 0 {
		t.Fatalf("overdue series = %+v, want only old payment", projection.Overdue)
	}
	if projection.NextIncome == nil || projection.NextIncome.Name != "Consulting" || projection.NextIncome.DaysUntil != 6 {
		t.Fatalf("next income = %+v, overdue salary must not be selected", projection.NextIncome)
	}
}

func TestBuildProjectionRejectsWeakUnstableAndOutOfScopeSeriesWithoutFakeTotals(t *testing.T) {
	zone, _ := time.LoadLocation("UTC")
	asOf := time.Date(2026, 9, 1, 0, 0, 0, 0, zone)
	rows := []Transaction{
		recurringTransaction(1, "expense", "100.00", "Two only", "услуги", "2026-07-01T00:00:00Z"),
		recurringTransaction(2, "expense", "100.00", "Two only", "услуги", "2026-08-01T00:00:00Z"),
		recurringTransaction(3, "expense", "100.00", "Unstable", "услуги", "2026-05-01T00:00:00Z"),
		recurringTransaction(4, "expense", "150.00", "Unstable", "услуги", "2026-06-01T00:00:00Z"),
		recurringTransaction(5, "expense", "101.00", "Unstable", "услуги", "2026-07-01T00:00:00Z"),
		recurringTransaction(6, "expense", "500.00", "Other member", "услуги", "2026-06-01T00:00:00Z"),
		recurringTransaction(7, "expense", "500.00", "Other member", "услуги", "2026-07-01T00:00:00Z"),
		recurringTransaction(8, "expense", "500.00", "Other member", "услуги", "2026-08-01T00:00:00Z"),
	}
	rows[7].OwnerID = "00000000-0000-4000-8000-000000000199"
	rows = append(rows, recurringTransaction(9, "expense", "500.00", "Future", "услуги", "2026-09-02T00:00:00Z"))
	projection := BuildProjection(recurringTenantID, recurringOwnerID, rows, asOf, zone)

	if len(projection.ExpenseSeries) != 0 || projection.MonthlyExpenseEstimate != nil ||
		len(projection.DueSoon) != 0 || len(projection.Overdue) != 0 || projection.NextIncome != nil {
		t.Fatalf("weak or out-of-scope series leaked into projection: %+v", projection)
	}
}

func TestBuildProjectionKeepsIncomeAndExpenseSeriesSeparateAndIgnoresDuplicateSameDayIntervals(t *testing.T) {
	zone, _ := time.LoadLocation("UTC")
	asOf := time.Date(2026, 8, 30, 0, 0, 0, 0, zone)
	rows := []Transaction{
		recurringTransaction(1, "expense", "500.00", "Shared name", "услуги", "2026-07-01T00:00:00Z"),
		recurringTransaction(2, "expense", "500.00", "Shared name", "услуги", "2026-07-08T00:00:00Z"),
		recurringTransaction(3, "expense", "500.00", "Shared name", "услуги", "2026-07-15T00:00:00Z"),
		recurringTransaction(4, "income", "500.00", "Shared name", "услуги", "2026-07-01T00:00:00Z"),
		recurringTransaction(5, "income", "500.00", "Shared name", "услуги", "2026-07-08T00:00:00Z"),
		recurringTransaction(6, "income", "500.00", "Shared name", "услуги", "2026-07-15T00:00:00Z"),
		recurringTransaction(7, "expense", "500.00", "Same day", "услуги", "2026-07-01T00:00:00Z"),
		recurringTransaction(8, "expense", "500.00", "Same day", "услуги", "2026-07-01T10:00:00Z"),
		recurringTransaction(9, "expense", "500.00", "Same day", "услуги", "2026-07-08T00:00:00Z"),
	}
	projection := BuildProjection(recurringTenantID, recurringOwnerID, rows, asOf, zone)
	if len(projection.ExpenseSeries) != 1 || projection.ExpenseSeries[0].Name != "Shared name" ||
		len(projection.IncomeSeries) != 1 || projection.IncomeSeries[0].Name != "Shared name" {
		t.Fatalf("income and expense series = %+v / %+v", projection.IncomeSeries, projection.ExpenseSeries)
	}
}

func TestBuildProjectionUsesLatestFullStateIncludingVoidsBeforeFiltering(t *testing.T) {
	zone, _ := time.LoadLocation("UTC")
	asOf := time.Date(2026, 8, 30, 0, 0, 0, 0, zone)
	rows := []Transaction{
		recurringTransaction(1, "expense", "100.00", "Cancelled", "services", "2026-08-01T00:00:00Z"),
		recurringTransaction(2, "expense", "100.00", "Cancelled", "services", "2026-08-08T00:00:00Z"),
		recurringTransaction(3, "expense", "100.00", "Cancelled", "services", "2026-08-15T00:00:00Z"),
	}
	voided := rows[2]
	voided.AggregateVersion = 2
	voided.Status = "voided"
	voided.RecordedAt = asOf
	rows = append(rows, voided)
	projection := BuildProjection(recurringTenantID, recurringOwnerID, rows, asOf, zone)
	if len(projection.ExpenseSeries) != 0 {
		t.Fatalf("voided latest transaction remained in recurring series: %+v", projection.ExpenseSeries)
	}
}

func TestBuildProjectionMatchesGoldenContractFixture(t *testing.T) {
	zone, err := time.LoadLocation("Europe/Moscow")
	if err != nil {
		t.Fatal(err)
	}
	asOf := time.Date(2026, 10, 6, 0, 0, 0, 0, zone)
	rows := []Transaction{
		recurringTransaction(1, "expense", "680.00", "Internet", "utilities", "2026-09-18T09:00:00+03:00"),
		recurringTransaction(2, "expense", "700.00", "Internet", "utilities", "2026-09-25T09:00:00+03:00"),
		recurringTransaction(3, "expense", "720.00", "Internet", "utilities", "2026-10-02T09:00:00+03:00"),
		recurringTransaction(4, "expense", "1500.00", "Rent", "housing", "2026-07-01T09:00:00+03:00"),
		recurringTransaction(5, "expense", "1500.00", "Rent", "housing", "2026-08-01T09:00:00+03:00"),
		recurringTransaction(6, "expense", "1500.00", "Rent", "housing", "2026-09-01T09:00:00+03:00"),
		recurringTransaction(7, "income", "90000.00", "Salary", "income", "2026-07-10T09:00:00+03:00"),
		recurringTransaction(8, "income", "90000.00", "Salary", "income", "2026-08-10T09:00:00+03:00"),
		recurringTransaction(9, "income", "90000.00", "Salary", "income", "2026-09-10T09:00:00+03:00"),
	}
	actual, err := json.Marshal(BuildProjection(recurringTenantID, recurringOwnerID, rows, asOf, zone))
	if err != nil {
		t.Fatal(err)
	}
	fixturePath := filepath.Join("..", "..", "..", "contracts", "analytics", "recurring-v1", "projection.json")
	fixture, err := os.ReadFile(fixturePath)
	if err != nil {
		t.Fatal(err)
	}
	var expected Projection
	if err := json.Unmarshal(fixture, &expected); err != nil {
		t.Fatal(err)
	}
	want, err := json.Marshal(expected)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(actual, want) {
		t.Fatalf("projection differs from golden fixture\n got: %s\nwant: %s", actual, want)
	}
}

func recurringTransaction(id int, txType, amount, name, category, occurredAt string) Transaction {
	occurred, err := time.Parse(time.RFC3339, occurredAt)
	if err != nil {
		panic(err)
	}
	return Transaction{
		TenantID: recurringTenantID, OwnerID: recurringOwnerID,
		ID:      "00000000-0000-4000-8000-" + twelveDigits(id),
		EventID: "00000000-0000-4000-8000-000000000201", AggregateVersion: 1,
		Type: txType, Amount: amount, Currency: "RUB", CategoryCode: category,
		Description: name, Status: "posted", OccurredAt: occurred, RecordedAt: occurred,
	}
}

func twelveDigits(value int) string {
	const digits = "000000000000"
	valueText := fmt.Sprint(value)
	return digits[:len(digits)-len(valueText)] + valueText
}
