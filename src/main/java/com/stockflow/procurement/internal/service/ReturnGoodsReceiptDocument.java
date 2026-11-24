package com.stockflow.procurement.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Run;
import com.stockflow.common.document.BusinessDocument.Signatures;
import com.stockflow.common.document.BusinessDocument.Signer;
import com.stockflow.common.document.BusinessDocument.Table;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.Letterhead;
import com.stockflow.common.document.SellerLetterhead;
import com.stockflow.common.document.Style;
import com.stockflow.common.text.VietnameseAmountInWords;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Draws the goods receipt voucher for goods a customer returns ("phiếu nhập kho", Mẫu số 01 - VT) as a PDF: the
 * accounting voucher the warehouse and the accountant sign when returned goods come back in, and the document a refund
 * is counted from (owner decision Q-7: the money of returned goods is settled when the warehouse confirms this
 * receipt).
 *
 * <p>The form is the Ministry of Finance's, issued with Thông tư 99/2025/TT-BTC (in force from 1 January 2026, in
 * place of Thông tư 200/2014): the unit at the left and "Mẫu số: 01 - VT" at the right, the date and number under the
 * title, who delivered, under which document, into which warehouse, the table with its columns lettered A to D and
 * numbered 1 to 4, the total in words, and four signatures - no director, unlike the issue voucher. The circular lets
 * a company adapt the form, but one that keeps the ministry's shape is the one an auditor reads without questions, so
 * the shape is kept and only what a return needs is added: the original invoice among the references, and a note under
 * a line.</p>
 *
 * <p>Column 1 is the quantity the documents give (the labels of the cartons that came back) and column 2 the quantity
 * actually counted, which is what the form's two quantity columns exist for; a line counted short, or opened and
 * written off rather than restocked (owner decision Q-18: the true count goes on the voucher), says so in its note. The
 * price is the cost the goods left stock at, not the selling price; where the system does not hold it the price, the
 * amount and the total are left for the accountant to write in - the form says the accountant fills those columns -
 * rather than printed as zero. The words are the Vietnamese bundle's, the layout the kit's ({@link BusinessDocument})
 * in black.</p>
 */
@Component
class ReturnGoodsReceiptDocument {

    /**
     * Everything the voucher says.
     *
     * @param deliveredBy     who handed the goods over: the customer's person or the carrier
     * @param basis           the document the goods come back under, as "Biên bản thỏa thuận trả lại hàng hóa số ...
     *                        ngày ..."
     * @param customerName    whose document that is: the customer returning the goods
     * @param originalInvoice the invoice the goods were sold on; null when not known
     * @param attachments     the source documents attached, as the form's last line lists them
     * @param preparedBy      who drew the voucher up; null to leave the name to be written in
     */
    record Facts(String number, LocalDate dated, String deliveredBy, String basis, String customerName,
                 String originalInvoice, String warehouseName, String warehouseAddress, List<Line> lines,
                 List<String> attachments, String preparedBy) {
    }

    /**
     * One line.
     *
     * @param documented the quantity the documents give: what the labels of the cartons that came back say
     * @param received   the quantity counted in
     * @param unitCost   the cost the goods left stock at; null when not held
     * @param note       what happened to the line - counted short, opened and written off; null for none
     */
    record Line(String name, String code, String unit, long documented, long received, BigDecimal unitCost,
                String note) {
    }

    private final DocumentFonts fonts;
    private final SellerLetterhead letterhead;

    ReturnGoodsReceiptDocument(DocumentFonts fonts, SellerLetterhead letterhead) {
        this.fonts = fonts;
        this.letterhead = letterhead;
    }

    byte[] render(Facts f) {
        ResourceBundle t = DocumentParts.bundle();
        Letterhead head = letterhead.get();
        String blank = t.getString("document.blank");
        List<Block> blocks = new ArrayList<>();
        blocks.add(DocumentParts.voucherHead(t, head, t.getString("return-receipt.pdf.form")));
        String title = t.getString("return-receipt.pdf.title");
        String date = DocumentFormats.longDate(f.dated());
        String dated = date.substring(0, 1).toUpperCase(Locale.ROOT) + date.substring(1);
        // The form's own order: the date under the title, then the number, the accounts at the right.
        blocks.add(new Title(title, null, dated));
        blocks.add(Paragraph.of(t.getString("document.number") + " " + f.number()).align(Align.CENTRE).after(0));
        blocks.add(Paragraph.of(t.getString("return-receipt.pdf.accounts")).align(Align.RIGHT));
        blocks.add(point(t.getString("return-receipt.pdf.deliveredBy").formatted(orBlank(f.deliveredBy(), blank))));
        blocks.add(point(t.getString("return-receipt.pdf.basis").formatted(f.basis(), f.customerName())));
        if (DocumentParts.filled(f.originalInvoice())) {
            blocks.add(point(t.getString("return-receipt.pdf.invoice").formatted(f.originalInvoice())));
        }
        blocks.add(point(t.getString("return-receipt.pdf.warehouse").formatted(f.warehouseName(),
                orBlank(f.warehouseAddress(), blank))));

        // The total is stated only when every line has its cost: a sum of the lines that happen to have one would be
        // a figure on an accounting voucher that means nothing.
        boolean costed = f.lines().stream().allMatch(line -> line.unitCost() != null);
        BigDecimal total = BigDecimal.ZERO;
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(lettered("A", "B", "C", "D", "1", "2", "3", "4"));
        int no = 0;
        for (Line line : f.lines()) {
            BigDecimal amount = line.unitCost() == null ? null
                    : line.unitCost().multiply(BigDecimal.valueOf(line.received()));
            if (amount != null) {
                total = total.add(amount);
            }
            rows.add(List.of(Cell.of(String.valueOf(++no)),
                    Cell.of(line.name()).details(DocumentParts.filled(line.note()) ? List.of(line.note())
                            : List.of()),
                    Cell.of(line.code()), Cell.of(line.unit()), Cell.of(DocumentFormats.number(line.documented())),
                    Cell.of(DocumentFormats.number(line.received())),
                    Cell.of(line.unitCost() == null ? "" : DocumentFormats.money(line.unitCost())),
                    Cell.of(amount == null ? "" : DocumentFormats.money(amount))));
        }
        rows.add(List.of(Cell.of(""), Cell.bold(t.getString("return-receipt.pdf.total")), cross(), cross(), cross(),
                cross(), cross(), Cell.bold(costed ? DocumentFormats.money(total) : "")));
        blocks.add(new Table(List.of(new Column(t.getString("document.goods.no"), 26, Align.CENTRE),
                new Column(t.getString("return-receipt.pdf.col.name"), 160, Align.LEFT),
                new Column(t.getString("return-receipt.pdf.col.code"), 78, Align.LEFT),
                new Column(t.getString("return-receipt.pdf.col.unit"), 36, Align.CENTRE),
                new Column(t.getString("return-receipt.pdf.col.documented"), 52, Align.RIGHT),
                new Column(t.getString("return-receipt.pdf.col.received"), 48, Align.RIGHT),
                new Column(t.getString("return-receipt.pdf.col.unitCost"), 52, Align.RIGHT),
                new Column(t.getString("return-receipt.pdf.col.amount"), 68, Align.RIGHT)), rows));

        String words = costed ? VietnameseAmountInWords.ofWhole(total).map(w -> w + ".").orElse(blank + blank)
                : blank + blank;
        blocks.add(Paragraph.numbered("-", Run.of(t.getString("return-receipt.pdf.inWords") + " "),
                new Run(words, costed ? Style.BOLD_ITALIC : Style.REGULAR)).align(Align.LEFT).after(2));
        blocks.add(point(t.getString("return-receipt.pdf.attachments").formatted(f.attachments().isEmpty() ? blank
                : String.join("; ", f.attachments()))));

        String caption = t.getString("document.sign.voucher");
        blocks.add(new Signatures(dated, List.of(
                new Signer(t.getString("document.sign.preparedByVoucher"), caption, f.preparedBy()),
                new Signer(t.getString("return-receipt.pdf.sign.deliverer"), caption, f.deliveredBy()),
                new Signer(t.getString("return-receipt.pdf.sign.storekeeper"), caption, null),
                new Signer(t.getString("return-receipt.pdf.sign.chiefAccountant"),
                        t.getString("return-receipt.pdf.sign.orDepartment") + " " + caption, null))));
        return BusinessDocument.draw(fonts, DocumentParts.voucher(t, f.number(), head), title + " " + f.number(),
                blocks);
    }

    private static Paragraph point(String text) {
        return Paragraph.numbered("-", Run.of(text)).align(Align.LEFT).after(2);
    }

    /** The row under the header that letters and numbers the form's columns, as the ministry's form does. */
    private static List<Cell> lettered(String... marks) {
        List<Cell> cells = new ArrayList<>();
        for (String mark : marks) {
            cells.add(Cell.of(mark).align(Align.CENTRE));
        }
        return cells;
    }

    /** The "x" the form puts in a total row's cells that sum nothing. */
    private static Cell cross() {
        return Cell.of("x").align(Align.CENTRE);
    }

    private static String orBlank(String value, String blank) {
        return DocumentParts.filled(value) ? value : blank;
    }
}
