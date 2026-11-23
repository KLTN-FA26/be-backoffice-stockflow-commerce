package com.stockflow.invoicing.internal.domain;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WF-05 BE-01, BE-26 (decisions Q-1, Q-10; invariant V7: a non-original's date is the day it is issued, its series the
 * issuing year's). SePay takes only today's date - an original or an adjustment dated yesterday is refused at once
 * (sandbox runs T-1, T-6) - and a correction dated the day it was drawn up was refused for good, a reduction of a failed
 * delivery that could not be voided with it.
 */
class EInvoiceCorrectionIssueDateTest {

    private static final LocalDate ISSUED_ON = EInvoiceFixtures.DELIVERED_ON.plusDays(4);

    @Test
    @DisplayName("BE-01: a correction drawn up on one day and issued days later carries the issuing day, under every "
            + "provider, and its deadline follows; it is never flagged late")
    void aCorrectionCarriesItsIssuingDay() {
        for (boolean datesOnlyToday : List.of(true, false)) {
            EInvoice original = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
            EInvoice adjustment = EInvoiceFixtures.adjustment(original, CorrectionBasis.RETURN, -2);
            assertThat(adjustment.issueDate()).isEqualTo(EInvoiceFixtures.CORRECTED_ON);

            assertThat(adjustment.dateForIssue(ISSUED_ON, datesOnlyToday)).isFalse();

            EInvoice.Snapshot dated = adjustment.snapshot();
            assertThat(dated.issueDate()).isEqualTo(ISSUED_ON);
            assertThat(dated.issueDueAt()).isEqualTo(IssueDeadline.of(ISSUED_ON));
            assertThat(dated.warnings()).doesNotContain(EInvoice.Warning.ISSUED_LATE);
            assertThat(dated.deliveredOn()).isNull();
        }
    }

    @Test
    @DisplayName("V7: a correction drawn up on 31/12 and issued on 2/1 goes under the new year's series; the old one is "
            + "refused")
    void aCorrectionTakesTheIssuingYearsSeries() {
        EInvoice original = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        EInvoice adjustment = EInvoice.drawUpAdjustment(UUID.randomUUID(), "EINV-20261231-0001", original,
                CorrectionBasis.RETURN, "tra hang", original.buyer(), EInvoiceFixtures.cartons(original, -1),
                LocalDate.of(2026, 12, 31), UUID.randomUUID(), List.of(), EInvoice.Corrections.NONE);
        adjustment.dateForIssue(LocalDate.of(2027, 1, 2), true);

        Throwable oldSeries = catchThrowable(() -> adjustment.submit(EInvoiceFixtures.SERIES, null,
                Instant.parse("2027-01-02T03:00:00Z")));
        assertThat(((BusinessException) oldSeries).errorCode()).isEqualTo(ErrorCode.EINVOICE_SERIES_NOT_CONFIGURED);

        adjustment.submit(new InvoiceSeries("SANDBOX", "1", "C27TSB", 2027), null, Instant.parse("2027-01-02T03:00:00Z"));
        assertThat(adjustment.snapshot().issueDate()).isEqualTo(LocalDate.of(2027, 1, 2));
        assertThat(adjustment.snapshot().symbol()).isEqualTo("C27TSB");

        // BE-30: SePay chooses a correction's series itself (sandbox T-4); recorded under the one it numbered it under.
        adjustment.numberedUnder("C26TSB");
        assertThat(adjustment.snapshot().symbol()).isEqualTo("C26TSB");
        EInvoice original2027 = EInvoiceFixtures.in(EInvoiceStatus.SUBMITTING);
        assertThat(((BusinessException) catchThrowable(() -> original2027.numberedUnder("C25TSB"))).errorCode())
                .isEqualTo(ErrorCode.EINVOICE_INVALID_TRANSITION);
    }

    @Test
    @DisplayName("BE-26 (Q-10): under a provider that takes only today's date, an original issued after its delivery "
            + "day is dated the day it is issued and flagged ISSUED_LATE; its delivery day never moves; the flag "
            + "outlives the issue and blocks no automatic issuing")
    void anOriginalIssuedLateIsDatedTodayAndFlagged() {
        EInvoice draft = EInvoiceFixtures.draft();
        assertThat(draft.snapshot().deliveredOn()).isEqualTo(EInvoiceFixtures.DELIVERED_ON);
        assertThat(draft.lateIfIssuedOn(ISSUED_ON, true)).isTrue();
        assertThat(draft.lateIfIssuedOn(ISSUED_ON, false)).as("a provider taking a past date keeps the day").isFalse();

        assertThat(draft.dateForIssue(ISSUED_ON, true)).as("flagged now").isTrue();
        assertThat(draft.dateForIssue(ISSUED_ON, true)).as("flagged once").isFalse();

        EInvoice.Snapshot dated = draft.snapshot();
        assertThat(dated.issueDate()).isEqualTo(ISSUED_ON);
        assertThat(dated.deliveredOn()).isEqualTo(EInvoiceFixtures.DELIVERED_ON);
        assertThat(dated.warnings()).contains(EInvoice.Warning.ISSUED_LATE);
        assertThat(EInvoice.Warning.ISSUED_LATE.blocksAutomaticIssue()).isFalse();
        assertThat(EInvoice.Warning.BUYER_EMAIL_MISSING.blocksAutomaticIssue()).as("Q-22 BR-06").isFalse();
        assertThat(EInvoice.Warning.TAX_CLASS_UNKNOWN.blocksAutomaticIssue()).as("Q-21").isTrue();

        draft.submit(EInvoiceFixtures.SERIES, null, EInvoiceFixtures.NOW);
        draft.issued(7, "M1", "LOOK", EInvoiceFixtures.NOW, "x.xml", "x.pdf", EInvoiceFixtures.NOW);
        assertThat(draft.snapshot().warnings()).as("kept, to be counted").containsExactly(EInvoice.Warning.ISSUED_LATE);
    }

    @Test
    @DisplayName("Q-1: an original issued on its delivery day is not late; under a provider that takes a past date it "
            + "keeps the delivery day whenever it is issued")
    void anOriginalOnItsDayIsNotLate() {
        EInvoice sameDay = EInvoiceFixtures.draft();
        assertThat(sameDay.dateForIssue(EInvoiceFixtures.DELIVERED_ON, true)).isFalse();
        assertThat(sameDay.snapshot().warnings()).doesNotContain(EInvoice.Warning.ISSUED_LATE);
        assertThat(sameDay.snapshot().issueDate()).isEqualTo(EInvoiceFixtures.DELIVERED_ON);

        EInvoice viettel = EInvoiceFixtures.draft();
        assertThat(viettel.dateForIssue(ISSUED_ON, false)).isFalse();
        assertThat(viettel.snapshot().issueDate()).isEqualTo(EInvoiceFixtures.DELIVERED_ON);
    }

    @Test
    @DisplayName("Q-1 (owner 23/11: no next-working-day grace for automatic issuing): under every provider an "
            + "original is due by the end of its delivery day")
    void theDeadlineIsTheEndOfTheDeliveryDay() {
        assertThat(IssueDeadline.of(EInvoiceFixtures.DELIVERED_ON)).isEqualTo(
                EInvoiceFixtures.DELIVERED_ON.plusDays(1).atStartOfDay(BusinessCalendar.ZONE).toInstant());
        EInvoice draft = EInvoice.drawUpOriginal(UUID.randomUUID(), "EINV-1", EInvoiceFixtures.delivery(),
                EInvoiceFixtures.SELLER, EInvoiceFixtures.BUYER, EInvoiceFixtures.lines(), false, false, false);
        assertThat(draft.snapshot().issueDueAt()).isEqualTo(IssueDeadline.of(EInvoiceFixtures.DELIVERED_ON));
    }

    @Test
    @DisplayName("Q-21 (BE-31): a draft whose product has no tax class carries TAX_CLASS_UNKNOWN from the start, and a "
            + "re-check before issuing finds a class lost since")
    void anUnknownTaxClassIsFlagged() {
        EInvoice flagged = EInvoice.drawUpOriginal(UUID.randomUUID(), "EINV-1", EInvoiceFixtures.delivery(),
                EInvoiceFixtures.SELLER, EInvoiceFixtures.BUYER, EInvoiceFixtures.lines(), false, false, true);
        assertThat(flagged.snapshot().warnings()).contains(EInvoice.Warning.TAX_CLASS_UNKNOWN);

        EInvoice clean = EInvoiceFixtures.draft();
        assertThat(clean.recheckBeforeIssue(false, false, false, true)).containsExactly(
                EInvoice.Warning.TAX_CLASS_UNKNOWN);
        assertThat(clean.recheckBeforeIssue(false, false, false, true)).as("only what is new").isEmpty();
    }
}
