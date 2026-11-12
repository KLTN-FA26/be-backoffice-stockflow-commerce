package com.stockflow.invoicing.internal.controller;

import com.stockflow.invoicing.internal.controller.dto.EInvoiceErrorNoticeResponse;
import com.stockflow.invoicing.internal.controller.dto.CustomerEInvoiceErrorNoticeResponse;
import com.stockflow.invoicing.internal.controller.dto.CustomerEInvoiceResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceListItemResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceProviderResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceRegisterEntryResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceSettingsResponse;
import com.stockflow.invoicing.internal.controller.dto.ProviderCallResponse;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.ErrorNotice;
import com.stockflow.invoicing.internal.domain.InvoiceParty;
import com.stockflow.invoicing.internal.domain.ProviderAccounts;
import com.stockflow.invoicing.internal.domain.ProviderCallLog;
import com.stockflow.invoicing.internal.domain.TaxRateTotal;
import com.stockflow.invoicing.internal.service.EInvoiceSettingsService;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** The invoicing module's values to its response DTOs. */
final class InvoicingWebMapper {

    private InvoicingWebMapper() {
    }

    static EInvoiceResponse toResponse(EInvoice.Snapshot s, Instant now) {
        return new EInvoiceResponse(s.id(), s.code(), s.type().name(), s.status().name(), s.adjusted(), s.orderId(),
                s.orderNumber(), s.contractNumber(), s.shipmentId(), s.shipmentCode(), s.deliveryBatchId(),
                s.customerId(), s.originalEInvoiceId(), s.provider(), s.templateCode(), s.symbol(),
                s.invoiceNumber(), s.taxAuthorityCode(), s.lookupCode(), s.issueDate(), s.issueDueAt(),
                overdue(s.status(), s.issueDueAt(), now), s.signedAt(), party(s.seller()), party(s.buyer()),
                s.currency(), s.paymentMethod(),
                s.lines().stream().map(line -> new EInvoiceResponse.Line(line.id(), line.lineNo(), line.kind().name(),
                        line.feeKind() == null ? null : line.feeKind().name(), line.orderLineId(), line.sku(),
                        line.itemName(), line.unit(), line.quantity(), line.unitsPerCarton(), line.unitPrice(),
                        line.taxRate(), line.amount(), line.taxAmount())).toList(),
                TaxRateTotal.of(s.lines()).stream().map(total -> new EInvoiceResponse.TaxRateTotal(total.taxRate(),
                        total.amount(), total.taxAmount())).toList(),
                s.subtotal(), s.taxTotal(), s.grandTotal(), s.amountInWords(),
                s.warnings().stream().sorted().map(Enum::name).toList(), s.rejectionCode(), s.rejectionMessage(),
                s.submittedAt(), s.issuedAt(), s.voidedAt(), s.voidReason(), s.status().wasIssued(), s.version(),
                s.correctionBasis() == null ? null : s.correctionBasis().name(), s.adjustmentReason(),
                s.correctionBasis() != null && s.correctionBasis().requiresAgreement(),
                s.agreementFileKey() == null ? null
                        : new EInvoiceResponse.Agreement(s.agreementNumber(), s.agreementDate()),
                s.returnRequestId(), s.listedInvoiceIds());
    }

    static EInvoiceListItemResponse toResponse(EInvoiceQueries.Listing row, Instant now) {
        return new EInvoiceListItemResponse(row.id(), row.code(), row.type().name(), row.status().name(),
                row.adjusted(), row.orderId(), row.orderNumber(), row.shipmentId(), row.shipmentCode(),
                row.customerId(), row.buyerName(), row.buyerTaxCode(), row.issueDate(), row.issueDueAt(),
                overdue(row.status(), row.issueDueAt(), now), row.templateCode(), row.symbol(), row.invoiceNumber(),
                row.subtotal(), row.taxTotal(), row.grandTotal(),
                row.warnings() == null ? List.of() : Arrays.asList(row.warnings().split(",")), row.rejectionCode(),
                row.issuedAt());
    }

    /** The row as its buyer sees it: nothing of the seller's work on the invoice. */
    static CustomerEInvoiceResponse toCustomerResponse(EInvoiceQueries.Listing row) {
        return new CustomerEInvoiceResponse(row.id(), row.type().name(), row.status().name(), row.adjusted(),
                row.orderId(), row.orderNumber(), row.shipmentCode(), row.issueDate(), row.templateCode(),
                row.symbol(), row.invoiceNumber(), row.subtotal(), row.taxTotal(), row.grandTotal(), row.issuedAt());
    }

    static EInvoiceRegisterEntryResponse toResponse(EInvoiceQueries.RegisterEntry entry) {
        return new EInvoiceRegisterEntryResponse(entry.templateCode(), entry.symbol(), entry.invoiceNumber(),
                entry.issueDate(), entry.type().name(), entry.status().name(), entry.adjusted(), entry.buyerName(),
                entry.buyerTaxCode(), entry.buyerAddress(), entry.orderNumber(), entry.subtotal(), entry.taxTotal(),
                entry.grandTotal(), entry.taxAuthorityCode(), entry.lookupCode(), entry.originalReference(),
                entry.byRate().stream().map(total -> new EInvoiceResponse.TaxRateTotal(total.taxRate(),
                        total.amount(), total.taxAmount())).toList());
    }

    static ProviderCallResponse toResponse(ProviderCallLog.Call call) {
        return new ProviderCallResponse(call.provider(), call.transactionId(), call.operation().name(),
                call.outcome().name(), call.httpStatus(), call.errorCode(), call.calledAt(), call.durationMs());
    }

    static CustomerEInvoiceErrorNoticeResponse toCustomerResponse(ErrorNotice.Snapshot notice) {
        return new CustomerEInvoiceErrorNoticeResponse(notice.id(), notice.einvoiceId(), notice.formCode(),
                notice.reason(), notice.correctBuyerName(), notice.correctBuyerAddress(), notice.status().name(),
                notice.createdAt(), notice.respondedAt());
    }

    static EInvoiceErrorNoticeResponse toResponse(ErrorNotice.Snapshot notice) {
        return new EInvoiceErrorNoticeResponse(notice.id(), notice.einvoiceId(), notice.formCode(),
                notice.subject().name(), notice.reason(), notice.correctBuyerName(), notice.correctBuyerAddress(),
                notice.status().name(), notice.sentAt(), notice.responseCode(), notice.responseMessage(),
                notice.respondedAt(), notice.recordedBy(), notice.createdAt());
    }

    static EInvoiceSettingsResponse toResponse(EInvoiceSettingsService.Settings settings) {
        var provider = settings.provider();
        return new EInvoiceSettingsResponse(provider.provider(), provider.environment(), provider.endpoint(),
                provider.username(), provider.secretSet(), provider.autoIssue(),
                settings.series().stream().map(series -> new EInvoiceSettingsResponse.Series(series.templateCode(),
                        series.symbol(), series.year())).toList(), provider.version());
    }

    static EInvoiceProviderResponse toResponse(ProviderAccounts.Account account) {
        return new EInvoiceProviderResponse(account.provider(), account.environment(), account.username(),
                account.secretSealed() != null, account.active(), account.autoIssue(), account.version());
    }

    private static EInvoiceResponse.Party party(InvoiceParty party) {
        return new EInvoiceResponse.Party(party.name(), party.taxCode(), party.address(), party.email());
    }

    /** Still to issue, past the end of the working day after its date (NĐ 254 Phụ lục điểm 7). */
    private static boolean overdue(EInvoiceStatus status, Instant dueAt, Instant now) {
        return !status.wasIssued() && status != EInvoiceStatus.VOIDED_DRAFT && !now.isBefore(dueAt);
    }
}
