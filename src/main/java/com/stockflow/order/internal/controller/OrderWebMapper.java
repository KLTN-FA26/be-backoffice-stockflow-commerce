package com.stockflow.order.internal.controller;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceGuestOrderCommand;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.controller.dto.PlaceGuestOrderRequest;
import com.stockflow.order.internal.controller.dto.PlaceOrderRequest;

import java.util.Currency;
import java.util.List;

/**
 * Maps between the web DTOs and the order module's public command and summary types.
 *
 * <p>Hand-written rather than MapStruct, unlike inventory's web mapper: the shapes do not line up.
 * {@code Money} has to be split into an amount and a currency on the way out and reassembled on the
 * way in, and the order currency is a policy decision (OQ-09: VND only for now) that belongs in
 * readable code rather than in a MapStruct expression string.
 */
final class OrderWebMapper {

    /** OQ-09: the platform is single-currency until the export plan is settled. */
    private static final Currency ORDER_CURRENCY = Money.VND;

    private OrderWebMapper() {}

    static PlaceOrderCommand toCommand(PlaceOrderRequest request) {
        List<PlaceOrderCommand.Line> lines =
                request.lines().stream()
                        .map(
                                line ->
                                        new PlaceOrderCommand.Line(
                                                new Sku(line.sku()),
                                                line.quantity(),
                                                wholeVnd(line.unitPrice()),
                                                line.designSnapshotId()))
                        .toList();
        return new PlaceOrderCommand(
                request.requestId(),
                request.customerId(),
                request.shippingAddressId(),
                request.billingAddressId(),
                true,
                lines,
                request.paymentTerm(),
                request.depositPercent(),
                request.balancePolicy());
    }

    static PlaceGuestOrderCommand toCommand(PlaceGuestOrderRequest request) {
        var shipping = toCommand(request.shippingAddress());
        var billing =
                request.billingSameAsShipping() || request.billingAddress() == null
                        ? shipping
                        : toCommand(request.billingAddress());
        return new PlaceGuestOrderCommand(
                request.requestId(),
                request.email(),
                shipping,
                billing,
                request.lines().stream()
                        .map(
                                line ->
                                        new PlaceGuestOrderCommand.Line(
                                                new Sku(line.sku()),
                                                line.quantity(),
                                                wholeVnd(line.unitPrice())))
                        .toList());
    }

    private static Money wholeVnd(java.math.BigDecimal amount) {
        if (amount == null || amount.stripTrailingZeros().scale() > 0)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return new Money(amount, ORDER_CURRENCY);
    }

    static OrderResponse toResponse(OrderSummary summary) {
        return toResponse(summary, true);
    }

    /**
     * {@code internal} false for a customer reading their own order: the agreed cancellation amount and why they pay
     * it are theirs to see, but {@code feeDetail} - what Sales agreed with whom and how it was counted - is the back
     * office's note (review of #109, nit).
     */
    static OrderResponse toResponse(OrderSummary summary, boolean internal) {
        OrderSummary.Lifecycle lifecycle = summary.lifecycle();
        java.util.Map<java.util.UUID, OrderSummary.LineProgress> progress = lifecycle == null ? java.util.Map.of()
                : lifecycle.lines().stream().collect(java.util.stream.Collectors.toMap(OrderSummary.LineProgress::lineId,
                        line -> line));
        return new OrderResponse(
                summary.orderId(),
                summary.orderNumber(),
                summary.customerId(),
                summary.status().name(),
                summary.total().amount(),
                summary.total().currency().getCurrencyCode(),
                summary.lines().stream()
                        .map(
                                line ->
                                        new OrderResponse.Line(
                                                line.lineId(),
                                                line.sku(),
                                                line.quantity(),
                                                line.unitPrice().amount(),
                                                line.lineTotal().amount(),
                                                line.reservationIds(),
                                                line.designSnapshotId(),
                                                line.designChecksum(),
                                                progress.containsKey(line.lineId())
                                                        ? progress.get(line.lineId()).shippedQuantity() : null,
                                                progress.containsKey(line.lineId())
                                                        ? progress.get(line.lineId()).deliveredQuantity() : null,
                                                progress.containsKey(line.lineId())
                                                        ? progress.get(line.lineId()).cancelledQuantity() : null))
                        .toList(),
                summary.placedAt(), summary.createdBy(), summary.lastModifiedAt(), summary.lastModifiedBy(),
                summary.contactName(), summary.contactEmail(), summary.contactPhone(),
                toResponse(summary.shippingAddress()), toResponse(summary.billingAddress()),
                summary.paymentTerm() == null ? null : summary.paymentTerm().name(),
                summary.warehouseId(), summary.releasedAt(),
                summary.payment() == null ? null : summary.payment().paidAmount(),
                summary.payment() == null ? null : summary.payment().depositRequired(),
                summary.payment() == null ? null : summary.payment().depositReceivedAt(),
                summary.payment() == null ? null : summary.payment().paidInFullAt(),
                summary.payment() == null ? null : summary.payment().status().name(),
                summary.payment() == null ? null : summary.payment().creditTermDays(),
                summary.payment() == null || summary.payment().balancePolicy() == null ? null
                        : summary.payment().balancePolicy().name(),
                summary.cancellation() == null ? null : new OrderResponse.Cancellation(
                        summary.cancellation().reasonCode() == null ? null : summary.cancellation().reasonCode().name(),
                        summary.cancellation().reason(), summary.cancellation().retainedAmount(),
                        summary.cancellation().feeReason(), internal ? summary.cancellation().feeDetail() : null,
                        summary.cancellation().feeAmount()),
                // A customer never reads why the shop holds the order (FRAUD...) nor where it goes back: only the
                // public wording, as on GET /orders/me/{id} (review of G3-2).
                lifecycle == null || !internal || lifecycle.holdReason() == null ? null : lifecycle.holdReason().name(),
                lifecycle == null || !internal || lifecycle.statusBeforeHold() == null ? null
                        : lifecycle.statusBeforeHold().name(),
                lifecycle == null || lifecycle.holdReason() == null ? null
                        : lifecycle.holdReason() == com.stockflow.order.api.HoldReason.CREDIT
                        ? "AWAITING_CREDIT_APPROVAL" : "UNDER_REVIEW",
                lifecycle == null || !internal ? null : lifecycle.holdNote(),
                lifecycle == null ? null : lifecycle.packedAt(),
                lifecycle == null ? null : lifecycle.shippedAt(),
                lifecycle == null ? null : lifecycle.deliveredAt(),
                lifecycle == null || lifecycle.deliveryFailureReason() == null ? null
                        : lifecycle.deliveryFailureReason().name(),
                lifecycle == null ? null : lifecycle.completedAt(),
                lifecycle == null ? null : lifecycle.closedAt(),
                lifecycle == null ? null : lifecycle.billedAmount(),
                lifecycle == null ? null : lifecycle.effectiveTotal(),
                lifecycle == null ? null : lifecycle.amountDue(),
                lifecycle == null ? null : lifecycle.amountDueBeforeHandover(),
                lifecycle == null ? List.of() : lifecycle.shipments().stream()
                        .map(shipment -> new OrderResponse.Shipment(shipment.shipmentId(), shipment.status().name(),
                                shipment.amount(), shipment.billedAmount(), shipment.handedOverAt(),
                                shipment.outcomeAt(),
                                shipment.failureReason() == null ? null : shipment.failureReason().name(),
                                shipment.lines().stream()
                                        .map(line -> new OrderResponse.ShipmentLine(line.lineId(), line.quantity()))
                                        .toList(), shipment.codAmount()))
                        .toList(),
                lifecycle == null ? null : lifecycle.amountDueOnDelivery(),
                lifecycle == null ? null : lifecycle.allowPartialShipment(),
                lifecycle == null ? null : lifecycle.printLinesReady());
    }

    private static PlaceGuestOrderCommand.Address toCommand(
            PlaceGuestOrderRequest.Address address) {
        return new PlaceGuestOrderCommand.Address(
                address.recipientName(),
                address.phone(),
                address.line1(),
                address.line2(),
                address.wardCode(),
                address.wardName(),
                address.provinceCode(),
                address.provinceName(),
                address.countryCode(),
                address.postalCode());
    }

    private static OrderResponse.Address toResponse(OrderSummary.AddressSummary address) {
        if (address == null) return null;
        return new OrderResponse.Address(
                address.recipientName(),
                address.phone(),
                address.line1(),
                address.line2(),
                address.wardCode(),
                address.wardName(),
                address.provinceCode(),
                address.provinceName(),
                address.countryCode(),
                address.postalCode());
    }

    static com.stockflow.order.api.CancellationFee toFee(
            com.stockflow.order.internal.controller.dto.CancellationFeeRequest request) {
        return request == null ? null
                : new com.stockflow.order.api.CancellationFee(request.amount(), request.reason(), request.detail());
    }
}
