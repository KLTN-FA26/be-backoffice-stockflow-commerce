package com.stockflow.common.label;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.stockflow.common.label.FormDocument.Block;
import com.stockflow.common.label.FormDocument.Box;
import com.stockflow.common.label.FormDocument.Bullets;
import com.stockflow.common.label.FormDocument.Cell;
import com.stockflow.common.label.FormDocument.Column;
import com.stockflow.common.label.FormDocument.Fact;
import com.stockflow.common.label.FormDocument.KeyFacts;
import com.stockflow.common.label.FormDocument.Masthead;
import com.stockflow.common.label.FormDocument.Note;
import com.stockflow.common.label.FormDocument.Table;
import com.stockflow.common.label.FormDocument.Title;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The block layout of a printed form: every block reaches the page, a table runs over pages with its header repeated, a
 * row taller than a page is cut between its lines rather than lost, text wraps inside its box, and the code is drawn
 * once, on page 1.
 */
class FormDocumentTest {

    private static final String CODE = "PRD-20261110-0007";
    private static final LabelFonts FONTS = new LabelFonts();
    private static final List<Column> COLUMNS = List.of(new Column("STT", 26, false), new Column("Mã hàng", 86, false),
            new Column("Tên hàng / quy cách", 233, false), new Column("Số thùng", 54, true),
            new Column("Cái / thùng", 56, true), new Column("Tổng số cái", 60, true));

    private static byte[] draw(List<Block> blocks, String code) {
        return FormDocument.draw(FONTS, blocks, "Phiếu thử", code, "Phiếu " + CODE, "Trang %d / %d");
    }

    private static List<Cell> row(int n, List<String> details) {
        return List.of(new Cell(String.valueOf(n), false, List.of()), new Cell("CUP-" + n, false, List.of()),
                new Cell("Ly giấy " + n, true, details), new Cell("12", false, List.of()),
                new Cell("50", false, List.of()), new Cell("600", false, List.of()));
    }

    private static Table table(int rows) {
        return new Table("I. HÀNG CẦN SẢN XUẤT", COLUMNS,
                IntStream.rangeClosed(1, rows).mapToObj(n -> row(n, List.of("Thùng 600 x 400 x 350 mm"))).toList());
    }

    private static String pageText(PDDocument doc, int page) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(doc);
    }

    private static String decode(PDDocument doc, int pageIndex) throws Exception {
        BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(pageIndex, 200);
        int[] pixels = page.getRGB(0, 0, page.getWidth(), page.getHeight(), null, 0, page.getWidth());
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(page.getWidth(), page.getHeight(), pixels)))).getText();
    }

    @Test
    @DisplayName("every block reaches the page")
    void drawsEveryBlock() throws Exception {
        byte[] pdf = draw(List.of(
                new Masthead("CÔNG TY TNHH STOCKFLOW"),
                new Title("PHIẾU ĐẶT HÀNG SẢN XUẤT", "Số: " + CODE + "     Ngày: 10/11/2026"),
                new KeyFacts(List.of(new Fact("CẦN HÀNG TRƯỚC NGÀY", "30/11/2026", null, 0.22f),
                        new Fact("GIAO VỀ", "Kho Hồ Chí Minh", "12 Nguyễn Huệ, TP HCM", 0.56f),
                        new Fact("DUYỆT MẪU IN TRƯỚC", "CÓ", null, 0.22f))),
                new Box("ĐƠN VỊ SẢN XUẤT", "Xưởng in Bình Dương — mã XUONG-IN", List.of("Liên hệ: Chị Lan")),
                new Table("I. HÀNG CẦN SẢN XUẤT", COLUMNS, List.of(row(1, List.of("Đóng gói: Dán nhãn")))),
                new Bullets("II. YÊU CẦU", List.of("Giao hàng tại: Kho HCM.", "Ghi chú: Giao sớm.")),
                new Note("Liên hệ khi cần hỏi về phiếu:", "Nguyễn Văn A", "không cần chữ ký")), CODE);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(new PDFTextStripper().getText(doc)).contains("CÔNG TY TNHH STOCKFLOW", "PHIẾU ĐẶT HÀNG SẢN XUẤT",
                    "Số: " + CODE, "Ngày: 10/11/2026", "CẦN HÀNG TRƯỚC NGÀY", "30/11/2026", "GIAO VỀ",
                    "Kho Hồ Chí Minh", "12 Nguyễn Huệ, TP HCM", "DUYỆT MẪU IN TRƯỚC", "CÓ", "ĐƠN VỊ SẢN XUẤT",
                    "Xưởng in Bình Dương — mã XUONG-IN", "Liên hệ: Chị Lan", "I. HÀNG CẦN SẢN XUẤT", "STT", "Mã hàng",
                    "Tên hàng / quy cách", "Số thùng", "CUP-1", "Ly giấy 1", "Đóng gói: Dán nhãn", "600", "II. YÊU CẦU",
                    "Giao hàng tại: Kho HCM.", "Ghi chú: Giao sớm.", "Liên hệ khi cần hỏi về phiếu:", "Nguyễn Văn A",
                    "không cần chữ ký", "Phiếu " + CODE, "Trang 1 / 1");
            assertThat(doc.getDocumentInformation().getTitle()).isEqualTo("Phiếu thử");
        }
    }

    @Test
    @DisplayName("a long table runs over pages, each page repeating its column header and numbering itself x / N")
    void tableBreaksAcrossPagesRepeatingItsHeader() throws Exception {
        try (PDDocument doc = Loader.loadPDF(draw(List.of(new Masthead("CÔNG TY"), table(80)), CODE))) {
            int pages = doc.getNumberOfPages();
            assertThat(pages).isGreaterThanOrEqualTo(2);
            for (int page = 1; page <= pages; page++) {
                assertThat(pageText(doc, page)).as("page %d", page).contains("Tên hàng / quy cách")
                        .contains("Trang " + page + " / " + pages);
            }
            assertThat(new PDFTextStripper().getText(doc)).contains("CUP-1 Ly giấy 1", "CUP-80 Ly giấy 80");
        }
    }

    @Test
    @DisplayName("a row taller than a whole page is cut between its lines and carries on, nothing lost")
    void oversizedRowIsCutBetweenLines() throws Exception {
        List<String> details = new ArrayList<>();
        for (int i = 1; i <= 300; i++) {
            details.add("d%03d".formatted(i));
        }
        try (PDDocument doc = Loader.loadPDF(draw(List.of(new Table("BẢNG", COLUMNS, List.of(row(1, details)))),
                null))) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("d001", "d150", "d300");
        }
    }

    @Test
    @DisplayName("long text wraps inside its box instead of running off the page")
    void longTextWrapsInsideItsBox() throws Exception {
        String words = IntStream.rangeClosed(1, 60).mapToObj(i -> "từ" + i).collect(Collectors.joining(" "));
        byte[] pdf = draw(List.of(
                new KeyFacts(List.of(new Fact("A", "1", null, 0.22f), new Fact("GIAO VỀ", "Kho", words, 0.56f),
                        new Fact("C", "3", null, 0.22f))),
                new Box("ĐƠN VỊ", "Tên", List.of(words))), CODE);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            String flat = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertThat(flat).contains("từ1 ", "từ30 ", "từ60");
        }
    }

    /** The leftmost and rightmost x, in points, of every text line holding {@code needle}. */
    private static List<float[]> spans(PDDocument doc, String needle) throws Exception {
        List<float[]> spans = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) throws java.io.IOException {
                if (text.contains(needle)) {
                    float left = Float.MAX_VALUE;
                    float right = 0;
                    for (TextPosition p : positions) {
                        left = Math.min(left, p.getXDirAdj());
                        right = Math.max(right, p.getXDirAdj() + p.getWidthDirAdj());
                    }
                    spans.add(new float[] {left, right});
                }
                super.writeString(text, positions);
            }
        };
        stripper.getText(doc);
        return spans;
    }

    @Test
    @DisplayName("a long legal company name wraps beside the QR and never covers it")
    void longCompanyNameLeavesTheQrReadable() throws Exception {
        String company = "CÔNG TY TNHH THƯƠNG MẠI DỊCH VỤ SẢN XUẤT BAO BÌ IN ẤN STOCKFLOW VIỆT NAM CHI NHÁNH THÀNH "
                + "PHỐ HỒ CHÍ MINH";
        try (PDDocument doc = Loader.loadPDF(draw(List.of(new Masthead(company)), CODE))) {
            float qrLeft = PDRectangle.A4.getWidth() - 40 - LabelDrawing.points(22);
            List<float[]> name = new ArrayList<>();
            for (String part : List.of("CÔNG TY", "STOCKFLOW", "MINH")) {
                name.addAll(spans(doc, part));
            }
            assertThat(name).isNotEmpty().allSatisfy(span -> assertThat(span[1]).as("text right edge")
                    .isLessThan(qrLeft));
            assertThat(new PDFTextStripper().getText(doc).replaceAll("\\s+", " ")).contains("CHI NHÁNH THÀNH PHỐ");
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 200);
            int[] pixels = page.getRGB(0, 0, page.getWidth(), page.getHeight(), null, 0, page.getWidth());
            var qrOnly = java.util.Map.of(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS,
                    List.of(com.google.zxing.BarcodeFormat.QR_CODE));
            assertThat(new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(
                    page.getWidth(), page.getHeight(), pixels))), qrOnly).getText()).isEqualTo(CODE);
        }
    }

    @Test
    @DisplayName("a key fact's long value wraps inside its own box instead of running over its neighbours")
    void longKeyFactValueStaysInItsBox() throws Exception {
        float width = PDRectangle.A4.getWidth() - 80;
        byte[] pdf = draw(List.of(new KeyFacts(List.of(new Fact("CẦN HÀNG TRƯỚC NGÀY", "30/11/2026", null, 0.22f),
                new Fact("GIAO VỀ", "Kho thành phẩm Khu công nghiệp Sóng Thần 2, Dĩ An, Bình Dương", "12 Đường số 5",
                        0.56f),
                new Fact("DUYỆT MẪU IN TRƯỚC", "KHÔNG", null, 0.22f)))), null);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            float left = 40 + width * 0.22f;
            float right = 40 + width * 0.78f;
            List<float[]> value = new ArrayList<>();
            for (String part : List.of("Kho thành phẩm", "Sóng Thần", "Bình Dương")) {
                value.addAll(spans(doc, part));
            }
            assertThat(value).isNotEmpty().allSatisfy(span -> {
                assertThat(span[0]).as("left edge").isGreaterThanOrEqualTo(left);
                assertThat(span[1]).as("right edge").isLessThanOrEqualTo(right);
            });
            assertThat(new PDFTextStripper().getText(doc).replaceAll("\\s+", " "))
                    .contains("Kho thành phẩm Khu công nghiệp Sóng Thần 2, Dĩ An, Bình Dương");
        }
    }

    @Test
    @DisplayName("a code wider than its column is cut after one of its hyphens, not in the middle of a part")
    void longCodeIsCutAtAHyphen() throws Exception {
        List<Cell> row = List.of(new Cell("1", false, List.of()), new Cell("T8719C3EAC18D-D944D", false, List.of()),
                new Cell("Ly", true, List.of()), new Cell("1", false, List.of()), new Cell("1", false, List.of()),
                new Cell("1", false, List.of()));
        try (PDDocument doc = Loader.loadPDF(draw(List.of(new Table("BẢNG", COLUMNS, List.of(row))), null))) {
            // The stripper ends a line with the platform's separator ("\r\n" on Windows): pinned, so the cut is checked
            // the same on every machine.
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            assertThat(stripper.getText(doc)).contains("T8719C3EAC18D-\n").contains("D944D");
        }
    }

    @Test
    @DisplayName("the code is a QR and a barcode on page 1 only")
    void codeOnlyOnPageOne() throws Exception {
        try (PDDocument doc = Loader.loadPDF(draw(List.of(new Masthead("CÔNG TY"),
                new Title("PHIẾU", "Số: " + CODE), table(80)), CODE))) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            assertThat(decode(doc, 0)).isEqualTo(CODE);
            assertThatThrownBy(() -> decode(doc, 1)).isInstanceOf(NotFoundException.class);
        }
    }

    @Test
    @DisplayName("no code: neither QR nor barcode, the rest drawn")
    void noCode() throws Exception {
        try (PDDocument doc = Loader.loadPDF(draw(List.of(new Masthead("CÔNG TY TNHH STOCKFLOW"),
                new Title("PHIẾU", "Số: 1")), null))) {
            assertThat(new PDFTextStripper().getText(doc)).contains("CÔNG TY TNHH STOCKFLOW", "PHIẾU");
            assertThatThrownBy(() -> decode(doc, 0)).isInstanceOf(NotFoundException.class);
        }
    }
}
