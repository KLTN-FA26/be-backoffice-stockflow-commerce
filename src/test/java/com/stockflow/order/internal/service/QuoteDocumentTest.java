package com.stockflow.order.internal.service;

import com.stockflow.common.config.SellerProperties;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.label.LabelFonts;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.internal.domain.QuoteStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
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
 * The quote drawn as a PDF (kltn-docs 14 §4.1, BR-08, BR-09): every word on the page however long the customer's legal
 * name, the offer's figures or the accepted ones, the total in words, and a draft that says it is one.
 */
class QuoteDocumentTest {

    /** A real legal name: long, in capitals, Vietnamese - far wider than its letters suggest. */
    private static final String BUYER = "CÔNG TY TRÁCH NHIỆM HỮU HẠN THƯƠNG MẠI DỊCH VỤ SẢN XUẤT BAO BÌ GIẤY VÀ NHỰA "
            + "MIỀN NAM VIỆT NAM";

    private static final SalesContracts.Party SELLER = new SalesContracts.Party("DEMO STOCKFLOW CO LTD", "0101",
            "1 Demo Street", "Demo Director", "Director");

    private final QuoteDocument document = new QuoteDocument(new LabelFonts());

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
        Instant sent = Instant.parse("2026-11-09T03:00:00Z");
        return new Quotes.QuoteView(UUID.randomUUID(), "QT-20261109-000007", UUID.randomUUID(), status, 2,
                Instant.parse("2026-11-23T16:59:00Z"), "Thanh toán theo hợp đồng.\nGiao tại kho của bên mua.",
                LocalDate.of(2026, 12, 15), false, lines, List.of(plateFee()), true, proposed, accepted,
                proposed.total(), accepted == null ? null : accepted.total(), status == QuoteStatus.DRAFT ? null : sent,
                UUID.randomUUID(), accepted == null ? null : Instant.parse("2026-11-10T02:30:00Z"), null, null, null,
                null, null, List.of(), sent, "sales", 3);
    }

    private static Quotes.Totals offered() {
        return new Quotes.Totals(new BigDecimal("2000000"), new BigDecimal("500000"), new BigDecimal("250000"),
                new BigDecimal("2750000"));
    }

    private static QuoteDocument.Facts facts(Quotes.QuoteView quote) {
        SalesContracts.Party buyer = new SalesContracts.Party(BUYER, "0312345678", "Số 12 đường Nguyễn Huệ, phường "
                + "Bến Nghé, quận 1, thành phố Hồ Chí Minh, Việt Nam - tầng 15, toà nhà Bitexco Financial Tower",
                "Trần Văn Bình", "Tổng giám đốc");
        return new QuoteDocument.Facts(quote, SELLER, buyer, LocalDate.of(2026, 11, 9));
    }

    @Test
    @DisplayName("a sent quote: number, parties, the line in cartons with its tiers, fee, totals, words - all inside "
            + "the margins")
    void everyWordOnThePage() throws IOException {
        byte[] pdf = document.render(facts(quote(QuoteStatus.SENT, List.of(cartonLine(null)), offered(), null)));
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
        assertThat(text).contains("BÁO GIÁ", "QT-20261109-000007 (bản sửa 2)", "Ngày lập: 09/11/2026",
                "Hiệu lực đến: 23:59 23/11/2026", "CÔNG TY TRÁCH NHIỆM HỮU HẠN", "MIỀN NAM VIỆT NAM", "Trần Văn Bình",
                "DEMO STOCKFLOW CO LTD",
                "LY12-ACME-01: 20 thùng x 50 cái = 1000 cái; đơn giá 2.000 VND/cái; thành tiền 2.000.000 VND; "
                        + "thuế GTGT 10% = 200.000 VND",
                "Bậc giá: từ 10 thùng: 2.000 VND/cái; từ 30 thùng: 1.900 VND/cái",
                "Phí khuôn / bản in (LY12-ACME-01): 500.000 VND; thuế GTGT 10% = 50.000 VND",
                "Tổng giá trị có thuế: 2.750.000 VND", "Bằng chữ: Hai triệu bảy trăm năm mươi nghìn đồng",
                "Ngày giao khách hàng yêu cầu: 15/12/2026", "Giao tại kho của bên mua.")
                .doesNotContain("BẢN NHÁP");
    }

    @Test
    @DisplayName("an accepted quote prints what was accepted: the cartons and price that become the order")
    void acceptedFigures() throws IOException {
        Quotes.Totals accepted = new Quotes.Totals(new BigDecimal("2850000"), new BigDecimal("500000"),
                new BigDecimal("335000"), new BigDecimal("3685000"));
        String text = text(document.render(facts(quote(QuoteStatus.ACCEPTED, List.of(cartonLine(30)), offered(),
                accepted))));
        assertThat(text).contains("LY12-ACME-01: 30 thùng x 50 cái = 1500 cái; đơn giá 1.900 VND/cái; thành tiền "
                        + "2.850.000 VND; thuế GTGT 10% = 285.000 VND",
                "Khách hàng chấp nhận lúc: 09:30 10/11/2026", "Tổng giá trị có thuế: 3.685.000 VND",
                "Bằng chữ: Ba triệu sáu trăm tám mươi lăm nghìn đồng")
                .doesNotContain("20 thùng x 50 cái", "2.750.000 VND");
    }

    @Test
    @DisplayName("a draft says it is one; what is not priced yet is printed as such, and its total has no words")
    void draftIsMarked() throws IOException {
        Quotes.LineView unpriced = new Quotes.LineView(UUID.randomUUID(), 1, "CARTON", "LY12-ACME-01", 20, 50, 1000,
                null, null, false, null, List.of(), null, null, null, null, null, null, null, null, null, null, null);
        Quotes.Totals unknown = new Quotes.Totals(null, new BigDecimal("500000"), null, null);
        String text = text(document.render(facts(quote(QuoteStatus.DRAFT, List.of(unpriced), unknown, null))));
        assertThat(text).contains("BẢN NHÁP - chưa gửi khách hàng",
                "LY12-ACME-01: 20 thùng x 50 cái = 1000 cái; đơn giá chưa xác định/cái; thành tiền chưa xác định; "
                        + "thuế GTGT chưa xác định",
                "Tổng giá trị có thuế: chưa xác định")
                .doesNotContain("Bằng chữ", "Bậc giá");
    }

    @Test
    @DisplayName("a quote drafted before cartons (QD-16) counts pieces; a cancelled one says it no longer stands")
    void legacyAndCancelled() throws IOException {
        Quotes.LineView legacy = new Quotes.LineView(UUID.randomUUID(), 1, "UNIT", "LY-GIAY-DEMO-12OZ-XANH", null, null,
                100, UUID.randomUUID(), null, true, 1, List.of(new Quotes.Tier(1, new BigDecimal("1000"))),
                new BigDecimal("1000"), new BigDecimal("100000"), null, null, null, null, null, null,
                BigDecimal.ZERO, null, null);
        Quotes.Totals totals = new Quotes.Totals(new BigDecimal("100000"), new BigDecimal("500000"),
                new BigDecimal("50000"), new BigDecimal("650000"));
        String text = text(document.render(facts(quote(QuoteStatus.CANCELLED, List.of(legacy), totals, null))));
        assertThat(text).contains("ĐÃ HUỶ", "LY-GIAY-DEMO-12OZ-XANH: 100 cái; đơn giá 1.000 VND/cái; "
                + "thành tiền 100.000 VND; thuế GTGT 0 VND", "Bậc giá: từ 1 cái: 1.000 VND/cái");
    }

    private static String text(byte[] pdf) throws IOException {
        try (var doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
        }
    }

    @Test
    @DisplayName("no seller configured: the quote's PDF is refused with SELLER_PROFILE_UNAVAILABLE, whose message names "
            + "quotes beside contracts and invoices, in both languages")
    void noSellerNoQuoteDocument() {
        Quotes quotes = mock(Quotes.class);
        Quotes.QuoteView sent = quote(QuoteStatus.SENT, List.of(cartonLine(null)), offered(), null);
        when(quotes.get(any(), any())).thenReturn(sent);
        QuoteDocumentService service = new QuoteDocumentService(quotes, document,
                new SellerProperties("", "", "", "", ""), null, null, Clock.systemUTC());

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
