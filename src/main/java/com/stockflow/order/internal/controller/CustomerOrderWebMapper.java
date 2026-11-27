package com.stockflow.order.internal.controller;

import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.controller.dto.CustomerOrderCardResponse;
import com.stockflow.order.internal.controller.dto.CustomerOrderDetailResponse;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.service.CustomerOrders;
import com.stockflow.order.internal.service.OrderSources;

/** Maps what {@link CustomerOrders} returns to the customer-facing DTOs. */
final class CustomerOrderWebMapper {

    private CustomerOrderWebMapper() {}

    static CustomerOrderCardResponse toResponse(CustomerOrders.Card card) {
        return new CustomerOrderCardResponse(card.orderId(), card.orderNumber(), card.status().name(),
                card.statusGroup().name(), card.total(), card.currency(), card.placedAt(), card.lineCount(),
                card.deliveryState().name(), card.cancellationRequestStatus());
    }

    static CustomerOrderDetailResponse toResponse(CustomerOrders.Detail detail) {
        return toResponse(detail, OrderSources.Extras.NONE);
    }

    /** With where the order came from: its quote and the design of each line's SKU (R5-3, R5-7). */
    static CustomerOrderDetailResponse toResponse(CustomerOrders.Detail detail, OrderSources.Extras extras) {
        // A shipment line names its order line, which carries the SKU's names: no second lookup.
        java.util.Map<java.util.UUID, CustomerOrders.Line> byLine = new java.util.HashMap<>();
        detail.lines().forEach(line -> byLine.put(line.lineId(), line));
        return new CustomerOrderDetailResponse(detail.orderId(), detail.orderNumber(), detail.status().name(),
                detail.statusGroup().name(), detail.holdReasonPublic(),
                detail.total(), detail.currency(), detail.placedAt(), toResponse(detail.shippingAddress()),
                detail.lines().stream().map(line -> toResponse(line, extras.design(line.sku()))).toList(),
                detail.timeline().stream().map(CustomerOrderWebMapper::toResponse).toList(),
                detail.deliveryState().name(), detail.allowedActions() == null ? null
                        : new CustomerOrderDetailResponse.AllowedActions(detail.allowedActions().cancel(),
                                detail.allowedActions().requestCancellation()),
                detail.cancellationRequest() == null ? null : new CustomerOrderDetailResponse.CancellationRequest(
                        detail.cancellationRequest().status(),
                        detail.cancellationRequest().reasonCode() == null ? null
                                : detail.cancellationRequest().reasonCode().name(),
                        detail.cancellationRequest().note(), detail.cancellationRequest().requestedAt(),
                        detail.cancellationRequest().decidedAt(), detail.cancellationRequest().decisionNote()),
                detail.shipments().stream().map(s -> new CustomerOrderDetailResponse.Shipment(s.shipmentId(), s.shipmentCode(),
                        s.status().name(), s.packageCount(), s.carrierName(), s.handedOverAt(),
                        s.outcomeAt(), s.failureReason() == null ? null : s.failureReason().name(),
                        s.lines().stream().map(line -> {
                            CustomerOrders.Line ordered = line.orderLineId() == null ? null
                                    : byLine.get(line.orderLineId());
                            return new CustomerOrderDetailResponse.ShipmentLine(line.orderLineId(), line.sku(),
                                    line.quantity(), line.returnableQuantity(),
                                    ordered == null ? null : ordered.productName(),
                                    ordered == null ? null : ordered.variantName());
                        }).toList())).toList(),
                detail.charges().netTotal(), detail.charges().taxTotal(),
                detail.charges().fees().stream().map(fee -> new CustomerOrderDetailResponse.Fee(fee.kind(), fee.sku(),
                        fee.amount(), fee.taxRate(), fee.taxAmount())).toList(),
                extras.sourceQuoteId(), extras.sourceQuoteNumber());
    }

    private static CustomerOrderDetailResponse.Line toResponse(CustomerOrders.Line line,
                                                               com.stockflow.design.api.SkuDesign design) {
        return new CustomerOrderDetailResponse.Line(line.lineId(), line.sku(), line.quantity(), line.unitPrice(),
                line.lineTotal(), line.designSnapshotId(),
                new CustomerOrderDetailResponse.Production(line.production().state().name(),
                        line.production().goodQuantity()),
                line.cartons(), line.unitsPerCarton(),
                line.taxRate(), line.taxAmount(), design == null ? null : design.name(),
                design == null ? null : design.previewUrl(), line.productName(), line.variantName());
    }

    private static CustomerOrderDetailResponse.TimelineEntry toResponse(CustomerOrders.TimelineEntry entry) {
        return new CustomerOrderDetailResponse.TimelineEntry(entry.status().name(), entry.statusGroup().name(),
                entry.occurredAt(), entry.reasonCode() == null ? null : entry.reasonCode().name());
    }

    private static OrderResponse.Address toResponse(OrderSummary.AddressSummary address) {
        if (address == null) {
            return null;
        }
        return new OrderResponse.Address(address.recipientName(), address.phone(), address.line1(), address.line2(),
                address.wardCode(), address.wardName(), address.provinceCode(), address.provinceName(),
                address.countryCode(), address.postalCode());
    }
}
