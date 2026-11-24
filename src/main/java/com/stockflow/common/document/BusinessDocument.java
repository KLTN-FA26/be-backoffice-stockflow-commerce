package com.stockflow.common.document;

import com.stockflow.common.label.LabelDrawing;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The A4 layout of a business document: a paper one company sends another and both may sign - a quotation, a sales
 * contract, a record of handover, an accounting voucher. It is drawn from blocks, top to bottom: one of three heads, a
 * title, paragraphs, labelled fields, tables, numbered clauses, figures and signature blocks side by side, with the
 * document's number and "page x/y" at the foot of every page.
 *
 * <p>Why a third layout beside {@link com.stockflow.common.label.PrintedDocument} (lines of text) and
 * {@link com.stockflow.common.label.FormDocument} (a form worked from at a counter): neither can set what Vietnamese
 * practice expects of a paper that is signed. Nghị định 30/2020/NĐ-CP (Phụ lục I) fixes the page - A4, margins of
 * 20-25 mm top and bottom, 30-35 mm left, 15-20 mm right, a serif at 13-14 pt, italic for legal bases and for the
 * place and date, body text justified - and practice fixes the rest: money in a ruled table with figures aligned
 * right, the total in words, two signatories side by side with room for a seal. Faking those with spaces in a line of
 * text is what this replaces.</p>
 *
 * <p>The three heads are the three families of paper: {@link NationalHeader} (Quốc hiệu and Tiêu ngữ) opens what two
 * parties sign - contracts, records; {@link BrandHeader} (logo, name, tax code, contacts) opens commercial papers - a
 * quotation, a debt notice; {@link VoucherHeader} ("Đơn vị ... / Mẫu số ...") opens an accounting voucher after
 * Thông tư 99/2025/TT-BTC.</p>
 *
 * <p>Nothing here knows a language or a company: every word is given by the caller, who takes it from the Vietnamese
 * bundle. Text is measured, never counted; a paragraph, a table and a field list carry on over pages, a table repeats
 * its header, and a heading, a signature block or a table's header never stays alone at the foot of a page - a table
 * starts with its first row, or the first lines of a tall one, under its header, the sentence introducing it and the
 * heading over them. Nor does a document end on a page that is all but empty: {@link #draw} sets it tighter to save
 * that page, or shares the content out over the last two.</p>
 */
public final class BusinessDocument {

    public enum Align { LEFT, CENTRE, RIGHT, JUSTIFY }

    /** A stretch of text in one style; a paragraph is several, so one sentence can name a party in bold. */
    public record Run(String text, Style style) {

        public static Run of(String text) {
            return new Run(text, Style.REGULAR);
        }

        public static Run bold(String text) {
            return new Run(text, Style.BOLD);
        }

        public static Run italic(String text) {
            return new Run(text, Style.ITALIC);
        }
    }

    /** In points. */
    public record Margins(float top, float bottom, float left, float right) {
    }

    /**
     * How a document's pages are set.
     *
     * @param size         the body text, in points
     * @param tableSize    the text inside tables: a goods table has eight columns and cannot afford the body size
     * @param accent       the one brand colour - title, head rule, table header tint - or null for a paper in black
     *                     alone: a contract or a voucher carries no colour
     * @param footerLeft   left of every page's foot: the document's number, so a page pulled from a stapled contract
     *                     still says which contract it belongs to
     * @param footerCentre centre of the foot, in grey: the company
     * @param pageLabel    right of the foot, a format taking the page and the page count: "Trang %d/%d"
     * @param watermark    written large and pale across every page ("BẢN NHÁP"), or null
     */
    public record Layout(Margins margins, float size, float tableSize, Color accent, String footerLeft,
                         String footerCentre, String pageLabel, String watermark) {

        /** What two parties sign: the margins and the 13 pt of Nghị định 30/2020/NĐ-CP, in black. */
        public static Layout official(String footerLeft, String footerCentre, String pageLabel, String watermark) {
            return new Layout(new Margins(mm(20), mm(20), mm(30), mm(15)), 13, 11, null, footerLeft, footerCentre,
                    pageLabel, watermark);
        }

        /**
         * A commercial paper or a voucher: tighter margins and smaller text than a decree asks of an official one - it
         * is read at a desk, and a quotation that fits one page is the one that gets read - with the brand colour.
         */
        public static Layout commercial(Color accent, String footerLeft, String footerCentre, String pageLabel,
                                        String watermark) {
            return new Layout(new Margins(mm(15), mm(15), mm(20), mm(15)), 11.5f, 10.5f, accent, footerLeft,
                    footerCentre, pageLabel, watermark);
        }
    }

    public sealed interface Block permits NationalHeader, BrandHeader, VoucherHeader, Title, Paragraph, Split, Fields,
            Heading, Table, Signatures, Spacer, Figure, PageBreak, Together {
    }

    /**
     * Quốc hiệu and Tiêu ngữ, with a rule under the motto as long as the motto.
     *
     * <p>With an {@code issuer}, the two-column head of a record ("biên bản"): the company and the paper's number at
     * the left, the nation, the motto and the place and date at the right. Without one, the nation and the motto
     * centred over the whole page, as a contract opens.</p>
     *
     * @param issuer    the company issuing the paper, or null for the centred head
     * @param number    "Số: ..." under the issuer; ignored without one
     * @param placeDate "Thành phố Hồ Chí Minh, ngày 23 tháng 11 năm 2026", italic under the motto; may be null
     */
    public record NationalHeader(String nation, String motto, String issuer, String number, String placeDate)
            implements Block {
    }

    /**
     * The company's own head on a commercial paper: its logo at the left, its name in the brand colour and its lines
     * (address, tax code, contacts) beside it, and a rule in the brand colour under both.
     *
     * @param logo a PNG, or null for a company without one: the name then starts at the margin
     */
    public record BrandHeader(byte[] logo, String name, List<String> lines) implements Block {
    }

    /**
     * The head of an accounting voucher: the unit and its address at the left, the form's number and the circular it
     * comes with at the right ("Mẫu số: 02 - VT", "(Kèm theo Thông tư số 99/2025/TT-BTC ...)").
     */
    public record VoucherHeader(String unit, String address, String form, String basis) implements Block {
    }

    /**
     * The paper's name in capitals, centred, with up to two centred lines under it.
     *
     * @param subtitle bold, e.g. the number ("Số: 045/2026/HĐMB"); may be null
     * @param note     italic, e.g. "(V/v in ly giấy theo thiết kế)"; may be null
     */
    public record Title(String title, String subtitle, String note) implements Block {
    }

    /**
     * A paragraph of running text.
     *
     * @param firstIndent how far the first line starts in from the others
     * @param indent      how far the whole paragraph starts in from the margin
     * @param marker      drawn at {@code indent}, the text hanging beside it ("1.", "a)", "-"); null for none
     * @param after       the space left under it
     * @param sizeDelta   added to the layout's body size: -1 for a note in smaller print
     */
    public record Paragraph(List<Run> runs, Align align, float firstIndent, float indent, String marker, float after,
                            float sizeDelta) implements Block {

        /** Justified, as Nghị định 30 sets body text. */
        public static Paragraph of(Run... runs) {
            return new Paragraph(List.of(runs), Align.JUSTIFY, 0, 0, null, 4, 0);
        }

        public static Paragraph of(String text) {
            return of(Run.of(text));
        }

        public static Paragraph bold(String text) {
            return of(Run.bold(text));
        }

        /** A legal basis, a note under a figure: italic. */
        public static Paragraph italic(String text) {
            return of(Run.italic(text));
        }

        /** A clause's sub-point: its number at the margin, the text hanging beside it. */
        public static Paragraph numbered(String marker, Run... runs) {
            return new Paragraph(List.of(runs), Align.JUSTIFY, 0, 0, marker, 4, 0);
        }

        public Paragraph align(Align to) {
            return new Paragraph(runs, to, firstIndent, indent, marker, after, sizeDelta);
        }

        public Paragraph firstIndent(float by) {
            return new Paragraph(runs, align, by, indent, marker, after, sizeDelta);
        }

        public Paragraph indent(float by) {
            return new Paragraph(runs, align, firstIndent, by, marker, after, sizeDelta);
        }

        public Paragraph after(float space) {
            return new Paragraph(runs, align, firstIndent, indent, marker, space, sizeDelta);
        }

        public Paragraph size(float delta) {
            return new Paragraph(runs, align, firstIndent, indent, marker, after, delta);
        }
    }

    /** One line with a left end and a right end: the number at the left, the place and date at the right. */
    public record Split(List<Run> left, List<Run> right) implements Block {
    }

    /** A labelled value: "Địa chỉ : ...". */
    public record Field(String label, List<Run> value) {

        public static Field of(String label, String value) {
            return new Field(label, List.of(Run.of(value)));
        }
    }

    /**
     * Labelled values under one another, their colons in one column - the block that names a party. A value wraps
     * beside its label, never under it.
     */
    public record Fields(List<Field> fields, float indent) implements Block {
    }

    /** A section or clause heading, bold; never left alone at the foot of a page. */
    public record Heading(String text) implements Block {
    }

    /** @param weight the column's share of the table's width; the weights of a table are scaled to the text width */
    public record Column(String heading, float weight, Align align) {
    }

    /**
     * One cell: its text and smaller lines under it (a product's specification under its name).
     *
     * @param align null for the column's own
     */
    public record Cell(String text, Style style, List<String> details, Align align) {

        public static Cell of(String text) {
            return new Cell(text, Style.REGULAR, List.of(), null);
        }

        public static Cell bold(String text) {
            return new Cell(text, Style.BOLD, List.of(), null);
        }

        public Cell details(List<String> lines) {
            return new Cell(text, style, lines, align);
        }

        public Cell align(Align to) {
            return new Cell(text, style, details, to);
        }
    }

    /** A row under the body of a table: its label across the columns left of the value's, right-aligned. */
    public record Summary(String label, String value, boolean bold) {
    }

    /**
     * A ruled table: a tinted header row repeated on every page it runs onto, rows that wrap inside their columns, and
     * summary rows (subtotal, VAT, total) under the body.
     *
     * @param valueColumn the column the summaries' values are written in (the amount column); ignored without summaries
     */
    public record Table(List<Column> columns, List<List<Cell>> rows, List<Summary> summaries, int valueColumn)
            implements Block {

        public Table(List<Column> columns, List<List<Cell>> rows) {
            this(columns, rows, List.of(), 0);
        }
    }

    /**
     * Who signs under one title.
     *
     * @param title    in capitals: "ĐẠI DIỆN BÊN A"
     * @param position the signatory's own title in capitals under it ("GIÁM ĐỐC"), as a company's papers head the
     *                 place its director signs; null where it is not known or the block is for anyone to sign
     * @param caption  italic under them: "(Ký, ghi rõ họ tên, đóng dấu)"
     * @param name     bold under the room left for the signature; null leaves the line for a name written by hand
     */
    public record Signer(String title, String position, String caption, String name) {

        /** A block headed by its title alone. */
        public Signer(String title, String caption, String name) {
            this(title, null, caption, name);
        }
    }

    /**
     * Signature blocks side by side, drawn whole on one page with room to sign and seal.
     *
     * @param dateLine italic over the last block ("..., ngày ... tháng ... năm ..."); may be null
     * @param room     the height left to sign in, in points; less than a seal's for blocks signed by hand on a
     *                 paper that has to stay on one page
     */
    public record Signatures(String dateLine, List<Signer> signers, float room) implements Block {

        /** With room for a signature and a company's seal over it, as a paper two companies sign needs. */
        public Signatures(String dateLine, List<Signer> signers) {
            this(dateLine, signers, SIGNING_ROOM);
        }
    }

    public record Spacer(float height) implements Block {
    }

    /**
     * What follows starts a new page - unless the page is still empty. For a paper whose signing part belongs on one
     * page: whoever signs a handover should have what they attest to - the vehicle, the state of the goods - on the
     * page they sign, not half of it on the page before.
     */
    public record PageBreak() implements Block {
    }

    /**
     * What follows, for {@code height} points, starts a new page unless that much still fits on this one. It takes no
     * room itself. For a part of a paper that is read and signed as one - a handover's vehicle, state of the goods,
     * confirmation and signatures - and that the rules between two blocks (a paragraph and the signatures after it)
     * would otherwise cut wherever the page ends.
     */
    public record Together(float height) implements Block {
    }

    /**
     * Where a {@link Figure} is drawn: a box of the page's text width, and the pen to draw in it with. Coordinates are
     * the page's own, in points from its lower left corner; {@code top()} is the box's upper edge.
     */
    public interface Canvas {

        float left();

        float top();

        float width();

        float height();

        /** The page's own stream, for shapes: a diagram's rectangles, a bar. Its colours are reset after the figure. */
        PDPageContentStream stream();

        void text(Style style, float size, float x, float baseline, String text, Color colour) throws IOException;

        float textWidth(Style style, float size, String text) throws IOException;

        /** {@code text} shortened with an ellipsis until it fits {@code room}. */
        String cut(Style style, float size, String text, float room) throws IOException;
    }

    /** Draws a figure into its box. */
    @FunctionalInterface
    public interface Painter {

        void paint(Canvas canvas) throws IOException;
    }

    /**
     * A drawing the document carries between its blocks of text - a loading diagram, a fill bar, a barcode - kept
     * whole on one page. Why a block and not a document of its own: a paper with a diagram still needs the company's
     * head, the page numbers and the signature blocks every other paper has, and a second layout to give it those is
     * what this kit replaced.
     *
     * @param height the room it takes, in points
     */
    public record Figure(float height, Painter painter) implements Block {
    }

    /**
     * The document's code as a barcode at the left and a QR code at the right: what a scanner at the dock reads from a
     * paper that comes back with the goods. {@code code} is [A-Z0-9-], as the label symbols are.
     */
    public static Figure codes(String code) {
        // 20 mm, the side the label layouts give a QR code: smaller, the modules of a long code fall under what a
        // camera reading the whole page resolves.
        float height = mm(20);
        return new Figure(height + 4, canvas -> {
            double pageMm = LabelDrawing.millimetres(PAGE_HEIGHT);
            var page = new LabelDrawing.Cell(0, 0, LabelDrawing.millimetres(PAGE_WIDTH), pageMm, pageMm);
            double topMm = pageMm - LabelDrawing.millimetres(canvas.top());
            double leftMm = LabelDrawing.millimetres(canvas.left());
            canvas.stream().setNonStrokingColor(Color.BLACK);
            LabelDrawing.barcode(canvas.stream(), page, code, leftMm, topMm + 4, 90, 12);
            LabelDrawing.qr(canvas.stream(), page, code,
                    leftMm + LabelDrawing.millimetres(canvas.width()) - 20, topMm, 20);
        });
    }

    /**
     * The two codes of {@link #codes} set into a record's head instead of a row of their own: the barcode under the
     * paper's number at the left, the QR code under the place and date at the right, beside the title that follows.
     * For a paper that has to fit one page - a delivery note signed at a tailgate - where a row of 20 mm for the codes
     * alone is what pushes the signatures onto a second one. To go straight after {@link NationalHeader} and before
     * {@link Title}: it takes the height of the barcode only, and the QR code hangs down beside the title, which is
     * centred and leaves the margin's side of its lines free.
     */
    public static Figure headCodes(String code) {
        float bar = mm(8);
        return new Figure(bar - 2, canvas -> {
            double pageMm = LabelDrawing.millimetres(PAGE_HEIGHT);
            var page = new LabelDrawing.Cell(0, 0, LabelDrawing.millimetres(PAGE_WIDTH), pageMm, pageMm);
            // The head leaves 12 pt under its last line; the codes start inside that gap, not after it.
            double topMm = pageMm - LabelDrawing.millimetres(canvas.top() + 5);
            double leftMm = LabelDrawing.millimetres(canvas.left());
            canvas.stream().setNonStrokingColor(Color.BLACK);
            // As wide as the row of its own gave it up to the QR code: under 0.25 mm a module a worn scanner at a
            // dock starts to miss.
            LabelDrawing.barcode(canvas.stream(), page, code, leftMm, topMm, 90, LabelDrawing.millimetres(bar));
            LabelDrawing.qr(canvas.stream(), page, code,
                    leftMm + LabelDrawing.millimetres(canvas.width()) - 20, topMm, 20);
        });
    }

    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float BORDER = 0.5f;
    private static final float CELL_PAD = 3.5f;
    /** A tall table row cut over pages keeps at least this many lines on the page it starts on... */
    private static final int TALL_ROW_HERE = 4;
    /** ...and at least this many on the next. */
    private static final int TALL_ROW_NEXT = 2;
    /** A table of at most this many rows is kept whole on a page where it is no more than a third of one. */
    private static final int SHORT_TABLE = 6;
    /** Room to sign and to seal: 25 to 30 mm between the caption and the name. */
    private static final float SIGNING_ROOM = mm(27);
    /** The body text is never set smaller than this, however a document is tightened to end well. */
    private static final float SMALLEST_BODY = 11;
    private static final float SMALLEST_TABLE = 9.5f;
    /** A last page filled less than this far down is worth trying to save by setting the document tighter. */
    private static final float SPARE_PAGE = 0.6f;
    /** A last page filled less than this far down is one a document should not end on. */
    private static final float THIN_PAGE = 0.3f;
    /** How far down its last page a document ends once its content is shared out over the last two. */
    private static final float FAIR_SHARE = 0.42f;
    private static final Color GREY_SHADE = new Color(0xEC, 0xEC, 0xEC);
    private static final Color MUTED = new Color(0x55, 0x55, 0x55);
    private static final Color WATERMARK = new Color(0x80, 0x80, 0x80);

    private BusinessDocument() {
    }

    /** Millimetres in points. */
    public static float mm(double millimetres) {
        return (float) (millimetres * 72 / 25.4);
    }

    /**
     * How tightly a document is set: nothing of its content, only the measures the page count hangs on.
     *
     * @param sizeDrop  points taken off the body text, never to below {@link #SMALLEST_BODY}
     * @param tableDrop points taken off the text in tables, never to below {@link #SMALLEST_TABLE}
     * @param line      a line's height as a multiple of its text's size
     * @param room      the most left to sign in, in points
     * @param heldPage  the page (from 1) that ends {@code held} points early, or 0 for none
     */
    private record Fit(float sizeDrop, float tableDrop, float line, float room, int heldPage, float held) {

        /** As the layout says: 1.25 lines keeps 13 pt Vietnamese accents well apart. */
        private static final Fit LOOSE = new Fit(0, 0, 1.25f, Float.MAX_VALUE, 0, 0);

        /**
         * Tighter and tighter, all of it inside what Nghị định 30 allows of a page (single line spacing and up) and
         * what stays easy to read: the line spacing first, then the text half a point at a time.
         */
        private static final List<Fit> TIGHTER = List.of(new Fit(0, 0, 1.2f, mm(23), 0, 0),
                new Fit(0.5f, 0.5f, 1.2f, mm(22), 0, 0), new Fit(1, 0.5f, 1.17f, mm(21), 0, 0),
                new Fit(1.5f, 1, 1.15f, mm(20), 0, 0));

        Fit holding(int page, float points) {
            return new Fit(sizeDrop, tableDrop, line, room, page, points);
        }
    }

    /** A drawing: the file, how many pages it took and how far down the last one its content ends, from 0 to 1. */
    private record Drawn(byte[] pdf, int pages, float lastFilled) {

        /** Whether the last page holds a signature block or a few lines and little else. */
        boolean endsThin() {
            return pages > 1 && lastFilled < THIN_PAGE;
        }

        /** Whether the last page holds so little that setting the document tighter may well save it. */
        boolean mayEndSooner() {
            return pages > 1 && lastFilled < SPARE_PAGE;
        }
    }

    /**
     * Draws the document so that it ends well. A paper whose last page holds the signatures and two lines is a paper
     * that looks unfinished, and one page more to print, sign and staple. So when the first drawing ends less than
     * half way down a page, the document is drawn again more tightly - {@link Fit#TIGHTER}, one step at a time,
     * stopping at the first step that saves the page - and where no step does and the last page is all but empty, the
     * page before it ends early, so the last carries a fair share of the content with the signatures instead of them
     * alone. What a document says and the order it says it in never change; a document that already ends well is
     * drawn once, exactly as its layout says.
     *
     * @param title the PDF's own title, shown by a viewer in its window bar
     */
    public static byte[] draw(DocumentFonts fonts, Layout layout, String title, List<Block> blocks) {
        Drawn best = drawn(fonts, layout, title, blocks, Fit.LOOSE);
        Fit fit = Fit.LOOSE;
        for (Fit tighter : Fit.TIGHTER) {
            if (!best.mayEndSooner()) {
                return best.pdf();
            }
            Drawn again = drawn(fonts, layout, title, blocks, tighter);
            if (again.pages() < best.pages()) {
                best = again;
                fit = tighter;
            }
        }
        if (!best.endsThin()) {
            return best.pdf();
        }
        // No step saved the page. Share the content out instead: the page before the last gives up what the last
        // lacks of a fair share - a little more on the second try, since blocks move whole.
        float page = PAGE_HEIGHT - layout.margins().top() - layout.margins().bottom();
        for (float share : new float[] {FAIR_SHARE, FAIR_SHARE + 0.12f}) {
            Drawn shared = drawn(fonts, layout, title, blocks,
                    fit.holding(best.pages() - 1, (share - best.lastFilled()) * page));
            if (shared.pages() == best.pages() && !shared.endsThin()) {
                return shared.pdf();
            }
        }
        return best.pdf();
    }

    private static Drawn drawn(DocumentFonts fonts, Layout layout, String title, List<Block> blocks, Fit fit) {
        try (PDDocument document = new PDDocument()) {
            Pen pen = new Pen(document, fonts, layout, fit);
            try {
                for (int i = 0; i < blocks.size(); i++) {
                    Block block = blocks.get(i);
                    // The signature blocks never open a page alone: the paragraph before them - a contract's last
                    // point, a letter's closing line - goes to the next page with them, so whoever signs sees under
                    // what.
                    if (block instanceof Paragraph last && i + 1 < blocks.size()
                            && blocks.get(i + 1) instanceof Signatures after) {
                        pen.keepTogether(last, after);
                    }
                    // Nor does a table open a page without the sentence that introduces it (demo run 24/11, D-7).
                    if (block instanceof Paragraph lead && i + 1 < blocks.size()
                            && blocks.get(i + 1) instanceof Table after) {
                        pen.keepTogether(lead, after);
                    }
                    if (block instanceof Heading heading && i + 1 < blocks.size()) {
                        if (blocks.get(i + 1) instanceof Table after) {
                            pen.keepTogether(heading, null, after);
                        } else if (i + 2 < blocks.size() && blocks.get(i + 1) instanceof Paragraph lead
                                && blocks.get(i + 2) instanceof Table after) {
                            pen.keepTogether(heading, lead, after);
                        }
                    }
                    switch (block) {
                        case NationalHeader h -> pen.nationalHeader(h);
                        case BrandHeader h -> pen.brandHeader(h);
                        case VoucherHeader h -> pen.voucherHeader(h);
                        case Title t -> pen.title(t);
                        case Paragraph p -> pen.paragraph(p);
                        case Split s -> pen.split(s);
                        case Fields f -> pen.fields(f);
                        case Heading h -> pen.heading(h);
                        // A table the signatures follow straight away keeps its last row and totals with them.
                        case Table t -> pen.table(t, i + 1 < blocks.size()
                                && blocks.get(i + 1) instanceof Signatures after ? after : null);
                        case Signatures s -> pen.signatures(s);
                        case Spacer s -> pen.spacer(s);
                        case PageBreak b -> pen.pageBreak();
                        case Together t -> pen.ensure(t.height());
                        case Figure f -> pen.figure(f);
                    }
                }
            } finally {
                pen.close();
            }
            float lastFilled = pen.filled();
            pen.finishPages();
            document.getDocumentInformation().setTitle(title);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return new Drawn(bytes.toByteArray(), document.getNumberOfPages(), lastFilled);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not draw " + title, ex);
        }
    }

    /** A word as it will be drawn; {@code glued} to the word before it when no space separated the two. */
    private record Word(String text, Style style, float width, boolean glued) {
    }

    /** A cell's line: its text, its style and size, and whether it is a detail line (grey). */
    private record CellLine(String text, Style style, float size, boolean detail) {
    }

    /** A column heading's lines and the size they are set in. */
    private record HeaderCell(List<String> lines, float size) {
    }

    /** The drawing state: the current page and how far down it the next block starts. */
    private static final class Pen {

        private final PDDocument document;
        private final DocumentFonts fonts;
        private final Layout layout;
        private final Map<Style, PDFont> loaded = new EnumMap<>(Style.class);
        private final float left;
        private final float width;
        private final float top;
        private final float floor;
        private final Fit fit;
        private final float size;
        private final float tableSize;
        /** Where this page's content ends: the bottom margin, or above it on the page {@link Fit} holds short. */
        private float bottom;
        private final float leading;
        private PDPageContentStream out;
        private float y;

        Pen(PDDocument document, DocumentFonts fonts, Layout layout, Fit fit) throws IOException {
            this.document = document;
            this.fonts = fonts;
            this.layout = layout;
            this.left = layout.margins().left();
            this.width = PAGE_WIDTH - layout.margins().left() - layout.margins().right();
            this.top = PAGE_HEIGHT - layout.margins().top();
            this.floor = layout.margins().bottom();
            this.bottom = floor;
            this.fit = fit;
            // A layout already set smaller than the smallest a tightening goes to keeps its own size.
            this.size = Math.max(Math.min(layout.size(), SMALLEST_BODY), layout.size() - fit.sizeDrop());
            this.tableSize = Math.max(Math.min(layout.tableSize(), SMALLEST_TABLE),
                    layout.tableSize() - fit.tableDrop());
            this.leading = leading(size);
            newPage();
        }

        /** Between single and 1.5 lines, as Nghị định 30 allows; how far between is the {@link Fit}'s to say. */
        private float leading(float size) {
            return size * fit.line();
        }

        /** How far down the current page its content ends, from 0 to 1. */
        float filled() {
            return (top - y) / (top - floor);
        }

        // ---- heads -------------------------------------------------------------------------------------------------

        void nationalHeader(NationalHeader h) throws IOException {
            if (blank(h.issuer())) {
                nation(h, left + width / 2, width, 13);
                y -= 10;
                return;
            }
            // A record's head: the issuer in 40% of the width, the nation in the rest - the nation's line must stay on
            // one line, so it gets the wider share and is set a point smaller.
            float issuerWidth = width * 0.4f;
            float centre = left + issuerWidth / 2;
            float line = y;
            for (String part : wrapPlain(Style.BOLD, 12, h.issuer(), issuerWidth - 8)) {
                line -= leading(12);
                centred(Style.BOLD, 12, centre, line, part);
            }
            line -= 5;
            rule(centre - issuerWidth / 6, line, issuerWidth / 3, BORDER, Color.BLACK);
            if (!blank(h.number())) {
                line -= leading(13);
                // Fitted to the issuer's share, short of its edge: a long number would otherwise run into the place
                // and date beside it, which is fitted short of its own edge too (nation()).
                centred(Style.REGULAR, fitting(Style.REGULAR, 13, h.number(), issuerWidth - 16), centre, line,
                        h.number());
            }
            nation(h, left + issuerWidth + (width - issuerWidth) / 2, width - issuerWidth, 12);
            y = Math.min(y, line) - 12;
        }

        private void nation(NationalHeader h, float centre, float room, float nationSize) throws IOException {
            float fitted = fitting(Style.BOLD, nationSize, h.nation(), room);
            y -= leading(fitted);
            centred(Style.BOLD, fitted, centre, y, h.nation());
            y -= leading(13);
            centred(Style.BOLD, 13, centre, y, h.motto());
            float mottoWidth = textWidth(Style.BOLD, 13, h.motto());
            y -= 4;
            rule(centre - mottoWidth / 2, y, mottoWidth, BORDER, Color.BLACK);
            if (!blank(h.placeDate())) {
                y -= leading(13) + 2;
                centred(Style.ITALIC, fitting(Style.ITALIC, 13, h.placeDate(), room - 16), centre, y, h.placeDate());
            }
        }

        void brandHeader(BrandHeader h) throws IOException {
            float logo = h.logo() == null ? 0 : mm(17);
            float textLeft = left + (logo == 0 ? 0 : logo + 10);
            float room = left + width - textLeft;
            List<String> name = wrapPlain(Style.BOLD, 13.5f, h.name(), room);
            List<String> lines = new ArrayList<>();
            for (String line : h.lines()) {
                if (!blank(line)) {
                    lines.addAll(wrapPlain(Style.REGULAR, 10, line, room));
                }
            }
            float text = name.size() * 17 + lines.size() * 13;
            float height = Math.max(logo, text);
            if (logo > 0) {
                PDImageXObject image = PDImageXObject.createFromByteArray(document, h.logo(), "logo");
                // The logo keeps its own proportions inside a square of the head's height.
                float scale = Math.min(logo / image.getWidth(), logo / image.getHeight());
                float w = image.getWidth() * scale;
                float ht = image.getHeight() * scale;
                out.drawImage(image, left + (logo - w) / 2, y - (height - ht) / 2 - ht, w, ht);
            }
            float line = y - (height - text) / 2;
            Color brand = layout.accent() == null ? Color.BLACK : layout.accent();
            for (String part : name) {
                line -= 17;
                text(Style.BOLD, 13.5f, textLeft, line + 4, part, brand);
            }
            for (String part : lines) {
                line -= 13;
                text(Style.REGULAR, 10, textLeft, line + 3.5f, part, Color.BLACK);
            }
            y -= height + 6;
            rule(left, y, width, 1.5f, brand);
            y -= 12;
        }

        void voucherHeader(VoucherHeader h) throws IOException {
            float start = y;
            float unitWidth = width * 0.5f;
            float line = y;
            for (String part : wrapPlain(Style.BOLD, 11, h.unit(), unitWidth - 10)) {
                line -= leading(11);
                text(Style.BOLD, 11, left, line, part, Color.BLACK);
            }
            if (!blank(h.address())) {
                for (String part : wrapPlain(Style.REGULAR, 10.5f, h.address(), unitWidth - 10)) {
                    line -= leading(10.5f);
                    text(Style.REGULAR, 10.5f, left, line, part, Color.BLACK);
                }
            }
            float formWidth = width * 0.44f;
            float centre = left + width - formWidth / 2;
            float right = start - leading(11);
            centred(Style.BOLD, 11, centre, right, h.form());
            if (!blank(h.basis())) {
                for (String part : wrapPlain(Style.ITALIC, 9.5f, h.basis(), formWidth)) {
                    right -= leading(9.5f);
                    centred(Style.ITALIC, 9.5f, centre, right, part);
                }
            }
            y = Math.min(line, right) - 12;
        }

        void title(Title t) throws IOException {
            float titleSize = size + 4.5f;
            List<String> name = wrapPlain(Style.BOLD, titleSize, t.title(), width);
            float height = 6 + name.size() * leading(titleSize) + (blank(t.subtitle()) ? 0 : leading)
                    + (blank(t.note()) ? 0 : leading) + 8;
            ensure(height + 3 * leading);
            y -= 6;
            Color colour = layout.accent() == null ? Color.BLACK : layout.accent();
            for (String part : name) {
                y -= leading(titleSize);
                centred(Style.BOLD, titleSize, left + width / 2, y, part, colour);
            }
            if (!blank(t.subtitle())) {
                y -= leading;
                centred(Style.BOLD, size, left + width / 2, y, t.subtitle());
            }
            if (!blank(t.note())) {
                y -= leading;
                centred(Style.ITALIC, size, left + width / 2, y, t.note());
            }
            y -= 8;
        }

        // ---- text --------------------------------------------------------------------------------------------------

        void paragraph(Paragraph p) throws IOException {
            float textSize = size + p.sizeDelta();
            float lineHeight = leading(textSize);
            float hang = 0;
            if (p.marker() != null) {
                hang = Math.max(textWidth(Style.REGULAR, textSize, p.marker()) + 5, textSize * 1.6f);
            }
            float x = left + p.indent() + hang;
            float room = width - p.indent() - hang;
            List<List<Word>> lines = wrap(p.runs(), textSize, room - p.firstIndent(), room);
            // Two lines of a paragraph stay together at the foot of a page: one line alone there reads as a mistake.
            ensure(Math.min(lines.size(), 2) * lineHeight);
            for (int i = 0; i < lines.size(); i++) {
                // Nor does its last line go to the next page alone: the line before it goes with it.
                boolean lastButOne = lines.size() > 2 && i == lines.size() - 2;
                ensure(lastButOne ? 2 * lineHeight : lineHeight);
                y -= lineHeight;
                float indent = i == 0 ? p.firstIndent() : 0;
                if (i == 0 && p.marker() != null) {
                    text(Style.REGULAR, textSize, left + p.indent(), y, p.marker(), Color.BLACK);
                }
                line(lines.get(i), textSize, x + indent, room - indent, p.align(), i == lines.size() - 1);
            }
            y -= p.after();
        }

        /** Starts a new page now unless {@code p} and the signature blocks after it both fit on this one. */
        void keepTogether(Paragraph p, Signatures s) throws IOException {
            float textSize = size + p.sizeDelta();
            float hang = p.marker() == null ? 0
                    : Math.max(textWidth(Style.REGULAR, textSize, p.marker()) + 5, textSize * 1.6f);
            float room = width - p.indent() - hang;
            int lines = wrap(p.runs(), textSize, room - p.firstIndent(), room).size();
            ensure(lines * leading(textSize) + p.after() + 10 + signaturesHeight(s));
        }

        void split(Split s) throws IOException {
            List<Word> leftWords = wrap(s.left(), size, Float.MAX_VALUE, Float.MAX_VALUE).getFirst();
            List<Word> rightWords = wrap(s.right(), size, Float.MAX_VALUE, Float.MAX_VALUE).getFirst();
            boolean together = natural(leftWords, size) + natural(rightWords, size) + 12 <= width;
            ensure(leading * (together ? 1 : 2));
            y -= leading;
            line(leftWords, size, left, width, Align.LEFT, true);
            if (!together) {
                y -= leading;
            }
            line(rightWords, size, left, width, Align.RIGHT, true);
            y -= 4;
        }

        void fields(Fields f) throws IOException {
            float labelWidth = 0;
            for (Field field : f.fields()) {
                labelWidth = Math.max(labelWidth, textWidth(Style.REGULAR, size, field.label()));
            }
            float colon = left + f.indent() + labelWidth + 4;
            float valueLeft = colon + textWidth(Style.REGULAR, size, ": ");
            float room = left + width - valueLeft;
            for (Field field : f.fields()) {
                List<List<Word>> lines = wrap(field.value(), size, room, room);
                ensure(Math.min(lines.size(), 2) * leading);
                for (int i = 0; i < lines.size(); i++) {
                    ensure(leading);
                    y -= leading;
                    if (i == 0) {
                        text(Style.REGULAR, size, left + f.indent(), y, field.label(), Color.BLACK);
                        text(Style.REGULAR, size, colon, y, ":", Color.BLACK);
                    }
                    line(lines.get(i), size, valueLeft, room, Align.LEFT, true);
                }
            }
            y -= 4;
        }

        void heading(Heading h) throws IOException {
            List<List<Word>> lines = wrap(List.of(Run.bold(h.text())), size, width, width);
            // With what follows it: a heading at the foot of a page, its clause on the next, is the orphan a reader
            // of contracts looks for. Three lines stand for "the start of what follows", table header included.
            ensure(6 + (lines.size() + 3) * leading);
            y -= 6;
            for (List<Word> line : lines) {
                y -= leading;
                line(line, size, left, width, Align.LEFT, true);
            }
            y -= 3;
        }

        void spacer(Spacer s) {
            y -= s.height();
        }

        void pageBreak() throws IOException {
            if (y < top) {
                newPage();
            }
        }

        void figure(Figure f) throws IOException {
            ensure(f.height());
            float boxTop = y;
            f.painter().paint(new Canvas() {
                @Override
                public float left() {
                    return left;
                }

                @Override
                public float top() {
                    return boxTop;
                }

                @Override
                public float width() {
                    return width;
                }

                @Override
                public float height() {
                    return f.height();
                }

                @Override
                public PDPageContentStream stream() {
                    return out;
                }

                @Override
                public void text(Style style, float size, float x, float baseline, String text, Color colour)
                        throws IOException {
                    Pen.this.text(style, size, x, baseline, text, colour);
                }

                @Override
                public float textWidth(Style style, float size, String text) throws IOException {
                    return Pen.this.textWidth(style, size, text);
                }

                @Override
                public String cut(Style style, float size, String text, float room) throws IOException {
                    return Pen.this.cut(style, size, text, room);
                }
            });
            // A painter leaves whatever colours it last drew in; the text after it is black.
            out.setNonStrokingColor(Color.BLACK);
            out.setStrokingColor(Color.BLACK);
            y = boxTop - f.height();
        }

        // ---- table -------------------------------------------------------------------------------------------------

        /** The table's column widths, its weights shared out over the text width. */
        private float[] widths(Table t) {
            float total = (float) t.columns().stream().mapToDouble(Column::weight).sum();
            float[] widths = new float[t.columns().size()];
            for (int i = 0; i < widths.length; i++) {
                widths[i] = t.columns().get(i).weight() * width / total;
            }
            return widths;
        }

        private List<HeaderCell> header(Table t, float[] widths, float tableSize) throws IOException {
            List<HeaderCell> header = new ArrayList<>();
            for (int i = 0; i < widths.length; i++) {
                String heading = t.columns().get(i).heading();
                float room = widths[i] - 2 * CELL_PAD;
                // A heading wraps between its words, never inside one: "ST" over "T" is not a heading. A word wider
                // than its column makes the whole heading smaller instead.
                float widest = 0;
                for (String word : fonts.drawable(heading == null ? "" : heading).strip().split("\\s+")) {
                    widest = Math.max(widest, textWidth(Style.BOLD, tableSize, word));
                }
                float headingSize = widest <= room ? tableSize : Math.max(6, tableSize * room / widest - 0.05f);
                header.add(new HeaderCell(wrapPlain(Style.BOLD, headingSize, heading, room), headingSize));
            }
            return header;
        }

        private static float headerHeight(List<HeaderCell> header, float lineHeight) {
            return header.stream().mapToInt(cell -> cell.lines().size()).max().orElse(1) * lineHeight + 2 * CELL_PAD;
        }

        /**
         * Whether a row of {@code lines} is tall: more than a third of a fresh page (a delivery note's SKU with its 40
         * LPNs). A tall row is cut over pages rather than moved whole, at least {@code TALL_ROW_HERE} lines on the
         * page it starts and {@code TALL_ROW_NEXT} on the next: moved whole, it left most of a page empty behind it.
         */
        private static boolean tall(int lines, float lineHeight, float fresh) {
            return lines * lineHeight + 2 * CELL_PAD > fresh / 3;
        }

        /**
         * The room a table needs where it starts: the gap above it, its header and its first row. The header goes with
         * that row. Reserving one line was not that: a first row that fits a fresh page but not what is left of this
         * one moves there whole ({@link #table}), and the header stayed behind alone at the foot of the page (demo run
         * 24/11, D-7: a delivery note's 40 LPNs, a liquidation's goods). So it is that whole row - or, for a tall row,
         * which is cut over pages, the lines of it that open the table.
         *
         * <p>A table of a few rows that is no more than a third of a page is not cut at all: it is read in one look
         * - a settlement's five amounts down to what is left - and half of it under a header on the next page is
         * read as two tables. It needs its whole height where it starts.</p>
         */
        private float start(Table t) throws IOException {
            float lineHeight = leading(tableSize) - 1;
            float[] widths = widths(t);
            float headerHeight = headerHeight(header(t, widths, tableSize), lineHeight);
            float fresh = top - bottom - headerHeight;
            if (t.rows().size() > 1 && t.rows().size() <= SHORT_TABLE) {
                float whole = 3 + headerHeight + t.summaries().size() * (lineHeight + 2 * CELL_PAD);
                for (List<Cell> row : t.rows()) {
                    whole += rowLines(row, widths, tableSize) * lineHeight + 2 * CELL_PAD;
                }
                if (whole <= (top - floor) / 3) {
                    return whole;
                }
            }
            int lines = t.rows().isEmpty() ? 1 : rowLines(t.rows().getFirst(), widths, tableSize);
            int kept = tall(lines, lineHeight, fresh) ? Math.min(lines, TALL_ROW_HERE) : lines;
            return 3 + headerHeight + kept * lineHeight + 2 * CELL_PAD;
        }

        /** The lines {@code p} takes, at its size and within its indents. */
        private int lines(Paragraph p) throws IOException {
            float textSize = size + p.sizeDelta();
            float hang = p.marker() == null ? 0
                    : Math.max(textWidth(Style.REGULAR, textSize, p.marker()) + 5, textSize * 1.6f);
            float room = width - p.indent() - hang;
            return wrap(p.runs(), textSize, room - p.firstIndent(), room).size();
        }

        /**
         * Starts a new page now unless {@code p} and the start of the table after it both fit on this one: the
         * sentence that introduces a table ("... cụ thể như sau:") is not left at the foot of a page with its table on
         * the next. Only a paragraph of at most three lines is held to it; a longer one is text of its own and is
         * left where it falls.
         */
        void keepTogether(Paragraph p, Table t) throws IOException {
            int lines = lines(p);
            if (lines <= 3) {
                ensure(lines * leading(size + p.sizeDelta()) + p.after() + start(t));
            }
        }

        /**
         * A heading over a table, or over the sentence that introduces one ({@code lead}, null when there is none):
         * they go over together, or the heading would stay behind alone once {@link #keepTogether(Paragraph, Table)}
         * moved the rest.
         */
        void keepTogether(Heading h, Paragraph lead, Table t) throws IOException {
            float heading = 6 + wrap(List.of(Run.bold(h.text())), size, width, width).size() * leading + 3;
            if (lead == null) {
                ensure(heading + start(t));
                return;
            }
            int lines = lines(lead);
            if (lines <= 3) {
                ensure(heading + lines * leading(size + lead.sizeDelta()) + lead.after() + start(t));
            }
        }

        /**
         * Draws {@code t}. With {@code signedAfter}, the signature blocks that come straight after it: its last row and
         * totals go to the next page with them when they do not all fit here, so that page never opens with the
         * signatures alone - whoever signs sees under what (the rule {@link #keepTogether(Paragraph, Signatures)}
         * keeps for a paragraph).
         */
        void table(Table t, Signatures signedAfter) throws IOException {
            float lineHeight = leading(tableSize) - 1;
            float[] widths = widths(t);
            List<HeaderCell> header = header(t, widths, tableSize);
            float headerHeight = headerHeight(header, lineHeight);
            float fresh = top - bottom - headerHeight;
            float opening = start(t);
            if (signedAfter != null && t.rows().size() == 1) {
                // One row: it is the last too, so the header, the row, the totals and the signatures go together.
                float whole = opening + t.summaries().size() * (lineHeight + 2 * CELL_PAD) + 18
                        + signaturesHeight(signedAfter);
                opening = whole <= top - bottom ? whole : opening;
            }
            ensure(opening);
            y -= 3;
            headerRow(widths, header, headerHeight, tableSize, lineHeight);
            int drawnHere = 0;
            for (int r = 0; r < t.rows().size(); r++) {
                List<Cell> row = t.rows().get(r);
                List<List<CellLine>> cells = new ArrayList<>();
                for (int i = 0; i < widths.length; i++) {
                    cells.add(cellLines(row.get(i), tableSize, widths[i] - 2 * CELL_PAD));
                }
                int count = Math.max(1, cells.stream().mapToInt(List::size).max().orElse(1));
                if (signedAfter != null && r == t.rows().size() - 1 && drawnHere > 0) {
                    float rest = count * lineHeight + 2 * CELL_PAD + t.summaries().size() * (lineHeight + 2 * CELL_PAD)
                            + 18 + signaturesHeight(signedAfter);
                    if (rest <= fresh && y - rest < bottom) {
                        newPage();
                        headerRow(widths, header, headerHeight, tableSize, lineHeight);
                    }
                }
                if (r == t.rows().size() - 2 && drawnHere > 0) {
                    // Nor does the last row open a page alone under a repeated header - the total of a goods table,
                    // read without one figure it sums: the row before it goes over with it.
                    float here = count * lineHeight + 2 * CELL_PAD;
                    float both = here + rowLines(t.rows().getLast(), widths, tableSize) * lineHeight + 2 * CELL_PAD;
                    if (both <= fresh && y - here >= bottom && y - both < bottom) {
                        newPage();
                        headerRow(widths, header, headerHeight, tableSize, lineHeight);
                    }
                }
                int pageBefore = document.getNumberOfPages();
                int from = 0;
                boolean tall = tall(count, lineHeight, fresh);
                while (from < count) {
                    int room = (int) Math.floor((y - bottom - 2 * CELL_PAD) / lineHeight);
                    int remaining = count - from;
                    // A row that fits on a fresh page moves there whole; a tall one is cut, but only where at least
                    // TALL_ROW_HERE of its lines fit (see tall).
                    boolean wholeOnFreshPage = remaining * lineHeight + 2 * CELL_PAD <= fresh;
                    boolean move = room < remaining && (tall ? room < Math.min(remaining, TALL_ROW_HERE)
                            : from == 0 && wholeOnFreshPage);
                    if (room < 1 || move) {
                        newPage();
                        headerRow(widths, header, headerHeight, tableSize, lineHeight);
                        continue;
                    }
                    int take = Math.min(room, remaining);
                    if (tall && take < remaining && remaining - take < TALL_ROW_NEXT) {
                        // Not one line of it alone at the top of the next page.
                        take = remaining - TALL_ROW_NEXT;
                    }
                    int to = from + take;
                    rowSlice(t, row, widths, cells, from, to, tableSize, lineHeight);
                    from = to;
                }
                drawnHere = document.getNumberOfPages() == pageBefore ? drawnHere + 1 : 1;
            }
            if (!t.summaries().isEmpty()) {
                summaries(t, widths, header, headerHeight, tableSize, lineHeight);
            }
            y -= 8;
        }

        /** How many lines {@code row} takes: its tallest cell's, as {@link #table} sets it. */
        private int rowLines(List<Cell> row, float[] widths, float tableSize) throws IOException {
            int lines = 1;
            for (int i = 0; i < widths.length; i++) {
                lines = Math.max(lines, cellLines(row.get(i), tableSize, widths[i] - 2 * CELL_PAD).size());
            }
            return lines;
        }

        private List<CellLine> cellLines(Cell cell, float tableSize, float room) throws IOException {
            List<CellLine> lines = new ArrayList<>();
            if (!blank(cell.text())) {
                // One word alone in its cell - a figure, a unit, a number - is set smaller until it fits rather than
                // cut: "1.250.000.000" over two lines reads as two figures. Only below 70% is it cut after all.
                String text = fonts.drawable(cell.text()).strip();
                float natural = textWidth(cell.style(), tableSize, text);
                float cellSize = natural <= room || text.matches(".*[\\s-].*") ? tableSize
                        : Math.max(tableSize * 0.7f, tableSize * room / natural - 0.05f);
                for (String part : wrapPlain(cell.style(), cellSize, text, room)) {
                    lines.add(new CellLine(part, cell.style(), cellSize, false));
                }
            }
            for (String detail : cell.details()) {
                if (!blank(detail)) {
                    for (String part : wrapPlain(Style.REGULAR, tableSize - 1, detail, room)) {
                        lines.add(new CellLine(part, Style.REGULAR, tableSize - 1, true));
                    }
                }
            }
            return lines;
        }

        private void headerRow(float[] widths, List<HeaderCell> header, float height, float tableSize,
                               float lineHeight) throws IOException {
            out.setNonStrokingColor(tint());
            out.addRect(left, y - height, width, height);
            out.fill();
            out.setNonStrokingColor(Color.BLACK);
            frame(left, y - height, width, height);
            float x = left;
            for (int i = 0; i < widths.length; i++) {
                if (i > 0) {
                    vertical(x, y - height, height);
                }
                List<String> lines = header.get(i).lines();
                // Centred both ways: a one-line heading beside a two-line one sits in the middle of the row.
                float baseline = y - (height - lines.size() * lineHeight) / 2 - tableSize * 0.82f;
                for (String part : lines) {
                    centred(Style.BOLD, header.get(i).size(), x + widths[i] / 2, baseline, part);
                    baseline -= lineHeight;
                }
                x += widths[i];
            }
            y -= height;
        }

        private void rowSlice(Table t, List<Cell> row, float[] widths, List<List<CellLine>> cells, int from, int to,
                              float tableSize, float lineHeight) throws IOException {
            float height = (to - from) * lineHeight + 2 * CELL_PAD;
            frame(left, y - height, width, height);
            float x = left;
            for (int i = 0; i < widths.length; i++) {
                if (i > 0) {
                    vertical(x, y - height, height);
                }
                Align align = row.get(i).align() == null ? t.columns().get(i).align() : row.get(i).align();
                List<CellLine> lines = cells.get(i);
                float baseline = y - CELL_PAD - tableSize * 0.82f;
                for (int k = from; k < Math.min(to, lines.size()); k++) {
                    CellLine part = lines.get(k);
                    aligned(part.style(), part.size(), x + CELL_PAD, widths[i] - 2 * CELL_PAD, baseline, part.text(),
                            align, part.detail() ? MUTED : Color.BLACK);
                    baseline -= lineHeight;
                }
                x += widths[i];
            }
            y -= height;
        }

        private void summaries(Table t, float[] widths, List<HeaderCell> header, float headerHeight,
                               float tableSize, float lineHeight) throws IOException {
            float labelWidth = 0;
            for (int i = 0; i < t.valueColumn(); i++) {
                labelWidth += widths[i];
            }
            float valueWidth = widths[t.valueColumn()];
            float height = lineHeight + 2 * CELL_PAD;
            // The totals stay together: a total alone on the next page would be read without the figures it sums.
            if (y - t.summaries().size() * height < bottom) {
                newPage();
                headerRow(widths, header, headerHeight, tableSize, lineHeight);
            }
            for (Summary summary : t.summaries()) {
                Style style = summary.bold() ? Style.BOLD : Style.REGULAR;
                frame(left, y - height, width, height);
                vertical(left + labelWidth, y - height, height);
                if (labelWidth + valueWidth < width - 0.5f) {
                    vertical(left + labelWidth + valueWidth, y - height, height);
                }
                float baseline = y - CELL_PAD - tableSize * 0.82f;
                String label = cut(style, tableSize, summary.label(), labelWidth - 2 * CELL_PAD);
                aligned(style, tableSize, left + CELL_PAD, labelWidth - 2 * CELL_PAD, baseline, label, Align.RIGHT,
                        Color.BLACK);
                aligned(style, tableSize, left + labelWidth + CELL_PAD, valueWidth - 2 * CELL_PAD, baseline,
                        summary.value(), Align.RIGHT, Color.BLACK);
                y -= height;
            }
        }

        private Color tint() {
            Color accent = layout.accent();
            if (accent == null) {
                return GREY_SHADE;
            }
            // The brand colour at about 14% over white: enough to mark the header, light enough for black text on a
            // black-and-white printer.
            return new Color(blend(accent.getRed()), blend(accent.getGreen()), blend(accent.getBlue()));
        }

        private static int blend(int channel) {
            return Math.round(255 - (255 - channel) * 0.14f);
        }

        // ---- signatures --------------------------------------------------------------------------------------------

        /** The height {@link #signatures} draws {@code s} in, without the space above it. */
        private float signaturesHeight(Signatures s) throws IOException {
            int count = s.signers().size();
            float column = width / count;
            float titleSize = count > 3 ? size - 1.5f : size;
            int titleLines = 1;
            int captionLines = 0;
            for (Signer signer : s.signers()) {
                titleLines = Math.max(titleLines, heading(signer, titleSize, column - 8).size());
                if (!blank(signer.caption())) {
                    captionLines = Math.max(captionLines,
                            wrapPlain(Style.ITALIC, titleSize - 1, signer.caption(), column - 8).size());
                }
            }
            return (blank(s.dateLine()) ? 0 : leading + 2) + titleLines * leading(titleSize)
                    + captionLines * leading(titleSize - 1) + Math.min(s.room(), fit.room()) + leading;
        }

        /** A signer's title, then their position under it, each wrapped in the block's width. */
        private List<String> heading(Signer signer, float titleSize, float room) throws IOException {
            List<String> lines = new ArrayList<>(wrapPlain(Style.BOLD, titleSize, signer.title(), room));
            if (!blank(signer.position())) {
                lines.addAll(wrapPlain(Style.BOLD, titleSize, signer.position(), room));
            }
            return lines;
        }

        void signatures(Signatures s) throws IOException {
            int count = s.signers().size();
            float column = width / count;
            float titleSize = count > 3 ? size - 1.5f : size;
            List<List<String>> titles = new ArrayList<>();
            int titleLines = 1;
            for (Signer signer : s.signers()) {
                List<String> lines = heading(signer, titleSize, column - 8);
                titles.add(lines);
                titleLines = Math.max(titleLines, lines.size());
            }
            List<List<String>> captions = new ArrayList<>();
            int captionLines = 0;
            for (Signer signer : s.signers()) {
                List<String> lines = blank(signer.caption()) ? List.of()
                        : wrapPlain(Style.ITALIC, titleSize - 1, signer.caption(), column - 8);
                captions.add(lines);
                captionLines = Math.max(captionLines, lines.size());
            }
            float titleLeading = leading(titleSize);
            float height = signaturesHeight(s);
            ensure(10 + height);
            y -= 10;
            if (!blank(s.dateLine())) {
                y -= leading;
                // Over the last block, but never past the margin: four blocks are each narrower than a date.
                float dateWidth = textWidth(Style.ITALIC, size, s.dateLine());
                float dateLeft = Math.min(left + width - column / 2 - dateWidth / 2, left + width - dateWidth);
                text(Style.ITALIC, size, dateLeft, y, s.dateLine(), Color.BLACK);
                y -= 2;
            }
            float start = y;
            for (int i = 0; i < count; i++) {
                float centre = left + column * i + column / 2;
                float line = start;
                for (String part : titles.get(i)) {
                    line -= titleLeading;
                    centred(Style.BOLD, titleSize, centre, line, part);
                }
                for (String part : captions.get(i)) {
                    line -= leading(titleSize - 1);
                    centred(Style.ITALIC, titleSize - 1, centre, line, part);
                }
                if (!blank(s.signers().get(i).name())) {
                    float name = start - titleLines * titleLeading - captionLines * leading(titleSize - 1)
                            - Math.min(s.room(), fit.room()) - leading;
                    String fitted = s.signers().get(i).name();
                    centred(Style.BOLD, fitting(Style.BOLD, titleSize, fitted, column - 8), centre, name + 3, fitted);
                }
            }
            y = start - (height - (blank(s.dateLine()) ? 0 : leading + 2));
        }

        // ---- pages -------------------------------------------------------------------------------------------------

        /** Starts a new page unless {@code height} still fits above the bottom margin of this one. */
        private void ensure(float height) throws IOException {
            if (y - height < bottom && y < top) {
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
            bottom = floor + (document.getNumberOfPages() == fit.heldPage() ? fit.held() : 0);
            y = top;
        }

        void close() throws IOException {
            if (out != null) {
                out.close();
                out = null;
            }
        }

        /** The foot and the watermark of every page, once the page count is known. */
        void finishPages() throws IOException {
            int pages = document.getNumberOfPages();
            float baseline = Math.max(floor - 21, 14);
            for (int i = 0; i < pages; i++) {
                try (PDPageContentStream over = new PDPageContentStream(document, document.getPage(i),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    out = over;
                    if (!blank(layout.watermark())) {
                        watermark(layout.watermark());
                    }
                    rule(left, baseline + 10, width, 0.4f, MUTED);
                    if (!blank(layout.footerLeft())) {
                        text(Style.REGULAR, 9, left, baseline, layout.footerLeft(), MUTED);
                    }
                    String label = layout.pageLabel().formatted(i + 1, pages);
                    float labelWidth = textWidth(Style.REGULAR, 9, label);
                    text(Style.REGULAR, 9, left + width - labelWidth, baseline, label, MUTED);
                    if (!blank(layout.footerCentre())) {
                        // Between the number and the page label, cut short rather than drawn over either.
                        float taken = Math.max(blank(layout.footerLeft()) ? 0
                                : textWidth(Style.REGULAR, 9, layout.footerLeft()), labelWidth);
                        String centre = cut(Style.REGULAR, 9, layout.footerCentre(), width - 2 * taken - 24);
                        centred(Style.REGULAR, 9, left + width / 2, baseline, centre, MUTED);
                    }
                }
            }
            out = null;
        }

        /** Across the page from the lower left to the upper right, pale enough to read the text through it. */
        private void watermark(String words) throws IOException {
            float diagonal = (float) Math.hypot(PAGE_WIDTH, PAGE_HEIGHT);
            float natural = textWidth(Style.BOLD, 100, words);
            float markSize = Math.min(110, 100 * diagonal * 0.55f / natural);
            float markWidth = natural * markSize / 100;
            double angle = Math.atan2(PAGE_HEIGHT, PAGE_WIDTH);
            PDExtendedGraphicsState pale = new PDExtendedGraphicsState();
            pale.setNonStrokingAlphaConstant(0.13f);
            out.saveGraphicsState();
            out.setGraphicsStateParameters(pale);
            out.setNonStrokingColor(WATERMARK);
            out.beginText();
            out.setFont(font(Style.BOLD), markSize);
            Matrix turn = Matrix.getRotateInstance(angle, PAGE_WIDTH / 2, PAGE_HEIGHT / 2);
            turn.translate(-markWidth / 2, -markSize * 0.35f);
            out.setTextMatrix(turn);
            out.showText(fonts.drawable(words));
            out.endText();
            out.restoreGraphicsState();
            out.setNonStrokingColor(Color.BLACK);
        }

        // ---- primitives --------------------------------------------------------------------------------------------

        private PDFont font(Style style) throws IOException {
            PDFont font = loaded.get(style);
            if (font == null) {
                // Loaded when first drawn in: a voucher with no italic line does not carry the italic font.
                font = PDType0Font.load(document, new ByteArrayInputStream(fonts.bytes(style)), true);
                loaded.put(style, font);
            }
            return font;
        }

        private void text(Style style, float textSize, float x, float baseline, String text, Color colour)
                throws IOException {
            out.setNonStrokingColor(colour);
            out.beginText();
            out.setFont(font(style), textSize);
            out.newLineAtOffset(x, baseline);
            out.showText(fonts.drawable(text));
            out.endText();
            out.setNonStrokingColor(Color.BLACK);
        }

        private void centred(Style style, float textSize, float centre, float baseline, String text)
                throws IOException {
            centred(style, textSize, centre, baseline, text, Color.BLACK);
        }

        private void centred(Style style, float textSize, float centre, float baseline, String text, Color colour)
                throws IOException {
            text(style, textSize, centre - textWidth(style, textSize, text) / 2, baseline, text, colour);
        }

        private void aligned(Style style, float textSize, float x, float room, float baseline, String text,
                             Align align, Color colour) throws IOException {
            float textWidth = textWidth(style, textSize, text);
            float at = switch (align) {
                case RIGHT -> x + room - textWidth;
                case CENTRE -> x + (room - textWidth) / 2;
                case LEFT, JUSTIFY -> x;
            };
            text(style, textSize, at, baseline, text, colour);
        }

        /**
         * One wrapped line at the current height. A justified line that is not its paragraph's last has its spaces
         * widened to fill the room; each run of words in one style is otherwise drawn as one string, so a reader's
         * text search finds a phrase as it was written.
         */
        private void line(List<Word> words, float textSize, float x, float room, Align align, boolean last)
                throws IOException {
            if (words.isEmpty()) {
                return;
            }
            float space = textWidth(Style.REGULAR, textSize, " ");
            float natural = natural(words, textSize);
            int gaps = 0;
            for (int i = 1; i < words.size(); i++) {
                gaps += words.get(i).glued() ? 0 : 1;
            }
            float extra = 0;
            // A line stretched by more than a space and a half per gap looks torn; it is left ragged instead.
            if (align == Align.JUSTIFY && !last && gaps > 0 && (room - natural) / gaps <= space * 1.5f) {
                extra = Math.max(0, (room - natural) / gaps);
            }
            float at = switch (align) {
                case RIGHT -> x + room - natural;
                case CENTRE -> x + (room - natural) / 2;
                case LEFT, JUSTIFY -> x;
            };
            StringBuilder pending = new StringBuilder();
            Style pendingStyle = null;
            float pendingAt = at;
            for (int i = 0; i < words.size(); i++) {
                Word word = words.get(i);
                boolean gap = i > 0 && !word.glued();
                if (gap) {
                    at += space + extra;
                }
                boolean joins = pendingStyle == word.style() && (extra == 0 || !gap);
                if (!joins && pendingStyle != null) {
                    text(pendingStyle, textSize, pendingAt, y, pending.toString(), Color.BLACK);
                    pending.setLength(0);
                    pendingStyle = null;
                }
                if (pendingStyle == null) {
                    pendingStyle = word.style();
                    pendingAt = at;
                } else if (gap) {
                    pending.append(' ');
                }
                pending.append(word.text());
                at += word.width();
            }
            if (pendingStyle != null) {
                text(pendingStyle, textSize, pendingAt, y, pending.toString(), Color.BLACK);
            }
        }

        private float natural(List<Word> words, float textSize) throws IOException {
            float space = textWidth(Style.REGULAR, textSize, " ");
            float total = 0;
            for (int i = 0; i < words.size(); i++) {
                total += words.get(i).width() + (i > 0 && !words.get(i).glued() ? space : 0);
            }
            return total;
        }

        /**
         * The runs cut into lines no wider than {@code first} for the first and {@code rest} for the others: at
         * spaces, and inside a word only when the word alone is wider than a line (a long code), after a hyphen or a
         * slash of it when it has one. Always at least one line.
         */
        private List<List<Word>> wrap(List<Run> runs, float textSize, float first, float rest) throws IOException {
            float space = textWidth(Style.REGULAR, textSize, " ");
            List<List<Word>> lines = new ArrayList<>();
            List<Word> line = new ArrayList<>();
            float used = 0;
            boolean spaceBefore = true;
            for (Run run : runs) {
                String text = fonts.drawable(run.text() == null ? "" : run.text());
                int at = 0;
                while (at < text.length()) {
                    if (Character.isWhitespace(text.charAt(at))) {
                        spaceBefore = true;
                        at++;
                        continue;
                    }
                    int end = at;
                    while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                        end++;
                    }
                    String token = text.substring(at, end);
                    at = end;
                    boolean glued = !spaceBefore && !line.isEmpty();
                    spaceBefore = false;
                    float room = lines.isEmpty() ? first : rest;
                    float tokenWidth = textWidth(run.style(), textSize, token);
                    float needed = used + (line.isEmpty() || glued ? 0 : space) + tokenWidth;
                    if (needed <= room) {
                        line.add(new Word(token, run.style(), tokenWidth, glued));
                        used = needed;
                        continue;
                    }
                    if (!line.isEmpty()) {
                        lines.add(line);
                        line = new ArrayList<>();
                        used = 0;
                        room = rest;
                    }
                    String tail = token;
                    while (textWidth(run.style(), textSize, tail) > room && tail.length() > 1) {
                        int cutAt = tail.length() - 1;
                        while (cutAt > 1 && textWidth(run.style(), textSize, tail.substring(0, cutAt)) > room) {
                            cutAt--;
                        }
                        int mark = Math.max(tail.lastIndexOf('-', cutAt - 1), tail.lastIndexOf('/', cutAt - 1));
                        if (mark > 0) {
                            cutAt = mark + 1;
                        }
                        String head = tail.substring(0, cutAt);
                        lines.add(List.of(new Word(head, run.style(), textWidth(run.style(), textSize, head),
                                false)));
                        tail = tail.substring(cutAt);
                        room = rest;
                    }
                    float tailWidth = textWidth(run.style(), textSize, tail);
                    line.add(new Word(tail, run.style(), tailWidth, false));
                    used = tailWidth;
                }
            }
            if (!line.isEmpty() || lines.isEmpty()) {
                lines.add(line);
            }
            return lines;
        }

        /** {@link #wrap} for text in one style, as strings: a table cell, a head's line. */
        private List<String> wrapPlain(Style style, float textSize, String text, float room) throws IOException {
            List<String> lines = new ArrayList<>();
            for (List<Word> line : wrap(List.of(new Run(text, style)), textSize, room, room)) {
                StringBuilder joined = new StringBuilder();
                for (Word word : line) {
                    if (!joined.isEmpty() && !word.glued()) {
                        joined.append(' ');
                    }
                    joined.append(word.text());
                }
                lines.add(joined.toString());
            }
            return lines;
        }

        /** {@code text} shortened with an ellipsis until it fits {@code room}. */
        private String cut(Style style, float textSize, String text, float room) throws IOException {
            String drawable = fonts.drawable(text);
            if (textWidth(style, textSize, drawable) <= room) {
                return drawable;
            }
            String kept = drawable;
            while (kept.length() > 1 && textWidth(style, textSize, kept + "...") > room) {
                kept = kept.substring(0, kept.length() - 1);
            }
            return kept.stripTrailing() + "...";
        }

        /** The largest size up to {@code wanted} at which {@code text} fits {@code room} on one line. */
        private float fitting(Style style, float wanted, String text, float room) throws IOException {
            float natural = textWidth(style, wanted, text);
            return natural <= room ? wanted : Math.max(6, wanted * room / natural);
        }

        private float textWidth(Style style, float textSize, String text) throws IOException {
            return font(style).getStringWidth(fonts.drawable(text)) / 1000 * textSize;
        }

        private void frame(float x, float atBottom, float w, float h) throws IOException {
            out.setStrokingColor(Color.BLACK);
            out.setLineWidth(BORDER);
            out.addRect(x, atBottom, w, h);
            out.stroke();
        }

        private void vertical(float x, float atBottom, float h) throws IOException {
            out.setStrokingColor(Color.BLACK);
            out.setLineWidth(BORDER);
            out.moveTo(x, atBottom);
            out.lineTo(x, atBottom + h);
            out.stroke();
        }

        private void rule(float x, float at, float w, float thickness, Color colour) throws IOException {
            out.setStrokingColor(colour);
            out.setLineWidth(thickness);
            out.moveTo(x, at);
            out.lineTo(x + w, at);
            out.stroke();
            out.setStrokingColor(Color.BLACK);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
