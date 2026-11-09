package com.stockflow.order.internal.service;

import com.stockflow.common.label.LabelFonts;
import com.stockflow.order.internal.service.PrintedDocument.Line;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
 * Draws a production order slip (4A-3, kltn-docs 17 §4.3 steps 6-7): the PDF a producer works from. Its header is 17's
 * - code, sales order, delivery batch, customer, producer, receiving warehouse, need-by date, who drew it up and who
 * approved it - then the lines (the customer's SKU, cartons times units per carton, the carton's size, packing and
 * labelling, the design and the checksum of its print files), the options (price, note) and whether a print sample is
 * approved first (BR-24). The code is printed as a barcode and a QR code too: it is the lot the goods are received under
 * (03 BR-03), and the receiving scanner reads it from the slip the producer sends back with the goods.
 *
 * <p>The labels are the Vietnamese bundle's - the producer is Vietnamese - laid out as every document of the module is
 * ({@link PrintedDocument}). What it shows is given; nothing is looked up here.</p>
 */
@Component
class ProductionOrderSlipDocument {

    /** Everything the slip says. Text a person typed (note, packing requirements) is printed as written. */
    record Facts(String code, Instant approvedAt, String producerCode, String producerName, String producerContact,
                 String salesOrderNumber, String customerName, int batchSeq, LocalDate windowStart,
                 LocalDate windowEnd, String warehouseName, String warehouseAddress, LocalDate needByDate,
                 boolean sampleApprovalRequired, BigDecimal unitPrice, String currency, String note,
                 String drawnUpBy, String approvedBy, List<SlipLine> lines) {
    }

    /** One line of the slip. {@code cartonSize} is null when the SKU has no carton size recorded. */
    record SlipLine(String sku, String name, int cartons, int unitsPerCarton, int totalUnits, String cartonSize,
                    String packingRequirements, UUID designSnapshotId, String printFileChecksum) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final LabelFonts fonts;

    ProductionOrderSlipDocument(LabelFonts fonts) {
        this.fonts = fonts;
    }

    byte[] render(Facts f) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        List<Line> lines = new ArrayList<>();
        lines.add(Line.title(t.getString("production-order.pdf.title")));
        lines.add(Line.bold(t.getString("production-order.pdf.code").formatted(f.code())));
        lines.add(Line.text(t.getString("production-order.pdf.approvedAt").formatted(MOMENT.format(f.approvedAt()))));
        lines.add(Line.text(t.getString("production-order.pdf.salesOrder").formatted(f.salesOrderNumber(),
                orDash(f.customerName()))));
        lines.add(Line.text(t.getString("production-order.pdf.batch").formatted(f.batchSeq(),
                DAY.format(f.windowStart()), DAY.format(f.windowEnd()))));

        lines.add(Line.heading(t.getString("production-order.pdf.producer")));
        lines.add(Line.bold(f.producerName() + " (" + f.producerCode() + ")"));
        if (f.producerContact() != null) {
            lines.add(Line.text(t.getString("production-order.pdf.contact").formatted(f.producerContact())));
        }

        lines.add(Line.heading(t.getString("production-order.pdf.delivery")));
        lines.add(Line.text(t.getString("production-order.pdf.warehouse").formatted(f.warehouseName(),
                orDash(f.warehouseAddress()))));
        lines.add(Line.bold(t.getString("production-order.pdf.needBy").formatted(DAY.format(f.needByDate()))));
        lines.add(Line.text(t.getString(f.sampleApprovalRequired() ? "production-order.pdf.sample.required"
                : "production-order.pdf.sample.notRequired")));

        lines.add(Line.heading(t.getString("production-order.pdf.lines")));
        int cartons = 0;
        int units = 0;
        int number = 0;
        for (SlipLine line : f.lines()) {
            cartons += line.cartons();
            units += line.totalUnits();
            lines.add(Line.bold(t.getString("production-order.pdf.line").formatted(++number, line.sku(),
                    orDash(line.name()))));
            lines.add(Line.text(t.getString("production-order.pdf.lineQuantity").formatted(line.cartons(),
                    line.unitsPerCarton(), line.totalUnits(), line.cartonSize() == null
                            ? t.getString("production-order.pdf.unknown") : line.cartonSize())));
            if (line.packingRequirements() != null) {
                lines.add(Line.text(t.getString("production-order.pdf.packing").formatted(line.packingRequirements())));
            }
            lines.add(Line.text(t.getString("production-order.pdf.design").formatted(line.designSnapshotId(),
                    line.printFileChecksum())));
        }
        lines.add(Line.bold(t.getString("production-order.pdf.totals").formatted(cartons, units)));

        if (f.unitPrice() != null || f.note() != null) {
            lines.add(Line.heading(t.getString("production-order.pdf.options")));
            if (f.unitPrice() != null) {
                lines.add(Line.text(t.getString("production-order.pdf.unitPrice").formatted(
                        f.unitPrice().stripTrailingZeros().toPlainString(), f.currency())));
            }
            if (f.note() != null) {
                lines.add(Line.text(t.getString("production-order.pdf.note").formatted(f.note())));
            }
        }

        lines.add(Line.heading(""));
        lines.add(Line.text(t.getString("production-order.pdf.drawnUpBy").formatted(orDash(f.drawnUpBy()))));
        lines.add(Line.text(t.getString("production-order.pdf.approvedBy").formatted(orDash(f.approvedBy()))));
        lines.add(Line.text(t.getString("production-order.pdf.lot")));
        return PrintedDocument.draw(fonts, lines, t.getString("production-order.pdf.title") + " " + f.code(),
                f.code());
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
