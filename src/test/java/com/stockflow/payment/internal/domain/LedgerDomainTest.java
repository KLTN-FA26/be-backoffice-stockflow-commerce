package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** G2-3: the ledger's money rules on the domain alone - no money spent twice, nothing edited, refunds bounded. */
class LedgerDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    private static CustomerTransfer transfer(String amount) {
        return CustomerTransfer.record(UUID.randomUUID(), "FT-1", new BigDecimal(amount), "VND", TODAY,
                UUID.randomUUID(), NOW, null);
    }

    private static ErrorCode code(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("no BusinessException");
    }

    @Test
    @DisplayName("an allocation by amount takes exactly that or nothing; a reversal gives it back; a credit refund "
            + "never exceeds what is left (15 BR-05)")
    void transferMoney() {
        CustomerTransfer t = transfer("1000");
        t.takeExactly(new BigDecimal("600"));
        assertThat(t.unallocatedAmount()).isEqualByComparingTo("400");
        assertThat(code(() -> t.takeExactly(new BigDecimal("401")))).isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_TRANSFER);
        assertThat(t.unallocatedAmount()).as("unchanged by the refusal").isEqualByComparingTo("400");

        t.giveBack(new BigDecimal("600"));
        assertThat(t.unallocatedAmount()).isEqualByComparingTo("1000");
        assertThatThrownBy(() -> t.giveBack(BigDecimal.ONE)).isInstanceOf(IllegalStateException.class);

        t.refundCredit(new BigDecimal("300"));
        assertThat(t.unallocatedAmount()).isEqualByComparingTo("700");
        assertThat(t.refundedAmount()).isEqualByComparingTo("300");
        assertThat(code(() -> t.refundCredit(new BigDecimal("701")))).isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_TRANSFER);
        assertThatThrownBy(() -> t.giveBack(new BigDecimal("301")))
                .as("refunded money never comes back as credit").isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a carrier remittance names its carrier; a bank transfer does not")
    void channels() {
        CustomerTransfer remittance = CustomerTransfer.carrierRemittance(UUID.randomUUID(), "GHN", "GHN-1:SO-1",
                BigDecimal.TEN, "VND", TODAY, UUID.randomUUID(), NOW, null);
        assertThat(remittance.channel()).isEqualTo(TransferChannel.CARRIER_REMITTANCE);
        assertThat(code(() -> CustomerTransfer.carrierRemittance(UUID.randomUUID(), " ", "GHN-1:SO-1", BigDecimal.TEN,
                "VND", TODAY, UUID.randomUUID(), NOW, null))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(transfer("1").channel()).isEqualTo(TransferChannel.BANK_TRANSFER);
    }

    @Test
    @DisplayName("taking money back off a receivable: the status follows the money and the due date")
    void unallocate() {
        Receivable r = Receivable.open(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000"), "VND", NOW,
                TODAY.plusDays(10));
        r.allocate(new BigDecimal("1000"), NOW);
        assertThat(r.status()).isEqualTo(ReceivableStatus.PAID);
        r.unallocate(new BigDecimal("400"), TODAY);
        assertThat(r.status()).isEqualTo(ReceivableStatus.PARTIALLY_PAID);
        assertThat(r.settledAt()).isNull();
        r.unallocate(new BigDecimal("600"), TODAY);
        assertThat(r.status()).isEqualTo(ReceivableStatus.OPEN);
        assertThatThrownBy(() -> r.unallocate(BigDecimal.ONE, TODAY)).isInstanceOf(IllegalStateException.class);

        r.allocate(new BigDecimal("1000"), NOW);
        r.unallocate(new BigDecimal("1"), TODAY.plusDays(11));
        assertThat(r.status()).as("past due again").isEqualTo(ReceivableStatus.OVERDUE);
    }

    @Test
    @DisplayName("a carrier-collected balance is a DEPOSIT_BALANCE the carrier pays, due on the delivery day")
    void carrierCollected() {
        Receivable r = Receivable.openCarrierCollected(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("770000"),
                "VND", NOW, TODAY);
        assertThat(r.kind()).isEqualTo("DEPOSIT_BALANCE");
        assertThat(r.collector()).isEqualTo(Collector.CARRIER);
        assertThat(r.carrierCollected()).isTrue();
        assertThat(r.dueDate()).isEqualTo(TODAY);
    }
}
