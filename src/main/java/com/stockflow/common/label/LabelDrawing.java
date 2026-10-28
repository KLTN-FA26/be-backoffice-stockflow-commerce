package com.stockflow.common.label;

import com.google.zxing.WriterException;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;

import java.io.IOException;

/**
 * The drawing primitives of {@link CodeLabelSheet}: a Code 128 barcode, a QR code, fitted text and the
 * millimetre/point conversion. Location labels (SCRUM-97) and carton labels (SCRUM-473) both go through
 * that one layout, so a scanner tuned on one reads the other.
 *
 * <p>Every bar and every QR module is a filled vector rectangle rather than a raster image, so a
 * label is as sharp on a 203 dpi thermal printer as on a 600 dpi office printer.</p>
 *
 * <p>Positions are millimetres from the page's top-left corner, which is how label stock is specified;
 * PDF measures from the bottom-left, and {@link Cell#y} converts.</p>
 */
final class LabelDrawing {

    private static final float POINTS_PER_MM = 72f / 25.4f;

    /** 3 and 2 dots of a 203 dpi thermal head, then the narrowest bar common scanners still resolve. */
    private static final double[] BAR_MODULE_WIDTHS_MM = {0.375, 0.25, 0.19};
    private static final int BARCODE_QUIET_MODULES = 10;
    private static final int QR_QUIET_MODULES = 4;

    /** The size {@link #cut} measures at: the small secondary lines of a label, not its headline. */
    public static final float CONTEXT_POINTS = 6.5f;
    private static final String ELLIPSIS = "…";

    private LabelDrawing() {
    }

    /**
     * One label's place on its page. {@code pageHeightMm} is carried because PDF's origin is the
     * bottom-left corner while every position here is measured from the top.
     */
    public record Cell(double leftMm, double topMm, double widthMm, double heightMm, double pageHeightMm) {

        public float y(double fromTopMm) {
            return points(pageHeightMm - fromTopMm);
        }

        /** Adds a rectangle given by its top-left corner; the caller fills all of them at once. */
        void rect(PDPageContentStream out, double x, double fromTop, double width, double height) throws IOException {
            out.addRect(points(x), y(fromTop + height), points(width), points(height));
        }
    }

    /** The largest size from {@code max} down to {@code min} that fits; a text wider than that at {@code min} is the caller's to squeeze. */
    public static float fittingSize(PDFont font, String text, double widthMm, float max, float min, float step)
            throws IOException {
        float size = max;
        while (size > min && widthMm(font, text, size) > widthMm) {
            size -= step;
        }
        return size;
    }

    /**
     * The longest prefix that fits with an ellipsis, found by bisection: width grows with length, and
     * measuring every prefix one character at a time cost a long name hundreds of measurements per label.
     * {@code text} must be BMP-only, so cutting at any index splits no character.
     */
    public static String cut(PDFont font, String text, double widthMm) throws IOException {
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

    public static void barcode(PDPageContentStream out, Cell cell, String code, double leftMm, double topMm,
                               double widthMm, double heightMm) throws IOException {
        boolean[] modules = new Code128Writer().encode(code);
        int total = modules.length + 2 * BARCODE_QUIET_MODULES;
        double module = widthMm / total;
        for (double candidate : BAR_MODULE_WIDTHS_MM) {
            if (candidate * total <= widthMm) {
                module = candidate;
                break;
            }
        }
        double x = leftMm + BARCODE_QUIET_MODULES * module;
        for (int i = 0; i < modules.length; ) {
            if (!modules[i]) {
                i++;
                continue;
            }
            int run = i;
            while (run < modules.length && modules[run]) {
                run++;
            }
            cell.rect(out, x + i * module, topMm, (run - i) * module, heightMm);
            i = run;
        }
        out.fill();
    }

    public static void qr(PDPageContentStream out, Cell cell, String code, double leftMm, double topMm,
                          double sideMm) throws IOException {
        ByteMatrix matrix;
        try {
            // No character-set hint: a label code is [A-Z0-9-], which QR's alphanumeric mode holds
            // without an ECI header, giving the smallest symbol.
            matrix = Encoder.encode(code, ErrorCorrectionLevel.M).getMatrix();
        } catch (WriterException e) {
            throw new IllegalStateException("A label code could not be encoded as a QR code", e);
        }
        double module = sideMm / (matrix.getWidth() + 2 * QR_QUIET_MODULES);
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
                cell.rect(out, leftMm + origin + x * module, topMm + origin + y * module, (run - x) * module, module);
                x = run;
            }
        }
        out.fill();
    }

    public static void text(PDPageContentStream out, Cell cell, PDFont font, float size, String text,
                            double leftMm, double baselineMm) throws IOException {
        out.beginText();
        out.setFont(font, size);
        out.newLineAtOffset(points(leftMm), cell.y(baselineMm));
        out.showText(text);
        out.endText();
    }

    public static double widthMm(PDFont font, String text, float size) throws IOException {
        return millimetres(font.getStringWidth(text) / 1000f * size);
    }

    public static double ascentMm(PDFont font, float size) {
        return millimetres(font.getFontDescriptor().getAscent() / 1000f * size);
    }

    public static float points(double mm) {
        return (float) (mm * POINTS_PER_MM);
    }

    public static double millimetres(float points) {
        return points / POINTS_PER_MM;
    }
}
