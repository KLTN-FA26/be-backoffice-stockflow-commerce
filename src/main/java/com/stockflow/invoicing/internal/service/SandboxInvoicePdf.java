package com.stockflow.invoicing.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
import com.stockflow.common.document.BusinessDocument.Field;
import com.stockflow.common.document.BusinessDocument.Fields;
import com.stockflow.common.document.BusinessDocument.Layout;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Run;
import com.stockflow.common.document.BusinessDocument.Signatures;
import com.stockflow.common.document.BusinessDocument.Signer;
import com.stockflow.common.document.BusinessDocument.Table;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.Style;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.InvoiceDocument;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.domain.InvoiceAbbreviations;
import com.stockflow.invoicing.internal.domain.InvoiceParty;
import com.stockflow.invoicing.internal.domain.TaxRateTotal;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Draws the sandbox's invoice as a PDF (plan §1.3): everything NĐ 254/2026 Điều 10 khoản 1 makes an invoice say - its
 * name, template, symbol and number, the seller and the buyer, each line with its unit, quantity, price, amount, rate
 * and tax, the tax by rate, the totals in figures and in words, the date, the tax authority's code - set out as a
 * value-added tax invoice is: the goods in a ruled table, the tax by rate in a second one, the two signature places
 * under them. The layout is the kit's ({@link BusinessDocument}), the labels the Vietnamese bundle's.
 *
 * <p>It says <b>"HOÁ ĐƠN THỬ NGHIỆM – KHÔNG CÓ GIÁ TRỊ PHÁP LÝ"</b> as its first line and its last, and "HÓA ĐƠN THỬ
 * NGHIỆM" across every page, so no page of it - not even one pulled from the middle - can be mistaken for an invoice:
 * the sandbox signs nothing and sends nothing to the tax authority. In black alone, and without the company's logo,
 * for the same reason: it must not look like the invoice the provider will draw.</p>
 *
 * <p>The buyer's name and address are written whole unless they outrun the room this layout gives them; then with the
 * abbreviations the law allows ({@link InvoiceAbbreviations}), never cut.</p>
 */
@Component
class SandboxInvoicePdf {

    /** The room this layout gives a name and an address before it abbreviates them. */
    static final int NAME_ROOM = 120;
    static final int ADDRESS_ROOM = 200;

    private final DocumentFonts fonts;

    SandboxInvoicePdf(DocumentFonts fonts) {
        this.fonts = fonts;
    }

    byte[] render(InvoiceDocument invoice, TaxAuthorityInvoiceXml.Issued issued) {
        ResourceBundle t = DocumentParts.bundle();
        Layout layout = Layout.commercial(null, t.getString("einvoice.pdf.lookup") + ": " + issued.lookupCode(), null,
                t.getString("document.page"), t.getString("einvoice.pdf.watermark"));
        return BusinessDocument.draw(fonts, layout, "E-invoice " + invoice.referenceCode(), blocks(t, invoice, issued));
    }

    private static List<Block> blocks(ResourceBundle t, InvoiceDocument invoice, TaxAuthorityInvoiceXml.Issued issued) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(Paragraph.bold(t.getString("einvoice.pdf.sandbox")).align(Align.CENTRE));
        LocalDate date = invoice.issueDate();
        blocks.add(new Title(t.getString("einvoice.pdf.title"),
                t.getString("einvoice.pdf.series").formatted(invoice.templateCode(), invoice.symbol(),
                        issued.invoiceNumber()),
                t.getString("einvoice.pdf.date").formatted("%02d".formatted(date.getDayOfMonth()),
                        "%02d".formatted(date.getMonthValue()), Integer.toString(date.getYear()))));
        if (invoice.correction() != null) {
            // TT 91 Điều 10 khoản 1 điểm b.1, b.2: what it is, the invoice it changes in the law's words, and why.
            blocks.add(Paragraph.bold(t.getString(invoice.correction().type() == EInvoiceType.REPLACEMENT
                    ? "einvoice.pdf.title.replacement" : "einvoice.pdf.title.adjustment")).align(Align.CENTRE)
                    .after(0));
            blocks.add(Paragraph.bold(CorrectionStatement.of(invoice.correction())).align(Align.CENTRE).after(0));
            blocks.add(Paragraph.of(t.getString("einvoice.correction.reason") + ": " + invoice.correction().reason())
                    .align(Align.CENTRE));
        }
        blocks.add(Paragraph.of(t.getString("einvoice.pdf.taxAuthorityCode") + ": " + issued.taxAuthorityCode())
                .align(Align.CENTRE).after(8));

        party(blocks, t, "einvoice.pdf.seller", invoice.seller(), List.of());
        party(blocks, t, "einvoice.pdf.buyer", invoice.buyer(), List.of(
                Field.of(t.getString("einvoice.pdf.payment"),
                        t.getString("einvoice.pdf.payment." + invoice.paymentMethod())),
                Field.of(t.getString("einvoice.pdf.currency"), invoice.currency()),
                Field.of(t.getString("einvoice.pdf.order"), invoice.orderNumber()),
                Field.of(t.getString("einvoice.pdf.contract"),
                        invoice.contractNumber() == null ? "-" : invoice.contractNumber())));

        blocks.add(lines(t, invoice));
        blocks.add(byRate(t, invoice));
        blocks.add(Paragraph.of(Run.of(t.getString("einvoice.pdf.inWords") + ": "),
                new Run(invoice.amountInWords(), Style.BOLD_ITALIC)).align(Align.LEFT));

        blocks.add(new Signatures(null, List.of(
                new Signer(t.getString("einvoice.pdf.sign.buyer"), t.getString("einvoice.pdf.sign.buyerHint"), null),
                new Signer(t.getString("einvoice.pdf.sign.seller"),
                        t.getString("einvoice.pdf.signedBy").formatted(invoice.seller().name(),
                                DocumentFormats.date(BusinessCalendar.date(issued.signedAt()))), null))));
        blocks.add(Paragraph.bold(t.getString("einvoice.pdf.sandbox")).align(Align.CENTRE));
        return blocks;
    }

    private static void party(List<Block> blocks, ResourceBundle t, String key, InvoiceParty party,
                              List<Field> more) {
        blocks.add(Paragraph.of(Run.of(t.getString(key) + ": "),
                Run.bold(InvoiceAbbreviations.fit(party.name(), NAME_ROOM))).align(Align.LEFT).after(0));
        List<Field> fields = new ArrayList<>();
        fields.add(Field.of(t.getString("einvoice.pdf.taxCode"), party.taxCode() == null ? "-" : party.taxCode()));
        fields.add(Field.of(t.getString("einvoice.pdf.address"),
                InvoiceAbbreviations.fit(party.address(), ADDRESS_ROOM)));
        if (party.email() != null) {
            fields.add(Field.of(t.getString("einvoice.pdf.email"), party.email()));
        }
        fields.addAll(more);
        blocks.add(new Fields(fields, 0));
    }

    /** Each line as a row: a note line (an adjustment that changes no money) carries its text alone. */
    private static Table lines(ResourceBundle t, InvoiceDocument invoice) {
        List<List<Cell>> rows = new ArrayList<>();
        for (EInvoiceLine line : invoice.lines()) {
            String name = line.unitsPerCarton() == null ? line.itemName()
                    : t.getString("einvoice.pdf.line.carton").formatted(line.itemName(), line.unitsPerCarton());
            rows.add(List.of(Cell.of(String.valueOf(line.lineNo())),
                    Cell.of(name).details(line.sku() == null ? List.of() : List.of(line.sku())),
                    Cell.of(line.unit() == null ? "" : line.unit()),
                    Cell.of(line.unitPrice() == null ? "" : DocumentFormats.number(line.quantity())),
                    Cell.of(money(line.unitPrice())), Cell.of(money(line.amount())),
                    Cell.of(line.taxRate() == null ? "" : DocumentFormats.rate(line.taxRate())),
                    Cell.of(money(line.taxAmount()))));
        }
        return new Table(List.of(new Column(t.getString("document.goods.no"), 28, Align.CENTRE),
                new Column(t.getString("einvoice.pdf.col.name"), 170, Align.LEFT),
                new Column(t.getString("document.goods.unit"), 42, Align.CENTRE),
                new Column(t.getString("document.goods.quantity"), 48, Align.RIGHT),
                new Column(t.getString("einvoice.pdf.col.unitPrice"), 62, Align.RIGHT),
                new Column(t.getString("einvoice.pdf.col.amount"), 72, Align.RIGHT),
                new Column(t.getString("einvoice.pdf.col.rate"), 40, Align.CENTRE),
                new Column(t.getString("einvoice.pdf.col.tax"), 62, Align.RIGHT)), rows);
    }

    /** The tax by rate (NĐ 254 Điều 10 khoản 1), then the invoice's three totals in one row under them. */
    private static Table byRate(ResourceBundle t, InvoiceDocument invoice) {
        List<List<Cell>> rows = new ArrayList<>();
        for (TaxRateTotal total : invoice.totalsByRate()) {
            rows.add(List.of(Cell.of(t.getString("einvoice.pdf.byRate").formatted(DocumentFormats.rate(
                            total.taxRate()))), Cell.of(money(total.amount())), Cell.of(money(total.taxAmount())),
                    Cell.of(money(total.amount().add(total.taxAmount())))));
        }
        rows.add(List.of(Cell.bold(t.getString("einvoice.pdf.total")), Cell.bold(money(invoice.subtotal())),
                Cell.bold(money(invoice.taxTotal())), Cell.bold(dong(invoice.grandTotal()))));
        return new Table(List.of(new Column(t.getString("einvoice.pdf.col.byRate"), 150, Align.LEFT),
                new Column(t.getString("einvoice.pdf.subtotal"), 124, Align.RIGHT),
                new Column(t.getString("einvoice.pdf.taxTotal"), 110, Align.RIGHT),
                new Column(t.getString("einvoice.pdf.grandTotal"), 140, Align.RIGHT)), rows);
    }

    /** NĐ 254 Phụ lục điểm 8: a dot between thousands. Blank for a line that states no money. */
    private static String money(BigDecimal amount) {
        return amount == null ? "" : DocumentFormats.money(amount);
    }

    /** The amount to pay with the dong's sign, "đ": the one figure a reader must not take for a quantity. */
    private static String dong(BigDecimal amount) {
        return DocumentFormats.money(amount) + " đ";
    }
}
