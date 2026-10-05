package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TelegramActorContextApi.ActorContext;
import com.decorix.finance.core.api.TelegramActorContextApi.ActorContextResponse;
import com.decorix.finance.core.api.TelegramActorContextApi.IssueRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.ResolvedContextResponse;
import com.decorix.finance.core.api.TelegramActorContextApi.TenantOption;
import com.decorix.finance.core.api.TelegramActorContextApi.TenantListResponse;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TelegramActorContextService {
    private static final Duration CONTEXT_TTL = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MemberProfileService memberProfiles;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    public TelegramActorContextService(JdbcTemplate jdbc, TransactionTemplate transactions,
                                       MemberProfileService memberProfiles) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.memberProfiles = memberProfiles;
    }

    public TenantListResponse listTenants(Long telegramUserId, String telegramDisplayName) {
        validateTelegramUserId(telegramUserId);
        return transactions.execute(status -> {
            enableServiceContext();
            UUID userId = activeLinkedUser(telegramUserId);
            memberProfiles.syncTelegramDisplayName(userId, telegramDisplayName);
            List<Membership> memberships = jdbc.query("""
                    SELECT tenant_id, role FROM memberships
                    WHERE user_id = ? AND status = 'active' ORDER BY created_at, tenant_id
                    """, (rs, row) -> new Membership(rs.getObject("tenant_id", UUID.class), rs.getString("role")),
                    userId);
            List<TenantOption> tenants = new ArrayList<>(memberships.size());
            for (Membership membership : memberships) {
                setTenantContext(membership.tenantId());
                String displayName = jdbc.queryForObject("SELECT display_name FROM tenants WHERE id = ?",
                        String.class, membership.tenantId());
                tenants.add(new TenantOption(membership.tenantId(), displayName, membership.role()));
            }
            return new TenantListResponse(List.copyOf(tenants));
        });
    }

    public ActorContextResponse issue(IssueRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Actor context is required");
        validateTelegramUserId(request.telegramUserId());
        if (request.tenantId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant is required");
        return transactions.execute(status -> {
            enableServiceContext();
            UUID userId = activeLinkedUser(request.telegramUserId());
            setTenantContext(request.tenantId());
            List<String> roles = jdbc.query("""
                    SELECT role FROM memberships WHERE tenant_id = ? AND user_id = ? AND status = 'active'
                    """, (rs, row) -> rs.getString("role"), request.tenantId(), userId);
            if (roles.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not available");
            String role = roles.get(0);
            Set<String> permissions;
            try {
                permissions = TelegramActorContextPolicy.permissionsForRole(role);
            } catch (IllegalArgumentException invalidRole) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Membership role is not supported", invalidRole);
            }
            String displayName = jdbc.queryForObject("SELECT display_name FROM tenants WHERE id = ?",
                    String.class, request.tenantId());
            Instant now = clock.instant();
            Instant expiresAt = now.plus(CONTEXT_TTL);
            jdbc.update("UPDATE telegram_actor_contexts SET revoked_at = ? "
                    + "WHERE telegram_user_id = ? AND revoked_at IS NULL", Timestamp.from(now),
                    request.telegramUserId());
            for (int attempt = 0; attempt < 3; attempt++) {
                String token = generateToken();
                int inserted = jdbc.update("""
                        INSERT INTO telegram_actor_contexts
                          (context_hash, telegram_user_id, tenant_id, user_id, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (context_hash) DO NOTHING
                        """, hash(token), request.telegramUserId(), request.tenantId(), userId,
                        Timestamp.from(now), Timestamp.from(expiresAt));
                if (inserted == 1) {
                    return new ActorContextResponse(token, request.tenantId(), displayName, role,
                            permissions.stream().sorted().toList(), expiresAt);
                }
            }
            throw new IllegalStateException("Unable to generate a unique Telegram actor context");
        });
    }

    public ResolvedContextResponse resolveResponse(String token) {
        ActorContext actor = require(token, null);
        String displayName = transactions.execute(status -> {
            enableServiceContext();
            setTenantContext(actor.tenantId());
            return jdbc.queryForObject("SELECT display_name FROM tenants WHERE id = ?", String.class, actor.tenantId());
        });
        return new ResolvedContextResponse(actor.tenantId(), displayName, actor.role(),
                actor.permissions().stream().sorted().toList(), actor.expiresAt());
    }

    /** Revalidate binding, active membership, current role, and the requested permission before a Telegram action. */
    public ActorContext require(String token, String requiredPermission) {
        if (token == null || token.isBlank() || token.length() > 128) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Telegram actor context is invalid");
        }
        return transactions.execute(status -> {
            enableServiceContext();
            String contextHash = hash(token);
            List<StoredContext> stored = jdbc.query("""
                    SELECT telegram_user_id, tenant_id, user_id, expires_at
                    FROM telegram_actor_contexts
                    WHERE context_hash = ? AND revoked_at IS NULL AND expires_at > ?
                    """, (rs, row) -> new StoredContext(rs.getLong("telegram_user_id"),
                    rs.getObject("tenant_id", UUID.class), rs.getObject("user_id", UUID.class),
                    rs.getTimestamp("expires_at").toInstant()), contextHash, Timestamp.from(clock.instant()));
            if (stored.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Telegram actor context expired");
            StoredContext context = stored.get(0);
            setTenantContext(context.tenantId());
            List<ActorIdentity> identities = jdbc.query("""
                    SELECT keycloak.subject, membership.role
                    FROM external_identities telegram
                    JOIN users ON users.id = telegram.user_id AND users.status = 'active'
                    JOIN external_identities keycloak ON keycloak.user_id = users.id AND keycloak.provider = 'keycloak'
                    JOIN memberships membership ON membership.tenant_id = ? AND membership.user_id = users.id
                      AND membership.status = 'active'
                    WHERE telegram.provider = 'telegram' AND telegram.subject = ? AND users.id = ?
                    """, (rs, row) -> new ActorIdentity(rs.getString("subject"), rs.getString("role")),
                    context.tenantId(), Long.toString(context.telegramUserId()), context.userId());
            if (identities.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Telegram membership is inactive");
            ActorIdentity identity = identities.get(0);
            Set<String> permissions;
            try {
                permissions = TelegramActorContextPolicy.permissionsForRole(identity.role());
            } catch (IllegalArgumentException invalidRole) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Membership role is not supported", invalidRole);
            }
            if (requiredPermission != null && !permissions.contains(requiredPermission)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Telegram action is not allowed");
            }
            return new ActorContext(context.telegramUserId(), context.tenantId(), context.userId(),
                    identity.keycloakSubject(), identity.role(), permissions, context.expiresAt());
        });
    }

    private UUID activeLinkedUser(Long telegramUserId) {
        List<UUID> users = jdbc.query("""
                SELECT identities.user_id FROM external_identities identities
                JOIN users ON users.id = identities.user_id
                WHERE identities.provider = 'telegram' AND identities.subject = ? AND users.status = 'active'
                """, (rs, row) -> rs.getObject("user_id", UUID.class), Long.toString(telegramUserId));
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Linked Telegram user not found");
        return users.get(0);
    }

    private void enableServiceContext() {
        jdbc.queryForObject("SELECT set_config('app.telegram_actor_service', 'true', true)", String.class);
    }

    private void setTenantContext(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private static void validateTelegramUserId(Long telegramUserId) {
        if (telegramUserId == null || telegramUserId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Telegram user ID is invalid");
        }
    }

    private String generateToken() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Membership(UUID tenantId, String role) {}
    private record StoredContext(long telegramUserId, UUID tenantId, UUID userId, Instant expiresAt) {}
    private record ActorIdentity(String keycloakSubject, String role) {}
}
