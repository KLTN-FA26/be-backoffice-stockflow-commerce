package com.stockflow.order.internal.controller;

import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.controller.dto.OrderResponse;

import java.util.List;

/**
 * Maps the order module's summary types to the web DTOs, and a cancellation fee request in.
 *
 * <p>Hand-written rather than MapStruct, unlike inventory's web mapper: the shapes do not line up. {@code Money} has
 * to be split into an amount and a currency on the way out, which reads better as code than as a MapStruct expression
 * string. Nothing maps an order in: no order is placed over HTTP since team plan 5-2 (kltn-docs 14 BR-01, BR-02).</p>
 */
final class OrderWebMapper {

    private OrderWebMapper() {}

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
                                                        ? progress.get(line.lineId()).cancelledQuantity() : null,
                                                line.requestedDeliveryDate(), line.cartons(), line.unitsPerCarton(),
                                                line.taxRate(),
                                                line.taxAmount() == null ? null : line.taxAmount().amount()))
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
                                        .toList()))
                        .toList(),
                lifecycle == null ? null : lifecycle.allowPartialShipment(),
                // Printing is outside the system: whatever an order holds is in stock, so the goods are ready.
                lifecycle == null ? null : Boolean.TRUE,
                summary.charges() == null ? null : summary.charges().netTotal().amount(),
                summary.charges() == null ? null : summary.charges().taxTotal().amount(),
                summary.charges() == null || summary.charges().fees() == null ? null : summary.charges().fees().stream()
                        .map(fee -> new OrderResponse.Fee(fee.kind().name(), fee.sku(), fee.amount().amount(),
                                fee.taxRate(), fee.taxAmount().amount())).toList());
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
