package com.decorix.finance.core.api;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Server-owned access list and explicit operator mappings for Telegram identities. */
@Component
public final class TelegramAccessPolicy {
    private final Set<Long> allowedUserIds;
    private final Map<Long, String> accountSubjects;

    public TelegramAccessPolicy(
            @Value("${finance.telegram.allowed-user-ids:}") String configuredIds,
            @Value("${finance.telegram.account-mappings:}") String configuredMappings) {
        this.allowedUserIds = parseIds(configuredIds);
        this.accountSubjects = parseMappings(configuredMappings, allowedUserIds);
    }

    public boolean isAllowed(Long telegramUserId) {
        return telegramUserId != null && allowedUserIds.contains(telegramUserId);
    }

    public void requireAllowed(Long telegramUserId) {
        if (!isAllowed(telegramUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Telegram account is not allowlisted");
        }
    }

    public Optional<String> accountSubject(Long telegramUserId) {
        return Optional.ofNullable(accountSubjects.get(telegramUserId));
    }

    public Optional<Long> telegramUserIdForAccount(String keycloakSubject) {
        if (keycloakSubject == null) return Optional.empty();
        return accountSubjects.entrySet().stream()
                .filter(entry -> entry.getValue().equals(keycloakSubject))
                .map(Map.Entry::getKey).findFirst();
    }

    static Set<Long> parseIds(String configuredIds) {
        Set<Long> result = new LinkedHashSet<>();
        if (configuredIds == null || configuredIds.isBlank()) return Set.of();
        for (String raw : configuredIds.split(",", -1)) {
            String value = raw.trim();
            if (!value.matches("[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException("Telegram allowlist contains a malformed numeric ID");
            }
            final long id;
            try {
                id = Long.parseLong(value);
            } catch (NumberFormatException invalidId) {
                throw new IllegalArgumentException("Telegram allowlist ID is outside the supported range", invalidId);
            }
            if (!result.add(id)) throw new IllegalArgumentException("Telegram allowlist contains a duplicate ID");
        }
        return Set.copyOf(result);
    }

    static Map<Long, String> parseMappings(String configuredMappings, Set<Long> allowedUserIds) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (configuredMappings == null || configuredMappings.isBlank()) return Map.of();
        for (String raw : configuredMappings.split(",", -1)) {
            String entry = raw.trim();
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator != entry.lastIndexOf('=')) {
                throw new IllegalArgumentException("Telegram account mapping must use telegramId=keycloakSubject");
            }
            String idPart = entry.substring(0, separator).trim();
            String subject = entry.substring(separator + 1).trim();
            Set<Long> parsedId = parseIds(idPart);
            if (parsedId.size() != 1) throw new IllegalArgumentException("Telegram account mapping ID is invalid");
            long telegramUserId = parsedId.iterator().next();
            if (!allowedUserIds.contains(telegramUserId)) {
                throw new IllegalArgumentException("Telegram account mapping must also be allowlisted");
            }
            if (subject.isEmpty() || subject.length() > 255 || subject.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Telegram account mapping subject is invalid");
            }
            if (result.putIfAbsent(telegramUserId, subject) != null) {
                throw new IllegalArgumentException("Telegram account mappings contain a duplicate ID");
            }
            if (result.entrySet().stream().anyMatch(mapping -> mapping.getKey() != telegramUserId
                    && mapping.getValue().equals(subject))) {
                throw new IllegalArgumentException("Telegram account mappings contain a duplicate Android subject");
            }
        }
        return Map.copyOf(result);
    }
}
