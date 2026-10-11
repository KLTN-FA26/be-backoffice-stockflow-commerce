package com.stockflow.procurement.internal.repository;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.procurement.internal.domain.PoLine;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.entity.PoLineJpaEntity;
import com.stockflow.procurement.internal.entity.PurchaseOrderJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link SubcontractOrderStore} on the purchase order's own JPA entities: the order is built by the
 * {@link PurchaseOrder#subcontract} factory and stored the way every order is, so the aggregate's
 * checks, the {@code @Version} column and the audit columns hold for it too. Only the document number
 * sequence and the timeline event are SQL, both shared with the rest of the module.
 */
@Repository
class SubcontractOrderStoreAdapter implements SubcontractOrderStore {

    private final PurchaseOrderJpaRepository orders;
    private final SupplierJpaRepository suppliers;
    private final PurchaseOrderEventLog events;

    @PersistenceContext
    private EntityManager entityManager;

    SubcontractOrderStoreAdapter(PurchaseOrderJpaRepository orders, SupplierJpaRepository suppliers,
                                 PurchaseOrderEventLog events) {
        this.orders = orders;
        this.suppliers = suppliers;
        this.events = events;
    }

    @Override
    public Optional<Row> findLiveByProductionOrder(UUID productionOrderId) {
        return orders.findByTypeAndProductionOrderIdAndStatusNot(PurchaseOrder.Type.SUBCONTRACT,
                productionOrderId, PurchaseOrderStatus.CANCELLED).map(SubcontractOrderStoreAdapter::toRow);
    }

    /** {@code forUpdate}: locked, then re-read — the context's copy may predate the lock. */
    @Override
    public Optional<Row> findById(UUID purchaseOrderId, boolean forUpdate) {
        Optional<PurchaseOrderJpaEntity> order = forUpdate
                ? orders.findByIdForUpdate(purchaseOrderId).map(locked -> {
                    entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);
                    locked.getLines().forEach(entityManager::refresh);
                    return locked;
                })
                : orders.findWithLinesById(purchaseOrderId);
        return order.filter(o -> o.getType() == PurchaseOrder.Type.SUBCONTRACT).map(SubcontractOrderStoreAdapter::toRow);
    }

    @Override
    public Optional<Supplier> supplier(UUID supplierId) {
        return suppliers.findById(supplierId).map(s -> new Supplier(s.getId(), s.getCode(), s.getName(),
                s.isPrintSubcontractor(), s.getLossTolerancePercent(), s.getPaymentTermDays(), s.getLeadTimeDays()));
    }

    @Override
    public String nextNumber(LocalDate day) {
        Number value = (Number) entityManager.createNativeQuery("""
                        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                        VALUES ('SPO', :day, 1)
                        ON CONFLICT (document_type, sequence_date)
                        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                        RETURNING last_value""")
                .setParameter("day", day)
                .getSingleResult();
        return "SPO-%s-%04d".formatted(day.format(DateTimeFormatter.BASIC_ISO_DATE), value.longValue());
    }

    @Override
    public UUID insert(NewOrder o) {
        Currency currency = Currency.getInstance(o.currency());
        PoLine line = PurchaseOrder.line(1, o.inventoryItemId(), new Sku(o.sku()), o.uom(), null, o.quantity(),
                new Money(o.unitPrice(), currency), BigDecimal.ZERO);
        PurchaseOrder order = PurchaseOrder.subcontract(o.poNumber(), o.productionOrderId(), o.supplier().id(),
                o.warehouseId(), currency, line, o.orderDate(), o.expectedDate(), o.supplier().paymentTermDays(),
                o.supplier().leadTimeDays());
        PurchaseOrderJpaEntity entity = new PurchaseOrderJpaEntity(order);
        entity.addLine(new PoLineJpaEntity(line));
        entity.apply(order);
        orders.saveAndFlush(entity);
        events.record(entity.getId(), null, "CREATED", o.createdBy(), null, PurchaseOrderStatus.DRAFT.name(),
                "Raised for production order " + o.productionOrderId(), null, "subcontracting");
        return entity.getId();
    }

    @Override
    public void changeQuantity(Row row, int newQuantity, String reason, UUID actorId) {
        PurchaseOrderJpaEntity order = orders.findWithLinesById(row.id()).orElseThrow(() ->
                new IllegalStateException("Subcontract order " + row.poNumber() + " vanished while it was being changed"));
        order.getLines().forEach(line -> line.reviseDraftQuantity(newQuantity));
        order.recomputeDraftTotals();
        orders.saveAndFlush(order);
        events.record(order.getId(), null, "REVISED", actorId, row.status(), row.status(), reason, null,
                "subcontracting");
    }

    private static Row toRow(PurchaseOrderJpaEntity order) {
        // check_subcontract_po_single_line: exactly one line.
        PoLineJpaEntity line = order.getLines().get(0);
        return new Row(order.getId(), order.getPoNumber(), order.getProductionOrderId(), order.getSupplierId(),
                order.getWarehouseId(), order.getStatus().name(), line.getInventoryItemId(), line.getOrderedQuantity(),
                line.getUnitPrice(), order.getCurrency(), order.getTotalAmount(), order.getExpectedAt());
    }
}
