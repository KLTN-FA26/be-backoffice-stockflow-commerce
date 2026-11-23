package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an invoice's own XML says it is, for one recorded from the provider's portal (WF-05 BE-08, BE-10; decision
 * Q-8): its template and symbol ({@code KHMSHDon}, {@code KHHDon}), number ({@code SHDon}), date ({@code NLap}), the
 * seller's tax code ({@code NBan/MST}), the tax authority's code ({@code MCCQT}) and when it was signed - the fields of
 * the tax authority's XML format for e-invoices (NĐ 254/2026/NĐ-CP). The accountant types what the
 * portal shows; the XML is the invoice in law, so what was typed is held against it, and an XML of another invoice is
 * refused ({@code EINVOICE_PORTAL_FILE_INVALID}). Read with patterns over the text, as the provider adapters read the
 * code and signing time: nothing here resolves entities or follows a reference, so a file handed in cannot make the
 * reader fetch anything. The signature is checked apart ({@link InvoiceXmlSignature}).
 */
record PortalInvoiceXml(String templateCode, String symbol, Integer number, LocalDate issueDate,
                        String sellerTaxCode, String taxAuthorityCode, Instant signedAt) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Pattern SELLER = Pattern.compile("<(?:\\w+:)?NBan\\b[^>]*>(.*?)</(?:\\w+:)?NBan>",
            Pattern.DOTALL);

    /**
     * @throws BusinessException {@code EINVOICE_PORTAL_FILE_INVALID} for a file that is not an invoice's XML: no
     *         template, symbol or number in it
     */
    static PortalInvoiceXml read(byte[] xml) {
        String text = xml == null ? "" : new String(xml, StandardCharsets.UTF_8);
        String template = field(text, "KHMSHDon");
        String symbol = field(text, "KHHDon");
        String number = field(text, "SHDon");
        if (template == null || symbol == null || number == null || !number.matches("[0-9]{1,8}")) {
            throw new BusinessException(ErrorCode.EINVOICE_PORTAL_FILE_INVALID,
                    "The XML handed in is not an e-invoice: it names no template, symbol and number");
        }
        Matcher seller = SELLER.matcher(text);
        String sellerTaxCode = seller.find() ? field(seller.group(1), "MST") : null;
        return new PortalInvoiceXml(template, symbol, Integer.parseInt(number), date(field(text, "NLap")),
                sellerTaxCode, field(text, "MCCQT"), instant(field(text, "SigningTime")));
    }

    private static String field(String text, String name) {
        Matcher matcher = Pattern.compile("<(?:\\w+:)?" + name + "\\b[^>]*>\\s*([^<]*?)\\s*</").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).strip();
        return value.isEmpty() ? null : value;
    }

    private static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException noOffset) {
            try {
                return LocalDateTime.parse(value).atZone(ZONE).toInstant();
            } catch (DateTimeParseException unreadable) {
                return null;
            }
        }
    }
}
