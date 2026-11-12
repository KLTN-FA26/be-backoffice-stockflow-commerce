package com.stockflow.invoicing.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.label.LabelFonts;
import com.stockflow.common.label.PrintedDocument;
import com.stockflow.common.label.PrintedDocument.Line;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.InvoiceDocument;
import com.stockflow.invoicing.internal.domain.InvoiceAbbreviations;
import com.stockflow.invoicing.internal.domain.InvoiceParty;
import com.stockflow.invoicing.internal.domain.TaxRateTotal;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Draws the sandbox's invoice as a PDF (plan §1.3): everything NĐ 254/2026 Điều 10 khoản 1 makes an invoice say - its
 * name, template, symbol and number, the seller and the buyer, each line with its unit, quantity, price, amount, rate
 * and tax, the tax by rate, the totals in figures and in words, the date, the tax authority's code - in the layout of
 * the system's other documents ({@link PrintedDocument}), with the Vietnamese bundle's labels.
 *
 * <p>It says <b>"HOÁ ĐƠN THỬ NGHIỆM – KHÔNG CÓ GIÁ TRỊ PHÁP LÝ"</b> as its first line and its last, so no page of it
 * can be mistaken for an invoice: the sandbox signs nothing and sends nothing to the tax authority.</p>
 *
 * <p>The buyer's name and address are written whole unless they outrun the room this layout gives them; then with the
 * abbreviations the law allows ({@link InvoiceAbbreviations}), never cut.</p>
 */
@Component
class SandboxInvoicePdf {

    /** The room this layout gives a name and an address before it abbreviates them. */
    static final int NAME_ROOM = 120;
    static final int ADDRESS_ROOM = 200;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final LabelFonts fonts;

    SandboxInvoicePdf(LabelFonts fonts) {
        this.fonts = fonts;
    }

    byte[] render(InvoiceDocument invoice, TaxAuthorityInvoiceXml.Issued issued) {
        return PrintedDocument.draw(fonts, lines(invoice, issued), "E-invoice " + invoice.referenceCode(), null);
    }

    /** The document's lines, in order: what the PDF says, readable without opening a PDF. */
    static List<Line> lines(InvoiceDocument invoice, TaxAuthorityInvoiceXml.Issued issued) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        List<Line> lines = new ArrayList<>();
        lines.add(Line.bold(t.getString("einvoice.pdf.sandbox")));
        lines.add(Line.title(t.getString("einvoice.pdf.title")));
        lines.add(Line.bold(t.getString("einvoice.pdf.series").formatted(invoice.templateCode(), invoice.symbol(),
                issued.invoiceNumber())));
        LocalDate date = invoice.issueDate();
        lines.add(Line.text(t.getString("einvoice.pdf.date").formatted("%02d".formatted(date.getDayOfMonth()),
                "%02d".formatted(date.getMonthValue()), Integer.toString(date.getYear()))));
        lines.add(Line.text(t.getString("einvoice.pdf.taxAuthorityCode") + ": " + issued.taxAuthorityCode()));

        party(lines, t, "einvoice.pdf.seller", invoice.seller());
        party(lines, t, "einvoice.pdf.buyer", invoice.buyer());
        lines.add(Line.text(t.getString("einvoice.pdf.payment") + ": "
                + t.getString("einvoice.pdf.payment." + invoice.paymentMethod()) + "    "
                + t.getString("einvoice.pdf.currency") + ": " + invoice.currency()));
        lines.add(Line.text(t.getString("einvoice.pdf.reference").formatted(invoice.orderNumber(),
                invoice.contractNumber() == null ? "-" : invoice.contractNumber())));

        lines.add(Line.heading(t.getString("einvoice.pdf.lines")));
        for (EInvoiceLine line : invoice.lines()) {
            String name = line.unitsPerCarton() == null ? line.itemName()
                    : t.getString("einvoice.pdf.line.carton").formatted(line.itemName(), line.unitsPerCarton());
            if (line.sku() != null) {
                name = line.sku() + " - " + name;
            }
            lines.add(Line.text(t.getString("einvoice.pdf.line").formatted(line.lineNo(), name, line.unit(),
                    line.quantity(), money(line.unitPrice()), money(line.amount()), rate(line.taxRate()),
                    money(line.taxAmount()))));
        }
        for (TaxRateTotal total : invoice.totalsByRate()) {
            lines.add(Line.text(t.getString("einvoice.pdf.byRate").formatted(rate(total.taxRate()),
                    money(total.amount()), money(total.taxAmount()))));
        }
        lines.add(Line.text(t.getString("einvoice.pdf.subtotal") + ": " + money(invoice.subtotal())));
        lines.add(Line.text(t.getString("einvoice.pdf.taxTotal") + ": " + money(invoice.taxTotal())));
        lines.add(Line.bold(t.getString("einvoice.pdf.grandTotal") + ": " + money(invoice.grandTotal())));
        lines.add(Line.text(t.getString("einvoice.pdf.inWords") + ": " + invoice.amountInWords()));

        lines.add(Line.heading(t.getString("einvoice.pdf.lookup") + ": " + issued.lookupCode()));
        lines.add(Line.text(t.getString("einvoice.pdf.signedBy").formatted(invoice.seller().name(),
                DAY.format(BusinessCalendar.date(issued.signedAt())))));
        lines.add(Line.bold(t.getString("einvoice.pdf.sandbox")));
        return lines;
    }

    private static void party(List<Line> lines, ResourceBundle t, String key, InvoiceParty party) {
        lines.add(Line.heading(t.getString(key) + ": " + InvoiceAbbreviations.fit(party.name(), NAME_ROOM)));
        lines.add(Line.text(t.getString("einvoice.pdf.taxCode") + ": " + party.taxCode()));
        lines.add(Line.text(t.getString("einvoice.pdf.address") + ": "
                + InvoiceAbbreviations.fit(party.address(), ADDRESS_ROOM)));
        if (party.email() != null) {
            lines.add(Line.text(t.getString("einvoice.pdf.email") + ": " + party.email()));
        }
    }

    /** NĐ 254 Phụ lục điểm 8: a dot between thousands; the dong's sign is "đ". */
    private static String money(BigDecimal amount) {
        return PrintedDocument.money(amount, "\u0111");
    }

    private static String rate(BigDecimal percent) {
        return percent.stripTrailingZeros().toPlainString();
    }
}
