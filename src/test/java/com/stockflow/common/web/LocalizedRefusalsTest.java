package com.stockflow.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.storage.ContentTypePolicy;
import com.stockflow.common.storage.FileCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A business refusal people read answers in their language (WF-02 review, gap 3): the exception names a bundle key and
 * its arguments, the handler translates it - Vietnamese by default, English when asked - over the real bundles, the
 * English message of the exception being the fallback; an enum argument reads as its label; a field's message the same;
 * and the key never reaches the wire.
 */
class LocalizedRefusalsTest {

    private static final Locale VIETNAMESE = Locale.of("vi");

    private final ReloadableResourceBundleMessageSource source = bundles();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new Messages(source), source);

    private static ReloadableResourceBundleMessageSource bundles() {
        ReloadableResourceBundleMessageSource bundles = new ReloadableResourceBundleMessageSource();
        bundles.setBasenames("classpath:i18n/messages", "classpath:i18n/validation");
        bundles.setDefaultEncoding(StandardCharsets.UTF_8.name());
        bundles.setFallbackToSystemLocale(false);
        return bundles;
    }

    private enum Probe { SENT }

    /** Named as the order module's own, so its label is the bundle's {@code label.QuoteStatus.ACCEPTED}. */
    private enum QuoteStatus { ACCEPTED }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static BusinessException belowMoq() {
        return new BusinessException(ErrorCode.QUOTE_BELOW_MOQ, "Line 1: 10 cartons is below its MOQ of 20", List.of(),
                Map.of("moq", 20), "refusal.quote.belowMoq", 1, "LY-12OZ", 10, 20);
    }

    @Test
    @DisplayName("TC-F03-01: below the MOQ, the message shows the MOQ in Vietnamese, the details carry it for the form")
    void vietnameseByDefault() {
        LocaleContextHolder.setLocale(VIETNAMESE);
        var body = handler.handleBusiness(belowMoq()).getBody();
        assertThat(body.errorCode()).isEqualTo("QUOTE_BELOW_MOQ");
        assertThat(body.message()).isEqualTo("Dòng 1 (LY-12OZ): 10 thùng thấp hơn số lượng đặt tối thiểu (MOQ) là 20 thùng.");
        assertThat(body.details()).containsEntry("moq", 20);
    }

    @Test
    @DisplayName("English when the caller asks for it")
    void englishWhenAsked() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(handler.handleBusiness(belowMoq()).getBody().message())
                .isEqualTo("Line 1 (LY-12OZ): 10 cartons is below the minimum order quantity of 20 cartons.");
    }

    @Test
    @DisplayName("an amount reads in the reader's grouping; an enum argument reads as its label")
    void amountsAndLabels() {
        LocaleContextHolder.setLocale(VIETNAMESE);
        var mismatch = new BusinessException(ErrorCode.PAYMENT_PLAN_INVALID, "plan", List.of(FieldError.localized(
                "paymentPlan", "the installments add up to 70000000", "BR-13", "refusal.contract.plan.totalMismatch",
                new BigDecimal("70000000"), new BigDecimal("77550000"))), Map.of(), "refusal.contract.planInvalid", 1);
        var body = handler.handleBusiness(mismatch).getBody();
        assertThat(body.message()).isEqualTo("Lịch thanh toán chưa hợp lệ (1 lỗi); xem chi tiết từng trường.");
        assertThat(body.fieldErrors()).containsExactly(new FieldError("paymentPlan",
                "Tổng các đợt thanh toán là 70.000.000, khác tổng tiền đơn (có thuế) là 77.550.000.", "BR-13"));

        var transition = new BusinessException(ErrorCode.INVALID_QUOTE_TRANSITION, "Cannot send quote QT-1")
                .localized("refusal.quote.transition.send", "QT-1", QuoteStatus.ACCEPTED);
        assertThat(handler.handleBusiness(transition).getBody().message())
                .isEqualTo("Báo giá QT-1 đang ở trạng thái Đã chấp nhận: không thể gửi lúc này.");
        var unlabelled = new BusinessException(ErrorCode.INVALID_QUOTE_TRANSITION, "x")
                .localized("refusal.quote.transition.send", "QT-1", Probe.SENT);
        assertThat(handler.handleBusiness(unlabelled).getBody().message()).as("no label: the name")
                .isEqualTo("Báo giá QT-1 đang ở trạng thái SENT: không thể gửi lúc này.");
    }

    @Test
    @DisplayName("a key missing from the bundles falls back to the English message, never to the key")
    void missingKeyFallsBack() {
        LocaleContextHolder.setLocale(VIETNAMESE);
        var refusal = new BusinessException(ErrorCode.CONFLICT, "Exactly this English").localized("refusal.no.such.key");
        assertThat(handler.handleBusiness(refusal).getBody().message()).isEqualTo("Exactly this English");
    }

    @Test
    @DisplayName("a refusal with nothing specific - the code's own English default - answers the code's translation")
    void genericDefaultIsTranslated() {
        LocaleContextHolder.setLocale(VIETNAMESE);
        var body = handler.handleBusiness(new BusinessException(ErrorCode.CHAT_DESIGN_NOT_APPROVED)).getBody();
        assertThat(body.message()).isEqualTo(source.getMessage("error.CHAT_DESIGN_NOT_APPROVED", null, VIETNAMESE))
                .isNotEqualTo(ErrorCode.CHAT_DESIGN_NOT_APPROVED.defaultMessage());
        assertThat(handler.handleBusiness(new BusinessException(ErrorCode.CONFLICT, "busy")).getBody().message())
                .as("a specific message stays as it was").isEqualTo("busy");
    }

    @Test
    @DisplayName("an upload refused for its size or type answers in Vietnamese with the limit and what is accepted")
    void uploadRefusals() {
        LocaleContextHolder.setLocale(VIETNAMESE);
        BusinessException tooLarge = catchBusiness(() -> ContentTypePolicy.check(FileCategory.DOCUMENT,
                "application/pdf", FileCategory.DOCUMENT.maxBytes() + 1, "%PDF-1.4".getBytes(StandardCharsets.US_ASCII)));
        assertThat(handler.handleBusiness(tooLarge).getBody().message()).isEqualTo("File quá lớn: tối đa 25 MB.");
        assertThat(tooLarge.getMessage()).contains("at most 25 MB");

        BusinessException wrongType = catchBusiness(() -> ContentTypePolicy.check(FileCategory.DOCUMENT,
                "text/plain", 10, "hello".getBytes(StandardCharsets.US_ASCII)));
        assertThat(handler.handleBusiness(wrongType).getBody().message())
                .startsWith("Không nhận loại file này. Loại được nhận: ").contains("application/pdf");
    }

    @Test
    @DisplayName("every upload-check key reads in both languages, so no refusal falls back to its English log text")
    void uploadCheckKeysResolve() {
        for (String key : List.of("refusal.pdf.encrypted", "refusal.pdf.javascript", "refusal.pdf.attachedFiles",
                "refusal.pdf.xfa", "refusal.pdf.media", "refusal.pdf.password", "refusal.pdf.unreadable",
                "refusal.pdf.action", "refusal.upload.tooLarge", "refusal.upload.type",
                "refusal.upload.contentMismatch", "refusal.upload.noType", "refusal.image.unreadable",
                "refusal.image.tooManyPixels", "refusal.upload.busy")) {
            assertThat(source.getMessage(key, new Object[] {"x"}, VIETNAMESE)).as(key).isNotBlank();
            assertThat(source.getMessage(key, new Object[] {"x"}, Locale.ENGLISH)).as(key).isNotBlank();
        }
    }

    private static BusinessException catchBusiness(Runnable call) {
        try {
            call.run();
        } catch (BusinessException refused) {
            return refused;
        }
        throw new AssertionError("not refused");
    }

    @Test
    @DisplayName("the key and its arguments never reach the wire: a field error is field, message and code")
    void keyIsNotSerialised() throws Exception {
        String json = new ObjectMapper().writeValueAsString(FieldError.localized("lines[0].cartons", "below", "Min",
                "refusal.quote.belowMoq", 1, "SKU", 10, 20));
        assertThat(new ObjectMapper().readTree(json).properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("field", "message", "code");
    }

    @Test
    @DisplayName("localized() keeps the code, message, fields and details; a subclass passes its key to the constructor")
    void localizedCopiesEverything() {
        var original = new BusinessException(ErrorCode.CONFLICT, "english", List.of(FieldError.of("a", "b")),
                Map.of("k", 1));
        var localized = original.localized("refusal.quote.noLine");
        assertThat(localized.errorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(localized.getMessage()).isEqualTo("english");
        assertThat(localized.fieldErrors()).isEqualTo(original.fieldErrors());
        assertThat(localized.details()).isEqualTo(original.details());
        assertThat(localized.messageKey()).isEqualTo("refusal.quote.noLine");
        assertThatThrownBy(() -> new NotFoundException("quote", "1").localized("x"))
                .isInstanceOf(IllegalStateException.class);
    }
}
