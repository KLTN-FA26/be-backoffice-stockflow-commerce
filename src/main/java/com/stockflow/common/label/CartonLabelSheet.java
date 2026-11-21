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
import java.util.List;

/**
 * The carton (LPN) label (SCRUM-473), on the same label stock as the location label ({@link LabelFormat}): a header of
 * the LPN as large bold text, the count as a black badge and the QR code at the right; a rule; then captioned fields
 * across the full width - SKU, the item name on up to two lines, the lot; and the Code 128 barcode centred at the foot,
 * taller when the name needs only one line. Both symbols carry exactly the LPN, the string every carton scan accepts:
 * the rest is for the eye, a picture of the carton when it was printed, and the system stays what is true.
 *
 * <p>Why the fields: printed goods are made to each customer's design, so two SKUs of the same cup look alike on a
 * shelf. The SKU names the customer ("product - customer - number"), the name says what is in the box, and the lot is
 * the production order the carton came from, so a carton is told apart, traced or put back without a scanner. No
 * sales order: a carton belongs to a customer's SKU, not to an order until it is allocated, and an order printed on it
 * would be wrong once the carton went to another.</p>
 *
 * <p>Black only, no greys: a thermal head dithers grey into noise. Captions come from the message bundles
 * ({@code label.carton.*}), printed "<vi> / <en>" (RTM-LABELLANG), so no Vietnamese lives in this class. Knows nothing
 * about persistence: callers render after the read-only transaction that chose the cartons has ended.</p>
 */
public final class CartonLabelSheet {

    private static final double HEADER_MM = 11;
    private static final double QR_BLEED_MM = 0.6;
    private static final double HEADER_TEXT_TO_QR_GAP_MM = 1.5;
    private static final double BADGE_HEIGHT_MM = 4;
    private static final double BADGE_PADDING_MM = 1.5;
    private static final double BADGE_RADIUS_MM = 0.8;
    private static final double RULE_GAP_MM = 0.9;
    private static final double RULE_WIDTH_MM = 0.21;
    private static final double FIRST_FIELD_BASELINE_MM = 2.9;
    private static final double FIELD_ROW_MM = 3.05;
    private static final double NAME_LINE_MM = 2.6;
    private static final double CAPTION_TO_VALUE_GAP_MM = 1.5;
    private static final double FIELDS_TO_BARCODE_GAP_MM = 1.6;
    /** The barcode's quiet zones may run into the padding: they are white anyway, and a wider symbol scans better. */
    private static final double BARCODE_BLEED_MM = 1.2;

    private static final float LPN_MAX_POINTS = 15;
    private static final float LPN_MIN_POINTS = 8;
    private static final float BADGE_POINTS = 7.5f;
    private static final float CAPTION_POINTS = 4.6f;
    private static final float VALUE_POINTS = 7;
    private static final float NAME_POINTS = 6.5f;

    static final String SKU = LabelCaptions.bilingual("label.carton.sku");
    static final String ITEM = LabelCaptions.bilingual("label.carton.item");
    static final String LOT = LabelCaptions.bilingual("label.carton.lot");
    static final String NOT_COUNTED = LabelCaptions.bilingual("label.carton.not-counted");
    /** The Vietnamese half alone, for when the bilingual note does not fit beside a long count. */
    static final String NOT_COUNTED_VI = LabelCaptions.vietnamese("label.carton.not-counted");

    /**
     * One carton's label. {@code lpn} is [A-Z0-9-], what both symbols carry; the other texts are free and made
     * drawable here.
     *
     * @param name        the item's name; null or blank leaves the field out
     * @param lot         the lot the carton is in; null or blank leaves the field out
     * @param receivedQty what was counted into the carton, null while it is still to be counted
     * @param unit        the packing unit's code
     */
    public record CartonLabel(String lpn, String sku, String name, String lot, Integer receivedQty, int nominalQty,
                              String unit) {
    }

    private CartonLabelSheet() {
    }

    /**
     * @return the PDF, one page per {@link LabelFormat#labelsPerPage()} labels, in the given order
     * @throws IllegalArgumentException when there is no label; the caller's service refuses that case first
     */
    public static byte[] render(LabelFonts fonts, List<CartonLabel> labels, LabelFormat format) {
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("A label sheet needs at least one label");
        }
        try (PDDocument document = new PDDocument()) {
            PDFont bold = PDType0Font.load(document, new ByteArrayInputStream(fonts.bold()), true);
            PDFont regular = PDType0Font.load(document, new ByteArrayInputStream(fonts.regular()), true);
            // The caption column is as wide as its widest caption, measured once.
            double captionColumn = Math.max(LabelDrawing.widthMm(bold, SKU, CAPTION_POINTS),
                    Math.max(LabelDrawing.widthMm(bold, ITEM, CAPTION_POINTS),
                            LabelDrawing.widthMm(bold, LOT, CAPTION_POINTS))) + CAPTION_TO_VALUE_GAP_MM;
            Drawing drawing = new Drawing(fonts, bold, regular, format, captionColumn);
            PDRectangle pageSize = new PDRectangle(LabelDrawing.points(format.pageWidthMm()),
                    LabelDrawing.points(format.pageHeightMm()));
            int perPage = format.labelsPerPage();
            for (int first = 0; first < labels.size(); first += perPage) {
                PDPage page = new PDPage(pageSize);
                document.addPage(page);
                try (PDPageContentStream out = new PDPageContentStream(document, page)) {
                    for (int slot = 0; slot < perPage && first + slot < labels.size(); slot++) {
                        drawing.label(out, Cell.of(format, slot), labels.get(first + slot));
                    }
                }
            }
            var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render carton labels", e);
        }
    }

    private record Drawing(LabelFonts fonts, PDFont bold, PDFont regular, LabelFormat format, double captionColumn) {

        void label(PDPageContentStream out, Cell cell, CartonLabel label) throws IOException {
            double pad = format.paddingMm();
            double left = cell.leftMm() + pad;
            double top = cell.topMm() + pad;
            double width = cell.widthMm() - 2 * pad;
            double bottom = top + cell.heightMm() - 2 * pad;

            // Header. The QR keeps its quiet zone inside its side, so it may bleed a little past the header.
            double headerText = width - HEADER_MM - HEADER_TEXT_TO_QR_GAP_MM;
            LabelDrawing.qr(out, cell, label.lpn(), left + width - HEADER_MM, top - QR_BLEED_MM,
                    HEADER_MM + 2 * QR_BLEED_MM);
            float lpnSize = LabelDrawing.fittingSize(bold, label.lpn(), headerText, LPN_MAX_POINTS, LPN_MIN_POINTS,
                    0.25f);
            LabelDrawing.squeezedText(out, cell, bold, lpnSize, label.lpn(), left,
                    top + LabelDrawing.ascentMm(bold, lpnSize) * 0.85, headerText);
            badge(out, cell, label, left, top + HEADER_MM - BADGE_HEIGHT_MM, headerText);

            double rule = top + HEADER_MM + RULE_GAP_MM;
            cell.rect(out, left, rule - RULE_WIDTH_MM / 2, width, RULE_WIDTH_MM);
            out.fill();

            // Fields, each a caption and a value across the rest of the label.
            double valueLeft = left + captionColumn;
            double valueWidth = width - captionColumn;
            double baseline = rule + FIRST_FIELD_BASELINE_MM;
            baseline = field(out, cell, SKU, List.of(cut(bold, label.sku(), valueWidth, VALUE_POINTS)), bold,
                    VALUE_POINTS, left, valueLeft, baseline);
            List<String> name = label.name() == null ? List.of()
                    : LabelDrawing.wrap(regular, fonts.drawable(label.name()), valueWidth, NAME_POINTS, 2);
            baseline = field(out, cell, ITEM, name, regular, NAME_POINTS, left, valueLeft, baseline);
            List<String> lot = label.lot() == null || label.lot().isBlank() ? List.of()
                    : List.of(cut(bold, label.lot(), valueWidth, VALUE_POINTS));
            baseline = field(out, cell, LOT, lot, bold, VALUE_POINTS, left, valueLeft, baseline);

            // The barcode takes whatever height is left, centred in the width it has.
            double barTop = baseline - FIELD_ROW_MM + FIELDS_TO_BARCODE_GAP_MM;
            LabelDrawing.centredBarcode(out, cell, label.lpn(), left - BARCODE_BLEED_MM, barTop,
                    width + 2 * BARCODE_BLEED_MM, bottom - barTop);
        }

        /** The count as a black badge, what a picker checks first; a carton not counted yet says so beside it. */
        private void badge(PDPageContentStream out, Cell cell, CartonLabel label, double left, double badgeTop,
                           double maxWidth) throws IOException {
            String count = "%s / %d %s".formatted(label.receivedQty() == null ? "-" : label.receivedQty().toString(),
                    label.nominalQty(), label.unit());
            count = cut(bold, count, maxWidth - 2 * BADGE_PADDING_MM, BADGE_POINTS); // cut makes it drawable
            double badgeWidth = LabelDrawing.widthMm(bold, count, BADGE_POINTS) + 2 * BADGE_PADDING_MM;
            roundedRect(out, cell, left, badgeTop, badgeWidth, BADGE_HEIGHT_MM, BADGE_RADIUS_MM);
            out.fill();
            double baseline = badgeTop + (BADGE_HEIGHT_MM + LabelDrawing.ascentMm(bold, BADGE_POINTS) * 0.72) / 2;
            out.setNonStrokingColor(1f);
            LabelDrawing.text(out, cell, bold, BADGE_POINTS, count, left + BADGE_PADDING_MM, baseline);
            out.setNonStrokingColor(0f);
            if (label.receivedQty() == null) {
                double captionLeft = left + badgeWidth + BADGE_PADDING_MM;
                double room = left + maxWidth - captionLeft;
                // Bilingual when it fits; else the Vietnamese the warehouse reads, cut only if even that is too long.
                String note = LabelDrawing.widthMm(bold, NOT_COUNTED, CAPTION_POINTS) <= room ? NOT_COUNTED
                        : LabelDrawing.cut(bold, NOT_COUNTED_VI, room, CAPTION_POINTS);
                // cut keeps at least a letter and the ellipsis; with no room even for that, the dash in the badge
                // says it alone rather than the note running into the QR code.
                if (LabelDrawing.widthMm(bold, note, CAPTION_POINTS) <= room) {
                    LabelDrawing.text(out, cell, bold, CAPTION_POINTS, note, captionLeft, baseline - 0.3);
                }
            }
        }

        /** Draws one field and answers the next field's baseline; a field with no line is left out. */
        private double field(PDPageContentStream out, Cell cell, String caption, List<String> lines, PDFont font,
                             float size, double left, double valueLeft, double baseline) throws IOException {
            if (lines.isEmpty()) {
                return baseline;
            }
            LabelDrawing.text(out, cell, bold, CAPTION_POINTS, caption, left, baseline - 0.25);
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    baseline += NAME_LINE_MM;
                }
                LabelDrawing.text(out, cell, font, size, lines.get(i), valueLeft, baseline);
            }
            return baseline + FIELD_ROW_MM;
        }

        private String cut(PDFont font, String text, double widthMm, float size) throws IOException {
            return LabelDrawing.cut(font, fonts.drawable(text), widthMm, size);
        }

        private static void roundedRect(PDPageContentStream out, Cell cell, double leftMm, double topMm,
                                        double widthMm, double heightMm, double radiusMm) throws IOException {
            float k = 0.5523f; // a quarter circle as one cubic Bézier
            float l = LabelDrawing.points(leftMm);
            float r = LabelDrawing.points(leftMm + widthMm);
            float t = cell.y(topMm);
            float b = cell.y(topMm + heightMm);
            float c = LabelDrawing.points(radiusMm);
            out.moveTo(l + c, t);
            out.lineTo(r - c, t);
            out.curveTo(r - c + k * c, t, r, t - c + k * c, r, t - c);
            out.lineTo(r, b + c);
            out.curveTo(r, b + c - k * c, r - c + k * c, b, r - c, b);
            out.lineTo(l + c, b);
            out.curveTo(l + c - k * c, b, l, b + c - k * c, l, b + c);
            out.lineTo(l, t - c);
            out.curveTo(l, t - c + k * c, l + c - k * c, t, l + c, t);
            out.closePath();
        }
    }
}
