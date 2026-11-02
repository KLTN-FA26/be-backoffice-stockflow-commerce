package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.Specs;
import com.stockflow.procurement.internal.domain.SupplierInvoice;
import com.stockflow.procurement.internal.domain.SupplierInvoiceRepository;
import com.stockflow.procurement.internal.entity.PoLineJpaEntity;
import com.stockflow.procurement.internal.entity.SupplierInvoiceJpaEntity;
import com.stockflow.procurement.internal.entity.SupplierInvoiceLineJpaEntity;
import com.stockflow.procurement.internal.entity.SupplierInvoiceVarianceJpaEntity;
import com.stockflow.procurement.internal.entity.SupplierJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Maps {@link SupplierInvoice} to {@code procurement.supplier_invoices} and its lines and variances. */
@Repository
class SupplierInvoiceRepositoryAdapter implements SupplierInvoiceRepository, SupplierInvoiceSearch {

    private final SupplierInvoiceJpaRepository jpa;
    private final EntityManager entityManager;

    SupplierInvoiceRepositoryAdapter(SupplierInvoiceJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<SupplierInvoice> findById(UUID invoiceId) {
        return jpa.findByIdWithLines(invoiceId).map(SupplierInvoiceRepositoryAdapter::toDomain);
    }

    /** Locked with a native {@code FOR UPDATE} first, then read and refreshed: as {@code GoodsReceiptRepositoryAdapter}. */
    @Override
    public Optional<SupplierInvoice> findByIdForUpdate(UUID invoiceId) {
        if (entityManager.createNativeQuery("select id from procurement.supplier_invoices where id = :id for update")
                .setParameter("id", invoiceId).getResultList().isEmpty()) {
            return Optional.empty();
        }
        return jpa.findByIdWithLines(invoiceId).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            return toDomain(entity);
        });
    }

    /**
     * Case does not tell two invoice numbers apart: {@code HD-001} and {@code hd-001} are one paper, and paying it twice
     * is what BR-INV-001 exists to stop. The unique key ({@code uk_supplier_invoices_number}) is the exact spelling.
     */
    @Override
    public boolean numberTaken(UUID supplierId, String invoiceNumber, UUID excluding) {
        return jpa.numberTaken(supplierId, invoiceNumber, excluding == null ? new UUID(0, 0) : excluding);
    }

    /**
     * Lines and variances are synchronised by id, in three flushes. Variances no longer wanted go first, on their own:
     * a line's row removed with them would take its variances along ({@code ON DELETE CASCADE}), and Hibernate, which
     * deletes after it updates, would then fail on the variance rows already gone. Then the header with the lines no
     * longer wanted: a corrected invoice may name another order, and {@code check_supplier_invoice_line} refuses a new
     * line of it while the header still names the old one - Hibernate inserts before it updates. New lines and
     * variances come last; a new variance only ever names a line already stored, as a match runs on saved lines.
     */
    @Override
    public SupplierInvoice save(SupplierInvoice invoice) {
        SupplierInvoiceJpaEntity entity = jpa.findByIdWithLines(invoice.id()).orElseGet(() ->
                new SupplierInvoiceJpaEntity(invoice.id(), invoice.supplierId(), invoice.currency(),
                        invoice.receivedBy()));
        boolean stored = !entity.isNew();

        Set<UUID> wantedVariances = invoice.variances().stream().map(SupplierInvoice.Variance::id)
                .collect(Collectors.toSet());
        if (entity.getVariances().removeIf(row -> !wantedVariances.contains(row.getId())) && stored) {
            jpa.saveAndFlush(entity);
        }

        Set<UUID> wantedLines = invoice.lines().stream().map(SupplierInvoice.Line::id).collect(Collectors.toSet());
        entity.getLines().removeIf(row -> !wantedLines.contains(row.getId()));
        applyHeader(entity, invoice);
        if (stored) {
            jpa.saveAndFlush(entity);
        }
        Set<UUID> storedLines = entity.getLines().stream().map(SupplierInvoiceLineJpaEntity::getId)
                .collect(Collectors.toSet());
        for (SupplierInvoice.Line line : invoice.lines()) {
            if (!storedLines.contains(line.id())) {
                entity.addLine(new SupplierInvoiceLineJpaEntity(line.id(), line.purchaseOrderLineId(),
                        line.receiptLineId(), BigDecimal.valueOf(line.quantity()), line.unitPrice(), line.taxRate(),
                        line.total()));
            }
        }

        Set<UUID> storedVariances = entity.getVariances().stream().map(SupplierInvoiceVarianceJpaEntity::getId)
                .collect(Collectors.toSet());
        for (SupplierInvoice.Variance variance : invoice.variances()) {
            if (!storedVariances.contains(variance.id())) {
                entity.addVariance(new SupplierInvoiceVarianceJpaEntity(variance.id(), variance.lineId(),
                        variance.purchaseOrderId(), variance.kind(), variance.expected(), variance.invoiced(),
                        variance.amount()));
            }
        }

        return toDomain(jpa.saveAndFlush(entity));
    }

    private static void applyHeader(SupplierInvoiceJpaEntity entity, SupplierInvoice invoice) {
        entity.apply(invoice.purchaseOrderId(), invoice.invoiceNumber(), invoice.invoiceDate(), invoice.dueDate(),
                invoice.note(), invoice.status(), invoice.subtotal(), invoice.taxTotal(), invoice.total());
        entity.applyMatch(invoice.matchedAt(), invoice.matchedBy(), invoice.priceTolerancePercent());
        entity.applyCancel(invoice.cancelledAt(), invoice.cancelledBy(), invoice.cancelReason());
        SupplierInvoice.Document scan = invoice.document();
        entity.applyScan(scan == null ? null : scan.key(), scan == null ? null : scan.name(),
                scan == null ? null : scan.contentType());
    }

    private static SupplierInvoice toDomain(SupplierInvoiceJpaEntity e) {
        List<SupplierInvoice.Line> lines = e.getLines().stream()
                .sorted(Comparator.comparing(SupplierInvoiceLineJpaEntity::getId))
                .map(l -> new SupplierInvoice.Line(l.getId(), l.getPoLineId(), l.getReceiptLineId(),
                        l.getInvoicedQuantity().intValueExact(), l.getUnitPrice(), l.getTaxRate()))
                .toList();
        List<SupplierInvoice.Variance> variances = e.getVariances().stream()
                .sorted(Comparator.comparing(SupplierInvoiceVarianceJpaEntity::getId))
                .map(v -> new SupplierInvoice.Variance(v.getId(), v.getInvoiceLineId(), v.getPurchaseOrderId(),
                        v.getKind(), v.getExpectedValue(), v.getInvoicedValue(), v.getVarianceAmount()))
                .toList();
        SupplierInvoice.Document scan = e.getFileKey() == null ? null
                : new SupplierInvoice.Document(e.getFileKey(), e.getFileName(), e.getFileContentType());
        return new SupplierInvoice(new SupplierInvoice.State(e.getId(), e.getSupplierId(), e.getPurchaseOrderId(),
                e.getCurrency(), e.getInvoiceNumber(), e.getInvoiceDate(), e.getDueDate(), e.getNote(), e.getStatus(),
                lines, variances, scan, e.getCreatedAt(), e.getReceivedBy(), e.getMatchedAt(), e.getMatchedBy(),
                e.getPriceTolerancePercent(), e.getVoidedAt(), e.getVoidedBy(), e.getVoidReason(), e.getVersion()));
    }

    // ---- search ---------------------------------------------------------------------------------------------------

    @Override
    public Page<Row> search(Criteria criteria, Pageable pageable) {
        Specification<SupplierInvoiceJpaEntity> spec = Specification.allOf(
                criteria.statuses() == null || criteria.statuses().isEmpty() ? Specs.all()
                        : Specs.in("status", criteria.statuses()),
                Specs.eq("supplierId", criteria.supplierId()),
                criteria.number() == null || criteria.number().isBlank() ? Specs.all()
                        : Specs.contains("invoiceNumber", criteria.number()),
                criteria.invoicedFrom() == null ? Specs.all()
                        : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("invoiceDate"), criteria.invoicedFrom()),
                criteria.invoicedTo() == null ? Specs.all()
                        : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("invoiceDate"), criteria.invoicedTo()),
                criteria.purchaseOrderId() == null ? Specs.all() : ofOrder(criteria.purchaseOrderId()));
        return jpa.findAll(spec, pageable).map(e -> new Row(e.getId(), e.getInvoiceNumber(), e.getSupplierId(),
                e.getPurchaseOrderId(), e.getStatus(), e.getInvoiceDate(), e.getDueDate(), e.getCurrency(),
                e.getTotalAmount()));
    }

    /** Its own order's invoices, and those over several orders with a line on one of its lines. */
    private static Specification<SupplierInvoiceJpaEntity> ofOrder(UUID purchaseOrderId) {
        return (root, query, cb) -> {
            var billed = query.subquery(UUID.class);
            var line = billed.from(SupplierInvoiceLineJpaEntity.class);
            var orderLines = billed.subquery(UUID.class);
            var poLine = orderLines.from(PoLineJpaEntity.class);
            orderLines.select(poLine.get("id")).where(cb.equal(poLine.get("purchaseOrder").get("id"), purchaseOrderId));
            billed.select(line.get("invoice").get("id")).where(line.get("poLineId").in(orderLines));
            return cb.or(cb.equal(root.get("purchaseOrderId"), purchaseOrderId), root.get("id").in(billed));
        };
    }

    @Override
    public Map<UUID, String> supplierNames(Collection<UUID> supplierIds) {
        if (supplierIds.isEmpty()) {
            return Map.of();
        }
        return entityManager.createQuery("select s from SupplierJpaEntity s where s.id in :ids", SupplierJpaEntity.class)
                .setParameter("ids", supplierIds).getResultList().stream()
                .collect(Collectors.toMap(SupplierJpaEntity::getId, SupplierJpaEntity::getName));
    }

    @Override
    public Map<UUID, Integer> varianceCounts(Collection<UUID> invoiceIds) {
        if (invoiceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> counts = new HashMap<>();
        entityManager.createQuery("""
                        select v.invoice.id, count(v) from SupplierInvoiceVarianceJpaEntity v
                         where v.invoice.id in :ids group by v.invoice.id""", Object[].class)
                .setParameter("ids", invoiceIds).getResultList()
                .forEach(row -> counts.put((UUID) row[0], ((Number) row[1]).intValue()));
        return counts;
    }
}
