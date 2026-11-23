package com.stockflow.order.internal.controller;

import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.service.OrderSources;

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

    /** F-1 as the detail shows it; null when there is nothing to show (nothing delivered yet). */
    static OrderResponse.SettlementReadiness toSettlement(
            com.stockflow.order.internal.service.SettlementReadiness.View view) {
        return view == null ? null : new OrderResponse.SettlementReadiness(view.branch().name(),
                view.returnWindowDays(), view.returnWindowEndsAt(), view.owing(), view.openReturn(),
                view.uninvoicedShipmentIds(), view.shippingFeeUninvoiced(), view.refundOutstanding(),
                view.completionHeld(), view.returnAdjustmentUnissued(), view.completesAt(), view.closesAt(),
                view.blockers().stream().map(Enum::name).toList());
    }

    static OrderResponse toResponse(OrderSummary summary) {
        return toResponse(summary, true);
    }

    /** What a back-office reader of an order sees of its customer: the name and code, from one batch lookup. */
    static OrderResponse toResponse(OrderSummary summary, com.stockflow.customer.api.CustomerLabel label) {
        return toResponse(summary, true, label);
    }

    /** The label of an order's customer in a batch read; null when there is none (or the order has no customer). */
    static com.stockflow.customer.api.CustomerLabel labelOf(
            java.util.Map<java.util.UUID, com.stockflow.customer.api.CustomerLabel> labels, java.util.UUID customerId) {
        return labels == null || customerId == null ? null : labels.get(customerId);
    }

    /**
     * {@code internal} false for a customer reading their own order: the agreed cancellation amount and why they pay
     * it are theirs to see, but {@code feeDetail} - what Sales agreed with whom and how it was counted - is the back
     * office's note (review of #109, nit).
     */
    static OrderResponse toResponse(OrderSummary summary, boolean internal) {
        return toResponse(summary, internal, null);
    }

    static OrderResponse toResponse(OrderSummary summary, boolean internal,
                                    com.stockflow.customer.api.CustomerLabel label) {
        return toResponse(summary, internal, label, OrderSources.Extras.NONE);
    }

    /** With where the order came from: its quote and the design of each line's SKU (R5-3, R5-7). */
    static OrderResponse toResponse(OrderSummary summary, boolean internal,
                                    com.stockflow.customer.api.CustomerLabel label, OrderSources.Extras extras) {
        OrderSummary.Lifecycle lifecycle = summary.lifecycle();
        java.util.Map<java.util.UUID, OrderSummary.LineProgress> progress = lifecycle == null ? java.util.Map.of()
                : lifecycle.lines().stream().collect(java.util.stream.Collectors.toMap(OrderSummary.LineProgress::lineId,
                        line -> line));
        return new OrderResponse(
                summary.orderId(),
                summary.orderNumber(),
                summary.customerId(),
                label == null ? null : label.name(),
                label == null ? null : label.code(),
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
                                                line.taxAmount() == null ? null : line.taxAmount().amount(),
                                                extras.design(line.sku()) == null ? null
                                                        : extras.design(line.sku()).name(),
                                                extras.design(line.sku()) == null ? null
                                                        : extras.design(line.sku()).previewUrl()))
                        .toList(),
                summary.placedAt(), summary.createdBy(), summary.lastModifiedAt(), summary.lastModifiedBy(),
                summary.contactName(), summary.contactEmail(), summary.contactPhone(),
                toResponse(summary.shippingAddress()), toResponse(summary.billingAddress()),
                summary.warehouseId(), summary.releasedAt(),
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
                lifecycle == null ? null : lifecycle.firstLabeledAt(),
                lifecycle == null ? null : lifecycle.shippedAt(),
                lifecycle == null ? null : lifecycle.deliveredAt(),
                lifecycle == null || lifecycle.deliveryFailureReason() == null ? null
                        : lifecycle.deliveryFailureReason().name(),
                lifecycle == null ? null : lifecycle.completedAt(),
                lifecycle == null ? null : lifecycle.closedAt(),
                lifecycle == null ? null : lifecycle.effectiveTotal(),
                lifecycle == null ? null : lifecycle.amountDueBeforeHandover(),
                lifecycle == null ? List.of() : lifecycle.shipments().stream()
                        .map(shipment -> new OrderResponse.Shipment(shipment.shipmentId(), shipment.status().name(),
                                shipment.amount(), shipment.handedOverAt(),
                                shipment.outcomeAt(),
                                shipment.failureReason() == null ? null : shipment.failureReason().name(),
                                shipment.lines().stream()
                                        .map(line -> new OrderResponse.ShipmentLine(line.lineId(), line.quantity()))
                                        .toList(),
                                // Order.closeLostShipment refuses a cancelled or closed order too.
                                shipment.lostClosable() && summary.status() != com.stockflow.order.api.OrderStatus.CANCELLED
                                        && summary.status() != com.stockflow.order.api.OrderStatus.CLOSED))
                        .toList(),
                lifecycle == null ? null : lifecycle.allowPartialShipment(),
                // Printing is outside the system: whatever an order holds is in stock, so the goods are ready.
                lifecycle == null ? null : Boolean.TRUE,
                summary.charges() == null ? null : summary.charges().netTotal().amount(),
                summary.charges() == null ? null : summary.charges().taxTotal().amount(),
                summary.charges() == null || summary.charges().fees() == null ? null : summary.charges().fees().stream()
                        .map(fee -> new OrderResponse.Fee(fee.kind().name(), fee.sku(), fee.amount().amount(),
                                fee.taxRate(), fee.taxAmount().amount())).toList(),
                extras.sourceQuoteId(), extras.sourceQuoteNumber());
    }

    /** An address as the order answers it; null for none. */
    static OrderResponse.Address toAddress(OrderSummary.AddressSummary address) {
        return toResponse(address);
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
