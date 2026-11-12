package com.stockflow.invoicing.internal.repository;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.FeeAllocation;
import com.stockflow.invoicing.internal.entity.EInvoiceJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link EInvoiceRepository} over {@code invoicing.einvoice} and {@code invoicing.einvoice_line}.
 *
 * <p>The invoice row is a JPA entity, so its optimistic version guards the whole aggregate. Its lines are plain rows
 * written with it and, on a draft, renamed - never mapped: they are inserted and read with SQL in the same
 * transaction, as payment's receipt lines are.</p>
 *
 * <p>{@link #findByIdForUpdate} refreshes what it locks ({@code PESSIMISTIC_WRITE}): an invoice already in the
 * persistence context would otherwise come back as it was read before the lock, and the lock would guard nothing.</p>
 */
@Repository
class EInvoiceRepositoryAdapter implements EInvoiceRepository {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final EInvoiceJpaRepository invoices;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    EInvoiceRepositoryAdapter(EInvoiceJpaRepository invoices, EntityManager entityManager, JdbcTemplate jdbc) {
        this.invoices = invoices;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EInvoice> findById(UUID id) {
        return invoices.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<EInvoice> findByIdForUpdate(UUID id) {
        EInvoiceJpaEntity entity = entityManager.find(EInvoiceJpaEntity.class, id);
        if (entity == null) {
            return Optional.empty();
        }
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(toDomain(entity));
    }

    @Override
    public Optional<EInvoice> findLiveOriginalOfShipment(UUID shipmentId) {
        // At most one original that is not voided or replaced (uk_einvoice_original_per_shipment) and at most one
        // issued original-or-replacement (uk_einvoice_in_force_per_shipment). The issued one first: a replacement
        // still on its way does not stand for the shipment yet.
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE shipment_id = ? AND type IN ('ORIGINAL', 'REPLACEMENT')
                   AND status NOT IN ('VOIDED_DRAFT', 'REPLACED')
                 ORDER BY CASE WHEN status = 'ISSUED' THEN 0 ELSE 1 END, created_at, id
                 LIMIT 1""",
                UUID.class, shipmentId);
        return ids.isEmpty() ? Optional.empty() : findById(ids.getFirst());
    }

    @Override
    public boolean hasReplacedOriginalOfShipment(UUID shipmentId) {
        Boolean replaced = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM invoicing.einvoice
                                WHERE shipment_id = ? AND type = 'ORIGINAL' AND status = 'REPLACED')""",
                Boolean.class, shipmentId);
        return Boolean.TRUE.equals(replaced);
    }

    @Override
    public Optional<EInvoice> findLiveShippingFeeInvoice(UUID orderId) {
        // At most one original of the rest that was not voided (uk_einvoice_shipping_fee_rest); what replaced it names
        // no shipment either. The issued one first, as for a shipment.
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE order_id = ? AND shipment_id IS NULL AND type IN ('ORIGINAL', 'REPLACEMENT')
                   AND status NOT IN ('VOIDED_DRAFT', 'REPLACED')
                 ORDER BY CASE WHEN status = 'ISSUED' THEN 0 ELSE 1 END, created_at, id
                 LIMIT 1""",
                UUID.class, orderId);
        return ids.isEmpty() ? Optional.empty() : findById(ids.getFirst());
    }

    @Override
    public void lockOrder(UUID orderId) {
        // Wrapped in a sub-select: pg_advisory_xact_lock returns void. Released when the transaction ends.
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) AS locked",
                Integer.class, "einvoice-order:" + orderId);
    }

    @Override
    public FeeAllocation.Invoiced invoicedOfOrder(UUID orderId) {
        // Every original of the order that was not voided: a replaced one counts too - what replaces it carries its
        // lines again, and a fee must not be drawn up a second time beside the replacement.
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(l.quantity * COALESCE(l.units_per_carton, 1)) FILTER (WHERE l.kind = 'GOODS'), 0)
                           AS units,
                       COALESCE(sum(l.amount) FILTER (WHERE l.fee_kind = 'SHIPPING_FEE'), 0) AS shipping,
                       COALESCE(array_agg(DISTINCT l.sku) FILTER (WHERE l.fee_kind = 'PLATE_FEE'), '{}') AS plate_skus
                  FROM invoicing.einvoice i
                  JOIN invoicing.einvoice_line l ON l.einvoice_id = i.id
                 WHERE i.order_id = ? AND i.type = 'ORIGINAL' AND i.status <> 'VOIDED_DRAFT'""",
                (row, n) -> new FeeAllocation.Invoiced(row.getLong("units"), row.getBigDecimal("shipping"),
                        plateSkus((String[]) row.getArray("plate_skus").getArray())), orderId);
    }

    private static java.util.Set<String> plateSkus(String[] skus) {
        java.util.Set<String> set = new java.util.HashSet<>(java.util.Arrays.asList(skus));
        set.remove(null);
        return set;
    }

    @Override
    public EInvoice.Corrections correctionsOf(UUID invoiceId) {
        // The predicates of tg_einvoice_starts_as_draft: an adjustment naming or listing the invoice, a replacement
        // of it, neither voided.
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM invoicing.einvoice a
                                WHERE a.type = 'ADJUSTMENT' AND a.status <> 'VOIDED_DRAFT'
                                  AND (a.original_einvoice_id = ?
                                       OR EXISTS (SELECT 1 FROM invoicing.einvoice_adjustment_listing l
                                                   WHERE l.einvoice_id = a.id AND l.listed_einvoice_id = ?))),
                       EXISTS (SELECT 1 FROM invoicing.einvoice r
                                WHERE r.original_einvoice_id = ? AND r.type = 'REPLACEMENT'
                                  AND r.status <> 'VOIDED_DRAFT'),
                       (SELECT COALESCE(sum(a.grand_total), 0) FROM invoicing.einvoice a
                         WHERE a.original_einvoice_id = ? AND a.type = 'ADJUSTMENT' AND a.status <> 'VOIDED_DRAFT')""",
                (row, n) -> new EInvoice.Corrections(row.getBoolean(1), row.getBoolean(2), row.getBigDecimal(3)),
                invoiceId, invoiceId, invoiceId, invoiceId);
    }

    @Override
    public List<EInvoice> findInForceOfOrder(UUID orderId) {
        return jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE order_id = ? AND type IN ('ORIGINAL', 'REPLACEMENT') AND status = 'ISSUED'
                 ORDER BY issue_date, created_at, id""", UUID.class, orderId).stream()
                .map(id -> findById(id).orElseThrow()).toList();
    }

    @Override
    public java.util.Map<UUID, Long> returnedPieces(UUID invoiceId) {
        java.util.Map<UUID, Long> pieces = new java.util.HashMap<>();
        jdbc.query("""
                SELECT l.order_line_id, sum(-l.quantity::bigint * COALESCE(l.units_per_carton, 1)) AS pieces
                  FROM invoicing.einvoice a
                  JOIN invoicing.einvoice_line l ON l.einvoice_id = a.id
                 WHERE a.original_einvoice_id = ? AND a.correction_basis = 'RETURN' AND a.status <> 'VOIDED_DRAFT'
                   AND l.kind = 'GOODS'
                 GROUP BY l.order_line_id""",
                row -> {
                    pieces.put(row.getObject("order_line_id", UUID.class), row.getLong("pieces"));
                }, invoiceId);
        return pieces;
    }

    @Override
    public boolean hasAdjustmentForReturn(UUID returnRequestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM invoicing.einvoice WHERE return_request_id = ?)", Boolean.class,
                returnRequestId));
    }

    @Override
    public boolean hasLiveReturnAdjustment(UUID returnRequestId, UUID invoiceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM invoicing.einvoice
                                WHERE return_request_id = ? AND original_einvoice_id = ?
                                  AND status <> 'VOIDED_DRAFT')""", Boolean.class, returnRequestId, invoiceId));
    }

    @Override
    public EInvoice save(EInvoice invoice) {
        EInvoice.Snapshot snapshot = invoice.snapshot();
        Optional<EInvoiceJpaEntity> existing = invoices.findById(snapshot.id());
        if (existing.isEmpty()) {
            EInvoiceJpaEntity entity = EInvoicePersistenceMapper.newEntity(snapshot);
            EInvoicePersistenceMapper.apply(snapshot, entity);
            // The invoice first: a line is refused unless its invoice is there, and a draft (tg_einvoice_line_guard).
            EInvoiceJpaEntity saved = invoices.saveAndFlush(entity);
            snapshot.lines().forEach(line -> insert(snapshot.id(), line));
            // The list of a trade-discount adjustment, written with it (tg_einvoice_adjustment_listing_guard).
            snapshot.listedInvoiceIds().forEach(listed -> jdbc.update(
                    "INSERT INTO invoicing.einvoice_adjustment_listing (einvoice_id, listed_einvoice_id) VALUES (?, ?)",
                    snapshot.id(), listed));
            return toDomain(saved);
        }
        EInvoiceJpaEntity entity = existing.get();
        if (entity.getVersion() != snapshot.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Invoice %s changed since it was read".formatted(snapshot.code()));
        }
        // Names first, while the row in the database is still the draft the aggregate allowed them on; a line whose
        // name is unchanged matches no row, so an invoice past its draft never touches a line.
        for (EInvoiceLine line : snapshot.lines()) {
            jdbc.update("UPDATE invoicing.einvoice_line SET item_name = ? WHERE id = ? AND item_name <> ?",
                    line.itemName(), line.id(), line.itemName());
        }
        EInvoicePersistenceMapper.apply(snapshot, entity);
        return toDomain(invoices.saveAndFlush(entity));
    }

    @Override
    public List<UUID> findSubmitting(int limit) {
        return jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice WHERE status = 'SUBMITTING'
                 ORDER BY submitted_at, id LIMIT ?""", UUID.class, limit);
    }

    @Override
    public List<UUID> findDueUnwarned(Instant dueBefore, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE status IN ('DRAFT', 'REJECTED', 'SUBMITTING') AND due_warned_at IS NULL AND issue_due_at < ?
                 ORDER BY issue_due_at, id LIMIT ?""", UUID.class, Timestamp.from(dueBefore), limit);
    }

    @Override
    public List<UUID> findAutoIssuable(int limit) {
        return jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE status = 'DRAFT' AND type = 'ORIGINAL' AND attempt = 0 AND warnings IS NULL
                 ORDER BY issue_date, id LIMIT ?""", UUID.class, limit);
    }

    @Override
    public Optional<String> findEarlierUnissued(UUID id, LocalDate issueDate) {
        return jdbc.queryForList("""
                SELECT code FROM invoicing.einvoice
                 WHERE id <> ? AND status IN ('DRAFT', 'REJECTED', 'SUBMITTING')
                   AND issue_date < ? AND issue_date >= ?
                 ORDER BY issue_date, id LIMIT 1""", String.class, id, java.sql.Date.valueOf(issueDate),
                java.sql.Date.valueOf(issueDate.withDayOfYear(1))).stream().findFirst();
    }

    @Override
    public List<UUID> findStuckUnannounced(Instant submittedBefore, int limit) {
        return jdbc.queryForList("""
                SELECT i.id FROM invoicing.einvoice i
                 WHERE i.status = 'SUBMITTING' AND i.submitted_at < ?
                   AND NOT EXISTS (SELECT 1 FROM invoicing.einvoice_stuck_notice n
                                    WHERE n.einvoice_id = i.id AND n.transaction_id = i.transaction_id)
                 ORDER BY i.submitted_at, i.id LIMIT ?""", UUID.class, Timestamp.from(submittedBefore), limit);
    }

    @Override
    public boolean markStuckAnnounced(UUID id, String transactionId, Instant now) {
        return jdbc.update("""
                INSERT INTO invoicing.einvoice_stuck_notice (einvoice_id, transaction_id, notified_at)
                VALUES (?, ?, ?) ON CONFLICT (einvoice_id, transaction_id) DO NOTHING""", id, transactionId,
                Timestamp.from(now)) == 1;
    }

    @Override
    public String nextCode(LocalDate day) {
        Long value = jdbc.queryForObject("""
                INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                VALUES ('EINV', ?, 1)
                ON CONFLICT (document_type, sequence_date)
                DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                RETURNING last_value""", Long.class, java.sql.Date.valueOf(day));
        return "EINV-%s-%04d".formatted(day.format(DAY), value);
    }

    private EInvoice toDomain(EInvoiceJpaEntity entity) {
        return EInvoicePersistenceMapper.toDomain(entity, lines(entity.getId()),
                entity.getCorrectionBasis() == null ? List.of() : jdbc.queryForList(
                        "SELECT listed_einvoice_id FROM invoicing.einvoice_adjustment_listing WHERE einvoice_id = ? "
                                + "ORDER BY listed_einvoice_id", UUID.class, entity.getId()));
    }

    private void insert(UUID invoiceId, EInvoiceLine line) {
        jdbc.update("""
                INSERT INTO invoicing.einvoice_line (id, einvoice_id, line_no, kind, fee_kind, order_line_id, sku,
                    item_name, unit, quantity, units_per_carton, unit_price, tax_rate, amount, tax_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", line.id(), invoiceId, line.lineNo(),
                line.kind().name(), line.feeKind() == null ? null : line.feeKind().name(), line.orderLineId(),
                line.sku(), line.itemName(), line.unit(), line.quantity(), line.unitsPerCarton(), line.unitPrice(),
                line.taxRate(), line.amount(), line.taxAmount());
    }

    private List<EInvoiceLine> lines(UUID invoiceId) {
        return jdbc.query("""
                SELECT id, line_no, kind, fee_kind, order_line_id, sku, item_name, unit, quantity, units_per_carton,
                       unit_price, tax_rate, amount, tax_amount
                  FROM invoicing.einvoice_line WHERE einvoice_id = ? ORDER BY line_no""",
                (row, n) -> new EInvoiceLine(row.getObject("id", UUID.class), row.getInt("line_no"),
                        EInvoiceLine.Kind.valueOf(row.getString("kind")),
                        row.getString("fee_kind") == null ? null
                                : EInvoiceLine.FeeKind.valueOf(row.getString("fee_kind")),
                        row.getObject("order_line_id", UUID.class), row.getString("sku"), row.getString("item_name"),
                        row.getString("unit"), row.getInt("quantity"),
                        row.getObject("units_per_carton", Integer.class), row.getBigDecimal("unit_price"),
                        row.getBigDecimal("tax_rate"), row.getBigDecimal("amount"), row.getBigDecimal("tax_amount")),
                invoiceId);
    }
}
