package com.decorix.finance.core.api;

import com.decorix.finance.core.api.RecurringApi.RecurringProjection;
import com.decorix.finance.core.api.RecurringApi.RecurringSeries;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Persists member-local recurring reminder preferences and overlays them on analytics projections. */
@Service
public class RecurringDecisionService {
    private static final String SECTION = "recurring";
    private static final Pattern SERIES_ID = Pattern.compile("[0-9a-f]{32}");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public RecurringDecisionService(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public RecurringProjection apply(UUID tenantId, UUID userId, RecurringProjection source) {
        Set<String> mutedIds = transaction.execute(status -> {
            setTenant(tenantId);
            return new HashSet<>(jdbc.query("SELECT suggestion_key FROM muted_suggestions "
                            + "WHERE tenant_id = ? AND user_id = ? AND section = ?",
                    (rs, row) -> rs.getString("suggestion_key"), tenantId, userId, SECTION));
        });
        if (mutedIds == null) throw unavailable();

        List<RecurringSeries> expenses = source.expenseSeries().stream()
                .filter(series -> !mutedIds.contains(series.id())).toList();
        List<RecurringSeries> incomes = source.incomeSeries().stream()
                .filter(series -> !mutedIds.contains(series.id())).toList();
        List<RecurringSeries> muted = new ArrayList<>();
        source.expenseSeries().stream().filter(series -> mutedIds.contains(series.id())).forEach(muted::add);
        source.incomeSeries().stream().filter(series -> mutedIds.contains(series.id())).forEach(muted::add);

        Map<String, BigDecimal> totals = new TreeMap<>();
        for (RecurringSeries series : expenses) {
            BigDecimal amount = new BigDecimal(series.amount());
            BigDecimal monthly = series.periodDays() >= 25 && series.periodDays() <= 35 ? amount
                    : amount.multiply(BigDecimal.valueOf(30))
                            .divide(BigDecimal.valueOf(series.periodDays()), 12, RoundingMode.HALF_UP);
            totals.merge(series.currency(), monthly, BigDecimal::add);
        }
        Map<String, String> estimates = new TreeMap<>();
        totals.forEach((currency, amount) -> estimates.put(currency, amount.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        String estimate = estimates.size() == 1 ? estimates.values().iterator().next() : null;
        List<RecurringSeries> dueSoon = expenses.stream()
                .filter(series -> series.daysUntil() >= 0 && series.daysUntil() <= 3).toList();
        List<RecurringSeries> overdue = expenses.stream().filter(series -> series.daysUntil() < 0).toList();
        RecurringSeries nextIncome = incomes.stream().filter(series -> series.daysUntil() >= 0)
                .min(Comparator.comparingInt(RecurringSeries::daysUntil).thenComparing(RecurringSeries::id))
                .orElse(null);

        return new RecurringProjection(source.algorithmVersion(), source.completeness(), source.timeZone(), source.asOf(),
                expenses, incomes, dueSoon, overdue, nextIncome, estimate, estimates, List.copyOf(muted));
    }

    public void mute(UUID tenantId, UUID userId, String seriesId) {
        String id = requireSeriesId(seriesId);
        transaction.executeWithoutResult(status -> {
            setTenant(tenantId);
            jdbc.update("INSERT INTO muted_suggestions (tenant_id, user_id, section, suggestion_key) "
                            + "VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id, user_id, section, suggestion_key) DO NOTHING",
                    tenantId, userId, SECTION, id);
        });
    }

    public void unmute(UUID tenantId, UUID userId, String seriesId) {
        String id = requireSeriesId(seriesId);
        transaction.executeWithoutResult(status -> {
            setTenant(tenantId);
            jdbc.update("DELETE FROM muted_suggestions WHERE tenant_id = ? AND user_id = ? "
                    + "AND section = ? AND suggestion_key = ?", tenantId, userId, SECTION, id);
        });
    }

    public static String requireSeriesId(String seriesId) {
        if (seriesId == null || !SERIES_ID.matcher(seriesId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recurring series ID is invalid");
        }
        return seriesId;
    }

    private void setTenant(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Recurring analytics response is incomplete");
    }
}
