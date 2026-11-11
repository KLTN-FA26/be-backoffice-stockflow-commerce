package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.label.LabelFonts;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderFee;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4B-4: the contract drawn as a PDF - every word on the page, however long the parties' legal names are; Vietnamese as
 * written.
 */
class SalesContractDocumentTest {

    /** A real legal name: long, in capitals, Vietnamese - far wider than its letters suggest. */
    private static final String BUYER = "CÔNG TY TRÁCH NHIỆM HỮU HẠN THƯƠNG MẠI DỊCH VỤ SẢN XUẤT BAO BÌ GIẤY VÀ NHỰA "
            + "MIỀN NAM VIỆT NAM";

    private static SalesContractDocument.Facts facts() {
        OrderLine cups = Order.line(UUID.randomUUID(), new Sku("LY12-ACME-01"), 1000, Money.vnd(2_000), null)
                .withCartons(UUID.randomUUID(), 20, 50).withTax(BigDecimal.TEN);
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 1), UUID.randomUUID(), UUID.randomUUID(),
                List.of(cups), Instant.now());
        order.attachFees(List.of(OrderFee.of(UUID.randomUUID(), FeeKind.PLATE_FEE, new Sku("LY12-ACME-01"),
                Money.vnd(500_000), BigDecimal.TEN)));
        SalesContracts.Party seller = new SalesContracts.Party("DEMO STOCKFLOW CO LTD", "0101", "1 Demo Street",
                "Demo Director", "Director");
        SalesContracts.Party buyer = new SalesContracts.Party(BUYER, "0312345678", "Số 12 đường Nguyễn Huệ, phường Bến "
                + "Nghé, quận 1, thành phố Hồ Chí Minh, Việt Nam - tầng 15, toà nhà Bitexco Financial Tower",
                "Trần Văn Bình", "Tổng giám đốc");
        SalesContracts.ContractView view = new SalesContracts.ContractView(UUID.randomUUID(), "HD-20261109-000001-1",
                order.id().value(), order.orderNumber().value(), "DRAFT", seller, buyer, 2,
                new SalesContracts.Totals(new BigDecimal("2500000"), new BigDecimal("250000"),
                        new BigDecimal("2750000"), "VND"),
                List.of(new SalesContracts.BatchView(1, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3),
                        new BigDecimal("2200000"), List.of(new SalesContracts.BatchLineView(cups.id(), "LY12-ACME-01",
                        20, 1000)))),
                List.of(new SalesContracts.InstallmentView(1, "Đặt cọc khi ký hợp đồng", "ON_SIGNING",
                                new BigDecimal("30"), new BigDecimal("825000"), 3, null),
                        new SalesContracts.InstallmentView(2, "Thanh toán trước khi giao", "BEFORE_DELIVERY", null,
                                new BigDecimal("1925000"), null, 1)),
                null, null, null, null, null, null, null, null, null, null, 0, null);
        return new SalesContractDocument.Facts(view, order, LocalDate.of(2026, 11, 9));
    }

    @Test
    @DisplayName("every glyph is inside the page's margins, the legal names wrapped by their drawn width")
    void everyWordOnThePage() throws IOException {
        byte[] pdf = new SalesContractDocument(new LabelFonts()).render(facts());
        float[] rightmost = {0};
        StringBuilder words = new StringBuilder();
        try (var document = Loader.loadPDF(pdf)) {
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
            stripper.getText(document);
        }
        assertThat(rightmost[0]).as("inside the right margin").isLessThanOrEqualTo(PDRectangle.A4.getWidth() - 50 + 1);
        String text = words.toString().replaceAll("\\s+", " ");
        assertThat(text).contains("CÔNG TY TRÁCH NHIỆM HỮU HẠN", "MIỀN NAM VIỆT NAM", "Trần Văn Bình",
                "HD-20261109-000001-1", "Phí khuôn / bản in (LY12-ACME-01)", "Đợt 2 - Thanh toán trước khi giao",
                "(đợt giao 1)");
    }
}
