package com.stockflow.invoicing.internal.repository;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.FeeAllocation;
import com.stockflow.invoicing.internal.domain.InvoiceSeries;
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
        // At most one original that is not voided, replaced or reversed (uk_einvoice_original_per_shipment) and at
        // most one issued original-or-replacement not reversed (uk_einvoice_in_force_per_shipment). The issued one
        // first: a replacement still on its way does not stand for the shipment yet. An original reversed for a failed
        // delivery stands for nothing (WF-05 HĐ-3).
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE shipment_id = ? AND type IN ('ORIGINAL', 'REPLACEMENT')
                   AND status NOT IN ('VOIDED_DRAFT', 'REPLACED') AND reversed_at IS NULL
                 ORDER BY CASE WHEN status = 'ISSUED' THEN 0 ELSE 1 END, created_at, id
                 LIMIT 1""",
                UUID.class, shipmentId);
        return ids.isEmpty() ? Optional.empty() : findById(ids.getFirst());
    }

    @Override
    public boolean markReversed(UUID invoiceId, Instant at) {
        // The version is left alone: the invoice is issued and says nothing new (tg_einvoice_guard checks the rest).
        return jdbc.update("UPDATE invoicing.einvoice SET reversed_at = ? WHERE id = ? AND reversed_at IS NULL",
                Timestamp.from(at), invoiceId) == 1;
    }

    @Override
    public boolean markDeliveryFailed(UUID invoiceId, Instant at) {
        // As markReversed, outside the version: the invoice is at the provider and its content does not change
        // (tg_einvoice_delivery_failed lets it be written once, while it is SUBMITTING).
        return jdbc.update("""
                UPDATE invoicing.einvoice SET delivery_failed_at = ?
                 WHERE id = ? AND status = 'SUBMITTING' AND delivery_failed_at IS NULL""",
                Timestamp.from(at), invoiceId) == 1;
    }

    @Override
    public boolean deliveryFailed(UUID invoiceId) {
        Boolean failed = jdbc.queryForObject(
                "SELECT delivery_failed_at IS NOT NULL FROM invoicing.einvoice WHERE id = ?", Boolean.class, invoiceId);
        return Boolean.TRUE.equals(failed);
    }

    @Override
    public boolean clearDeliveryFailed(UUID invoiceId) {
        // tg_einvoice_delivery_failed lets the mark go only while the invoice is not issued.
        return jdbc.update("""
                UPDATE invoicing.einvoice SET delivery_failed_at = NULL
                 WHERE id = ? AND delivery_failed_at IS NOT NULL AND status IN ('SUBMITTING', 'DRAFT', 'REJECTED')""",
                invoiceId) == 1;
    }

    @Override
    public List<EInvoice> findUnsettledDeliveryFailedOfShipment(UUID shipmentId) {
        return jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE shipment_id = ? AND delivery_failed_at IS NOT NULL
                   AND status IN ('SUBMITTING', 'DRAFT', 'REJECTED')
                 ORDER BY created_at, id""", UUID.class, shipmentId).stream()
                .map(this::findById).flatMap(Optional::stream).toList();
    }

    @Override
    public Optional<EInvoice> findPendingReplacementOf(UUID originalId) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE original_einvoice_id = ? AND type = 'REPLACEMENT'
                   AND status IN ('DRAFT', 'REJECTED', 'SUBMITTING')
                 ORDER BY created_at, id
                 LIMIT 1""", UUID.class, originalId);
        return ids.isEmpty() ? Optional.empty() : findById(ids.getFirst());
    }

    @Override
    public boolean hasReplacedOriginalOfShipment(UUID shipmentId) {
        // A replaced original whose chain was reversed - the replacement standing for the shipment, reduced in full
        // for a failed delivery - keeps nothing back: a reversed invoice of the shipment was created after it (WF-05
        // HĐ-3; the same reading as tg_einvoice_original_per_shipment, V20261203000300).
        Boolean replaced = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM invoicing.einvoice i
                                WHERE i.shipment_id = ? AND i.type = 'ORIGINAL' AND i.status = 'REPLACED'
                                  AND NOT EXISTS (SELECT 1 FROM invoicing.einvoice x
                                                   WHERE x.shipment_id = i.shipment_id AND x.reversed_at IS NOT NULL
                                                     AND x.created_at >= i.created_at))""",
                Boolean.class, shipmentId);
        return Boolean.TRUE.equals(replaced);
    }

    @Override
    public Optional<EInvoice> findLiveShippingFeeInvoice(UUID orderId) {
        // An original of the rest that was not voided, or what replaced it (it names no shipment either); the issued
        // one first, as for a shipment. Since audit r3 LM-2 an order may have several issued ones - the rest decided
        // again after a delivery was undone - and at most one not issued yet (uk_einvoice_shipping_fee_rest).
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
        // lines again, and a fee must not be drawn up a second time beside the replacement. A reversed one does not
        // (WF-05 HĐ-3): its full reduction took its fees off, and the redelivery's original carries them again. Nor
        // does a replaced one whose replacement was reversed - a reversed invoice of its shipment created after it -
        // for the same reason: the reduction of the replacement took off what the replaced original carried.
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(l.quantity * COALESCE(l.units_per_carton, 1)) FILTER (WHERE l.kind = 'GOODS'), 0)
                           AS units,
                       COALESCE(sum(l.amount) FILTER (WHERE l.fee_kind = 'SHIPPING_FEE'), 0) AS shipping,
                       COALESCE(sum(l.amount) FILTER (WHERE l.kind = 'GOODS'), 0) AS goods_value,
                       COALESCE(array_agg(DISTINCT l.sku) FILTER (WHERE l.fee_kind = 'PLATE_FEE'), '{}') AS plate_skus
                  FROM invoicing.einvoice i
                  JOIN invoicing.einvoice_line l ON l.einvoice_id = i.id
                 WHERE i.order_id = ? AND i.type = 'ORIGINAL' AND i.status <> 'VOIDED_DRAFT'
                   AND i.reversed_at IS NULL
                   AND NOT (i.status = 'REPLACED' AND EXISTS (
                        SELECT 1 FROM invoicing.einvoice x
                         WHERE x.shipment_id = i.shipment_id AND x.reversed_at IS NOT NULL
                           AND x.created_at >= i.created_at))""",
                (row, n) -> new FeeAllocation.Invoiced(row.getLong("units"), row.getBigDecimal("shipping"),
                        plateSkus((String[]) row.getArray("plate_skus").getArray()),
                        row.getBigDecimal("goods_value")), orderId);
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
    public List<UUID> findInForceOfOrder(UUID orderId) {
        return jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE order_id = ? AND type IN ('ORIGINAL', 'REPLACEMENT') AND status = 'ISSUED'
                   AND reversed_at IS NULL
                 ORDER BY issue_date, created_at, id""", UUID.class, orderId);
    }

    @Override
    public java.util.Map<UUID, Long> piecesTakenOff(UUID invoiceId) {
        // A return's lines are all goods taken off. An error's line moves pieces only when it is priced as the line
        // it corrects (a quantity that was wrong, or a rate: off and on again at that price); a price that was wrong is
        // stated at the difference over the whole quantity and moves none. A trade discount moves no goods. The
        // reduction of a failed delivery takes every piece off (WF-05 HĐ-3).
        java.util.Map<UUID, Long> pieces = new java.util.HashMap<>();
        jdbc.query("""
                SELECT l.order_line_id, sum(-l.quantity::bigint * COALESCE(l.units_per_carton, 1)) AS pieces
                  FROM invoicing.einvoice a
                  JOIN invoicing.einvoice_line l ON l.einvoice_id = a.id
                 WHERE a.original_einvoice_id = ? AND a.type = 'ADJUSTMENT' AND a.status <> 'VOIDED_DRAFT'
                   AND l.kind = 'GOODS'
                   AND (a.correction_basis IN ('RETURN', 'DELIVERY_FAILED')
                        OR a.correction_basis = 'ERROR' AND EXISTS (
                            SELECT 1 FROM invoicing.einvoice_line o
                             WHERE o.einvoice_id = a.original_einvoice_id AND o.kind = 'GOODS'
                               AND o.order_line_id = l.order_line_id AND o.unit_price = l.unit_price
                               AND o.units_per_carton IS NOT DISTINCT FROM l.units_per_carton))
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
    public List<EInvoice> findVoidedReturnAdjustmentsOf(UUID invoiceId) {
        return jdbc.queryForList("""
                SELECT DISTINCT ON (v.return_request_id) v.id FROM invoicing.einvoice v
                 WHERE v.original_einvoice_id = ? AND v.type = 'ADJUSTMENT' AND v.correction_basis = 'RETURN'
                   AND v.status = 'VOIDED_DRAFT'
                   AND NOT EXISTS (SELECT 1 FROM invoicing.einvoice a
                                    WHERE a.return_request_id = v.return_request_id AND a.status <> 'VOIDED_DRAFT')
                 ORDER BY v.return_request_id, v.voided_at DESC NULLS LAST, v.id""", UUID.class, invoiceId).stream()
                .map(this::findById).flatMap(Optional::stream).toList();
    }

    @Override
    public List<EInvoice> findUnrecordedVoidedErrorAdjustmentsOf(UUID invoiceId) {
        return jdbc.queryForList("""
                SELECT v.id FROM invoicing.einvoice v
                 WHERE v.original_einvoice_id = ? AND v.type = 'ADJUSTMENT' AND v.correction_basis = 'ERROR'
                   AND v.status = 'VOIDED_DRAFT'
                   AND NOT EXISTS (SELECT 1 FROM invoicing.einvoice_portal_record r WHERE r.voided_draft_id = v.id)
                 ORDER BY v.voided_at DESC NULLS LAST, v.id""", UUID.class, invoiceId).stream()
                .map(this::findById).flatMap(Optional::stream).toList();
    }

    @Override
    public void notePortalRecordOf(UUID voidedDraftId, UUID recordedId) {
        jdbc.update("INSERT INTO invoicing.einvoice_portal_record (voided_draft_id, recorded_einvoice_id) VALUES (?, ?)",
                voidedDraftId, recordedId);
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
        // einvoice_sending.held_until is when the invoice was last asked about once its lease is given up: the order
        // is a round, never-asked first. Ordered by submitted_at alone, the oldest invoices - those marked unknown to
        // the provider above all, which stay SUBMITTING for as long as the accountant leaves them - took every place
        // in the batch on every run, and nothing newer was ever asked about.
        return jdbc.queryForList("""
                SELECT i.id
                  FROM invoicing.einvoice i
                  LEFT JOIN invoicing.einvoice_sending s ON s.einvoice_id = i.id
                 WHERE i.status = 'SUBMITTING'
                   AND (s.holder IS NULL OR s.held_until <= now())
                 ORDER BY coalesce(i.warnings, '') LIKE '%UNKNOWN_TO_PROVIDER%', s.held_until NULLS FIRST,
                          i.submitted_at, i.id
                 LIMIT ?""", UUID.class, limit);
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
        // A missing e-mail alone holds nothing back (WF-05 Q-22, BR-06; EInvoice.Warning.blocksAutomaticIssue): the
        // column holds the codes sorted and comma-joined, so that one alone is exactly its name.
        return jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE status = 'DRAFT' AND type = 'ORIGINAL' AND attempt = 0
                   AND (warnings IS NULL OR warnings = 'BUYER_EMAIL_MISSING')
                 ORDER BY issue_date, id LIMIT ?""", UUID.class, limit);
    }

    @Override
    public List<UUID> findOverdueToRemind(Instant now, Instant toldBefore, int limit) {
        // ix_einvoice_status_due: status, then the deadline.
        return jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE status IN ('DRAFT', 'REJECTED', 'SUBMITTING') AND issue_due_at <= ?
                   AND due_warned_at IS NOT NULL AND COALESCE(overdue_warned_at, due_warned_at) < ?
                 ORDER BY issue_due_at, id LIMIT ?""", UUID.class, Timestamp.from(now), Timestamp.from(toldBefore),
                limit);
    }

    @Override
    public boolean markOverdueReminded(UUID id, Instant now, Instant toldBefore) {
        // One statement that checks and marks: two sweeps that overlap remind once. The version is left alone - a
        // reminder changes nothing the invoice says (tg_einvoice_guard lets the column change on any invoice not issued).
        return jdbc.update("""
                UPDATE invoicing.einvoice SET overdue_warned_at = ?
                 WHERE id = ? AND status IN ('DRAFT', 'REJECTED', 'SUBMITTING') AND issue_due_at <= ?
                   AND due_warned_at IS NOT NULL AND COALESCE(overdue_warned_at, due_warned_at) < ?""",
                Timestamp.from(now), id, Timestamp.from(now), Timestamp.from(toldBefore)) == 1;
    }

    @Override
    public Optional<String> findEarlierSubmitting(UUID id, InvoiceSeries series, LocalDate issueDate) {
        return jdbc.queryForList("""
                SELECT code FROM invoicing.einvoice
                 WHERE id <> ? AND status = 'SUBMITTING'
                   AND provider = ? AND template_code = ? AND symbol = ? AND issue_date < ?
                 ORDER BY issue_date, id LIMIT 1""", String.class, id, series.provider(), series.templateCode(),
                series.symbol(), java.sql.Date.valueOf(issueDate)).stream().findFirst();
    }

    @Override
    public Optional<String> findEarlierUnissued(UUID id, InvoiceSeries series, LocalDate issueDate) {
        // A draft or a rejected invoice carries no series of its own yet (a rejected one carries the series of its last
        // attempt, which is not necessarily the one it will be sent under): of the same year, it will be numbered under
        // the series in use for that year at the provider in use - the one asked about.
        return jdbc.queryForList("""
                SELECT code FROM invoicing.einvoice
                 WHERE id <> ? AND issue_date < ? AND issue_date >= ?
                   AND (status IN ('DRAFT', 'REJECTED')
                        OR status = 'SUBMITTING' AND provider = ? AND template_code = ? AND symbol = ?)
                 ORDER BY issue_date, id LIMIT 1""", String.class, id, java.sql.Date.valueOf(issueDate),
                java.sql.Date.valueOf(issueDate.withDayOfYear(1)), series.provider(), series.templateCode(),
                series.symbol()).stream().findFirst();
    }

    @Override
    public void lockSeries(InvoiceSeries series) {
        // As lockOrder: transaction-scoped, released at commit or rollback. One key per provider and series.
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) AS locked",
                Integer.class, "einvoice-series:%s:%s%s".formatted(series.provider(), series.templateCode(),
                        series.symbol()));
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
    public List<UUID> findHeldUnannounced(int limit) {
        // The complement of findAutoIssuable among the drafts it would take: warnings beyond a missing e-mail.
        return jdbc.queryForList("""
                SELECT i.id FROM invoicing.einvoice i
                 WHERE i.status = 'DRAFT' AND i.type = 'ORIGINAL' AND i.attempt = 0
                   AND i.warnings IS NOT NULL AND i.warnings <> 'BUYER_EMAIL_MISSING'
                   AND NOT EXISTS (SELECT 1 FROM invoicing.einvoice_held_notice n
                                    WHERE n.einvoice_id = i.id AND n.reason = i.warnings)
                 ORDER BY i.issue_date, i.id LIMIT ?""", UUID.class, limit);
    }

    @Override
    public Optional<String> markHeldForWarnings(UUID id, Instant now) {
        return jdbc.queryForList("""
                INSERT INTO invoicing.einvoice_held_notice (einvoice_id, reason, notified_at)
                SELECT i.id, i.warnings, ? FROM invoicing.einvoice i
                 WHERE i.id = ? AND i.status = 'DRAFT' AND i.type = 'ORIGINAL' AND i.attempt = 0
                   AND i.warnings IS NOT NULL AND i.warnings <> 'BUYER_EMAIL_MISSING'
                ON CONFLICT (einvoice_id, reason) DO NOTHING
                RETURNING reason""", String.class, Timestamp.from(now), id).stream().findFirst();
    }

    @Override
    public boolean markHeldAnnounced(UUID id, String reason, Instant now) {
        return jdbc.update("""
                INSERT INTO invoicing.einvoice_held_notice (einvoice_id, reason, notified_at)
                VALUES (?, ?, ?) ON CONFLICT (einvoice_id, reason) DO NOTHING""", id, reason,
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

    @Override
    public boolean clearReversed(UUID invoiceId) {
        // tg_einvoice_guard lets it go only once its reduction was voided as corrected back in this transaction.
        return jdbc.update("UPDATE invoicing.einvoice SET reversed_at = NULL WHERE id = ? AND reversed_at IS NOT NULL",
                invoiceId) == 1;
    }

    @Override
    public Optional<EInvoice> findReversedOfShipment(UUID shipmentId) {
        return jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE shipment_id = ? AND type IN ('ORIGINAL', 'REPLACEMENT') AND reversed_at IS NOT NULL
                 ORDER BY reversed_at DESC, id LIMIT 1""", UUID.class, shipmentId).stream().findFirst()
                .flatMap(this::findById);
    }

    @Override
    public Optional<EInvoice> findUnissuedShippingFeeInvoice(UUID orderId) {
        return jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE order_id = ? AND shipment_id IS NULL AND type = 'ORIGINAL'
                   AND status IN ('DRAFT', 'SUBMITTING', 'REJECTED')
                 ORDER BY created_at, id LIMIT 1""", UUID.class, orderId).stream().findFirst()
                .flatMap(this::findById);
    }

    @Override
    public List<EInvoice> findIssuedShippingFeeInvoices(UUID orderId) {
        return jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE order_id = ? AND shipment_id IS NULL AND type = 'ORIGINAL' AND status = 'ISSUED'
                 ORDER BY issued_at DESC NULLS LAST, created_at DESC, id""", UUID.class, orderId).stream()
                .map(this::findById).flatMap(Optional::stream).toList();
    }

    @Override
    public java.math.BigDecimal shippingFeeReducedOf(UUID invoiceId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(l.amount), 0) FROM invoicing.einvoice a
                  JOIN invoicing.einvoice_line l ON l.einvoice_id = a.id
                 WHERE a.original_einvoice_id = ? AND a.type = 'ADJUSTMENT' AND a.correction_basis = 'DELIVERY_FAILED'
                   AND a.status <> 'VOIDED_DRAFT' AND l.fee_kind = 'SHIPPING_FEE'""", java.math.BigDecimal.class,
                invoiceId);
    }

    @Override
    public boolean hasVoidedUnsendableReversal(UUID invoiceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM invoicing.einvoice
                                WHERE original_einvoice_id = ? AND type = 'ADJUSTMENT'
                                  AND correction_basis = 'DELIVERY_FAILED' AND status = 'VOIDED_DRAFT'
                                  AND void_reason_code = 'UNSENDABLE_REVERSAL')""", Boolean.class, invoiceId));
    }

    @Override
    public Optional<EInvoice> findLiveDeliveryFailedReductionOf(UUID invoiceId) {
        return jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE original_einvoice_id = ? AND type = 'ADJUSTMENT' AND correction_basis = 'DELIVERY_FAILED'
                   AND status <> 'VOIDED_DRAFT'
                 ORDER BY created_at DESC, id LIMIT 1""", UUID.class, invoiceId).stream().findFirst()
                .flatMap(this::findById);
    }

    @Override
    public List<EInvoice> findUnissuedCorrectionsOf(UUID invoiceId) {
        return jdbc.queryForList("""
                SELECT a.id FROM invoicing.einvoice a
                 WHERE a.status IN ('DRAFT', 'REJECTED', 'SUBMITTING')
                   AND (a.original_einvoice_id = ?
                        OR EXISTS (SELECT 1 FROM invoicing.einvoice_adjustment_listing l
                                    WHERE l.einvoice_id = a.id AND l.listed_einvoice_id = ?))
                 ORDER BY a.created_at, a.id""", UUID.class, invoiceId, invoiceId).stream()
                .map(this::findById).flatMap(Optional::stream).toList();
    }

    @Override
    public List<EInvoiceLine> issuedAdjustmentLinesOf(UUID invoiceId) {
        List<UUID> adjustments = jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice
                 WHERE original_einvoice_id = ? AND type = 'ADJUSTMENT' AND status = 'ISSUED'
                 ORDER BY issued_at, id""", UUID.class, invoiceId);
        List<EInvoiceLine> lines = new java.util.ArrayList<>();
        adjustments.forEach(id -> lines.addAll(lines(id)));
        return lines;
    }

    @Override
    public int refusalsUnder(UUID invoiceId, String rejectionCode) {
        if (rejectionCode == null) {
            return 0;
        }
        // Each attempt is its own transaction: a refusal answered twice for one attempt counts once.
        Integer count = jdbc.queryForObject("""
                SELECT count(DISTINCT transaction_id) FROM invoicing.einvoice_provider_call
                 WHERE einvoice_id = ? AND outcome = 'REJECTED' AND error_code = ?""", Integer.class, invoiceId,
                rejectionCode);
        return count == null ? 0 : count;
    }

    @Override
    public boolean returnAdjustmentUnissued(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM invoicing.einvoice
                                WHERE order_id = ? AND type = 'ADJUSTMENT' AND correction_basis = 'RETURN'
                                  AND status IN ('DRAFT', 'REJECTED', 'SUBMITTING'))""", Boolean.class, orderId));
    }

    @Override
    public boolean deliveryReductionUnissued(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM invoicing.einvoice
                                WHERE order_id = ? AND type = 'ADJUSTMENT' AND correction_basis = 'DELIVERY_FAILED'
                                  AND status IN ('DRAFT', 'REJECTED', 'SUBMITTING'))""", Boolean.class, orderId));
    }

    @Override
    public void watchTransaction(UUID einvoiceId, String transactionId, String provider, String letGoAs,
                                 Instant until) {
        jdbc.update("""
                INSERT INTO invoicing.einvoice_transaction_watch (einvoice_id, transaction_id, provider, let_go_as,
                                                                  watch_until)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (einvoice_id, transaction_id) DO NOTHING""", einvoiceId,
                transactionId, provider, letGoAs, Timestamp.from(until));
    }

    @Override
    public List<WatchedTransaction> findWatchedToAsk(Instant now, Instant askedBefore, int limit) {
        return jdbc.query("""
                SELECT einvoice_id, transaction_id, provider, let_go_as FROM invoicing.einvoice_transaction_watch
                 WHERE found_at IS NULL AND watch_until > ?
                   AND (last_asked_at IS NULL OR last_asked_at < ?)
                 ORDER BY last_asked_at NULLS FIRST, created_at LIMIT ?""",
                (row, n) -> new WatchedTransaction(row.getObject("einvoice_id", UUID.class),
                        row.getString("transaction_id"), row.getString("provider"), row.getString("let_go_as")),
                Timestamp.from(now), Timestamp.from(askedBefore), limit);
    }

    @Override
    public Optional<TransactionWatch> latestWatchOf(UUID einvoiceId) {
        return jdbc.query("""
                SELECT transaction_id, let_go_as, watch_until, found_at FROM invoicing.einvoice_transaction_watch
                 WHERE einvoice_id = ?
                 ORDER BY created_at DESC, watch_until DESC LIMIT 1""",
                (row, n) -> new TransactionWatch(row.getString("transaction_id"), row.getString("let_go_as"),
                        row.getTimestamp("watch_until").toInstant(),
                        row.getTimestamp("found_at") == null ? null : row.getTimestamp("found_at").toInstant()),
                einvoiceId).stream().findFirst();
    }

    @Override
    public boolean watchFound(UUID einvoiceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM invoicing.einvoice_transaction_watch
                                WHERE einvoice_id = ? AND found_at IS NOT NULL)""", Boolean.class, einvoiceId));
    }

    @Override
    public void markWatchAsked(UUID einvoiceId, String transactionId, Instant at) {
        jdbc.update("""
                UPDATE invoicing.einvoice_transaction_watch SET last_asked_at = ?
                 WHERE einvoice_id = ? AND transaction_id = ?""", Timestamp.from(at), einvoiceId, transactionId);
    }

    @Override
    public boolean markWatchFound(UUID einvoiceId, String transactionId, Instant at, int number, String templateCode,
                                  String symbol, String taxAuthorityCode) {
        return jdbc.update("""
                UPDATE invoicing.einvoice_transaction_watch
                   SET found_at = ?, found_number = ?, found_template_code = ?, found_symbol = ?,
                       found_tax_authority_code = ?, last_asked_at = ?
                 WHERE einvoice_id = ? AND transaction_id = ? AND found_at IS NULL""", Timestamp.from(at), number,
                templateCode, symbol, taxAuthorityCode, Timestamp.from(at), einvoiceId, transactionId) == 1;
    }

    @Override
    public List<UUID> findResultOverdueUnannounced(Instant submittedBefore, int limit) {
        return jdbc.queryForList("""
                SELECT i.id FROM invoicing.einvoice i
                 WHERE i.status = 'SUBMITTING' AND i.submitted_at < ?
                   AND NOT EXISTS (SELECT 1 FROM invoicing.einvoice_stuck_notice n
                                    WHERE n.einvoice_id = i.id AND n.transaction_id = i.transaction_id
                                      AND n.result_overdue_at IS NOT NULL)
                 ORDER BY i.submitted_at, i.id LIMIT ?""", UUID.class, Timestamp.from(submittedBefore), limit);
    }

    @Override
    public boolean markResultOverdueAnnounced(UUID id, String transactionId, Instant now) {
        return noteOnSubmission(id, transactionId, now, "result_overdue_at");
    }

    @Override
    public boolean markSeriesMismatchAnnounced(UUID id, String transactionId, Instant now) {
        return noteOnSubmission(id, transactionId, now, "series_mismatch_at");
    }

    /** One notice per submission: the column is the fixed name of one of two, never a caller's text. */
    private boolean noteOnSubmission(UUID id, String transactionId, Instant now, String column) {
        if (!"result_overdue_at".equals(column) && !"series_mismatch_at".equals(column)) {
            throw new IllegalArgumentException(column);
        }
        return jdbc.update("""
                INSERT INTO invoicing.einvoice_stuck_notice AS n (einvoice_id, transaction_id, notified_at, %1$s)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (einvoice_id, transaction_id) DO UPDATE SET %1$s = EXCLUDED.%1$s
                 WHERE n.%1$s IS NULL""".formatted(column), id, transactionId, Timestamp.from(now),
                Timestamp.from(now)) == 1;
    }

    @Override
    public boolean markDueWarned(UUID id, Instant now) {
        return jdbc.update("""
                UPDATE invoicing.einvoice SET due_warned_at = ?
                 WHERE id = ? AND due_warned_at IS NULL AND status IN ('DRAFT', 'REJECTED', 'SUBMITTING')""",
                Timestamp.from(now), id) == 1;
    }

    @Override
    public void keepPendingReturn(PendingReturnPieces pieces, Instant now) {
        jdbc.update("""
                INSERT INTO invoicing.einvoice_pending_return (return_request_id, shipment_id, order_line_id, order_id,
                                                               rma_number, order_number, pieces, kept_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (return_request_id, shipment_id, order_line_id) DO NOTHING""",
                pieces.returnRequestId(), pieces.shipmentId(), pieces.orderLineId(), pieces.orderId(),
                pieces.rmaNumber(), pieces.orderNumber(), pieces.pieces(), Timestamp.from(now));
    }

    @Override
    public List<PendingReturnPieces> findPendingReturnsOfShipment(UUID shipmentId) {
        return jdbc.query("""
                SELECT return_request_id, rma_number, order_id, order_number, shipment_id, order_line_id, pieces
                  FROM invoicing.einvoice_pending_return
                 WHERE shipment_id = ? AND settled_at IS NULL
                 ORDER BY kept_at, return_request_id, order_line_id""",
                (row, n) -> new PendingReturnPieces(row.getObject("return_request_id", UUID.class),
                        row.getString("rma_number"), row.getObject("order_id", UUID.class),
                        row.getString("order_number"), row.getObject("shipment_id", UUID.class),
                        row.getObject("order_line_id", UUID.class), row.getLong("pieces")), shipmentId);
    }

    @Override
    public void settlePendingReturn(UUID returnRequestId, UUID shipmentId, Instant now) {
        jdbc.update("""
                UPDATE invoicing.einvoice_pending_return SET settled_at = ?
                 WHERE return_request_id = ? AND shipment_id = ? AND settled_at IS NULL""", Timestamp.from(now),
                returnRequestId, shipmentId);
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
