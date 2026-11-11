package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 4B-6, kltn-docs 15 §4.2 step 6, §5.3, BR-03..BR-05: what a receipt is on its own, and its void. */
class PaymentReceiptTest {

    private static final Instant AT = Instant.parse("2026-11-09T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ACCOUNTANT = UUID.randomUUID();

    private static PaymentReceipt.Payment pays(long amount) {
        return new PaymentReceipt.Payment(UUID.randomUUID(), BigDecimal.valueOf(amount));
    }

    private static PaymentReceipt receipt(String reference, LocalDate receivedOn, List<PaymentReceipt.Payment> lines) {
        return PaymentReceipt.record(CUSTOMER, reference, receivedOn, "VND", "  statement  ", lines, ACCOUNTANT, AT,
                TODAY);
    }

    private static ErrorCode code(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("several lines, one statement line: the receipt is their sum, RECORDED, reference and note trimmed")
    void sumOfLines() {
        PaymentReceipt receipt = receipt(" FT123 ", TODAY, List.of(pays(1_000), pays(2_500)));
        assertThat(receipt.amount()).isEqualByComparingTo("3500");
        assertThat(receipt.reference()).isEqualTo("FT123");
        assertThat(receipt.note()).isEqualTo("statement");
        assertThat(receipt.status()).isEqualTo(PaymentReceiptStatus.RECORDED);
        assertThat(receipt.lines()).hasSize(2);
    }

    @Test
    @DisplayName("refused: no line, a line paying nothing or a fraction of a dong, an installment twice, a day ahead")
    void refused() {
        assertThatThrownBy(() -> receipt("FT1", TODAY, List.of())).extracting(PaymentReceiptTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> receipt("FT1", TODAY, List.of(pays(0)))).extracting(PaymentReceiptTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> receipt("FT1", TODAY, List.of(new PaymentReceipt.Payment(UUID.randomUUID(),
                new BigDecimal("10.5"))))).extracting(PaymentReceiptTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        UUID twice = UUID.randomUUID();
        assertThatThrownBy(() -> receipt("FT1", TODAY, List.of(new PaymentReceipt.Payment(twice, BigDecimal.TEN),
                new PaymentReceipt.Payment(twice, BigDecimal.ONE)))).extracting(PaymentReceiptTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> receipt("FT1", TODAY.plusDays(1), List.of(pays(10))))
                .as("15 §3: money is not received in the future").extracting(PaymentReceiptTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> receipt("   ", TODAY, List.of(pays(10)))).extracting(PaymentReceiptTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> receipt("F".repeat(101), TODAY, List.of(pays(10))))
                .extracting(PaymentReceiptTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("voided with a reason, who and when, kept as it was; a second void is refused (BR-04)")
    void voidOnce() {
        PaymentReceipt receipt = receipt("FT9", TODAY, List.of(pays(1_000)));
        assertThatThrownBy(() -> receipt.voidReceipt("  ", ACCOUNTANT, AT)).extracting(PaymentReceiptTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        receipt.voidReceipt(" wrong customer ", ACCOUNTANT, AT.plusSeconds(60));
        assertThat(receipt.status()).isEqualTo(PaymentReceiptStatus.VOIDED);
        assertThat(receipt.voidReason()).isEqualTo("wrong customer");
        assertThat(receipt.voidedBy()).isEqualTo(ACCOUNTANT);
        assertThat(receipt.amount()).isEqualByComparingTo("1000");
        assertThat(receipt.lines()).hasSize(1);
        assertThatThrownBy(() -> receipt.voidReceipt("again", ACCOUNTANT, AT)).extracting(PaymentReceiptTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_ALREADY_VOIDED);
    }
}
