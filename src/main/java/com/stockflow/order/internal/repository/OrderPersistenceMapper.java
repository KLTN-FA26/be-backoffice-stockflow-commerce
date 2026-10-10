package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.entity.OrderJpaEntity;
import com.stockflow.order.internal.entity.OrderLineJpaEntity;
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
        List<OrderLine> lines = entity.getLines().stream()
                .map(line -> {
                    var mapped = new OrderLine(
                        line.getId(),
                        new Sku(line.getSku()),
                        line.getQuantity(),
                        new Money(line.getUnitPrice(), Currency.getInstance(line.getCurrency())),
                        line.getDesignSnapshotId(),
                        List.copyOf(line.getReservationIds()));
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
                entity.getCancellationReasonCode(), entity.getCancellationRetainedAmount());
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
                order.cancellationRetainedAmount());
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
                        PaymentStatus.of(entity.getPaymentTerm(), entity.getPaidAmount(), entity.getPaidInFullAt())),
                entity.getStatus() == OrderStatus.CANCELLED
                        ? new OrderSummary.Cancellation(entity.getCancellationReasonCode(),
                                entity.getCancellationReason(), entity.getCancellationRetainedAmount())
                        : null);
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
