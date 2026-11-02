package com.stockflow.order.internal.controller;

import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.controller.dto.CustomerOrderCardResponse;
import com.stockflow.order.internal.controller.dto.CustomerOrderDetailResponse;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.controller.dto.TransferInstructionsResponse;
import com.stockflow.order.internal.service.CustomerOrders;
import com.stockflow.order.internal.service.TransferInstructions;

/** Maps what {@link CustomerOrders} returns to the customer-facing DTOs. */
final class CustomerOrderWebMapper {

    private CustomerOrderWebMapper() {}

    static CustomerOrderCardResponse toResponse(CustomerOrders.Card card) {
        return new CustomerOrderCardResponse(card.orderId(), card.orderNumber(), card.status().name(),
                card.statusGroup().name(), card.total(), card.currency(), card.placedAt(), card.lineCount(),
                card.deliveryState().name(), card.cancellationRequestStatus());
    }

    static TransferInstructionsResponse toResponse(TransferInstructions.Instructions instructions) {
        return new TransferInstructionsResponse(instructions.orderId(), instructions.orderNumber(),
                instructions.bankName(), instructions.accountNumber(), instructions.accountName(),
                instructions.reference(), instructions.amountDue(), instructions.currency(),
                instructions.dueBy().name(), instructions.dueByNote());
    }

    static CustomerOrderDetailResponse toResponse(CustomerOrders.Detail detail) {
        return new CustomerOrderDetailResponse(detail.orderId(), detail.orderNumber(), detail.status().name(),
                detail.statusGroup().name(), detail.holdReasonPublic(),
                detail.paymentTerm() == null ? null : detail.paymentTerm().name(), detail.total(), detail.currency(),
                detail.placedAt(), toResponse(detail.shippingAddress()), toResponse(detail.payment()),
                detail.lines().stream().map(CustomerOrderWebMapper::toResponse).toList(),
                detail.timeline().stream().map(CustomerOrderWebMapper::toResponse).toList(),
                detail.deliveryState().name(), detail.allowedActions() == null ? null
                        : new CustomerOrderDetailResponse.AllowedActions(detail.allowedActions().cancel(),
                                detail.allowedActions().requestCancellation(), detail.allowedActions().reorder()),
                detail.cancellationRequest() == null ? null : new CustomerOrderDetailResponse.CancellationRequest(
                        detail.cancellationRequest().status(),
                        detail.cancellationRequest().reasonCode() == null ? null
                                : detail.cancellationRequest().reasonCode().name(),
                        detail.cancellationRequest().note(), detail.cancellationRequest().requestedAt(),
                        detail.cancellationRequest().decidedAt(), detail.cancellationRequest().decisionNote()),
                detail.shipments().stream().map(s -> new CustomerOrderDetailResponse.Shipment(s.shipmentCode(),
                        s.status().name(), s.packageCount(), s.carrierName(), s.deliveryMode(), s.handedOverAt(),
                        s.outcomeAt(), s.failureReason() == null ? null : s.failureReason().name())).toList(),
                detail.deliveryMode());
    }

    private static CustomerOrderDetailResponse.Payment toResponse(CustomerOrders.Payment payment) {
        if (payment == null) {
            return null;
        }
        return new CustomerOrderDetailResponse.Payment(payment.status() == null ? null : payment.status().name(),
                payment.paidAmount(), payment.depositRequired(), payment.creditTermDays());
    }

    private static CustomerOrderDetailResponse.Line toResponse(CustomerOrders.Line line) {
        return new CustomerOrderDetailResponse.Line(line.lineId(), line.sku(), line.quantity(), line.unitPrice(),
                line.lineTotal(), line.designSnapshotId(),
                new CustomerOrderDetailResponse.Production(line.production().state().name(),
                        line.production().goodQuantity()),
                line.productSlug(), line.productTitle(), line.productImageUrl());
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
