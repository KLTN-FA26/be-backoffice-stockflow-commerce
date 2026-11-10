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
                    if (line.getCartons() != null) {
                        mapped.withCartons(line.getUomConversionId(), line.getCartons(), line.getUnitsPerCarton());
                    }
                    if (line.getTaxRate() != null) {
                        mapped.withTax(line.getTaxRate(), new Money(line.getTaxAmount(),
                                Currency.getInstance(line.getCurrency())));
                    }
                    mapped.withRequestedDeliveryDate(line.getRequestedDeliveryDate());
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
        order.restoreContractSigned(entity.getContractSignedAt());
        order.restoreShippingWarehouse(entity.getShippingWarehouseId(), entity.getShippingWarehouseChosenAt(),
                entity.getShippingWarehouseChosenBy());
        order.restoreBalance(entity.getBalancePolicy());
        order.restorePartialShipment(entity.isAllowPartialShipment());
        order.restoreRemainderFee(entity.getRemainderFeeAmount(), entity.getRemainderFeeRetainedAmount(),
                entity.getRemainderFeePaidAmount());
        order.restoreFulfilmentStarted(entity.getFulfilmentStartedAt());
        order.restorePackingStarted(entity.getPackingStartedAt());
        order.restoreLifecycle(entity.getHoldReason(), entity.getStatusBeforeHold(), entity.getHoldNote(),
                entity.getShippedAt(), entity.getDeliveredAt(), entity.getDeliveryFailureReason(),
                entity.getCompletedAt(), entity.getRefundedAt(), entity.getClosedAt(), entity.getBilledAmount(),
                entity.getReceivablePaidAmount(), entity.getRemainderRefundAmount());
        order.attachFees(entity.getFees().stream()
                .map(fee -> new com.stockflow.order.internal.domain.OrderFee(fee.getId(), fee.getKind(),
                        fee.getSku() == null ? null : new Sku(fee.getSku()),
                        new Money(fee.getAmount(), Currency.getInstance(fee.getCurrency())), fee.getTaxRate(),
                        new Money(fee.getTaxAmount(), Currency.getInstance(fee.getCurrency()))))
                .toList());
        order.restoreShipments(shipmentRows.stream()
                .map(row -> new OrderShipment(row.getId(), row.getQuantities(), row.getAmount(), row.getHandedOverAt(),
                        row.getHandedOverBy(), row.getStatus(), row.getOutcomeAt(), row.getOutcomeBy(),
                        row.getFailureReason(), row.getFailureNote(), row.getBilledAmount(),
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
        entity.addMissingFees(toFeeEntities(order));
        entity.applyTax(order.taxTotal().amount());
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
        entity.applyContractSigned(order.contractSignedAt());
        entity.applyShippingWarehouse(order.shippingWarehouseId(), order.shippingWarehouseChosenAt(),
                order.shippingWarehouseChosenBy());
        entity.applyPlaced(order.placedAt());
        entity.applyBalance(order.balancePolicy());
        entity.applyPartialShipment(order.allowPartialShipment());
        entity.applyRemainderFee(order.remainderFeeAmount(), order.remainderFeeRetainedAmount(),
                order.remainderFeePaidAmount());
        entity.applyClosedAmount(order.closedAmount());
        entity.applyFulfilmentStarted(order.fulfilmentStartedAt());
        entity.applyPackingStarted(order.packingStartedAt());
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
                        entity.getBilledAmount(), null, null, null, List.of(), List.of(),
                        entity.isAllowPartialShipment()),
                rowCharges(entity));
    }

    /**
     * The totals a row holds (4B-3): before tax is {@code net_amount}, which the database generates as the total less
     * the VAT. The fee lines are not loaded for a row: null, so nobody reads "no fee lines" from it.
     */
    private static OrderSummary.Charges rowCharges(OrderJpaEntity entity) {
        Currency currency = Currency.getInstance(entity.getCurrency());
        return new OrderSummary.Charges(new Money(entity.getTotalAmount().subtract(entity.getTaxAmount()), currency),
                new Money(entity.getTaxAmount(), currency), null);
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
        entity.addMissingFees(toFeeEntities(order));
        entity.applyTax(order.taxTotal().amount());
        applyTermsAndRelease(order, entity);
    }

    private static List<com.stockflow.order.internal.entity.OrderFeeLineJpaEntity> toFeeEntities(Order order) {
        return order.fees().stream().map(fee -> new com.stockflow.order.internal.entity.OrderFeeLineJpaEntity(fee.id(),
                fee.kind(), fee.sku() == null ? null : fee.sku().code(), fee.amount().amount(), fee.taxRate(),
                fee.taxAmount().amount(), fee.amount().currency().getCurrencyCode())).toList();
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
                    mapped.setRequestedDeliveryDate(line.requestedDeliveryDate());
                    mapped.setCartons(line.uomConversionId(), line.cartons(), line.unitsPerCarton());
                    mapped.setTax(line.taxRate(), line.taxAmount() == null ? null : line.taxAmount().amount());
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
