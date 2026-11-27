package com.stockflow.order.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.BrandHeader;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
import com.stockflow.common.document.BusinessDocument.Field;
import com.stockflow.common.document.BusinessDocument.Fields;
import com.stockflow.common.document.BusinessDocument.Heading;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Run;
import com.stockflow.common.document.BusinessDocument.Split;
import com.stockflow.common.document.BusinessDocument.Table;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.Letterhead;
import com.stockflow.common.document.SellerLetterhead;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Draws a production order slip (4A-3, kltn-docs 17 §4.3 steps 6-7): the paper a producer makes the goods from and the
 * warehouse receives them against. It may go to the company's own workshop or to a partner, to whoever is at the
 * machine, so it says only what making and delivering the goods needs - what, how many cartons of how many units, from
 * which print files (each with its whole SHA-256, so a print shop can tell it holds the approved files), by when, to
 * which warehouse, whether a print sample comes first - with the need-by date, the address and the sample at a glance.
 *
 * <p>No money, no sales order or customer, no signatures (owner, 2026-11-19): prices and partner terms are other
 * departments' business outside the system; who sent it (BR-23) and the producer's acceptance are recorded in the
 * system, not on paper. No totals row either: units of different products do not add up, and the warehouse counts
 * line by line against the production order (03 §4.1). The coordinator who drew it up is named for questions.</p>
 *
 * <p>The code is printed as a barcode and a QR code: it is the lot the goods are received under (03 BR-03), and the
 * receiving scanner reads it from the slip the producer sends back with the goods.</p>
 *
 * <p>A commercial paper of the company, so it opens with the company's head as a quotation does - a partner's print
 * shop should see at once whose order it holds - and is laid out by the kit every other paper uses
 * ({@link BusinessDocument}), in the Vietnamese bundle's words. What it shows is given; beside the letterhead nothing
 * is looked up here.</p>
 */
@Component
class ProductionOrderSlipDocument {

    /**
     * Everything the slip says. Text a person typed (note, packing requirements) is printed as written; any optional
     * part that is null or blank is left out.
     */
    record Facts(String code, Instant sentAt, String companyName, String producerCode, String producerName,
                 String producerContact, String warehouseName, String warehouseAddress, LocalDate needByDate,
                 boolean sampleApprovalRequired, String note, String drawnUpByName, String drawnUpByEmail,
                 List<SlipLine> lines) {
    }

    /** One line of the slip. {@code cartonSize} is null when the SKU has no carton size recorded. */
    record SlipLine(String sku, String name, int cartons, int unitsPerCarton, int totalUnits, String cartonSize,
                    String packingRequirements, UUID designSnapshotId, String printFileChecksum) {
    }

    private final DocumentFonts fonts;
    private final SellerLetterhead letterhead;

    ProductionOrderSlipDocument(DocumentFonts fonts, SellerLetterhead letterhead) {
        this.fonts = fonts;
        this.letterhead = letterhead;
    }

    byte[] render(Facts f) {
        ResourceBundle t = DocumentParts.bundle();
        Letterhead head = letterhead.get();
        LocalDate sent = LocalDate.ofInstant(f.sentAt(), DocumentFormats.VIETNAM);
        List<Block> blocks = new ArrayList<>();
        BrandHeader brand = DocumentParts.brandHeader(t, head);
        // The company as the order was sent under: the slip is kept as sent, and names who sent it then.
        blocks.add(present(f.companyName()) ? new BrandHeader(brand.logo(), f.companyName(), brand.lines()) : brand);
        blocks.add(new Split(List.of(Run.bold(t.getString("document.number") + " " + f.code())),
                List.of(Run.italic(DocumentParts.placeDate(t, head, sent)))));
        blocks.add(new Title(t.getString("production-order.pdf.title"), null, null));
        blocks.add(BusinessDocument.codes(f.code()));

        List<Field> facts = new ArrayList<>();
        facts.add(field(t, "production-order.pdf.producer",
                t.getString("production-order.pdf.producerName").formatted(f.producerName(), f.producerCode())));
        if (present(f.producerContact())) {
            facts.add(field(t, "production-order.pdf.contact", f.producerContact()));
        }
        facts.add(field(t, "production-order.pdf.needBy", DocumentFormats.date(f.needByDate())));
        facts.add(field(t, "production-order.pdf.deliverTo", present(f.warehouseAddress())
                ? f.warehouseName() + " - " + f.warehouseAddress() : f.warehouseName()));
        facts.add(field(t, "production-order.pdf.sample",
                t.getString(f.sampleApprovalRequired() ? "production-order.pdf.yes" : "production-order.pdf.no")));
        blocks.add(new Fields(facts, 0));

        blocks.add(new Heading(t.getString("production-order.pdf.lines")));
        blocks.add(goods(t, f.lines()));
        blocks.add(new Heading(t.getString("production-order.pdf.requirements")));
        int no = 0;
        for (String requirement : requirements(t, f)) {
            blocks.add(Paragraph.numbered(++no + ".", Run.of(requirement)).after(2));
        }

        String coordinator = t.getString("production-order.pdf.drawnUpBy").formatted(f.drawnUpByName())
                + (present(f.drawnUpByEmail()) ? " - " + f.drawnUpByEmail() : "");
        blocks.add(Paragraph.of(Run.of(t.getString("production-order.pdf.ask") + " "), Run.bold(coordinator))
                .align(Align.LEFT).after(2));
        blocks.add(Paragraph.italic(t.getString("production-order.pdf.system")
                .formatted(DocumentFormats.moment(f.sentAt()))).align(Align.LEFT).size(-1));
        return BusinessDocument.draw(fonts, DocumentParts.commercial(t, f.code(), head, null),
                t.getString("production-order.pdf.title") + " " + f.code(), blocks);
    }

    /** A fact read at a glance: its value in bold beside its label. */
    private static Field field(ResourceBundle t, String key, String value) {
        return new Field(t.getString(key), List.of(Run.bold(value)));
    }

    private static Table goods(ResourceBundle t, List<SlipLine> lines) {
        List<List<Cell>> rows = new ArrayList<>();
        int number = 0;
        for (SlipLine line : lines) {
            List<String> details = new ArrayList<>();
            details.add(present(line.cartonSize())
                    ? t.getString("production-order.pdf.carton").formatted(line.cartonSize())
                    : t.getString("production-order.pdf.carton.unknown"));
            if (present(line.packingRequirements())) {
                details.add(t.getString("production-order.pdf.packing").formatted(line.packingRequirements()));
            }
            details.add(t.getString("production-order.pdf.printFile").formatted(line.designSnapshotId(),
                    line.printFileChecksum()));
            rows.add(List.of(Cell.of(String.valueOf(++number)), Cell.of(line.sku()),
                    Cell.bold(present(line.name()) ? line.name() : line.sku()).details(details),
                    Cell.of(DocumentFormats.number(line.cartons())),
                    Cell.of(DocumentFormats.number(line.unitsPerCarton())),
                    Cell.of(DocumentFormats.number(line.totalUnits()))));
        }
        return new Table(List.of(new Column(t.getString("document.goods.no"), 28, Align.CENTRE),
                new Column(t.getString("document.goods.code"), 88, Align.LEFT),
                new Column(t.getString("production-order.pdf.column.name"), 224, Align.LEFT),
                new Column(t.getString("production-order.pdf.column.cartons"), 50, Align.RIGHT),
                new Column(t.getString("production-order.pdf.column.perCarton"), 56, Align.RIGHT),
                new Column(t.getString("production-order.pdf.column.units"), 64, Align.RIGHT)), rows);
    }

    private static List<String> requirements(ResourceBundle t, Facts f) {
        List<String> items = new ArrayList<>();
        String needBy = DocumentFormats.date(f.needByDate());
        items.add(present(f.warehouseAddress())
                ? t.getString("production-order.pdf.deliver").formatted(f.warehouseName(), f.warehouseAddress(), needBy)
                : t.getString("production-order.pdf.deliver.noAddress").formatted(f.warehouseName(), needBy));
        items.add(t.getString(f.sampleApprovalRequired() ? "production-order.pdf.sample.required"
                : "production-order.pdf.sample.notRequired"));
        items.add(t.getString("production-order.pdf.checksum"));
        items.add(t.getString("production-order.pdf.cartonRule"));
        if (present(f.note())) {
            items.add(t.getString("production-order.pdf.note").formatted(f.note()));
        }
        items.add(t.getString("production-order.pdf.lot").formatted(f.code()));
        return items;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
