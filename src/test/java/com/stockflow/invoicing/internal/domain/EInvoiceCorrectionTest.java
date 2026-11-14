package com.stockflow.invoicing.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The marks an issued invoice takes when a correction of it is issued, and the one thing it never takes (WF-05 E4;
 * TT 91/2026/TT-BTC Điều 10; plans/E0-legal.md): it becomes REPLACED or it is flagged as adjusted, and it is never
 * cancelled or voided - Điều 10 has no such procedure (E0 row 6). Drawing a correction up is the service's
 * ({@code EInvoiceCorrectionsTest}).
 */
class EInvoiceCorrectionTest {

    private static void assertRefused(ThrowingCallable call, ErrorCode expected) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).errorCode()).isEqualTo(expected);
    }

    @Test
    @DisplayName("its replacement issued, the original is REPLACED: final, and no longer an invoice to correct")
    void aReplacedOriginalIsFinal() {
        EInvoice original = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        EInvoice replacement = EInvoiceFixtures.agreed(EInvoiceFixtures.replacement(original, 18));
        assertThat(original.status()).as("in force while its replacement is a draft").isEqualTo(EInvoiceStatus.ISSUED);

        EInvoiceFixtures.issued(replacement, 2);
        original.replaced();

        assertThat(original.status()).isEqualTo(EInvoiceStatus.REPLACED);
        assertThat(original.adjusted()).isFalse();
        assertThat(original.snapshot().invoiceNumber()).as("what it said stays as it was issued").isEqualTo(1);
        assertThat(EInvoiceStatus.REPLACED.next()).isEmpty();
        assertRefused(original::replaced, ErrorCode.EINVOICE_INVALID_TRANSITION);
        assertRefused(original::markAdjusted, ErrorCode.EINVOICE_NOT_IN_FORCE);
        assertRefused(() -> EInvoiceFixtures.adjustment(original, CorrectionBasis.ERROR, -2),
                ErrorCode.EINVOICE_NOT_IN_FORCE);
        assertRefused(() -> EInvoiceFixtures.replacement(original, 17), ErrorCode.EINVOICE_NOT_IN_FORCE);
    }

    @Test
    @DisplayName("its adjustment issued, the original is flagged and stays ISSUED; flagged twice is flagged once; and "
            + "an adjusted invoice is never REPLACED")
    void anAdjustedOriginalStaysInForce() {
        EInvoice original = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        assertThat(original.adjusted()).isFalse();

        original.markAdjusted();
        original.markAdjusted();

        assertThat(original.adjusted()).isTrue();
        assertThat(original.status()).isEqualTo(EInvoiceStatus.ISSUED);
        // Khoản 6 điểm a: the form first used is kept.
        assertRefused(original::replaced, ErrorCode.EINVOICE_CORRECTION_FORM_FIXED);
        assertThat(original.status()).isEqualTo(EInvoiceStatus.ISSUED);
        // Adjusted again, though: an adjustment that was wrong is put right by another one of the same invoice.
        assertThat(EInvoiceFixtures.adjustment(original, CorrectionBasis.ERROR, -1).originalEInvoiceId())
                .isEqualTo(original.id());
    }

    @Test
    @DisplayName("only an invoice in force is flagged as adjusted")
    void onlyAnInvoiceInForceIsFlagged() {
        for (EInvoiceStatus status : EInvoiceStatus.values()) {
            if (status == EInvoiceStatus.ISSUED) {
                continue;
            }
            EInvoice notInForce = EInvoiceFixtures.in(status);

            assertRefused(notInForce::markAdjusted, ErrorCode.EINVOICE_NOT_IN_FORCE);
            assertThat(notInForce.adjusted()).isFalse();
        }
    }

    @Test
    @DisplayName("E0 row 6: an issued invoice is never voided, reopened or cancelled - adjusted or not, replaced or not")
    void anIssuedInvoiceHasNoWayBack() {
        EInvoice issued = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        EInvoice adjusted = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        adjusted.markAdjusted();
        EInvoice replaced = EInvoiceFixtures.in(EInvoiceStatus.REPLACED);

        for (EInvoice invoice : new EInvoice[] {issued, adjusted, replaced}) {
            EInvoice.Snapshot before = invoice.snapshot();

            assertRefused(() -> invoice.voidDraft("drawn up by mistake", null, EInvoiceFixtures.NOW),
                    ErrorCode.EINVOICE_INVALID_TRANSITION);
            assertRefused(invoice::reopen, ErrorCode.EINVOICE_INVALID_TRANSITION);
            assertRefused(() -> invoice.rejected("LATE", "refused after all"), ErrorCode.EINVOICE_INVALID_TRANSITION);

            assertThat(invoice.snapshot()).as("refused whole: nothing of the void is kept").isEqualTo(before);
        }
        // The state machine has one way out of ISSUED, and it is not a cancellation.
        assertThat(EInvoiceStatus.ISSUED.next()).containsExactly(EInvoiceStatus.REPLACED);
        assertThat(EInvoiceStatus.values()).extracting(EInvoiceStatus::name).noneMatch(name -> name.contains("CANCEL"));
        // And the aggregate has no operation that could be one: adding a cancel() is a decision to take against the
        // law's text, not a helper to slip in.
        assertThat(Arrays.stream(EInvoice.class.getDeclaredMethods()).filter(method -> Modifier.isPublic(
                method.getModifiers())).map(Method::getName).map(name -> name.toLowerCase(Locale.ROOT)))
                .noneMatch(name -> name.contains("cancel"));
    }
}
