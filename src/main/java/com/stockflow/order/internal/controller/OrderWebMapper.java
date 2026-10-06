package com.stockflow.order.internal.controller;

import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.api.PlaceGuestOrderCommand;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.controller.dto.PlaceOrderRequest;
import com.stockflow.order.internal.controller.dto.PlaceGuestOrderRequest;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;

import java.util.Currency;
import java.util.List;

/**
 * Maps between the web DTOs and the order module's public command and summary types.
 *
 * <p>Hand-written rather than MapStruct, unlike inventory's web mapper: the shapes do not line up.
 * {@code Money} has to be split into an amount and a currency on the way out and reassembled on
 * the way in, and the order currency is a policy decision (OQ-09: VND only for now) that belongs
 * in readable code rather than in a MapStruct expression string.</p>
 */
final class OrderWebMapper {

    /** OQ-09: the platform is single-currency until the export plan is settled. */
    private static final Currency ORDER_CURRENCY = Money.VND;

    private OrderWebMapper() {
    }

    static PlaceOrderCommand toCommand(PlaceOrderRequest request) {
        List<PlaceOrderCommand.Line> lines = request.lines().stream()
                .map(line -> new PlaceOrderCommand.Line(
                        new Sku(line.sku()),
                        line.quantity(),
                        new Money(line.unitPrice(), ORDER_CURRENCY),
                        line.designSnapshotId()))
                .toList();
        return new PlaceOrderCommand(request.requestId(), request.customerId(),
                request.shippingAddressId(), request.billingAddressId(), true, lines);
    }

    static PlaceGuestOrderCommand toCommand(PlaceGuestOrderRequest request) {
        var shipping = toCommand(request.shippingAddress());
        var billing = request.billingSameAsShipping() || request.billingAddress() == null
                ? shipping : toCommand(request.billingAddress());
        return new PlaceGuestOrderCommand(request.requestId(), request.email(), shipping, billing,
                request.lines().stream().map(line -> new PlaceGuestOrderCommand.Line(
                        new Sku(line.sku()), line.quantity(), new Money(line.unitPrice(), ORDER_CURRENCY)))
                        .toList());
    }

    static OrderResponse toResponse(OrderSummary summary) {
        return new OrderResponse(
                summary.orderId(),
                summary.orderNumber(),
                summary.customerId(),
                summary.status().name(),
                summary.total().amount(),
                summary.total().currency().getCurrencyCode(),
                summary.lines().stream()
                        .map(line -> new OrderResponse.Line(
                                line.lineId(),
                                line.sku(),
                                line.quantity(),
                                line.unitPrice().amount(),
                                line.lineTotal().amount(),
                                line.reservationIds(), line.designSnapshotId(), line.designChecksum()))
                        .toList(),
                summary.placedAt(), summary.createdBy(), summary.lastModifiedAt(), summary.lastModifiedBy(),
                summary.contactName(), summary.contactEmail(), summary.contactPhone(),
                toResponse(summary.shippingAddress()), toResponse(summary.billingAddress()));
    }

    private static PlaceGuestOrderCommand.Address toCommand(PlaceGuestOrderRequest.Address address) {
        return new PlaceGuestOrderCommand.Address(address.recipientName(), address.phone(),
                address.line1(), address.line2(), address.wardCode(), address.wardName(),
                address.provinceCode(), address.provinceName(), address.countryCode(), address.postalCode());
    }

    private static OrderResponse.Address toResponse(OrderSummary.AddressSummary address) {
        if (address == null) return null;
        return new OrderResponse.Address(address.recipientName(), address.phone(), address.line1(),
                address.line2(), address.wardCode(), address.wardName(), address.provinceCode(),
                address.provinceName(), address.countryCode(), address.postalCode());
    }
}
