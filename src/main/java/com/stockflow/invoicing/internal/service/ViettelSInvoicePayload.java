package com.stockflow.invoicing.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.InvoiceDocument;
import com.stockflow.invoicing.internal.domain.TaxRateTotal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * An invoice as SInvoice's {@code createInvoice} takes it: the "CommonInvoiceInput" of Viettel's public description
 * (its chapter on the invoice's input) - {@code generalInvoiceInfo}, {@code sellerInfo}, {@code buyerInfo},
 * {@code payments}, {@code itemInfo}, {@code taxBreakdowns}, {@code summarizeInfo}. Every field name here is one that
 * description or Odoo's {@code l10n_vn_edi_viettel} shows; nothing is sent that neither shows.
 *
 * <p>Only an original invoice ({@code adjustmentType 1}) is written, and a correction is refused here rather than
 * written as one: SInvoice's fields for an adjustment and a replacement ({@code adjustmentType 5 / 3} and the reference
 * to the original) are not verified against a test account yet. {@link ViettelSInvoiceProvider#issuesCorrections} says
 * no, so a correction never reaches this class; the refusal here is the last line, should it ever.</p>
 *
 * <p>Amounts are the invoice's own, line by line. SInvoice computes totals too; sending ours lets it refuse an invoice
 * whose arithmetic it disagrees with instead of quietly issuing other figures.</p>
 */
final class ViettelSInvoicePayload {

    /** Vietnam's time: an invoice's date is a day there, and SInvoice reads times as GMT+7. */
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private ViettelSInvoicePayload() {
    }

    /**
     * @param transactionUuid      the transaction id as SInvoice takes it (10 to 36 characters)
     * @param providerTemplateCode SInvoice's own code of the template ({@code templateCode}, e.g. {@code 1/001})
     * @param paid                 {@code paymentStatus}, see {@link ViettelSInvoiceProperties#paymentStatusPaid()}
     */
    static String of(ObjectMapper json, InvoiceDocument invoice, String transactionUuid, String providerTemplateCode,
                     boolean paid, Instant now) {
        if (invoice.correction() != null) {
            throw new IllegalArgumentException("SInvoice payload: corrections are not written; " + transactionUuid
                    + " would be issued as an original");
        }
        ObjectNode root = json.createObjectNode();

        ObjectNode general = root.putObject("generalInvoiceInfo");
        general.put("transactionUuid", transactionUuid);
        // The kind of invoice is the template code of TT 91: 1 is a VAT invoice.
        general.put("invoiceType", invoice.templateCode());
        general.put("templateCode", providerTemplateCode);
        general.put("invoiceSeries", invoice.symbol());
        general.put("invoiceIssuedDate", issuedAt(invoice.issueDate(), now));
        general.put("currencyCode", invoice.currency());
        general.put("adjustmentType", "1");
        general.put("paymentStatus", paid);
        general.put("cusGetInvoiceRight", true);

        ObjectNode seller = root.putObject("sellerInfo");
        seller.put("sellerLegalName", invoice.seller().name());
        seller.put("sellerTaxCode", invoice.seller().taxCode());
        seller.put("sellerAddressLine", invoice.seller().address());

        ObjectNode buyer = root.putObject("buyerInfo");
        buyer.put("buyerLegalName", invoice.buyer().name());
        buyer.put("buyerTaxCode", invoice.buyer().taxCode());
        buyer.put("buyerAddressLine", invoice.buyer().address());
        if (invoice.buyer().email() != null) {
            buyer.put("buyerEmail", invoice.buyer().email());
        }
        // 0: the buyer takes the invoice, so its tax code and address are required - which they are (21 BR-06).
        buyer.put("buyerNotGetInvoice", 0);

        root.putArray("payments").addObject().put("paymentMethodName", paymentMethodName(invoice.paymentMethod()));

        ArrayNode items = root.putArray("itemInfo");
        for (EInvoiceLine line : invoice.lines()) {
            ObjectNode item = items.addObject();
            // 1: goods or a service. (2 is a note line, 3 a discount; the system has neither.)
            item.put("selection", 1);
            if (line.sku() != null) {
                item.put("itemCode", line.sku());
            }
            item.put("itemName", line.itemName());
            item.put("unitName", line.unit());
            item.put("unitPrice", line.unitPrice());
            item.put("quantity", line.quantity());
            item.put("itemTotalAmountWithoutTax", line.amount());
            item.put("taxPercentage", percentage(line.taxRate()));
            item.put("taxAmount", line.taxAmount());
            item.put("discount", 0);
            item.put("itemTotalAmountAfterDiscount", line.amount());
            item.put("itemTotalAmountWithTax", line.amount().add(line.taxAmount()));
        }

        ArrayNode breakdowns = root.putArray("taxBreakdowns");
        for (TaxRateTotal total : invoice.totalsByRate()) {
            ObjectNode breakdown = breakdowns.addObject();
            breakdown.put("taxPercentage", percentage(total.taxRate()));
            breakdown.put("taxableAmount", total.amount());
            breakdown.put("taxAmount", total.taxAmount());
        }

        // The two fields the description marks required. The other totals are SInvoice's to compute from the lines
        // (Odoo sends no summarizeInfo at all); their names are not sent on a guess.
        ObjectNode summary = root.putObject("summarizeInfo");
        summary.put("totalAmountWithoutTax", invoice.subtotal());
        summary.put("discountAmount", 0);

        return root.toString();
    }

    /**
     * {@code invoiceIssuedDate}: milliseconds since the epoch, whole seconds (the description's time standard). An
     * invoice dated today is issued now; one dated an earlier day - a delivery invoiced the next working day, which the
     * law allows - is dated the last second of its day, the latest moment that is still that date, so it is least
     * likely to fall before the previous invoice of the symbol, which SInvoice refuses.
     */
    static long issuedAt(LocalDate issueDate, Instant now) {
        LocalDate today = now.atZone(ZONE).toLocalDate();
        Instant at = issueDate.equals(today) ? now
                : issueDate.atTime(LocalTime.of(23, 59, 59)).atZone(ZONE).toInstant();
        return at.getEpochSecond() * 1000;
    }

    /**
     * The payment method as it is printed on the invoice. SInvoice prints {@code paymentMethodName} as given, so the
     * stored code {@code BANK_TRANSFER} would reach the buyer as an English code word on a Vietnamese invoice. A
     * transfer is printed "CK", the usual Vietnamese abbreviation for a bank transfer; anything else - there is nothing
     * else today, {@code ck_einvoice_payment_method} - is "TM/CK", cash or transfer, which is what an invoice says
     * when the method is not settled.
     */
    static String paymentMethodName(String paymentMethod) {
        return "BANK_TRANSFER".equals(paymentMethod) ? "CK" : "TM/CK";
    }

    /** 8.00 → 8, 7.50 → 7.5: SInvoice's rate is a number of percent. */
    private static BigDecimal percentage(BigDecimal rate) {
        BigDecimal stripped = rate.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
