package com.stockflow.common.document;

import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.BrandHeader;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
import com.stockflow.common.document.BusinessDocument.Field;
import com.stockflow.common.document.BusinessDocument.Fields;
import com.stockflow.common.document.BusinessDocument.Heading;
import com.stockflow.common.document.BusinessDocument.Layout;
import com.stockflow.common.document.BusinessDocument.NationalHeader;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Run;
import com.stockflow.common.document.BusinessDocument.Signatures;
import com.stockflow.common.document.BusinessDocument.Signer;
import com.stockflow.common.document.BusinessDocument.Spacer;
import com.stockflow.common.document.BusinessDocument.Split;
import com.stockflow.common.document.BusinessDocument.Summary;
import com.stockflow.common.document.BusinessDocument.Table;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.BusinessDocument.VoucherHeader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.awt.Color;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The business-document kit: what a signed paper must never get wrong whatever the data - text outside the margins, a
 * table that loses its header over a page, a signature block cut in two, a page without its number.
 */
class BusinessDocumentTest {

    private static final DocumentFonts FONTS = new DocumentFonts();

    /** A real legal name: long, in capitals, Vietnamese - far wider than its letters suggest. */
    private static final String LONG_NAME = "CÔNG TY TRÁCH NHIỆM HỮU HẠN THƯƠNG MẠI DỊCH VỤ SẢN XUẤT BAO BÌ GIẤY VÀ NHỰA "
            + "MIỀN NAM VIỆT NAM";

    private static Layout official(String watermark) {
        return Layout.official("Số: 045/2026/HĐMB", "CÔNG TY AN PHÁT", "Trang %d/%d", watermark);
    }

    /** Where every glyph of every page sits: the extremes, to hold against the margins. */
    private record Extent(float left, float right, float lowest, List<String> pages) {
    }

    private static Extent extent(byte[] pdf) throws IOException {
        float[] box = {Float.MAX_VALUE, 0, 0};
        List<String> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                PDFTextStripper stripper = new PDFTextStripper() {
                    @Override
                    protected void writeString(String text, List<TextPosition> positions) throws IOException {
                        for (TextPosition glyph : positions) {
                            // The foot is below the bottom margin by design, and the watermark is turned: neither
                            // is body text.
                            if (glyph.getDir() != 0 || glyph.getYDirAdj() > PDRectangle.A4.getHeight() - 50) {
                                continue;
                            }
                            box[0] = Math.min(box[0], glyph.getXDirAdj());
                            box[1] = Math.max(box[1], glyph.getXDirAdj() + glyph.getWidthDirAdj());
                            box[2] = Math.max(box[2], glyph.getYDirAdj());
                        }
                        super.writeString(text, positions);
                    }
                };
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document).replaceAll("\\s+", " "));
            }
        }
        return new Extent(box[0], box[1], box[2], pages);
    }

    @Test
    @DisplayName("the fonts draw every Vietnamese letter in all four styles, and the signs documents use")
    void vietnameseCoverage() {
        String letters = "aăâbcdđeêghiklmnoôơpqrstuưvxy áàảãạ ắằẳẵặ ấầẩẫậ éèẻẽẹ ếềểễệ íìỉĩị óòỏõọ ốồổỗộ ớờởỡợ "
                + "úùủũụ ứừửữự ýỳỷỹỵ";
        String all = letters + letters.toUpperCase() + "0123456789 ± % – “ ” … / ( ) |";
        assertThat(FONTS.drawable(all)).isEqualTo(all);
        // Typed with combining marks, a letter is still one glyph; an emoji and a tab do not stop the print.
        assertThat(FONTS.drawable("Việt\tNam 😀")).isEqualTo("Việt Nam ?");
    }

    @Test
    @DisplayName("numbers, money and dates are written the Vietnamese way")
    void formats() {
        assertThat(DocumentFormats.money(new BigDecimal("133100000"))).isEqualTo("133.100.000");
        assertThat(DocumentFormats.money(new BigDecimal("999.5"))).isEqualTo("1.000");
        assertThat(DocumentFormats.number(1_234_567)).isEqualTo("1.234.567");
        assertThat(DocumentFormats.rate(new BigDecimal("8.00"))).isEqualTo("8%");
        assertThat(DocumentFormats.rate(new BigDecimal("7.5"))).isEqualTo("7,5%");
        assertThat(DocumentFormats.date(LocalDate.of(2026, 3, 5))).isEqualTo("05/03/2026");
        // Nghị định 30/2020: the day below 10 and the months 1 and 2 take a zero in front.
        assertThat(DocumentFormats.longDate(LocalDate.of(2026, 2, 5))).isEqualTo("ngày 05 tháng 02 năm 2026");
        assertThat(DocumentFormats.longDate(LocalDate.of(2026, 3, 5))).isEqualTo("ngày 05 tháng 3 năm 2026");
        assertThat(DocumentFormats.longDate(LocalDate.of(2026, 11, 23))).isEqualTo("ngày 23 tháng 11 năm 2026");
        // 16:59 UTC is 23:59 in Vietnam, the same day - not the day after, and not UTC's hour.
        assertThat(DocumentFormats.moment(Instant.parse("2026-11-23T16:59:00Z"))).isEqualTo("23:59 ngày 23/11/2026");
    }

    @Test
    @DisplayName("an official paper keeps the margins of Nghị định 30 - 30 mm left, 15 mm right - however long the "
            + "words, and a word wider than a line is cut, not drawn over the edge")
    void textStaysInsideTheMargins() throws IOException {
        String code = "LY-GIAY-12OZ-IN-LOGO-" + "X".repeat(120);
        List<Block> blocks = List.of(
                new NationalHeader("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", "Độc lập - Tự do - Hạnh phúc", null, null,
                        null),
                new Title("HỢP ĐỒNG MUA BÁN HÀNG HÓA", "Số: 045/2026/HĐMB", null),
                Paragraph.of(Run.bold("BÊN MUA (BÊN B): "), Run.bold(LONG_NAME)).align(Align.LEFT),
                new Fields(List.of(Field.of("Địa chỉ", "Số 12 Đường Nguyễn Huệ, Phường Sài Gòn, Thành phố Hồ Chí "
                                + "Minh, Việt Nam - tầng 15, toà nhà Bitexco Financial Tower"),
                        Field.of("Mã số thuế", "0398765432"), Field.of("Mã hàng", code)), 0),
                Paragraph.of("Hàng hóa được sản xuất theo thiết kế do Bên B cung cấp và theo mẫu in đã được Bên B "
                        + "duyệt; mẫu in đã duyệt là căn cứ nghiệm thu chất lượng. " + code).firstIndent(28),
                new Split(List.of(Run.of("Số: "), Run.bold(code.substring(0, 40))),
                        List.of(Run.italic("Thành phố Hồ Chí Minh, ngày 23 tháng 11 năm 2026"))));

        Extent extent = extent(BusinessDocument.draw(FONTS, official(null), "t", blocks));

        assertThat(extent.left()).as("left margin, 30 mm").isGreaterThanOrEqualTo(BusinessDocument.mm(30) - 0.5f);
        assertThat(extent.right()).as("right margin, 15 mm")
                .isLessThanOrEqualTo(PDRectangle.A4.getWidth() - BusinessDocument.mm(15) + 0.5f);
        assertThat(String.join(" ", extent.pages())).contains("MIỀN NAM VIỆT NAM", "Bitexco Financial Tower",
                "Thành phố Hồ Chí Minh, ngày 23 tháng 11 năm 2026");
    }

    @Test
    @DisplayName("a justified paragraph fills its line to the right margin, except its last line")
    void justifiedText() throws IOException {
        String sentence = "Bên A có nghĩa vụ giao hàng đúng chủng loại, số lượng, chất lượng và thời hạn đã thỏa thuận. ";
        Extent justified = extent(BusinessDocument.draw(FONTS, official(null), "t",
                List.of(Paragraph.of(sentence.repeat(4)))));
        Extent ragged = extent(BusinessDocument.draw(FONTS, official(null), "t",
                List.of(Paragraph.of(sentence.repeat(4)).align(Align.LEFT))));

        float margin = PDRectangle.A4.getWidth() - BusinessDocument.mm(15);
        assertThat(justified.right()).isBetween(margin - 1.5f, margin + 0.5f);
        assertThat(ragged.right()).isLessThanOrEqualTo(margin + 0.5f);
        // The words survive the spacing: a reader's search still finds the sentence.
        assertThat(justified.pages().getFirst()).contains("giao hàng đúng chủng loại, số lượng, chất lượng");
    }

    @Test
    @DisplayName("a table runs over pages with its header repeated, never below the bottom margin, its totals kept "
            + "together and its figures against the right edge of their column")
    void tableBreaksOverPages() throws IOException {
        List<Column> columns = List.of(new Column("STT", 30, Align.CENTRE),
                new Column("Tên hàng, quy cách", 240, Align.LEFT), new Column("Số lượng", 70, Align.RIGHT),
                new Column("Thành tiền (VNĐ)", 100, Align.RIGHT));
        List<List<Cell>> rows = new ArrayList<>();
        for (int i = 1; i <= 70; i++) {
            rows.add(List.of(Cell.of(String.valueOf(i)),
                    Cell.bold("Ly giấy 12oz in theo thiết kế, mẫu số " + i)
                            .details(List.of("Đóng gói: 20 thùng x 1.000 cái/thùng")),
                    Cell.of("20.000"), Cell.of("25.000.000")));
        }
        List<Summary> totals = List.of(new Summary("Cộng tiền hàng", "1.750.000.000", false),
                new Summary("Tổng cộng thanh toán", "1.890.000.000", true));

        byte[] pdf = BusinessDocument.draw(FONTS, official(null), "t",
                List.of(new Heading("ĐIỀU 1. HÀNG HÓA"), new Table(columns, rows, totals, 3)));
        Extent extent = extent(pdf);

        assertThat(extent.pages()).hasSizeGreaterThan(2);
        assertThat(extent.pages()).allSatisfy(page -> assertThat(page).contains("Tên hàng, quy cách",
                "Thành tiền (VNĐ)"));
        assertThat(extent.lowest()).as("above the bottom margin, 20 mm")
                .isLessThanOrEqualTo(PDRectangle.A4.getHeight() - BusinessDocument.mm(20) + 0.5f);
        String last = extent.pages().getLast();
        assertThat(last).contains("Cộng tiền hàng", "1.750.000.000", "Tổng cộng thanh toán", "1.890.000.000");
        assertThat(extent.pages().get(extent.pages().size() - 2)).doesNotContain("Cộng tiền hàng");
        // Every row is there once, in order.
        String whole = String.join(" ", extent.pages());
        assertThat(whole).containsSubsequence("mẫu số 1 ", "mẫu số 35 ", "mẫu số 70 ");
        assertThat(whole.split("mẫu số 70 ", -1)).hasSize(2);
        // The amount column ends at the right margin: its figures sit against it, less the cell's padding.
        assertThat(extent.right()).isBetween(PDRectangle.A4.getWidth() - BusinessDocument.mm(15) - 6,
                PDRectangle.A4.getWidth() - BusinessDocument.mm(15));
    }

    @Test
    @DisplayName("demo run 24/11, D-7: a table header never stays alone at the foot of a page - a first row that "
            + "moves to the next page whole takes the header, the sentence introducing the table and the heading over "
            + "it along; a tall row (40 LPNs) starts under its header and runs on, never leaving a page half empty")
    void aTableHeaderKeepsItsFirstRow() throws IOException {
        List<Column> columns = List.of(new Column("STT", 30, Align.CENTRE),
                new Column("Tên hàng, mã thùng", 300, Align.LEFT), new Column("Số thùng", 70, Align.RIGHT));
        float usable = PDRectangle.A4.getHeight() - 2 * BusinessDocument.mm(20);
        // A goods line of a few lines: the header and the start of a row still fit at the foot, the row does not.
        List<Cell> goods = List.of(Cell.of("1"), Cell.bold("Ly giấy 12oz in logo Mộc Lam").details(List.of(
                "LY12-MOC-01", "Lô LOT-1", "Đóng gói 1.000 cái/thùng", "Giao đợt 1 và đợt 2")), Cell.of("80"));
        Extent moved = extent(BusinessDocument.draw(FONTS, official(null), "t", List.of(
                Paragraph.of("Điều khoản đầu tiên."), new Spacer(usable - 125),
                new Heading("ĐIỀU 2. NGHĨA VỤ ĐÃ HOÀN THÀNH"),
                Paragraph.of("Bên A đã giao hàng cho Bên B, cụ thể như sau:"), new Table(columns, List.of(goods)))));

        assertThat(moved.pages()).hasSize(2);
        assertThat(moved.pages().get(0)).as("no header, no lead-in, no heading left behind")
                .contains("Điều khoản đầu tiên").doesNotContain("Tên hàng, mã thùng", "cụ thể như sau", "ĐIỀU 2");
        assertThat(moved.pages().get(1)).containsSubsequence("ĐIỀU 2. NGHĨA VỤ ĐÃ HOÀN THÀNH", "cụ thể như sau",
                "Tên hàng, mã thùng", "Ly giấy 12oz in logo Mộc Lam", "Giao đợt 1 và đợt 2");

        // A delivery note's line: one SKU, its forty LPNs listed under it - taller than a third of a page.
        List<String> lpns = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            lpns.add("Mã thùng (LPN): LPN-20261124-" + String.format("%06d", i));
        }
        Extent cut = extent(BusinessDocument.draw(FONTS, official(null), "t", List.of(
                Paragraph.of("Điều khoản đầu tiên."), new Spacer(usable - 250),
                Paragraph.of("Hai Bên giao nhận hàng hóa như sau:"),
                new Table(columns, List.of(List.of(Cell.of("1"), Cell.bold("Ly giấy 16oz").details(lpns),
                        Cell.of("40")))))));
        assertThat(cut.pages()).hasSize(2);
        assertThat(cut.pages().get(0)).as("the header with the start of its row, the rest of the page used")
                .containsSubsequence("Hai Bên giao nhận", "Tên hàng, mã thùng", "Ly giấy 16oz", "LPN-20261124-000001",
                        "LPN-20261124-000005");
        assertThat(cut.pages().get(1)).as("the row runs on under its header repeated, more than one line of it")
                .containsSubsequence("Tên hàng, mã thùng", "LPN-20261124-000039", "LPN-20261124-000040");
        assertThat(String.join(" ", cut.pages()).split("LPN-20261124-000020", -1)).as("every LPN once").hasSize(2);
    }

    @Test
    @DisplayName("a heading is never left at the foot of a page, and signature blocks move to the next page whole")
    void headingsAndSignaturesAreNotOrphaned() throws IOException {
        headingAndSignatureBlocks();
    }

    @Test
    @DisplayName("signatures straight after a table that do not fit under it take its last row and totals to the next "
            + "page: that page never opens with the signatures alone")
    void signaturesTakeTheTableEndBeforeThem() throws IOException {
        List<Column> columns = List.of(new Column("STT", 30, Align.CENTRE), new Column("Tên hàng", 300, Align.LEFT),
                new Column("Số lượng", 70, Align.RIGHT));
        List<List<Cell>> rows = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            rows.add(List.of(Cell.of(String.valueOf(i)), Cell.of("Ly giấy mẫu số " + i), Cell.of("1.000")));
        }
        float usable = PDRectangle.A4.getHeight() - 2 * BusinessDocument.mm(20);
        Extent extent = extent(BusinessDocument.draw(FONTS, official(null), "t", List.of(
                Paragraph.of("Điều khoản đầu tiên."), new Spacer(usable - 280),
                new Table(columns, rows, List.of(new Summary("Tổng cộng", "8.000", true)), 2),
                new Signatures(null, List.of(new Signer("ĐẠI DIỆN BÊN GIAO", "(Ký, ghi rõ họ tên)", null),
                        new Signer("ĐẠI DIỆN BÊN NHẬN", "(Ký, ghi rõ họ tên)", null))))));

        assertThat(extent.pages()).hasSize(2);
        assertThat(extent.pages().get(0)).contains("mẫu số 1 ").doesNotContain("mẫu số 8", "ĐẠI DIỆN");
        assertThat(extent.pages().get(1)).as("the last row and the total go over with the signatures")
                .containsSubsequence("Tên hàng", "mẫu số 8", "Tổng cộng", "8.000", "ĐẠI DIỆN BÊN GIAO");
    }

    private void headingAndSignatureBlocks() throws IOException {
        // Fills a page to within two lines of its foot: a heading or a signature block no longer fits whole.
        float usable = PDRectangle.A4.getHeight() - 2 * BusinessDocument.mm(20);
        List<Block> blocks = new ArrayList<>(List.of(new Spacer(usable - 30)));
        blocks.add(new Heading("ĐIỀU 12. HIỆU LỰC HỢP ĐỒNG"));
        blocks.add(Paragraph.of("Hợp đồng này có hiệu lực kể từ ngày hai Bên ký."));
        blocks.add(new Spacer(usable - 120));
        blocks.add(new Signatures("Thành phố Hồ Chí Minh, ngày 23 tháng 11 năm 2026", List.of(
                new Signer("ĐẠI DIỆN BÊN A", "(Ký, ghi rõ họ tên, đóng dấu)", "Trần Quốc An"),
                new Signer("ĐẠI DIỆN BÊN B", "(Ký, ghi rõ họ tên, đóng dấu)", "Lê Thị Mộc Lam"))));

        Extent extent = extent(BusinessDocument.draw(FONTS, official(null), "t", blocks));

        assertThat(extent.pages()).hasSize(3);
        assertThat(extent.pages().get(0)).doesNotContain("ĐIỀU 12");
        assertThat(extent.pages().get(1)).contains("ĐIỀU 12. HIỆU LỰC HỢP ĐỒNG", "có hiệu lực kể từ ngày")
                .doesNotContain("ĐẠI DIỆN");
        assertThat(extent.pages().get(2)).contains("ĐẠI DIỆN BÊN A", "ĐẠI DIỆN BÊN B", "(Ký, ghi rõ họ tên, đóng dấu)",
                "Trần Quốc An", "Lê Thị Mộc Lam", "ngày 23 tháng 11 năm 2026");
    }

    @Test
    @DisplayName("signature blocks never open a page alone: the paragraph before them goes over with them")
    void signaturesTakeTheParagraphBeforeThem() throws IOException {
        // Room is left for the closing paragraph but not for the signature blocks under it.
        float usable = PDRectangle.A4.getHeight() - 2 * BusinessDocument.mm(20);
        List<Block> blocks = List.of(Paragraph.of("Điều khoản đầu tiên của hợp đồng."), new Spacer(usable - 90),
                Paragraph.numbered("12.4.", Run.of("Hợp đồng được lập thành 02 bản có giá trị pháp lý như nhau.")),
                new Signatures(null, List.of(
                        new Signer("ĐẠI DIỆN BÊN A", "(Ký, ghi rõ họ tên, đóng dấu)", "Trần Quốc An"),
                        new Signer("ĐẠI DIỆN BÊN B", "(Ký, ghi rõ họ tên, đóng dấu)", null))));

        Extent extent = extent(BusinessDocument.draw(FONTS, official(null), "t", blocks));

        assertThat(extent.pages()).hasSize(2);
        assertThat(extent.pages().get(0)).contains("Điều khoản đầu tiên").doesNotContain("12.4.", "ĐẠI DIỆN");
        assertThat(extent.pages().get(1)).containsSubsequence("12.4.", "lập thành 02 bản", "ĐẠI DIỆN BÊN A",
                "ĐẠI DIỆN BÊN B");
    }

    @Test
    @DisplayName("every page carries the document's number and 'Trang x/y'; a draft carries its watermark on each")
    void footersAndWatermark() throws IOException {
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            blocks.add(Paragraph.of("Điều khoản thứ " + i + " của hợp đồng mua bán hàng hóa giữa hai Bên."));
        }
        byte[] pdf = BusinessDocument.draw(FONTS, official("BẢN NHÁP"), "Hợp đồng 045", blocks);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            assertThat(pages).isGreaterThan(1);
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("Hợp đồng 045");
            for (int page = 1; page <= pages; page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                assertThat(text.replaceAll("\\s+", " ")).contains("Số: 045/2026/HĐMB", "CÔNG TY AN PHÁT",
                        "Trang " + page + "/" + pages);
                // The watermark's letters are turned and come out of the PDF one by one.
                assertThat(text.replaceAll("\\s+", "")).contains("BẢNNHÁP");
            }
        }
        assertThat(extent(BusinessDocument.draw(FONTS, official(null), "t", blocks)).pages().getFirst()
                .replace(" ", "")).doesNotContain("BẢNNHÁP");
    }

    @Test
    @DisplayName("the three heads: the nation and motto, the company's own with its logo, an accounting voucher's")
    void heads() throws IOException {
        byte[] logo = new ClassPathResource("branding/seller-logo.png").getContentAsByteArray();
        Layout commercial = Layout.commercial(new Color(0x1F, 0x6F, 0x54), "Số: BG-01", "AN PHÁT", "Trang %d/%d",
                null);
        byte[] branded = BusinessDocument.draw(FONTS, commercial, "t", List.of(
                new BrandHeader(logo, LONG_NAME, List.of("Địa chỉ: Số 268 Đường số 7, Phường Tân Tạo, Thành phố Hồ "
                        + "Chí Minh", "MST: 0312345678   |   ĐT: (028) 3765 0000")),
                new Title("BẢNG BÁO GIÁ", null, "(V/v in ly giấy theo thiết kế)")));
        try (PDDocument document = Loader.loadPDF(branded)) {
            List<PDImageXObject> images = new ArrayList<>();
            for (var name : document.getPage(0).getResources().getXObjectNames()) {
                if (document.getPage(0).getResources().getXObject(name) instanceof PDImageXObject image) {
                    images.add(image);
                }
            }
            assertThat(images).as("the logo is embedded").hasSize(1);
        }
        Extent brandedExtent = extent(branded);
        assertThat(brandedExtent.pages().getFirst()).contains("MIỀN NAM VIỆT NAM", "MST: 0312345678", "BẢNG BÁO GIÁ");
        assertThat(brandedExtent.right()).isLessThanOrEqualTo(PDRectangle.A4.getWidth() - BusinessDocument.mm(15)
                + 0.5f);
        // Without a logo the head is still drawn: a company that has none still sends quotations.
        assertThat(extent(BusinessDocument.draw(FONTS, commercial, "t", List.of(new BrandHeader(null, "AN PHÁT",
                List.of("MST: 0312345678"))))).pages().getFirst()).contains("AN PHÁT", "MST: 0312345678");

        String record = extent(BusinessDocument.draw(FONTS, official(null), "t", List.of(
                new NationalHeader("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", "Độc lập - Tự do - Hạnh phúc", LONG_NAME,
                        "Số: 0210/2026/BBGN", "Thành phố Hồ Chí Minh, ngày 23 tháng 11 năm 2026")))).pages().getFirst();
        assertThat(record).contains("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", "Độc lập - Tự do - Hạnh phúc",
                "Số: 0210/2026/BBGN", "MIỀN NAM VIỆT NAM", "ngày 23 tháng 11 năm 2026");

        String voucher = extent(BusinessDocument.draw(FONTS, commercial, "t", List.of(
                new VoucherHeader("Đơn vị: " + LONG_NAME, "Địa chỉ: Số 268 Đường số 7, Phường Tân Tạo",
                        "Mẫu số: 02 - VT", "(Kèm theo Thông tư số 99/2025/TT-BTC ngày 27 tháng 10 năm 2025 của Bộ "
                        + "trưởng Bộ Tài chính)")))).pages().getFirst();
        assertThat(voucher).contains("Mẫu số: 02 - VT", "Thông tư số 99/2025/TT-BTC", "MIỀN NAM VIỆT NAM");
    }
}
