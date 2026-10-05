package com.decorix.finance.core.api;

import com.decorix.finance.core.api.MemberProfileApi.ProfileResponse;
import com.decorix.finance.core.api.MemberProfileApi.MemberResponse;
import com.decorix.finance.core.api.MemberProfileApi.UpdateProfileRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberProfileService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public MemberProfileService(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public ProfileResponse get(UUID tenantId, String subject) {
        return transaction.execute(status -> {
            setContext(tenantId, subject);
            UUID userId = userId(subject);
            List<ProfileResponse> rows = jdbc.query("SELECT display_name, planned_income, onboarding_state, timezone, currency "
                    + "FROM member_profiles WHERE tenant_id = ? AND user_id = ?",
                    (rs, row) -> new ProfileResponse(rs.getString("display_name"), rs.getBigDecimal("planned_income"),
                            rs.getString("onboarding_state"), rs.getString("timezone"), rs.getString("currency")), tenantId, userId);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found");
            return rows.get(0);
        });
    }

    public ProfileResponse update(UUID tenantId, String subject, UpdateProfileRequest request) {
        validate(tenantId, subject, request);
        return transaction.execute(status -> {
            setContext(tenantId, subject);
            UUID userId = userId(subject);
            ProfileResponse before = getWithinTransaction(tenantId, userId);
            jdbc.update("UPDATE member_profiles SET display_name = ?, display_name_source = 'user', planned_income = ?, onboarding_state = ?, updated_at = now() "
                            + "WHERE tenant_id = ? AND user_id = ?",
                    request.displayName().trim(), request.plannedIncome(), request.onboardingState(), tenantId, userId);
            ProfileResponse after = getWithinTransaction(tenantId, userId);
            try {
                String beforeJson = new tools.jackson.databind.ObjectMapper().writeValueAsString(before);
                String afterJson = new tools.jackson.databind.ObjectMapper().writeValueAsString(after);
                jdbc.update("INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id, before_state, after_state, trace_id) "
                                + "VALUES (?, ?, 'profile.updated', 'member_profile', ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)",
                        tenantId, subject, tenantId, beforeJson, afterJson, UUID.randomUUID().toString());
            } catch (tools.jackson.core.JacksonException error) {
                throw new IllegalStateException("Profile audit serialization failed", error);
            }
            return after;
        });
    }

    public List<MemberResponse> listMembers(UUID tenantId, String subject) {
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid member request");
        }
        return transaction.execute(status -> {
            setContext(tenantId, subject);
            UUID requesterId = userId(subject);
            String role = jdbc.queryForObject("SELECT role FROM memberships WHERE tenant_id = ? AND user_id = ? "
                    + "AND status = 'active'", String.class, tenantId, requesterId);
            String sql = "SELECT m.user_id, p.display_name, m.role FROM memberships m "
                    + "JOIN member_profiles p ON p.tenant_id = m.tenant_id AND p.user_id = m.user_id "
                    + "WHERE m.tenant_id = ? AND m.status = 'active'";
            if ("owner".equals(role) || "admin".equals(role)) {
                sql += " ORDER BY lower(p.display_name), m.user_id";
                return jdbc.query(sql, (rs, row) -> new MemberResponse(rs.getObject("user_id", UUID.class),
                        rs.getString("display_name"), rs.getString("role")), tenantId);
            }
            sql += " AND m.user_id = ? ORDER BY lower(p.display_name), m.user_id";
            return jdbc.query(sql, (rs, row) -> new MemberResponse(rs.getObject("user_id", UUID.class),
                    rs.getString("display_name"), rs.getString("role")), tenantId, requesterId);
        });
    }

    public void syncTelegramDisplayName(UUID userId, String candidate) {
        if (userId == null || candidate == null) return;
        String displayName = candidate.trim();
        if (displayName.isEmpty() || displayName.length() > 120) return;
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.telegram_link_service', 'true', true)", String.class);
            jdbc.queryForObject("SELECT set_config('app.telegram_link_user_id', ?, true)", String.class, userId.toString());
            List<UUID> tenantIds = jdbc.query("SELECT tenant_id FROM memberships WHERE user_id = ? AND status = 'active'",
                    (rs, row) -> rs.getObject("tenant_id", UUID.class), userId);
            for (UUID tenantId : tenantIds) {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                jdbc.update("UPDATE member_profiles SET display_name = ?, display_name_source = 'telegram', updated_at = now() "
                                + "WHERE tenant_id = ? AND user_id = ? AND display_name_source IN ('workspace_default', 'telegram')",
                        displayName, tenantId, userId);
            }
        });
    }

    private ProfileResponse getWithinTransaction(UUID tenantId, UUID userId) {
        return jdbc.queryForObject("SELECT display_name, planned_income, onboarding_state, timezone, currency FROM member_profiles "
                        + "WHERE tenant_id = ? AND user_id = ?",
                (rs, row) -> new ProfileResponse(rs.getString("display_name"), rs.getBigDecimal("planned_income"),
                        rs.getString("onboarding_state"), rs.getString("timezone"), rs.getString("currency")), tenantId, userId);
    }

    private UUID userId(String subject) {
        List<UUID> ids = jdbc.query("SELECT user_id FROM external_identities WHERE provider = 'keycloak' AND subject = ?",
                (rs, row) -> rs.getObject(1, UUID.class), subject);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found");
        return ids.get(0);
    }

    private void setContext(UUID tenantId, String subject) {
        jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        if (jdbc.queryForObject("SELECT count(*) FROM memberships WHERE tenant_id = ? AND subject = ? AND status = 'active'",
                Integer.class, tenantId, subject) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found");
    }

    private static void validate(UUID tenantId, String subject, UpdateProfileRequest request) {
        BigDecimal income = request == null ? null : request.plannedIncome();
        if (tenantId == null || subject == null || subject.isBlank() || request == null || request.displayName() == null
                || request.displayName().isBlank() || request.displayName().trim().length() > 120
                || !List.of("started", "complete").contains(request.onboardingState())
                || income != null && (income.signum() <= 0 || income.scale() > 2 || income.precision() > 20)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid member profile");
        }
    }
}
