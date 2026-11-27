package com.stockflow.order.internal.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentSamples;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production order slip (4A-3, kltn-docs 17 §4.3): a production paper - what to make, how much, to which files, by
 * when and where to deliver - with its code as the lot of the goods (03 BR-03), and nothing a print shop does not need
 * to make them: no money, no sales data, no signatures (design 2026-11-19).
 */
class ProductionOrderSlipDocumentTest {

    private static final UUID DESIGN = UUID.fromString("0192f000-0000-7000-8000-000000000001");
    private static final String CHECKSUM = "ab".repeat(32);

    private final ProductionOrderSlipDocument document = new ProductionOrderSlipDocument(new DocumentFonts(),
            DocumentSamples.seller());

    private static ProductionOrderSlipDocument.Facts facts(List<ProductionOrderSlipDocument.SlipLine> lines) {
        return new ProductionOrderSlipDocument.Facts("PRD-20261110-0007", Instant.parse("2026-11-10T03:15:00Z"),
                "CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT", "XUONG-IN", "Xưởng in Bình Dương", "Chị Lan - 0909123456 - don@xuong.vn",
                "Kho Hồ Chí Minh", "12 Nguyễn Huệ, TP HCM", LocalDate.of(2026, 11, 30), true, "Giao trước 10 giờ",
                "Nguyễn Văn Điều Phối", "dieuphoi@stockflow.vn", lines);
    }

    private static ProductionOrderSlipDocument.SlipLine line(String sku, String size, String packing) {
        return new ProductionOrderSlipDocument.SlipLine(sku, "Ly giấy in logo", 12, 50, 600, size, packing, DESIGN,
                CHECKSUM);
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    @DisplayName("everything a print shop needs to make and deliver the goods is on the slip")
    void everyField() throws Exception {
        byte[] pdf = DocumentSamples.keep("production-order-slip-sample", document.render(facts(List.of(
                line("CUP-CAFE-001", "600 x 400 x 350 mm", "Dán nhãn mã đơn"), line("CUP-CAFE-002", null, null)))));
        assertThat(DocumentSamples.within(pdf, 20, 15)).as("inside the margins").isTrue();

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = text(pdf).replaceAll("\\s+", " ");
        assertThat(text)
                .as("the company's head: the name the order was sent under, then the letterhead's lines")
                .contains("CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT", "MST: 0312345678", "PHIẾU ĐẶT HÀNG SẢN XUẤT",
                        "Số: PRD-20261110-0007")
                .as("the date of the approval, in Vietnam's time")
                .contains("Thành phố Hồ Chí Minh, ngày 10 tháng 11 năm 2026")
                .containsSubsequence("Đơn vị sản xuất", "Xưởng in Bình Dương — mã XUONG-IN", "Liên hệ",
                        "Chị Lan - 0909123456 - don@xuong.vn", "Cần hàng trước ngày", "30/11/2026", "Giao về",
                        "Kho Hồ Chí Minh - 12 Nguyễn Huệ, TP HCM", "Duyệt mẫu in trước", "CÓ")
                .contains("CUP-CAFE-001", "CUP-CAFE-002", "Ly giấy in logo", "12", "50", "600")
                .contains("Thùng 600 x 400 x 350 mm")
                .as("a SKU with no carton size recorded").contains("Kích thước thùng: chưa khai báo")
                .contains("Đóng gói: Dán nhãn mã đơn")
                .contains("Giao hàng tại: Kho Hồ Chí Minh — 12 Nguyễn Huệ, TP HCM, trước ngày 30/11/2026.")
                .contains("Ghi chú: Giao trước 10 giờ")
                .contains("Ghi số phiếu PRD-20261110-0007 trên phiếu giao hàng")
                .contains("Nguyễn Văn Điều Phối (Điều phối sản xuất)", "dieuphoi@stockflow.vn")
                .as("approved at, in Vietnam's time").contains("10:15 ngày 10/11/2026")
                .contains("không cần chữ ký", "Trang 1/1");
        String flat = text.replaceAll("\\s+", "");
        assertThat(flat).as("the whole checksum, never shortened").contains(CHECKSUM).contains(DESIGN.toString());
    }

    @Test
    @DisplayName("no money, no sales data, no totals row and no signature: not what a print shop makes goods from")
    void leavesOutWhatIsNotProduction() throws Exception {
        String text = text(document.render(facts(List.of(line("CUP-CAFE-001", null, null),
                line("CUP-CAFE-002", null, null)))));
        assertThat(text).doesNotContain("Đơn giá", "Thành tiền", "VND", "Cộng", "Tổng cộng", "Khách hàng", "Đơn bán",
                "Đợt giao", "Người duyệt", "Ký");
    }

    @Test
    @DisplayName("what is not recorded is left out - no null, no empty label, no dangling separator")
    void missingPartsAreLeftOut() throws Exception {
        var plain = new ProductionOrderSlipDocument.Facts("PRD-20261110-0008", Instant.parse("2026-11-10T03:15:00Z"),
                " ", "XUONG-IN", "Xưởng in", null, "Kho HCM", null, LocalDate.of(2026, 12, 1), false, null, "A", null,
                List.of(line("CUP-1", null, null)));
        String text = text(document.render(plain)).replaceAll("\\s+", " ");
        assertThat(text)
                .as("no company named on the order: the letterhead's").contains("CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT")
                .contains("KHÔNG")
                .contains("Giao hàng tại: Kho HCM, trước ngày 01/12/2026.")
                .contains("Kích thước thùng: chưa khai báo")
                .contains("A (Điều phối sản xuất)")
                .doesNotContain("null", "Liên hệ :", "Liên hệ:", "Ghi chú", "Đóng gói", "(Điều phối sản xuất) -");
    }

    @Test
    @DisplayName("the code is printed as a barcode the receiving scanner reads (03 BR-03: the lot of the goods)")
    void codeIsScannable() throws Exception {
        byte[] pdf = document.render(facts(List.of(line("CUP-CAFE-001", null, null))));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 200);
            int[] pixels = page.getRGB(0, 0, page.getWidth(), page.getHeight(), null, 0, page.getWidth());
            var decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                    new RGBLuminanceSource(page.getWidth(), page.getHeight(), pixels))));
            assertThat(decoded.getText()).isEqualTo("PRD-20261110-0007");
        }
    }

    @Test
    @DisplayName("a long production order runs over several A4 pages, the table's header on each, pages numbered")
    void manyLines() throws Exception {
        List<ProductionOrderSlipDocument.SlipLine> lines = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            lines.add(line("CUP-CAFE-%03d".formatted(i), "600 x 400 x 350 mm", "Dán nhãn"));
        }
        try (PDDocument doc = Loader.loadPDF(document.render(facts(lines)))) {
            int pages = doc.getNumberOfPages();
            assertThat(pages).isGreaterThan(1);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("CUP-CAFE-039", "Trang " + pages + "/" + pages);
            assertThat(text.split("Tên hàng, quy cách", -1).length - 1).as("the header on each page the table spans")
                    .isGreaterThanOrEqualTo(pages - 1);
        }
    }
}
