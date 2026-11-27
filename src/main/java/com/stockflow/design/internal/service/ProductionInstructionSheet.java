package com.stockflow.design.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
import com.stockflow.common.document.BusinessDocument.Field;
import com.stockflow.common.document.BusinessDocument.Fields;
import com.stockflow.common.document.BusinessDocument.Heading;
import com.stockflow.common.document.BusinessDocument.Layout;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Signatures;
import com.stockflow.common.document.BusinessDocument.Signer;
import com.stockflow.common.document.BusinessDocument.Table;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.Letterhead;
import com.stockflow.common.document.SellerLetterhead;
import com.stockflow.design.internal.domain.BagSheet;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintConfig;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * The production instruction issued with an approved design (kltn-docs 12 §4.5; plan D2): the paper the print shop
 * works from, generated from what the system knows - the customer's SKU, the variant and its carton spec, the
 * template's print areas and colour mode (and, for a bag, its flat sheet and folds), the layout's and the print file's
 * checksums and when it was approved - rather than drawn by the customer.
 *
 * <p>An internal order to production ("lệnh sản xuất"), so it carries the company's head and no Quốc hiệu, no price,
 * and three places to sign - who drew it up, the workshop, who approved - as such an order does on a shop floor. The
 * print areas are a table: a template may have many, and a table carries on over pages where the single page this
 * sheet once was let them run off the paper.</p>
 *
 * <p>Vietnamese, as the producer reads it; the words are the {@code design.instruction.*} keys of the bundle, the
 * layout the kit's ({@link BusinessDocument}) with its embedded serif, which draws every Vietnamese letter.</p>
 */
@Component
class ProductionInstructionSheet {

    private final DocumentFonts fonts;
    private final SellerLetterhead letterhead;
    private final PrintOutputIntentProperties intent;

    ProductionInstructionSheet(DocumentFonts fonts, SellerLetterhead letterhead, PrintOutputIntentProperties intent) {
        this.fonts = fonts;
        this.letterhead = letterhead;
        this.intent = intent;
    }

    /**
     * What the sheet states. {@code carton} is null when the variant has none (preflight refuses that already).
     * {@code printPages} is how many pages the stamped print file has: a bag's flat sheet is stated only when the file
     * is that one sheet - a bag submitted side by side before the sheet (four pages) is printed as it was drawn.
     * {@code approvedBy} is the approver's account: the design module does not read staff names, and the account is
     * what the audit trail is searched by.
     */
    record Facts(String customerSku, String productName, String sourceSku, PrintConfig template,
                 UomConversion carton, String layoutChecksum, String printFileChecksum, UUID approvedBy,
                 Instant approvedAt, int printPages) {
    }

    byte[] render(Facts facts) {
        ResourceBundle t = DocumentParts.bundle();
        Letterhead head = letterhead.get();
        PrintConfig template = facts.template();
        List<Block> blocks = new ArrayList<>();
        blocks.add(DocumentParts.brandHeader(t, head));
        blocks.add(new Title(t.getString("design.instruction.title"),
                t.getString("design.instruction.subtitle").formatted(plain(facts.customerSku())), null));

        List<Field> product = new ArrayList<>();
        product.add(field(t, "design.instruction.product", facts.productName()));
        product.add(field(t, "design.instruction.variant", facts.sourceSku()));
        product.add(field(t, "design.instruction.template", template.code() + " - " + template.name()));
        product.add(field(t, "design.instruction.colorMode", template.colorMode() + " - " + template.minDpi()
                + " DPI"));
        if (facts.carton() != null) {
            UomConversion carton = facts.carton();
            product.add(field(t, "design.instruction.carton", t.getString("design.instruction.cartonValue")
                    .formatted(carton.unitOfMeasure(), carton.factor(), plain(carton.lengthCm()),
                            plain(carton.widthCm()), plain(carton.heightCm()), plain(carton.weightKg()))));
        }
        // A bag is printed as one flat sheet (FE request 2026-11-18 BE-10): the producer reads its die-cut size and the
        // folds the sides do not show - the glue flap, the top fold, the bottom fold.
        if (template.model() instanceof BagModel bag && facts.printPages() == 1) {
            BagSheet sheet = BagSheet.of(bag, template.printAreas());
            product.add(field(t, "design.instruction.sheet", t.getString("design.instruction.sheetValue")
                    .formatted(sheet.trimWidth(), sheet.trimHeight(), sheet.bleedMm(), sheet.glueFlapMm(),
                            sheet.topFoldMm(), sheet.bottomFoldMm())));
        }
        blocks.add(new Fields(product, 0));

        blocks.add(new Heading(t.getString("design.instruction.areas")));
        List<List<Cell>> rows = new ArrayList<>();
        int no = 0;
        for (PrintArea area : template.printAreas()) {
            rows.add(List.of(Cell.of(String.valueOf(++no)), Cell.of(area.code()), Cell.bold(area.name()),
                    Cell.of(mm(area.widthMm()) + " x " + mm(area.heightMm())), Cell.of(mm(area.bleedMm())),
                    Cell.of(mm(area.safeMarginMm()))));
        }
        blocks.add(new Table(List.of(new Column(t.getString("document.goods.no"), 30, Align.CENTRE),
                new Column(t.getString("design.instruction.area.code"), 80, Align.LEFT),
                new Column(t.getString("design.instruction.area.name"), 150, Align.LEFT),
                new Column(t.getString("design.instruction.area.size"), 100, Align.CENTRE),
                new Column(t.getString("design.instruction.area.bleed"), 70, Align.CENTRE),
                new Column(t.getString("design.instruction.area.safe"), 70, Align.CENTRE)), rows));

        blocks.add(new Heading(t.getString("design.instruction.files")));
        blocks.add(new Fields(List.of(
                field(t, "design.instruction.layoutChecksum", facts.layoutChecksum()),
                field(t, "design.instruction.printFileChecksum", facts.printFileChecksum()),
                field(t, "design.instruction.approvedAt", DocumentFormats.moment(facts.approvedAt())),
                field(t, "design.instruction.approved", String.valueOf(facts.approvedBy()))), 0));
        // Which output intent PrintReadyStamp embedded, and whether it is still the interim one (B35).
        blocks.add(Paragraph.of(t.getString(intent.interim() ? "design.instruction.pdfxInterim"
                : "design.instruction.pdfx").formatted(intent.outputConditionIdentifier())).after(6));

        String caption = t.getString("document.sign.voucher");
        blocks.add(new Signatures(null, List.of(
                new Signer(t.getString("design.instruction.sign.drawnUp"), caption, null),
                new Signer(t.getString("design.instruction.sign.workshop"), caption, null),
                new Signer(t.getString("design.instruction.sign.approver"), caption, null))));
        return BusinessDocument.draw(fonts, Layout.commercial(DocumentParts.ACCENT, facts.customerSku(),
                        head.name(), t.getString("document.page"), null),
                t.getString("design.instruction.title") + " " + facts.customerSku(), blocks);
    }

    private static Field field(ResourceBundle t, String key, String value) {
        return Field.of(t.getString(key), plain(value));
    }

    private static String plain(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** Millimetres to one decimal, with the comma a Vietnamese paper writes a decimal with. */
    private static String mm(double value) {
        return String.format(Locale.ROOT, "%.1f", value).replace('.', ',');
    }
}
