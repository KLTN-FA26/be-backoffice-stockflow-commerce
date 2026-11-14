package com.stockflow.fulfillment.internal.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.stockflow.common.label.LabelFonts;
import com.stockflow.fulfillment.api.ShippedCarton;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The delivery note of a shipment (kltn-docs 09 §4.2; glossary "POD"): the shipment's code a scanner reads, the order,
 * contract and batch, the parties, the vehicle, the cartons by SKU with their LPNs - and dotted lines where a fact is
 * not known yet, so the same form prints before the vehicle is booked.
 */
class DeliveryNoteDocumentTest {

    private static final String CUSTOMER = "CÔNG TY TRÁCH NHIỆM HỮU HẠN THƯƠNG MẠI DỊCH VỤ SẢN XUẤT BAO BÌ GIẤY VÀ "
            + "NHỰA MIỀN NAM VIỆT NAM";

    private final DeliveryNoteDocument document = new DeliveryNoteDocument(new LabelFonts());

    private static List<DeliveryNoteDocument.SkuCartons> goods() {
        return DeliveryNoteDocument.bySku(List.of(
                new ShippedCarton("LPN-000003", "LY16-ACME-02", "LOT-2", 40),
                new ShippedCarton("LPN-000001", "LY12-ACME-01", "LOT-1", 50),
                new ShippedCarton("LPN-000002", "LY12-ACME-01", "LOT-1", 50)));
    }

    private static DeliveryNoteDocument.Facts facts(List<DeliveryNoteDocument.Vehicle> vehicles, Instant handedOver) {
        return new DeliveryNoteDocument.Facts("SO-20261109-000001-S1", "SO-20261109-000001",
                "HD-20261109-000001-1", 2, false, "Kho Hồ Chí Minh", "12 Đường Số 7, KCN Tân Bình, TP. Hồ Chí Minh",
                CUSTOMER, "Số 12 đường Nguyễn Huệ, phường Bến Nghé, quận 1, thành phố Hồ Chí Minh",
                "Trần Văn Bình - 0901234567", vehicles, handedOver, goods(), LocalDate.of(2026, 11, 15));
    }

    @Test
    @DisplayName("the cartons are grouped by SKU in code order: how many, their pieces, their LPNs in order")
    void cartonsBySku() {
        assertThat(goods()).containsExactly(
                new DeliveryNoteDocument.SkuCartons("LY12-ACME-01", 2, 100, List.of("LPN-000001", "LPN-000002")),
                new DeliveryNoteDocument.SkuCartons("LY16-ACME-02", 1, 40, List.of("LPN-000003")));
    }

    @Test
    @DisplayName("after the handover: shipment, order, contract and batch, both parties, the vehicle, every LPN, the "
            + "totals and the three signatures - all inside the margins")
    void everyWordOnThePage() throws IOException {
        byte[] pdf = document.render(facts(List.of(new DeliveryNoteDocument.Vehicle("LD-20261115-0003",
                "Xe tải Nhanh", "51C-123.45", "Nguyễn Văn Tài", "0912345678", "WB-778899")),
                Instant.parse("2026-11-15T02:05:00Z")));
        float[] rightmost = {0};
        StringBuilder words = new StringBuilder();
        try (var doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    for (TextPosition glyph : positions) {
                        rightmost[0] = Math.max(rightmost[0], glyph.getXDirAdj() + glyph.getWidthDirAdj());
                    }
                    words.append(text).append(' ');
                    super.writeString(text, positions);
                }
            };
            stripper.getText(doc);
        }
        assertThat(rightmost[0]).as("inside the right margin").isLessThanOrEqualTo(PDRectangle.A4.getWidth() - 50 + 1);
        String text = words.toString().replaceAll("\\s+", " ");
        assertThat(text).contains("BIÊN BẢN GIAO NHẬN HÀNG HOÁ", "Mã shipment: SO-20261109-000001-S1",
                "Đơn hàng: SO-20261109-000001", "Hợp đồng: HD-20261109-000001-1 - đợt giao 2", "Kho Hồ Chí Minh",
                "CÔNG TY TRÁCH NHIỆM HỮU HẠN", "MIỀN NAM VIỆT NAM", "Người nhận: Trần Văn Bình - 0901234567",
                "Chuyến LD-20261115-0003 - Xe tải Nhanh: biển số 51C-123.45; tài xế Nguyễn Văn Tài; điện thoại "
                        + "0912345678; mã vận đơn WB-778899",
                "Thời điểm bàn giao: 09:05 15/11/2026", "LY12-ACME-01: 2 thùng, 100 cái",
                "Thùng (LPN): LPN-000001, LPN-000002", "LY16-ACME-02: 1 thùng, 40 cái", "Tổng cộng: 3 thùng, 140 cái",
                "BÊN GIAO (KHO)", "TÀI XẾ", "BÊN NHẬN", "(POD)")
                .doesNotContain("Giao lại");
    }

    @Test
    @DisplayName("printed before the vehicle is booked: the vehicle, driver, waybill and handover are lines to fill in")
    void beforeTheVehicle() throws IOException {
        String text = text(document.render(facts(List.of(), null)));
        assertThat(text).contains("Xe: biển số ..............................; tài xế ..............................",
                "Thời điểm bàn giao: ..............................", "Tổng cộng: 3 thùng, 140 cái");
    }

    @Test
    @DisplayName("the shipment's code is printed as a barcode the carrier and the warehouse scan (E20)")
    void codeIsScannable() throws Exception {
        byte[] pdf = document.render(facts(List.of(), null));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 200);
            int[] pixels = page.getRGB(0, 0, page.getWidth(), page.getHeight(), null, 0, page.getWidth());
            var decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                    new RGBLuminanceSource(page.getWidth(), page.getHeight(), pixels))));
            assertThat(decoded.getText()).isEqualTo("SO-20261109-000001-S1");
        }
    }

    private static String text(byte[] pdf) throws IOException {
        try (var doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
        }
    }
}
