package com.stockflow.payment.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part C: the installment a bank transfer's content names, read the way banks hand the content back - the card writes
 * {@code HD-20261108-000007-1 DOT2}, the bank may upper-case it, drop or replace the dashes, and wrap it in its own words.
 */
class TransferReferenceTest {

    private static final String CONTRACT = "HD-20261108-000007-1";

    @ParameterizedTest
    @ValueSource(strings = {
            "HD-20261108-000007-1 DOT2",
            "hd-20261108-000007-1 dot2",
            "HD 20261108 000007 1 DOT 2",
            "MBVCB.1234567.HD-20261108-000007-1 DOT2.CT tu 0123 CONG TY ABC",
            "CONG TY ABC chuyen tien HD-20261108-000007-1-DOT2 cam on",
            "HĐ-20261108-000007-1 ĐOT2"})
    @DisplayName("the card's wording, with separators kept or changed, names one contract and its installment")
    void readsTheCardsWording(String content) {
        assertThat(TransferReference.read(content)).containsExactly(new TransferReference(List.of(CONTRACT), 2));
    }

    @Test
    @DisplayName("every separator dropped: each split of the sequence and the contract's count is a candidate")
    void separatorsDroppedGiveCandidates() {
        assertThat(TransferReference.read("CK HD202611080000071DOT2"))
                .as("a six-digit sequence leaves one split").containsExactly(new TransferReference(List.of(CONTRACT), 2));
        List<TransferReference> read = TransferReference.read("CK HD2026110800000712DOT2");
        assertThat(read).hasSize(1);
        assertThat(read.getFirst().seq()).isEqualTo(2);
        assertThat(read.getFirst().contractNumbers()).as("the installments that exist decide which was meant")
                .containsExactly("HD-20261108-000007-12", "HD-20261108-0000071-2");
        assertThat(TransferReference.read("HD2026110800000710DOT2").getFirst().contractNumbers())
                .as("a contract's count never starts with 0").containsExactly("HD-20261108-000007-10");
    }

    @Test
    @DisplayName("a sequence longer than six digits and a contract drawn up a second time")
    void longSequenceSecondContract() {
        assertThat(TransferReference.read("HD-20261108-1600017-2 DOT10"))
                .containsExactly(new TransferReference(List.of("HD-20261108-1600017-2"), 10));
    }

    @Test
    @DisplayName("a fee item is FEE<n>: seq 21 and on, never one of the plan's installments")
    void feeItem() {
        assertThat(TransferReference.read("HD-20261108-000007-1 FEE1"))
                .containsExactly(new TransferReference(List.of(CONTRACT), Installment.FIRST_FEE_SEQ));
        assertThat(TransferReference.read("HD-20261108-000007-1 DOT21")).as("a plan has at most 20 rows").isEmpty();
    }

    @Test
    @DisplayName("two installments named: both read, in order; the same one twice is one")
    void severalAndRepeats() {
        assertThat(TransferReference.read("HD-20261108-000007-1 DOT1 HD-20261108-000007-1 DOT2"))
                .extracting(TransferReference::seq).containsExactly(1, 2);
        assertThat(TransferReference.read("HD-20261108-000007-1 DOT1 / HD-20261108-000007-1 DOT1")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "chuyen tien hang", "HD-20261108-000007-1", "DOT2", "HD-2026110-000007-1 DOT2",
            "HD-20261108-000007-1 DOT0", "HD-20261108-000007-1 DOT123"})
    @DisplayName("content that names no installment reads as none")
    void namesNothing(String content) {
        assertThat(TransferReference.read(content)).isEmpty();
        assertThat(TransferReference.read(null)).isEmpty();
    }
}
