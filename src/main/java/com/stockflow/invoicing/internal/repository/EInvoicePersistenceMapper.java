package com.stockflow.invoicing.internal.repository;

import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.InvoiceParty;
import com.stockflow.invoicing.internal.entity.EInvoiceJpaEntity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** {@code invoicing.einvoice} rows to and from the aggregate's snapshot. Hand-written: the only class that knows both. */
final class EInvoicePersistenceMapper {

    private EInvoicePersistenceMapper() {
    }

    static EInvoiceJpaEntity newEntity(EInvoice.Snapshot s) {
        return new EInvoiceJpaEntity(s.id(), s.code(), s.type(), s.orderId(), s.orderNumber(), s.contractNumber(),
                s.shipmentId(), s.shipmentCode(), s.deliveryBatchId(), s.customerId(), s.originalEInvoiceId(),
                s.issueDate(), s.issueDueAt(), s.seller().name(), s.seller().taxCode(), s.seller().address(),
                s.currency(), s.paymentMethod(), s.subtotal(), s.taxTotal(), s.grandTotal(), s.amountInWords(),
                s.adjustmentReason(), s.correctionBasis(), s.returnRequestId());
    }

    /** Everything that follows the aggregate after it was drawn up. */
    static void apply(EInvoice.Snapshot s, EInvoiceJpaEntity entity) {
        entity.applyBuyer(s.buyer().name(), s.buyer().taxCode(), s.buyer().address(), s.buyer().email(),
                warnings(s.warnings()));
        entity.applyAgreement(s.agreementFileKey(), s.agreementNumber(), s.agreementDate());
        entity.applyState(s.status(), s.adjusted(), s.dueWarnedAt());
        entity.applySubmission(s.provider(), s.attempt(), s.transactionId(), s.templateCode(), s.symbol(),
                s.submittedAt(), s.submittedBy(), s.rejectionCode(), s.rejectionMessage());
        entity.applyIssue(s.invoiceNumber(), s.taxAuthorityCode(), s.lookupCode(), s.signedAt(), s.xmlFileKey(),
                s.pdfFileKey(), s.issuedAt());
        entity.applyVoid(s.voidedAt(), s.voidedBy(), s.voidReason());
    }

    static EInvoice toDomain(EInvoiceJpaEntity e, List<EInvoiceLine> lines, List<java.util.UUID> listed) {
        return EInvoice.restore(new EInvoice.Snapshot(e.getId(), e.getCode(), e.getType(), e.getStatus(),
                e.isAdjusted(), e.getOrderId(), e.getOrderNumber(), e.getContractNumber(), e.getShipmentId(),
                e.getShipmentCode(), e.getDeliveryBatchId(), e.getCustomerId(), e.getOriginalEInvoiceId(),
                e.getProvider(), e.getAttempt(), e.getTransactionId(), e.getTemplateCode(), e.getSymbol(),
                e.getInvoiceNumber(), e.getTaxAuthorityCode(), e.getLookupCode(), e.getIssueDate(), e.getIssueDueAt(),
                e.getDueWarnedAt(), e.getSignedAt(),
                new InvoiceParty(e.getSellerName(), e.getSellerTaxCode(), e.getSellerAddress(), null),
                new InvoiceParty(e.getBuyerName(), e.getBuyerTaxCode(), e.getBuyerAddress(), e.getBuyerEmail()),
                e.getCurrency(), e.getPaymentMethod(), lines, e.getSubtotal(), e.getTaxTotal(), e.getGrandTotal(),
                e.getAmountInWords(), warnings(e.getWarnings()), e.getAdjustmentReason(), e.getAgreementFileKey(),
                e.getXmlFileKey(), e.getPdfFileKey(), e.getRejectionCode(), e.getRejectionMessage(),
                e.getSubmittedAt(), e.getSubmittedBy(), e.getIssuedAt(), e.getVoidedAt(), e.getVoidedBy(),
                e.getVoidReason(), e.getVersion(), e.getCorrectionBasis(), e.getAgreementNumber(),
                e.getAgreementDate(), e.getReturnRequestId(), listed));
    }

    /** Codes in the enum's order, comma-separated; null for none, so the column says "nothing to look at" plainly. */
    static String warnings(Set<EInvoice.Warning> warnings) {
        return warnings == null || warnings.isEmpty() ? null
                : warnings.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    static Set<EInvoice.Warning> warnings(String stored) {
        if (stored == null || stored.isBlank()) {
            return EnumSet.noneOf(EInvoice.Warning.class);
        }
        return Arrays.stream(stored.split(",")).map(String::strip).map(EInvoice.Warning::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(EInvoice.Warning.class)));
    }
}
