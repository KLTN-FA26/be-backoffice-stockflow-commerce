package com.stockflow.order.internal.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.stockflow.common.label.LabelFonts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production order slip (4A-3, kltn-docs 17 §4.3 step 6's table): every field of the header and of each line is on
 * it, in Vietnamese, and its code - the lot of the goods - is a barcode a scanner reads.
 */
class ProductionOrderSlipDocumentTest {

    private static final UUID DESIGN = UUID.fromString("0192f000-0000-7000-8000-000000000001");
    private static final String CHECKSUM = "ab".repeat(32);

    private final ProductionOrderSlipDocument document = new ProductionOrderSlipDocument(new LabelFonts());

    private static ProductionOrderSlipDocument.Facts facts(List<ProductionOrderSlipDocument.SlipLine> lines) {
        return new ProductionOrderSlipDocument.Facts("PRD-20261110-0007", Instant.parse("2026-11-10T03:15:00Z"),
                "XUONG-IN", "Xưởng in Bình Dương", "Chị Lan - 0909123456 - don@xuong.vn", "SO-20261101-000042",
                "Công ty Cà phê Sáng", 2, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), "Kho Hồ Chí Minh",
                "12 Nguyễn Huệ, TP HCM", LocalDate.of(2026, 11, 30), true, new BigDecimal("1250.5000"), "VND",
                "Giao trước 10 giờ", "Nguyễn Văn Điều Phối", "Trần Thị Duyệt", lines);
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
    @DisplayName("every field of 17 §4.3's production order is on the slip")
    void everyField() throws Exception {
        byte[] pdf = document.render(facts(List.of(line("CUP-CAFE-001", "600 x 400 x 350 mm", "Dán nhãn mã đơn"),
                line("CUP-CAFE-002", null, null))));

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = text(pdf);
        assertThat(text)
                .contains("PHIẾU ĐƠN SẢN XUẤT", "PRD-20261110-0007")
                .as("approved and sent at, in Vietnam's time").contains("10:15 10/11/2026")
                .contains("SO-20261101-000042", "Công ty Cà phê Sáng")
                .contains("Đợt giao 2", "01/12/2026", "03/12/2026")
                .contains("Xưởng in Bình Dương (XUONG-IN)", "Chị Lan - 0909123456 - don@xuong.vn")
                .contains("Kho Hồ Chí Minh", "12 Nguyễn Huệ, TP HCM")
                .contains("Ngày cần hàng: 30/11/2026")
                .contains("Cần duyệt mẫu in: CÓ")
                .contains("CUP-CAFE-001", "CUP-CAFE-002", "Ly giấy in logo")
                .contains("12 x 50 cái/thùng = 600 cái")
                .contains("600 x 400 x 350 mm")
                .as("a SKU with no carton size recorded").contains("kích thước thùng: chưa rõ")
                .contains("Dán nhãn mã đơn")
                .contains(DESIGN.toString(), CHECKSUM)
                .contains("Tổng cộng: 24 thùng, 1200 cái")
                .contains("1250.5 VND")
                .contains("Giao trước 10 giờ")
                .contains("Nguyễn Văn Điều Phối", "Trần Thị Duyệt");
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
    @DisplayName("no sample, no price, no note: the slip says so and leaves the options out")
    void withoutOptions() throws Exception {
        var plain = new ProductionOrderSlipDocument.Facts("PRD-20261110-0008", Instant.parse("2026-11-10T03:15:00Z"),
                "XUONG-IN", "Xưởng in", null, "SO-1", null, 1, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1),
                "Kho HCM", null, LocalDate.of(2026, 12, 1), false, null, "VND", null, "A", "B",
                List.of(line("CUP-1", null, null)));
        String text = text(document.render(plain));
        assertThat(text).contains("Cần duyệt mẫu in: KHÔNG").doesNotContain("Tuỳ chọn", "Đơn giá gia công",
                "Ghi chú");
    }

    @Test
    @DisplayName("a long production order runs over several A4 pages")
    void manyLines() throws Exception {
        List<ProductionOrderSlipDocument.SlipLine> lines = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            lines.add(line("CUP-CAFE-%03d".formatted(i), "600 x 400 x 350 mm", "Dán nhãn"));
        }
        try (PDDocument doc = Loader.loadPDF(document.render(facts(lines)))) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            assertThat(new PDFTextStripper().getText(doc)).contains("CUP-CAFE-039", "Tổng cộng: 480 thùng");
        }
    }
}
