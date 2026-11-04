package com.stockflow.payment.internal.controller;

import com.stockflow.payment.internal.controller.dto.AllocationResponse;
import com.stockflow.payment.internal.controller.dto.CustomerTransferResponse;
import com.stockflow.payment.internal.controller.dto.OrderPaymentsResponse;
import com.stockflow.payment.internal.controller.dto.ReceivableResponse;
import com.stockflow.payment.internal.domain.CustomerTransfer;
import com.stockflow.payment.internal.domain.Receivable;
import com.stockflow.payment.internal.domain.ReceivableBook;
import com.stockflow.payment.internal.service.MoneyLedgerService;

import java.util.List;

/**
 * The payment module's wire shapes, built from its domain types (G2-3 gathers the mappings the controllers had each
 * kept for themselves). Hand-written: the records are flat and some fields are derived ({@code target},
 * {@code outstanding}).
 */
final class PaymentWebMapper {

    private PaymentWebMapper() {
    }

    static CustomerTransferResponse toResponse(CustomerTransfer t, List<ReceivableBook.Allocation> allocations) {
        return new CustomerTransferResponse(t.id(), t.customerId(), t.reference(), t.amount(), t.unallocatedAmount(),
                t.refundedAmount(), t.currency(), t.receivedOn(), t.recordedBy(), t.recordedAt(), t.note(),
                t.channel().name(), t.carrierName(),
                allocations == null ? null : allocations.stream().map(PaymentWebMapper::toResponse).toList());
    }

    static ReceivableResponse toResponse(Receivable r, List<ReceivableBook.Allocation> allocations) {
        return new ReceivableResponse(r.id(), r.orderId(), r.orderNumber(), r.customerId(), r.kind(), r.collector().name(),
                r.shipmentId(), r.amount(), r.paidAmount(), r.outstanding(), r.currency(), r.issuedAt(), r.dueDate(),
                r.status().name(), r.settledAt(),
                allocations == null ? null : allocations.stream().map(PaymentWebMapper::toResponse).toList());
    }

    static AllocationResponse toResponse(ReceivableBook.Allocation a) {
        return new AllocationResponse(a.id(), a.transferId(), a.orderId(), target(a), a.receivableId(), a.amount(),
                a.allocatedAt(), a.allocatedBy(), a.reversesId(), a.note());
    }

    static OrderPaymentsResponse toResponse(MoneyLedgerService.OrderPayments p) {
        var o = p.order();
        return new OrderPaymentsResponse(o.orderId(), o.orderNumber(), o.customerId(), o.status(), o.paymentTerm(),
                o.balancePolicy(), o.currency(), o.billable(), o.depositRequired(), p.collected(),
                p.outstandingReceivables(), o.codInTransit(), p.mayStillTake(),
                p.allocations().stream().map(a -> {
                    CustomerTransfer t = p.transfers().get(a.transferId());
                    return new OrderPaymentsResponse.Movement(a.id(), a.transferId(), t.reference(),
                            t.channel().name(), t.receivedOn(), target(a), a.receivableId(), a.amount(),
                            a.allocatedAt(), a.allocatedBy(), a.reversesId(), a.note());
                }).toList(),
                p.receivables().stream().map(r -> toResponse(r, null)).toList(),
                p.refunds().stream().map(r -> new OrderPaymentsResponse.Refund(r.id(), r.paymentId(), r.allocationId(),
                        r.amount(), r.currency(), r.status(), r.source(), r.createdAt())).toList());
    }

    private static String target(ReceivableBook.Allocation a) {
        return a.paysOrder() ? "ORDER" : "RECEIVABLE";
    }
}
