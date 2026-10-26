package com.stockflow.warehouse.internal.service;

import com.stockflow.common.label.LabelDrawing;
import com.stockflow.common.label.LabelDrawing.Cell;
import com.stockflow.common.label.LabelFonts;
import com.stockflow.common.label.LabelFormat;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;
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
 * Draws location labels into a PDF (SCRUM-97): the code as text, a QR code, and a Code 128 barcode,
 * all three carrying exactly the location code - the string every scan endpoint accepts.
 *
 * <p>Every bar and every QR module is a filled vector rectangle rather than a raster image, so a
 * label is as sharp on a 203 dpi thermal printer as on a 600 dpi office printer. Code 128 is what a
 * handheld 1D scanner reads; the QR code is for phone and tablet cameras, and stays readable when a
 * very long code forces the barcode's bars below what a scanner resolves.</p>
 *
 * <p>Knows nothing about persistence: it is called after the read-only transaction that chose the
 * labels has ended, so no connection is held while a large PDF is drawn.</p>
 */
@Component
public class LocationLabelPdfRenderer {

    private static final double TEXT_TO_QR_GAP_MM = 2;
    private static final double QR_TO_BARCODE_GAP_MM = 1.5;

    private static final float CODE_MAX_POINTS = 16;
    private static final float CODE_MIN_POINTS = 8;
    private static final float CODE_STEP_POINTS = 0.5f;
    private static final float CONTEXT_POINTS = LabelDrawing.CONTEXT_POINTS;
    private static final float LINE_SPACING = 1.25f;

    private final LabelFonts fonts;

    public LocationLabelPdfRenderer(LabelFonts fonts) {
        this.fonts = fonts;
    }

    /**
     * @return the PDF, one page per {@link LabelFormat#labelsPerPage()} labels, in the sheet's order
     * @throws IllegalArgumentException when the sheet has no label; the service refuses that case first
     */
    public byte[] render(LocationLabelSheet sheet, LabelFormat format, LabelWords words) {
        List<LocationLabel> labels = sheet.labels();
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("A label sheet needs at least one label");
        }
        try (PDDocument document = new PDDocument()) {
            Fonts loaded = new Fonts(
                    PDType0Font.load(document, new ByteArrayInputStream(fonts.bold()), true),
                    PDType0Font.load(document, new ByteArrayInputStream(fonts.regular()), true));
            PDRectangle pageSize = new PDRectangle(LabelDrawing.points(format.pageWidthMm()), LabelDrawing.points(format.pageHeightMm()));
            int perPage = format.labelsPerPage();
            // The same on every label, so cut once rather than 2,000 times.
            String warehouseLine = LabelDrawing.cut(loaded.regular, drawable(sheet.warehouseName()), textWidthMm(format));
            for (int first = 0; first < labels.size(); first += perPage) {
                PDPage page = new PDPage(pageSize);
                document.addPage(page);
                try (PDPageContentStream out = new PDPageContentStream(document, page)) {
                    for (int slot = 0; slot < perPage && first + slot < labels.size(); slot++) {
                        var cell = cellOf(format, slot);
                        drawLabel(out, format, cell, labels.get(first + slot), warehouseLine, words, loaded);
                    }
                }
            }
            var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render location labels", e);
        }
    }

    /**
     * @param warehouseLine the warehouse name, already made drawable and cut to the text column
     */
    private void drawLabel(PDPageContentStream out, LabelFormat format, Cell cell, LocationLabel label, String warehouseLine,
                           LabelWords words, Fonts fonts) throws IOException {
        double qrSide = format.qrSideMm();
        double textWidth = textWidthMm(format);
        double innerLeft = cell.leftMm() + format.paddingMm();
        double innerTop = cell.topMm() + format.paddingMm();
        double innerWidth = cell.widthMm() - 2 * format.paddingMm();
        double innerHeight = cell.heightMm() - 2 * format.paddingMm();

        float codeSize = LabelDrawing.fittingSize(fonts.bold, label.code(), textWidth, CODE_MAX_POINTS,
                CODE_MIN_POINTS, CODE_STEP_POINTS);
        double baseline = innerTop + LabelDrawing.ascentMm(fonts.bold, codeSize);
        // Still too wide at the smallest size: squeeze the glyphs rather than cut the code or let it
        // run over the QR code, whose finder patterns it would break.
        float squeeze = (float) Math.min(1, textWidth / LabelDrawing.widthMm(fonts.bold, label.code(), codeSize));
        out.setHorizontalScaling(squeeze * 100);
        LabelDrawing.text(out, cell, fonts.bold, codeSize, label.code(), innerLeft, baseline);
        out.setHorizontalScaling(100);
        String contextLine = LabelDrawing.cut(fonts.regular, drawable(context(label, words)), textWidth);
        for (String line : List.of(warehouseLine, contextLine)) {
            baseline += LabelDrawing.millimetres(CONTEXT_POINTS * LINE_SPACING);
            LabelDrawing.text(out, cell, fonts.regular, CONTEXT_POINTS, line, innerLeft, baseline);
        }

        LabelDrawing.qr(out, cell, label.code(), innerLeft + innerWidth - qrSide, innerTop, qrSide);

        double barTop = innerTop + qrSide + QR_TO_BARCODE_GAP_MM;
        LabelDrawing.barcode(out, cell, label.code(), innerLeft, barTop, innerWidth,
                innerTop + innerHeight - barTop);
    }

    /** Beside the QR code: the label's inner width minus the QR code and the gap before it. */
    private static double textWidthMm(LabelFormat format) {
        return format.labelWidthMm() - 2 * format.paddingMm() - format.qrSideMm() - TEXT_TO_QR_GAP_MM;
    }

    /**
     * Names are free text: a tab pasted from a spreadsheet or an emoji is a legal warehouse or area
     * name, and PDFBox throws on any character the font has no glyph for - which would make every
     * print of that warehouse fail. Control characters become spaces, anything else undrawable a
     * {@code ?}, so the label still prints and still says where it is.
     */
    private String drawable(String text) {
        var out = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)) {
                out.append(' ');
            } else if (fonts.canDraw(codePoint)) {
                out.appendCodePoint(codePoint);
            } else {
                out.append('?');
            }
        });
        return out.toString();
    }

    private static String context(LocationLabel label, LabelWords words) {
        if (label.kind() == StorageLocationKind.AREA) {
            return "%s · %s".formatted(label.areaName(), label.storageClass());
        }
        return "%s %s · %s %d · %s %s · %s".formatted(words.shelf(), label.shelfCode(), words.level(),
                label.levelIndex(), words.bin(), label.binCode(), label.storageClass());
    }

    private static Cell cellOf(LabelFormat format, int slot) {
        return new Cell((slot % format.columns()) * format.labelWidthMm(),
                format.topMarginMm() + (slot / format.columns()) * format.labelHeightMm(),
                format.labelWidthMm(), format.labelHeightMm(), format.pageHeightMm());
    }

    private record Fonts(PDFont bold, PDFont regular) {
    }
}
