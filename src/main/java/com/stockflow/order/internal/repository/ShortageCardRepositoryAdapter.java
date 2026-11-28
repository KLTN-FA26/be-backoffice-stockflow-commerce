package com.stockflow.order.internal.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.order.api.ShortageCardEnd;
import com.stockflow.order.api.ShortageCardStatus;
import com.stockflow.order.api.ShortageCards;
import com.stockflow.order.api.ShortageOption;
import com.stockflow.order.internal.domain.ShortageCard;
import com.stockflow.order.internal.domain.ShortageCardRepository;
import jakarta.persistence.EntityManager;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Native SQL on {@code ordering.shortage_card} and its lines, as {@link DeliveryFailureCasesAdapter}: a card is a handful
 * of columns that move a few times. Through the {@code EntityManager}, not plain JDBC, so that what the same transaction
 * changed through JPA is flushed before these statements and the table's CHECKs read it.
 *
 * <p>The figures each option showed are stored as one JSON array ({@code amounts}); the options offered are its entries.</p>
 */
@Repository
class ShortageCardRepositoryAdapter implements ShortageCardRepository {

    private static final TypeReference<List<ShortageCards.Option>> OPTIONS = new TypeReference<>() { };

    private static final String COLUMNS = """
            c.id, c.order_id, c.delivery_batch_id, c.customer_id, c.status, CAST(c.amounts AS TEXT),
            c.expected_delivery_date, c.expected_date_source, c.sales_note, c.sent_by, c.sent_at, c.chosen_option,
            c.chosen_by, c.chosen_at, c.customer_note, c.execution_started_at, c.executed_by, c.executed_at,
            c.ended_reason, c.superseded_by, c.chat_message_id, c.version""";

    private final EntityManager entityManager;
    private final ObjectMapper json;

    ShortageCardRepositoryAdapter(EntityManager entityManager, ObjectMapper json) {
        this.entityManager = entityManager;
        this.json = json;
    }

    @Override
    public ShortageCard save(ShortageCard card) {
        boolean exists = !entityManager.createNativeQuery("SELECT 1 FROM ordering.shortage_card WHERE id = :id")
                .setParameter("id", card.id()).getResultList().isEmpty();
        if (!exists) {
            insert(card);
            return find(card.id()).orElseThrow();
        }
        int updated = entityManager.createNativeQuery("""
                        UPDATE ordering.shortage_card
                           SET status = :status,
                               chosen_option = CAST(:chosenOption AS VARCHAR),
                               chosen_by = CAST(:chosenBy AS UUID),
                               chosen_at = CAST(:chosenAt AS TIMESTAMPTZ),
                               customer_note = CAST(:customerNote AS VARCHAR),
                               execution_started_at = CAST(:executionStartedAt AS TIMESTAMPTZ),
                               executed_by = CAST(:executedBy AS UUID),
                               executed_at = CAST(:executedAt AS TIMESTAMPTZ),
                               ended_reason = CAST(:endedReason AS VARCHAR),
                               superseded_by = CAST(:supersededBy AS UUID),
                               version = version + 1
                         WHERE id = :id AND version = :version""")
                .setParameter("status", card.status().name())
                .setParameter("chosenOption", card.chosenOption() == null ? null : card.chosenOption().name())
                .setParameter("chosenBy", card.chosenBy()).setParameter("chosenAt", timestamp(card.chosenAt()))
                .setParameter("customerNote", card.customerNote())
                .setParameter("executionStartedAt", timestamp(card.executionStartedAt()))
                .setParameter("executedBy", card.executedBy()).setParameter("executedAt", timestamp(card.executedAt()))
                .setParameter("endedReason", card.endedReason() == null ? null : card.endedReason().name())
                .setParameter("supersededBy", card.supersededBy())
                .setParameter("id", card.id()).setParameter("version", card.version())
                .executeUpdate();
        if (updated != 1) {
            // Every writer holds the order's row lock, so this is a stale copy written twice, not a race.
            throw new OptimisticLockingFailureException("Shortage card %s changed since it was read".formatted(card.id()));
        }
        return find(card.id()).orElseThrow();
    }

    @Override
    public void linkMessage(UUID cardId, UUID messageId) {
        entityManager.createNativeQuery("UPDATE ordering.shortage_card SET chat_message_id = :message WHERE id = :id")
                .setParameter("message", messageId).setParameter("id", cardId).executeUpdate();
    }

    private void insert(ShortageCard c) {
        String options = c.options().stream().map(o -> o.option().name()).collect(Collectors.joining(",", "{", "}"));
        entityManager.createNativeQuery("""
                        INSERT INTO ordering.shortage_card (id, order_id, delivery_batch_id, customer_id, status,
                            options_offered, missing_cartons, amounts, expected_delivery_date, expected_date_source,
                            sales_note, sent_by, sent_at)
                        VALUES (:id, :order, :batch, :customer, :status, CAST(:options AS VARCHAR[]), :missing,
                                CAST(:amounts AS JSONB), CAST(:expectedDate AS DATE), CAST(:expectedSource AS VARCHAR),
                                CAST(:note AS VARCHAR), :sentBy, :sentAt)""")
                .setParameter("id", c.id()).setParameter("order", c.orderId())
                .setParameter("batch", c.deliveryBatchId()).setParameter("customer", c.customerId())
                .setParameter("status", c.status().name()).setParameter("options", options)
                .setParameter("missing", c.missingCartons()).setParameter("amounts", write(c.options()))
                .setParameter("expectedDate", c.expectedDeliveryDate() == null ? null
                        : java.sql.Date.valueOf(c.expectedDeliveryDate()))
                .setParameter("expectedSource", c.expectedDateSource() == null ? null : c.expectedDateSource().name())
                .setParameter("note", c.salesNote()).setParameter("sentBy", c.sentBy())
                .setParameter("sentAt", Timestamp.from(c.sentAt()))
                .executeUpdate();
        for (ShortageCard.Line line : c.lines()) {
            entityManager.createNativeQuery("""
                            INSERT INTO ordering.shortage_card_line (id, card_id, order_line_id, sku, sku_name,
                                batch_cartons, available_cartons, missing_cartons, units_per_carton)
                            VALUES (:id, :card, :orderLine, :sku, CAST(:skuName AS VARCHAR), :batch, :available,
                                    :missing, :units)""")
                    .setParameter("id", line.id()).setParameter("card", c.id())
                    .setParameter("orderLine", line.orderLineId()).setParameter("sku", line.sku())
                    .setParameter("skuName", line.skuName()).setParameter("batch", line.batchCartons())
                    .setParameter("available", line.availableCartons()).setParameter("missing", line.missingCartons())
                    .setParameter("units", line.unitsPerCarton())
                    .executeUpdate();
        }
    }

    @Override
    public Optional<ShortageCard> find(UUID id) {
        return query("WHERE c.id = :value", id, false).stream().findFirst();
    }

    @Override
    public Optional<ShortageCard> findForUpdate(UUID id) {
        return query("WHERE c.id = :value", id, true).stream().findFirst();
    }

    @Override
    public Optional<ShortageCard> liveOf(UUID orderId, UUID deliveryBatchId) {
        var select = entityManager.createNativeQuery("SELECT c.id FROM ordering.shortage_card c WHERE c.order_id = :order "
                        + "AND c.delivery_batch_id = :batch AND c.status IN ('SENT', 'ANSWERED')")
                .setParameter("order", orderId).setParameter("batch", deliveryBatchId);
        @SuppressWarnings("unchecked")
        List<Object> ids = select.getResultList();
        return ids.isEmpty() ? Optional.empty() : find((UUID) ids.getFirst());
    }

    @Override
    public List<ShortageCard> liveOf(UUID orderId) {
        return query("WHERE c.order_id = :value AND c.status IN ('SENT', 'ANSWERED') ORDER BY c.sent_at, c.id", orderId,
                false);
    }

    @Override
    public List<ShortageCard> liveForUpdate(UUID orderId) {
        return query("WHERE c.order_id = :value AND c.status IN ('SENT', 'ANSWERED') ORDER BY c.sent_at, c.id", orderId,
                true);
    }

    @Override
    public List<ShortageCard> ofOrder(UUID orderId) {
        return query("WHERE c.order_id = :value ORDER BY c.sent_at DESC, c.id DESC", orderId, false);
    }

    /** The cards {@code where} keeps, each with its lines: two statements, not n + 1. */
    private List<ShortageCard> query(String where, UUID value, boolean forUpdate) {
        var select = entityManager.createNativeQuery("SELECT " + COLUMNS + " FROM ordering.shortage_card c " + where
                + (forUpdate ? " FOR UPDATE" : "")).setParameter("value", value);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = select.getResultList();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(r -> (UUID) r[0]).toList();
        Map<UUID, List<ShortageCard.Line>> lines = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> lineRows = entityManager.createNativeQuery("""
                        SELECT card_id, id, order_line_id, sku, sku_name, batch_cartons, available_cartons,
                               missing_cartons, units_per_carton
                          FROM ordering.shortage_card_line WHERE card_id IN (:ids) ORDER BY card_id, sku, id""")
                .setParameter("ids", ids).getResultList();
        for (Object[] l : lineRows) {
            lines.computeIfAbsent((UUID) l[0], k -> new ArrayList<>()).add(new ShortageCard.Line((UUID) l[1],
                    (UUID) l[2], (String) l[3], (String) l[4], ((Number) l[5]).intValue(), ((Number) l[6]).intValue(),
                    ((Number) l[7]).intValue(), ((Number) l[8]).intValue()));
        }
        return rows.stream().map(r -> new ShortageCard((UUID) r[0], (UUID) r[1], (UUID) r[2], (UUID) r[3],
                ShortageCardStatus.valueOf((String) r[4]), lines.getOrDefault((UUID) r[0], List.of()),
                read((String) r[5]), date(r[6]),
                r[7] == null ? null : ShortageCard.DateSource.valueOf((String) r[7]), (String) r[8], (UUID) r[9],
                instant(r[10]), r[11] == null ? null : ShortageOption.valueOf((String) r[11]), (UUID) r[12],
                instant(r[13]), (String) r[14], instant(r[15]), (UUID) r[16], instant(r[17]),
                r[18] == null ? null : ShortageCardEnd.valueOf((String) r[18]), (UUID) r[19], (UUID) r[20],
                ((Number) r[21]).longValue())).toList();
    }

    private String write(List<ShortageCards.Option> options) {
        try {
            return json.writeValueAsString(options);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("A shortage card's figures could not be written", unwritable);
        }
    }

    private List<ShortageCards.Option> read(String amounts) {
        try {
            return json.readValue(amounts, OPTIONS);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("A shortage card's figures could not be read", unreadable);
        }
    }

    private static Timestamp timestamp(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp t) {
            return t.toInstant();
        }
        if (value instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        return value instanceof Instant i ? i : null;
    }

    private static LocalDate date(Object value) {
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return value instanceof LocalDate d ? d : null;
    }
}
