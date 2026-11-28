package com.stockflow.identity.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A cover during leave (sales-owner plan BR-S07, D7): its own rules; the overlap rule is the service's and the DB's. */
class StaffCoverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);
    private static final UUID AWAY = UUID.randomUUID();
    private static final UUID STAND_IN = UUID.randomUUID();

    @Test
    @DisplayName("BR-S07: a cover covers its days, both ends included, and no other")
    void coversItsDays() {
        StaffCover cover = arrange(TODAY, TODAY.plusDays(2));

        assertThat(cover.status()).isEqualTo(StaffCover.Status.ACTIVE);
        assertThat(cover.coversOn(TODAY)).isTrue();
        assertThat(cover.coversOn(TODAY.plusDays(2))).isTrue();
        assertThat(cover.coversOn(TODAY.minusDays(1))).isFalse();
        assertThat(cover.coversOn(TODAY.plusDays(3))).as("it ends by itself").isFalse();
    }

    @Test
    @DisplayName("BR-S07 / D7: 60 days counted inclusively is the longest; 61 is refused STAFF_COVER_TOO_LONG")
    void atMostSixtyDays() {
        assertThat(arrange(TODAY, TODAY.plusDays(59)).coversOn(TODAY.plusDays(59))).isTrue();
        refused(ErrorCode.STAFF_COVER_TOO_LONG, () -> arrange(TODAY, TODAY.plusDays(60)));
    }

    @Test
    @DisplayName("BR-S07: nobody covers for themselves (STAFF_COVER_SELF)")
    void notForOneself() {
        refused(ErrorCode.STAFF_COVER_SELF, () -> StaffCover.arrange(UUID.randomUUID(), AWAY, AWAY, TODAY, TODAY, null,
                AWAY, TODAY));
    }

    @Test
    @DisplayName("BR-S07: the first day comes before the last, and a cover that already ended is refused")
    void daysInOrderAndNotOver() {
        refused(ErrorCode.VALIDATION_FAILED, () -> arrange(TODAY.plusDays(1), TODAY));
        refused(ErrorCode.VALIDATION_FAILED, () -> arrange(TODAY.minusDays(5), TODAY.minusDays(1)));
        assertThat(arrange(TODAY.minusDays(3), TODAY).coversOn(TODAY)).as("a cover running since before today").isTrue();
    }

    @Test
    @DisplayName("BR-S08: a cancelled cover covers nothing; cancelling twice changes nothing")
    void cancelling() {
        StaffCover cover = arrange(TODAY, TODAY.plusDays(5));
        Instant at = Instant.parse("2026-11-28T05:00:00Z");

        assertThat(cover.cancel(null, at, StaffCover.CancelReason.COVER_DEACTIVATED)).isTrue();
        assertThat(cover.cancel(AWAY, at.plusSeconds(60), StaffCover.CancelReason.MANUAL)).isFalse();

        assertThat(cover.status()).isEqualTo(StaffCover.Status.CANCELLED);
        assertThat(cover.cancelReason()).isEqualTo(StaffCover.CancelReason.COVER_DEACTIVATED);
        assertThat(cover.cancelledAt()).isEqualTo(at);
        assertThat(cover.coversOn(TODAY)).isFalse();
    }

    private static StaffCover arrange(LocalDate from, LocalDate to) {
        return StaffCover.arrange(UUID.randomUUID(), AWAY, STAND_IN, from, to, "leave", AWAY, TODAY);
    }

    private static void refused(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                refusal -> assertThat(refusal.errorCode()).isEqualTo(code));
    }
}
