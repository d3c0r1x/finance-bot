package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TelegramLinkApi.LinkCodeResponse;
import com.decorix.finance.core.api.TelegramLinkApi.RedeemRequest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TelegramLinkService {
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration ISSUE_WINDOW = Duration.ofMinutes(1);
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private static final Duration BLOCK_DURATION = Duration.ofMinutes(15);
    private static final int MAX_ISSUES_PER_WINDOW = 3;
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MemberProfileService memberProfiles;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    public TelegramLinkService(JdbcTemplate jdbc, TransactionTemplate transactions, MemberProfileService memberProfiles) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.memberProfiles = memberProfiles;
    }

    public LinkCodeResponse createCode(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
            List<UUID> userIds = jdbc.query("""
                    SELECT u.id FROM users u
                    JOIN external_identities i ON i.user_id = u.id AND i.provider = 'keycloak'
                    WHERE i.subject = ? AND u.status = 'active'
                      AND EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = u.id AND m.status = 'active')
                    FOR UPDATE OF u
                    """, (rs, row) -> rs.getObject("id", UUID.class), subject);
            if (userIds.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active membership required");
            }
            UUID userId = userIds.get(0);
            Instant now = clock.instant();
            Integer issued = jdbc.queryForObject("""
                    SELECT count(*) FROM telegram_link_codes
                    WHERE user_id = ? AND created_at >= ?
                    """, Integer.class, userId, Timestamp.from(now.minus(ISSUE_WINDOW)));
            if (issued != null && issued >= MAX_ISSUES_PER_WINDOW) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Link code issue limit reached");
            }
            jdbc.update("UPDATE telegram_link_codes SET invalidated_at = now() "
                    + "WHERE user_id = ? AND consumed_at IS NULL AND invalidated_at IS NULL", userId);

            Instant expiresAt = now.plus(CODE_TTL);
            for (int attempt = 0; attempt < 3; attempt++) {
                String code = TelegramLinkCode.generate(random);
                String codeHash = TelegramLinkCode.hash(code);
                int inserted = jdbc.update("INSERT INTO telegram_link_codes (code_hash, user_id, created_at, expires_at) "
                                + "VALUES (?, ?, ?, ?) ON CONFLICT (code_hash) DO NOTHING",
                        codeHash, userId, Timestamp.from(now), Timestamp.from(expiresAt));
                if (inserted == 1) return new LinkCodeResponse(code, expiresAt);
            }
            throw new IllegalStateException("Unable to generate a unique Telegram link code");
        });
    }

    public RedemptionResult redeem(RedeemRequest request) {
        if (request == null || request.telegramUserId() == null || request.telegramUserId() <= 0) {
            return RedemptionResult.INVALID;
        }
        String codeHash;
        try {
            codeHash = TelegramLinkCode.hash(request.code());
        } catch (IllegalArgumentException invalidCode) {
            return recordFailure(request.telegramUserId());
        }
        return transactions.execute(status -> {
            enableLinkServiceContext();
            long telegramUserId = request.telegramUserId();
            jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, telegramUserId);
            AttemptState attempt = lockAttemptState(telegramUserId);
            Instant now = clock.instant();
            if (attempt.blockedUntil() != null && attempt.blockedUntil().isAfter(now)) {
                return RedemptionResult.RATE_LIMITED;
            }
            List<UUID> telegramIdentity = jdbc.query("""
                    SELECT user_id FROM external_identities WHERE provider = 'telegram' AND subject = ?
                    """, (rs, row) -> rs.getObject("user_id", UUID.class), Long.toString(telegramUserId));
            if (!telegramIdentity.isEmpty()) {
                return RedemptionResult.CONFLICT;
            }

            List<LinkCodeRow> codes = jdbc.query("""
                    SELECT user_id, expires_at, consumed_at, invalidated_at
                    FROM telegram_link_codes WHERE code_hash = ? FOR UPDATE
                    """, (rs, row) -> new LinkCodeRow(rs.getObject("user_id", UUID.class),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                    rs.getTimestamp("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant()),
                    codeHash);
            if (codes.isEmpty() || !codes.get(0).expiresAt().isAfter(now)
                    || codes.get(0).consumedAt() != null || codes.get(0).invalidatedAt() != null) {
                return failAttempt(telegramUserId, attempt, now);
            }

            UUID userId = codes.get(0).userId();
            List<String> activeUsers = jdbc.query("SELECT status FROM users WHERE id = ? FOR UPDATE",
                    (rs, row) -> rs.getString("status"), userId);
            if (activeUsers.isEmpty() || !"active".equals(activeUsers.get(0))) {
                return RedemptionResult.CONFLICT;
            }
            Integer alreadyLinked = jdbc.queryForObject("""
                    SELECT count(*) FROM external_identities WHERE provider = 'telegram' AND user_id = ?
                    """, Integer.class, userId);
            if (alreadyLinked != null && alreadyLinked > 0) {
                return RedemptionResult.CONFLICT;
            }
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'telegram', ?)",
                    userId, Long.toString(telegramUserId));
            memberProfiles.syncTelegramDisplayName(userId, request.telegramDisplayName());
            jdbc.update("UPDATE telegram_link_codes SET consumed_at = ? WHERE code_hash = ?", Timestamp.from(now), codeHash);
            jdbc.update("DELETE FROM telegram_link_attempts WHERE telegram_user_id = ?", telegramUserId);
            return RedemptionResult.LINKED;
        });
    }

    private RedemptionResult recordFailure(long telegramUserId) {
        return transactions.execute(status -> {
            enableLinkServiceContext();
            AttemptState attempt = lockAttemptState(telegramUserId);
            Instant now = clock.instant();
            if (attempt.blockedUntil() != null && attempt.blockedUntil().isAfter(now)) {
                return RedemptionResult.RATE_LIMITED;
            }
            return failAttempt(telegramUserId, attempt, now);
        });
    }

    private void enableLinkServiceContext() {
        jdbc.queryForObject("SELECT set_config('app.telegram_link_service', 'true', true)", String.class);
    }

    private AttemptState lockAttemptState(long telegramUserId) {
        jdbc.update("INSERT INTO telegram_link_attempts (telegram_user_id) VALUES (?) "
                + "ON CONFLICT (telegram_user_id) DO NOTHING", telegramUserId);
        return jdbc.queryForObject("""
                SELECT window_started_at, failed_attempts, blocked_until
                FROM telegram_link_attempts WHERE telegram_user_id = ? FOR UPDATE
                """, (rs, row) -> new AttemptState(rs.getTimestamp("window_started_at").toInstant(),
                rs.getInt("failed_attempts"), rs.getTimestamp("blocked_until") == null
                ? null : rs.getTimestamp("blocked_until").toInstant()), telegramUserId);
    }

    private RedemptionResult failAttempt(long telegramUserId, AttemptState attempt, Instant now) {
        Instant windowStarted = attempt.windowStartedAt();
        int failedAttempts = attempt.failedAttempts();
        if (!windowStarted.plus(ATTEMPT_WINDOW).isAfter(now)) {
            windowStarted = now;
            failedAttempts = 0;
        }
        failedAttempts = Math.min(MAX_FAILED_ATTEMPTS, failedAttempts + 1);
        Instant blockedUntil = failedAttempts >= MAX_FAILED_ATTEMPTS ? now.plus(BLOCK_DURATION) : null;
        jdbc.update("""
                UPDATE telegram_link_attempts
                SET window_started_at = ?, failed_attempts = ?, blocked_until = ?, updated_at = ?
                WHERE telegram_user_id = ?
                """, Timestamp.from(windowStarted), failedAttempts,
                blockedUntil == null ? null : Timestamp.from(blockedUntil), Timestamp.from(now), telegramUserId);
        return RedemptionResult.INVALID;
    }

    private record LinkCodeRow(UUID userId, Instant expiresAt, Instant consumedAt, Instant invalidatedAt) {}
    private record AttemptState(Instant windowStartedAt, int failedAttempts, Instant blockedUntil) {}

    public enum RedemptionResult { LINKED, INVALID, CONFLICT, RATE_LIMITED }
}
