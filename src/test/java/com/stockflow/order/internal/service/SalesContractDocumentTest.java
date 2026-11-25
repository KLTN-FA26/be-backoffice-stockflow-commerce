package com.stockflow.order.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderAddressSnapshot;
import com.stockflow.order.internal.domain.OrderFee;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4B-4: the contract drawn as a PDF, in the form of a Vietnamese contract for the sale of goods - the nation's head,
 * the legal bases, both parties, the numbered clauses with their tables, the two signature blocks - every word inside
 * the margins of Nghị định 30 however long the parties' legal names are.
 */
class SalesContractDocumentTest {

    private static final ContractProperties TERMS = new ContractProperties(2, 5, 6, 8);

    private final SalesContractDocument document = new SalesContractDocument(new DocumentFonts(), TERMS, 7);

    private static SalesContractDocument.Facts facts() {
        return facts(new BigDecimal("2750000"), "DRAFT", true, 14, SalesDocumentSamples.LONG_BUYER);
    }

    private static final List<SalesContracts.InstallmentView> PAID_BEFORE = List.of(
            new SalesContracts.InstallmentView(1, "Đặt cọc khi ký hợp đồng", "ON_SIGNING",
                    new BigDecimal("30"), new BigDecimal("825000"), 3, null),
            new SalesContracts.InstallmentView(2, "Thanh toán trước khi giao", "BEFORE_DELIVERY", null,
                    new BigDecimal("1925000"), null, 1));

    private static SalesContractDocument.Facts facts(BigDecimal total, String status, boolean issued,
                                                     Integer returnWindowDays, SalesContracts.Party buyer) {
        return facts(total, status, issued, returnWindowDays, buyer, PAID_BEFORE);
    }

    private static SalesContractDocument.Facts facts(BigDecimal total, String status, boolean issued,
                                                     Integer returnWindowDays, SalesContracts.Party buyer,
                                                     List<SalesContracts.InstallmentView> plan) {
        OrderLine cups = Order.line(UUID.randomUUID(), new Sku("LY12-ACME-01"), 1000, Money.vnd(2_000), null)
                .withCartons(UUID.randomUUID(), 20, 50).withTax(BigDecimal.TEN);
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 1), UUID.randomUUID(), UUID.randomUUID(),
                List.of(cups), Instant.now());
        order.attachFees(List.of(OrderFee.of(UUID.randomUUID(), FeeKind.PLATE_FEE, new Sku("LY12-ACME-01"),
                Money.vnd(500_000), BigDecimal.TEN)));
        SalesContracts.ContractView view = new SalesContracts.ContractView(UUID.randomUUID(), "HD-20261109-000001-1",
                order.id().value(), order.orderNumber().value(), status, SalesDocumentSamples.SELLER, buyer, 2,
                new SalesContracts.Totals(new BigDecimal("2500000"), new BigDecimal("250000"), total, "VND"),
                List.of(new SalesContracts.BatchView(1, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3),
                        new BigDecimal("2200000"), List.of(new SalesContracts.BatchLineView(cups.id(), "LY12-ACME-01",
                        20, 1000)))),
                plan, null, null, null, null, null, null, null, null, null, null, 0, null, returnWindowDays);
        return new SalesContractDocument.Facts(view, order, LocalDate.of(2026, 11, 9),
                SalesDocumentSamples.letterhead(), SalesDocumentSamples.NAMES, issued);
    }

    @Test
    @DisplayName("the copy sent reads as a contract: head, bases, parties, twelve clauses with the goods, delivery and "
            + "payment tables, signature blocks - every glyph inside the margins of Nghị định 30")
    void everyWordOnThePage() throws IOException {
        byte[] pdf = SalesDocumentSamples.keep("contract-long-buyer-name", document.render(facts()));

        float[] extent = SalesDocumentSamples.extent(pdf);
        assertThat(extent[0]).as("left margin, 30 mm").isGreaterThanOrEqualTo(BusinessDocument.mm(30) - 0.5f);
        assertThat(extent[1]).as("right margin, 15 mm")
                .isLessThanOrEqualTo(PDRectangle.A4.getWidth() - BusinessDocument.mm(15) + 0.5f);
        String text = SalesDocumentSamples.text(pdf);
        assertThat(text).containsSubsequence("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", "Độc lập - Tự do - Hạnh phúc",
                "HỢP ĐỒNG MUA BÁN HÀNG HÓA", "Số: HD-20261109-000001-1",
                "Căn cứ Bộ luật Dân sự số 91/2015/QH13", "Căn cứ Luật Thương mại số 36/2005/QH11",
                "Căn cứ Đơn đặt hàng số SO-20261109-000001 của Bên B;",
                "Hôm nay, ngày 09 tháng 11 năm 2026, tại Thành phố Hồ Chí Minh, chúng tôi gồm:",
                "BÊN BÁN (BÊN A): CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT", "Mã số thuế", "0312345678",
                "(028) 3765 0000", "0071001234567 tại Ngân hàng TMCP Ngoại thương Việt Nam (Vietcombank) - Chi nhánh "
                        + "Bình Tây", "Ông/Bà Trần Quốc An - Chức vụ: Giám đốc",
                "BÊN MUA (BÊN B): CÔNG TY TRÁCH NHIỆM HỮU HẠN", "MIỀN NAM VIỆT NAM", "Trần Văn Bình",
                "ĐIỀU 1. HÀNG HÓA, SỐ LƯỢNG VÀ GIÁ CẢ", "Ly giấy 12oz in theo thiết kế",
                "Đóng gói: 20 thùng x 50 cái/thùng", "Phí khuôn, bản in",
                "ĐIỀU 2. CHẤT LƯỢNG, QUY CÁCH VÀ MẪU IN", "in mẫu miễn phí 2 lần", "±5%",
                "ĐIỀU 3. BAO BÌ, ĐÓNG GÓI", "ĐIỀU 4. GIAO NHẬN HÀNG HÓA", "Từ 01/12/2026 đến 03/12/2026",
                "LY12-ACME-01: 20 thùng (1.000 cái)", "2.200.000", "ĐIỀU 5. THANH TOÁN",
                "Đặt cọc khi ký hợp đồng", "Trong vòng 3 ngày kể từ ngày ký Hợp đồng", "30%", "825.000",
                "Thanh toán trước khi giao", "(đợt giao 1)", "1.925.000", "ĐIỀU 6. HÓA ĐƠN",
                "Nghị định số 254/2026/NĐ-CP", "ĐIỀU 7. KHIẾU NẠI, ĐỔI TRẢ VÀ BẢO HÀNH", "trong thời hạn 14 ngày",
                "trong thời hạn 6 tháng", "ĐIỀU 8. QUYỀN VÀ NGHĨA VỤ CỦA CÁC BÊN",
                "ĐIỀU 9. PHẠT VI PHẠM VÀ BỒI THƯỜNG THIỆT HẠI", "chịu phạt 8% giá trị phần nghĩa vụ hợp đồng bị vi phạm",
                "Điều 306 Luật Thương mại", "ĐIỀU 10. BẤT KHẢ KHÁNG", "ĐIỀU 11. GIẢI QUYẾT TRANH CHẤP",
                "ĐIỀU 12. HIỆU LỰC HỢP ĐỒNG", "lập thành 02 bản", "ĐẠI DIỆN BÊN A", "GIÁM ĐỐC",
                "(Ký, ghi rõ họ tên, đóng dấu)", "Trần Quốc An", "ĐẠI DIỆN BÊN B", "TỔNG GIÁM ĐỐC",
                "(Ký, ghi rõ họ tên, đóng dấu)", "Trần Văn Bình");
        assertThat(text).contains("Số: HD-20261109-000001-1", "Trang 1/").doesNotContain("VND", "null");
        // The copy the customer signs is drawn while the contract is still DRAFT: it must not say so.
        assertThat(SalesDocumentSamples.watermarkedPages(pdf)).isZero();
    }

    @Test
    @DisplayName("the total in words follows the total; a total not in whole dong leaves out the words, not the PDF")
    void totalInWords() throws IOException {
        String whole = SalesDocumentSamples.text(document.render(facts()));
        assertThat(whole).containsSubsequence("Tổng giá trị Hợp đồng (đã gồm thuế GTGT) 2.750.000",
                "Tổng giá trị Hợp đồng bằng chữ: Hai triệu bảy trăm năm mươi nghìn đồng.", "ĐIỀU 2.");

        String fraction = SalesDocumentSamples.text(document.render(facts(new BigDecimal("2750000.5"), "SENT", true,
                14, SalesDocumentSamples.BUYER)));
        assertThat(fraction).contains("Tổng giá trị Hợp đồng (đã gồm thuế GTGT)").doesNotContain("bằng chữ");
    }

    @Test
    @DisplayName("a draft previewed and a cancelled contract carry their watermark on every page; a sent one none")
    void watermark() throws IOException {
        byte[] draft = SalesDocumentSamples.keep("contract-draft-preview",
                document.render(facts(new BigDecimal("2750000"), "DRAFT", false, 14, SalesDocumentSamples.BUYER)));
        assertThat(SalesDocumentSamples.compact(draft)).contains("BẢNNHÁP");
        assertThat(SalesDocumentSamples.watermarkedPages(draft)).isEqualTo(SalesDocumentSamples.pages(draft));

        assertThat(SalesDocumentSamples.compact(document.render(facts(new BigDecimal("2750000"), "CANCELLED", false,
                14, SalesDocumentSamples.BUYER)))).contains("ĐÃHỦY").doesNotContain("BẢNNHÁP");
        assertThat(SalesDocumentSamples.watermarkedPages(document.render(facts(new BigDecimal("2750000"), "SIGNED",
                false, 14, SalesDocumentSamples.BUYER)))).isZero();
    }

    @Test
    @DisplayName("a clause whose fact is absent is left out and the rest renumbered: no agreed complaint period prints "
            + "the configured one returns apply, no tolerance and no warranty configured print none")
    void clausesFollowTheFacts() throws IOException {
        // MIG-2: a contract without its own period is held to the configured one, so that is the number printed.
        String unset = SalesDocumentSamples.text(document.render(facts(new BigDecimal("2750000"), "SENT", true,
                null, SalesDocumentSamples.BUYER)));
        assertThat(unset).contains("trong thời hạn 7 ngày kể từ ngày giao đợt đó")
                .doesNotContain("trong thời hạn 14 ngày", "Điều 318 Luật Thương mại", "ba tháng");

        SalesContractDocument plain = new SalesContractDocument(new DocumentFonts(), new ContractProperties(2, 0, 0, 0),
                7);
        String text = SalesDocumentSamples.text(plain.render(facts()));
        assertThat(text).doesNotContain("±", "bảo hành chất lượng in", "chịu phạt")
                // 2.3 is now the colour point, not a gap where the tolerance was; clause 9 starts at damages.
                .containsSubsequence("2.2.", "2.3. Màu sắc in theo mẫu in đã duyệt", "2.4.", "ĐIỀU 3.")
                .containsSubsequence("ĐIỀU 9.", "9.1. Ngoài tiền phạt", "9.2.", "ĐIỀU 10.");
    }

    @Test
    @DisplayName("the paper says what the system does: a cancellation refunds in full until the run is printed, the "
            + "tax difference is settled by the invoice, and only a plan paid partly after delivery prints the credit "
            + "condition a refused limit cancels the contract on")
    void paymentTermsFollowTheSystem() throws IOException {
        String paidBefore = SalesDocumentSamples.text(document.render(facts()));
        assertThat(paidBefore)
                .containsSubsequence("ĐIỀU 5. THANH TOÁN", "khoản thanh toán trước, không phải tiền đặt cọc",
                        "hoàn lại toàn bộ số tiền đã nhận, không tính phí", "chỉ được huỷ khi Bên A đồng ý",
                        "không vượt quá giá trị Hợp đồng", "ĐIỀU 6.")
                .contains("phần chênh lệch tiền thuế được thanh toán thêm hoặc hoàn lại theo hóa đơn")
                .doesNotContain("hạn mức công nợ");

        String onCredit = SalesDocumentSamples.text(document.render(facts(new BigDecimal("2750000"), "SENT", true, 14,
                SalesDocumentSamples.BUYER, List.of(
                        new SalesContracts.InstallmentView(1, "Thanh toán khi ký", "ON_SIGNING", new BigDecimal("30"),
                                new BigDecimal("825000"), 3, null),
                        new SalesContracts.InstallmentView(2, "Thanh toán sau giao", "AFTER_DELIVERY", null,
                                new BigDecimal("1925000"), 30, 1)))));
        assertThat(onCredit).containsSubsequence("ĐIỀU 5. THANH TOÁN", "khoản thanh toán trước",
                "Phần thanh toán sau giao hàng có hiệu lực khi Bên A xác nhận hạn mức công nợ của Bên B",
                "Hợp đồng bị huỷ bỏ và Bên A hoàn lại toàn bộ số tiền đã nhận", "ĐIỀU 6.");
    }

    @Test
    @DisplayName("the penalty a contract prints cannot pass the 8% of Luật Thương mại Điều 301; the other terms have "
            + "their bounds too")
    void termsAreBounded() {
        assertThatThrownBy(() -> new ContractProperties(2, 5, 6, 9)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("penalty-percent").hasMessageContaining("301");
        assertThatThrownBy(() -> new ContractProperties(2, 11, 6, 8)).hasMessageContaining("quantity-tolerance");
        assertThatThrownBy(() -> new ContractProperties(2, 5, -1, 8)).hasMessageContaining("warranty-months");
        assertThat(new ContractProperties(2, 10, 0, 8).penaltyPercent()).isEqualTo(8);
    }

    @Test
    @DisplayName("a realistic contract: three products, two fees, three deliveries, three instalments - kept to be "
            + "looked at")
    void realisticSample() throws IOException {
        OrderLine cups12 = Order.line(UUID.randomUUID(), new Sku("LY12-MLC-01"), 100_000, Money.vnd(1_250), null)
                .withCartons(UUID.randomUUID(), 100, 1000).withTax(new BigDecimal("8"));
        OrderLine cups16 = Order.line(UUID.randomUUID(), new Sku("LY16-MLC-02"), 30_000, Money.vnd(2_150), null)
                .withCartons(UUID.randomUUID(), 60, 500).withTax(new BigDecimal("8"));
        OrderLine bags = Order.line(UUID.randomUUID(), new Sku("TUI-MLC-03"), 8_000, Money.vnd(4_800), null)
                .withCartons(UUID.randomUUID(), 40, 200).withTax(BigDecimal.TEN);
        OrderAddressSnapshot address = new OrderAddressSnapshot("Nguyễn Minh Khoa", "0909 123 456",
                "Kho trung tâm Mộc Lam, Số 45 Đường Hồng Hà", null, "26734", "Phường Tân Sơn Nhất", "79",
                "Thành phố Hồ Chí Minh", "VN", null);
        Order order = Order.customerDraft(OrderNumber.of(LocalDate.of(2026, 11, 23), 45), UUID.randomUUID(),
                UUID.randomUUID(), List.of(cups12, cups16, bags), Instant.now(), "Nguyễn Minh Khoa",
                "khoa@moclam.example", "0909123456", address, address);
        order.attachFees(List.of(
                OrderFee.of(UUID.randomUUID(), FeeKind.PLATE_FEE, new Sku("LY12-MLC-01"), Money.vnd(1_500_000),
                        new BigDecimal("8")),
                OrderFee.of(UUID.randomUUID(), FeeKind.SHIPPING_FEE, null, Money.vnd(800_000), new BigDecimal("8"))));
        // Goods 227,900,000 + fees 2,300,000 = 230,200,000; VAT 19,184,000; with tax 249,384,000.
        SalesContracts.ContractView view = new SalesContracts.ContractView(UUID.randomUUID(), "HD-20261123-000045-1",
                order.id().value(), order.orderNumber().value(), "SENT", SalesDocumentSamples.SELLER,
                SalesDocumentSamples.BUYER, 2,
                new SalesContracts.Totals(new BigDecimal("230200000"), new BigDecimal("19184000"),
                        new BigDecimal("249384000"), "VND"),
                List.of(new SalesContracts.BatchView(1, LocalDate.of(2026, 12, 15), LocalDate.of(2026, 12, 17),
                                new BigDecimal("98820000"), List.of(
                                new SalesContracts.BatchLineView(cups12.id(), "LY12-MLC-01", 40, 40_000),
                                new SalesContracts.BatchLineView(cups16.id(), "LY16-MLC-02", 20, 10_000),
                                new SalesContracts.BatchLineView(bags.id(), "TUI-MLC-03", 20, 4_000))),
                        new SalesContracts.BatchView(2, LocalDate.of(2027, 1, 12), LocalDate.of(2027, 1, 12),
                                new BigDecimal("98820000"), List.of(
                                new SalesContracts.BatchLineView(cups12.id(), "LY12-MLC-01", 40, 40_000),
                                new SalesContracts.BatchLineView(cups16.id(), "LY16-MLC-02", 20, 10_000),
                                new SalesContracts.BatchLineView(bags.id(), "TUI-MLC-03", 20, 4_000))),
                        new SalesContracts.BatchView(3, null, null, new BigDecimal("49260000"), List.of(
                                new SalesContracts.BatchLineView(cups12.id(), "LY12-MLC-01", 20, 20_000),
                                new SalesContracts.BatchLineView(cups16.id(), "LY16-MLC-02", 20, 10_000)))),
                List.of(new SalesContracts.InstallmentView(1, "Đặt cọc khi ký hợp đồng", "ON_SIGNING",
                                new BigDecimal("30"), new BigDecimal("74815200"), 3, null),
                        new SalesContracts.InstallmentView(2, "Thanh toán trước đợt giao thứ nhất",
                                "BEFORE_DELIVERY", new BigDecimal("30"), new BigDecimal("74815200"), null, 1),
                        new SalesContracts.InstallmentView(3, "Thanh toán sau khi giao đủ hàng", "AFTER_DELIVERY",
                                new BigDecimal("40"), new BigDecimal("99753600"), 15, 3)),
                Instant.parse("2026-11-23T03:00:00Z"), UUID.randomUUID(), null, null, null, null, null, null, null,
                null, 2, null, 30);
        byte[] pdf = SalesDocumentSamples.keep("contract-sample", document.render(new SalesContractDocument.Facts(
                view, order, LocalDate.of(2026, 11, 23), SalesDocumentSamples.letterhead(),
                SalesDocumentSamples.NAMES, true)));

        String text = SalesDocumentSamples.text(pdf);
        assertThat(text).contains("Địa điểm giao hàng: Kho trung tâm Mộc Lam, Số 45 Đường Hồng Hà, Phường Tân Sơn "
                        + "Nhất, Thành phố Hồ Chí Minh. Người nhận hàng: Nguyễn Minh Khoa, điện thoại +84909123456.",
                "Hàng hóa được giao thành 3 đợt theo lịch sau:", "12/01/2027", "Hai Bên thống nhất sau",
                "Bên B thanh toán giá trị Hợp đồng cho Bên A thành 3 đợt như sau:",
                "Trong vòng 15 ngày kể từ ngày giao hàng (đợt giao 3)", "Tổng cộng 249.384.000",
                "Tổng giá trị Hợp đồng bằng chữ: Hai trăm bốn mươi chín triệu ba trăm tám mươi bốn nghìn đồng.");
        int pages = SalesDocumentSamples.pages(pdf);
        assertThat(text).contains("Trang " + pages + "/" + pages);
    }
}
