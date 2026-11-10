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

    // ---- 4B-6: installments and receipts ----------------------------------------------------------------------------

    static com.stockflow.payment.internal.controller.dto.InstallmentResponse toResponse(
            com.stockflow.payment.internal.domain.Installment i,
            List<com.stockflow.payment.internal.domain.InstallmentBook.ReceiptOfInstallment> receipts) {
        return new com.stockflow.payment.internal.controller.dto.InstallmentResponse(i.id(), i.contractId(),
                i.contractNumber(), i.orderId(), i.orderNumber(), i.customerId(), i.seq(), i.name(),
                i.milestone().name(), i.deliveryBatchSeq(), i.amount(), i.paidAmount(), i.reducedAmount(),
                i.outstanding(), i.currency(),
                i.dueDays(), i.reachedAt(), i.dueDate(), i.status().name(), i.paidAt(), i.cancelledAt(),
                i.transferReference(), receipts == null ? null : receipts.stream()
                        .map(r -> new com.stockflow.payment.internal.controller.dto.InstallmentResponse.Receipt(
                                r.receiptId(), r.reference(), r.receivedOn(), r.amount(), r.status().name(),
                                r.recordedAt())).toList());
    }

    static com.stockflow.payment.internal.controller.dto.OrderInstallmentsResponse toResponse(
            com.stockflow.payment.internal.service.InstallmentService.OrderInstallments o) {
        var standing = o.standing();
        var first = o.installments().getFirst();
        return new com.stockflow.payment.internal.controller.dto.OrderInstallmentsResponse(standing.orderId(),
                first.orderNumber(), first.contractNumber(),
                standing.paymentStatus() == null ? null : standing.paymentStatus().name(), standing.total(),
                standing.paid(), standing.total().subtract(standing.paid()), standing.onSigningPaid(),
                standing.beforeDeliveryUnpaid(), standing.dueBeforeHandover(), first.currency(),
                o.installments().stream().map(i -> toResponse(i, null)).toList());
    }

    static com.stockflow.payment.internal.controller.dto.InstallmentTransferResponse toResponse(
            com.stockflow.payment.internal.service.InstallmentService.TransferInstructions t) {
        var i = t.installment();
        return new com.stockflow.payment.internal.controller.dto.InstallmentTransferResponse(i.id(),
                i.contractNumber(), i.orderNumber(), i.seq(), i.name(), t.account().bankName(),
                t.account().accountNumber(), t.account().accountName(), t.amountDue(), i.currency(), t.reference(),
                i.dueDate());
    }

    static com.stockflow.payment.internal.controller.dto.PaymentReceiptResponse toResponse(
            com.stockflow.payment.internal.domain.PaymentReceipt r,
            java.util.Map<java.util.UUID, com.stockflow.payment.internal.domain.Installment> installments) {
        return new com.stockflow.payment.internal.controller.dto.PaymentReceiptResponse(r.id(), r.customerId(),
                r.reference(), r.receivedOn(), r.amount(), r.currency(), r.note(), r.status().name(), r.recordedBy(),
                r.recordedAt(), r.voidedBy(), r.voidedAt(), r.voidReason(), r.lines().stream().map(line -> {
                    var i = installments == null ? null : installments.get(line.installmentId());
                    return new com.stockflow.payment.internal.controller.dto.PaymentReceiptResponse.Line(
                            line.installmentId(), line.amount(), i == null ? null : i.contractNumber(),
                            i == null ? null : i.orderId(), i == null ? null : i.orderNumber(),
                            i == null ? null : i.seq(), i == null ? null : i.name(),
                            i == null ? null : i.milestone().name());
                }).toList());
    }

    // ---- 4B-9: refunds ------------------------------------------------------------------------------------------------

    /**
     * @param forCustomer the customer's own view: live records only (a voided one is the accountant's correction), and
     *                    no fee reason or detail - the agreement behind them is Sales'
     */
    static com.stockflow.payment.internal.controller.dto.OrderRefundResponse toResponse(
            com.stockflow.payment.internal.service.RefundService.OrderRefundDetail d, boolean forCustomer) {
        var standing = d.standing();
        return new com.stockflow.payment.internal.controller.dto.OrderRefundResponse(standing.orderId(),
                d.orderNumber(), d.currency(), standing.status().name(), standing.refundable(), standing.refunded(),
                standing.remaining(), d.settlements().stream().map(s -> {
                    var terms = s.feeTerms();
                    return new com.stockflow.payment.internal.controller.dto.OrderRefundResponse.Settlement(s.id(),
                            s.kind().name(), s.collected(), s.closed(), s.reduced(), s.surplus(), s.fee(),
                            terms.percent(), forCustomer ? null : terms.reason(), forCustomer ? null : terms.detail(),
                            s.feeCovered(), s.refundable(), s.feeOwed(), s.feeItemId(), s.settledAt());
                }).toList(), d.records().stream()
                .filter(r -> !forCustomer
                        || r.status() == com.stockflow.payment.internal.domain.RefundRecord.Status.RECORDED)
                .map(r -> forCustomer ? forCustomer(r) : toResponse(r)).toList());
    }

    /** What the customer sees of a refund made: the money and the statement line, not the accountant's note or name. */
    private static com.stockflow.payment.internal.controller.dto.RefundRecordResponse forCustomer(
            com.stockflow.payment.internal.domain.RefundRecord r) {
        return new com.stockflow.payment.internal.controller.dto.RefundRecordResponse(r.id(), r.orderId(),
                r.orderNumber(), r.customerId(), r.amount(), r.currency(), r.refundedOn(), r.reference(), null,
                r.status().name(), null, r.recordedAt(), null, null, null);
    }

    /** A row of the accountant's work list: amounts only. */
    static com.stockflow.payment.internal.controller.dto.OrderRefundResponse toResponse(
            com.stockflow.payment.internal.domain.OrderRefund o) {
        var standing = com.stockflow.payment.internal.domain.RefundStanding.of(o.orderId(), o.refundable(),
                o.refunded());
        return new com.stockflow.payment.internal.controller.dto.OrderRefundResponse(o.orderId(), o.orderNumber(),
                o.currency(), standing.status().name(), standing.refundable(), standing.refunded(),
                standing.remaining(), null, null);
    }

    static com.stockflow.payment.internal.controller.dto.RefundRecordResponse toResponse(
            com.stockflow.payment.internal.domain.RefundRecord r) {
        return new com.stockflow.payment.internal.controller.dto.RefundRecordResponse(r.id(), r.orderId(),
                r.orderNumber(), r.customerId(), r.amount(), r.currency(), r.refundedOn(), r.reference(), r.note(),
                r.status().name(), r.recordedBy(), r.recordedAt(), r.voidedBy(), r.voidedAt(), r.voidReason());
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
                p.outstandingReceivables(), p.mayStillTake(),
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
