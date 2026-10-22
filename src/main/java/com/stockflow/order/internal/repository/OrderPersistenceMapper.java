package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.entity.OrderJpaEntity;
import com.stockflow.order.internal.entity.OrderLineJpaEntity;
import com.stockflow.order.internal.entity.OrderShipmentJpaEntity;
import com.stockflow.order.internal.domain.OrderShipment;
import com.stockflow.order.internal.entity.OrderAddressJpaEmbeddable;

import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderAddressSnapshot;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;

import java.util.Currency;
import java.util.List;

/** Translates between the {@link Order} aggregate and its rows. */
final class OrderPersistenceMapper {

    private OrderPersistenceMapper() {
    }

    static Order toDomain(OrderJpaEntity entity) {
        return toDomain(entity, List.of());
    }

    static Order toDomain(OrderJpaEntity entity, List<OrderShipmentJpaEntity> shipmentRows) {
        List<OrderLine> lines = entity.getLines().stream()
                .map(line -> {
                    var mapped = new OrderLine(
                        line.getId(),
                        new Sku(line.getSku()),
                        line.getQuantity(),
                        new Money(line.getUnitPrice(), Currency.getInstance(line.getCurrency())),
                        line.getDesignSnapshotId(),
                        List.copyOf(line.getReservationIds()),
                        line.getCancelledQuantity());
                    if (line.getDesignChecksum() != null) { mapped.recordDesignChecksum(line.getDesignChecksum()); }
                    return mapped;
                })
                .toList();

        Order order = new Order(
                new OrderId(entity.getId()),
                new OrderNumber(entity.getOrderNumber()),
                entity.getCustomerId(),
                entity.getRequestId(),
                lines,
                entity.getStatus(),
                entity.getPlacedAt(),
                entity.getCancellationReason(),
                entity.getVersion(),
                entity.getCreatedBy(), entity.getLastModifiedAt(), entity.getLastModifiedBy(),
                entity.getContactName(), entity.getContactEmail(), entity.getContactPhone(),
                toDomain(entity.getShippingAddress()), toDomain(entity.getBillingAddress()));
        order.restoreTermsAndRelease(entity.getPaymentTerm(), entity.getDepositRequired(),
                entity.getDepositReceivedAt(), entity.getWarehouseId(), entity.getReleasedAt(),
                entity.getReleasedBy());
        order.restorePaymentAndCancellation(entity.getPaidAmount(), entity.getPaidInFullAt(),
                entity.getCancellationReasonCode(), entity.getCancellationRetainedAmount(), entity.getCreditTermDays());
        order.restoreCancellationFee(entity.getCancellationFeeAmount(), entity.getCancellationFeeReason(),
                entity.getCancellationFeeDetail());
        order.restoreFulfilmentFacts(entity.getPrintingStartedAt(), entity.getPackedAt());
        order.restoreGoodsLeft(entity.getGoodsLeftAt());
        order.restoreBalance(entity.getBalancePolicy(), entity.getCodAmount());
        order.restorePartialShipment(entity.isAllowPartialShipment(), entity.isAwaitingProduction());
        order.restoreRemainderFee(entity.getRemainderFeeAmount(), entity.getRemainderFeeRetainedAmount(),
                entity.getRemainderFeePaidAmount());
        order.restoreFulfilmentStarted(entity.getFulfilmentStartedAt());
        order.restoreDeliveryMode(entity.getDeliveryMode(), entity.getPackingStartedAt());
        order.restoreLifecycle(entity.getHoldReason(), entity.getStatusBeforeHold(), entity.getHoldNote(),
                entity.getShippedAt(), entity.getDeliveredAt(), entity.getDeliveryFailureReason(),
                entity.getCompletedAt(), entity.getRefundedAt(), entity.getClosedAt(), entity.getBilledAmount(),
                entity.getReceivablePaidAmount(), entity.getRemainderRefundAmount());
        order.restoreShipments(shipmentRows.stream()
                .map(row -> new OrderShipment(row.getId(), row.getQuantities(), row.getAmount(), row.getHandedOverAt(),
                        row.getHandedOverBy(), row.getStatus(), row.getOutcomeAt(), row.getOutcomeBy(),
                        row.getFailureReason(), row.getFailureNote(), row.getBilledAmount(), row.getCodAmount(),
                        row.getCarriage()))
                .toList());
        return order;
    }

    static OrderJpaEntity toNewEntity(Order order) {
        Money total = order.total();
        OrderJpaEntity entity = new OrderJpaEntity(
                order.id().value(),
                order.orderNumber().value(),
                order.customerId(),
                order.requestId(),
                order.status(),
                total.amount(),
                total.currency().getCurrencyCode(),
                order.placedAt(),
                order.cancellationReason(), order.contactName(), order.contactEmail(),
                order.contactPhone(), toEntity(order.shippingAddress()),
                toEntity(order.billingAddress()));
        entity.replaceLines(toLineEntities(order));
        applyTermsAndRelease(order, entity);
        return entity;
    }

    private static void applyTermsAndRelease(Order order, OrderJpaEntity entity) {
        entity.applyTermsAndRelease(order.paymentTerm(), order.depositRequired(), order.depositReceivedAt(),
                order.warehouseId(), order.releasedAt(), order.releasedBy());
        entity.applyPaymentAndCancellation(order.paidAmount(), order.paidInFullAt(), order.cancellationReasonCode(),
                order.cancellationRetainedAmount(), order.creditTermDays());
        entity.applyCancellationFee(order.cancellationFeeAmount(), order.cancellationFeeReason(),
                order.cancellationFeeDetail());
        entity.applyFulfilmentFacts(order.printingStartedAt(), order.packedAt());
        entity.applyGoodsLeft(order.goodsLeftAt());
        entity.applyBalance(order.balancePolicy(), order.codAmount());
        entity.applyPartialShipment(order.allowPartialShipment(), order.awaitingProduction());
        entity.applyRemainderFee(order.remainderFeeAmount(), order.remainderFeeRetainedAmount(),
                order.remainderFeePaidAmount());
        entity.applyFulfilmentStarted(order.fulfilmentStartedAt());
        entity.applyDeliveryMode(order.deliveryMode(), order.packingStartedAt());
        entity.applyLifecycle(order.holdReason(), order.statusBeforeHold(), order.holdNote(), order.shippedAt(),
                order.deliveredAt(), order.deliveryFailureReason(), order.completedAt(), order.refundedAt(),
                order.closedAt(), order.billedAmount(), order.receivablePaidAmount(), order.remainderRefundAmount());
    }

    /** For the paginated order-history query only. Deliberately never touches {@code
     *  entity.getLines()} — see {@code OrderSearchRepository}'s own javadoc for why. */
    static OrderSummary toSummaryWithoutLines(OrderJpaEntity entity) {
        return new OrderSummary(
                entity.getId(),
                entity.getOrderNumber(),
                entity.getCustomerId(),
                entity.getStatus(),
                new Money(entity.getTotalAmount(), Currency.getInstance(entity.getCurrency())),
                List.of(),
                entity.getPlacedAt(),
                entity.getCreatedBy(),
                entity.getLastModifiedAt(),
                entity.getLastModifiedBy(),
                // The order-history rows carry no contact block or addresses: the detail view has them.
                null, null, null, null, null);
    }

    /** A list row: contact block and shipping address (the list shows the recipient), no lines. */
    static OrderSummary toListSummary(OrderJpaEntity entity) {
        return new OrderSummary(
                entity.getId(),
                entity.getOrderNumber(),
                entity.getCustomerId(),
                entity.getStatus(),
                new Money(entity.getTotalAmount(), Currency.getInstance(entity.getCurrency())),
                List.of(),
                entity.getPlacedAt(),
                entity.getCreatedBy(),
                entity.getLastModifiedAt(),
                entity.getLastModifiedBy(),
                entity.getContactName(), entity.getContactEmail(), entity.getContactPhone(),
                toSummary(entity.getShippingAddress()), null,
                entity.getPaymentTerm(), entity.getWarehouseId(), entity.getReleasedAt(),
                new OrderSummary.Payment(entity.getPaidAmount(), entity.getDepositRequired(),
                        entity.getDepositReceivedAt(), entity.getPaidInFullAt(),
                        PaymentStatus.of(entity.getPaymentTerm(), entity.getPaidAmount(), entity.getPaidInFullAt()),
                        entity.getCreditTermDays(), entity.getBalancePolicy()),
                entity.getStatus() == OrderStatus.CANCELLED
                        ? new OrderSummary.Cancellation(entity.getCancellationReasonCode(),
                                entity.getCancellationReason(), entity.getCancellationRetainedAmount(),
                                entity.getCancellationFeeReason(), entity.getCancellationFeeDetail(),
                                entity.getCancellationFeeAmount())
                        : null,
                // A list row: the hold and the milestones; no lines, no shipments, no derived money.
                new OrderSummary.Lifecycle(entity.getHoldReason(), entity.getStatusBeforeHold(), entity.getHoldNote(),
                        entity.getPackedAt(), entity.getShippedAt(), entity.getDeliveredAt(),
                        entity.getDeliveryFailureReason(), entity.getCompletedAt(), entity.getClosedAt(),
                        entity.getBilledAmount(), null, null, null, List.of(), List.of(), null, null,
                        entity.isAllowPartialShipment(), printLinesReady(entity), entity.getDeliveryMode(),
                        com.stockflow.order.internal.domain.Order.deliveryModeLocked(entity.getStatus(),
                                entity.getStatusBeforeHold(), entity.getPackingStartedAt(), entity.getPackedAt())));
    }

    /**
     * A list row's view of {@code Order#printLinesReady} without its lines: not while it is printed (or held from
     * there), nor while it waits for production after its stock left (review of #150). An order with no print line
     * reads as not ready only in the first two cases, which a stock-only order never is in.
     */
    private static boolean printLinesReady(OrderJpaEntity entity) {
        boolean printing = entity.getStatus() == OrderStatus.IN_PRODUCTION
                || entity.getStatus() == OrderStatus.ON_HOLD && entity.getStatusBeforeHold() == OrderStatus.IN_PRODUCTION;
        return !printing && !entity.isAwaitingProduction();
    }

    private static OrderSummary.AddressSummary toSummary(OrderAddressJpaEmbeddable a) {
        if (a == null || a.getRecipientName() == null && a.getLine1() == null) {
            return null;
        }
        return new OrderSummary.AddressSummary(a.getRecipientName(), a.getPhone(), a.getLine1(), a.getLine2(),
                a.getWardCode(), a.getWardName(), a.getProvinceCode(), a.getProvinceName(),
                a.getCountryCode(), a.getPostalCode());
    }

    static void applyToEntity(Order order, OrderJpaEntity entity) {
        Money total = order.total();
        entity.apply(order.status(), total.amount(),
                total.currency().getCurrencyCode(), order.cancellationReason());
        entity.replaceLines(toLineEntities(order));
        applyTermsAndRelease(order, entity);
    }

    private static List<OrderLineJpaEntity> toLineEntities(Order order) {
        return order.lines().stream()
                .map(line -> {
                    var mapped = new OrderLineJpaEntity(
                        line.id(),
                        line.sku().code(),
                        line.quantity(),
                        line.unitPrice().amount(),
                        line.unitPrice().currency().getCurrencyCode(),
                        line.designSnapshotId(),
                        line.reservationIds());
                    mapped.setDesignChecksum(line.designChecksum());
                    mapped.setCancelledQuantity(line.cancelledQuantity());
                    return mapped;
                })
                .toList();
    }

    private static OrderAddressSnapshot toDomain(OrderAddressJpaEmbeddable address) {
        if (address == null) return null;
        return new OrderAddressSnapshot(address.getRecipientName(), address.getPhone(),
                address.getLine1(), address.getLine2(), address.getWardCode(), address.getWardName(),
                address.getProvinceCode(), address.getProvinceName(), address.getCountryCode(),
                address.getPostalCode());
    }

    private static OrderAddressJpaEmbeddable toEntity(OrderAddressSnapshot address) {
        if (address == null) return null;
        return new OrderAddressJpaEmbeddable(address.recipientName(), address.phone(),
                address.line1(), address.line2(), address.wardCode(), address.wardName(),
                address.provinceCode(), address.provinceName(), address.countryCode(),
                address.postalCode());
    }
}
