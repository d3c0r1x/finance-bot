package com.decorix.finance.core.api;

import com.decorix.finance.core.api.MerchantMappingApi.Mapping;
import com.decorix.finance.core.api.MerchantMappingApi.MappingRequest;
import com.decorix.finance.core.domain.MerchantCategoryPolicy;
import java.text.Normalizer;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MerchantMappingService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public MerchantMappingService(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public List<Mapping> list(UUID tenantId, String subject) {
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, false);
            return listForMember(tenantId, actor.userId());
        });
    }

    public Mapping save(UUID tenantId, String subject, MappingRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Merchant category is required");
        final String label;
        final String normalized;
        try {
            label = displayLabel(request.merchant());
            normalized = MerchantCategoryPolicy.normalizeMerchant(label);
            if (!MerchantCategoryPolicy.CATEGORIES.contains(request.categoryCode())) {
                throw new IllegalArgumentException("merchant category is invalid");
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Merchant or category is invalid", exception);
        }
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, true);
            return saveForMember(tenantId, actor.userId(), label, normalized, request.categoryCode());
        });
    }

    List<Mapping> listForMember(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT label, normalized_merchant, category_code, decision_source, decision_version, updated_at::text
                FROM merchant_mappings WHERE tenant_id = ? AND user_id = ?
                ORDER BY normalized_merchant
                """, (rs, row) -> new Mapping(rs.getString("label"), rs.getString("normalized_merchant"),
                rs.getString("category_code"), rs.getString("decision_source"), rs.getString("decision_version"),
                rs.getString("updated_at")), tenantId, userId);
    }

    Mapping saveForMember(UUID tenantId, UUID userId, String label, String normalized, String categoryCode) {
        MerchantCategoryLocks.acquire(jdbc, tenantId, userId, normalized);
        jdbc.update("""
                INSERT INTO merchant_mappings (tenant_id, user_id, normalized_merchant, label, category_code,
                    decision_source, decision_version)
                VALUES (?, ?, ?, ?, ?, 'human', ?)
                ON CONFLICT (tenant_id, user_id, normalized_merchant) DO UPDATE SET
                    label = EXCLUDED.label, category_code = EXCLUDED.category_code,
                    decision_source = 'human', decision_version = EXCLUDED.decision_version, updated_at = now()
                """, tenantId, userId, normalized, label, categoryCode, MerchantCategoryPolicy.ALGORITHM_VERSION);
        return jdbc.queryForObject("""
                SELECT label, normalized_merchant, category_code, decision_source, decision_version, updated_at::text
                FROM merchant_mappings WHERE tenant_id = ? AND user_id = ? AND normalized_merchant = ?
                """, (rs, row) -> new Mapping(rs.getString("label"), rs.getString("normalized_merchant"),
                rs.getString("category_code"), rs.getString("decision_source"), rs.getString("decision_version"),
                rs.getString("updated_at")), tenantId, userId, normalized);
    }

    private Actor actor(UUID tenantId, String subject, boolean write) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
        List<Actor> actors = jdbc.query("""
                SELECT user_id, role FROM memberships
                WHERE tenant_id = ? AND subject = ? AND status = 'active'
                """, (rs, row) -> new Actor(rs.getObject("user_id", UUID.class), rs.getString("role")), tenantId, subject);
        if (actors.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        Actor actor = actors.get(0);
        if (write && "viewer".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Viewer access is read-only");
        }
        return actor;
    }

    private static String displayLabel(String merchant) {
        if (merchant == null) throw new IllegalArgumentException("merchant is required");
        String label = Normalizer.normalize(merchant, Normalizer.Form.NFKC).replaceAll("\\s+", " ").strip();
        if (label.isBlank() || label.length() > 200) throw new IllegalArgumentException("merchant label is invalid");
        return label;
    }

    private record Actor(UUID userId, String role) {}
}
