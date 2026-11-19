package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.label.LabelFonts;
import com.stockflow.fulfillment.internal.domain.loadplanning.Layout;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The loading sheet of a vehicle (kltn-docs 09 §4.2 step 5): the PDF opens, says what the vehicle is - Vietnamese
 * diacritics included - lists every package once in loading order across as many pages as it needs, and draws the
 * placements to scale from above and from the side.
 */
class LoadingSheetDocumentTest {

    private static final Instant PRINTED = Instant.parse("2026-11-18T03:30:00Z");

    private final LoadingSheetDocument document = new LoadingSheetDocument(new LabelFonts());

    /** Each carton weighs 20 kg: 160 of them are 3,200 kg on a 5,000 kg truck, a load BR-12 allows. */
    private static final BigDecimal CARTON_KG = new BigDecimal("20");

    /**
     * {@code count} cartons 600 long (along the truck) x 400 wide x 300 high filled into a 5.2 x 2.05 x 2.05 m truck:
     * five across, then along, then up; every seventh turned on its side. SKUs alternate between two. The stored fill
     * percentages are deliberately wrong (42.9 and 3.1): the sheet must work its percentages out from the figures it
     * prints, never read these.
     */
    private static Layout truck(int count) {
        List<Layout.Box> boxes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int across = i % 5;
            int along = (i / 5) % 8;
            int up = i / 40;
            boolean turned = i % 7 == 6;
            boxes.add(new Layout.Box(across * 400, up * 300, along * 600, 400, 300, 600, "ref-" + i, null,
                    "LPN-20261117-%06d".formatted(i + 1), i % 2 == 0 ? "SOFA-3S-GREY" : "GHE-AN-ACME-01", null,
                    new Layout.Dims(600, 400, 300), turned ? "WLH" : "LWH", true, CARTON_KG, i + 1));
        }
        return new Layout(1, "TRUCK-5T", UUID.randomUUID(), new Layout.Dims(5200, 2050, 2050), new BigDecimal("5000"),
                List.of(), boxes, count * 400L * 300 * 600, CARTON_KG.multiply(BigDecimal.valueOf(count)),
                new BigDecimal("42.9"), new BigDecimal("3.1"));
    }

    private static Map<String, String> orders(Layout layout) {
        Map<String, String> orders = new HashMap<>();
        for (int i = 0; i < layout.boxes().size(); i++) {
            orders.put(layout.boxes().get(i).ref(), i % 3 == 0 ? "SO-20261117-000011" : "SO-20261117-000012");
        }
        return orders;
    }

    private LoadingSheetDocument.Facts booked(Layout layout) {
        return new LoadingSheetDocument.Facts("LP-20261117-0004", "Xe tải 5 tấn", "Kho Hồ Chí Minh",
                "LD-20261117-0006", "Xe tải Thăng Long", "WB-778899", "51C-246.80", "Nguyễn Văn Tài", "0912345678",
                layout, orders(layout), PRINTED);
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
        }
    }

    private static int pages(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    @Test
    @DisplayName("a booked vehicle: plan, load, type, warehouse, carrier, waybill, plate, driver, the fills, every LPN "
            + "with its order, SKU, size, weight, position and orientation - one A4 page, Vietnamese diacritics intact")
    void theWholeSheet() throws Exception {
        Layout layout = truck(3);
        byte[] pdf = document.render(booked(layout));
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target", "loading-sheet-sample-small.pdf"), pdf);

        assertThat(pdf).startsWith("%PDF".getBytes());
        assertThat(pages(pdf)).isEqualTo(1);
        String text = text(pdf);
        assertThat(text).contains("PHIẾU XẾP HÀNG", "LP-20261117-0004", "Xe #1 (TRUCK-5T)", "LD-20261117-0006",
                "Xe tải 5 tấn", "Kho Hồ Chí Minh", "Hãng vận chuyển", "Xe tải Thăng Long", "WB-778899", "51C-246.80",
                "Nguyễn Văn Tài - 0912345678", "520 × 205 × 205 cm", "5.000 kg", "10:30 18/11/2026",
                "TỔNG KẾT: 3 kiện", "Lấp đầy thể tích: 0,22 / 21,85 m³ 1%", "Lấp đầy khối lượng: 60 / 5.000 kg 1,2%",
                "Sơ đồ nhìn từ trên xuống", "Sơ đồ nhìn từ bên trái xe", "Đầu xe ở bên trái, cửa xe ở bên phải",
                "DANH SÁCH KIỆN THEO THỨ TỰ XẾP", "STT", "Mã kiện (LPN)", "Hướng đặt", "Giữ nguyên",
                "LPN-20261117-000001", "LPN-20261117-000002", "LPN-20261117-000003", "SO-20261117-000011",
                "SO-20261117-000012", "SOFA-3S-GREY (2 kiện)", "GHE-AN-ACME-01 (1 kiện)", "60×40×30",
                "0 / 0 / 0", "Trang 1/1", "Tổng: 3 kiện 60");
        assertThat(text).as("no glyph was replaced by a question mark").doesNotContain("?");
        assertThat(text).as("the stored fill percentages are not what is printed").doesNotContain("42,9%", "3,1%");
    }

    @Test
    @DisplayName("a carton lying other than as measured says Xoay with its placed D×R×C")
    void turnedCartons() throws Exception {
        // The 7th carton (index 6) is turned: measured 600x400x300 placed 400 across x 300 up x 600 along as WLH.
        String text = text(document.render(booked(truck(7))));
        assertThat(text).contains("Xoay: 60×40×30");
    }

    @Test
    @DisplayName("not booked yet: the load and the booking are dashes or 'no load', the vehicle type falls back to its code")
    void beforeBooking() throws Exception {
        LoadingSheetDocument.Facts facts = new LoadingSheetDocument.Facts("LP-20261117-0004", null, null, null, null,
                null, null, null, null, truck(2), Map.of(), PRINTED);
        String text = text(document.render(facts));
        assertThat(text).contains("Mã chuyến Chưa có chuyến", "Hãng vận chuyển —", "Mã vận đơn —", "Biển số —",
                "Tài xế —", "Loại xe TRUCK-5T", "Kho xuất —");
    }

    @Test
    @DisplayName("a vehicle with no package still prints: the sheet says it is empty")
    void emptyVehicle() throws Exception {
        Layout empty = new Layout(2, "TRUCK-5T", UUID.randomUUID(), new Layout.Dims(5200, 2050, 2050),
                new BigDecimal("5000"), List.of(), List.of(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        byte[] pdf = document.render(booked(empty));
        assertThat(pages(pdf)).isEqualTo(1);
        assertThat(text(pdf)).contains("Xe này chưa có kiện nào.", "TỔNG KẾT: 0 kiện");
    }

    @Test
    @DisplayName("many packages flow over as many pages as they need: every LPN once, in loading order, the page "
            + "number on every page, the table header repeated, everything inside the margins")
    void manyPackages() throws Exception {
        Layout layout = truck(160);
        byte[] pdf = document.render(booked(layout));
        Files.write(Path.of("target", "loading-sheet-sample-large.pdf"), pdf);
        int pages = pages(pdf);
        // Page 1 holds the vehicle and the diagram, with a few rows; then about fifty rows to a page.
        assertThat(pages).isBetween(4, 5);

        float[] rightmost = {0};
        StringBuilder all = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    for (TextPosition glyph : positions) {
                        rightmost[0] = Math.max(rightmost[0], glyph.getXDirAdj() + glyph.getWidthDirAdj());
                    }
                    super.writeString(text, positions);
                }
            };
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String onPage = stripper.getText(doc).replaceAll("\\s+", " ");
                assertThat(onPage).as("page %d is numbered", page).contains("Trang %d/%d".formatted(page, pages));
                if (page > 1) {
                    assertThat(onPage).as("page %d repeats the header", page).contains("Mã kiện (LPN)");
                }
                all.append(onPage).append(' ');
            }
        }
        assertThat(rightmost[0]).isLessThanOrEqualTo(PDRectangle.A4.getWidth() - 36 + 1);
        String text = all.toString();
        List<Integer> positions = new ArrayList<>();
        for (int i = 1; i <= 160; i++) {
            String lpn = "LPN-20261117-%06d".formatted(i);
            var matcher = Pattern.compile(Pattern.quote(lpn)).matcher(text);
            assertThat(matcher.find()).as(lpn).isTrue();
            positions.add(matcher.start());
            assertThat(matcher.find()).as(lpn + " appears once").isFalse();
        }
        assertThat(positions).as("in loading order").isSorted();
        assertThat(text).contains("Tổng: 160 kiện 3.200", "Lấp đầy thể tích: 11,52 / 21,85 m³ 52,7%",
                "Lấp đầy khối lượng: 3.200 / 5.000 kg 64%");
    }

    @Test
    @DisplayName("the diagram is drawn to scale from the placements: the colour of each SKU is on the page, and a "
            + "gusset block is grey")
    void diagramIsDrawn() throws Exception {
        Layout base = truck(10);
        Layout withGusset = new Layout(base.seq(), base.code(), base.vehicleTypeId(), base.innerMm(),
                base.maxPayloadKg(), List.of(new Layout.Block(0, 1900, 0, 150, 150, 150)), base.boxes(),
                base.usedVolumeMm3(), base.usedWeightKg(), base.volumeFillPct(), base.weightFillPct());
        byte[] pdf = document.render(booked(withGusset));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 100);
            Files.write(Path.of("target", "loading-sheet-page1.png"), png(page));
            assertThat(count(page, 0xA9D6F5)).as("first SKU's colour").isPositive();
            assertThat(count(page, 0xF5C6A0)).as("second SKU's colour").isPositive();
            assertThat(count(page, 0xB0B5BD)).as("the gusset's grey").isPositive();
        }
    }

    @Test
    @DisplayName("each fill percentage is used / capacity of the very figures printed beside it, for volume and weight, "
            + "at any load - whatever fill the layout carries")
    void percentagesAreUsedOverCapacity() throws Exception {
        BigDecimal innerM3 = BigDecimal.valueOf(5200L * 2050 * 2050).movePointLeft(9);
        NumberFormat vi = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi"));
        vi.setMaximumFractionDigits(1);
        for (int count : new int[] {1, 3, 40, 100, 160, 200}) {
            Layout layout = truck(count);
            BigDecimal usedM3 = BigDecimal.valueOf(layout.usedVolumeMm3()).movePointLeft(9);
            String volume = vi.format(usedM3.multiply(BigDecimal.valueOf(100)).divide(innerM3, 1, RoundingMode.HALF_UP));
            String weight = vi.format(layout.usedWeightKg().multiply(BigDecimal.valueOf(100))
                    .divide(layout.maxPayloadKg(), 1, RoundingMode.HALF_UP));
            String text = text(document.render(booked(layout)));
            assertThat(text).as("%d cartons: volume", count).containsPattern(
                    "Lấp đầy thể tích: [\\d.,]+ / 21,85 m³ " + Pattern.quote(volume) + "%");
            assertThat(text).as("%d cartons: weight", count).containsPattern(
                    "Lấp đầy khối lượng: [\\d.,]+ / 5\\.000 kg " + Pattern.quote(weight) + "%");
        }
    }

    @Test
    @DisplayName("a long label code and SKU are printed whole - shrunk to their column, never cut")
    void longCodesAreNotCut() throws Exception {
        Layout base = truck(2);
        List<Layout.Box> boxes = new ArrayList<>();
        for (Layout.Box b : base.boxes()) {
            boxes.add(new Layout.Box(b.xMm(), b.yMm(), b.zMm(), b.wMm(), b.hMm(), b.dMm(), b.ref(), null,
                    "SO-20261118-8115101-C" + b.loadSequence(), "T3FA69F49614E-C70D3-LONG", null, b.origMm(),
                    b.orientation(), true, b.weightKg(), b.loadSequence()));
        }
        Layout layout = new Layout(base.seq(), base.code(), base.vehicleTypeId(), base.innerMm(), base.maxPayloadKg(),
                List.of(), boxes, base.usedVolumeMm3(), base.usedWeightKg(), base.volumeFillPct(),
                base.weightFillPct());
        String text = text(document.render(booked(layout)));
        assertThat(text).contains("SO-20261118-8115101-C1", "SO-20261118-8115101-C2", "T3FA69F49614E-C70D3-LONG")
                .doesNotContain("…");
    }

    @Test
    @DisplayName("a name with a tab or an emoji and a very long carrier name still print: nothing throws, the text "
            + "is cut inside its column")
    void awkwardText() throws Exception {
        LoadingSheetDocument.Facts facts = new LoadingSheetDocument.Facts("LP-1", "Xe\t5 tấn 🚚",
                "Kho " + "Hồ Chí Minh ".repeat(20), "LD-1", "Công ty vận tải ".repeat(15), "WB-1", "51C-1", "Tài xế 😀",
                null, truck(2), Map.of(), PRINTED);
        byte[] pdf = document.render(facts);
        assertThat(pages(pdf)).isEqualTo(1);
        assertThat(text(pdf)).contains("Xe 5 tấn ?", "Công ty vận tải").contains("…");
    }

    private static int count(BufferedImage image, int rgb) {
        int found = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == rgb) {
                    found++;
                }
            }
        }
        return found;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
