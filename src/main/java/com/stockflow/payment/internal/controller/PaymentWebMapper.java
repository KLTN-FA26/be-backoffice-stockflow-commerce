package com.stockflow.payment.internal.controller;

import java.util.List;

/**
 * The payment module's wire shapes, built from its domain types. Hand-written: the records are flat and some fields
 * are derived ({@code outstanding}, a refund's standing).
 */
final class PaymentWebMapper {

    private PaymentWebMapper() {
    }

    // ---- 4B-6: installments and receipts ----------------------------------------------------------------------------

    static com.stockflow.payment.internal.controller.dto.InstallmentResponse toResponse(
            com.stockflow.payment.internal.domain.Installment i,
            List<com.stockflow.payment.internal.domain.InstallmentBook.ReceiptOfInstallment> receipts,
            java.time.LocalDate today, java.util.Map<java.util.UUID, com.stockflow.customer.api.CustomerLabel> labels) {
        var label = labelOf(labels, i.customerId());
        return new com.stockflow.payment.internal.controller.dto.InstallmentResponse(i.id(), i.contractId(),
                i.contractNumber(), i.orderId(), i.orderNumber(), i.customerId(),
                label == null ? null : label.name(), label == null ? null : label.code(), i.seq(), i.name(),
                i.milestone().name(), i.deliveryBatchSeq(), i.amount(), i.paidAmount(), i.reducedAmount(),
                i.outstanding(), i.currency(),
                i.dueDays(), i.reachedAt(), i.dueDate(), i.status().name(), i.overdue(today), i.paidAt(),
                i.cancelledAt(),
                i.transferReference(), receipts == null ? null : receipts.stream()
                        .map(r -> new com.stockflow.payment.internal.controller.dto.InstallmentResponse.Receipt(
                                r.receiptId(), r.reference(), r.receivedOn(), r.amount(), r.status().name(),
                                r.recordedAt())).toList(), i.daysOverdue(today));
    }

    static com.stockflow.payment.internal.controller.dto.OrderInstallmentsResponse toResponse(
            com.stockflow.payment.internal.service.InstallmentService.OrderInstallments o,
            java.time.LocalDate today, java.util.Map<java.util.UUID, com.stockflow.customer.api.CustomerLabel> labels) {
        var standing = o.standing();
        var first = o.installments().getFirst();
        return new com.stockflow.payment.internal.controller.dto.OrderInstallmentsResponse(standing.orderId(),
                first.orderNumber(), first.contractNumber(),
                standing.paymentStatus() == null ? null : standing.paymentStatus().name(), standing.total(),
                standing.paid(), standing.outstanding(), standing.onSigningPaid(),
                standing.beforeDeliveryUnpaid(), standing.dueBeforeHandover(), first.currency(),
                o.installments().stream().map(i -> toResponse(i, null, today, labels)).toList());
    }

    static com.stockflow.payment.internal.controller.dto.InstallmentTransferResponse toResponse(
            com.stockflow.payment.internal.service.InstallmentService.TransferInstructions t) {
        var i = t.installment();
        return new com.stockflow.payment.internal.controller.dto.InstallmentTransferResponse(i.id(),
                i.contractNumber(), i.orderNumber(), i.seq(), i.name(), t.account().bankName(),
                t.account().accountNumber(), t.account().accountName(), t.amountDue(), i.currency(), t.reference(),
                i.dueDate(), com.stockflow.payment.api.VietQr.forInstallment(t.account(), t.amountDue(), i.currency(),
                        i.status().name(), t.reference()));
    }

    /**
     * @param names         R5-8: display names of the staff who recorded and voided receipts, by user id
     * @param voidable      whether the receipt may be voided now; null where it was not worked out (the list)
     * @param voidBlockedBy why not; null when voidable or not worked out
     */
    static com.stockflow.payment.internal.controller.dto.PaymentReceiptResponse toResponse(
            com.stockflow.payment.internal.domain.PaymentReceipt r,
            java.util.Map<java.util.UUID, com.stockflow.payment.internal.domain.Installment> installments,
            java.util.Map<java.util.UUID, com.stockflow.customer.api.CustomerLabel> labels,
            java.util.Map<java.util.UUID, String> names, Boolean voidable,
            com.stockflow.common.error.ErrorCode voidBlockedBy) {
        return toResponse(r, installments, labels, names, voidable, voidBlockedBy, java.util.List.of());
    }

    static com.stockflow.payment.internal.controller.dto.PaymentReceiptResponse toResponse(
            com.stockflow.payment.internal.domain.PaymentReceipt r,
            java.util.Map<java.util.UUID, com.stockflow.payment.internal.domain.Installment> installments,
            java.util.Map<java.util.UUID, com.stockflow.customer.api.CustomerLabel> labels,
            java.util.Map<java.util.UUID, String> names, Boolean voidable,
            com.stockflow.common.error.ErrorCode voidBlockedBy, java.util.List<String> loadingLoadCodes) {
        var label = labelOf(labels, r.customerId());
        return new com.stockflow.payment.internal.controller.dto.PaymentReceiptResponse(r.id(), r.customerId(),
                label == null ? null : label.name(), label == null ? null : label.code(),
                r.reference(), r.receivedOn(), r.amount(), r.currency(), r.note(), r.status().name(), r.recordedBy(),
                r.recordedAt(), r.voidedBy(), r.voidedAt(), r.voidReason(), r.lines().stream().map(line -> {
                    var i = installments == null ? null : installments.get(line.installmentId());
                    return new com.stockflow.payment.internal.controller.dto.PaymentReceiptResponse.Line(
                            line.installmentId(), line.amount(), i == null ? null : i.contractNumber(),
                            i == null ? null : i.orderId(), i == null ? null : i.orderNumber(),
                            i == null ? null : i.seq(), i == null ? null : i.name(),
                            i == null ? null : i.milestone().name());
                }).toList(), r.recordedBy() == null ? null : names.get(r.recordedBy()),
                r.voidedBy() == null ? null : names.get(r.voidedBy()), voidable,
                voidBlockedBy == null ? null : voidBlockedBy.name(), loadingLoadCodes);
    }

    /** The label of a customer from a batch read; null when there is no batch (a customer's own view) or no such customer. */
    private static com.stockflow.customer.api.CustomerLabel labelOf(
            java.util.Map<java.util.UUID, com.stockflow.customer.api.CustomerLabel> labels, java.util.UUID customerId) {
        return labels == null || customerId == null ? null : labels.get(customerId);
    }

    // ---- 4B-9: refunds ------------------------------------------------------------------------------------------------

    /**
     * @param forCustomer the customer's own view: live records only (a voided one is the accountant's correction), and
     *                    no fee reason or detail - the agreement behind them is Sales'
     */
    static com.stockflow.payment.internal.controller.dto.OrderRefundResponse toResponse(
            com.stockflow.payment.internal.service.RefundService.OrderRefundDetail d, boolean forCustomer) {
        return toResponse(d, forCustomer, java.util.Map.of(), null);
    }

    /**
     * @param names  who recorded and voided the records, by user id (staff view only)
     * @param labels the customer's label, by customer id (staff view only)
     */
    static com.stockflow.payment.internal.controller.dto.OrderRefundResponse toResponse(
            com.stockflow.payment.internal.service.RefundService.OrderRefundDetail d, boolean forCustomer,
            java.util.Map<java.util.UUID, String> names,
            java.util.Map<java.util.UUID, com.stockflow.customer.api.CustomerLabel> labels) {
        var standing = d.standing();
        return new com.stockflow.payment.internal.controller.dto.OrderRefundResponse(standing.orderId(),
                d.orderNumber(), d.currency(), standing.status().name(), standing.refundable(), standing.refunded(),
                standing.remaining(), d.settlements().stream().map(s -> {
                    var terms = s.feeTerms();
                    return new com.stockflow.payment.internal.controller.dto.OrderRefundResponse.Settlement(s.id(),
                            s.kind().name(), s.collected(), s.closed(), s.reduced(), s.surplus(), s.fee(),
                            terms.percent(), forCustomer ? null : terms.reason(), forCustomer ? null : terms.detail(),
                            s.feeCovered(), s.refundable(), s.feeOwed(), s.feeItemId(), s.settledAt(), s.shipmentId(),
                            s.shippingFeeWaived(), s.carriedToFee());
                }).toList(), d.records().stream()
                .filter(r -> !forCustomer
                        || r.status() == com.stockflow.payment.internal.domain.RefundRecord.Status.RECORDED)
                .map(r -> forCustomer ? forCustomer(r) : toResponse(r, names, labels)).toList(), d.feeOwed());
    }

    /** What the customer sees of a refund made: the money and the statement line, not the accountant's note or name. */
    private static com.stockflow.payment.internal.controller.dto.RefundRecordResponse forCustomer(
            com.stockflow.payment.internal.domain.RefundRecord r) {
        return new com.stockflow.payment.internal.controller.dto.RefundRecordResponse(r.id(), r.orderId(),
                r.orderNumber(), r.customerId(), r.amount(), r.currency(), r.refundedOn(), r.reference(), null,
                r.status().name(), null, r.recordedAt(), null, null, null, null, null, null, null);
    }

    /** A row of the accountant's work list: amounts only, {@code remaining} what is still owed (TT-4). */
    static com.stockflow.payment.internal.controller.dto.OrderRefundResponse toResponse(
            com.stockflow.payment.internal.domain.OrderRefund o) {
        var standing = com.stockflow.payment.internal.domain.RefundStanding.of(o.orderId(), o.refundable(),
                o.refunded());
        return new com.stockflow.payment.internal.controller.dto.OrderRefundResponse(o.orderId(), o.orderNumber(),
                o.currency(), standing.status().name(), o.refundable(), o.refunded(), o.remaining(), null, null,
                o.feeOwed());
    }

    /**
     * A refund record as the accountant reads it.
     *
     * @param names  who recorded and voided it, by user id, read once for the page
     * @param labels its customer's name and code, by customer id, read once for the page; null for none
     */
    static com.stockflow.payment.internal.controller.dto.RefundRecordResponse toResponse(
            com.stockflow.payment.internal.domain.RefundRecord r, java.util.Map<java.util.UUID, String> names,
            java.util.Map<java.util.UUID, com.stockflow.customer.api.CustomerLabel> labels) {
        var label = labelOf(labels, r.customerId());
        return new com.stockflow.payment.internal.controller.dto.RefundRecordResponse(r.id(), r.orderId(),
                r.orderNumber(), r.customerId(), r.amount(), r.currency(), r.refundedOn(), r.reference(), r.note(),
                r.status().name(), r.recordedBy(), r.recordedAt(), r.voidedBy(), r.voidedAt(), r.voidReason(),
                r.recordedBy() == null ? null : names.get(r.recordedBy()),
                r.voidedBy() == null ? null : names.get(r.voidedBy()), label == null ? null : label.name(),
                label == null ? null : label.code());
    }

    /** Who recorded and voided {@code records}, by user id, in one read. */
    static java.util.Map<java.util.UUID, String> namesOf(com.stockflow.identity.api.IdentityService identities,
            java.util.Collection<com.stockflow.payment.internal.domain.RefundRecord> records) {
        var ids = records.stream().flatMap(r -> java.util.stream.Stream.of(r.recordedBy(), r.voidedBy()))
                .filter(java.util.Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? java.util.Map.of() : identities.displayNames(ids);
    }

    /** The customers of {@code records}, by id, in one read. */
    static java.util.Map<java.util.UUID, com.stockflow.customer.api.CustomerLabel> labelsOf(
            com.stockflow.customer.api.CustomerService customers,
            java.util.Collection<com.stockflow.payment.internal.domain.RefundRecord> records) {
        var ids = records.stream().map(com.stockflow.payment.internal.domain.RefundRecord::customerId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? java.util.Map.of() : customers.labels(ids);
    }
}
