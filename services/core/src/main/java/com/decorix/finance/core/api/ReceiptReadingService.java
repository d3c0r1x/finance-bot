package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ReceiptReadingApi.ReceiptOcrReading;
import com.decorix.finance.core.api.ReceiptReadingApi.ReceiptItemEvidence;
import com.decorix.finance.core.api.ReceiptReadingApi.ReceiptLineItem;
import com.decorix.finance.core.api.ReceiptReadingApi.ReceiptReconciliation;
import com.decorix.finance.core.api.ReceiptReadingApi.ReceiptTopUpSuggestion;
import com.decorix.finance.core.api.ReceiptReadingApi.VisionReceiptReading;
import com.decorix.finance.core.domain.ReceiptReconciliationPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReceiptReadingService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    public ReceiptReadingService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
    }

    public ReceiptOcrReading get(UUID tenantId, String subject, UUID receiptId) {
        return transaction.execute(status -> {
            if (tenantId == null || subject == null || subject.isBlank()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt reading not found");
            }
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            List<UUID> users = jdbc.query("""
                    SELECT m.user_id FROM memberships m
                    JOIN external_identities i ON i.user_id = m.user_id
                    WHERE m.tenant_id = ? AND m.status = 'active' AND i.provider = 'keycloak' AND i.subject = ?
                    """, (rs, row) -> rs.getObject("user_id", UUID.class), tenantId, subject);
            if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt reading not found");
            List<ReceiptOcrReadingRow> rows = jdbc.query("""
                    SELECT o.result_fields ->> 'text' AS text, o.result_fields -> 'words' AS words,
                           o.provider AS ocr_provider, o.model_version AS ocr_model, o.prompt_version AS ocr_prompt,
                           o.confidence AS ocr_confidence,
                           o.result_fields ->> 'total' AS ocr_total, o.result_fields -> 'items'::text AS ocr_items,
                           o.field_evidence ->> 'visionFallbackReason' AS ocr_vision_fallback_reason,
                           coalesce(o.field_evidence ->> 'ocrFallbackReason',
                                    v.field_evidence ->> 'ocrFallbackReason') AS ocr_fallback_reason,
                           v.result_fields::text AS vision_fields, v.provider AS vision_provider,
                           v.model_version AS vision_model, v.prompt_version AS vision_prompt,
                           v.field_evidence ->> 'fallbackReason' AS vision_fallback_reason
                    FROM receipts r
                    LEFT JOIN LATERAL (
                        SELECT * FROM receipt_readings rr
                        WHERE rr.tenant_id = r.tenant_id AND rr.receipt_id = r.id AND rr.reader = 'ocr'
                        ORDER BY rr.created_at DESC, rr.id DESC LIMIT 1
                    ) o ON true
                    LEFT JOIN LATERAL (
                        SELECT * FROM receipt_readings rr
                        WHERE rr.tenant_id = r.tenant_id AND rr.receipt_id = r.id AND rr.reader = 'vision'
                        ORDER BY rr.created_at DESC, rr.id DESC LIMIT 1
                    ) v ON true
                    WHERE r.tenant_id = ? AND r.owner_user_id = ? AND r.id = ?
                      AND (o.id IS NOT NULL OR v.id IS NOT NULL)
                    """, (rs, row) -> new ReceiptOcrReadingRow(rs.getString("text"), rs.getString("words"),
                    rs.getString("ocr_provider"), rs.getString("ocr_model"), rs.getString("ocr_prompt"),
                    rs.getBigDecimal("ocr_confidence"), rs.getString("ocr_total"), rs.getString("ocr_items"),
                    rs.getString("ocr_vision_fallback_reason"),
                    rs.getString("ocr_fallback_reason"), rs.getString("vision_fields"), rs.getString("vision_provider"),
                    rs.getString("vision_model"), rs.getString("vision_prompt"), rs.getString("vision_fallback_reason")),
                    tenantId, users.get(0), receiptId);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt reading not found");
            ReceiptOcrReadingRow value = rows.get(0);
            try {
                Object decoded = json.readValue(value.words() == null ? "[]" : value.words(), Object.class);
                if (!(decoded instanceof List<?> list)) throw new IllegalStateException("Receipt word evidence is invalid");
                List<Map<String, Object>> words = list.stream().map(item -> {
                    if (!(item instanceof Map<?, ?> map)) throw new IllegalStateException("Receipt word evidence is invalid");
                    @SuppressWarnings("unchecked") Map<String, Object> word = (Map<String, Object>) map;
                    return word;
                }).toList();
                List<ReceiptLineItem> ocrItems = parseOcrItems(value.ocrItems());
                VisionReceiptReading vision = parseVision(value);
                ReceiptReconciliation reconciliation = reconcile(value, ocrItems, vision);
                return new ReceiptOcrReading(value.text() == null ? "" : value.text(), words, value.ocrProvider(),
                        value.ocrModel(), value.ocrPrompt(), value.ocrConfidence(), value.ocrTotal(), ocrItems,
                        reconciliation, value.visionExecutionFallbackReason(), value.ocrFallbackReason(), vision);
            } catch (JacksonException error) {
                throw new IllegalStateException("Receipt word evidence could not be read", error);
            }
        });
    }

    private VisionReceiptReading parseVision(ReceiptOcrReadingRow value) throws JacksonException {
        if (value.visionFields() == null) return null;
        Object decoded = json.readValue(value.visionFields(), Object.class);
        if (!(decoded instanceof Map<?, ?> fields) || !(fields.get("items") instanceof List<?> rawItems)) {
            throw new IllegalStateException("Receipt Vision evidence is invalid");
        }
        List<Map<String, Object>> items = rawItems.stream().map(item -> {
            if (!(item instanceof Map<?, ?> map)) throw new IllegalStateException("Receipt Vision item evidence is invalid");
            @SuppressWarnings("unchecked") Map<String, Object> normalized = (Map<String, Object>) map;
            return normalized;
        }).toList();
        return new VisionReceiptReading(emptyToNull(fields.get("store")), emptyToNull(fields.get("date")),
                emptyToNull(fields.get("total")), items, value.visionProvider(), value.visionModel(),
                value.visionPrompt(), value.visionFallbackReason());
    }

    private List<ReceiptLineItem> parseOcrItems(String encoded) throws JacksonException {
        if (encoded == null) return List.of();
        Object decoded = json.readValue(encoded, Object.class);
        if (!(decoded instanceof List<?> rawItems)) throw new IllegalStateException("Receipt OCR items are invalid");
        return rawItems.stream().map(item -> {
            if (!(item instanceof Map<?, ?> fields)
                    || !(fields.get("name") instanceof String name)) {
                throw new IllegalStateException("Receipt OCR item is invalid");
            }
            return new ReceiptLineItem(name, emptyToNull(fields.get("quantity")),
                    emptyToNull(fields.get("unitPrice")), emptyToNull(fields.get("lineSum")));
        }).toList();
    }

    private static ReceiptReconciliation reconcile(ReceiptOcrReadingRow row, List<ReceiptLineItem> ocrItems,
            VisionReceiptReading vision) {
        ReceiptReconciliationPolicy.Reading ocr = row.ocrProvider() == null ? null
                : new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.OCR,
                        decimal(row.ocrTotal()), null, null, ocrItems.stream()
                                .map(item -> new ReceiptReconciliationPolicy.Item(item.name(), decimal(item.lineSum())))
                                .toList());
        ReceiptReconciliationPolicy.Reading visual = vision == null ? null
                : new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.VISION,
                        decimal(vision.total()), null, null, vision.items().stream()
                                .map(item -> new ReceiptReconciliationPolicy.Item((String) item.get("name"),
                                        decimal(item.get("lineSum"))))
                                .toList());
        ReceiptReconciliationPolicy.Result result = ReceiptReconciliationPolicy.evaluate(ocr, visual);
        return new ReceiptReconciliation(result.algorithmVersion(), result.decision().name().toLowerCase(),
                result.selectedReader() == null ? null : result.selectedReader().name().toLowerCase(),
                result.mismatchFields(), text(result.ocrItemsTotal()), text(result.visionItemsTotal()),
                text(result.allowedDifference()), result.ocrItemsReconciled(), result.visionItemsReconciled(),
                result.itemEvidence().stream().map(item -> new ReceiptItemEvidence(item.visionOrdinal(),
                        item.ocrOrdinal(), item.status().name().toLowerCase())).toList(),
                result.suggestedTopUps().stream().map(item -> new ReceiptTopUpSuggestion(item.ocrOrdinal(),
                        item.name(), text(item.lineSum()))).toList());
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("Stored receipt amount is invalid", invalid); }
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String emptyToNull(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        return text;
    }

    private record ReceiptOcrReadingRow(String text, String words, String ocrProvider, String ocrModel,
            String ocrPrompt, java.math.BigDecimal ocrConfidence, String ocrTotal, String ocrItems,
            String visionExecutionFallbackReason, String ocrFallbackReason, String visionFields, String visionProvider,
            String visionModel, String visionPrompt, String visionFallbackReason) {}
}
