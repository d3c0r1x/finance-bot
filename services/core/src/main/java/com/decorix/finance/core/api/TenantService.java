package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TenantApi.CreateTenantRequest;
import com.decorix.finance.core.api.TenantApi.TenantResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TelegramAccessPolicy telegramAccess;

    public TenantService(JdbcTemplate jdbc, TransactionTemplate transaction, TelegramAccessPolicy telegramAccess) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.telegramAccess = telegramAccess;
    }

    public TenantResponse create(String subject, boolean emailVerified, CreateTenantRequest request) {
        requireVerifiedEmail(subject, emailVerified);
        return create(subject, request);
    }

    public TenantResponse create(String subject, CreateTenantRequest request) {
        String displayName = request == null || request.displayName() == null ? "" : request.displayName().trim();
        boolean memberNameChosen = request != null && request.memberDisplayName() != null;
        String memberDisplayName = request == null || request.memberDisplayName() == null
                ? displayName : request.memberDisplayName().trim();
        BigDecimal plannedIncome = request == null ? null : request.plannedIncome();
        String timezone = request == null || request.timezone() == null ? "UTC" : request.timezone().trim();
        if (subject == null || subject.isBlank() || displayName.isEmpty() || displayName.length() > 120
                || memberDisplayName.isEmpty() || memberDisplayName.length() > 120
                || plannedIncome != null && (plannedIncome.signum() <= 0 || plannedIncome.scale() > 2 || plannedIncome.precision() > 20)
                || timezone.isEmpty() || timezone.length() > 64 || !timezone.matches("[A-Za-z0-9_+./-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tenant profile");
        }
        try {
            timezone = ZoneId.of(timezone).getId();
        } catch (DateTimeException invalidTimezone) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timezone", invalidTimezone);
        }
        String validatedTimezone = timezone;
        String validatedMemberName = memberDisplayName;
        return transaction.execute(status -> {
            attachConfiguredTelegramIdentity(subject);
            UUID userId = identityFor(subject);
            UUID tenantId = UUID.randomUUID();
            setTenantContext(tenantId);
            jdbc.update("INSERT INTO tenants (id, display_name) VALUES (?, ?)", tenantId, displayName);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, user_id, role) VALUES (?, ?, ?, 'owner')",
                    tenantId, subject, userId);
            jdbc.update("INSERT INTO member_profiles (tenant_id, user_id, display_name, display_name_source, planned_income, timezone, onboarding_state) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    tenantId, userId, validatedMemberName,
                    memberNameChosen ? "user" : "workspace_default", plannedIncome, validatedTimezone,
                    plannedIncome == null ? "started" : "complete");
            return new TenantResponse(tenantId, displayName, "owner", validatedTimezone, userId);
        });
    }

    public List<TenantResponse> list(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return transaction.execute(status -> {
            attachConfiguredTelegramIdentity(subject);
            jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
            UUID userId = existingIdentity(subject);
            if (userId == null) return List.of();
            jdbc.queryForObject("SELECT set_config('app.user_id', ?, true)", String.class, userId.toString());
            List<Membership> memberships = jdbc.query(
                    "SELECT tenant_id, user_id, role FROM memberships WHERE user_id = ? AND status = 'active' ORDER BY created_at",
                    (rs, row) -> new Membership(rs.getObject("tenant_id", UUID.class),
                            rs.getObject("user_id", UUID.class), rs.getString("role")), userId);
            List<TenantResponse> result = new ArrayList<>();
            for (Membership membership : memberships) {
                setTenantContext(membership.tenantId());
                result.addAll(jdbc.query("SELECT t.display_name, COALESCE(p.timezone, 'UTC') AS timezone "
                                + "FROM tenants t LEFT JOIN member_profiles p ON p.tenant_id = t.id "
                                + "AND p.user_id = ? "
                                + "WHERE t.id = ?",
                        (rs, row) -> new TenantResponse(membership.tenantId(), rs.getString("display_name"),
                                membership.role(), rs.getString("timezone"), membership.userId()), userId, membership.tenantId()));
            }
            return result;
        });
    }

    public List<TenantResponse> list(String subject, boolean emailVerified) {
        requireVerifiedEmail(subject, emailVerified);
        return list(subject);
    }

    private void attachConfiguredTelegramIdentity(String subject) {
        if (subject == null || subject.isBlank()) return;
        Long telegramId = telegramAccess.telegramUserIdForAccount(subject).orElse(null);
        if (telegramId == null) return;
        telegramAccess.requireAllowed(telegramId);
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, telegramId);
        List<LinkedUser> linked = jdbc.query("""
                SELECT identity.user_id, users.status FROM external_identities identity
                JOIN users ON users.id = identity.user_id
                WHERE identity.provider = 'telegram' AND identity.subject = ?
                """, (rs, row) -> new LinkedUser(rs.getObject("user_id", UUID.class), rs.getString("status")),
                Long.toString(telegramId));
        if (linked.isEmpty()) return; // Android registered first; Telegram will bind when first used.
        LinkedUser target = linked.get(0);
        if (!"active".equals(target.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Telegram account is inactive");
        }
        UUID subjectUser = existingIdentity(subject);
        if (subjectUser != null && !subjectUser.equals(target.userId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Android identity belongs to another account");
        }
        List<String> otherSubjects = jdbc.query("""
                SELECT subject FROM external_identities WHERE provider='keycloak' AND user_id=?
                """, (rs, row) -> rs.getString(1), target.userId());
        if (!otherSubjects.isEmpty() && !otherSubjects.get(0).equals(subject)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Telegram account already has an Android identity");
        }
        if (subjectUser == null) {
            int inserted = jdbc.update("""
                    INSERT INTO external_identities (user_id, provider, subject)
                    VALUES (?, 'keycloak', ?) ON CONFLICT DO NOTHING
                    """, target.userId(), subject);
            if (inserted != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Android identity attachment conflicts");
            }
        }
    }

    private UUID existingIdentity(String subject) {
        List<UUID> identities = jdbc.query("""
                SELECT identity.user_id FROM external_identities identity
                JOIN users ON users.id=identity.user_id AND users.status='active'
                WHERE identity.provider='keycloak' AND identity.subject=?
                """, (rs, row) -> rs.getObject(1, UUID.class), subject);
        return identities.isEmpty() ? null : identities.get(0);
    }

    private void requireVerifiedEmail(String subject, boolean verified) {
        if (!verified && telegramAccess.telegramUserIdForAccount(subject).isPresent()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email verification is required");
        }
    }

    private record LinkedUser(UUID userId, String status) {}

    private UUID identityFor(String subject) {
        List<UUID> existing = jdbc.query("SELECT user_id FROM external_identities WHERE provider = 'keycloak' AND subject = ?",
                (rs, row) -> rs.getObject(1, UUID.class), subject);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        UUID userId = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
        jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?) "
                + "ON CONFLICT (provider, subject) DO NOTHING", userId, subject);
        return jdbc.queryForObject("SELECT user_id FROM external_identities WHERE provider = 'keycloak' AND subject = ?",
                UUID.class, subject);
    }

    private void setTenantContext(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private record Membership(UUID tenantId, UUID userId, String role) {}
}
