package com.stockflow.order.internal.service;

import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two papers that follow a signed contract: its appendix (Bộ luật Dân sự 2015 Điều 403) and the record that
 * liquidates it (Điều 422). Both are set as the contract is - the nation's head, the bases, both parties, numbered
 * clauses, two signature blocks headed by the signatories' titles - and both state their figures in tables whose
 * totals the paper works out itself.
 */
class ContractFollowUpDocumentsTest {

    private static final String CONTRACT = "HD-20261109-000001-1";

    private final ContractAppendixDocument appendix = new ContractAppendixDocument(new DocumentFonts(),
            DocumentSamples.seller());
    private final ContractLiquidationDocument liquidation = new ContractLiquidationDocument(new DocumentFonts(),
            DocumentSamples.seller());

    private static ContractAppendixDocument.Facts appendixFacts(boolean goods, boolean draft) {
        return new ContractAppendixDocument.Facts(1, "PL01-" + CONTRACT, CONTRACT, LocalDate.of(2026, 11, 9),
                SalesDocumentSamples.SELLER, SalesDocumentSamples.BUYER, LocalDate.of(2026, 11, 23),
                "Bên B đề nghị tăng số lượng ly 12oz và lùi ngày giao đợt 2",
                List.of(new ContractAppendixDocument.Change("Số lượng ly giấy 12oz (Điều 1)", "20.000 cái",
                                "25.000 cái"),
                        new ContractAppendixDocument.Change("Thời gian giao hàng đợt 2 (Điều 4)",
                                "Từ 01/12/2026 đến 03/12/2026", "Từ 08/12/2026 đến 10/12/2026"),
                        new ContractAppendixDocument.Change("Tổng giá trị Hợp đồng (Điều 1)", "48.600.000 đồng",
                                "58.320.000 đồng")),
                goods ? List.of(new ContractAppendixDocument.GoodsLine("LY12-MLC-01", "Ly giấy 12oz in logo Mộc Lam",
                                "Cái", 25_000, new BigDecimal("1800"), new BigDecimal("45000000"),
                                new BigDecimal("8")),
                        new ContractAppendixDocument.GoodsLine("LY16-MLC-02",
                                "Ly giấy 16oz hai lớp in logo Mộc Lam", "Cái", 4_000, new BigDecimal("2250"),
                                new BigDecimal("9000000"), new BigDecimal("8"))) : List.of(),
                goods ? new SalesContracts.Totals(new BigDecimal("54000000"), new BigDecimal("4320000"),
                        new BigDecimal("58320000"), "VND") : null, draft);
    }

    /**
     * Delivered: 25,000 + 4,000 cups delivered, 1,000 returned and refunded; kept 24,000 x 1,800 + 4,000 x 2,250 =
     * 52,200,000 before VAT, 56,376,000 with 8%; a plate fee of 1,080,000 with VAT besides; paid 17,496,000 +
     * 41,904,000 = 59,400,000, paid back 1,944,000: 56,376,000 + 1,080,000 - 59,400,000 + 1,944,000 = 0.
     * Not delivered: the plate fee was made and is kept; the deposit paid, the rest of it paid back.
     */
    private static ContractLiquidationDocument.Facts liquidationFacts(boolean delivered) {
        List<ContractLiquidationDocument.GoodsLine> goods = List.of(
                new ContractLiquidationDocument.GoodsLine("LY12-MLC-01", "Ly giấy 12oz in logo Mộc Lam", "Cái",
                        25_000, delivered ? 25_000 : 0, delivered ? 1_000 : 0, new BigDecimal("1800")),
                new ContractLiquidationDocument.GoodsLine("LY16-MLC-02", "Ly giấy 16oz hai lớp in logo Mộc Lam",
                        "Cái", 4_000, delivered ? 4_000 : 0, 0, new BigDecimal("2250")));
        ContractLiquidationDocument.Payment deposit = new ContractLiquidationDocument.Payment(
                LocalDate.of(2026, 11, 10), "FT26314000123", new BigDecimal("17496000"));
        return new ContractLiquidationDocument.Facts("TL-" + CONTRACT, CONTRACT, LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 12, 28), SalesDocumentSamples.SELLER, SalesDocumentSamples.BUYER,
                delivered ? null : "lô hàng giao không thành công và Bên B không còn nhu cầu nhận hàng", goods,
                new BigDecimal("58320000"), delivered ? new BigDecimal("56376000") : BigDecimal.ZERO,
                new BigDecimal("1080000"), List.of("Phí khuôn, bản in"),
                delivered ? List.of(deposit, new ContractLiquidationDocument.Payment(LocalDate.of(2026, 12, 12),
                        "FT26346000456", new BigDecimal("41904000"))) : List.of(deposit),
                delivered ? new BigDecimal("1944000") : new BigDecimal("16416000"),
                delivered ? List.of("số 00000042 (12/12/2026)", "số 00000057 (20/12/2026, điều chỉnh giảm)")
                        : List.of());
    }

    @Test
    @DisplayName("an appendix reads as one: its number and the contract it belongs to, the bases, both parties, each "
            + "change beside what the contract said, the goods and value as amended, the closing clause, both titles")
    void appendixInFull() throws IOException {
        byte[] pdf = DocumentSamples.keep("contract-appendix-sample", appendix.render(appendixFacts(true, false)));

        assertThat(DocumentSamples.within(pdf, 30, 15)).as("inside the margins of Nghị định 30").isTrue();
        String text = DocumentSamples.text(pdf);
        assertThat(text).containsSubsequence("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", "PHỤ LỤC HỢP ĐỒNG SỐ 01",
                "Số: PL01-" + CONTRACT, "(Kèm theo Hợp đồng mua bán hàng hóa số " + CONTRACT + " ngày 09/11/2026)",
                "Căn cứ Bộ luật Dân sự số 91/2015/QH13",
                "Căn cứ Hợp đồng mua bán hàng hóa số " + CONTRACT + " ký ngày 09/11/2026 giữa hai Bên;",
                "Hôm nay, ngày 23 tháng 11 năm 2026, tại Thành phố Hồ Chí Minh, chúng tôi gồm:",
                "BÊN BÁN (BÊN A): CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT", "BÊN MUA (BÊN B): CÔNG TY CỔ PHẦN CÀ PHÊ",
                "ĐIỀU 1. NỘI DUNG SỬA ĐỔI, BỔ SUNG", "1.1.", "Lý do sửa đổi, bổ sung:", "1.2.",
                "Theo Hợp đồng", "Sửa đổi, bổ sung thành", "20.000 cái", "25.000 cái",
                "ĐIỀU 2. HÀNG HÓA VÀ GIÁ TRỊ HỢP ĐỒNG SAU ĐIỀU CHỈNH", "Ly giấy 12oz in logo Mộc Lam", "25.000",
                "45.000.000", "54.000.000", "4.320.000", "58.320.000",
                "Năm mươi tám triệu ba trăm hai mươi nghìn đồng",
                "ĐIỀU 3. ĐIỀU KHOẢN CHUNG", "3.1.", "là một phần không thể tách rời của Hợp đồng số " + CONTRACT,
                "3.4.", "ĐẠI DIỆN BÊN A", "(Ký, ghi rõ họ tên, đóng dấu)", "Trần Quốc An", "ĐẠI DIỆN BÊN B",
                "Lê Thị Mộc Lam");
        assertThat(text).as("each signatory's title heads their block").contains("GIÁM ĐỐC", "TỔNG GIÁM ĐỐC")
                .doesNotContain("?");
        assertThat(SalesDocumentSamples.watermarkedPages(pdf)).isZero();
    }

    @Test
    @DisplayName("an appendix that moves neither goods nor money has no goods clause and leaves no gap in the "
            + "numbers; a copy for review is marked a draft on every page")
    void appendixWithoutGoods() throws IOException {
        byte[] pdf = DocumentSamples.keep("contract-appendix-draft", appendix.render(appendixFacts(false, true)));

        assertThat(DocumentSamples.text(pdf)).contains("ĐIỀU 1. NỘI DUNG SỬA ĐỔI, BỔ SUNG",
                "ĐIỀU 2. ĐIỀU KHOẢN CHUNG").doesNotContain("ĐIỀU 3", "HÀNG HÓA VÀ GIÁ TRỊ");
        assertThat(SalesDocumentSamples.watermarkedPages(pdf)).isEqualTo(DocumentSamples.pages(pdf));
    }

    @Test
    @DisplayName("owner 2026-11-23, demo run 24/11 D-6: a liquidation keeps the contract's value as signed and prints "
            + "exactly five rows - the value performed (the goods kept and the fees the customer keeps paying, a note "
            + "naming the fees), what was paid as one sum, what was paid back and what is left, 0 - then that the "
            + "parties owe each other nothing more")
    void liquidationInFull() throws IOException {
        byte[] pdf = DocumentSamples.keep("contract-liquidation-sample", liquidation.render(liquidationFacts(true)));

        assertThat(DocumentSamples.within(pdf, 30, 15)).as("inside the margins of Nghị định 30").isTrue();
        String text = DocumentSamples.text(pdf);
        assertThat(text).containsSubsequence("BIÊN BẢN THANH LÝ HỢP ĐỒNG MUA BÁN HÀNG HÓA", "Số: TL-" + CONTRACT,
                "Căn cứ Bộ luật Dân sự số 91/2015/QH13", "Căn cứ Luật Thương mại số 36/2005/QH11",
                "Căn cứ Hợp đồng mua bán hàng hóa số " + CONTRACT + " ký ngày 09/11/2026 giữa hai Bên;",
                "Hôm nay, ngày 28 tháng 12 năm 2026",
                "ĐIỀU 1. THỎA THUẬN CHẤM DỨT HỢP ĐỒNG", "đã được thực hiện xong",
                "ĐIỀU 2. NGHĨA VỤ ĐÃ HOÀN THÀNH", "SL theo hợp đồng", "SL thực giao", "SL trả lại", "SL quyết toán",
                "Ly giấy 12oz in logo Mộc Lam", "25.000", "25.000", "1.000", "24.000", "1.800", "43.200.000",
                "Giá trị Hợp đồng", "58.320.000",
                "Giá trị thực hiện", "Gồm: hàng hóa đã giao thành công 56.376.000; phí khuôn, bản in 1.080.000",
                "57.456.000",
                "Đã thanh toán", "59.400.000", "Đã hoàn trả", "1.944.000", "Còn lại", "0",
                "Hai Bên xác nhận đã hoàn thành nghĩa vụ thanh toán theo Hợp đồng và không còn nghĩa vụ nào khác "
                        + "đối với nhau.",
                "số 00000042 (12/12/2026)",
                "ĐIỀU 3. NGHĨA VỤ CÒN LẠI CỦA CÁC BÊN", "Hai Bên không còn khoản phải thanh toán nào theo Hợp đồng.",
                "ĐIỀU 4. ĐIỀU KHOẢN CHUNG", "Ngoài các nghĩa vụ nêu tại Điều 3",
                "ĐẠI DIỆN BÊN A", "ĐẠI DIỆN BÊN B");
        assertThat(text).contains("GIÁM ĐỐC", "TỔNG GIÁM ĐỐC").doesNotContain("?", "còn phải");
        assertThat(text).as("no sixth row for the fees, no row per payment")
                .doesNotContain("Phí và khoản khác", "Trong đó", "FT26314000123", "17.496.000");
    }

    /**
     * Demo run 24/11 (D-6, D-7), the figures of WF5-A: 80,000 cups at 1,200 delivered over two batches, 2,000 returned
     * (TH-1); 4,000,000 of plate and shipping fees; 8% VAT; 108,000,000 paid in three transfers, 2,592,000 paid back.
     * Kept goods 78,000 x 1,200 x 1.08 = 101,088,000; with the fees 105,408,000 = 108,000,000 - 2,592,000.
     */
    @Test
    @DisplayName("demo run 24/11, D-6 / D-7: WF5-A's liquidation - the fees inside the value performed with a note "
            + "naming them, no row of their own, no row per transfer; the goods table's header on its first row's page")
    void liquidationOfTheDemoOrder() throws IOException {
        List<ContractLiquidationDocument.Payment> payments = List.of(
                new ContractLiquidationDocument.Payment(LocalDate.of(2026, 11, 9), "FT26313000001",
                        new BigDecimal("32400000")),
                new ContractLiquidationDocument.Payment(LocalDate.of(2026, 11, 24), "FT26328000002",
                        new BigDecimal("40000000")),
                new ContractLiquidationDocument.Payment(LocalDate.of(2026, 11, 24), "FT26328000003",
                        new BigDecimal("35600000")));
        ContractLiquidationDocument.Facts facts = new ContractLiquidationDocument.Facts(
                "TL-HD-20261109-950001-1", "HD-20261109-950001-1", LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 11, 24), SalesDocumentSamples.SELLER, SalesDocumentSamples.BUYER, null,
                List.of(new ContractLiquidationDocument.GoodsLine("LY12-MOC-01", "Ly giấy 2 lớp in logo Mộc - 12 oz",
                        "Cái", 80_000, 80_000, 2_000, new BigDecimal("1200"))),
                new BigDecimal("108000000"), new BigDecimal("101088000"), new BigDecimal("4320000"),
                List.of("Phí khuôn, bản in", "Phí vận chuyển"), payments, new BigDecimal("2592000"),
                List.of("số 358928 (24/11/2026)", "số 358929 (24/11/2026)",
                        "số 358930 (24/11/2026, điều chỉnh giảm)"));
        byte[] pdf = DocumentSamples.keep("contract-liquidation-wf5a", liquidation.render(facts));

        String text = DocumentSamples.text(pdf);
        assertThat(text).containsSubsequence("Giá trị Hợp đồng", "108.000.000", "Giá trị thực hiện",
                "Gồm: hàng hóa đã giao thành công 101.088.000; phí khuôn, bản in và phí vận chuyển 4.320.000",
                "105.408.000", "Đã thanh toán", "108.000.000", "Đã hoàn trả", "2.592.000", "Còn lại", "0")
                .doesNotContain("Phí và khoản khác", "Trong đó", "FT26313000001");
        int header = -1;
        int firstRow = -1;
        for (int page = 1; page <= DocumentSamples.pages(pdf); page++) {
            String onPage = DocumentSamples.text(pdf, page);
            if (header < 0 && onPage.contains("SL quyết toán")) {
                header = page;
            }
            if (firstRow < 0 && onPage.contains("Ly giấy 2 lớp in logo Mộc")) {
                firstRow = page;
            }
        }
        assertThat(firstRow).as("the goods table's first row on its header's page").isEqualTo(header).isPositive();
    }

    @Test
    @DisplayName("a contract that ends with nothing delivered is liquidated too (Q-6): the reason, no goods table, a "
            + "value performed of 0, the plate fee kept, the deposit paid and the rest of it paid back, 0 left")
    void liquidationWithNothingDelivered() throws IOException {
        byte[] pdf = DocumentSamples.keep("contract-liquidation-undelivered",
                liquidation.render(liquidationFacts(false)));

        String text = DocumentSamples.text(pdf);
        assertThat(text).contains("Do lô hàng giao không thành công và Bên B không còn nhu cầu nhận hàng, các Bên",
                "Bên A chưa giao hàng hóa nào cho Bên B theo Hợp đồng.")
                .containsSubsequence("Giá trị Hợp đồng", "58.320.000", "Giá trị thực hiện",
                        "Gồm: hàng hóa đã giao thành công 0; phí khuôn, bản in 1.080.000", "1.080.000", "Đã thanh toán",
                        "17.496.000", "Đã hoàn trả", "16.416.000", "Còn lại", "0",
                        "không còn nghĩa vụ nào khác đối với nhau")
                .doesNotContain("SL thực giao", "bảo hành", "còn phải");
    }

    @Test
    @DisplayName("a liquidation whose rows leave anything to pay or pay back is never drawn: it would put a false "
            + "\"no other obligations\" up for signature")
    void anUnsettledLiquidationIsNotDrawn() {
        ContractLiquidationDocument.Facts settled = liquidationFacts(true);
        ContractLiquidationDocument.Facts owing = new ContractLiquidationDocument.Facts(settled.number(),
                settled.contractNumber(), settled.contractDate(), settled.dated(), settled.seller(), settled.buyer(),
                settled.reason(), settled.goods(), settled.contractValue(), settled.performedValue(),
                settled.otherCharges(), settled.charges(), settled.payments(), BigDecimal.ZERO, settled.invoices());
        assertThat(owing.remaining()).as("the refund still owed to the buyer").isEqualByComparingTo("-1944000");
        assertThatThrownBy(() -> liquidation.render(owing)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not settled");
    }
}
