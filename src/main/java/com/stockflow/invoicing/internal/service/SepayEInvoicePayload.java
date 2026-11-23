package com.stockflow.invoicing.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.InvoiceDocument;
import com.stockflow.invoicing.internal.domain.InvoiceParty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * The bodies SePay eInvoice's create, adjust and replace calls take (developer.sepay.vn, "Tạo hóa đơn điện tử",
 * "Xuất hóa đơn điều chỉnh", "Xuất hóa đơn thay thế"), written from an invoice as the system holds it.
 *
 * <p>Amounts go as the system computed them: every line's {@code tax_amount} and the invoice's {@code total_amount}
 * are sent, so SePay uses them as they are instead of computing its own (its documentation: a value sent is "used
 * as-is"). An invoice's tax is the sum of its lines' taxes (kltn-docs 14 BR-21); a provider rounding the sum instead
 * could disagree with the invoice the system records by a dong.</p>
 */
final class SepayEInvoicePayload {

    /** The VAT rates SePay takes, in percent ({@code -1} "not declared" and {@code -2} "not subject" are never sent). */
    private static final Set<Integer> RATES = Set.of(0, 5, 8, 10);
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter ISSUED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SepayEInvoicePayload() {
    }

    /** A rate SePay cannot carry: the invoice is refused before anything is sent. */
    static final class UnsupportedRateException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        UnsupportedRateException(BigDecimal rate) {
            super("SePay eInvoice takes VAT at 0, 5, 8 or 10%, not " + rate.toPlainString() + "%");
        }
    }

    /** An original invoice, issued at once ({@code is_draft} false). */
    static String original(ObjectMapper json, InvoiceDocument invoice, String referenceCode, String providerAccountId,
                           String templateCode, Instant now) {
        ObjectNode root = common(json, invoice, referenceCode, now);
        root.put("template_code", templateCode);
        root.put("invoice_series", invoice.symbol());
        root.put("currency", invoice.currency());
        root.put("provider_account_id", providerAccountId);
        return root.toString();
    }

    /**
     * An adjustment of an issued invoice. Its lines are the change: a reduction is a negative quantity, which SePay keeps
     * with its sign on a {@code decrease} or {@code combined} adjustment.
     */
    static String adjustment(ObjectMapper json, InvoiceDocument invoice, String referenceCode, String providerAccountId,
                             String originalReference, Instant now) {
        ObjectNode root = common(json, invoice, referenceCode, now);
        root.put("provider_account_id", providerAccountId);
        root.put("original_invoice_reference_code", originalReference);
        root.put("adjustment_type", adjustmentType(invoice.lines()));
        return root.toString();
    }

    /** A replacement: the whole invoice again, as it should have been. */
    static String replacement(ObjectMapper json, InvoiceDocument invoice, String referenceCode,
                              String providerAccountId, String originalReference, Instant now) {
        ObjectNode root = common(json, invoice, referenceCode, now);
        root.put("provider_account_id", providerAccountId);
        root.put("original_invoice_reference_code", originalReference);
        root.put("currency", invoice.currency());
        return root.toString();
    }

    /**
     * What an adjustment does to the invoice's money: {@code info} for note lines only (a wrong name or tax code, no
     * money), {@code increase} when every line adds, {@code decrease} when every line takes away, and {@code combined}
     * otherwise.
     */
    static String adjustmentType(List<EInvoiceLine> lines) {
        boolean adds = false;
        boolean takes = false;
        for (EInvoiceLine line : lines) {
            if (line.kind() == EInvoiceLine.Kind.NOTE) {
                continue;
            }
            if (line.quantity() > 0) {
                adds = true;
            } else {
                takes = true;
            }
        }
        if (!adds && !takes) {
            return "info";
        }
        return adds && takes ? "combined" : adds ? "increase" : "decrease";
    }

    /**
     * {@code issued_date}: now when the invoice is dated today, the end of its own day otherwise - never in the future,
     * and never another day than the one the system records.
     *
     * <p>An invoice is dated the day it is submitted (WF-05 Q-1: {@code EInvoice} is re-dated at submit, since SePay
     * takes only today's date - sandbox runs T-1, T-6), so this normally sends now. A send can still cross midnight
     * after the invoice was dated: the first send of an invoice submitted at 23:59, or an automatic resend after
     * midnight. It then names the invoice's own day, which SePay refuses at once ({@code ISSUED_DATE_INVALID}, nothing
     * created), rather than "now", which would number an invoice under a day the system does not record. The refusal
     * is recorded, and the accountant reopens the invoice and issues it again, re-dated to that day.</p>
     */
    static String issuedDate(LocalDate issueDate, Instant now) {
        LocalDateTime at = issueDate.equals(LocalDate.ofInstant(now, ZONE))
                ? LocalDateTime.ofInstant(now, ZONE).withNano(0)
                : issueDate.atTime(LocalTime.of(23, 59, 59));
        return ISSUED.format(at);
    }

    /** SePay's {@code payment_method}: the seller is paid by bank transfer (kltn-docs 21 BR-11). */
    static String paymentMethod(String paymentMethod) {
        return switch (paymentMethod == null ? "" : paymentMethod) {
            case "BANK_TRANSFER" -> "CK";
            case "CASH" -> "TM";
            default -> "TM/CK";
        };
    }

    private static ObjectNode common(ObjectMapper json, InvoiceDocument invoice, String referenceCode, Instant now) {
        ObjectNode root = json.createObjectNode();
        root.put("reference_code", referenceCode);
        root.put("issued_date", issuedDate(invoice.issueDate(), now));
        root.put("payment_method", paymentMethod(invoice.paymentMethod()));
        root.put("is_draft", false);
        buyer(root.putObject("buyer"), invoice.buyer());
        ArrayNode items = root.putArray("items");
        for (EInvoiceLine line : invoice.lines()) {
            item(items.addObject(), line);
        }
        root.put("total_amount", invoice.grandTotal().longValueExact());
        // No notes: SePay's template prints them in its "Mã kho" (warehouse code) box, where an order number is wrong.
        return root;
    }

    /**
     * The buyer, without its e-mail (WF-05 Q-14, BE-27). With {@code buyer.email} SePay mails the buyer itself, for an
     * original, an adjustment and a replacement alike, and offers no switch to stop it (sandbox run T-3: one
     * {@code sent} e-mail log per invoice; an invoice sent without it has none). The system mails the invoice itself
     * ({@code EInvoiceIssuedNotificationListener}), after checking the seller's signature and with retries; both mailing
     * would send the buyer two, and SePay's would go even when the signature check withholds the system's. The e-mail is
     * not invoice content in law (NĐ 254/2026/NĐ-CP Điều 10 khoản 1), so the invoice is the same without it.
     */
    private static void buyer(ObjectNode buyer, InvoiceParty party) {
        // A business buyer (the seller sells B2B only): the company is the legal name. "name" is the person buying,
        // which the system does not record - sending the company there too printed it twice.
        buyer.put("type", "company");
        buyer.put("legal_name", party.name());
        putIfPresent(buyer, "tax_code", party.taxCode());
        putIfPresent(buyer, "address", party.address());
    }

    private static void item(ObjectNode item, EInvoiceLine line) {
        item.put("line_number", line.lineNo());
        if (line.kind() == EInvoiceLine.Kind.NOTE) {
            // A note line prints its name and nothing else.
            item.put("line_type", 4);
            item.put("item_name", line.itemName());
            return;
        }
        item.put("line_type", 1);
        String code = line.sku() != null ? line.sku() : line.feeKind() != null ? line.feeKind().name() : null;
        putIfPresent(item, "item_code", code);
        item.put("item_name", line.itemName());
        item.put("unit", line.unit());
        item.put("quantity", line.quantity());
        item.put("unit_price", line.unitPrice().longValueExact());
        item.put("tax_rate", taxRate(line.taxRate()));
        item.put("tax_amount", line.taxAmount().longValueExact());
    }

    /** @throws UnsupportedRateException for a line at a rate SePay does not take */
    static void requireRates(List<EInvoiceLine> lines) {
        for (EInvoiceLine line : lines) {
            if (line.kind() != EInvoiceLine.Kind.NOTE) {
                taxRate(line.taxRate());
            }
        }
    }

    /** @throws UnsupportedRateException for a rate SePay does not take */
    static int taxRate(BigDecimal rate) {
        BigDecimal whole = rate.stripTrailingZeros();
        if (whole.scale() > 0 || !RATES.contains(whole.intValue())) {
            throw new UnsupportedRateException(rate);
        }
        return whole.intValue();
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
