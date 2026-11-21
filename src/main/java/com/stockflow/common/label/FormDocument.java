package com.stockflow.common.label;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The A4 layout of a printed form: a paper people work from - read across a counter, checked off, kept with the goods -
 * rather than a letter. Where {@link PrintedDocument} flows lines of text down the page, this lays out blocks: a
 * masthead, a title, a row of key facts in boxes, a boxed party, a table that runs over as many pages as it needs, a
 * list of requirements and a closing note, with "page x / N" on every page. The production order slip (4A-3) is drawn
 * with it; the documents that read as letters - quote, contract, delivery note, sandbox invoice - keep
 * {@link PrintedDocument}.
 *
 * <p>Same fonts (Noto Sans, which has the Vietnamese glyphs) and the same code drawing as every label and document, so
 * the scanners tuned on labels read the code here too: a QR in the masthead and a Code 128 barcode under the title, on
 * the first page only - the code identifies the paper, not each of its pages.</p>
 *
 * <p>Text is measured, not counted, and wrapped inside its box; a block that does not fit in what is left of a page
 * starts the next one. A table carries on over pages with its column header repeated, a row taller than a whole page is
 * cut between its lines, and a list of bullets carries on over pages, so the parts that can grow with the data never
 * run below the margin. A masthead, a title, a key-facts row, a box and a note are drawn whole: they are meant to stay
 * short, and one taller than a page would overflow it. Page numbers are drawn once every page exists, since the count
 * is not known before.</p>
 */
public final class FormDocument {

    /** One part of the form, drawn in the order given. */
    public sealed interface Block permits Masthead, Title, KeyFacts, Box, Table, Bullets, Note {
    }

    /** Our company's name at the left, vertically centred on the code's QR at the right; a blank name draws the QR only. */
    public record Masthead(String company) implements Block {
    }

    /** A centred title, a centred line under it (number and date), then the code's barcode, centred. */
    public record Title(String title, String line) implements Block {
    }

    /**
     * A box of the key-facts row: a grey heading, its value large and bold, an optional detail under it.
     *
     * @param share the fraction of the row's width this box takes; the shares of a row add up to 1
     */
    public record Fact(String label, String value, String detail, float share) {
    }

    /** The facts a reader needs at a glance, side by side, as tall as the tallest. */
    public record KeyFacts(List<Fact> facts) implements Block {
    }

    /** A full-width box: a grey heading row, the first line bold, the others regular. */
    public record Box(String heading, String firstLine, List<String> lines) implements Block {
    }

    /** @param width in points; a table's widths are scaled to the page's text width */
    public record Column(String heading, float width, boolean numeric) {
    }

    /** One cell: its text, bold when {@code bold}, and smaller grey lines under it. */
    public record Cell(String text, boolean bold, List<String> details) {
    }

    /** A table under a section heading; each row has one cell per column. */
    public record Table(String heading, List<Column> columns, List<List<Cell>> rows) implements Block {
    }

    /** A section heading and a framed list of items, one bullet each. */
    public record Bullets(String heading, List<String> items) implements Block {
    }

    /** A bold lead and its text, then an optional grey line. */
    public record Note(String lead, String text, String grey) implements Block {
    }

    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float MARGIN = 40;
    private static final float WIDTH = PAGE_WIDTH - 2 * MARGIN;
    /** The lowest a block may reach: above the footer's rule. */
    private static final float BOTTOM = MARGIN + 24;
    private static final float GAP = 14;
    private static final float BORDER = 0.6f;
    private static final float HEADING_ROW = 15;
    private static final Color SHADE = new Color(0xED, 0xED, 0xED);
    private static final Color MUTED = new Color(0x59, 0x59, 0x59);

    private static final double QR_MM = 22;
    private static final double BARCODE_WIDTH_MM = 62;
    private static final double BARCODE_HEIGHT_MM = 9;

    private static final float TABLE_SIZE = 8.5f;
    private static final float TABLE_DETAIL_SIZE = 8f;
    private static final float TABLE_LEADING = 11.5f;
    private static final float TEXT_SIZE = 9;
    private static final float TEXT_LEADING = 12;

    private FormDocument() {
    }

    /**
     * @param title     the PDF's own title
     * @param code      drawn as the masthead's QR and the title's barcode on page 1, or null for neither; [A-Z0-9-]
     * @param footer    left of every page's footer
     * @param pageLabel right of every page's footer: a format taking the page and the page count, "Trang %d / %d"
     */
    public static byte[] draw(LabelFonts fonts, List<Block> blocks, String title, String code, String footer,
                              String pageLabel) {
        try (PDDocument document = new PDDocument()) {
            Pen pen = new Pen(document, fonts, code);
            try {
                for (Block block : blocks) {
                    switch (block) {
                        case Masthead m -> pen.masthead(m);
                        case Title t -> pen.title(t);
                        case KeyFacts k -> pen.keyFacts(k);
                        case Box b -> pen.box(b);
                        case Table t -> pen.table(t);
                        case Bullets b -> pen.bullets(b);
                        case Note n -> pen.note(n);
                    }
                }
            } finally {
                pen.close();
            }
            pen.footers(footer, pageLabel);
            document.getDocumentInformation().setTitle(fonts.drawable(title));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not draw " + title, ex);
        }
    }

    /** The drawing state: the current page and how far down it the next block starts. */
    private static final class Pen {

        private final PDDocument document;
        private final LabelFonts fonts;
        private final String code;
        private final PDFont regular;
        private final PDFont bold;
        private PDPageContentStream out;
        private float y;

        Pen(PDDocument document, LabelFonts fonts, String code) throws IOException {
            this.document = document;
            this.fonts = fonts;
            this.code = code;
            this.regular = PDType0Font.load(document, new ByteArrayInputStream(fonts.regular()), true);
            this.bold = PDType0Font.load(document, new ByteArrayInputStream(fonts.bold()), true);
            newPage();
        }

        // ---- blocks ------------------------------------------------------------------------------------------------

        void masthead(Masthead m) throws IOException {
            float qr = LabelDrawing.points(QR_MM);
            boolean hasCode = code != null && document.getNumberOfPages() == 1;
            String company = blank(m.company()) ? null : m.company();
            if (!hasCode && company == null) {
                return;
            }
            // A Vietnamese legal name runs to 60-80 letters and more: it wraps short of the QR, never over it, or the
            // QR would no longer scan.
            List<String> name = company == null ? List.of()
                    : wrap(bold, 12, company, hasCode ? WIDTH - qr - 12 : WIDTH);
            float text = name.size() * 15;
            float height = Math.max(hasCode ? qr : 0, Math.max(text, 20));
            if (hasCode) {
                LabelDrawing.qr(out, pageCell(), code, LabelDrawing.millimetres(PAGE_WIDTH - MARGIN - qr),
                        LabelDrawing.millimetres(PAGE_HEIGHT - y), QR_MM);
            }
            float line = y - (height - text) / 2 - 11;
            for (String part : name) {
                text(bold, 12, MARGIN, line, part);
                line -= 15;
            }
            y -= height + 6;
            rule(MARGIN, y, WIDTH, 0.8f);
            y -= GAP;
        }

        void title(Title t) throws IOException {
            boolean barcode = code != null && document.getNumberOfPages() == 1;
            float height = 30 + (blank(t.line()) ? 0 : 16) + (barcode ? LabelDrawing.points(BARCODE_HEIGHT_MM) + 6 : 0);
            ensure(height);
            y -= 18;
            centred(bold, 15, PAGE_WIDTH / 2, y, t.title());
            if (!blank(t.line())) {
                y -= 16;
                centred(bold, 10, PAGE_WIDTH / 2, y, t.line());
            }
            if (barcode) {
                y -= 6;
                LabelDrawing.centredBarcode(out, pageCell(), code,
                        (LabelDrawing.millimetres(PAGE_WIDTH) - BARCODE_WIDTH_MM) / 2,
                        LabelDrawing.millimetres(PAGE_HEIGHT - y), BARCODE_WIDTH_MM, BARCODE_HEIGHT_MM);
                y -= LabelDrawing.points(BARCODE_HEIGHT_MM);
            }
            y -= GAP;
        }

        void keyFacts(KeyFacts k) throws IOException {
            // Both the value and its detail wrap inside their own box - a warehouse's name runs to 200 letters - and
            // the row is as tall as its tallest box, each box's content centred in it.
            List<Float> widths = new ArrayList<>();
            List<List<String>> values = new ArrayList<>();
            List<List<String>> details = new ArrayList<>();
            List<Float> sizes = new ArrayList<>();
            List<Float> contents = new ArrayList<>();
            float tallest = 0;
            for (Fact fact : k.facts()) {
                float width = WIDTH * fact.share();
                widths.add(width);
                List<String> detail = blank(fact.detail()) ? List.of()
                        : wrap(regular, 8.5f, fact.detail(), width - 12);
                float size = detail.isEmpty() ? 13 : 11;
                List<String> value = wrap(bold, size, fact.value(), width - 12);
                float content = value.size() * (size + 3) + (detail.isEmpty() ? 0 : 2 + detail.size() * 11);
                values.add(value);
                details.add(detail);
                sizes.add(size);
                contents.add(content);
                tallest = Math.max(tallest, content);
            }
            float body = Math.max(25, tallest + 16);
            float height = HEADING_ROW + body;
            ensure(height);
            float x = MARGIN;
            for (int i = 0; i < k.facts().size(); i++) {
                Fact fact = k.facts().get(i);
                float width = widths.get(i);
                float size = sizes.get(i);
                shade(x, y - HEADING_ROW, width, HEADING_ROW);
                frame(x, y - height, width, height);
                rule(x, y - HEADING_ROW, width, 0.5f);
                centred(bold, 8.5f, x + width / 2, y - 11, fact.label());
                float centre = x + width / 2;
                float top = y - HEADING_ROW - (body - contents.get(i)) / 2;
                float line = top;
                for (String part : values.get(i)) {
                    line -= size + 3;
                    centred(bold, size, centre, line + 3, part);
                }
                line -= 2;
                for (String part : details.get(i)) {
                    line -= 11;
                    centred(regular, 8.5f, centre, line + 2.5f, part);
                }
                x += width;
            }
            y -= height + GAP;
        }

        void box(Box b) throws IOException {
            List<Styled> lines = new ArrayList<>();
            for (String part : wrap(bold, TEXT_SIZE, b.firstLine(), WIDTH - 12)) {
                lines.add(new Styled(bold, part));
            }
            for (String line : b.lines()) {
                if (!blank(line)) {
                    for (String part : wrap(regular, TEXT_SIZE, line, WIDTH - 12)) {
                        lines.add(new Styled(regular, part));
                    }
                }
            }
            float height = HEADING_ROW + 4 + lines.size() * TEXT_LEADING + 8;
            ensure(height);
            shade(MARGIN, y - HEADING_ROW, WIDTH, HEADING_ROW);
            frame(MARGIN, y - height, WIDTH, height);
            rule(MARGIN, y - HEADING_ROW, WIDTH, 0.5f);
            text(bold, TEXT_SIZE, MARGIN + 6, y - 11, b.heading());
            float line = y - HEADING_ROW - 4;
            for (Styled part : lines) {
                line -= TEXT_LEADING;
                text(part.font(), TEXT_SIZE, MARGIN + 6, line, part.text());
            }
            y -= height + GAP;
        }

        void table(Table t) throws IOException {
            float total = (float) t.columns().stream().mapToDouble(Column::width).sum();
            float[] widths = new float[t.columns().size()];
            for (int i = 0; i < widths.length; i++) {
                widths[i] = t.columns().get(i).width() * WIDTH / total;
            }
            List<List<Styled>> header = new ArrayList<>();
            for (int i = 0; i < widths.length; i++) {
                List<Styled> lines = new ArrayList<>();
                for (String part : wrap(bold, TABLE_SIZE, t.columns().get(i).heading(), widths[i] - 8)) {
                    lines.add(new Styled(bold, part));
                }
                header.add(lines);
            }
            float headerHeight = rowHeight(header.stream().mapToInt(List::size).max().orElse(1));
            ensure(14 + 6 + headerHeight + rowHeight(1));
            y -= 10;
            text(bold, 10, MARGIN, y, t.heading());
            y -= 6;
            headerRow(widths, header, headerHeight);
            for (List<Cell> row : t.rows()) {
                List<List<Styled>> cells = new ArrayList<>();
                for (int i = 0; i < widths.length; i++) {
                    cells.add(cellLines(row.get(i), widths[i] - 8));
                }
                int count = Math.max(1, cells.stream().mapToInt(List::size).max().orElse(1));
                int from = 0;
                while (from < count) {
                    int room = (int) Math.floor((y - BOTTOM - 8) / TABLE_LEADING);
                    int remaining = count - from;
                    // A row that fits on a fresh page moves there whole; only a row taller than a page is cut.
                    boolean wholeOnFreshPage = rowHeight(remaining) <= freshRoom() - headerHeight;
                    if (room < 1 || (room < remaining && from == 0 && wholeOnFreshPage)) {
                        newPage();
                        headerRow(widths, header, headerHeight);
                        continue;
                    }
                    int to = from + Math.min(room, remaining);
                    rowSlice(t, widths, cells, from, to);
                    from = to;
                }
            }
            y -= GAP;
        }

        void bullets(Bullets b) throws IOException {
            List<List<String>> items = new ArrayList<>();
            for (String item : b.items()) {
                if (!blank(item)) {
                    items.add(wrap(regular, TEXT_SIZE, item, WIDTH - 26));
                }
            }
            float first = items.isEmpty() ? 0 : items.getFirst().size() * TEXT_LEADING;
            ensure(10 + 4 + 6 + first + 7);
            y -= 10;
            text(bold, 10, MARGIN, y, b.heading());
            y -= 4;
            float top = y;
            y -= 6;
            for (List<String> item : items) {
                boolean bullet = true;
                for (String part : item) {
                    if (y - TEXT_LEADING < BOTTOM + 7) {
                        frame(MARGIN, y - 7, WIDTH, top - (y - 7));
                        newPage();
                        top = y;
                        y -= 6;
                    }
                    y -= TEXT_LEADING;
                    if (bullet) {
                        text(regular, TEXT_SIZE, MARGIN + 6, y, "•");
                    }
                    text(regular, TEXT_SIZE, MARGIN + 16, y, part);
                    bullet = false;
                }
            }
            y -= 7;
            frame(MARGIN, y, WIDTH, top - y);
            y -= GAP;
        }

        void note(Note n) throws IOException {
            float leadWidth = width(bold, TEXT_SIZE, n.lead()) + 3;
            List<String> rest = blank(n.text()) ? List.of() : wrap(regular, TEXT_SIZE, n.text(), WIDTH - leadWidth);
            List<String> grey = blank(n.grey()) ? List.of() : wrap(regular, 8.5f, n.grey(), WIDTH);
            ensure(Math.max(1, rest.size()) * TEXT_LEADING + grey.size() * 11 + 4);
            y -= TEXT_LEADING;
            text(bold, TEXT_SIZE, MARGIN, y, n.lead());
            for (int i = 0; i < rest.size(); i++) {
                if (i > 0) {
                    y -= TEXT_LEADING;
                }
                text(regular, TEXT_SIZE, MARGIN + leadWidth, y, rest.get(i));
            }
            out.setNonStrokingColor(MUTED);
            for (String part : grey) {
                y -= 11;
                text(regular, 8.5f, MARGIN, y, part);
            }
            out.setNonStrokingColor(Color.BLACK);
            y -= GAP;
        }

        // ---- table rows --------------------------------------------------------------------------------------------

        private List<Styled> cellLines(Cell cell, float width) throws IOException {
            List<Styled> lines = new ArrayList<>();
            PDFont font = cell.bold() ? bold : regular;
            if (!blank(cell.text())) {
                for (String part : wrap(font, TABLE_SIZE, cell.text(), width)) {
                    lines.add(new Styled(font, part));
                }
            }
            for (String detail : cell.details()) {
                if (!blank(detail)) {
                    for (String part : wrap(regular, TABLE_DETAIL_SIZE, detail, width)) {
                        lines.add(new Styled(null, part));
                    }
                }
            }
            return lines;
        }

        private void headerRow(float[] widths, List<List<Styled>> header, float height) throws IOException {
            shade(MARGIN, y - height, WIDTH, height);
            frame(MARGIN, y - height, WIDTH, height);
            float x = MARGIN;
            for (int i = 0; i < widths.length; i++) {
                if (i > 0) {
                    column(x, y - height, height);
                }
                float line = y - 4 - TABLE_SIZE;
                for (Styled part : header.get(i)) {
                    centred(bold, TABLE_SIZE, x + widths[i] / 2, line, part.text());
                    line -= TABLE_LEADING;
                }
                x += widths[i];
            }
            y -= height;
        }

        private void rowSlice(Table t, float[] widths, List<List<Styled>> cells, int from, int to) throws IOException {
            float height = rowHeight(to - from);
            frame(MARGIN, y - height, WIDTH, height);
            float x = MARGIN;
            for (int i = 0; i < widths.length; i++) {
                if (i > 0) {
                    column(x, y - height, height);
                }
                boolean numeric = t.columns().get(i).numeric();
                List<Styled> lines = cells.get(i);
                float line = y - 4 - TABLE_SIZE;
                for (int k = from; k < Math.min(to, lines.size()); k++) {
                    Styled part = lines.get(k);
                    boolean detail = part.font() == null;
                    PDFont font = detail ? regular : part.font();
                    float size = detail ? TABLE_DETAIL_SIZE : TABLE_SIZE;
                    if (detail) {
                        out.setNonStrokingColor(MUTED);
                    }
                    if (numeric) {
                        text(font, size, x + widths[i] - 4 - width(font, size, part.text()), line, part.text());
                    } else {
                        text(font, size, x + 4, line, part.text());
                    }
                    out.setNonStrokingColor(Color.BLACK);
                    line -= TABLE_LEADING;
                }
                x += widths[i];
            }
            y -= height;
        }

        private static float rowHeight(int lines) {
            return lines * TABLE_LEADING + 8;
        }

        private static float freshRoom() {
            return PAGE_HEIGHT - MARGIN - BOTTOM;
        }

        // ---- pages -------------------------------------------------------------------------------------------------

        /** Starts a new page unless {@code height} still fits above the bottom of this one. */
        private void ensure(float height) throws IOException {
            if (y - height < BOTTOM && y < PAGE_HEIGHT - MARGIN) {
                newPage();
            }
        }

        private void newPage() throws IOException {
            if (out != null) {
                out.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            out = new PDPageContentStream(document, page);
            y = PAGE_HEIGHT - MARGIN;
        }

        void close() throws IOException {
            if (out != null) {
                out.close();
                out = null;
            }
        }

        /** "page x / N" and the footer text on every page, once the count is known. */
        void footers(String footer, String pageLabel) throws IOException {
            int pages = document.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                try (PDPageContentStream foot = new PDPageContentStream(document, document.getPage(i),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    out = foot;
                    out.setStrokingColor(MUTED);
                    rule(MARGIN, MARGIN + 12, WIDTH, 0.4f);
                    out.setNonStrokingColor(MUTED);
                    if (!blank(footer)) {
                        text(regular, 7.5f, MARGIN, MARGIN, footer);
                    }
                    String label = pageLabel.formatted(i + 1, pages);
                    text(regular, 7.5f, MARGIN + WIDTH - width(regular, 7.5f, label), MARGIN, label);
                }
            }
            out = null;
        }

        // ---- primitives --------------------------------------------------------------------------------------------

        private LabelDrawing.Cell pageCell() {
            double width = LabelDrawing.millimetres(PAGE_WIDTH);
            double height = LabelDrawing.millimetres(PAGE_HEIGHT);
            return new LabelDrawing.Cell(0, 0, width, height, height);
        }

        private void text(PDFont font, float size, float x, float baseline, String text) throws IOException {
            out.beginText();
            out.setFont(font, size);
            out.newLineAtOffset(x, baseline);
            out.showText(fonts.drawable(text));
            out.endText();
        }

        private void centred(PDFont font, float size, float centre, float baseline, String text) throws IOException {
            text(font, size, centre - width(font, size, text) / 2, baseline, text);
        }

        private void shade(float x, float bottom, float width, float height) throws IOException {
            out.setNonStrokingColor(SHADE);
            out.addRect(x, bottom, width, height);
            out.fill();
            out.setNonStrokingColor(Color.BLACK);
        }

        private void frame(float x, float bottom, float width, float height) throws IOException {
            out.setLineWidth(BORDER);
            out.addRect(x, bottom, width, height);
            out.stroke();
        }

        private void rule(float x, float at, float width, float thickness) throws IOException {
            out.setLineWidth(thickness);
            out.moveTo(x, at);
            out.lineTo(x + width, at);
            out.stroke();
        }

        private void column(float x, float bottom, float height) throws IOException {
            out.setLineWidth(BORDER);
            out.moveTo(x, bottom);
            out.lineTo(x, bottom + height);
            out.stroke();
        }

        private float width(PDFont font, float size, String text) throws IOException {
            return font.getStringWidth(fonts.drawable(text)) / 1000 * size;
        }

        /**
         * {@code text} cut into lines no wider than {@code width} in {@code font} at {@code size}: at spaces, and
         * inside a word only when the word alone is wider than the line (a checksum, a long code) - after a hyphen of
         * it when there is one.
         */
        private List<String> wrap(PDFont font, float size, String text, float width) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : fonts.drawable(text == null ? "" : text).strip().split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(font, size, candidate) <= width) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                String rest = word;
                while (width(font, size, rest) > width && rest.length() > 1) {
                    int cut = rest.length() - 1;
                    while (cut > 1 && width(font, size, rest.substring(0, cut)) > width) {
                        cut--;
                    }
                    // A code (a SKU, an LPN) reads better cut after one of its hyphens than in the middle of a part.
                    int hyphen = rest.lastIndexOf('-', cut - 1);
                    if (hyphen > 0) {
                        cut = hyphen + 1;
                    }
                    lines.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                line.append(rest);
            }
            if (!line.isEmpty() || lines.isEmpty()) {
                lines.add(line.toString());
            }
            return lines;
        }
    }

    /** A line of text and the font it is drawn in; a null font is a grey detail line. */
    private record Styled(PDFont font, String text) {
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
