package com.stockflow.invoicing.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.stockflow.invoicing.internal.domain.EInvoiceFixtures.BUYER;
import static com.stockflow.invoicing.internal.domain.EInvoiceFixtures.NOW;
import static com.stockflow.invoicing.internal.domain.EInvoiceFixtures.SERIES;
import static com.stockflow.invoicing.internal.domain.EInvoiceStatus.DRAFT;
import static com.stockflow.invoicing.internal.domain.EInvoiceStatus.ISSUED;
import static com.stockflow.invoicing.internal.domain.EInvoiceStatus.REJECTED;
import static com.stockflow.invoicing.internal.domain.EInvoiceStatus.REPLACED;
import static com.stockflow.invoicing.internal.domain.EInvoiceStatus.SUBMITTING;
import static com.stockflow.invoicing.internal.domain.EInvoiceStatus.VOIDED_DRAFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The e-invoice's rules on the aggregate (plan §1.4, §1.5; kltn-docs 21 §5.1, BR-03, BR-04, BR-06): what a draft is
 * drawn up with, every edge of its lifecycle and every edge that is not one, and that an issued invoice is never
 * cancelled or changed.
 */
class EInvoiceTest {

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Nested
    @DisplayName("drawing up")
    class DrawingUp {

        @Test
        @DisplayName("a draft: dated the day of delivery, due the next working day, totals added up from its lines")
        void aDraftOfADelivery() {
            EInvoice.Snapshot s = EInvoiceFixtures.draft().snapshot();

            assertThat(s.type()).isEqualTo(EInvoiceType.ORIGINAL);
            assertThat(s.status()).isEqualTo(DRAFT);
            assertThat(s.issueDate()).isEqualTo(EInvoiceFixtures.DELIVERED_ON);
            assertThat(s.issueDueAt()).as("Thursday 12/11 -> the end of Friday 13/11, Vietnam time")
                    .isEqualTo(Instant.parse("2026-11-13T17:00:00Z"));
            // 20 cartons x 100,000 at 10% + a plate fee of 500,000 at 8%
            assertThat(s.subtotal()).isEqualByComparingTo("2500000");
            assertThat(s.taxTotal()).isEqualByComparingTo("240000");
            assertThat(s.grandTotal()).isEqualByComparingTo("2740000");
            assertThat(s.amountInWords()).isEqualTo("Hai triệu bảy trăm bốn mươi nghìn đồng");
            assertThat(s.currency()).isEqualTo("VND");
            assertThat(s.paymentMethod()).isEqualTo("BANK_TRANSFER");
            assertThat(s.warnings()).isEmpty();
            assertThat(s.attempt()).isZero();
            assertThat(s.transactionId()).as("nothing was sent").isNull();
            assertThat(s.invoiceNumber()).isNull();
        }

        @Test
        @DisplayName("the tax is stated by rate, lowest first, and adds up to the invoice's tax")
        void totalsByRate() {
            List<TaxRateTotal> totals = EInvoiceFixtures.draft().totalsByRate();
            assertThat(totals).extracting(t -> t.taxRate().toPlainString(), t -> t.amount().toPlainString(),
                    t -> t.taxAmount().toPlainString()).containsExactly(
                    org.assertj.core.groups.Tuple.tuple("8.00", "500000", "40000"),
                    org.assertj.core.groups.Tuple.tuple("10.00", "2000000", "200000"));
        }

        @Test
        @DisplayName("a buyer with something missing still gets its draft, flagged; a rate off the contract's is flagged")
        void warnings() {
            EInvoice noEmail = EInvoiceFixtures.draft(new InvoiceParty("Cong ty", "0398765432", "1 Le Loi", " "));
            assertThat(noEmail.warnings()).containsExactly(EInvoice.Warning.BUYER_PROFILE_INCOMPLETE);

            EInvoice otherRate = EInvoice.drawUpOriginal(UUID.randomUUID(), "EINV-20261112-0002",
                    EInvoiceFixtures.delivery(), EInvoiceFixtures.SELLER, BUYER, EInvoiceFixtures.lines(), true, false);
            assertThat(otherRate.warnings()).containsExactly(EInvoice.Warning.TAX_RATE_DIFFERS_FROM_CONTRACT);

            // A line whose order fixed no VAT rate: the customer agreed to none, so the draft is never issued unseen.
            EInvoice noRate = EInvoice.drawUpOriginal(UUID.randomUUID(), "EINV-20261112-0004",
                    EInvoiceFixtures.delivery(), EInvoiceFixtures.SELLER, BUYER, EInvoiceFixtures.lines(), false, true);
            assertThat(noRate.warnings()).containsExactly(EInvoice.Warning.TAX_RATE_NOT_ON_CONTRACT);
            noRate.reviseBuyer(BUYER);
            assertThat(noRate.warnings()).as("correcting the buyer does not settle it")
                    .containsExactly(EInvoice.Warning.TAX_RATE_NOT_ON_CONTRACT);
        }

        @Test
        @DisplayName("refused: no seller, no lines, two lines with one number, a line below zero, no shipment")
        void refusals() {
            var delivery = EInvoiceFixtures.delivery();
            UUID id = UUID.randomUUID();
            assertThatThrownBy(() -> EInvoice.drawUpOriginal(id, "E-1", delivery,
                    new InvoiceParty("Seller", null, "1 Le Loi", null), BUYER, EInvoiceFixtures.lines(), false, false))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> EInvoice.drawUpOriginal(id, "E-1", delivery, EInvoiceFixtures.SELLER, BUYER,
                    List.of(), false, false)).isInstanceOf(IllegalArgumentException.class);
            EInvoiceLine one = EInvoiceFixtures.lines().getFirst();
            assertThatThrownBy(() -> EInvoice.drawUpOriginal(id, "E-1", delivery, EInvoiceFixtures.SELLER, BUYER,
                    List.of(one, one), false, false)).isInstanceOf(IllegalArgumentException.class);
            EInvoiceLine below = EInvoiceLine.goods(UUID.randomUUID(), 1, UUID.randomUUID(), "SKU", "Ly", "Thung", -1,
                    50, BigDecimal.TEN, BigDecimal.TEN);
            assertThatThrownBy(() -> EInvoice.drawUpOriginal(id, "E-1", delivery, EInvoiceFixtures.SELLER, BUYER,
                    List.of(below), false, false)).isInstanceOf(IllegalArgumentException.class);
            var noShipment = new EInvoice.Delivery(delivery.orderId(), delivery.orderNumber(), null, null, null, null,
                    delivery.customerId(), delivery.deliveredOn());
            assertThatThrownBy(() -> EInvoice.drawUpOriginal(id, "E-1", noShipment, EInvoiceFixtures.SELLER, BUYER,
                    EInvoiceFixtures.lines(), false, false)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        /** Every edge of the lifecycle, and nothing else: 6 of the 36 pairs. */
        private static final Map<EInvoiceStatus, Set<EInvoiceStatus>> EDGES = Map.of(
                DRAFT, EnumSet.of(SUBMITTING, VOIDED_DRAFT),
                SUBMITTING, EnumSet.of(ISSUED, REJECTED),
                REJECTED, EnumSet.of(DRAFT),
                ISSUED, EnumSet.of(REPLACED),
                VOIDED_DRAFT, EnumSet.noneOf(EInvoiceStatus.class),
                REPLACED, EnumSet.noneOf(EInvoiceStatus.class));

        static Stream<Arguments> everyPair() {
            return Stream.of(EInvoiceStatus.values()).flatMap(from -> Stream.of(EInvoiceStatus.values())
                    .map(to -> Arguments.of(from, to, EDGES.get(from).contains(to))));
        }

        @ParameterizedTest(name = "{0} -> {1}: {2}")
        @MethodSource("everyPair")
        @DisplayName("the status allows exactly the lifecycle's edges")
        void theStatusAllowsExactlyTheEdges(EInvoiceStatus from, EInvoiceStatus to, boolean allowed) {
            assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
        }

        /** The operations that move an invoice, each with the one status it starts from and where it leads. */
        private record Move(String name, EInvoiceStatus from, EInvoiceStatus to, Consumer<EInvoice> action) {
            @Override
            public String toString() {
                return name;
            }
        }

        private static final List<Move> MOVES = List.of(
                new Move("submit", DRAFT, SUBMITTING, invoice -> invoice.submit(SERIES, null, NOW)),
                new Move("void", DRAFT, VOIDED_DRAFT, invoice -> invoice.voidDraft("mistake", null, NOW)),
                new Move("issued", SUBMITTING, ISSUED, invoice -> invoice.issued(7, "SBX1", "SBXL", NOW,
                        "einvoices/2026/11/12/b.xml", "einvoices/2026/11/12/b.pdf", NOW)),
                new Move("rejected", SUBMITTING, REJECTED, invoice -> invoice.rejected("CODE", "why")),
                new Move("reopen", REJECTED, DRAFT, EInvoice::reopen));

        static Stream<Arguments> everyMoveFromEveryStatus() {
            return MOVES.stream().flatMap(move -> Stream.of(EInvoiceStatus.values())
                    .map(from -> Arguments.of(move, from)));
        }

        @ParameterizedTest(name = "{0} from {1}")
        @MethodSource("everyMoveFromEveryStatus")
        @DisplayName("each operation moves the invoice from its one status, and is refused from every other")
        void eachOperationFromEveryStatus(Move move, EInvoiceStatus from) {
            EInvoice invoice = EInvoiceFixtures.in(from);
            if (from == move.from()) {
                move.action().accept(invoice);
                assertThat(invoice.status()).isEqualTo(move.to());
            } else {
                assertThatThrownBy(() -> move.action().accept(invoice))
                        .extracting(EInvoiceTest::code).isEqualTo(ErrorCode.EINVOICE_INVALID_TRANSITION);
                assertThat(invoice.status()).as("left as it was").isEqualTo(from);
            }
        }

        @Test
        @DisplayName("an issued invoice is never voided, reopened, rejected or sent again: it has no way out here")
        void anIssuedInvoiceIsNeverCancelled() {
            EInvoice issued = EInvoiceFixtures.in(ISSUED);
            EInvoice.Snapshot before = issued.snapshot();
            for (Move move : MOVES) {
                assertThatThrownBy(() -> move.action().accept(issued))
                        .extracting(EInvoiceTest::code).isEqualTo(ErrorCode.EINVOICE_INVALID_TRANSITION);
            }
            assertThat(issued.snapshot()).as("nothing about it moved").isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("issuing")
    class Issuing {

        @Test
        @DisplayName("submit gives a transaction id - the invoice's id and its attempt - the series and who issued")
        void submit() {
            EInvoice invoice = EInvoiceFixtures.draft();
            UUID accountant = UUID.randomUUID();
            invoice.submit(SERIES, accountant, NOW);

            EInvoice.Snapshot s = invoice.snapshot();
            assertThat(s.status()).isEqualTo(SUBMITTING);
            assertThat(s.attempt()).isEqualTo(1);
            assertThat(s.transactionId()).isEqualTo(s.id() + "-1");
            assertThat(s.provider()).isEqualTo("SANDBOX");
            assertThat(s.templateCode()).isEqualTo("1");
            assertThat(s.symbol()).isEqualTo("C26TSB");
            assertThat(s.submittedBy()).isEqualTo(accountant);
            assertThat(s.submittedAt()).isEqualTo(NOW);
        }

        @ParameterizedTest(name = "buyer without {0}")
        @EnumSource(Missing.class)
        @DisplayName("21 BR-06: a buyer without its name, tax code, address or invoice e-mail is not issued")
        void anIncompleteBuyerIsNotIssued(Missing missing) {
            EInvoice invoice = EInvoiceFixtures.draft(missing.buyer());
            assertThatThrownBy(() -> invoice.submit(SERIES, null, NOW))
                    .extracting(EInvoiceTest::code).isEqualTo(ErrorCode.EINVOICE_BUYER_PROFILE_INCOMPLETE);
            assertThat(invoice.status()).as("still a draft, nothing sent").isEqualTo(DRAFT);
            assertThat(invoice.snapshot().transactionId()).isNull();
            assertThat(invoice.snapshot().attempt()).isZero();
        }

        enum Missing {
            TAX_CODE(new InvoiceParty(BUYER.name(), null, BUYER.address(), BUYER.email())),
            ADDRESS(new InvoiceParty(BUYER.name(), BUYER.taxCode(), "  ", BUYER.email())),
            EMAIL(new InvoiceParty(BUYER.name(), BUYER.taxCode(), BUYER.address(), null));

            private final InvoiceParty buyer;

            Missing(InvoiceParty buyer) {
                this.buyer = buyer;
            }

            InvoiceParty buyer() {
                return buyer;
            }
        }

        @Test
        @DisplayName("a series of another year than the invoice's date is refused: a symbol names its year")
        void aSeriesOfAnotherYear() {
            EInvoice invoice = EInvoiceFixtures.draft();
            assertThatThrownBy(() -> invoice.submit(new InvoiceSeries("SANDBOX", "1", "C27TSB", 2027), null, NOW))
                    .extracting(EInvoiceTest::code).isEqualTo(ErrorCode.EINVOICE_SERIES_NOT_CONFIGURED);
            assertThat(invoice.status()).isEqualTo(DRAFT);
        }

        @Test
        @DisplayName("issued: the provider's number, the tax authority's code, the files; whatever was flagged is settled")
        void issued() {
            EInvoice invoice = EInvoice.drawUpOriginal(UUID.randomUUID(), "EINV-20261112-0003",
                    EInvoiceFixtures.delivery(), EInvoiceFixtures.SELLER, BUYER, EInvoiceFixtures.lines(), true, false);
            invoice.submit(SERIES, null, NOW);
            invoice.issued(12345678, "SBXCODE", "SBXLOOK", NOW.plusSeconds(5), "einvoices/2026/11/12/x.xml",
                    "einvoices/2026/11/12/x.pdf", NOW.plusSeconds(6));

            EInvoice.Snapshot s = invoice.snapshot();
            assertThat(s.status()).isEqualTo(ISSUED);
            assertThat(s.status().wasIssued()).isTrue();
            assertThat(s.invoiceNumber()).isEqualTo(12345678);
            assertThat(s.taxAuthorityCode()).isEqualTo("SBXCODE");
            assertThat(s.lookupCode()).isEqualTo("SBXLOOK");
            assertThat(s.signedAt()).isEqualTo(NOW.plusSeconds(5));
            assertThat(s.issuedAt()).isEqualTo(NOW.plusSeconds(6));
            assertThat(s.xmlFileKey()).endsWith(".xml");
            assertThat(s.pdfFileKey()).endsWith(".pdf");
            assertThat(s.warnings()).isEmpty();
        }

        @Test
        @DisplayName("NĐ 254 Phụ lục điểm 2: a number is 1 to 99,999,999")
        void theNumberHasAtMostEightDigits() {
            for (int wrong : new int[] {0, -1, 100_000_000}) {
                EInvoice invoice = EInvoiceFixtures.in(SUBMITTING);
                assertThatThrownBy(() -> invoice.issued(wrong, "C", "L", NOW, "a.xml", "a.pdf", NOW))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            EInvoice last = EInvoiceFixtures.in(SUBMITTING);
            last.issued(99_999_999, "C", "L", NOW, "a.xml", "a.pdf", NOW);
            assertThat(last.snapshot().invoiceNumber()).isEqualTo(99_999_999);
        }

        @Test
        @DisplayName("21 BR-04: a rejected invoice issued again is a new transaction; the reason stays until then")
        void aNewTransactionAfterARejection() {
            EInvoice invoice = EInvoiceFixtures.in(REJECTED);
            String first = invoice.transactionId();
            assertThat(invoice.snapshot().rejectionCode()).isEqualTo("INVALID_BUYER_TAX_CODE");

            invoice.reopen();
            assertThat(invoice.snapshot().rejectionCode()).as("what was refused is still shown on the draft")
                    .isEqualTo("INVALID_BUYER_TAX_CODE");
            assertThat(invoice.transactionId()).as("no new transaction until it is sent again").isEqualTo(first);

            invoice.submit(SERIES, null, NOW);
            assertThat(invoice.transactionId()).isEqualTo(invoice.id() + "-2").isNotEqualTo(first);
            assertThat(invoice.snapshot().rejectionCode()).isNull();
        }

        @Test
        @DisplayName("a rejection needs its code; a long message is cut to what is stored")
        void rejection() {
            EInvoice invoice = EInvoiceFixtures.in(SUBMITTING);
            assertThatThrownBy(() -> invoice.rejected(" ", "why")).isInstanceOf(IllegalArgumentException.class);
            EInvoice other = EInvoiceFixtures.in(SUBMITTING);
            other.rejected("CODE", "x".repeat(900));
            assertThat(other.snapshot().rejectionMessage()).hasSize(500);
        }
    }

    @Nested
    @DisplayName("a draft's corrections")
    class Corrections {

        @Test
        @DisplayName("the buyer is revised on a draft, and the flag follows what is missing")
        void reviseBuyer() {
            EInvoice invoice = EInvoiceFixtures.draft(new InvoiceParty("Cong ty", "0398765432", "1 Le Loi", null));
            assertThat(invoice.warnings()).contains(EInvoice.Warning.BUYER_PROFILE_INCOMPLETE);

            invoice.reviseBuyer(BUYER);
            assertThat(invoice.buyer()).isEqualTo(BUYER);
            assertThat(invoice.warnings()).isEmpty();

            invoice.reviseBuyer(new InvoiceParty("Cong ty", null, "1 Le Loi", "a@b.vn"));
            assertThat(invoice.warnings()).containsExactly(EInvoice.Warning.BUYER_PROFILE_INCOMPLETE);
            assertThatThrownBy(() -> invoice.reviseBuyer(new InvoiceParty(" ", "0398765432", "1 Le Loi", "a@b.vn")))
                    .extracting(EInvoiceTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("lines are renamed by id; money never changes; an unknown line or a blank name is refused")
        void renameLines() {
            EInvoice invoice = EInvoiceFixtures.draft();
            EInvoice.Snapshot before = invoice.snapshot();
            UUID first = before.lines().getFirst().id();

            invoice.renameLines(Map.of(first, "Ly giay 12oz in logo"));
            EInvoice.Snapshot after = invoice.snapshot();
            assertThat(after.lines().getFirst().itemName()).isEqualTo("Ly giay 12oz in logo");
            assertThat(after.lines().get(1)).isEqualTo(before.lines().get(1));
            assertThat(after.grandTotal()).isEqualByComparingTo(before.grandTotal());

            assertThatThrownBy(() -> invoice.renameLines(Map.of(UUID.randomUUID(), "x")))
                    .extracting(EInvoiceTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThatThrownBy(() -> invoice.renameLines(Map.of(first, " ")))
                    .extracting(EInvoiceTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
            invoice.renameLines(Map.of());
            invoice.renameLines(null);
        }

        @ParameterizedTest
        @EnumSource(value = EInvoiceStatus.class, names = "DRAFT", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("21 BR-03: only a draft is corrected - not one at the provider, issued, rejected, voided or replaced")
        void onlyADraftIsCorrected(EInvoiceStatus status) {
            EInvoice invoice = EInvoiceFixtures.in(status);
            EInvoice.Snapshot before = invoice.snapshot();
            assertThatThrownBy(() -> invoice.reviseBuyer(new InvoiceParty("Other", "0300000000", "x", "a@b.vn")))
                    .extracting(EInvoiceTest::code).isEqualTo(ErrorCode.EINVOICE_NOT_DRAFT);
            assertThatThrownBy(() -> invoice.renameLines(Map.of(before.lines().getFirst().id(), "x")))
                    .extracting(EInvoiceTest::code).isEqualTo(ErrorCode.EINVOICE_NOT_DRAFT);
            assertThat(invoice.snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("voiding a draft needs a reason and keeps who and when")
        void voidDraft() {
            EInvoice invoice = EInvoiceFixtures.draft();
            assertThatThrownBy(() -> invoice.voidDraft(" ", null, NOW))
                    .extracting(EInvoiceTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(invoice.status()).isEqualTo(DRAFT);

            UUID accountant = UUID.randomUUID();
            invoice.voidDraft("  the delivery was recorded on the wrong order  ", accountant, NOW);
            EInvoice.Snapshot s = invoice.snapshot();
            assertThat(s.status()).isEqualTo(VOIDED_DRAFT);
            assertThat(s.voidReason()).isEqualTo("the delivery was recorded on the wrong order");
            assertThat(s.voidedBy()).isEqualTo(accountant);
            assertThat(s.voidedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("the deadline")
    class Deadline {

        @Test
        @DisplayName("overdue once the next working day has ended, while it is still to issue; never once issued or voided")
        void overdue() {
            Instant due = Instant.parse("2026-11-13T17:00:00Z");
            EInvoice draft = EInvoiceFixtures.draft();
            assertThat(draft.overdue(due.minusSeconds(1))).isFalse();
            assertThat(draft.overdue(due)).isTrue();
            assertThat(EInvoiceFixtures.in(SUBMITTING).overdue(due)).as("sent, not yet coded").isTrue();
            assertThat(EInvoiceFixtures.in(REJECTED).overdue(due)).isTrue();
            assertThat(EInvoiceFixtures.in(ISSUED).overdue(due.plusSeconds(86_400))).isFalse();
            assertThat(EInvoiceFixtures.in(VOIDED_DRAFT).overdue(due.plusSeconds(86_400))).isFalse();
        }

        @Test
        @DisplayName("the accountant is told once")
        void warnedOnce() {
            EInvoice draft = EInvoiceFixtures.draft();
            draft.warnedOfDeadline(NOW);
            draft.warnedOfDeadline(NOW.plusSeconds(3600));
            assertThat(draft.snapshot().dueWarnedAt()).isEqualTo(NOW);
        }
    }

    @Test
    @DisplayName("a snapshot restores the same invoice")
    void snapshotRoundTrip() {
        EInvoice issued = EInvoiceFixtures.in(ISSUED);
        assertThat(EInvoice.restore(issued.snapshot()).snapshot()).isEqualTo(issued.snapshot());
    }
}
