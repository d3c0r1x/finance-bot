package com.decorix.finance.core.api;

import com.decorix.finance.core.domain.CashPlanningPolicy;
import com.decorix.finance.core.domain.RecurringProjectionPolicy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CashPlanningService {
    private final JdbcTemplate jdbc;

    public CashPlanningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CashPlanningPolicy.SafeToSpend safeToSpend(UUID tenantId, UUID userId, ZoneId zone, LocalDate today,
                                                       YearMonth month, String realizedIncome, String monthlyExpenses) {
        List<BigDecimal> plannedRows = jdbc.query("SELECT planned_income FROM member_profiles WHERE tenant_id = ? AND user_id = ?",
                (rs, row) -> rs.getBigDecimal("planned_income"), tenantId, userId);
        BigDecimal plannedIncome = plannedRows.isEmpty() ? null : plannedRows.get(0);
        Timestamp windowStart = Timestamp.from(today.minusDays(200).atStartOfDay(zone).toInstant());
        Timestamp windowEnd = Timestamp.from(today.plusDays(1).atStartOfDay(zone).toInstant());
        List<RecurringProjectionPolicy.HistoryItem> history = jdbc.query("""
                SELECT type, amount::text AS amount, category_code, description,
                       (occurred_at AT TIME ZONE ?)::date AS occurred_on
                FROM transactions
                WHERE tenant_id = ? AND owner_user_id = ? AND status = 'posted'
                  AND type IN ('income', 'expense') AND occurred_at >= ? AND occurred_at < ?
                ORDER BY occurred_at, id
                """, (rs, row) -> new RecurringProjectionPolicy.HistoryItem(
                rs.getString("type"), new BigDecimal(rs.getString("amount")), rs.getString("category_code"),
                rs.getString("description"), rs.getObject("occurred_on", LocalDate.class)),
                zone.getId(), tenantId, userId, windowStart, windowEnd);
        return CashPlanningPolicy.calculate(plannedIncome, new BigDecimal(realizedIncome),
                new BigDecimal(monthlyExpenses), today, month, RecurringProjectionPolicy.detect(history, today));
    }
}
