package com.stockflow.order.internal.service;

import com.stockflow.common.label.FormDocument;
import com.stockflow.common.label.FormDocument.Block;
import com.stockflow.common.label.FormDocument.Box;
import com.stockflow.common.label.FormDocument.Bullets;
import com.stockflow.common.label.FormDocument.Cell;
import com.stockflow.common.label.FormDocument.Column;
import com.stockflow.common.label.FormDocument.Fact;
import com.stockflow.common.label.FormDocument.KeyFacts;
import com.stockflow.common.label.FormDocument.Masthead;
import com.stockflow.common.label.FormDocument.Note;
import com.stockflow.common.label.FormDocument.Table;
import com.stockflow.common.label.FormDocument.Title;
import com.stockflow.common.label.LabelFonts;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * <p>The code is printed as a QR and a barcode: it is the lot the goods are received under (03 BR-03), and the receiving
 * scanner reads it from the slip the producer sends back with the goods. Vietnamese labels - the producer is
 * Vietnamese - laid out as a form ({@link FormDocument}). What it shows is given; nothing is looked up here.</p>
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

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(VIETNAM);

    private final LabelFonts fonts;

    ProductionOrderSlipDocument(LabelFonts fonts) {
        this.fonts = fonts;
    }

    byte[] render(Facts f) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Masthead(present(f.companyName()) ? f.companyName() : null));
        blocks.add(new Title(t.getString("production-order.pdf.title"), t.getString("production-order.pdf.number")
                .formatted(f.code(), DAY.format(f.sentAt().atZone(VIETNAM)))));
        blocks.add(new KeyFacts(List.of(
                new Fact(t.getString("production-order.pdf.needBy"), DAY.format(f.needByDate()), null, 0.22f),
                new Fact(t.getString("production-order.pdf.deliverTo"), f.warehouseName(), f.warehouseAddress(),
                        0.56f),
                new Fact(t.getString("production-order.pdf.sample"),
                        t.getString(f.sampleApprovalRequired() ? "production-order.pdf.yes" : "production-order.pdf.no"),
                        null, 0.22f))));
        blocks.add(new Box(t.getString("production-order.pdf.producer"),
                t.getString("production-order.pdf.producerName").formatted(f.producerName(), f.producerCode()),
                present(f.producerContact())
                        ? List.of(t.getString("production-order.pdf.contact").formatted(f.producerContact()))
                        : List.of()));
        blocks.add(goods(t, f.lines()));
        blocks.add(new Bullets(t.getString("production-order.pdf.requirements"), requirements(t, f)));
        String coordinator = t.getString("production-order.pdf.drawnUpBy").formatted(f.drawnUpByName())
                + (present(f.drawnUpByEmail()) ? " · " + f.drawnUpByEmail() : "");
        blocks.add(new Note(t.getString("production-order.pdf.ask"), coordinator,
                t.getString("production-order.pdf.system").formatted(MOMENT.format(f.sentAt()))));
        return FormDocument.draw(fonts, blocks, t.getString("production-order.pdf.title") + " " + f.code(), f.code(),
                t.getString("production-order.pdf.footer").formatted(f.code()),
                t.getString("production-order.pdf.page"));
    }

    private static Table goods(ResourceBundle t, List<SlipLine> lines) {
        List<Column> columns = List.of(new Column(t.getString("production-order.pdf.column.no"), 26, false),
                new Column(t.getString("production-order.pdf.column.sku"), 86, false),
                new Column(t.getString("production-order.pdf.column.name"), 233, false),
                new Column(t.getString("production-order.pdf.column.cartons"), 54, true),
                new Column(t.getString("production-order.pdf.column.perCarton"), 56, true),
                new Column(t.getString("production-order.pdf.column.units"), 60, true));
        List<List<Cell>> rows = new ArrayList<>();
        int number = 0;
        for (SlipLine line : lines) {
            List<String> details = new ArrayList<>();
            details.add(present(line.cartonSize()) ? t.getString("production-order.pdf.carton").formatted(line.cartonSize())
                    : t.getString("production-order.pdf.carton.unknown"));
            if (present(line.packingRequirements())) {
                details.add(t.getString("production-order.pdf.packing").formatted(line.packingRequirements()));
            }
            details.add(t.getString("production-order.pdf.printFile").formatted(line.designSnapshotId(),
                    line.printFileChecksum()));
            rows.add(List.of(new Cell(String.valueOf(++number), false, List.of()),
                    new Cell(line.sku(), false, List.of()),
                    new Cell(present(line.name()) ? line.name() : line.sku(), true, details),
                    new Cell(count(line.cartons()), false, List.of()),
                    new Cell(count(line.unitsPerCarton()), false, List.of()),
                    new Cell(count(line.totalUnits()), false, List.of())));
        }
        return new Table(t.getString("production-order.pdf.lines"), columns, rows);
    }

    private static List<String> requirements(ResourceBundle t, Facts f) {
        List<String> items = new ArrayList<>();
        String needBy = DAY.format(f.needByDate());
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

    /** Whole units grouped with dots, as Vietnamese papers write them: 40.000. */
    private static String count(int value) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        return new DecimalFormat("#,##0", symbols).format(value);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
