package com.stockflow.inventory.internal.service;

import com.stockflow.common.label.LabelDrawing;
import com.stockflow.common.label.LabelDrawing.Cell;
import com.stockflow.common.label.LabelFonts;
import com.stockflow.common.label.LabelFormat;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Draws carton labels into a PDF (SCRUM-473): the LPN as large text, a QR code and a Code 128 barcode, all three
 * carrying exactly the LPN - the string every carton scan accepts - and beneath the text the SKU, the item name and
 * {@code received / nominal UNIT}.
 *
 * <p>The layout is the location label's, so one printer setup serves both; the drawing is the shared
 * {@link LabelDrawing}. Knows nothing about persistence: it runs after the read-only transaction that chose the
 * cartons has ended.</p>
 */
@Component
public class StockPackageLabelPdfRenderer {

    private static final double QR_TO_BARCODE_GAP_MM = 1.5;
    private static final double TEXT_TO_QR_GAP_MM = 2;

    private static final float LPN_MAX_POINTS = 16;
    private static final float LPN_MIN_POINTS = 8;
    private static final float LPN_STEP_POINTS = 0.5f;
    private static final float CONTEXT_POINTS = LabelDrawing.CONTEXT_POINTS;
    private static final float LINE_SPACING = 1.25f;

    private final LabelFonts fonts;

    public StockPackageLabelPdfRenderer(LabelFonts fonts) {
        this.fonts = fonts;
    }

    /**
     * @return the PDF, one page per {@link LabelFormat#labelsPerPage()} labels, in the given order
     * @throws IllegalArgumentException when there is no label; the service refuses that case first
     */
    public byte[] render(List<StockPackageLabel> labels, LabelFormat format) {
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("A label sheet needs at least one label");
        }
        try (PDDocument document = new PDDocument()) {
            Fonts loaded = new Fonts(
                    PDType0Font.load(document, new ByteArrayInputStream(fonts.bold()), true),
                    PDType0Font.load(document, new ByteArrayInputStream(fonts.regular()), true));
            PDRectangle pageSize = new PDRectangle(LabelDrawing.points(format.pageWidthMm()),
                    LabelDrawing.points(format.pageHeightMm()));
            int perPage = format.labelsPerPage();
            for (int first = 0; first < labels.size(); first += perPage) {
                PDPage page = new PDPage(pageSize);
                document.addPage(page);
                try (PDPageContentStream out = new PDPageContentStream(document, page)) {
                    for (int slot = 0; slot < perPage && first + slot < labels.size(); slot++) {
                        drawLabel(out, format, cellOf(format, slot), labels.get(first + slot), loaded);
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

    private void drawLabel(PDPageContentStream out, LabelFormat format, Cell cell, StockPackageLabel label,
                           Fonts loaded) throws IOException {
        double qrSide = format.qrSideMm();
        double textWidth = format.labelWidthMm() - 2 * format.paddingMm() - qrSide - TEXT_TO_QR_GAP_MM;
        double innerLeft = cell.leftMm() + format.paddingMm();
        double innerTop = cell.topMm() + format.paddingMm();
        double innerWidth = cell.widthMm() - 2 * format.paddingMm();
        double innerHeight = cell.heightMm() - 2 * format.paddingMm();

        float lpnSize = LabelDrawing.fittingSize(loaded.bold, label.lpn(), textWidth, LPN_MAX_POINTS,
                LPN_MIN_POINTS, LPN_STEP_POINTS);
        double baseline = innerTop + LabelDrawing.ascentMm(loaded.bold, lpnSize);
        // Still too wide at the smallest size: squeeze the glyphs rather than cut the LPN or let it run over the
        // QR code, whose finder patterns it would break.
        float squeeze = (float) Math.min(1, textWidth / LabelDrawing.widthMm(loaded.bold, label.lpn(), lpnSize));
        out.setHorizontalScaling(squeeze * 100);
        LabelDrawing.text(out, cell, loaded.bold, lpnSize, label.lpn(), innerLeft, baseline);
        out.setHorizontalScaling(100);
        for (String line : List.of(label.sku(), label.itemName(), quantityLine(label))) {
            baseline += LabelDrawing.millimetres(CONTEXT_POINTS * LINE_SPACING);
            LabelDrawing.text(out, cell, loaded.regular, CONTEXT_POINTS,
                    LabelDrawing.cut(loaded.regular, fonts.drawable(line), textWidth), innerLeft, baseline);
        }

        LabelDrawing.qr(out, cell, label.lpn(), innerLeft + innerWidth - qrSide, innerTop, qrSide);

        double barTop = innerTop + qrSide + QR_TO_BARCODE_GAP_MM;
        LabelDrawing.barcode(out, cell, label.lpn(), innerLeft, barTop, innerWidth,
                innerTop + innerHeight - barTop);
    }

    /** {@code 980 / 1000 CARTON}; a carton not yet counted shows a dash for what it holds. */
    private static String quantityLine(StockPackageLabel label) {
        return "%s / %d %s".formatted(label.receivedQty() == null ? "-" : label.receivedQty().toString(),
                label.nominalQty(), label.unit());
    }

    private static Cell cellOf(LabelFormat format, int slot) {
        return new Cell((slot % format.columns()) * format.labelWidthMm(),
                format.topMarginMm() + (slot / format.columns()) * format.labelHeightMm(),
                format.labelWidthMm(), format.labelHeightMm(), format.pageHeightMm());
    }

    private record Fonts(PDFont bold, PDFont regular) {
    }
}
