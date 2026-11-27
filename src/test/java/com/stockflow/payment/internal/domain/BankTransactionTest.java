package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Part C: a transfer the bank feed reported is kept, then decided once - and back in the queue if its receipt is voided. */
class BankTransactionTest {

    private static final Instant NOW = Instant.parse("2026-11-26T03:00:00Z");
    private static final UUID ACCOUNTANT = Identifiers.newId();

    private static BankTransfer transfer(String reference) {
        return new BankTransfer("92704", "0071001234567", new BigDecimal("660000"), "HD-20261108-000007-1 DOT1",
                reference, null, NOW.minusSeconds(60), "{}");
    }

    private static BankTransaction received(String reference) {
        return BankTransaction.received(BankTransaction.Provider.SEPAY, BankTransaction.Source.WEBHOOK,
                transfer(reference), NOW);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("applied automatically from RECEIVED: no reviewer; the receipt carries the bank's reference")
    void appliedAutomatically() {
        BankTransaction t = received("FT26330ABC");
        UUID receipt = Identifiers.newId();
        t.applied(receipt, null, NOW);
        assertThat(t.status()).isEqualTo(BankTransactionStatus.APPLIED);
        assertThat(t.receiptId()).isEqualTo(receipt);
        assertThat(t.reviewedBy()).isNull();
        assertThat(t.receiptReference()).isEqualTo("FT26330ABC");
        assertThatThrownBy(() -> t.applied(Identifiers.newId(), null, NOW))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.BANK_TRANSACTION_NOT_OPEN));
    }

    @Test
    @DisplayName("without a bank reference the receipt's reference is SEPAY-<id>")
    void referenceWithoutTheBanks() {
        assertThat(received(null).receiptReference()).isEqualTo("SEPAY-92704");
    }

    @Test
    @DisplayName("to the queue with a reason; the Accountant assigns it (not automatically) or ignores it with a reason")
    void queueThenAssignOrIgnore() {
        BankTransaction assigned = received("FT1");
        assigned.needsReview(BankTransaction.ReviewReason.OVERPAYMENT, "brought more", null, null, NOW);
        assertThat(assigned.status()).isEqualTo(BankTransactionStatus.NEEDS_REVIEW);
        assertThatThrownBy(() -> assigned.applied(Identifiers.newId(), null, NOW))
                .as("the feed itself does not apply what waits for the Accountant")
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.BANK_TRANSACTION_NOT_OPEN));
        assigned.applied(Identifiers.newId(), ACCOUNTANT, NOW);
        assertThat(assigned.reviewedBy()).isEqualTo(ACCOUNTANT);
        assertThat(assigned.reviewReason()).isNull();

        BankTransaction ignored = received("FT2");
        ignored.needsReview(BankTransaction.ReviewReason.NO_INSTALLMENT_NAMED, "none", null, null, NOW);
        assertThatThrownBy(() -> ignored.ignore("  ", ACCOUNTANT, NOW))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        ignored.ignore("Interest paid by the bank", ACCOUNTANT, NOW);
        assertThat(ignored.status()).isEqualTo(BankTransactionStatus.IGNORED);
        assertThatThrownBy(() -> ignored.ignore("again", ACCOUNTANT, NOW))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.BANK_TRANSACTION_NOT_OPEN));
    }

    @Test
    @DisplayName("a RECEIVED transfer is not ignored: only what waits for the Accountant")
    void receivedIsNotIgnored() {
        assertThatThrownBy(() -> received("FT3").ignore("why", ACCOUNTANT, NOW))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.BANK_TRANSACTION_NOT_OPEN));
    }

    @Test
    @DisplayName("its receipt voided: back to the queue, RECEIPT_VOIDED, no receipt named")
    void receiptVoided() {
        BankTransaction t = received("FT4");
        t.applied(Identifiers.newId(), null, NOW);
        t.receiptVoided("FT4", NOW.plusSeconds(5));
        assertThat(t.status()).isEqualTo(BankTransactionStatus.NEEDS_REVIEW);
        assertThat(t.reviewReason()).isEqualTo(BankTransaction.ReviewReason.RECEIPT_VOIDED);
        assertThat(t.receiptId()).isNull();
        assertThat(t.reviewNote()).contains("FT4");
    }

    @Test
    @DisplayName("one transfer, one dedupe key: the bank reference as BR-03 compares it; without one, a digest")
    void dedupeKey() {
        assertThat(transfer("ft 26330 abc").dedupeKey()).isEqualTo("REF:FT26330ABC");
        BankTransfer webhook = transfer(null);
        BankTransfer listed = new BankTransfer("v2:9f1c", "0071001234567", new BigDecimal("660000.00"),
                "HD-20261108-000007-1 DOT1", null, null, NOW.minusSeconds(60), "{}");
        assertThat(listed.dedupeKey()).as("the same transfer from the API, whose id is not the webhook's")
                .isEqualTo(webhook.dedupeKey()).startsWith("SHA256:");
        assertThatThrownBy(() -> new BankTransfer("1", null, new BigDecimal("0.5"), null, null, null, NOW, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
