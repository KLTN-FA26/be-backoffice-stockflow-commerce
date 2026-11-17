package com.stockflow.payment.internal.repository;

import com.stockflow.payment.internal.domain.OrderRefund;
import com.stockflow.payment.internal.domain.OrderSettlement;
import com.stockflow.payment.internal.domain.RefundRecord;
import com.stockflow.payment.internal.domain.SettlementBook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link SettlementBook} and {@link RefundSearch} over {@code payment.order_settlement},
 * {@code payment.installment_reduction} and {@code payment.refund_record}, in plain SQL: a settlement is written once
 * and never changed, and a refund record only ever moves to VOIDED, so there is no aggregate state for an ORM to track.
 * The statements run on the transaction's connection, after the installments the same unit of work saved and flushed.
 */
@Repository
class SettlementBookAdapter implements SettlementBook, RefundSearch {

    private static final String SETTLEMENT_COLUMNS = """
            id, order_id, contract_id, customer_id, order_number, contract_number, kind, source_id, currency,
            collected_amount, closed_amount, reduced_amount, surplus_amount, fee_amount, fee_percent, fee_reason,
            fee_detail, fee_covered_amount, refundable_amount, fee_owed_amount, fee_installment_id, settled_by,
            settled_at""";

    private static final String RECORD_COLUMNS = """
            id, order_id, customer_id, order_number, amount, currency, refunded_on, reference, note, status, recorded_by,
            recorded_at, voided_by, voided_at, void_reason""";

    private final NamedParameterJdbcTemplate jdbc;

    SettlementBookAdapter(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    // ---- settlements ------------------------------------------------------------------------------------------------

    @Override
    public Optional<OrderSettlement> find(OrderSettlement.Kind kind, UUID sourceId) {
        List<OrderSettlement> found = settlements("kind = :kind AND source_id = :source",
                new MapSqlParameterSource("kind", kind.name()).addValue("source", sourceId));
        return found.stream().findFirst();
    }

    @Override
    public List<OrderSettlement> ofOrder(UUID orderId) {
        return settlements("order_id = :order", new MapSqlParameterSource("order", orderId));
    }

    private List<OrderSettlement> settlements(String where, MapSqlParameterSource params) {
        List<Object[]> rows = jdbc.query("SELECT " + SETTLEMENT_COLUMNS + " FROM payment.order_settlement WHERE "
                + where + " ORDER BY settled_at, id", params, (rs, n) -> {
            Object[] row = new Object[23];
            for (int i = 0; i < row.length; i++) {
                row[i] = rs.getObject(i + 1);
            }
            return row;
        });
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<OrderSettlement.Reduction>> reductions = jdbc.query("""
                        SELECT settlement_id, installment_id, amount FROM payment.installment_reduction
                         WHERE settlement_id IN (:ids) ORDER BY created_at, id""",
                new MapSqlParameterSource("ids", rows.stream().map(row -> (UUID) row[0]).toList()),
                (rs, n) -> Map.entry(rs.getObject(1, UUID.class), new OrderSettlement.Reduction(
                        rs.getObject(2, UUID.class), rs.getBigDecimal(3))))
                .stream().collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        List<OrderSettlement> settlements = new ArrayList<>();
        for (Object[] r : rows) {
            UUID id = (UUID) r[0];
            settlements.add(new OrderSettlement(id, (UUID) r[1], (UUID) r[2], (UUID) r[3], (String) r[4],
                    (String) r[5], OrderSettlement.Kind.valueOf((String) r[6]), (UUID) r[7], (String) r[8],
                    (BigDecimal) r[9], (BigDecimal) r[10], (BigDecimal) r[11], (BigDecimal) r[12], (BigDecimal) r[13],
                    terms((BigDecimal) r[14], (BigDecimal) r[13], (String) r[15], (String) r[16]),
                    (BigDecimal) r[17], (BigDecimal) r[18], (BigDecimal) r[19], (UUID) r[20], (UUID) r[21],
                    instant(r[22]), reductions.getOrDefault(id, List.of())));
        }
        return settlements;
    }

    /** The fee's terms as stored: a share, or an agreed amount with its reason and detail, or none. */
    private static OrderSettlement.Fee terms(BigDecimal percent, BigDecimal fee, String reason, String detail) {
        if (percent != null) {
            return new OrderSettlement.Fee(percent, null, null, null);
        }
        return reason == null ? OrderSettlement.Fee.none() : new OrderSettlement.Fee(null, fee, reason, detail);
    }

    @Override
    public void save(OrderSettlement s) {
        OrderSettlement.Fee terms = s.feeTerms();
        jdbc.update("INSERT INTO payment.order_settlement (" + SETTLEMENT_COLUMNS + """
                        , version, created_at, created_by)
                        VALUES (:id, :order, :contract, :customer, :orderNumber, :contractNumber, :kind, :source,
                                :currency, :collected, :closed, :reduced, :surplus, :fee, :percent, :reason, :detail,
                                :covered, :refundable, :owed, :feeItem, :by, :at, 0, NOW(), :createdBy)""",
                new MapSqlParameterSource("id", s.id())
                        .addValue("order", s.orderId())
                        .addValue("contract", s.contractId())
                        .addValue("customer", s.customerId())
                        .addValue("orderNumber", s.orderNumber())
                        .addValue("contractNumber", s.contractNumber())
                        .addValue("kind", s.kind().name())
                        .addValue("source", s.sourceId())
                        .addValue("currency", s.currency())
                        .addValue("collected", s.collected())
                        .addValue("closed", s.closed())
                        .addValue("reduced", s.reduced())
                        .addValue("surplus", s.surplus())
                        .addValue("fee", s.fee())
                        .addValue("percent", terms.percent())
                        .addValue("reason", terms.reason())
                        .addValue("detail", terms.detail())
                        .addValue("covered", s.feeCovered())
                        .addValue("refundable", s.refundable())
                        .addValue("owed", s.feeOwed())
                        .addValue("feeItem", s.feeItemId())
                        .addValue("by", s.settledBy())
                        .addValue("at", utc(s.settledAt()))
                        .addValue("createdBy", s.settledBy() == null ? "system" : s.settledBy().toString()));
        for (OrderSettlement.Reduction reduction : s.reductions()) {
            jdbc.update("""
                            INSERT INTO payment.installment_reduction (id, settlement_id, installment_id, amount)
                            VALUES (:id, :settlement, :installment, :amount)""",
                    new MapSqlParameterSource("id", com.stockflow.common.id.Identifiers.newId())
                            .addValue("settlement", s.id())
                            .addValue("installment", reduction.installmentId())
                            .addValue("amount", reduction.amount()));
        }
    }

    // ---- refund records ---------------------------------------------------------------------------------------------

    private static final RowMapper<RefundRecord> RECORD = (rs, n) -> new RefundRecord(rs.getObject(1, UUID.class),
            rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getBigDecimal(5),
            rs.getString(6), rs.getObject(7, java.time.LocalDate.class), rs.getString(8), rs.getString(9),
            RefundRecord.Status.valueOf(rs.getString(10)), rs.getObject(11, UUID.class), instant(rs.getTimestamp(12)),
            rs.getObject(13, UUID.class), instant(rs.getTimestamp(14)), rs.getString(15));

    @Override
    public RefundRecord record(RefundRecord r) {
        jdbc.update("INSERT INTO payment.refund_record (" + RECORD_COLUMNS + """
                        , version, created_at, created_by)
                        VALUES (:id, :order, :customer, :orderNumber, :amount, :currency, :refundedOn, :reference,
                                :note, :status, :by, :at, NULL, NULL, NULL, 0, NOW(), :createdBy)""",
                new MapSqlParameterSource("id", r.id())
                        .addValue("order", r.orderId())
                        .addValue("customer", r.customerId())
                        .addValue("orderNumber", r.orderNumber())
                        .addValue("amount", r.amount())
                        .addValue("currency", r.currency())
                        .addValue("refundedOn", r.refundedOn())
                        .addValue("reference", r.reference())
                        .addValue("note", r.note())
                        .addValue("status", r.status().name())
                        .addValue("by", r.recordedBy())
                        .addValue("at", utc(r.recordedAt()))
                        .addValue("createdBy", r.recordedBy().toString()));
        return r;
    }

    @Override
    public RefundRecord saveVoided(RefundRecord r) {
        jdbc.update("""
                        UPDATE payment.refund_record
                           SET status = :status, voided_by = :by, voided_at = :at, void_reason = :reason,
                               version = version + 1, last_modified_at = NOW(), last_modified_by = :modifiedBy
                         WHERE id = :id""",
                new MapSqlParameterSource("status", r.status().name())
                        .addValue("by", r.voidedBy())
                        .addValue("at", utc(r.voidedAt()))
                        .addValue("reason", r.voidReason())
                        .addValue("modifiedBy", r.voidedBy().toString())
                        .addValue("id", r.id()));
        return r;
    }

    @Override
    public Optional<RefundRecord> findRecord(UUID id) {
        return jdbc.query("SELECT " + RECORD_COLUMNS + " FROM payment.refund_record WHERE id = :id",
                new MapSqlParameterSource("id", id), RECORD).stream().findFirst();
    }

    @Override
    public Optional<RefundRecord> lockRecord(UUID id) {
        return jdbc.query("SELECT " + RECORD_COLUMNS + " FROM payment.refund_record WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), RECORD).stream().findFirst();
    }

    @Override
    public List<RefundRecord> recordsOf(UUID orderId) {
        return jdbc.query("SELECT " + RECORD_COLUMNS + " FROM payment.refund_record WHERE order_id = :order "
                + "ORDER BY recorded_at, id", new MapSqlParameterSource("order", orderId), RECORD);
    }

    @Override
    public boolean refundReferenceInUse(String reference) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM payment.refund_record
                                        WHERE reference = :reference AND status = 'RECORDED')""",
                new MapSqlParameterSource("reference", reference.strip()), Boolean.class));
    }

    // ---- search -----------------------------------------------------------------------------------------------------

    @Override
    public Page<RefundRecord> records(UUID customerId, UUID orderId, RefundRecord.Status status, Pageable pageable) {
        String where = """
                 WHERE (CAST(:customer AS UUID) IS NULL OR customer_id = :customer)
                   AND (CAST(:order AS UUID) IS NULL OR order_id = :order)
                   AND (CAST(:status AS VARCHAR) IS NULL OR status = :status)""";
        MapSqlParameterSource params = new MapSqlParameterSource("customer", customerId).addValue("order", orderId)
                .addValue("status", status == null ? null : status.name())
                .addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        long total = count("SELECT count(*) FROM payment.refund_record" + where, params);
        List<RefundRecord> content = jdbc.query("SELECT " + RECORD_COLUMNS + " FROM payment.refund_record" + where
                + " ORDER BY recorded_at DESC, id DESC LIMIT :limit OFFSET :offset", params, RECORD);
        return new PageImpl<>(content, pageable, total);
    }

    @Override
    public Page<OrderRefund> orderRefunds(UUID customerId, Boolean outstanding, Pageable pageable) {
        String from = """
                  FROM (SELECT s.order_id, MAX(s.order_number) AS order_number, MAX(CAST(s.customer_id AS VARCHAR))
                               AS customer_id, MAX(s.currency) AS currency, SUM(s.refundable_amount) AS refundable,
                               COALESCE((SELECT SUM(r.amount) FROM payment.refund_record r
                                          WHERE r.order_id = s.order_id AND r.status = 'RECORDED'), 0) AS refunded,
                               MAX(s.settled_at) AS settled_at
                          FROM payment.order_settlement s
                         WHERE CAST(:customer AS UUID) IS NULL OR s.customer_id = :customer
                         GROUP BY s.order_id
                        HAVING SUM(s.refundable_amount) > 0) o
                 WHERE CAST(:outstanding AS BOOLEAN) IS NULL OR (o.refunded < o.refundable) = :outstanding""";
        MapSqlParameterSource params = new MapSqlParameterSource("customer", customerId)
                .addValue("outstanding", outstanding)
                .addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        long total = count("SELECT count(*)" + from, params);
        List<OrderRefund> content = jdbc.query("""
                        SELECT o.order_id, o.order_number, o.customer_id, o.currency, o.refundable, o.refunded""" + from
                        + " ORDER BY o.settled_at DESC, o.order_id LIMIT :limit OFFSET :offset", params,
                (rs, n) -> new OrderRefund(rs.getObject(1, UUID.class), rs.getString(2),
                        UUID.fromString(rs.getString(3)), rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6)));
        return new PageImpl<>(content, pageable, total);
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long total = jdbc.queryForObject(sql, params, Long.class);
        return total == null ? 0 : total;
    }

    /** A moment as {@code timestamptz}, whatever the JVM's zone: pgjdbc binds an OffsetDateTime as one. */
    private static java.time.OffsetDateTime utc(Instant at) {
        return at == null ? null : java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC);
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case Timestamp t -> t.toInstant();
            case java.time.OffsetDateTime o -> o.toInstant();
            default -> Instant.parse(value.toString());
        };
    }
}
