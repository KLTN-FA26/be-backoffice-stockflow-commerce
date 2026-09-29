package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.entity.POLineJpaEntity;
import com.stockflow.procurement.internal.entity.PurchaseOrderJpaEntity;
import com.stockflow.procurement.internal.domain.PoLine;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;

import java.util.Currency;
import java.util.List;

/**
 * Translates between the {@code PurchaseOrder} aggregate and its rows.
 *
 * <p>Hand-written rather than MapStruct, same reasoning as {@code ProductPersistenceMapper}:
 * rehydration goes through the aggregate's constructor so its invariants are re-checked.</p>
 */
final class PurchaseOrderPersistenceMapper {

    private PurchaseOrderPersistenceMapper() {
    }

    static PurchaseOrder toDomain(PurchaseOrderJpaEntity entity) {
        Currency currency = Currency.getInstance(entity.getCurrency());
        List<PoLine> lines = entity.getLines().stream()
                .map(line -> toDomain(line, currency))
                .toList();

        return new PurchaseOrder(
                new PurchaseOrderId(entity.getId()),
                entity.getPoNumber(),
                entity.getSupplierId(),
                entity.getStatus(),
                currency,
                lines,
                entity.getExpectedAt(),
                entity.getCancellationReason(),
                entity.getCloseShortReason(),
                entity.getPaymentTermDays(), entity.getLeadTimeDays(), entity.getSentAt(),
                entity.getSupplierConfirmationStatus(), entity.getSupplierRespondedAt(),
                entity.getSupplierReference(), entity.getSupplierResponseNote(),
                entity.getReceiptCompletedAt(), entity.getVersion(),
                entity.getCreatedAt(),
                entity.getCreatedBy(),
                entity.getLastModifiedAt(),
                entity.getLastModifiedBy());
    }

    private static PoLine toDomain(POLineJpaEntity entity, Currency currency) {
        return new PoLine(entity.getId(), new Sku(entity.getSku()), entity.getDescription(),
                entity.getQuantityOrdered(), entity.getQuantityReceived(),
                new Money(entity.getUnitPrice(), currency));
    }

    /** For the paginated list query only. Deliberately never touches {@code entity.getLines()}. */
    static PurchaseOrderSummary toSummaryWithoutLines(PurchaseOrderJpaEntity entity) {
        return new PurchaseOrderSummary(
                entity.getId(),
                entity.getPoNumber(),
                entity.getSupplierId(),
                entity.getStatus().name(),
                entity.getCurrency(),
                entity.getTotalAmount(),
                entity.getExpectedAt(),
                List.of(),
                entity.getCreatedAt(),
                entity.getCreatedBy(),
                entity.getLastModifiedAt(),
                entity.getLastModifiedBy(),
                false,
                entity.getCancellationReason(),
                entity.getCloseShortReason(), entity.getPaymentTermDays(), entity.getLeadTimeDays(),
                entity.getSentAt(), entity.getSupplierConfirmationStatus().name(), entity.getSupplierRespondedAt(),
                entity.getSupplierReference(), entity.getSupplierResponseNote());
    }

    /** Fresh row for an aggregate that has never been persisted. */
    static PurchaseOrderJpaEntity toNewEntity(PurchaseOrder order) {
        PurchaseOrderJpaEntity entity = new PurchaseOrderJpaEntity(
                order.id().value(),
                order.poNumber(),
                order.supplierId(),
                order.status(),
                order.currency().getCurrencyCode(),
                order.totalAmount().amount(),
                order.expectedAt(),
                order.cancellationReason(),
                order.closeShortReason(), order.paymentTermDays(), order.leadTimeDays(), order.sentAt(),
                order.supplierConfirmationStatus(), order.supplierRespondedAt(), order.supplierReference(),
                order.supplierResponseNote());
        entity.replaceLines(toEntities(order));
        entity.recordCompletion(order.receiptCompletedAt());
        return entity;
    }

    /**
     * Copy the aggregate's state onto a row already managed by the persistence context.
     *
     * <p>Deliberately does <b>not</b> call {@link PurchaseOrderJpaEntity#replaceLines} —
     * {@code toEntities} builds brand-new {@code POLineJpaEntity} instances whose {@code @Version}
     * defaults to 0, and merging those over rows whose real version has already advanced (e.g.
     * after the order's first save) fails Hibernate's optimistic lock on every save after the
     * first. Lines are never added or removed after creation, so updating each managed line's
     * {@code quantityReceived} in place — the only field {@link PoLine#receive} ever changes — is
     * both sufficient and keeps each row's own version intact.</p>
     */
    static void applyToEntity(PurchaseOrder order, PurchaseOrderJpaEntity entity) {
        entity.confirmExpectedAt(order.expectedAt());
        entity.recordCompletion(order.receiptCompletedAt());
        entity.apply(order.status(), order.totalAmount().amount(),
                order.cancellationReason(), order.closeShortReason(), order.sentAt(),
                order.supplierConfirmationStatus(), order.supplierRespondedAt(),
                order.supplierReference(), order.supplierResponseNote());
        for (PoLine line : order.lines()) {
            entity.getLines().stream()
                    .filter(e -> e.getId().equals(line.id()))
                    .findFirst()
                    .ifPresent(e -> e.recordReceipt(line.quantityReceived()));
        }
    }

    private static List<POLineJpaEntity> toEntities(PurchaseOrder order) {
        return order.lines().stream()
                .map(line -> new POLineJpaEntity(line.id(), line.sku().code(), line.description(),
                        line.quantityOrdered(), line.quantityReceived(), line.unitPrice().amount()))
                .toList();
    }
}
