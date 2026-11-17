package com.stockflow.common.label;

import com.stockflow.common.label.LabelDrawing.Cell;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The shipping label of one outbound carton (team plan 4C-6; kltn-docs 08 §4.3 step 6), stuck next to the carton's LPN
 * label: one 100 x 150 mm page per carton, the common 4 x 6 inch thermal label, which an office printer also prints on
 * A6. It says what the docs list: the order, the LPN as a Code 128 barcode and a QR code - the code loading and the
 * handover scan, since the carton's code from here on is its LPN - the SKU and the units it holds, its weight and
 * volumetric weight and size, the recipient, their phone and delivery address, the warehouse that ships it and takes it
 * back, and "do not rotate" when the carton may not be turned. The label code and version say which print this is: a
 * reprint goes up a version and voids the one before (08 BR-08).
 *
 * <p>Drawn with the primitives of {@link CodeLabelSheet} ({@link LabelDrawing}, {@link LabelFonts}): the same vector
 * barcodes and the same embedded fonts that draw Vietnamese names and addresses. Captions are English, as everything
 * the code writes; names and addresses are printed as the order has them. Knows nothing about persistence: callers
 * render after the read-only transaction that chose the cartons has ended.</p>
 */
public final class ShippingLabelSheet {

    private static final double PAGE_WIDTH_MM = 100;
    private static final double PAGE_HEIGHT_MM = 150;
    private static final double PADDING_MM = 4;
    private static final double QR_SIDE_MM = 24;
    private static final double BARCODE_HEIGHT_MM = 20;
    private static final double RULE_MM = 0.3;
    private static final float LINE_SPACING = 1.3f;

    /**
     * One carton's label. {@code lpn} is [A-Z0-9-], what both symbols carry; every other text is free and made drawable
     * here. Measures are in cm and kg as the carton row stores them.
     *
     * @param labelVersion 1 for the first print, up by one per reprint
     */
    public record ShippingLabel(String orderNumber, String lpn, String labelCode, int labelVersion, String sku,
                                int quantity, BigDecimal weightKg, BigDecimal dimWeightKg, BigDecimal lengthCm,
                                BigDecimal widthCm, BigDecimal heightCm, boolean rotatable, String recipientName,
                                String recipientPhone, String deliveryAddress, String senderName,
                                String senderAddress) {
    }

    private ShippingLabelSheet() {
    }

    /**
     * @return the PDF, one page per label, in the given order
     * @throws IllegalArgumentException when there is no label; every caller's service refuses that case first
     */
    public static byte[] render(LabelFonts fonts, List<ShippingLabel> labels) {
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("A label sheet needs at least one label");
        }
        try (PDDocument document = new PDDocument()) {
            PDFont bold = PDType0Font.load(document, new ByteArrayInputStream(fonts.bold()), true);
            PDFont regular = PDType0Font.load(document, new ByteArrayInputStream(fonts.regular()), true);
            PDRectangle size = new PDRectangle(LabelDrawing.points(PAGE_WIDTH_MM), LabelDrawing.points(PAGE_HEIGHT_MM));
            for (ShippingLabel label : labels) {
                PDPage page = new PDPage(size);
                document.addPage(page);
                try (PDPageContentStream out = new PDPageContentStream(document, page)) {
                    new Drawing(out, fonts, bold, regular).draw(label);
                }
            }
            var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render shipping labels", e);
        }
    }

    /** One page being drawn, top to bottom; {@code y} is the next free position from the top, in mm. */
    private static final class Drawing {

        private final PDPageContentStream out;
        private final LabelFonts fonts;
        private final PDFont bold;
        private final PDFont regular;
        private final Cell cell = new Cell(0, 0, PAGE_WIDTH_MM, PAGE_HEIGHT_MM, PAGE_HEIGHT_MM);
        private final double left = PADDING_MM;
        private final double width = PAGE_WIDTH_MM - 2 * PADDING_MM;
        private double y = PADDING_MM;

        Drawing(PDPageContentStream out, LabelFonts fonts, PDFont bold, PDFont regular) {
            this.out = out;
            this.fonts = fonts;
            this.bold = bold;
            this.regular = regular;
        }

        void draw(ShippingLabel label) throws IOException {
            // The order and which print this is, beside the QR code of the LPN.
            double textWidth = width - QR_SIDE_MM - 2;
            LabelDrawing.qr(out, cell, label.lpn(), left + width - QR_SIDE_MM, y, QR_SIDE_MM);
            line(regular, 7, "ORDER", textWidth);
            fitted(bold, 15, 9, label.orderNumber(), textWidth);
            line(regular, 7, "%s  ·  label v%d".formatted(label.labelCode(), label.labelVersion()), textWidth);
            y = Math.max(y, PADDING_MM + QR_SIDE_MM) + 1.5;
            rule();

            line(bold, 8, "SHIP TO", width);
            line(bold, 11, label.recipientName(), width);
            line(regular, 9, label.recipientPhone(), width);
            wrapped(regular, 9, label.deliveryAddress(), width, 4);
            rule();

            line(bold, 7, "FROM / RETURN TO", width);
            line(regular, 8, label.senderName(), width);
            wrapped(regular, 8, label.senderAddress(), width, 2);
            rule();

            line(bold, 11, "SKU  " + nullToEmpty(label.sku()), width);
            line(regular, 9, "QTY  %d".formatted(label.quantity()), width);
            line(regular, 9, "WEIGHT  %s kg    DIM. WEIGHT  %s kg".formatted(plain(label.weightKg()),
                    plain(label.dimWeightKg())), width);
            line(regular, 9, "SIZE  %s x %s x %s cm".formatted(plain(label.lengthCm()), plain(label.widthCm()),
                    plain(label.heightCm())), width);
            if (!label.rotatable()) {
                y += 1.5;
                box("DO NOT ROTATE");
            }

            // The LPN across the bottom: what every later scan reads.
            double barTop = PAGE_HEIGHT_MM - PADDING_MM - BARCODE_HEIGHT_MM - 6;
            LabelDrawing.barcode(out, cell, label.lpn(), left, barTop, width, BARCODE_HEIGHT_MM);
            float size = LabelDrawing.fittingSize(bold, label.lpn(), width, 12, 7, 0.5f);
            double textWidth2 = LabelDrawing.widthMm(bold, label.lpn(), size);
            LabelDrawing.text(out, cell, bold, size, label.lpn(), left + (width - textWidth2) / 2,
                    PAGE_HEIGHT_MM - PADDING_MM - 1);
        }

        private void line(PDFont font, float size, String text, double maxWidth) throws IOException {
            String drawn = LabelDrawing.cut(font, fonts.drawable(nullToEmpty(text)), maxWidth * LabelDrawing.CONTEXT_POINTS
                    / size);
            write(font, size, drawn);
        }

        private void fitted(PDFont font, float max, float min, String text, double maxWidth) throws IOException {
            String drawn = fonts.drawable(nullToEmpty(text));
            float size = LabelDrawing.fittingSize(font, drawn, maxWidth, max, min, 0.5f);
            write(font, size, drawn);
        }

        /** Greedy word wrap into at most {@code lines} lines; what does not fit is cut with an ellipsis. */
        private void wrapped(PDFont font, float size, String text, double maxWidth, int lines) throws IOException {
            String drawable = fonts.drawable(nullToEmpty(text)).trim();
            if (drawable.isEmpty()) {
                return;
            }
            List<String> rows = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : drawable.split("\\s+")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (current.isEmpty() || LabelDrawing.widthMm(font, candidate, size) <= maxWidth) {
                    current.setLength(0);
                    current.append(candidate);
                } else {
                    rows.add(current.toString());
                    current.setLength(0);
                    current.append(word);
                }
            }
            if (!current.isEmpty()) {
                rows.add(current.toString());
            }
            if (rows.size() > lines) {
                // The rest goes on the last line, cut with an ellipsis.
                String last = String.join(" ", rows.subList(lines - 1, rows.size()));
                rows = new ArrayList<>(rows.subList(0, lines - 1));
                rows.add(last);
            }
            for (String row : rows) {
                write(font, size, LabelDrawing.cut(font, row, maxWidth * LabelDrawing.CONTEXT_POINTS / size));
            }
        }

        private void write(PDFont font, float size, String text) throws IOException {
            double baseline = y + LabelDrawing.ascentMm(font, size);
            LabelDrawing.text(out, cell, font, size, text, left, baseline);
            y += LabelDrawing.millimetres(size * LINE_SPACING);
        }

        private void rule() throws IOException {
            y += 1;
            cell.rect(out, left, y, width, RULE_MM);
            out.fill();
            y += 2;
        }

        private void box(String text) throws IOException {
            float size = 12;
            double textWidth = LabelDrawing.widthMm(bold, text, size);
            double height = LabelDrawing.millimetres(size * LINE_SPACING) + 2;
            double boxWidth = textWidth + 6;
            cell.rect(out, left, y, boxWidth, RULE_MM * 2);
            cell.rect(out, left, y + height, boxWidth, RULE_MM * 2);
            cell.rect(out, left, y, RULE_MM * 2, height);
            cell.rect(out, left + boxWidth, y, RULE_MM * 2, height + RULE_MM * 2);
            out.fill();
            LabelDrawing.text(out, cell, bold, size, text, left + 3, y + 1 + LabelDrawing.ascentMm(bold, size));
            y += height + 2;
        }

        private static String plain(BigDecimal value) {
            return value == null ? "-" : value.stripTrailingZeros().toPlainString();
        }

        private static String nullToEmpty(String text) {
            return text == null ? "" : text;
        }
    }
}
