package com.stockflow.common.web;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-464: a business refusal that names request fields answers with them, as Bean Validation
 * does, each message translated by the field's own code.
 */
class BusinessFieldErrorsTest {

    private final StaticMessageSource source = new StaticMessageSource();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new Messages(source), source);

    @Test
    @DisplayName("fieldErrors keep field and code; the message is the translation of the code, else the detail")
    void fieldsAreTranslatedByTheirCode() {
        source.addMessage("error.LOT_REQUIRED", Locale.forLanguageTag("vi"), "Hãy nhập số lô");
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
        try {
            var response = handler.handleBusiness(new BusinessException(ErrorCode.RECEIPT_LOT_DATA_INVALID,
                    "Line 2: CUP is lot-tracked", List.of(
                            new FieldError("lines[1].lotNumber", "CUP is lot-tracked", "LOT_REQUIRED"),
                            new FieldError("lines[1].expiryDate", "untranslated detail", "NO_SUCH_KEY"))));

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getBody().errorCode()).isEqualTo("RECEIPT_LOT_DATA_INVALID");
            assertThat(response.getBody().fieldErrors()).containsExactly(
                    new FieldError("lines[1].lotNumber", "Hãy nhập số lô", "LOT_REQUIRED"),
                    new FieldError("lines[1].expiryDate", "untranslated detail", "NO_SUCH_KEY"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    @DisplayName("a refusal naming no field answers without fieldErrors, as before")
    void noFieldsNoList() {
        var response = handler.handleBusiness(new BusinessException(ErrorCode.CONFLICT, "busy"));
        assertThat(response.getBody().fieldErrors()).isNull();
    }

    @Test
    @DisplayName("SCRUM-193: a refusal's details travel in the envelope, and are absent when there are none")
    void detailsTravel() {
        var response = handler.handleBusiness(new BusinessException(ErrorCode.BALANCE_CREDIT_LIMIT_EXCEEDED, "over",
                List.of(), java.util.Map.of("availableCredit", new java.math.BigDecimal("1250000"), "currency", "VND")));
        assertThat(response.getBody().details()).containsEntry("currency", "VND")
                .containsEntry("availableCredit", new java.math.BigDecimal("1250000"));
        assertThat(handler.handleBusiness(new BusinessException(ErrorCode.CONFLICT, "busy")).getBody().details()).isNull();
    }
}
