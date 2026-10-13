package com.stockflow.procurement.internal.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** BR-06's soft half: the window is counted in calendar days from the receiving date, inclusive. */
class ExpiryWarningTest {

    private static final LocalDate RECEIVED = LocalDate.of(2026, 10, 14);

    @Test
    void warnsInsideTheWindowAndOnItsLastDay() {
        assertThat(ExpiryWarning.isNear(RECEIVED.plusDays(10), RECEIVED, 30)).isTrue();
        assertThat(ExpiryWarning.isNear(RECEIVED.plusDays(30), RECEIVED, 30)).isTrue();
        assertThat(ExpiryWarning.isNear(RECEIVED.plusDays(1), RECEIVED, 30)).isTrue();
    }

    @Test
    void staysQuietOneDayPastTheWindow() {
        assertThat(ExpiryWarning.isNear(RECEIVED.plusDays(31), RECEIVED, 30)).isFalse();
        assertThat(ExpiryWarning.isNear(RECEIVED.plusYears(1), RECEIVED, 30)).isFalse();
    }

    @Test
    void aLineWithoutAnExpiryDateNeverWarns() {
        assertThat(ExpiryWarning.isNear(null, RECEIVED, 30)).isFalse();
    }

    @Test
    void aLotThatIsAlreadyOutOfDateStillWarnsRatherThanBeingHidden() {
        // The count refuses these (EXPIRY_NOT_AFTER_RECEIPT); an older row must not read as healthy.
        assertThat(ExpiryWarning.isNear(RECEIVED, RECEIVED, 30)).isTrue();
        assertThat(ExpiryWarning.isNear(RECEIVED.minusDays(1), RECEIVED, 30)).isTrue();
    }

    @Test
    void followsTheConfiguredWindow() {
        assertThat(ExpiryWarning.isNear(RECEIVED.plusDays(7), RECEIVED, 7)).isTrue();
        assertThat(ExpiryWarning.isNear(RECEIVED.plusDays(8), RECEIVED, 7)).isFalse();
        assertThat(ExpiryWarning.isNear(RECEIVED.plusDays(31), RECEIVED, 90)).isTrue();
    }
}
