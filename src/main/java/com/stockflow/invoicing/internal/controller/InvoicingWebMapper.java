package com.stockflow.invoicing.internal.controller;

import com.stockflow.invoicing.internal.controller.dto.EInvoiceErrorNoticeResponse;
import com.stockflow.invoicing.internal.controller.dto.CustomerEInvoiceErrorNoticeResponse;
import com.stockflow.invoicing.internal.controller.dto.CustomerEInvoiceResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceListItemResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceProviderResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceRegisterEntryResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceSettingsResponse;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceSignatureResponse;
import com.stockflow.invoicing.internal.controller.dto.ProviderCallResponse;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.ErrorNotice;
import com.stockflow.invoicing.internal.domain.InvoiceParty;
import com.stockflow.invoicing.internal.domain.ProviderAccounts;
import com.stockflow.invoicing.internal.domain.ProviderCallLog;
import com.stockflow.invoicing.internal.domain.TaxRateTotal;
import com.stockflow.invoicing.internal.service.EInvoiceQueryService;
import com.stockflow.invoicing.internal.service.EInvoiceSettingsService;
import com.stockflow.invoicing.internal.service.InvoiceXmlSignature;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** The invoicing module's values to its response DTOs. */
final class InvoicingWebMapper {

    private InvoicingWebMapper() {
    }

    /** The warning shown beside the stored ones while an earlier-dated invoice of the series waits (HĐ-2). */
    static final String EARLIER_INVOICE_PENDING = "EARLIER_INVOICE_PENDING";

    /**
     * @param earlierPending       the code of an earlier-dated invoice still to be issued under the series this draft
     *                             would take - a warning, not a refusal; null when there is none or it was not asked
     * @param errorNoticeSupported whether its provider sends a Mẫu 04/SS-HĐĐT notice (F-11)
     * @param deliveryFailureReductionAllowed whether the accountant's reduction for a failed delivery would be drawn now
     *                             (BE #423, {@code EInvoiceDrafts#deliveryFailureReducible})
     */
    static EInvoiceResponse toResponse(EInvoice.Snapshot s, Instant now, String earlierPending,
                                       boolean errorNoticeSupported,
                                       com.stockflow.invoicing.internal.service.WithdrawalRule.Decision withdrawal,
                                       com.stockflow.invoicing.internal.service.EInvoiceIssuance
                                               .ExternalReductionOptions external,
                                       com.stockflow.invoicing.internal.domain.EInvoiceRepository
                                               .TransactionWatch watch,
                                       boolean deliveryFailureReductionAllowed) {
        List<String> bases = new java.util.ArrayList<>();
        if (external.deliveryFailed()) {
            bases.add("DELIVERY_FAILED");
        }
        if (!external.returns().isEmpty()) {
            bases.add("RETURN");
        }
        if (!external.errors().isEmpty()) {
            bases.add("ERROR");
        }
        List<String> warnings = new java.util.ArrayList<>(s.warnings().stream().sorted().map(Enum::name).toList());
        if (earlierPending != null) {
            warnings.add(EARLIER_INVOICE_PENDING);
        }
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
                List.copyOf(warnings), s.rejectionCode(), s.rejectionMessage(),
                s.submittedAt(), s.issuedAt(), s.voidedAt(), s.voidReason(), s.status().wasIssued(), s.version(),
                s.correctionBasis() == null ? null : s.correctionBasis().name(), s.adjustmentReason(),
                s.correctionBasis() != null && s.correctionBasis().requiresAgreement(),
                s.agreementFileKey() == null ? null
                        : new EInvoiceResponse.Agreement(s.agreementNumber(), s.agreementDate()),
                s.returnRequestId(), s.listedInvoiceIds(), earlierPending, s.deliveryFailedAt(), s.reversedAt(),
                errorNoticeSupported, s.issuedVia() == null ? null : s.issuedVia().name(), s.deliveredOn(),
                s.voidReasonCode() == null ? null : s.voidReasonCode().name(), withdrawal.allowed(),
                withdrawal.blocked() == null ? null : withdrawal.blocked().name(), withdrawal.from(),
                withdrawal.evidenceRequired(), external.any(), List.copyOf(bases),
                external.returns().stream().map(option -> new EInvoiceResponse.ExternalReductionReturn(
                        option.returnRequestId(), option.rmaNumber())).toList(),
                watch == null ? null : watch.watchUntil(), watch == null ? null : watch.foundAt(),
                external.errors().stream().map(option -> new EInvoiceResponse.ExternalReductionError(
                        option.voidedAdjustmentId(), option.code(), option.reason(), option.grandTotal())).toList(),
                deliveryFailureReductionAllowed);
    }

    static EInvoiceListItemResponse toResponse(EInvoiceQueries.Listing row, Instant now) {
        return new EInvoiceListItemResponse(row.id(), row.code(), row.type().name(), row.status().name(),
                row.adjusted(), row.orderId(), row.orderNumber(), row.shipmentId(), row.shipmentCode(),
                row.customerId(), row.buyerName(), row.buyerTaxCode(), row.issueDate(), row.issueDueAt(),
                overdue(row.status(), row.issueDueAt(), now), row.templateCode(), row.symbol(), row.invoiceNumber(),
                row.subtotal(), row.taxTotal(), row.grandTotal(),
                row.warnings() == null ? List.of() : Arrays.asList(row.warnings().split(",")), row.rejectionCode(),
                row.issuedAt(), row.correctionBasis(), row.reversedAt(), row.deliveredOn());
    }

    /** The row as its buyer sees it: nothing of the seller's work on the invoice. */
    static CustomerEInvoiceResponse toCustomerResponse(EInvoiceQueryService.IssuedInvoice issued) {
        EInvoiceQueries.Listing row = issued.invoice();
        EInvoiceQueries.Replacements links = issued.replacements() == null ? EInvoiceQueries.Replacements.NONE
                : issued.replacements();
        return new CustomerEInvoiceResponse(row.id(), row.type().name(), row.status().name(), row.adjusted(),
                row.orderId(), row.orderNumber(), row.shipmentCode(), row.issueDate(), row.templateCode(),
                row.symbol(), row.invoiceNumber(), row.subtotal(), row.taxTotal(), row.grandTotal(), row.issuedAt(),
                reference(links.replaces()), reference(links.replacedBy()));
    }

    private static CustomerEInvoiceResponse.Reference reference(EInvoiceQueries.Reference reference) {
        return reference == null ? null : new CustomerEInvoiceResponse.Reference(reference.id(), reference.code());
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
                call.outcome().name(), call.httpStatus(), call.errorCode(), call.calledAt(), call.durationMs(),
                call.providerReference());
    }

    static CustomerEInvoiceErrorNoticeResponse toCustomerResponse(ErrorNotice.Snapshot notice) {
        return new CustomerEInvoiceErrorNoticeResponse(notice.id(), notice.einvoiceId(), notice.formCode(),
                notice.reason(), notice.correctBuyerName(), notice.correctBuyerAddress(), notice.status().name(),
                notice.createdAt(), notice.respondedAt(), ErrorNotice.FORM_NAME);
    }

    static EInvoiceErrorNoticeResponse toResponse(ErrorNotice.Snapshot notice) {
        return new EInvoiceErrorNoticeResponse(notice.id(), notice.einvoiceId(), notice.formCode(),
                notice.subject().name(), notice.reason(), notice.correctBuyerName(), notice.correctBuyerAddress(),
                notice.status().name(), notice.sentAt(), notice.responseCode(), notice.responseMessage(),
                notice.respondedAt(), notice.recordedBy(), notice.createdAt(), ErrorNotice.FORM_NAME);
    }

    static EInvoiceSettingsResponse toResponse(EInvoiceSettingsService.Settings settings) {
        var provider = settings.provider();
        return new EInvoiceSettingsResponse(provider.provider(), provider.environment(), provider.endpoint(),
                provider.username(), provider.secretSet(), provider.autoIssue(),
                settings.series().stream().map(series -> new EInvoiceSettingsResponse.Series(series.templateCode(),
                        series.symbol(), series.year())).toList(), provider.version());
    }

    static EInvoiceProviderResponse toResponse(ProviderAccounts.Account account,
                                               java.util.Optional<EInvoiceProvider> port) {
        return new EInvoiceProviderResponse(account.provider(), account.environment(), account.username(),
                account.secretSealed() != null, account.active(), account.autoIssue(), account.version(),
                port.map(EInvoiceProvider::accountInDeployment).orElse(false),
                port.map(EInvoiceProvider::deploymentAccountReady).orElse(false),
                port.map(EInvoiceProvider::issuesCorrections).orElse(false),
                port.map(EInvoiceProvider::adjustsReplacements).orElse(false),
                port.map(EInvoiceProvider::sendsErrorNotices).orElse(false));
    }

    private static EInvoiceResponse.Party party(InvoiceParty party) {
        return new EInvoiceResponse.Party(party.name(), party.taxCode(), party.address(), party.email());
    }

    /** Still to issue, past the end of its date (NĐ 254 Điều 9 khoản 1; WF-05 Q-1). */
    private static boolean overdue(EInvoiceStatus status, Instant dueAt, Instant now) {
        return !status.wasIssued() && status != EInvoiceStatus.VOIDED_DRAFT && !now.isBefore(dueAt);
    }

    static EInvoiceSignatureResponse toResponse(InvoiceXmlSignature.Check check) {
        return new EInvoiceSignatureResponse(check.status().name(), check.valid(), check.signer(),
                check.signerTaxCode(), check.signerOrganization(), check.issuer(), check.signingTime(),
                check.certificateValidFrom(), check.certificateValidTo(), check.certificateValidAtSigning(),
                check.buyerSigned(), check.chainChecked());
    }
}
