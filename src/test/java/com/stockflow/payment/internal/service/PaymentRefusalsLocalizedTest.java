package com.stockflow.payment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.web.GlobalExceptionHandler;
import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.domain.OrderSettlement;
import com.stockflow.payment.internal.domain.ReceiptFilter;
import com.stockflow.payment.internal.domain.RefundRecord;
import com.stockflow.payment.internal.repository.InstallmentSearch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE BE-7: the payment refusals staff and customers read answer in their language over the real bundles - refunds, the
 * settlement's fee, the reversed receipt date range, the transfer details of a paid installment - instead of the
 * English text the exception was logged with; a status reads as its label, never the raw {@code PAID}.
 */
class PaymentRefusalsLocalizedTest {

    private static final Locale VIETNAMESE = Locale.of("vi");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private final ReloadableResourceBundleMessageSource source = bundles();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new Messages(source), source);

    private static ReloadableResourceBundleMessageSource bundles() {
        ReloadableResourceBundleMessageSource bundles = new ReloadableResourceBundleMessageSource();
        bundles.setBasenames("classpath:i18n/messages", "classpath:i18n/validation");
        bundles.setDefaultEncoding(StandardCharsets.UTF_8.name());
        bundles.setFallbackToSystemLocale(false);
        return bundles;
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private String vietnamese(Executable refused) {
        LocaleContextHolder.setLocale(VIETNAMESE);
        return handler.handleBusiness(refusal(refused)).getBody().message();
    }

    private static BusinessException refusal(Executable call) {
        try {
            call.execute();
        } catch (BusinessException refused) {
            assertThat(refused.messageKey()).as("a key people read, not the log's English").isNotNull();
            return refused;
        } catch (Throwable other) {
            throw new AssertionError("not a business refusal", other);
        }
        throw new AssertionError("not refused");
    }

    private static RefundRecord refund() {
        return RefundRecord.record(UUID.randomUUID(), UUID.randomUUID(), "DH-1", new BigDecimal("100000"), "VND", TODAY,
                "FT-REFUND-1", null, UUID.randomUUID(), NOW, TODAY);
    }

    @Test
    @DisplayName("a refund record refused for what it is on its own reads in Vietnamese")
    void refundRecord() {
        assertThat(vietnamese(() -> RefundRecord.record(UUID.randomUUID(), UUID.randomUUID(), "DH-1",
                new BigDecimal("0.5"), "VND", TODAY, "FT", null, UUID.randomUUID(), NOW, TODAY)))
                .isEqualTo("Số tiền hoàn phải lớn hơn 0 và là số đồng nguyên.");
        assertThat(vietnamese(() -> RefundRecord.record(UUID.randomUUID(), UUID.randomUUID(), "DH-1",
                new BigDecimal("100"), "VND", TODAY.plusDays(1), "FT", null, UUID.randomUUID(), NOW, TODAY)))
                .isEqualTo("Ngày hoàn tiền là bắt buộc và không được ở tương lai.");
        assertThat(vietnamese(() -> RefundRecord.record(UUID.randomUUID(), UUID.randomUUID(), "DH-1",
                new BigDecimal("100"), "VND", TODAY, " ", null, UUID.randomUUID(), NOW, TODAY)))
                .isEqualTo("Mã tham chiếu sao kê của lần hoàn tiền dài từ 1 đến 100 ký tự.");

        RefundRecord voided = refund();
        voided.voidRecord("recorded twice", UUID.randomUUID(), NOW);
        assertThat(vietnamese(() -> voided.voidRecord("again", UUID.randomUUID(), NOW)))
                .isEqualTo("Ghi nhận hoàn tiền FT-REFUND-1 của đơn DH-1 đã bị huỷ trước đó.");
        assertThat(vietnamese(() -> refund().voidRecord(" ", UUID.randomUUID(), NOW)))
                .isEqualTo("Huỷ ghi nhận hoàn tiền cần ghi lý do (tối đa 500 ký tự).");
    }

    @Test
    @DisplayName("the fee a cancellation keeps reads in Vietnamese, its limits grouped the reader's way")
    void settlementFee() {
        assertThat(vietnamese(() -> new OrderSettlement.Fee(BigDecimal.TEN, BigDecimal.TEN, "r", "d", com.stockflow.payment.api.FeeBearer.CUSTOMER)))
                .isEqualTo("Chỉ giữ lại theo tỷ lệ phần trăm hoặc theo số tiền thoả thuận, không được cả hai.");
        assertThat(vietnamese(() -> new OrderSettlement.Fee(new BigDecimal("101"), null, null, null,
                com.stockflow.payment.api.FeeBearer.CUSTOMER)))
                .isEqualTo("Tỷ lệ giữ lại phải lớn hơn 0 và tối đa 100%, tối đa hai chữ số thập phân.");
        assertThat(vietnamese(() -> new OrderSettlement.Fee(null, BigDecimal.TEN, null, null,
                com.stockflow.payment.api.FeeBearer.CUSTOMER)))
                .isEqualTo("Số tiền thoả thuận cần có lý do (tối đa 500 ký tự) và diễn giải (tối đa 2.000 ký tự).");
    }

    @Test
    @DisplayName("a reversed receipt date range: the message and the field's both in Vietnamese, the code kept")
    void reversedDateRange() {
        LocaleContextHolder.setLocale(VIETNAMESE);
        var body = handler.handleBusiness(refusal(() -> new ReceiptFilter(null, null, null, TODAY, TODAY.minusDays(1))))
                .getBody();
        assertThat(body.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.message()).isEqualTo("Ngày nhận tiền \"từ\" không được sau ngày nhận tiền \"đến\".");
        assertThat(body.fieldErrors()).containsExactly(new FieldError("receivedFrom",
                "Ngày nhận tiền \"từ\" không được sau ngày nhận tiền \"đến\".", "RangeInverted"));
    }

    @Test
    @DisplayName("transfer details of a paid installment: refused with the status's Vietnamese label, not PAID")
    void notPayableReadsTheLabel() {
        InstallmentBook book = mock(InstallmentBook.class);
        Installment paid = new Installment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "HD-1", "DH-1", 1, "Dot 1", InstallmentMilestone.ON_SIGNING, null, null,
                new BigDecimal("1000"), new BigDecimal("1000"), "VND", 0, NOW, TODAY, InstallmentStatus.PAID, NOW, null,
                0L);
        when(book.find(paid.id())).thenReturn(Optional.of(paid));
        InstallmentService service = new InstallmentService(book, mock(InstallmentSearch.class),
                mock(InstallmentOrders.class), new CompanyBankProperties("Bank", "0001", "StockFlow"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(vietnamese(() -> service.transferInstructions(paid.id(), null)))
                .isEqualTo("Đợt 1 của hợp đồng HD-1 đang ở trạng thái Đã trả: không ghi nhận thêm tiền được.");
        assertThat(vietnamese(() -> service.transferInstructions(UUID.randomUUID(), null)))
                .isEqualTo("Không tìm thấy đợt thanh toán.");
    }

    @Test
    @DisplayName("every key this change added reads in both languages, so none falls back to its English log text")
    void keysResolve() {
        // refusal.payment.failedTripFee is no longer read: WF-05 Q-19 removed the failed-trip fee (BE-53).
        for (String key : List.of("refusal.payment.orderNoInstallments", "refusal.payment.overdueIsAFlag",
                "refusal.payment.orderNotFound", "refusal.payment.exportTooLarge",
                "refusal.payment.receivedRange", "refusal.payment.searchTooBroad", "refusal.refund.notFound",
                "refusal.refund.exceedsRefundable", "refusal.refund.referenceInUse",
                "refusal.settlement.feeAmount", "refusal.settlement.feeReasonWithoutAmount",
                "refusal.settlement.closureShare", "refusal.settlement.closureAmountAbove")) {
            Object[] args = {"x", "y", "z", "w"};
            assertThat(source.getMessage(key, args, VIETNAMESE)).as(key).isNotBlank()
                    .isNotEqualTo(source.getMessage(key, args, Locale.ENGLISH));
        }
        LocaleContextHolder.setLocale(VIETNAMESE);
        assertThat(new Messages(source).refusal("refusal.payment.exportTooLarge", "x", 50_000))
                .isEqualTo("Bộ lọc khớp hơn 50.000 phiếu thu hoặc dòng phiếu thu: hãy thu hẹp bộ lọc rồi xuất lại.");
    }
}
