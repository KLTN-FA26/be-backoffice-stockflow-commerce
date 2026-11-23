package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Canvas;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
import com.stockflow.common.document.BusinessDocument.Figure;
import com.stockflow.common.document.BusinessDocument.Heading;
import com.stockflow.common.document.BusinessDocument.Margins;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Signatures;
import com.stockflow.common.document.BusinessDocument.Signer;
import com.stockflow.common.document.BusinessDocument.Summary;
import com.stockflow.common.document.BusinessDocument.Table;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.Letterhead;
import com.stockflow.common.document.SellerLetterhead;
import com.stockflow.common.document.Style;
import com.stockflow.fulfillment.internal.domain.loadplanning.Layout;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;

/**
 * The loading sheet of one vehicle of a load plan as a PDF (kltn-docs 09 §4.2 step 5, the loading diagram exported per
 * vehicle, and §3 Output, the booking slip and the diagram as PDF): what the dispatcher sends the carrier and what the
 * loaders read at the dock.
 *
 * <p>Page one is the vehicle - plan, dispatch load, vehicle type, warehouse, carrier and waybill when booked, plate and
 * driver - its totals (packages, volume and weight against the inside and the payload, as bars and percentages) and a
 * readable 2D diagram: the placements seen from above and from the left side, both drawn to one scale from the stored
 * placements, each carton numbered by its place in the loading order and coloured by SKU. The carton list follows, in
 * loading order, flowing over as many A4 pages as it needs, and ends with the places where the dispatcher, the loader
 * and the driver sign that the vehicle left as drawn.</p>
 *
 * <p>Laid out by the kit every paper of the company uses ({@link BusinessDocument}): its head, its page foot and its
 * table are the kit's, and the bars and the diagrams are figures the kit places between them - a sheet with a drawing
 * still needs the head and the page numbers, and a second layout engine to give it those is what the kit replaced.
 * The carton list is set smaller than the kit's tables: it has eight columns of codes and must stay one line a
 * carton.</p>
 *
 * <p>Axes are the planner's ({@link Layout}): {@code x} across (from the left wall), {@code y} up, {@code z} along the
 * length from the front wall to the door. The sheet calls them D (length), R (width) and C (height), the letters the
 * screens and labels use. Captions are Vietnamese (the bundle's). Knows nothing about persistence: what it shows is
 * given.</p>
 */
@Component
class LoadingSheetDocument {

    /**
     * Everything the sheet says besides the layout; a text that is not known (no dispatch load yet, not booked) is
     * null.
     *
     * @param orderByRef  the order number of each carton, by the layout box's {@code ref}
     * @param loadCode    the vehicle's dispatch load; null while the plan is not confirmed
     */
    record Facts(String planCode, String vehicleType, String warehouse, String loadCode,
                 String carrier, String waybill, String plate, String driver, String driverPhone, Layout layout,
                 Map<String, String> orderByRef, Instant printedAt) {
    }

    private static final Locale VI = Locale.forLanguageTag("vi");
    private static final float DIAGRAM_MAX_HEIGHT = 105;
    private static final float FACT_ROW = 14;
    private static final float FACT_LABEL = 100;
    /** The carton list's text: eight columns of codes, one line a carton. */
    private static final float LIST_SIZE = 8;

    private static final Color MUTED = new Color(0x55, 0x55, 0x55);
    private static final Color RULE = new Color(0xD1D5DB);
    private static final Color TRACK = new Color(0xF3F4F6);
    private static final Color GUSSET = new Color(0xB0B5BD);
    private static final Color BAR_OK = new Color(0x2F7D5B);
    private static final Color BAR_HIGH = new Color(0xC9892B);
    /** Soft fills dark text stays readable on; assigned to the SKUs in order, wrapping round past the last. */
    private static final Color[] PALETTE = {new Color(0xA9D6F5), new Color(0xF5C6A0), new Color(0xB8E0B1),
            new Color(0xF2B8C6), new Color(0xD6C4F0), new Color(0xF2E29A), new Color(0x9FDCD3), new Color(0xE8B4F0),
            new Color(0xC9D68A), new Color(0xF0B3A3), new Color(0xA6B8F0), new Color(0xD9CBB0)};

    private final DocumentFonts fonts;
    private final SellerLetterhead letterhead;

    LoadingSheetDocument(DocumentFonts fonts, SellerLetterhead letterhead) {
        this.fonts = fonts;
        this.letterhead = letterhead;
    }

    byte[] render(Facts facts) {
        ResourceBundle t = DocumentParts.bundle();
        Letterhead head = letterhead.get();
        Layout layout = facts.layout();
        Numbers n = new Numbers();
        Map<String, Color> colors = new TreeMap<>();
        List<String> skus = layout.boxes().stream().map(Layout.Box::partName).distinct().sorted().toList();
        for (int i = 0; i < skus.size(); i++) {
            colors.put(skus.get(i), PALETTE[i % PALETTE.length]);
        }
        String vehicle = facts.planCode() + " · " + t.getString("loading-sheet.pdf.vehicle")
                .formatted(layout.seq(), layout.code());

        List<Block> blocks = new ArrayList<>();
        blocks.add(DocumentParts.brandHeader(t, head));
        blocks.add(new Title(t.getString("loading-sheet.pdf.title"), vehicle, null));
        blocks.add(vehicleFacts(t, facts, n));

        // The percentages are worked out here from the very figures printed beside them - used over capacity - never
        // taken from a stored fill percentage, so the bar, the % and the numbers cannot disagree.
        BigDecimal usedM3 = BigDecimal.valueOf(layout.usedVolumeMm3()).movePointLeft(9);
        BigDecimal innerM3 = BigDecimal.valueOf((long) layout.innerMm().l() * layout.innerMm().w()
                * layout.innerMm().h()).movePointLeft(9);
        blocks.add(Paragraph.bold(t.getString("loading-sheet.pdf.totals").formatted(layout.boxes().size()))
                .align(Align.LEFT).after(0));
        blocks.add(bar(t.getString("loading-sheet.pdf.volume").formatted(n.volume(usedM3), n.volume(innerM3)),
                percent(usedM3, innerM3), n));
        blocks.add(bar(t.getString("loading-sheet.pdf.weight").formatted(n.number(layout.usedWeightKg()),
                n.number(layout.maxPayloadKg())), percent(layout.usedWeightKg(), layout.maxPayloadKg()), n));

        // The top view: along the length to the right (door at the right), across downward from the left wall.
        blocks.add(diagram(t, layout, colors, true));
        blocks.add(diagram(t, layout, colors, false));
        blocks.add(legend(t, layout, colors));
        blocks.add(Paragraph.italic(t.getString("loading-sheet.pdf.numbers")).size(-3).align(Align.LEFT));

        blocks.add(new Heading(t.getString("loading-sheet.pdf.list")));
        blocks.add(Paragraph.of(t.getString("loading-sheet.pdf.hint")).size(-3));
        List<Layout.Box> boxes = layout.boxes().stream().sorted(Comparator.comparingInt(Layout.Box::loadSequence))
                .toList();
        blocks.add(boxes.isEmpty() ? Paragraph.of(t.getString("loading-sheet.pdf.empty"))
                : cartons(t, facts, boxes, n));

        String caption = t.getString("document.sign.named");
        blocks.add(new Signatures(null, List.of(
                new Signer(t.getString("loading-sheet.pdf.sign.dispatcher"), caption, null),
                new Signer(t.getString("loading-sheet.pdf.sign.loader"), caption, null),
                new Signer(t.getString("loading-sheet.pdf.sign.driver"), caption, null))));

        float margin = BusinessDocument.mm(15);
        BusinessDocument.Layout page = new BusinessDocument.Layout(
                new Margins(margin, margin, BusinessDocument.mm(20), margin), 11, LIST_SIZE, DocumentParts.ACCENT,
                facts.planCode() + " #" + layout.seq(), head.name(), t.getString("document.page"), null);
        return BusinessDocument.draw(fonts, page, t.getString("loading-sheet.pdf.title") + " " + facts.planCode()
                + " #" + layout.seq(), blocks);
    }

    // ---- the vehicle and its totals -----------------------------------------------------------------------------

    /** The vehicle's facts in two columns of label and value: eleven facts must leave the page to the diagram. */
    private Figure vehicleFacts(ResourceBundle t, Facts facts, Numbers n) {
        Layout layout = facts.layout();
        String blank = t.getString("loading-sheet.pdf.unknown");
        String driver = !DocumentParts.filled(facts.driver()) ? blank
                : DocumentParts.filled(facts.driverPhone()) ? facts.driver() + " - " + facts.driverPhone()
                : facts.driver();
        String[][] left = {
                {"plan", facts.planCode()},
                {"load", facts.loadCode() == null ? t.getString("loading-sheet.pdf.noLoad") : facts.loadCode()},
                {"vehicleType", or(facts.vehicleType(), layout.code())},
                {"warehouse", or(facts.warehouse(), blank)},
                {"carrier", or(facts.carrier(), blank)},
                {"waybill", or(facts.waybill(), blank)}};
        String[][] right = {
                {"plate", or(facts.plate(), blank)},
                {"driver", driver},
                {"inner", "%s × %s × %s cm".formatted(n.cm(layout.innerMm().l()), n.cm(layout.innerMm().w()),
                        n.cm(layout.innerMm().h()))},
                {"payload", n.number(layout.maxPayloadKg()) + " kg"},
                {"printedOn", DocumentFormats.moment(facts.printedAt())}};
        return new Figure(left.length * FACT_ROW + 6, canvas -> {
            float column = canvas.width() / 2 - 8;
            for (int i = 0; i < left.length; i++) {
                fact(canvas, t, left[i], canvas.left(), column, canvas.top() - i * FACT_ROW);
            }
            for (int i = 0; i < right.length; i++) {
                fact(canvas, t, right[i], canvas.left() + canvas.width() / 2 + 8, column,
                        canvas.top() - i * FACT_ROW);
            }
            line(canvas.stream(), canvas.left(), canvas.top() - left.length * FACT_ROW - 3, canvas.width());
        });
    }

    private static void fact(Canvas canvas, ResourceBundle t, String[] pair, float x, float width, float top)
            throws IOException {
        canvas.text(Style.REGULAR, 9.5f, x, top - 10, t.getString("loading-sheet.pdf.label." + pair[0]), MUTED);
        canvas.text(Style.BOLD, 10, x + FACT_LABEL, top - 10,
                canvas.cut(Style.BOLD, 10, pair[1], width - FACT_LABEL), Color.BLACK);
    }

    /** {@code used} over {@code capacity}, in percent to one decimal; 0 when there is no capacity. */
    private static BigDecimal percent(BigDecimal used, BigDecimal capacity) {
        if (capacity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return used.multiply(BigDecimal.valueOf(100)).divide(capacity, 1, RoundingMode.HALF_UP);
    }

    /** A caption, a bar filled to {@code pct} of the width (capped at the frame), and the percentage. */
    private static Figure bar(String caption, BigDecimal pct, Numbers n) {
        return new Figure(16, canvas -> {
            float barX = canvas.left() + 250;
            float barWidth = canvas.width() - 250 - 52;
            float y = canvas.top() - 2;
            canvas.text(Style.REGULAR, 10, canvas.left(), y - 9, caption, Color.BLACK);
            double fraction = Math.max(0, Math.min(1, pct.doubleValue() / 100));
            PDPageContentStream out = canvas.stream();
            out.setNonStrokingColor(TRACK);
            out.addRect(barX, y - 10, barWidth, 9);
            out.fill();
            out.setNonStrokingColor(pct.doubleValue() > 100 ? BAR_HIGH : BAR_OK);
            out.addRect(barX, y - 10, (float) (barWidth * fraction), 9);
            out.fill();
            out.setStrokingColor(RULE);
            out.setLineWidth(0.5f);
            out.addRect(barX, y - 10, barWidth, 9);
            out.stroke();
            canvas.text(Style.BOLD, 10, barX + barWidth + 6, y - 9, n.number(pct) + "%", Color.BLACK);
        });
    }

    // ---- the 2D diagram -----------------------------------------------------------------------------------------

    private static Figure diagram(ResourceBundle t, Layout layout, Map<String, Color> colors, boolean top) {
        int length = layout.innerMm().l();
        int depth = top ? layout.innerMm().w() : layout.innerMm().h();
        float gap = top ? 10 : 18;
        float height = DIAGRAM_MAX_HEIGHT * depth / Math.max(layout.innerMm().w(), layout.innerMm().h());
        // Never taller than drawn below: the scale there can only be the smaller, when the vehicle is long.
        return new Figure(14 + height + gap, canvas -> {
            // Both views at one scale; 34 points at the right are kept for the words naming the walls.
            float scale = Math.min((canvas.width() - 34) / length,
                    DIAGRAM_MAX_HEIGHT / Math.max(layout.innerMm().w(), layout.innerMm().h()));
            float w = length * scale;
            float h = depth * scale;
            PDPageContentStream out = canvas.stream();
            float left = canvas.left();
            canvas.text(Style.BOLD, 10, left, canvas.top() - 10,
                    t.getString(top ? "loading-sheet.pdf.top" : "loading-sheet.pdf.side"), Color.BLACK);
            if (top) {
                String ends = t.getString("loading-sheet.pdf.ends");
                canvas.text(Style.ITALIC, 8.5f, left + canvas.width() - canvas.textWidth(Style.ITALIC, 8.5f, ends),
                        canvas.top() - 10, ends, MUTED);
            }
            float frameTop = canvas.top() - 14;
            float floor = frameTop - h;
            // Reserved ceiling corners (BR-10): grey, behind the cartons.
            for (Layout.Block gusset : layout.gussets()) {
                float gy = top ? frameTop - (gusset.xMm() + gusset.wMm()) * scale : floor + gusset.yMm() * scale;
                out.setNonStrokingColor(GUSSET);
                out.addRect(left + gusset.zMm() * scale, gy, gusset.dMm() * scale,
                        (top ? gusset.wMm() : gusset.hMm()) * scale);
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
                out.setStrokingColor(Color.BLACK);
                out.setLineWidth(0.4f);
                out.addRect(bx, by, bw, bh);
                out.stroke();
                mark(canvas, String.valueOf(box.loadSequence()), bx, by, bw, bh);
            }
            out.setStrokingColor(Color.BLACK);
            out.setLineWidth(1f);
            out.addRect(left, floor, w, h);
            out.stroke();
            canvas.text(Style.REGULAR, 7.5f, left + w + 4, frameTop - 7,
                    t.getString(top ? "loading-sheet.pdf.left" : "loading-sheet.pdf.ceiling"), MUTED);
            canvas.text(Style.REGULAR, 7.5f, left + w + 4, floor + 1,
                    t.getString(top ? "loading-sheet.pdf.right" : "loading-sheet.pdf.floor"), MUTED);
            if (!top) {
                // A scale bar of one metre.
                float metre = 1000 * scale;
                out.setLineWidth(1.2f);
                out.moveTo(left + w - metre, floor - 7);
                out.lineTo(left + w, floor - 7);
                out.stroke();
                String label = t.getString("loading-sheet.pdf.scale");
                canvas.text(Style.REGULAR, 7.5f, left + w - metre - 4 - canvas.textWidth(Style.REGULAR, 7.5f, label),
                        floor - 9, label, Color.BLACK);
            }
        });
    }

    /** The carton's number in its rectangle, centred, when the rectangle is big enough to read it. */
    private static void mark(Canvas canvas, String label, float x, float bottom, float w, float h)
            throws IOException {
        float size = Math.min(7f, h * 0.7f);
        float natural = canvas.textWidth(Style.BOLD, size, label);
        if (natural > w * 0.92f) {
            size = size * w * 0.92f / natural;
        }
        if (size < 3.2f) {
            return;
        }
        canvas.text(Style.BOLD, size, x + (w - canvas.textWidth(Style.BOLD, size, label)) / 2,
                bottom + (h - size * 0.72f) / 2, label, Color.BLACK);
    }

    /** Which colour is which SKU, and how many cartons of it. */
    private static Figure legend(ResourceBundle t, Layout layout, Map<String, Color> colors) {
        Map<String, Integer> counts = new TreeMap<>();
        layout.boxes().forEach(b -> counts.merge(b.partName(), 1, Integer::sum));
        List<String> captions = new ArrayList<>();
        counts.forEach((sku, count) -> captions.add(t.getString("loading-sheet.pdf.legend").formatted(sku, count)));
        // A figure's height is asked before it can measure text, so the rows are counted from a width no caption
        // reaches (six tenths of the size a character); drawn with the real widths they can only need fewer.
        float x = 0;
        int rows = 1;
        for (String caption : captions) {
            float need = 24 + caption.length() * 5.2f;
            if (x > 0 && x + need > 480) {
                rows++;
                x = 0;
            }
            x += need;
        }
        int reserved = rows + (layout.gussets().isEmpty() ? 0 : 1);
        return new Figure(reserved * 12 + 4, canvas -> {
            PDPageContentStream out = canvas.stream();
            float at = canvas.left();
            float y = canvas.top();
            int index = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                String caption = captions.get(index++);
                float need = 12 + canvas.textWidth(Style.REGULAR, 8.5f, caption) + 12;
                if (at > canvas.left() && at + need > canvas.left() + canvas.width()) {
                    at = canvas.left();
                    y -= 12;
                }
                swatch(out, colors.get(entry.getKey()), at, y - 9);
                canvas.text(Style.REGULAR, 8.5f, at + 12, y - 8.5f, caption, Color.BLACK);
                at += need;
            }
            if (!layout.gussets().isEmpty()) {
                swatch(out, GUSSET, canvas.left(), y - 21);
                canvas.text(Style.REGULAR, 8.5f, canvas.left() + 12, y - 20.5f,
                        t.getString("loading-sheet.pdf.gusset"), Color.BLACK);
            }
        });
    }

    private static void swatch(PDPageContentStream out, Color colour, float x, float bottom) throws IOException {
        out.setNonStrokingColor(colour);
        out.addRect(x, bottom, 8, 8);
        out.fill();
        out.setStrokingColor(Color.BLACK);
        out.setLineWidth(0.3f);
        out.addRect(x, bottom, 8, 8);
        out.stroke();
    }

    // ---- the carton list ----------------------------------------------------------------------------------------

    private static Table cartons(ResourceBundle t, Facts facts, List<Layout.Box> boxes, Numbers n) {
        List<List<Cell>> rows = new ArrayList<>();
        for (Layout.Box box : boxes) {
            // Whether the carton lies other than as measured: its placed sizes are not its length, width, height.
            String orientation = "LWH".equals(box.orientation())
                    ? t.getString("loading-sheet.pdf.orientation.asIs")
                    : t.getString("loading-sheet.pdf.orientation.turned").formatted(n.cm(box.dMm()), n.cm(box.wMm()),
                            n.cm(box.hMm()));
            rows.add(List.of(Cell.of(String.valueOf(box.loadSequence())), Cell.bold(box.label()),
                    Cell.of(facts.orderByRef().getOrDefault(box.ref(), "")), Cell.of(box.partName()),
                    Cell.of("%s×%s×%s".formatted(n.cm(box.origMm().l()), n.cm(box.origMm().w()),
                            n.cm(box.origMm().h()))),
                    Cell.of(n.number(box.weightKg())),
                    Cell.of("%s / %s / %s".formatted(n.cm(box.zMm()), n.cm(box.xMm()), n.cm(box.yMm()))),
                    Cell.of(orientation)));
        }
        BigDecimal total = boxes.stream().map(Layout.Box::weightKg).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Table(List.of(new Column(t.getString("loading-sheet.pdf.col.seq"), 24, Align.CENTRE),
                new Column(t.getString("loading-sheet.pdf.col.lpn"), 88, Align.LEFT),
                new Column(t.getString("loading-sheet.pdf.col.order"), 80, Align.LEFT),
                new Column(t.getString("loading-sheet.pdf.col.sku"), 120, Align.LEFT),
                new Column(t.getString("loading-sheet.pdf.col.size"), 42, Align.RIGHT),
                new Column(t.getString("loading-sheet.pdf.col.weight"), 24, Align.RIGHT),
                new Column(t.getString("loading-sheet.pdf.col.position"), 54, Align.RIGHT),
                new Column(t.getString("loading-sheet.pdf.col.orientation"), 64, Align.LEFT)), rows,
                List.of(new Summary(t.getString("loading-sheet.pdf.total").formatted(boxes.size()),
                        n.number(total), true)), 5);
    }

    private static void line(PDPageContentStream out, float x, float y, float width) throws IOException {
        out.setStrokingColor(RULE);
        out.setLineWidth(0.5f);
        out.moveTo(x, y);
        out.lineTo(x + width, y);
        out.stroke();
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Measures as a Vietnamese reader writes them - a comma before the decimals, a dot between thousands. Not the
     * kit's whole-number formats: a carton is 62,5 cm long and a load 11,52 m³. One for each sheet: a NumberFormat is
     * not safe to share between request threads.
     */
    private static final class Numbers {

        private final NumberFormat number = NumberFormat.getNumberInstance(VI);
        /** Volumes in cubic metres to two decimals: a small load must not print as 0,2 beside a 1 % bar. */
        private final NumberFormat volume = NumberFormat.getNumberInstance(VI);

        Numbers() {
            number.setMaximumFractionDigits(1);
            number.setMinimumFractionDigits(0);
            volume.setMaximumFractionDigits(2);
            volume.setMinimumFractionDigits(0);
        }

        String number(BigDecimal value) {
            return number.format(value);
        }

        String volume(BigDecimal value) {
            return volume.format(value);
        }

        String cm(int mm) {
            return number.format(mm / 10.0);
        }
    }
}
