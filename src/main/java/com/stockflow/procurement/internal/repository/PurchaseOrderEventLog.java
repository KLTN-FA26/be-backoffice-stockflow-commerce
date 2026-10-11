package com.stockflow.procurement.internal.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * The one writer of {@code procurement.purchase_order_events}, the order's append-only timeline
 * ({@code tg_purchase_order_events_append_only}). Native SQL on purpose: an event is inserted once and
 * never read back through JPA, so an entity would only add a mapping to keep in step with the table.
 *
 * <p>The purchase order itself, its receiving view and the subcontract store all write here, so the
 * columns and the reason limit are stated once.</p>
 */
@Component
class PurchaseOrderEventLog {

    /** {@code purchase_order_events.reason VARCHAR(500)}. */
    private static final int REASON_MAX = 500;

    private final ObjectMapper json;

    @PersistenceContext
    private EntityManager entityManager;

    PurchaseOrderEventLog(ObjectMapper json) {
        this.json = json;
    }

    /**
     * @param source   who wrote the row ({@code created_by}): the flow, not the user — the user is
     *                 {@code actorId}
     * @param payload  extra facts for the timeline (a receipt's id and number), or {@code null}
     */
    void record(UUID purchaseOrderId, UUID revisionId, String action, UUID actorId, String fromStatus,
                String toStatus, String reason, Map<String, String> payload, String source) {
        String sql = """
                INSERT INTO procurement.purchase_order_events
                       (id, po_id, po_revision_id, action, actor_id, from_status, to_status, reason, payload,
                        created_at, created_by)
                VALUES (:id, :po, :revision, :action, :actor, :from, :to, :reason, %s, NOW(), :source)"""
                .formatted(payload == null ? "NULL" : "CAST(:payload AS jsonb)");
        var query = entityManager.createNativeQuery(sql)
                .setParameter("id", Identifiers.newId())
                .setParameter("po", purchaseOrderId)
                .setParameter("revision", revisionId)
                .setParameter("action", action)
                .setParameter("actor", actorId)
                .setParameter("from", fromStatus)
                .setParameter("to", toStatus)
                .setParameter("reason", reason == null ? null : truncate(reason))
                .setParameter("source", source);
        if (payload != null) {
            query.setParameter("payload", toJson(payload));
        }
        query.executeUpdate();
    }

    private String toJson(Map<String, String> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A map of strings always serialises", e);
        }
    }

    private static String truncate(String value) {
        String trimmed = value.trim();
        return trimmed.length() > REASON_MAX ? trimmed.substring(0, REASON_MAX) : trimmed;
    }
}
