package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.domain.PoLineStatus;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.ReceivingPurchaseOrder;
import com.stockflow.procurement.internal.domain.ReceivingPurchaseOrders;
import com.stockflow.procurement.internal.domain.SupplierConfirmationStatus;
import com.stockflow.procurement.internal.entity.PoLineJpaEntity;
import com.stockflow.procurement.internal.entity.PurchaseOrderJpaEntity;
import com.stockflow.procurement.internal.entity.SupplierJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Receiving's view of a purchase order, read and written through the same JPA entities as the
 * {@code PurchaseOrder} aggregate ({@link PurchaseOrderRepositoryAdapter}). The view stays narrow on
 * purpose — receiving changes line and header status and adds a timeline event, nothing else — but it
 * no longer has SQL of its own against the order tables: the {@code @Version} column, the audit
 * columns and the entity's own guards ({@link PurchaseOrderJpaEntity#advanceByReceipt}) apply to it
 * the same way they apply to every other write.
 */
@Repository
class ReceivingPurchaseOrderAdapter implements ReceivingPurchaseOrders {

    private final PurchaseOrderJpaRepository orders;
    private final SupplierJpaRepository suppliers;
    private final PurchaseOrderEventLog events;

    @PersistenceContext
    private EntityManager entityManager;

    ReceivingPurchaseOrderAdapter(PurchaseOrderJpaRepository orders, SupplierJpaRepository suppliers,
                                  PurchaseOrderEventLog events) {
        this.orders = orders;
        this.suppliers = suppliers;
        this.events = events;
    }

    @Override
    public Optional<ReceivingPurchaseOrder> find(UUID purchaseOrderId) {
        return orders.findWithLinesById(purchaseOrderId).map(this::toView);
    }

    /**
     * Locked, then re-read: the persistence context may already hold the order from before the lock,
     * and computing the status from that copy would make the lock decorative. The supplier row is read,
     * not locked.
     */
    @Override
    public Optional<ReceivingPurchaseOrder> lock(UUID purchaseOrderId) {
        return orders.findByIdForUpdate(purchaseOrderId).map(order -> {
            entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
            order.getLines().forEach(entityManager::refresh);
            return toView(order);
        });
    }

    @Override
    public void recordProgress(ReceivingPurchaseOrder view, Map<UUID, ReceivingPurchaseOrder.LineStatus> lineStatuses,
                               ReceivingPurchaseOrder.Status newStatus, UUID actorId, UUID receiptId,
                               String receiptNumber) {
        PurchaseOrderJpaEntity order = orders.findWithLinesById(view.id()).orElseThrow(() ->
                new IllegalStateException("Purchase order " + view.id() + " vanished while it was being received"));
        for (PoLineJpaEntity line : order.getLines()) {
            ReceivingPurchaseOrder.LineStatus next = lineStatuses.get(line.getId());
            if (next != null && line.getStatus() != PoLineStatus.valueOf(next.name())) {
                line.setStatus(PoLineStatus.valueOf(next.name()));
            }
        }
        boolean statusChanges = newStatus != view.status();
        if (statusChanges) {
            order.advanceByReceipt(PurchaseOrderStatus.valueOf(newStatus.name()));
        }
        orders.saveAndFlush(order);
        if (statusChanges) {
            events.record(order.getId(), order.getActiveRevisionId(), newStatus.name(), actorId,
                    view.status().name(), newStatus.name(), "Goods receipt " + receiptNumber,
                    Map.of("receiptId", receiptId.toString(), "receiptNumber", receiptNumber), "goods-receipt");
        }
    }

    private ReceivingPurchaseOrder toView(PurchaseOrderJpaEntity order) {
        BigDecimal tolerance = suppliers.findById(order.getSupplierId())
                .map(SupplierJpaEntity::getOverReceiptTolerancePercent)
                .orElse(null);
        return new ReceivingPurchaseOrder(order.getId(), order.getPoNumber(),
                ReceivingPurchaseOrder.Status.valueOf(order.getStatus().name()), order.getSupplierId(),
                order.getWarehouseId(), order.getActiveRevisionId(), tolerance,
                order.getSupplierConfirmationStatus() == SupplierConfirmationStatus.REJECTED,
                order.getLines().stream()
                        .sorted(Comparator.comparingInt(PoLineJpaEntity::getLineNo))
                        .map(line -> new ReceivingPurchaseOrder.Line(line.getId(), line.getLineNo(),
                                line.getInventoryItemId(), line.getOrderedQuantity(),
                                ReceivingPurchaseOrder.LineStatus.valueOf(line.getStatus().name())))
                        .toList());
    }
}
