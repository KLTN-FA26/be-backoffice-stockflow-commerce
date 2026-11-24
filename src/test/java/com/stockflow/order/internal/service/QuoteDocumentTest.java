package com.stockflow.order.internal.service;

import com.stockflow.common.config.SellerProperties;
import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.internal.domain.QuoteStatus;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The quotation drawn as a PDF (kltn-docs 14 §4.1, BR-08, BR-09): the paper as a company sends it - letterhead, goods
 * table, totals, the total in words, terms, signature blocks - with every word inside the margins however long the
 * customer's legal name, the offer's figures or the accepted ones, and a quote that does not stand marked as such.
 */
class QuoteDocumentTest {

    private final QuoteDocument document = new QuoteDocument(new DocumentFonts(), new ContractProperties(2, 5, 6, 8));

    /** 20 cartons of 50 at 2,000 a piece with 10% VAT, a plate fee of 500,000 with 10%: 2,750,000 with tax. */
    private static Quotes.LineView cartonLine(Integer acceptedCartons) {
        Integer acceptedUnits = acceptedCartons == null ? null : acceptedCartons * 50;
        BigDecimal acceptedNet = acceptedCartons == null ? null : new BigDecimal(acceptedUnits * 1_900);
        return new Quotes.LineView(UUID.randomUUID(), 1, "CARTON", "LY12-ACME-01", 20, 50, 1000, null, null, true, 10,
                List.of(new Quotes.Tier(10, new BigDecimal("2000")), new Quotes.Tier(30, new BigDecimal("1900"))),
                new BigDecimal("2000"), new BigDecimal("2000000"), acceptedCartons, acceptedUnits,
                acceptedCartons == null ? null : new BigDecimal("1900"), acceptedNet, "STANDARD", BigDecimal.TEN,
                new BigDecimal("200000"), acceptedNet == null ? null : acceptedNet.divide(BigDecimal.TEN), null);
    }

    private static Quotes.FeeView plateFee() {
        return new Quotes.FeeView(UUID.randomUUID(), FeeKind.PLATE_FEE, "LY12-ACME-01", new BigDecimal("500000"),
                "STANDARD", BigDecimal.TEN, new BigDecimal("50000"));
    }

    private static Quotes.QuoteView quote(QuoteStatus status, List<Quotes.LineView> lines, Quotes.Totals proposed,
                                          Quotes.Totals accepted) {
        return quote(status, lines, List.of(plateFee()), proposed, accepted);
    }

    private static Quotes.QuoteView quote(QuoteStatus status, List<Quotes.LineView> lines, List<Quotes.FeeView> fees,
                                          Quotes.Totals proposed, Quotes.Totals accepted) {
        Instant sent = Instant.parse("2026-11-09T03:00:00Z");
        return new Quotes.QuoteView(UUID.randomUUID(), "QT-20261109-000007", UUID.randomUUID(), status, 2,
                Instant.parse("2026-11-23T16:59:00Z"), "Thanh toán theo hợp đồng.\nGiao tại kho của bên mua.",
                LocalDate.of(2026, 12, 15), false, lines, fees, true, proposed, accepted,
                proposed.total(), accepted == null ? null : accepted.total(), status == QuoteStatus.DRAFT ? null : sent,
                UUID.randomUUID(), accepted == null ? null : Instant.parse("2026-11-10T02:30:00Z"), null, null, null,
                null, null, List.of(), sent, "sales", 3);
    }

    private static Quotes.Totals offered() {
        return new Quotes.Totals(new BigDecimal("2000000"), new BigDecimal("500000"), new BigDecimal("250000"),
                new BigDecimal("2750000"));
    }

    private static QuoteDocument.Facts facts(Quotes.QuoteView quote) {
        return new QuoteDocument.Facts(quote, SalesDocumentSamples.SELLER, SalesDocumentSamples.LONG_BUYER,
                LocalDate.of(2026, 11, 9), SalesDocumentSamples.letterhead(), SalesDocumentSamples.NAMES);
    }

    @Test
    @DisplayName("a sent quote reads as a quotation: letterhead, number and dated line, addressee, the goods in a "
            + "table with its totals and the total in words, the terms, both signature blocks - inside the margins")
    void everyWordOnThePage() throws IOException {
        byte[] pdf = SalesDocumentSamples.keep("quote-long-buyer-name",
                document.render(facts(quote(QuoteStatus.SENT, List.of(cartonLine(null)), offered(), null))));

        float[] extent = SalesDocumentSamples.extent(pdf);
        assertThat(extent[0]).as("inside the left margin").isGreaterThanOrEqualTo(BusinessDocument.mm(20) - 0.5f);
        assertThat(extent[1]).as("inside the right margin")
                .isLessThanOrEqualTo(PDRectangle.A4.getWidth() - BusinessDocument.mm(15) + 0.5f);
        String text = SalesDocumentSamples.text(pdf);
        assertThat(text).contains("CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT", "MST: 0312345678", "ĐT: (028) 3765 0000",
                "Website: www.baobianphat.example", "BẢNG BÁO GIÁ", "Số: QT-20261109-000007 (lần sửa 2)",
                "Thành phố Hồ Chí Minh, ngày 09 tháng 11 năm 2026", "Kính gửi: CÔNG TY TRÁCH NHIỆM HỮU HẠN",
                "MIỀN NAM VIỆT NAM", "Ông/Bà Trần Văn Bình - Chức vụ: Tổng giám đốc",
                // The table's header, then the line: name, packing, tiers, and its figures in their own columns.
                "Tên hàng, quy cách", "Đơn giá (VNĐ)", "Thành tiền (VNĐ)", "Ly giấy 12oz in theo thiết kế",
                "Đóng gói: 20 thùng x 50 cái/thùng", "từ 10 thùng: 2.000 đ/cái; từ 30 thùng: 1.900 đ/cái",
                "Phí khuôn, bản in", "Cộng tiền hàng 2.000.000", "Các khoản phí 500.000", "Tiền thuế GTGT 250.000",
                "Tổng cộng thanh toán 2.750.000", "Số tiền bằng chữ: Hai triệu bảy trăm năm mươi nghìn đồng.",
                "ĐIỀU KHOẢN THƯƠNG MẠI", "Hiệu lực báo giá: đến 23:59 ngày 23/11/2026.", "dự kiến ngày 15/12/2026",
                "tài khoản số 0071001234567 tại Ngân hàng TMCP Ngoại thương Việt Nam (Vietcombank) - Chi nhánh Bình "
                        + "Tây", "Dung sai số lượng: ±5%", "Bảo hành: 6 tháng", "Giao tại kho của bên mua.",
                "XÁC NHẬN CỦA KHÁCH HÀNG", "ĐẠI DIỆN BÊN BÁN", "GIÁM ĐỐC", "(Ký, ghi rõ họ tên, đóng dấu)",
                "Trần Quốc An",
                "Trang 1/")
                .doesNotContain("chưa xác định", "VND");
        assertThat(SalesDocumentSamples.watermarkedPages(pdf)).as("a quote that stands has no watermark").isZero();
        // One goods row: quantity, unit price, amount and rate side by side, in that order.
        assertThat(text).containsSubsequence("Cái", "1.000", "2.000", "2.000.000", "10%");
    }

    @Test
    @DisplayName("an accepted quote prints what was accepted: the cartons and price that become the order")
    void acceptedFigures() throws IOException {
        Quotes.Totals accepted = new Quotes.Totals(new BigDecimal("2850000"), new BigDecimal("500000"),
                new BigDecimal("335000"), new BigDecimal("3685000"));
        String text = SalesDocumentSamples.text(document.render(facts(quote(QuoteStatus.ACCEPTED,
                List.of(cartonLine(30)), offered(), accepted))));
        assertThat(text).contains("Đóng gói: 30 thùng x 50 cái/thùng", "Cộng tiền hàng 2.850.000",
                "Khách hàng đã chấp nhận báo giá lúc 09:30 ngày 10/11/2026.", "Tổng cộng thanh toán 3.685.000",
                "Số tiền bằng chữ: Ba triệu sáu trăm tám mươi lăm nghìn đồng.")
                .containsSubsequence("1.500", "1.900", "2.850.000")
                .doesNotContain("20 thùng x 50", "2.750.000");
    }

    @Test
    @DisplayName("a draft carries its watermark on every page; a line not priced yet shows a dash, and no total - in "
            + "figures or in words - is printed over it")
    void draftIsMarked() throws IOException {
        Quotes.LineView unpriced = new Quotes.LineView(UUID.randomUUID(), 1, "CARTON", "LY12-ACME-01", 20, 50, 1000,
                null, null, false, null, List.of(), null, null, null, null, null, null, null, null, null, null, null);
        Quotes.Totals unknown = new Quotes.Totals(null, new BigDecimal("500000"), null, null);
        byte[] pdf = SalesDocumentSamples.keep("quote-draft-unpriced",
                document.render(facts(quote(QuoteStatus.DRAFT, List.of(unpriced), unknown, null))));
        String text = SalesDocumentSamples.text(pdf);
        assertThat(text).contains("Ly giấy 12oz in theo thiết kế", "Đóng gói: 20 thùng x 50 cái/thùng",
                "Bản nháp: các dòng chưa có đơn giá sẽ được bổ sung trước khi phát hành.")
                .doesNotContain("bằng chữ", "Bậc giá", "Tổng cộng thanh toán", "Cộng tiền hàng", "chưa xác định",
                        "null");
        // The watermark is on each page, not only the first.
        assertThat(SalesDocumentSamples.compact(pdf)).contains("BẢNNHÁP");
        assertThat(SalesDocumentSamples.watermarkedPages(pdf)).isEqualTo(SalesDocumentSamples.pages(pdf));
    }

    @Test
    @DisplayName("a quote drafted before cartons (QD-16) counts pieces; a cancelled one says so across the page")
    void legacyAndCancelled() throws IOException {
        Quotes.LineView legacy = new Quotes.LineView(UUID.randomUUID(), 1, "UNIT", "LY-GIAY-DEMO-12OZ-XANH", null, null,
                100, UUID.randomUUID(), null, true, 1, List.of(new Quotes.Tier(1, new BigDecimal("1000"))),
                new BigDecimal("1000"), new BigDecimal("100000"), null, null, null, null, null, null,
                BigDecimal.ZERO, null, null);
        Quotes.Totals totals = new Quotes.Totals(new BigDecimal("100000"), new BigDecimal("500000"),
                new BigDecimal("50000"), new BigDecimal("650000"));
        byte[] cancelled = document.render(facts(quote(QuoteStatus.CANCELLED, List.of(legacy), totals, null)));
        assertThat(SalesDocumentSamples.text(cancelled)).contains("Bậc giá: từ 1 cái: 1.000 đ/cái",
                "Tổng cộng thanh toán 650.000").doesNotContain("Đóng gói", "thùng x");
        // No product name is known for a legacy SKU: its code stands where the name would be, wrapped in its column.
        assertThat(SalesDocumentSamples.compact(cancelled)).contains("ĐÃHỦY", "LY-GIAY-DEMO-12OZ-XANH");
        assertThat(SalesDocumentSamples.compact(document.render(facts(quote(QuoteStatus.EXPIRED, List.of(legacy),
                totals, null))))).contains("HẾTHIỆULỰC");
    }

    @Test
    @DisplayName("a deployment with the legal identity alone still sends a quotation: no logo, no contacts, no account")
    void bareLetterhead() throws IOException {
        QuoteDocument.Facts bare = new QuoteDocument.Facts(quote(QuoteStatus.SENT, List.of(cartonLine(null)),
                offered(), null), SalesDocumentSamples.SELLER, new SalesContracts.Party("Cà phê Mộc Lam", null, null,
                null, null), LocalDate.of(2026, 11, 9), SalesDocumentSamples.bareLetterhead(),
                SalesDocumentSamples.NAMES);
        String text = SalesDocumentSamples.text(document.render(bare));
        assertThat(text).contains("CÔNG TY TNHH SẢN XUẤT BAO BÌ AN PHÁT", "MST: 0312345678",
                        "Kính gửi: Cà phê Mộc Lam", "Ngày 09 tháng 11 năm 2026",
                        "Thanh toán: chuyển khoản theo tiến độ ghi trong hợp đồng mua bán.")
                .doesNotContain("ĐT:", "Email:", "tài khoản số", "null", "Mã số thuế :");
    }

    @Test
    @DisplayName("the standing terms are the configured ones: no tolerance and no warranty configured, none printed")
    void termsFollowConfiguration() throws IOException {
        QuoteDocument plain = new QuoteDocument(new DocumentFonts(), new ContractProperties(2, 0, 0, 8));
        assertThat(SalesDocumentSamples.text(plain.render(facts(quote(QuoteStatus.SENT, List.of(cartonLine(null)),
                offered(), null))))).doesNotContain("Dung sai", "Bảo hành").contains("Màu sắc:");
    }

    @Test
    @DisplayName("a realistic quotation of three products, two fees and a long list of tiers, kept to be looked at")
    void realisticSample() throws IOException {
        List<Quotes.LineView> lines = List.of(
                line(1, "LY12-MLC-01", 100, 1000, "1250", "8", "In offset 4 màu, giấy 280gsm tráng PE một mặt"),
                line(2, "LY16-MLC-02", 60, 500, "2150", "8", "In flexo 2 màu, giấy 320gsm hai lớp"),
                line(3, "TUI-MLC-03", 40, 200, "4800", "10", null));
        List<Quotes.FeeView> fees = List.of(
                new Quotes.FeeView(UUID.randomUUID(), FeeKind.PLATE_FEE, "LY12-MLC-01", new BigDecimal("1500000"),
                        "STANDARD", new BigDecimal("8"), new BigDecimal("120000")),
                new Quotes.FeeView(UUID.randomUUID(), FeeKind.SHIPPING_FEE, null, new BigDecimal("800000"),
                        "STANDARD", new BigDecimal("8"), new BigDecimal("64000")));
        // 125,000,000 + 64,500,000 + 38,400,000 = 227,900,000; fees 2,300,000; VAT 10,000,000 + 5,160,000 +
        // 3,840,000 + 184,000 = 19,184,000.
        Quotes.Totals totals = new Quotes.Totals(new BigDecimal("227900000"), new BigDecimal("2300000"),
                new BigDecimal("19184000"), new BigDecimal("249384000"));
        Quotes.QuoteView quote = new Quotes.QuoteView(UUID.randomUUID(), "QT-20261123-0001", UUID.randomUUID(),
                QuoteStatus.SENT, 1, Instant.parse("2026-12-07T10:00:00Z"),
                "Giá áp dụng cho đơn hàng đầu tiên; các đơn lặp lại không tính phí khuôn, bản in.",
                LocalDate.of(2026, 12, 20), false, lines, fees, true, totals, null, totals.total(), null,
                Instant.parse("2026-11-23T03:00:00Z"), UUID.randomUUID(), null, null, null, null, null, null,
                List.of(), Instant.parse("2026-11-23T02:00:00Z"), "sales", 1);
        byte[] pdf = SalesDocumentSamples.keep("quote-sample", document.render(new QuoteDocument.Facts(quote,
                SalesDocumentSamples.SELLER, SalesDocumentSamples.BUYER, LocalDate.of(2026, 11, 23),
                SalesDocumentSamples.letterhead(), SalesDocumentSamples.NAMES)));

        String text = SalesDocumentSamples.text(pdf);
        assertThat(text).contains("Số: QT-20261123-0001", "Kính gửi: CÔNG TY CỔ PHẦN CÀ PHÊ MỘC LAM",
                        "Ly giấy 16oz hai lớp in logo Mộc Lam", "Quy cách in: In flexo 2 màu, giấy 320gsm hai lớp",
                        "Phí vận chuyển", "Tổng cộng thanh toán 249.384.000",
                        "Số tiền bằng chữ: Hai trăm bốn mươi chín triệu ba trăm tám mươi bốn nghìn đồng.")
                .doesNotContain("(lần sửa");
        assertThat(SalesDocumentSamples.pages(pdf)).as("a quotation of three lines fits two pages").isLessThanOrEqualTo(2);
    }

    private static Quotes.LineView line(int no, String sku, int cartons, int perCarton, String price, String rate,
                                        String printSpec) {
        int units = cartons * perCarton;
        BigDecimal unitPrice = new BigDecimal(price);
        BigDecimal net = unitPrice.multiply(BigDecimal.valueOf(units));
        BigDecimal tax = net.multiply(new BigDecimal(rate)).divide(BigDecimal.valueOf(100));
        return new Quotes.LineView(UUID.randomUUID(), no, "CARTON", sku, cartons, perCarton, units, null, printSpec,
                true, 10, List.of(new Quotes.Tier(10, unitPrice.add(BigDecimal.valueOf(100))),
                new Quotes.Tier(cartons, unitPrice)), unitPrice, net, null, null, null, null, "STANDARD",
                new BigDecimal(rate), tax, null, null);
    }

    @Test
    @DisplayName("no seller configured: the quote's PDF is refused with SELLER_PROFILE_UNAVAILABLE, whose message names "
            + "quotes beside contracts and invoices, in both languages")
    void noSellerNoQuoteDocument() {
        Quotes quotes = mock(Quotes.class);
        Quotes.QuoteView sent = quote(QuoteStatus.SENT, List.of(cartonLine(null)), offered(), null);
        when(quotes.get(any(), any())).thenReturn(sent);
        QuoteDocumentService service = new QuoteDocumentService(quotes, document,
                new SellerProperties("", "", "", "", ""), null, null, null, Clock.systemUTC());

        Throwable refused = catchThrowable(() -> service.document(null, sent.id()));

        assertThat(refused).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) refused).errorCode()).isEqualTo(ErrorCode.SELLER_PROFILE_UNAVAILABLE);
        assertThat(ErrorCode.SELLER_PROFILE_UNAVAILABLE.defaultMessage()).contains("quotes", "contracts", "invoices");
        ResourceBundle.Control exact = ResourceBundle.Control.getNoFallbackControl(
                ResourceBundle.Control.FORMAT_PROPERTIES);
        assertThat(ResourceBundle.getBundle("i18n.messages", Locale.ENGLISH, exact)
                .getString("error.SELLER_PROFILE_UNAVAILABLE")).contains("quotes", "contracts", "invoices");
        assertThat(ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"), exact)
                .getString("error.SELLER_PROFILE_UNAVAILABLE")).contains("báo giá", "hợp đồng", "hoá đơn");
    }
}
