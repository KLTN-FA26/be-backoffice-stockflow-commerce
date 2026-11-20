package com.stockflow.customer.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.web.GlobalExceptionHandler;
import com.stockflow.customer.api.CustomerLegalProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * FE BE-8: a legal profile refused for its fields answers {@code VALIDATION_FAILED} with one {@code fieldErrors} entry
 * per field at fault, each in the reader's language, as a form validated by Bean Validation does - not one English
 * line naming the first field.
 */
class LegalProfileFieldErrorsTest {

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

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static CustomerLegalProfileService.Save save(String address, String representative, String title,
                                                         String invoiceEmail,
                                                         CustomerLegalProfile.BankAccount bank) {
        return new CustomerLegalProfileService.Save(UUID.randomUUID(), address, representative, title, invoiceEmail,
                bank, null, UUID.randomUUID());
    }

    @Test
    @DisplayName("every field at fault named under its own key, in Vietnamese; the code stays VALIDATION_FAILED")
    void everyFieldInVietnamese() {
        LocaleContextHolder.setLocale(VIETNAMESE);
        BusinessException refused = catchThrowableOfType(BusinessException.class,
                () -> CustomerLegalProfileService.check(save(" ", "x".repeat(201), "Director", "ke toan@example",
                        new CustomerLegalProfile.BankAccount("12#", "Vietcombank", " ")), "contact@example.com"));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        var body = handler.handleBusiness(refused).getBody();
        assertThat(body.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.message()).isEqualTo("Hồ sơ pháp lý có 5 trường cần sửa.");
        assertThat(body.fieldErrors()).containsExactly(
                new FieldError("registeredAddress", "Không được để trống.", "NotBlank"),
                new FieldError("representativeName", "Tối đa 200 ký tự.", "Size"),
                new FieldError("invoiceEmail", "Email nhận hoá đơn không hợp lệ.", "Email"),
                new FieldError("bankAccount.accountNumber", "Số tài khoản gồm 4 đến 34 chữ số hoặc chữ cái.",
                        "Pattern"),
                new FieldError("bankAccount.accountHolder", "Không được để trống.", "NotBlank"));
    }

    @Test
    @DisplayName("English when asked; a valid profile is kept trimmed, the invoice e-mail defaulting to the contact")
    void englishAndValid() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        BusinessException refused = catchThrowableOfType(BusinessException.class,
                () -> CustomerLegalProfileService.check(save("12 Nguyen Hue", "Tran Van A", null, null, null), null));
        var body = handler.handleBusiness(refused).getBody();
        assertThat(body.message()).isEqualTo("The legal profile has 1 field(s) to correct.");
        assertThat(body.fieldErrors()).containsExactly(new FieldError("representativeTitle", "Required.", "NotBlank"));

        var checked = CustomerLegalProfileService.check(save(" 12 Nguyen Hue ", "Tran Van A", "Director", null,
                new CustomerLegalProfile.BankAccount("0123 456 789", " Vietcombank ", "CONG TY")), "Ke.Toan@Example.com");
        assertThat(checked.registeredAddress()).isEqualTo("12 Nguyen Hue");
        assertThat(checked.invoiceEmail()).isEqualTo("ke.toan@example.com");
        assertThat(checked.bankAccount()).isEqualTo(
                new CustomerLegalProfile.BankAccount("0123456789", "Vietcombank", "CONG TY"));
    }
}
