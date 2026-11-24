package com.stockflow.invoicing.internal.service;

import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Drafts the agreement two companies sign before an invoice is adjusted or replaced ("Biên bản thỏa thuận điều chỉnh /
 * thay thế hóa đơn", Thông tư 91/2026/TT-BTC Điều 10), from the correction already drawn up: the invoice it corrects,
 * the reason the accountant gave, and each item the correction changes - what the invoice says beside what is right.
 *
 * <p>The paper is a draft to print and sign; the signed scan comes back through {@code PUT /einvoices/{id}/agreement}.
 * So it is drawn from the correction as it stands and never stored: a correction revised before it is issued redraws
 * its agreement. Once the agreement was attached, the draft carries the number and the date recorded then, so a
 * reprint matches the signed copy.</p>
 *
 * <p>The items are worked out, not typed: for a replacement, every difference between the two invoices - the buyer's
 * name, address and tax code, each line's quantity, price and rate; for an adjustment, whose lines are differences,
 * the quantity the invoice held against what it holds once adjusted. The three totals close the table whenever the
 * money changes.</p>
 */
@Service
public class EInvoiceAgreementPaperService {

    /** A drawn paper and the name its download carries. */
    public record Pdf(String fileName, byte[] content) {
    }

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final EInvoiceQueryService invoices;
    private final EInvoiceQueries queries;
    private final CustomerLegalProfiles legalProfiles;
    private final InvoiceAdjustmentAgreementDocument document;
    private final Clock clock;

    EInvoiceAgreementPaperService(EInvoiceQueryService invoices, EInvoiceQueries queries,
                                  CustomerLegalProfiles legalProfiles, InvoiceAdjustmentAgreementDocument document,
                                  Clock clock) {
        this.invoices = invoices;
        this.queries = queries;
        this.legalProfiles = legalProfiles;
        this.document = document;
        this.clock = clock;
    }

    /**
     * The agreement of one correction, for the back office.
     *
     * @param id the adjustment or the replacement - not the invoice it corrects
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code EINVOICE_CORRECTION_NOT_APPLICABLE} for an invoice
     *         that corrects nothing, a correction voided as a draft, or one whose original has no number yet
     */
    @Transactional(readOnly = true)
    public Pdf agreement(UUID id) {
        EInvoice.Snapshot correction = invoices.get(id);
        if (correction.type() == EInvoiceType.ORIGINAL || correction.originalEInvoiceId() == null
                || correction.status() == EInvoiceStatus.VOIDED_DRAFT) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                    "Invoice %s is not an adjustment or a replacement in force".formatted(correction.code()));
        }
        EInvoice.Snapshot original = invoices.get(correction.originalEInvoiceId());
        if (original.invoiceNumber() == null) {
            throw new BusinessException(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE,
                    "Invoice %s has no number to name in an agreement".formatted(original.code()));
        }
        ResourceBundle t = DocumentParts.bundle();
        CustomerLegalProfile profile = legalProfiles.legalProfile(original.customerId()).orElse(null);
        DocumentParts.Party buyer = new DocumentParts.Party(original.buyer().name(), original.buyer().taxCode(),
                original.buyer().address(), profile == null ? null : profile.representativeName(),
                profile == null ? null : profile.representativeTitle());
        String number = correction.agreementNumber() != null ? correction.agreementNumber()
                : "BBTT-" + correction.code();
        LocalDate dated = correction.agreementDate() != null ? correction.agreementDate()
                : LocalDate.now(clock.withZone(BUSINESS_ZONE));
        byte[] pdf = document.render(new InvoiceAdjustmentAgreementDocument.Facts(number, dated,
                correction.type() == EInvoiceType.REPLACEMENT, null, null, buyer, original.templateCode(),
                original.symbol(), original.invoiceNumber(), original.issueDate(), original.taxAuthorityCode(),
                correction.adjustmentReason(), changes(t, original, correction)));
        return new Pdf(t.getString(correction.type() == EInvoiceType.REPLACEMENT
                ? "document.file.invoiceAgreement.replacement" : "document.file.invoiceAgreement.adjustment")
                .formatted(number), pdf);
    }

    /**
     * The agreement of a correction issued to the customer. As every invoice read on the portal, one that is not the
     * customer's or not issued is not found - the customer learns nothing of the back office's drafts.
     */
    @Transactional(readOnly = true)
    public Pdf agreementOfCustomer(UUID customerId, UUID id) {
        if (queries.findIssuedTo(customerId, id).isEmpty()) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_FOUND);
        }
        return agreement(id);
    }

    /** Each item the correction changes: what the original says, what is right. */
    static List<InvoiceAdjustmentAgreementDocument.Change> changes(ResourceBundle t, EInvoice.Snapshot original,
                                                                    EInvoice.Snapshot correction) {
        boolean replacement = correction.type() == EInvoiceType.REPLACEMENT;
        List<InvoiceAdjustmentAgreementDocument.Change> changes = new ArrayList<>();
        differs(changes, t, "invoice-papers.buyer.name", original.buyer().name(), correction.buyer().name());
        differs(changes, t, "invoice-papers.buyer.address", original.buyer().address(), correction.buyer().address());
        differs(changes, t, "invoice-papers.buyer.taxCode", original.buyer().taxCode(), correction.buyer().taxCode());

        Map<String, EInvoiceLine> recorded = new LinkedHashMap<>();
        original.lines().stream().filter(line -> line.kind() != EInvoiceLine.Kind.NOTE)
                .forEach(line -> recorded.putIfAbsent(key(line), line));
        Map<String, EInvoiceLine> corrected = new LinkedHashMap<>();
        correction.lines().stream().filter(line -> line.kind() != EInvoiceLine.Kind.NOTE)
                .forEach(line -> corrected.putIfAbsent(key(line), line));
        corrected.forEach((key, line) -> {
            EInvoiceLine was = recorded.get(key);
            if (replacement) {
                replaced(changes, t, was, line);
            } else {
                adjusted(changes, t, was, line);
            }
        });
        if (replacement) {
            recorded.forEach((key, was) -> {
                if (!corrected.containsKey(key)) {
                    replaced(changes, t, was, null);
                }
            });
        }

        BigDecimal subtotal = replacement ? correction.subtotal() : original.subtotal().add(correction.subtotal());
        BigDecimal tax = replacement ? correction.taxTotal() : original.taxTotal().add(correction.taxTotal());
        BigDecimal total = replacement ? correction.grandTotal()
                : original.grandTotal().add(correction.grandTotal());
        if (total.compareTo(original.grandTotal()) != 0 || subtotal.compareTo(original.subtotal()) != 0) {
            changes.add(new InvoiceAdjustmentAgreementDocument.Change(t.getString("invoice-papers.subtotal"),
                    dong(t, original.subtotal()), dong(t, subtotal)));
            changes.add(new InvoiceAdjustmentAgreementDocument.Change(t.getString("invoice-papers.tax"),
                    dong(t, original.taxTotal()), dong(t, tax)));
            BigDecimal moved = total.subtract(original.grandTotal());
            changes.add(new InvoiceAdjustmentAgreementDocument.Change(t.getString("invoice-papers.total"),
                    dong(t, original.grandTotal()), moved.signum() == 0 ? dong(t, total)
                    : t.getString(moved.signum() < 0 ? "invoice-papers.decrease" : "invoice-papers.increase")
                    .formatted(DocumentFormats.money(total), DocumentFormats.money(moved.abs()))));
        }
        return changes;
    }

    /** A replacement's line against the original's: quantity, price and rate, each where it differs. */
    private static void replaced(List<InvoiceAdjustmentAgreementDocument.Change> changes, ResourceBundle t,
                                 EInvoiceLine was, EInvoiceLine now) {
        EInvoiceLine named = now != null ? now : was;
        String absent = t.getString("invoice-papers.absent");
        if (was == null || now == null || was.quantity() != now.quantity()) {
            changes.add(new InvoiceAdjustmentAgreementDocument.Change(
                    t.getString("invoice-papers.quantity").formatted(named.itemName()),
                    was == null ? absent : quantity(t, was.quantity(), was.unit()),
                    now == null ? absent : quantity(t, now.quantity(), now.unit())));
        }
        if (was != null && now != null && was.unitPrice().compareTo(now.unitPrice()) != 0) {
            changes.add(new InvoiceAdjustmentAgreementDocument.Change(
                    t.getString("invoice-papers.unitPrice").formatted(named.itemName()), dong(t, was.unitPrice()),
                    dong(t, now.unitPrice())));
        }
        if (was != null && now != null && was.taxRate().compareTo(now.taxRate()) != 0) {
            changes.add(new InvoiceAdjustmentAgreementDocument.Change(
                    t.getString("invoice-papers.taxRate").formatted(named.itemName()),
                    DocumentFormats.rate(was.taxRate()), DocumentFormats.rate(now.taxRate())));
        }
    }

    /**
     * An adjustment's line is a difference. At the original's price it is a difference in quantity, and the row says
     * the quantity before and after; at another price it is money alone, and the row says how much the line moves.
     */
    private static void adjusted(List<InvoiceAdjustmentAgreementDocument.Change> changes, ResourceBundle t,
                                 EInvoiceLine was, EInvoiceLine delta) {
        if (was != null && was.unitPrice().compareTo(delta.unitPrice()) == 0) {
            changes.add(new InvoiceAdjustmentAgreementDocument.Change(
                    t.getString("invoice-papers.quantity").formatted(delta.itemName()),
                    quantity(t, was.quantity(), was.unit()),
                    quantity(t, was.quantity() + delta.quantity(), was.unit())));
            return;
        }
        changes.add(new InvoiceAdjustmentAgreementDocument.Change(
                t.getString("invoice-papers.line").formatted(delta.itemName()),
                was == null ? t.getString("invoice-papers.absent") : dong(t, was.amount()),
                t.getString(delta.amount().signum() < 0 ? "invoice-papers.lineDecrease"
                        : "invoice-papers.lineIncrease").formatted(DocumentFormats.money(delta.amount().abs()))));
    }

    private static void differs(List<InvoiceAdjustmentAgreementDocument.Change> changes, ResourceBundle t,
                                String key, String recorded, String correct) {
        if (!Objects.equals(recorded, correct)) {
            String absent = t.getString("invoice-papers.absent");
            changes.add(new InvoiceAdjustmentAgreementDocument.Change(t.getString(key),
                    DocumentParts.filled(recorded) ? recorded : absent,
                    DocumentParts.filled(correct) ? correct : absent));
        }
    }

    /** What makes two lines the same item on both invoices: the order line sold, or the fee charged. */
    private static String key(EInvoiceLine line) {
        return line.kind() == EInvoiceLine.Kind.GOODS ? "G:" + line.orderLineId() : "F:" + line.feeKind();
    }

    private static String quantity(ResourceBundle t, long quantity, String unit) {
        return t.getString("invoice-papers.quantityValue").formatted(DocumentFormats.number(quantity), unit);
    }

    private static String dong(ResourceBundle t, BigDecimal amount) {
        return t.getString("document.dong").formatted(DocumentFormats.money(amount));
    }
}
