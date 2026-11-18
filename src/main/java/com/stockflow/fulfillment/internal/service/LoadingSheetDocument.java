package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.label.LabelFonts;
import com.stockflow.fulfillment.internal.domain.loadplanning.Layout;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;

/**
 * The loading sheet of one vehicle of a load plan as a PDF (kltn-docs 09 §4.2 step 5 "xuất PDF sơ đồ xếp cho từng xe",
 * §3 Output "phiếu đặt xe" and "sơ đồ xếp xuất PDF"): what the dispatcher sends the carrier and what the loaders read
 * at the dock.
 *
 * <p>Page one is the vehicle - plan, dispatch load, vehicle type, warehouse, carrier and waybill when booked, plate and
 * driver - its totals (packages, volume and weight against the inside and the payload, as bars and percentages) and a
 * readable 2D diagram: the placements seen from above and from the left side, both drawn to one scale from the stored
 * placements, each carton numbered by its place in the loading order and coloured by SKU. The carton list follows, in
 * loading order, flowing over as many A4 pages as it needs, with the page number on every page.</p>
 *
 * <p>Axes are the planner's ({@link Layout}): {@code x} across (from the left wall), {@code y} up, {@code z} along the
 * length from the front wall to the door. The sheet calls them R (rộng), C (cao) and D (dài) as the screens and labels
 * do. Captions are Vietnamese (the bundle's), drawn in the embedded Noto Sans of the labels, so every diacritic prints;
 * free text is made drawable by {@link LabelFonts#drawable}. Knows nothing about persistence: what it shows is given.</p>
 */
@Component
class LoadingSheetDocument {

    /**
     * Everything the sheet says besides the layout; a text that is not known (no dispatch load yet, not booked) is null.
     *
     * @param planStatus  the plan's status name; a plan not yet confirmed or cancelled gets a note
     * @param orderByRef  the order number of each carton, by the layout box's {@code ref}
     * @param loadCode    the vehicle's dispatch load; null while the plan is not confirmed
     */
    record Facts(String planCode, String planStatus, String vehicleType, String warehouse, String loadCode,
                 String carrier, String waybill, String plate, String driver, String driverPhone, Layout layout,
                 Map<String, String> orderByRef, Instant printedAt) {
    }

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(VIETNAM);
    private static final Locale VI = Locale.forLanguageTag("vi");

    private static final float MARGIN = 36;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float WIDTH = PAGE_WIDTH - 2 * MARGIN;
    private static final float FOOTER_Y = 20;
    private static final float BOTTOM = 40;
    private static final float ROW = 13;
    /** The smallest size a table cell shrinks to before its text is cut. */
    private static final float MIN_CELL_SIZE = 5.5f;
    private static final float DIAGRAM_MAX_HEIGHT = 130;

    private static final Color INK = new Color(0x1F2937);
    private static final Color MUTED = new Color(0x6B7280);
    private static final Color RULE = new Color(0xD1D5DB);
    private static final Color HEAD_FILL = new Color(0xF3F4F6);
    private static final Color GUSSET = new Color(0xB0B5BD);
    private static final Color BAR_OK = new Color(0x2F7D5B);
    private static final Color BAR_HIGH = new Color(0xC9892B);
    /** Soft fills dark text stays readable on; assigned to the SKUs in order, wrapping round past the last. */
    private static final Color[] PALETTE = {new Color(0xA9D6F5), new Color(0xF5C6A0), new Color(0xB8E0B1),
            new Color(0xF2B8C6), new Color(0xD6C4F0), new Color(0xF2E29A), new Color(0x9FDCD3), new Color(0xE8B4F0),
            new Color(0xC9D68A), new Color(0xF0B3A3), new Color(0xA6B8F0), new Color(0xD9CBB0)};
    /** Columns of the carton list: header key, width in points, right-aligned. */
    private static final String[] COLUMNS = {"seq", "lpn", "order", "sku", "size", "weight", "position",
            "orientation"};
    private static final float[] COLUMN_WIDTHS = {26, 108, 98, 86, 62, 28, 54, 61};
    private static final boolean[] RIGHT = {true, false, false, false, true, true, true, false};

    private final LabelFonts fonts;

    LoadingSheetDocument(LabelFonts fonts) {
        this.fonts = fonts;
    }

    byte[] render(Facts facts) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", VI);
        try (PDDocument document = new PDDocument()) {
            PDFont bold = PDType0Font.load(document, new ByteArrayInputStream(fonts.bold()), true);
            PDFont regular = PDType0Font.load(document, new ByteArrayInputStream(fonts.regular()), true);
            new Sheet(document, bold, regular, t, facts).draw();
            document.getDocumentInformation().setTitle(fonts.drawable(t.getString("loading-sheet.pdf.title") + " "
                    + facts.planCode() + " #" + facts.layout().seq()));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not draw the loading sheet of " + facts.planCode(), e);
        }
    }

    /** The pages of one sheet, drawn top to bottom; {@code y} is the next free position, in points from the bottom. */
    private final class Sheet {

        private final PDDocument document;
        private final PDFont bold;
        private final PDFont regular;
        private final ResourceBundle t;
        private final Facts facts;
        private final Layout layout;
        private final NumberFormat number = NumberFormat.getNumberInstance(VI);
        private final Map<String, Color> colors = new TreeMap<>();
        private PDPageContentStream out;
        private float y;

        Sheet(PDDocument document, PDFont bold, PDFont regular, ResourceBundle t, Facts facts) {
            this.document = document;
            this.bold = bold;
            this.regular = regular;
            this.t = t;
            this.facts = facts;
            this.layout = facts.layout();
            this.number.setMaximumFractionDigits(1);
            this.number.setMinimumFractionDigits(0);
            List<String> skus = layout.boxes().stream().map(Layout.Box::partName).distinct().sorted().toList();
            for (int i = 0; i < skus.size(); i++) {
                colors.put(skus.get(i), PALETTE[i % PALETTE.length]);
            }
        }

        void draw() throws IOException {
            try {
                newPage();
                header();
                totals();
                diagrams();
                table();
            } finally {
                if (out != null) {
                    out.close();
                }
            }
            footers();
        }

        // ---- header and totals ------------------------------------------------------------------------------

        private void header() throws IOException {
            text(bold, 16, t.getString("loading-sheet.pdf.title"), MARGIN, y - 16, INK);
            String right = facts.planCode() + " · " + t.getString("loading-sheet.pdf.vehicle")
                    .formatted(layout.seq(), layout.code());
            textRight(bold, 11, right, MARGIN + WIDTH, y - 16);
            y -= 30;
            String blank = t.getString("loading-sheet.pdf.unknown");
            String[][] left = {
                    {"plan", facts.planCode()},
                    {"load", facts.loadCode() == null ? t.getString("loading-sheet.pdf.noLoad") : facts.loadCode()},
                    {"vehicleType", or(facts.vehicleType(), layout.code())},
                    {"warehouse", or(facts.warehouse(), blank)},
                    {"carrier", or(facts.carrier(), blank)},
                    {"waybill", or(facts.waybill(), blank)}};
            String[][] rightColumn = {
                    {"plate", or(facts.plate(), blank)},
                    {"driver", driver(blank)},
                    {"inner", "%s × %s × %s cm".formatted(cm(layout.innerMm().l()), cm(layout.innerMm().w()),
                            cm(layout.innerMm().h()))},
                    {"payload", number.format(layout.maxPayloadKg()) + " kg"},
                    {"printedOn", MOMENT.format(facts.printedAt())}};
            float columnWidth = WIDTH / 2 - 8;
            float labelWidth = 92;
            float top = y;
            for (int i = 0; i < left.length; i++) {
                keyValue(left[i], MARGIN, labelWidth, columnWidth, top - i * 14);
            }
            for (int i = 0; i < rightColumn.length; i++) {
                keyValue(rightColumn[i], MARGIN + WIDTH / 2 + 8, labelWidth, columnWidth, top - i * 14);
            }
            y = top - left.length * 14 - 4;
            rule();
        }

        private String driver(String blank) {
            if (facts.driver() == null || facts.driver().isBlank()) {
                return blank;
            }
            return facts.driverPhone() == null || facts.driverPhone().isBlank() ? facts.driver()
                    : facts.driver() + " - " + facts.driverPhone();
        }

        private void keyValue(String[] pair, float x, float labelWidth, float width, float top) throws IOException {
            text(regular, 8.5f, t.getString("loading-sheet.pdf.label." + pair[0]), x, top - 9, MUTED);
            text(bold, 9, cut(bold, 9, fonts.drawable(pair[1]), width - labelWidth), x + labelWidth, top - 9, INK);
        }

        private void totals() throws IOException {
            BigDecimal usedM3 = BigDecimal.valueOf(layout.usedVolumeMm3()).movePointLeft(9);
            BigDecimal innerM3 = BigDecimal.valueOf((long) layout.innerMm().l() * layout.innerMm().w()
                    * layout.innerMm().h()).movePointLeft(9);
            y -= 4;
            text(bold, 10, t.getString("loading-sheet.pdf.totals").formatted(layout.boxes().size()), MARGIN, y - 10,
                    INK);
            y -= 18;
            bar(t.getString("loading-sheet.pdf.volume").formatted(number.format(usedM3), number.format(innerM3)),
                    layout.volumeFillPct());
            bar(t.getString("loading-sheet.pdf.weight").formatted(number.format(layout.usedWeightKg()),
                    number.format(layout.maxPayloadKg())), layout.weightFillPct());
            if (facts.planStatus() != null) {
                String note = switch (facts.planStatus()) {
                    case "DRAFT", "PLANNED" -> t.getString("loading-sheet.pdf.note.unconfirmed");
                    case "CANCELLED" -> t.getString("loading-sheet.pdf.note.cancelled");
                    default -> null;
                };
                if (note != null) {
                    text(bold, 8.5f, note, MARGIN, y - 9, BAR_HIGH);
                    y -= 14;
                }
            }
            y -= 2;
            rule();
        }

        /** A caption, a bar filled to {@code pct} of the width (capped at the frame), and the percentage. */
        private void bar(String caption, BigDecimal pct) throws IOException {
            float barX = MARGIN + 250;
            float barWidth = WIDTH - 250 - 52;
            text(regular, 9, caption, MARGIN, y - 9, INK);
            double fraction = Math.max(0, Math.min(1, pct.doubleValue() / 100));
            out.setNonStrokingColor(HEAD_FILL);
            out.addRect(barX, y - 10, barWidth, 9);
            out.fill();
            out.setNonStrokingColor(pct.doubleValue() > 100 ? BAR_HIGH : BAR_OK);
            out.addRect(barX, y - 10, (float) (barWidth * fraction), 9);
            out.fill();
            out.setStrokingColor(RULE);
            out.setLineWidth(0.5f);
            out.addRect(barX, y - 10, barWidth, 9);
            out.stroke();
            text(bold, 9, number.format(pct) + "%", barX + barWidth + 6, y - 9, INK);
            y -= 15;
        }

        // ---- the 2D diagram ---------------------------------------------------------------------------------

        private void diagrams() throws IOException {
            int length = layout.innerMm().l();
            int width = layout.innerMm().w();
            int height = layout.innerMm().h();
            float scale = Math.min(WIDTH / length, DIAGRAM_MAX_HEIGHT / Math.max(width, height));
            y -= 4;
            // The top view: along the length to the right (door at the right), across downward from the left wall.
            diagram(t.getString("loading-sheet.pdf.top"), length, width, scale, true);
            diagram(t.getString("loading-sheet.pdf.side"), length, height, scale, false);
            legend();
            rule();
        }

        private void diagram(String title, int length, int depth, float scale, boolean top) throws IOException {
            float w = length * scale;
            float h = depth * scale;
            float gap = top ? 14 : 22;
            ensure(h + gap + 14);
            text(bold, 9.5f, title, MARGIN, y - 9, INK);
            if (top) {
                textRight(regular, 7.5f, t.getString("loading-sheet.pdf.ends"), MARGIN + WIDTH, y - 9);
            }
            y -= 14;
            float left = MARGIN;
            float frameTop = y;
            float floor = frameTop - h;
            // Floor / inside of the vehicle.
            out.setNonStrokingColor(Color.WHITE);
            out.addRect(left, frameTop - h, w, h);
            out.fill();
            // Reserved ceiling corners (BR-10): grey, behind the cartons.
            for (Layout.Block gusset : layout.gussets()) {
                float gx = left + gusset.zMm() * scale;
                float gy = top ? frameTop - (gusset.xMm() + gusset.wMm()) * scale : floor + gusset.yMm() * scale;
                float gw = gusset.dMm() * scale;
                float gh = (top ? gusset.wMm() : gusset.hMm()) * scale;
                out.setNonStrokingColor(GUSSET);
                out.addRect(gx, gy, gw, gh);
                out.fill();
            }
            List<Layout.Box> order = new ArrayList<>(layout.boxes());
            // What is nearer the viewer is drawn last: the higher in the top view, the nearer the left wall in the side
            // view.
            order.sort(top ? Comparator.comparingInt(Layout.Box::yMm).thenComparingInt(Layout.Box::loadSequence)
                    : Comparator.comparingInt(Layout.Box::xMm).reversed()
                            .thenComparingInt(Layout.Box::loadSequence));
            for (Layout.Box box : order) {
                float bx = left + box.zMm() * scale;
                float bw = box.dMm() * scale;
                float bh = (top ? box.wMm() : box.hMm()) * scale;
                float by = top ? frameTop - (box.xMm() + box.wMm()) * scale : floor + box.yMm() * scale;
                out.setNonStrokingColor(colors.getOrDefault(box.partName(), PALETTE[0]));
                out.addRect(bx, by, bw, bh);
                out.fill();
                out.setStrokingColor(INK);
                out.setLineWidth(0.4f);
                out.addRect(bx, by, bw, bh);
                out.stroke();
                mark(String.valueOf(box.loadSequence()), bx, by, bw, bh);
            }
            out.setStrokingColor(INK);
            out.setLineWidth(1f);
            out.addRect(left, frameTop - h, w, h);
            out.stroke();
            // Axis hints, and a scale bar of one metre.
            if (top) {
                text(regular, 7, t.getString("loading-sheet.pdf.left"), left + w + 4, frameTop - 7, MUTED);
                text(regular, 7, t.getString("loading-sheet.pdf.right"), left + w + 4, frameTop - h + 1, MUTED);
            } else {
                text(regular, 7, t.getString("loading-sheet.pdf.ceiling"), left + w + 4, frameTop - 7, MUTED);
                text(regular, 7, t.getString("loading-sheet.pdf.floor"), left + w + 4, frameTop - h + 1, MUTED);
            }
            if (!top) {
                float metre = 1000 * scale;
                out.setStrokingColor(INK);
                out.setLineWidth(1.2f);
                out.moveTo(left + w - metre, frameTop - h - 7);
                out.lineTo(left + w, frameTop - h - 7);
                out.stroke();
                textRight(regular, 7, t.getString("loading-sheet.pdf.scale"), left + w - metre - 4, frameTop - h - 9);
            }
            y = frameTop - h - gap;
        }

        /** The carton's number in its rectangle, centred, when the rectangle is big enough to read it. */
        private void mark(String label, float x, float bottom, float w, float h) throws IOException {
            float size = Math.min(7f, h * 0.7f);
            float natural = textWidth(bold, size, label);
            if (natural > w * 0.92f) {
                size = size * w * 0.92f / natural;
            }
            if (size < 3.2f) {
                return;
            }
            text(bold, size, label, x + (w - textWidth(bold, size, label)) / 2, bottom + (h - size * 0.72f) / 2, INK);
        }

        private void legend() throws IOException {
            Map<String, Integer> counts = new TreeMap<>();
            layout.boxes().forEach(b -> counts.merge(b.partName(), 1, Integer::sum));
            float x = MARGIN;
            ensure(24);
            text(regular, 7.5f, t.getString("loading-sheet.pdf.numbers"), MARGIN, y - 7, MUTED);
            y -= 12;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                String caption = fonts.drawable(t.getString("loading-sheet.pdf.legend").formatted(entry.getKey(),
                        entry.getValue()));
                float need = 12 + textWidth(regular, 8, caption) + 12;
                if (x + need > MARGIN + WIDTH) {
                    x = MARGIN;
                    y -= 12;
                    ensure(16);
                }
                out.setNonStrokingColor(colors.get(entry.getKey()));
                out.addRect(x, y - 8, 8, 8);
                out.fill();
                out.setStrokingColor(INK);
                out.setLineWidth(0.3f);
                out.addRect(x, y - 8, 8, 8);
                out.stroke();
                text(regular, 8, caption, x + 12, y - 7.5f, INK);
                x += need;
            }
            if (!layout.gussets().isEmpty()) {
                String caption = t.getString("loading-sheet.pdf.gusset");
                float need = 12 + textWidth(regular, 8, caption);
                if (x + need > MARGIN + WIDTH) {
                    x = MARGIN;
                    y -= 12;
                }
                out.setNonStrokingColor(GUSSET);
                out.addRect(x, y - 8, 8, 8);
                out.fill();
                text(regular, 8, caption, x + 12, y - 7.5f, INK);
            }
            y -= 16;
        }

        // ---- the carton list --------------------------------------------------------------------------------

        private void table() throws IOException {
            ensure(60);
            text(bold, 10, t.getString("loading-sheet.pdf.list"), MARGIN, y - 10, INK);
            y -= 16;
            for (String line : wrap(regular, 7.5f, t.getString("loading-sheet.pdf.hint"), WIDTH)) {
                text(regular, 7.5f, line, MARGIN, y - 7.5f, MUTED);
                y -= 10;
            }
            y -= 4;
            List<Layout.Box> boxes = layout.boxes().stream()
                    .sorted(Comparator.comparingInt(Layout.Box::loadSequence)).toList();
            if (boxes.isEmpty()) {
                text(regular, 9, t.getString("loading-sheet.pdf.empty"), MARGIN, y - 9, INK);
                return;
            }
            tableHeader();
            for (Layout.Box box : boxes) {
                if (y - ROW < BOTTOM) {
                    newPage();
                    tableHeader();
                }
                row(box);
            }
            BigDecimal total = boxes.stream().map(Layout.Box::weightKg).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (y - ROW < BOTTOM) {
                newPage();
            }
            float x = MARGIN;
            for (int i = 0; i < 5; i++) {
                x += COLUMN_WIDTHS[i];
            }
            line(MARGIN, y, MARGIN + WIDTH, y);
            text(bold, 8, t.getString("loading-sheet.pdf.total").formatted(boxes.size()), MARGIN + 3, y - 9.5f, INK);
            textRight(bold, 8, number.format(total), x + COLUMN_WIDTHS[5] - 3, y - 9.5f);
            y -= ROW;
        }

        private void tableHeader() throws IOException {
            out.setNonStrokingColor(HEAD_FILL);
            out.addRect(MARGIN, y - ROW, WIDTH, ROW);
            out.fill();
            float x = MARGIN;
            for (int i = 0; i < COLUMNS.length; i++) {
                String caption = t.getString("loading-sheet.pdf.col." + COLUMNS[i]);
                cell(bold, 7.5f, caption, x, COLUMN_WIDTHS[i], y - 9.5f, RIGHT[i]);
                x += COLUMN_WIDTHS[i];
            }
            line(MARGIN, y - ROW, MARGIN + WIDTH, y - ROW);
            y -= ROW;
        }

        private void row(Layout.Box box) throws IOException {
            String orientation = turned(box)
                    ? t.getString("loading-sheet.pdf.orientation.turned").formatted(cm(box.dMm()), cm(box.wMm()),
                            cm(box.hMm()))
                    : t.getString("loading-sheet.pdf.orientation.asIs");
            String[] values = {String.valueOf(box.loadSequence()), box.label(), facts.orderByRef().getOrDefault(
                    box.ref(), ""), box.partName(),
                    "%s×%s×%s".formatted(cm(box.origMm().l()), cm(box.origMm().w()), cm(box.origMm().h())),
                    number.format(box.weightKg()),
                    "%s / %s / %s".formatted(cm(box.zMm()), cm(box.xMm()), cm(box.yMm())), orientation};
            float x = MARGIN;
            for (int i = 0; i < values.length; i++) {
                cell(i == 1 ? bold : regular, 7.5f, values[i], x, COLUMN_WIDTHS[i], y - 9.5f, RIGHT[i]);
                x += COLUMN_WIDTHS[i];
            }
            line(MARGIN, y - ROW, MARGIN + WIDTH, y - ROW);
            y -= ROW;
        }

        /** Whether the carton lies other than as measured: its placed sizes are not its length, width, height. */
        private boolean turned(Layout.Box box) {
            return !"LWH".equals(box.orientation());
        }

        // ---- pages, footers, primitives ---------------------------------------------------------------------

        private void newPage() throws IOException {
            if (out != null) {
                out.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            out = new PDPageContentStream(document, page);
            y = PAGE_HEIGHT - MARGIN;
        }

        /** A new page when {@code needed} points do not fit above the bottom margin. */
        private void ensure(float needed) throws IOException {
            if (y - needed < BOTTOM) {
                newPage();
            }
        }

        /** "LP-... · Xe #n - Trang p/N" on every page, once their number is known. */
        private void footers() throws IOException {
            int pages = document.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                PDPage page = document.getPage(i);
                try (PDPageContentStream footer = new PDPageContentStream(document, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    String caption = t.getString("loading-sheet.pdf.footer").formatted(facts.planCode(), layout.seq(),
                            i + 1, pages);
                    footer.setNonStrokingColor(MUTED);
                    footer.beginText();
                    footer.setFont(regular, 7.5f);
                    footer.newLineAtOffset((PAGE_WIDTH - textWidth(regular, 7.5f, caption)) / 2, FOOTER_Y);
                    footer.showText(caption);
                    footer.endText();
                }
            }
        }

        private void rule() throws IOException {
            line(MARGIN, y - 2, MARGIN + WIDTH, y - 2);
            y -= 8;
        }

        private void line(float x1, float y1, float x2, float y2) throws IOException {
            out.setStrokingColor(RULE);
            out.setLineWidth(0.5f);
            out.moveTo(x1, y1);
            out.lineTo(x2, y2);
            out.stroke();
        }

        /** Text inside a column, 3 points from the cell's edge: shrunk to fit, cut with an ellipsis only past the smallest size. */
        private void cell(PDFont font, float size, String value, float x, float width, float baseline, boolean right)
                throws IOException {
            String drawable = fonts.drawable(value);
            // A code or a SKU is read whole: shrink the text to the column before cutting it, down to a size still legible
            // on paper; only what is longer than that is cut with an ellipsis.
            float fitted = size;
            while (fitted > MIN_CELL_SIZE && textWidth(font, fitted, drawable) > width - 6) {
                fitted -= 0.25f;
            }
            String shown = cut(font, fitted, drawable, width - 6);
            if (right) {
                textRight(font, fitted, shown, x + width - 3, baseline);
            } else {
                text(font, fitted, shown, x + 3, baseline, INK);
            }
        }

        private void text(PDFont font, float size, String value, float x, float baseline, Color color)
                throws IOException {
            out.setNonStrokingColor(color);
            out.beginText();
            out.setFont(font, size);
            out.newLineAtOffset(x, baseline);
            out.showText(fonts.drawable(value));
            out.endText();
        }

        private void textRight(PDFont font, float size, String value, float right, float baseline)
                throws IOException {
            String shown = fonts.drawable(value);
            text(font, size, shown, right - textWidth(font, size, shown), baseline, INK);
        }

        private float textWidth(PDFont font, float size, String value) throws IOException {
            return font.getStringWidth(fonts.drawable(value)) / 1000f * size;
        }

        /** The longest prefix that fits {@code width} with an ellipsis; the text itself when it fits. */
        private String cut(PDFont font, float size, String value, float width) throws IOException {
            if (textWidth(font, size, value) <= width) {
                return value;
            }
            int fits = 0;
            int tooLong = value.length();
            while (tooLong - fits > 1) {
                int middle = (fits + tooLong) >>> 1;
                if (textWidth(font, size, value.substring(0, middle) + "…") <= width) {
                    fits = middle;
                } else {
                    tooLong = middle;
                }
            }
            return value.substring(0, Math.max(fits, 1)).strip() + "…";
        }

        private List<String> wrap(PDFont font, float size, String value, float width) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : fonts.drawable(value).split(" ")) {
                String tryLine = current.isEmpty() ? word : current + " " + word;
                if (!current.isEmpty() && textWidth(font, size, tryLine) > width) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(tryLine);
                }
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
            }
            return lines;
        }

        private String cm(int mm) {
            return number.format(mm / 10.0);
        }

        private String or(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
