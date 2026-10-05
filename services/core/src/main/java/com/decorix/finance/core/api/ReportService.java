package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ReportApi.Report;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReportService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BudgetService budgets;

    public ReportService(JdbcTemplate jdbc, TransactionTemplate transaction, BudgetService budgets) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.budgets = budgets;
    }

    public Report get(UUID tenantId, String subject, String period, YearMonth requestedMonth,
                      LocalDate from, LocalDate to, boolean family) {
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report request");
        }
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = jdbc.queryForList("SELECT user_id FROM memberships WHERE tenant_id = ? AND subject = ? "
                            + "AND status = 'active'", UUID.class, tenantId, subject).stream().findFirst().orElse(null);
            if (userId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            String timezone = jdbc.queryForObject("SELECT timezone FROM member_profiles WHERE tenant_id = ? AND user_id = ?",
                    String.class, tenantId, userId);
            ZoneId zone = ZoneId.of(timezone);
            LocalDate today = LocalDate.now(zone);
            Window window = resolveWindow(period, requestedMonth, from, to, today);
            Instant start = window.from().atStartOfDay(zone).toInstant();
            Instant end = window.to().plusDays(1).atStartOfDay(zone).toInstant();
            String scope = family ? "family" : "personal";
            UUID personalOwner = family ? null : userId;

            Totals totals = jdbc.queryForObject("""
                    SELECT COALESCE(sum(amount) FILTER (WHERE type = 'income'), 0.00)::numeric(20,2)::text AS income_total,
                           COALESCE(sum(amount) FILTER (WHERE type = 'expense'), 0.00)::numeric(20,2)::text AS expense_total,
                           COALESCE(sum(amount) FILTER (WHERE type = 'debt_payment'), 0.00)::numeric(20,2)::text AS debt_total,
                           COALESCE(sum(amount) FILTER (WHERE type = 'refund'), 0.00)::numeric(20,2)::text AS refund_total,
                           count(*) FILTER (WHERE type IN ('income', 'expense', 'debt_payment', 'refund'))::integer AS transaction_count,
                           COALESCE(sum(amount) FILTER (WHERE type = 'expense'
                             AND extract(isodow FROM occurred_at AT TIME ZONE ?) IN (6, 7)), 0.00)::numeric(20,2)::text AS weekend_total,
                           COALESCE(sum(amount) FILTER (WHERE type = 'expense'
                             AND extract(isodow FROM occurred_at AT TIME ZONE ?) BETWEEN 1 AND 5), 0.00)::numeric(20,2)::text AS weekday_total
                    FROM transactions
                    WHERE tenant_id = ? AND status = 'posted'
                      AND type IN ('income', 'expense', 'debt_payment', 'refund')
                      AND (?::uuid IS NULL OR owner_user_id = ?)
                      AND occurred_at >= ? AND occurred_at < ?
                    """, (rs, row) -> new Totals(rs.getString("income_total"), rs.getString("expense_total"),
                    rs.getString("debt_total"), rs.getString("refund_total"), rs.getInt("transaction_count"),
                    rs.getString("weekend_total"), rs.getString("weekday_total")),
                    timezone, timezone, tenantId, personalOwner, personalOwner, Timestamp.from(start), Timestamp.from(end));

            Map<String, String> categoryTotals = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT category_code, sum(amount)::numeric(20,2)::text AS amount
                    FROM transactions
                    WHERE tenant_id = ? AND status = 'posted' AND type = 'expense'
                      AND (?::uuid IS NULL OR owner_user_id = ?)
                      AND occurred_at >= ? AND occurred_at < ?
                    GROUP BY category_code ORDER BY sum(amount) DESC, category_code
                    """, rs -> {
                while (rs.next()) categoryTotals.put(rs.getString("category_code"), rs.getString("amount"));
                return null;
            }, tenantId, personalOwner, personalOwner, Timestamp.from(start), Timestamp.from(end));

            Map<String, String> dailyTotals = new LinkedHashMap<>();
            for (LocalDate date = window.from(); !date.isAfter(window.to()); date = date.plusDays(1)) {
                dailyTotals.put(date.toString(), "0.00");
            }
            jdbc.query("""
                    SELECT to_char(occurred_at AT TIME ZONE ?, 'YYYY-MM-DD') AS local_date,
                           sum(amount)::numeric(20,2)::text AS amount
                    FROM transactions
                    WHERE tenant_id = ? AND status = 'posted' AND type = 'expense'
                      AND (?::uuid IS NULL OR owner_user_id = ?)
                      AND occurred_at >= ? AND occurred_at < ?
                    GROUP BY local_date ORDER BY local_date
                    """, rs -> {
                while (rs.next()) dailyTotals.put(rs.getString("local_date"), rs.getString("amount"));
                return null;
            }, timezone, tenantId, personalOwner, personalOwner, Timestamp.from(start), Timestamp.from(end));

            Integer weekendShare = weekendShare(totals.weekendTotal(), totals.weekdayTotal());
            BudgetApi.Overview currentBudget = budgets.get(tenantId, subject);
            String monthlyLimit = null;
            String monthlyRemaining = null;
            if ("month".equals(window.period())) {
                monthlyLimit = family ? currentBudget.familyTotalLimit() : currentBudget.effectiveTotalLimit();
                BigDecimal netSpend = new BigDecimal(totals.expenseTotal()).subtract(new BigDecimal(totals.refundTotal()));
                monthlyRemaining = new BigDecimal(monthlyLimit).subtract(netSpend).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
            }
            BudgetApi.RollingFoodStatus foodStatus = family
                    ? budgets.rollingFoodStatus(tenantId, subject, true) : currentBudget.rolling7FoodStatus();
            return new Report(window.period(), scope, window.from(), window.to(), today, timezone, "RUB",
                    totals.incomeTotal(), totals.expenseTotal(), totals.debtPaymentTotal(), totals.refundTotal(),
                    totals.transactionCount(), Collections.unmodifiableMap(categoryTotals),
                    Collections.unmodifiableMap(dailyTotals), weekendShare, monthlyLimit,
                    monthlyRemaining, foodStatus);
        });
    }

    private static Window resolveWindow(String period, YearMonth requestedMonth, LocalDate from, LocalDate to, LocalDate today) {
        if (period == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Report period is required");
        return switch (period) {
            case "month" -> {
                YearMonth month = requestedMonth == null ? YearMonth.from(today) : requestedMonth;
                yield new Window("month", month.atDay(1), month.atEndOfMonth());
            }
            case "week" -> new Window("week", today.minusDays(6), today);
            case "90d" -> new Window("90d", today.minusDays(89), today);
            case "custom" -> {
                if (from == null || to == null || from.isAfter(to)
                        || ChronoUnit.DAYS.between(from, to) >= 366) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Custom report range must be 1 to 366 days");
                }
                yield new Window("custom", from, to);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported report period");
        };
    }

    private static Integer weekendShare(String weekend, String weekday) {
        BigDecimal weekendAmount = new BigDecimal(weekend);
        BigDecimal weekdayAmount = new BigDecimal(weekday);
        BigDecimal total = weekendAmount.add(weekdayAmount);
        if (total.signum() <= 0 || weekdayAmount.signum() <= 0) return null;
        return weekendAmount.multiply(BigDecimal.valueOf(100)).divide(total, 0, RoundingMode.DOWN).intValueExact();
    }

    private record Window(String period, LocalDate from, LocalDate to) {}
    private record Totals(String incomeTotal, String expenseTotal, String debtPaymentTotal, String refundTotal,
                          int transactionCount, String weekendTotal, String weekdayTotal) {}
}
