package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.entity.OrderJpaEntity;
import com.stockflow.order.internal.entity.OrderLineJpaEntity;
import com.stockflow.order.internal.entity.OrderAddressJpaEmbeddable;

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

        return new Order(
                new OrderId(entity.getId()),
                new OrderNumber(entity.getOrderNumber()),
                entity.getCustomerId(),
                entity.getRequestId(),
                lines,
                entity.getStatus(),
                entity.getPlacedAt(),
                entity.getCancellationReason(),
                entity.getVersion(),
                entity.getContactName(), entity.getContactEmail(), entity.getContactPhone(),
                toDomain(entity.getShippingAddress()), toDomain(entity.getBillingAddress()));
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
        return entity;
    }

    static void applyToEntity(Order order, OrderJpaEntity entity) {
        Money total = order.total();
        entity.apply(order.status(), total.amount(),
                total.currency().getCurrencyCode(), order.cancellationReason());
        entity.replaceLines(toLineEntities(order));
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
