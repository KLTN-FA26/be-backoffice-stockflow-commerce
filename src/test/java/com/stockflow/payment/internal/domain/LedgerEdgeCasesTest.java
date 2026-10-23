package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Edge cases of money arithmetic on the ledger aggregates (kltn-docs 15 BR-05, BR-06): zero, scale, no-ops. */
class LedgerEdgeCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private static CustomerTransfer transfer(String amount) {
        return CustomerTransfer.record(UUID.randomUUID(), "FT-1", new BigDecimal(amount), "VND", TODAY,
                UUID.randomUUID(), NOW, "  note  ");
    }

    private static Receivable receivable(String amount, LocalDate due) {
        return Receivable.open(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(amount), "VND", NOW, due);
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    @DisplayName("every internal payment method translates to the contract method of the same name")
    void paymentMethodToContract(PaymentMethod method) {
        assertThat(method.toContract().name()).isEqualTo(method.name());
    }

    @Test
    @DisplayName("take: never more than is held, never negative; a negative or zero ask takes nothing")
    void takeBounds() {
        CustomerTransfer t = transfer("100");
        assertThat(t.take(new BigDecimal("-5"))).isEqualByComparingTo("0");
        assertThat(t.take(BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(t.unallocatedAmount()).isEqualByComparingTo("100");
        assertThat(t.take(new BigDecimal("0.01"))).isEqualByComparingTo("0.01");
        assertThat(t.unallocatedAmount()).isEqualByComparingTo("99.99");
    }

    @Test
    @DisplayName("takeExactly(0) or a negative amount is VALIDATION_FAILED, not a free allocation")
    void takeExactlyNonPositive() {
        CustomerTransfer t = transfer("100");
        for (String bad : new String[] {"0", "-1"}) {
            assertThatThrownBy(() -> t.takeExactly(new BigDecimal(bad))).isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThat(t.unallocatedAmount()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("takeExactly of everything leaves zero; the transfer amount itself never changes")
    void amountIsImmutable() {
        CustomerTransfer t = transfer("100.50");
        t.takeExactly(new BigDecimal("100.50"));
        assertThat(t.unallocatedAmount()).isEqualByComparingTo("0");
        assertThat(t.amount()).isEqualByComparingTo("100.50");
    }

    @Test
    @DisplayName("a reversal can give back at most what was taken: the ledger identity amount = unallocated + refunded + allocated holds")
    void giveBackBounds() {
        CustomerTransfer t = transfer("100");
        t.takeExactly(new BigDecimal("60"));
        t.refundCredit(new BigDecimal("40"));
        assertThat(t.unallocatedAmount()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> t.giveBack(new BigDecimal("61"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> t.giveBack(BigDecimal.ZERO)).isInstanceOf(IllegalStateException.class);
        t.giveBack(new BigDecimal("60"));
        assertThat(t.unallocatedAmount()).isEqualByComparingTo("60");
        assertThat(t.unallocatedAmount().add(t.refundedAmount())).isEqualByComparingTo(t.amount());
    }

    @Test
    @DisplayName("a transfer trims its reference and note, a blank note is none, and a negative amount is refused")
    void constructionGuards() {
        CustomerTransfer t = transfer("1");
        assertThat(t.note()).isEqualTo("note");
        assertThat(CustomerTransfer.record(UUID.randomUUID(), "FT", BigDecimal.ONE, "VND", TODAY, UUID.randomUUID(), NOW,
                "  ").note()).isNull();
        assertThatThrownBy(() -> transfer("-1")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a bank transfer cannot carry a carrier name")
    void bankTransferWithCarrier() {
        assertThatThrownBy(() -> new CustomerTransfer(UUID.randomUUID(), UUID.randomUUID(), "FT", BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.ZERO, "VND", TODAY, UUID.randomUUID(), NOW, null,
                TransferChannel.BANK_TRANSFER, "GHN", 0L)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a receivable must be owed money: zero or negative is refused")
    void receivableAmountPositive() {
        assertThatThrownBy(() -> receivable("0", TODAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receivable("-1", TODAY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("allocate: zero, negative and null offers apply nothing and leave the status OPEN")
    void allocateNoOps() {
        Receivable r = receivable("100", TODAY);
        assertThat(r.allocate(BigDecimal.ZERO, NOW)).isEqualByComparingTo("0");
        assertThat(r.allocate(new BigDecimal("-3"), NOW)).isEqualByComparingTo("0");
        assertThat(r.allocate(null, NOW)).isEqualByComparingTo("0");
        assertThat(r.status()).isEqualTo(ReceivableStatus.OPEN);
    }

    @Test
    @DisplayName("allocate: cents add up exactly (no rounding) and the last cent settles it")
    void allocateCents() {
        Receivable r = receivable("100.10", TODAY);
        r.allocate(new BigDecimal("33.37"), NOW);
        r.allocate(new BigDecimal("33.37"), NOW);
        assertThat(r.outstanding()).isEqualByComparingTo("33.36");
        assertThat(r.status()).isEqualTo(ReceivableStatus.PARTIALLY_PAID);
        r.allocate(new BigDecimal("33.36"), NOW);
        assertThat(r.status()).isEqualTo(ReceivableStatus.PAID);
        assertThat(r.outstanding()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("markOverdue: a PAID receivable is never overdue; PARTIALLY_PAID past due is; the sweep is idempotent")
    void markOverdueRules() {
        Receivable partly = receivable("100", TODAY.minusDays(1));
        partly.allocate(new BigDecimal("10"), NOW);
        assertThat(partly.markOverdue(TODAY)).isTrue();
        assertThat(partly.status()).isEqualTo(ReceivableStatus.OVERDUE);
        assertThat(partly.markOverdue(TODAY)).as("already overdue: nothing more").isFalse();

        Receivable paid = receivable("100", TODAY.minusDays(30));
        paid.allocate(new BigDecimal("100"), NOW);
        assertThat(paid.markOverdue(TODAY)).isFalse();
        assertThat(paid.status()).isEqualTo(ReceivableStatus.PAID);
    }

    @Test
    @DisplayName("unallocate: more than was paid, zero and null are refused; part paid past due stays OVERDUE")
    void unallocateBounds() {
        Receivable r = receivable("100", TODAY.minusDays(2));
        r.allocate(new BigDecimal("40"), NOW);
        assertThatThrownBy(() -> r.unallocate(new BigDecimal("41"), TODAY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.unallocate(BigDecimal.ZERO, TODAY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.unallocate(null, TODAY)).isInstanceOf(IllegalStateException.class);
        r.unallocate(new BigDecimal("10"), TODAY);
        assertThat(r.paidAmount()).isEqualByComparingTo("30");
        assertThat(r.status()).isEqualTo(ReceivableStatus.OVERDUE);
    }

    @Test
    @DisplayName("on the due day itself a receivable is not yet overdue after taking money back")
    void unallocateOnDueDay() {
        Receivable r = receivable("100", TODAY);
        r.allocate(new BigDecimal("100"), NOW);
        r.unallocate(new BigDecimal("100"), TODAY);
        assertThat(r.status()).isEqualTo(ReceivableStatus.OPEN);
    }

    @Test
    @DisplayName("a cancellation fee is a CANCELLATION_FEE receivable paid by the customer; kind and collector are settable")
    void kinds() {
        Receivable fee = Receivable.openCancellationFee(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "VND",
                NOW, TODAY);
        assertThat(fee.kind()).isEqualTo("CANCELLATION_FEE");
        assertThat(fee.carrierCollected()).isFalse();
        assertThat(fee.shipmentId()).isNull();
        assertThat(fee.collectedBy(Collector.CARRIER).carrierCollected()).isTrue();
        assertThatThrownBy(() -> fee.withKind(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> fee.collectedBy(null)).isInstanceOf(NullPointerException.class);
    }
}
