package com.stockflow.warehouse.internal.service;

import com.google.zxing.WriterException;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
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

    private static final float POINTS_PER_MM = 72f / 25.4f;

    /** 3 and 2 dots of a 203 dpi thermal head, then the narrowest bar common scanners still resolve. */
    private static final double[] BAR_MODULE_WIDTHS_MM = {0.375, 0.25, 0.19};
    private static final int BARCODE_QUIET_MODULES = 10;
    private static final int QR_QUIET_MODULES = 4;
    private static final double TEXT_TO_QR_GAP_MM = 2;
    private static final double QR_TO_BARCODE_GAP_MM = 1.5;

    private static final float CODE_MAX_POINTS = 16;
    private static final float CODE_MIN_POINTS = 8;
    private static final float CODE_STEP_POINTS = 0.5f;
    private static final float CONTEXT_POINTS = 6.5f;
    private static final float LINE_SPACING = 1.25f;
    private static final String ELLIPSIS = "…";

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
            PDRectangle pageSize = new PDRectangle(points(format.pageWidthMm()), points(format.pageHeightMm()));
            int perPage = format.labelsPerPage();
            // The same on every label, so cut once rather than 2,000 times.
            String warehouseLine = cut(loaded.regular, drawable(sheet.warehouseName()), textWidthMm(format));
            for (int first = 0; first < labels.size(); first += perPage) {
                PDPage page = new PDPage(pageSize);
                document.addPage(page);
                try (PDPageContentStream out = new PDPageContentStream(document, page)) {
                    for (int slot = 0; slot < perPage && first + slot < labels.size(); slot++) {
                        var cell = new Cell(format, slot);
                        drawLabel(out, cell, labels.get(first + slot), warehouseLine, words, loaded);
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
    private void drawLabel(PDPageContentStream out, Cell cell, LocationLabel label, String warehouseLine,
                           LabelWords words, Fonts fonts) throws IOException {
        double qrSide = cell.format.qrSideMm();
        double textWidth = textWidthMm(cell.format);

        float codeSize = fittingSize(fonts.bold, label.code(), textWidth);
        double baseline = cell.innerTop() + ascentMm(fonts.bold, codeSize);
        // Still too wide at the smallest size: squeeze the glyphs rather than cut the code or let it
        // run over the QR code, whose finder patterns it would break.
        float squeeze = (float) Math.min(1, textWidth / widthMm(fonts.bold, label.code(), codeSize));
        out.setHorizontalScaling(squeeze * 100);
        text(out, cell, fonts.bold, codeSize, label.code(), cell.innerLeft(), baseline);
        out.setHorizontalScaling(100);
        String contextLine = cut(fonts.regular, drawable(context(label, words)), textWidth);
        for (String line : List.of(warehouseLine, contextLine)) {
            baseline += millimetres(CONTEXT_POINTS * LINE_SPACING);
            text(out, cell, fonts.regular, CONTEXT_POINTS, line, cell.innerLeft(), baseline);
        }

        drawQr(out, cell, label.code(), cell.innerLeft() + cell.innerWidth() - qrSide, cell.innerTop(), qrSide);

        double barTop = cell.innerTop() + qrSide + QR_TO_BARCODE_GAP_MM;
        drawBarcode(out, cell, label.code(), cell.innerLeft(), barTop, cell.innerWidth(),
                cell.innerTop() + cell.innerHeight() - barTop);
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

    /** The largest size from 16 pt down to 8 pt that fits; a code wider than that at 8 pt is squeezed. */
    private static float fittingSize(PDFont font, String text, double widthMm) throws IOException {
        float size = CODE_MAX_POINTS;
        while (size > CODE_MIN_POINTS && widthMm(font, text, size) > widthMm) {
            size -= CODE_STEP_POINTS;
        }
        return size;
    }

    /**
     * The longest prefix that fits with an ellipsis, found by bisection: width grows with length, and
     * measuring every prefix one character at a time cost a long name hundreds of measurements per label.
     * {@code text} is BMP-only after {@link #drawable}, so cutting at any index splits no character.
     */
    private static String cut(PDFont font, String text, double widthMm) throws IOException {
        if (widthMm(font, text, CONTEXT_POINTS) <= widthMm) {
            return text;
        }
        int fits = 0;
        int tooLong = text.length();
        while (tooLong - fits > 1) {
            int middle = (fits + tooLong) >>> 1;
            if (widthMm(font, text.substring(0, middle) + ELLIPSIS, CONTEXT_POINTS) <= widthMm) {
                fits = middle;
            } else {
                tooLong = middle;
            }
        }
        return text.substring(0, Math.max(fits, 1)).strip() + ELLIPSIS;
    }

    private static void drawBarcode(PDPageContentStream out, Cell cell, String code, double left, double top,
                                    double width, double height) throws IOException {
        boolean[] modules = new Code128Writer().encode(code);
        int total = modules.length + 2 * BARCODE_QUIET_MODULES;
        double module = width / total;
        for (double candidate : BAR_MODULE_WIDTHS_MM) {
            if (candidate * total <= width) {
                module = candidate;
                break;
            }
        }
        double x = left + BARCODE_QUIET_MODULES * module;
        for (int i = 0; i < modules.length; ) {
            if (!modules[i]) {
                i++;
                continue;
            }
            int run = i;
            while (run < modules.length && modules[run]) {
                run++;
            }
            cell.rect(out, x + i * module, top, (run - i) * module, height);
            i = run;
        }
        out.fill();
    }

    private static void drawQr(PDPageContentStream out, Cell cell, String code, double left, double top,
                               double side) throws IOException {
        ByteMatrix matrix;
        try {
            // No character-set hint: a location code is [A-Z0-9-], which QR's alphanumeric mode holds
            // without an ECI header, giving the smallest symbol.
            matrix = Encoder.encode(code, ErrorCorrectionLevel.M).getMatrix();
        } catch (WriterException e) {
            throw new IllegalStateException("A location code could not be encoded as a QR code", e);
        }
        double module = side / (matrix.getWidth() + 2 * QR_QUIET_MODULES);
        double origin = QR_QUIET_MODULES * module;
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); ) {
                if (matrix.get(x, y) != 1) {
                    x++;
                    continue;
                }
                int run = x;
                while (run < matrix.getWidth() && matrix.get(run, y) == 1) {
                    run++;
                }
                cell.rect(out, left + origin + x * module, top + origin + y * module, (run - x) * module, module);
                x = run;
            }
        }
        out.fill();
    }

    private static void text(PDPageContentStream out, Cell cell, PDFont font, float size, String text,
                             double left, double baseline) throws IOException {
        out.beginText();
        out.setFont(font, size);
        out.newLineAtOffset(points(left), cell.y(baseline));
        out.showText(text);
        out.endText();
    }

    private static double widthMm(PDFont font, String text, float size) throws IOException {
        return millimetres(font.getStringWidth(text) / 1000f * size);
    }

    private static double ascentMm(PDFont font, float size) {
        return millimetres(font.getFontDescriptor().getAscent() / 1000f * size);
    }

    private static float points(double millimetres) {
        return (float) (millimetres * POINTS_PER_MM);
    }

    private static double millimetres(float points) {
        return points / POINTS_PER_MM;
    }

    private record Fonts(PDFont bold, PDFont regular) {
    }

    /**
     * One label's place on its page, in millimetres measured from the page's top-left corner, which
     * is how label stock is specified. PDF measures from the bottom-left; {@link #y} converts.
     */
    private static final class Cell {

        private final LabelFormat format;
        private final double left;
        private final double top;

        Cell(LabelFormat format, int slot) {
            this.format = format;
            this.left = (slot % format.columns()) * format.labelWidthMm();
            this.top = format.topMarginMm() + (slot / format.columns()) * format.labelHeightMm();
        }

        double innerLeft() { return left + format.paddingMm(); }
        double innerTop() { return top + format.paddingMm(); }
        double innerWidth() { return format.labelWidthMm() - 2 * format.paddingMm(); }
        double innerHeight() { return format.labelHeightMm() - 2 * format.paddingMm(); }

        float y(double fromTopMm) {
            return points(format.pageHeightMm() - fromTopMm);
        }

        /** Adds a rectangle given by its top-left corner; the caller fills all of them at once. */
        void rect(PDPageContentStream out, double x, double fromTop, double width, double height) throws IOException {
            out.addRect(points(x), y(fromTop + height), points(width), points(height));
        }
    }
}
