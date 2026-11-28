package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a load shows as its booking's last error (WF4 UX audit S17-2): the advice for the refusal of a FAILED booking,
 * and whatever was stored for every other booking status.
 */
class DispatchWebMapperRefusalTest {

    private static final String RAW = "400 INVALID_LOCATION: Failed";

    private DispatchWebMapper mapper;

    @BeforeEach
    void vietnameseReader() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        LocaleContextHolder.setLocale(new Locale("vi"));
        mapper = new DispatchWebMapper(new Messages(source));
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void theRefusalOfAFailedBookingIsShownAsAdvice() {
        String shown = mapper.refusalAsAdvice("FAILED", RAW);

        assertThat(shown).contains("Quản lý kho").contains("Tạo đơn ở hãng").doesNotContain("INVALID_LOCATION");
    }

    @Test
    void aBookingInAnyOtherStatusKeepsWhatWasStored() {
        for (String status : new String[]{"REQUESTED", "BOOKED", "UNCONFIRMED", "CANCELLED", "CANCEL_FAILED", null}) {
            assertThat(mapper.refusalAsAdvice(status, RAW)).as(String.valueOf(status)).isEqualTo(RAW);
        }
    }

    @Test
    void aFailedBookingWithACodeNoBundleKnowsOrNoCodeKeepsWhatWasStored() {
        assertThat(mapper.refusalAsAdvice("FAILED", "400 BRAND_NEW_CODE: something new"))
                .isEqualTo("400 BRAND_NEW_CODE: something new");
        assertThat(mapper.refusalAsAdvice("FAILED", "Ahamove did not answer")).isEqualTo("Ahamove did not answer");
        assertThat(mapper.refusalAsAdvice("FAILED", null)).isNull();
    }
}
