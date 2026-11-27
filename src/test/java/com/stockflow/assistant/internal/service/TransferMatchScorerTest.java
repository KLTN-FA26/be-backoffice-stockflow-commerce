package com.stockflow.assistant.internal.service;

import com.stockflow.payment.api.BankTransferReview;
import com.stockflow.payment.api.PayableInstallment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The match suggestions' reading of real-looking Vietnamese transfer contents (work features 2.1): what each signal
 * finds and what it must not, and how the weights rank installments.
 */
class TransferMatchScorerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);
    private static final UUID ABC = UUID.randomUUID();
    private static final UUID XYZ = UUID.randomUUID();
    private static final Map<UUID, TransferMatchScorer.Payer> PAYERS = Map.of(
            ABC, new TransferMatchScorer.Payer("Công ty Cổ phần ABC", "0312345678"),
            XYZ, new TransferMatchScorer.Payer("Công ty TNHH Bao Bì Minh Phát", "0109876543-001"));

    private static PayableInstallment installment(UUID customer, String orderNumber, int count, int seq,
                                                  long outstanding, LocalDate due, int daysOverdue) {
        String contract = "HD-" + orderNumber.substring(3) + "-" + count;
        return new PayableInstallment(UUID.randomUUID(), customer, UUID.randomUUID(), contract, UUID.randomUUID(),
                orderNumber, seq, "Đợt " + seq, "AFTER_DELIVERY", false, BigDecimal.valueOf(outstanding),
                BigDecimal.valueOf(outstanding), "VND", due, due == null ? "NOT_DUE" : "DUE", daysOverdue > 0,
                daysOverdue, contract + " DOT" + seq);
    }

    private static BankTransferReview.Candidate candidate(PayableInstallment installment) {
        return new BankTransferReview.Candidate(installment, false);
    }

    private static List<TransferMatchScorer.Suggestion> suggest(long amount, String content,
                                                                BankTransferReview.Candidate... candidates) {
        return TransferMatchScorer.suggest(BigDecimal.valueOf(amount), content, List.of(candidates), PAYERS, TODAY);
    }

    @Nested
    @DisplayName("the content as banks hand it back")
    class Reading {

        @Test
        @DisplayName("accents, đ and case go, every run of other characters is one space")
        void spaced() {
            assertThat(TransferText.of("Công ty ABC ck HD-20261120-900001 đợt 2").spaced())
                    .isEqualTo("CONG TY ABC CK HD 20261120 900001 DOT 2");
            assertThat(TransferText.of("CT DEN:123 MBVCB.456 THANH TOAN DON SO20261117 8476387").spaced())
                    .isEqualTo("CT DEN 123 MBVCB 456 THANH TOAN DON SO20261117 8476387");
            assertThat(TransferText.of(null).spaced()).isEmpty();
        }

        @ParameterizedTest(name = "\"{0}\" names {1}: {2}")
        @CsvSource(delimiter = '|', value = {
                "THANH TOAN DON SO-20261117-000123 | SO-20261117-000123 | true",
                "thanh toan don so20261117000123 | SO-20261117-000123 | true",
                "tt don so 20261117 000123 ck | SO-20261117-000123 | true",
                "IBFT 20261117000123 TT | SO-20261117-000123 | true",
                "CT DEN:123 MBVCB.456 THANH TOAN DON SO20261117 8476387 | SO-20261117-847638 | true",
                "CT DEN:123 MBVCB.456 THANH TOAN DON SO20261117 8476387 | SO-20261117-000123 | false",
                "REF 120261117000123 | SO-20261117-000123 | false",
                "REF 202611170001234 | SO-20261117-000123 | false",
                "HD-20261117-000123-1 DOT2 | SO-20261117-000123 | true",
                "thanh toan tien hang | SO-20261117-000123 | false"})
        void order(String content, String order, boolean named) {
            assertThat(TransferText.of(content).namesOrder(order)).isEqualTo(named);
        }

        @ParameterizedTest(name = "\"{0}\" names {1}: {2}")
        @CsvSource(delimiter = '|', value = {
                "HD-20261117-000123-1 DOT2 | HD-20261117-000123-1 | true",
                "hd 20261117 000123 1 dot 2 | HD-20261117-000123-1 | true",
                "MBVCB.1234.hd202611170001231dot2.CT tu ABC | HD-20261117-000123-1 | true",
                "HD-20261117-000123-12 DOT1 | HD-20261117-000123-1 | false",
                "HD-20261117-000123-12 DOT1 | HD-20261117-000123-12 | true",
                "CONG TY ABC CK HD-20261120-900001 DOT 2 | HD-20261120-900001-1 | false",
                "SO-20261117-000123 | HD-20261117-000123-1 | false"})
        void contract(String content, String contract, boolean named) {
            assertThat(TransferText.of(content).namesContract(contract)).isEqualTo(named);
        }

        @ParameterizedTest(name = "\"{0}\" says {1}: {2}")
        @CsvSource(delimiter = '|', value = {
                "HD-20261117-000123-1 DOT2 | HD-20261117-000123-1 DOT2 | true",
                "CONG TY ABC CK HD-20261120-900001 DOT 2 | HD-20261120-900001-1 DOT2 | true",
                "thanh toan đợt 02 don so20261117000123 | HD-20261117-000123-1 DOT2 | true",
                "HD-20261117-000123-1 DOT12 | HD-20261117-000123-1 DOT1 | false",
                "HD-20261117-000123-1 DOT2 | HD-20261117-000123-1 DOT1 | false",
                "HD-20261117-000123-1 FEE1 | HD-20261117-000123-1 FEE1 | true",
                "HD-20261117-000123-1 FEE1 | HD-20261117-000123-1 DOT1 | false",
                "HD202611170001231DOT2 | HD-20261117-000123-1 DOT2 | true"})
        void place(String content, String reference, boolean named) {
            assertThat(TransferText.of(content).namesInstallment(reference)).isEqualTo(named);
        }

        @ParameterizedTest(name = "\"{0}\" names {1}: {2}")
        @CsvSource(delimiter = '|', value = {
                "CONG TY ABC CK HD-20261120-900001 DOT 2 | Công ty Cổ phần ABC | true",
                "CTY BAO BI MINH PHAT CHUYEN TIEN | Công ty TNHH Bao Bì Minh Phát | true",
                "cong ty tnhh bao bi minh phat ck | CÔNG TY TNHH BAO BÌ MINH PHÁT | true",
                "CONGTYBAOBIMINHPHAT CK | Công ty TNHH Bao Bì Minh Phát | true",
                "BAO BI MINH ANH CK | Công ty TNHH Bao Bì Minh Phát | false",
                "THANH TOAN DON HANG | Cửa hàng Hàng | false",
                "CONG TY TNHH TM DV CK TIEN | Công ty TNHH Thương mại Dịch vụ | false",
                "CK AB | Công ty AB | false",
                "TT ABCD | Công ty TNHH ABC | false"})
        void customer(String content, String name, boolean named) {
            assertThat(TransferText.of(content).namesCustomer(name)).isEqualTo(named);
        }

        @ParameterizedTest(name = "\"{0}\" holds {1}: {2}")
        @CsvSource(delimiter = '|', value = {
                "MST 0312345678 TT HD | 0312345678 | true",
                "CK TU 0109876543 001 | 0109876543-001 | true",
                "CK TU 0109876543-001 | 0109876543 | true",
                "REF 103123456789 | 0312345678 | false",
                "SO TIEN 03123456789 | 0312345678 | false"})
        void taxCode(String content, String taxCode, boolean named) {
            assertThat(TransferText.of(content).namesTaxCode(taxCode)).isEqualTo(named);
        }

        @Test
        @DisplayName("a name's legal form is not what tells customers apart")
        void distinctive() {
            assertThat(TransferText.distinctive("Công ty TNHH Một Thành Viên Thương Mại Dịch Vụ Minh Phát"))
                    .containsExactly("MINH", "PHAT");
            assertThat(TransferText.distinctive("CÔNG TY CỔ PHẦN ĐẦU TƯ ABC VIỆT NAM"))
                    .containsExactly("ABC", "VIET", "NAM");
            assertThat(TransferText.distinctive(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("scoring and ranking")
    class Scoring {

        @Test
        @DisplayName("an installment the bank feed's own reading names, owing exactly the amount, scores the most")
        void namedAndExact() {
            PayableInstallment named = installment(ABC, "SO-20261117-000123", 1, 2, 50_000_000, TODAY.plusDays(3), 0);
            PayableInstallment same = installment(XYZ, "SO-20261110-000045", 1, 1, 50_000_000, null, 0);

            List<TransferMatchScorer.Suggestion> suggestions = suggest(50_000_000, "HD-20261117-000123-1 DOT2",
                    new BankTransferReview.Candidate(named, true), candidate(same));

            assertThat(suggestions).extracting(s -> s.installment().id()).containsExactly(named.id(), same.id());
            assertThat(suggestions.getFirst().score()).isEqualTo(95);
            assertThat(suggestions.getFirst().reasons()).containsExactly(
                    TransferMatchScorer.Reason.CONTRACT_NUMBER_IN_CONTENT, TransferMatchScorer.Reason.AMOUNT_EXACT,
                    TransferMatchScorer.Reason.DUE_SOON);
            assertThat(suggestions.get(1).score()).isEqualTo(TransferMatchScorer.FLOOR);
            assertThat(suggestions.get(1).reasons()).containsExactly(TransferMatchScorer.Reason.AMOUNT_EXACT);
        }

        @Test
        @DisplayName("\"CONG TY ABC CK HD-20261120-900001 DOT 2\": the contract's count dropped, so the feed read "
                + "nothing; the order, DOT 2 and the name point at ABC's second installment")
        void mangledContract() {
            PayableInstallment second = installment(ABC, "SO-20261120-900001", 1, 2, 30_000_000, TODAY.minusDays(4),
                    4);
            PayableInstallment third = installment(ABC, "SO-20261120-900001", 1, 3, 30_000_000, null, 0);
            PayableInstallment otherCustomer = installment(XYZ, "SO-20261101-000777", 1, 2, 30_000_000,
                    TODAY.plusDays(2), 0);

            List<TransferMatchScorer.Suggestion> suggestions = suggest(30_000_000,
                    "CONG TY ABC CK HD-20261120-900001 DOT 2", candidate(otherCustomer), candidate(third),
                    candidate(second));

            assertThat(suggestions).extracting(s -> s.installment().id())
                    .containsExactly(second.id(), third.id(), otherCustomer.id());
            assertThat(suggestions.getFirst().score()).isEqualTo(100);
            assertThat(suggestions.getFirst().reasons()).containsExactly(
                    TransferMatchScorer.Reason.ORDER_NUMBER_IN_CONTENT,
                    TransferMatchScorer.Reason.INSTALLMENT_IN_CONTENT,
                    TransferMatchScorer.Reason.CUSTOMER_NAME_IN_CONTENT, TransferMatchScorer.Reason.AMOUNT_EXACT,
                    TransferMatchScorer.Reason.OVERDUE);
            assertThat(suggestions.get(1).score()).isEqualTo(40 + 25 + 30);
            assertThat(suggestions.get(2).score()).isEqualTo(30 + 5);
        }

        @Test
        @DisplayName("\"CT DEN:123 MBVCB.456 THANH TOAN DON SO20261117 8476387\": the order with a bank's digits "
                + "glued on, paid in part")
        void gluedOrderNumber() {
            PayableInstallment order = installment(ABC, "SO-20261117-847638", 1, 1, 120_000_000, TODAY, 0);

            List<TransferMatchScorer.Suggestion> suggestions = suggest(60_000_000,
                    "CT DEN:123 MBVCB.456 THANH TOAN DON SO20261117 8476387", candidate(order));

            assertThat(suggestions).singleElement().satisfies(s -> {
                assertThat(s.score()).isEqualTo(40 + 10 + 5);
                assertThat(s.reasons()).containsExactly(TransferMatchScorer.Reason.ORDER_NUMBER_IN_CONTENT,
                        TransferMatchScorer.Reason.AMOUNT_WITHIN_DUE, TransferMatchScorer.Reason.DUE_SOON);
            });
        }

        @Test
        @DisplayName("the payer's tax code points at its installments; the amount picks among them")
        void taxCode() {
            PayableInstallment exact = installment(XYZ, "SO-20261020-000310", 1, 1, 18_500_000, TODAY.minusDays(40),
                    40);
            PayableInstallment larger = installment(XYZ, "SO-20261102-000020", 1, 2, 90_000_000, null, 0);

            List<TransferMatchScorer.Suggestion> suggestions = suggest(18_500_000, "MST 0109876543 THANH TOAN CONG NO",
                    candidate(larger), candidate(exact));

            assertThat(suggestions).extracting(TransferMatchScorer.Suggestion::score).containsExactly(35 + 30 + 5,
                    35 + 10);
            assertThat(suggestions.getFirst().installment().id()).isEqualTo(exact.id());
            assertThat(suggestions.getFirst().reasons()).contains(TransferMatchScorer.Reason.TAX_CODE_IN_CONTENT);
        }

        @Test
        @DisplayName("a name alone, or a part payment of an overdue installment, is no lead: nothing reaches the "
                + "floor, the list is empty; the name with a due day is one")
        void belowTheFloor() {
            PayableInstallment notDue = installment(ABC, "SO-20261117-000123", 1, 1, 80_000_000, null, 0);
            PayableInstallment due = installment(ABC, "SO-20261117-000124", 1, 1, 80_000_000, TODAY.minusDays(1), 1);

            assertThat(suggest(90_000_000, "CONG TY ABC CHUYEN TIEN", candidate(notDue))).isEmpty();
            assertThat(suggest(10_000_000, "thanh toan tien hang", candidate(due))).isEmpty();
            assertThat(suggest(10_000_000, "CONG TY ABC CHUYEN TIEN", candidate(due))).singleElement()
                    .satisfies(s -> assertThat(s.score()).isEqualTo(25 + 10 + 5));
        }

        @Test
        @DisplayName("a transfer larger than what an installment owes says so, and weighs less: a receipt cannot take "
                + "it as it is (15 BR-05)")
        void overpayment() {
            PayableInstallment small = installment(ABC, "SO-20261117-000123", 1, 1, 5_000_000, null, 0);

            List<TransferMatchScorer.Suggestion> suggestions = suggest(6_000_000, "TT DON SO-20261117-000123",
                    candidate(small));

            assertThat(suggestions).singleElement().satisfies(s -> {
                assertThat(s.score()).isEqualTo(40 - 10);
                assertThat(s.reasons()).containsExactly(TransferMatchScorer.Reason.ORDER_NUMBER_IN_CONTENT,
                        TransferMatchScorer.Reason.AMOUNT_ABOVE_DUE);
            });
        }

        @Test
        @DisplayName("\"HD-...-1 DOT2\" for more than DOT2 owes - the feed's OVERPAYMENT - is still the lead, and says "
                + "the amount is too high")
        void namedButTooMuch() {
            PayableInstallment second = installment(ABC, "SO-20261117-000123", 1, 2, 20_000_000, TODAY.plusDays(2), 0);

            List<TransferMatchScorer.Suggestion> suggestions = suggest(25_000_000, "HD-20261117-000123-1 DOT2",
                    new BankTransferReview.Candidate(second, true));

            assertThat(suggestions).singleElement().satisfies(s -> {
                assertThat(s.score()).isEqualTo(60 - 10 + 5);
                assertThat(s.reasons()).contains(TransferMatchScorer.Reason.AMOUNT_ABOVE_DUE)
                        .doesNotContain(TransferMatchScorer.Reason.AMOUNT_EXACT,
                                TransferMatchScorer.Reason.AMOUNT_WITHIN_DUE);
            });
        }

        @Test
        @DisplayName("two customers' installments owing the transfer's amount, nothing else said: a tie, flagged "
                + "ambiguous; a clear best is not")
        void tieIsAmbiguous() {
            PayableInstallment abc = installment(ABC, "SO-20261001-000001", 1, 1, 12_000_000, null, 0);
            PayableInstallment xyz = installment(XYZ, "SO-20261002-000002", 1, 1, 12_000_000, null, 0);

            List<TransferMatchScorer.Suggestion> tie = suggest(12_000_000, "CK", candidate(abc), candidate(xyz));
            List<TransferMatchScorer.Suggestion> clear = suggest(12_000_000, "MST 0312345678 CK", candidate(abc),
                    candidate(xyz));

            assertThat(tie).extracting(TransferMatchScorer.Suggestion::score).containsExactly(30, 30);
            assertThat(TransferMatchScorer.ambiguous(tie)).isTrue();
            assertThat(clear.getFirst().installment().id()).isEqualTo(abc.id());
            assertThat(TransferMatchScorer.ambiguous(clear)).isFalse();
            assertThat(TransferMatchScorer.ambiguous(clear.subList(0, 1))).isFalse();
        }

        @Test
        @DisplayName("at most three, best first; a tie goes to the more overdue")
        void atMostThree() {
            PayableInstallment a = installment(ABC, "SO-20261001-000001", 1, 1, 7_000_000, TODAY.minusDays(2), 2);
            PayableInstallment b = installment(ABC, "SO-20261002-000002", 1, 1, 7_000_000, TODAY.minusDays(20), 20);
            PayableInstallment c = installment(XYZ, "SO-20261003-000003", 1, 1, 7_000_000, null, 0);
            PayableInstallment d = installment(XYZ, "SO-20261004-000004", 1, 1, 7_000_000, null, 0);

            List<TransferMatchScorer.Suggestion> suggestions = suggest(7_000_000, "CK", candidate(c), candidate(a),
                    candidate(d), candidate(b));

            assertThat(suggestions).hasSize(TransferMatchScorer.MAX_SUGGESTIONS);
            assertThat(suggestions).extracting(s -> s.installment().id()).startsWith(b.id(), a.id());
        }

        @Test
        @DisplayName("DOT n alone, without an order or contract number, names nobody's installment")
        void placeAloneIsNothing() {
            PayableInstallment second = installment(ABC, "SO-20261117-000123", 1, 2, 9_000_000, null, 0);

            assertThat(TransferMatchScorer.score(BigDecimal.valueOf(1_000_000), TransferText.of("THANH TOAN DOT 2"),
                    candidate(second), PAYERS.get(ABC), TODAY).reasons())
                    .containsExactly(TransferMatchScorer.Reason.AMOUNT_WITHIN_DUE);
        }

        @Test
        @DisplayName("a customer whose name and tax code cannot be read is never named in the content")
        void unknownPayer() {
            PayableInstallment i = installment(UUID.randomUUID(), "SO-20261117-000123", 1, 1, 9_000_000, null, 0);

            assertThat(TransferMatchScorer.score(BigDecimal.valueOf(9_000_000), TransferText.of("CONG TY ABC"),
                    candidate(i), null, TODAY).reasons()).containsExactly(TransferMatchScorer.Reason.AMOUNT_EXACT);
        }
    }
}
