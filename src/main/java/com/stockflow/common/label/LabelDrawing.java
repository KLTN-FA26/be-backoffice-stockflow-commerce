package com.stockflow.common.label;

import com.google.zxing.WriterException;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The drawing primitives of the label sheets: a Code 128 barcode, a QR code, fitted and wrapped text and the
 * millimetre/point conversion. Location labels (SCRUM-97, {@link CodeLabelSheet}), carton labels (SCRUM-473,
 * {@link CartonLabelSheet}) and shipping labels all draw their symbols here, so a scanner tuned on one reads the
 * others. Public since 4A-3: the production order slip prints its code - the lot its goods are received under - with
 * the same symbols, so the receiving scanner reads it too.
 *
 * <p>Every bar and every QR module is a filled vector rectangle rather than a raster image, so a
 * label is as sharp on a 203 dpi thermal printer as on a 600 dpi office printer.</p>
 *
 * <p>Positions are millimetres from the page's top-left corner, which is how label stock is specified;
 * PDF measures from the bottom-left, and {@link Cell#y} converts.</p>
 */
public final class LabelDrawing {

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

        /** The {@code slot}-th label of a page of {@code format}, row by row. */
        static Cell of(LabelFormat format, int slot) {
            return new Cell((slot % format.columns()) * format.labelWidthMm(),
                    format.topMarginMm() + (slot / format.columns()) * format.labelHeightMm(),
                    format.labelWidthMm(), format.labelHeightMm(), format.pageHeightMm());
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
     * Draws a code that must stay whole - a label's LPN or location code - at {@code size}, squeezing the glyphs when
     * it is still wider than {@code widthMm}: cutting the code would make it unreadable, and letting it run over would
     * break the finder patterns of the QR code beside it. Both code label sheets head their label this way.
     */
    public static void squeezedText(PDPageContentStream out, Cell cell, PDFont font, float size, String text,
                                    double leftMm, double baselineMm, double widthMm) throws IOException {
        float squeeze = (float) Math.min(1, widthMm / widthMm(font, text, size));
        out.setHorizontalScaling(squeeze * 100);
        text(out, cell, font, size, text, leftMm, baselineMm);
        out.setHorizontalScaling(100);
    }

    /**
     * The longest prefix that fits with an ellipsis, found by bisection: width grows with length, and
     * measuring every prefix one character at a time cost a long name hundreds of measurements per label.
     * {@code text} must be BMP-only, so cutting at any index splits no character.
     */
    public static String cut(PDFont font, String text, double widthMm) throws IOException {
        return cut(font, text, widthMm, CONTEXT_POINTS);
    }

    /** {@link #cut(PDFont, String, double)} for text drawn at {@code size} points. */
    public static String cut(PDFont font, String text, double widthMm, float size) throws IOException {
        if (widthMm(font, text, size) <= widthMm) {
            return text;
        }
        int fits = 0;
        int tooLong = text.length();
        while (tooLong - fits > 1) {
            int middle = (fits + tooLong) >>> 1;
            if (widthMm(font, text.substring(0, middle) + ELLIPSIS, size) <= widthMm) {
                fits = middle;
            } else {
                tooLong = middle;
            }
        }
        return text.substring(0, Math.max(fits, 1)).strip() + ELLIPSIS;
    }

    /**
     * Greedy word wrap of drawable text into at most {@code lines} lines of {@code widthMm} at {@code size} points;
     * what the last line cannot hold is cut with an ellipsis, and a single word wider than a line is cut likewise.
     * Blank text gives no line.
     */
    public static List<String> wrap(PDFont font, String text, double widthMm, float size, int lines)
            throws IOException {
        if (lines < 1) {
            throw new IllegalArgumentException("A wrap needs at least one line, not " + lines);
        }
        String trimmed = text.trim();
        List<String> rows = new ArrayList<>();
        if (trimmed.isEmpty()) {
            return rows;
        }
        StringBuilder current = new StringBuilder();
        for (String word : trimmed.split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (current.isEmpty() || widthMm(font, candidate, size) <= widthMm) {
                current.setLength(0);
                current.append(candidate);
            } else {
                rows.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        rows.add(current.toString());
        if (rows.size() > lines) {
            // The rest goes on the last line, cut with an ellipsis.
            String last = String.join(" ", rows.subList(lines - 1, rows.size()));
            rows = new ArrayList<>(rows.subList(0, lines - 1));
            rows.add(last);
        }
        List<String> drawn = new ArrayList<>(rows.size());
        for (String row : rows) {
            drawn.add(cut(font, row, widthMm, size));
        }
        return drawn;
    }

    /**
     * {@link #barcode}, centred in {@code widthMm}: it draws at the widest printable module that fits, which usually
     * leaves room over, and a symbol hugging the left edge looks misplaced on a label.
     */
    public static void centredBarcode(PDPageContentStream out, Cell cell, String code, double leftMm, double topMm,
                                      double widthMm, double heightMm) throws IOException {
        boolean[] modules = new Code128Writer().encode(code);
        int total = modules.length + 2 * BARCODE_QUIET_MODULES;
        double module = moduleWidth(total, widthMm);
        bars(out, cell, modules, leftMm + (widthMm - module * total) / 2 + BARCODE_QUIET_MODULES * module, topMm,
                module, heightMm);
    }

    private static double moduleWidth(int totalModules, double widthMm) {
        for (double candidate : BAR_MODULE_WIDTHS_MM) {
            if (candidate * totalModules <= widthMm) {
                return candidate;
            }
        }
        return widthMm / totalModules;
    }

    public static void barcode(PDPageContentStream out, Cell cell, String code, double leftMm, double topMm,
                               double widthMm, double heightMm) throws IOException {
        boolean[] modules = new Code128Writer().encode(code);
        int total = modules.length + 2 * BARCODE_QUIET_MODULES;
        double module = moduleWidth(total, widthMm);
        bars(out, cell, modules, leftMm + BARCODE_QUIET_MODULES * module, topMm, module, heightMm);
    }

    private static void bars(PDPageContentStream out, Cell cell, boolean[] modules, double x, double topMm,
                             double module, double heightMm) throws IOException {
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
