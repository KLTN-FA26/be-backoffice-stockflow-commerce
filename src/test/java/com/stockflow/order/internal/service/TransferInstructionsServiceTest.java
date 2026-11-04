package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.service.TransferInstructions.DueBy;
import com.stockflow.payment.api.CompanyBankAccount;
import com.stockflow.payment.api.OrderLedger;
import com.stockflow.payment.api.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SCRUM-193: what a customer is told to transfer, from the order's terms and what payment's ledger holds. */
class TransferInstructionsServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-23T03:00:00Z");

    private final UUID customer = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final OrderService orders = mock(OrderService.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final TransferInstructionsService service = new TransferInstructionsService(orders, payments,
            new Messages(new StaticMessageSource()));

    private static BigDecimal vnd(long amount) {
        return BigDecimal.valueOf(amount);
    }

    /** A 10M order on {@code term} with {@code paid} counted, {@code depositRequired} (deposit only) and what the order wants before handover. */
    private OrderSummary order(OrderStatus status, PaymentTerm term, long paid, Long depositRequired, long dueBeforeHandover,
                               HoldReason hold) {
        var payment = new OrderSummary.Payment(vnd(paid), depositRequired == null ? null : vnd(depositRequired), null, null,
                PaymentStatus.UNPAID);
        var lifecycle = new OrderSummary.Lifecycle(hold, null, null, null, null, null, null, null, null, BigDecimal.ZERO,
                vnd(10_000_000), vnd(10_000_000 - paid), vnd(dueBeforeHandover), List.of(), List.of());
        return new OrderSummary(orderId, "SO-20261023-000001", customer, status, Money.vnd(10_000_000), List.of(), T0, null,
                null, null, null, null, null, null, null, term, null, null, payment, null, lifecycle);
    }

    private void given(OrderSummary order, long ledgerCollected, boolean configured) {
        when(orders.findById(orderId)).thenReturn(Optional.of(order));
        when(payments.ledgerOf(orderId)).thenReturn(new OrderLedger(vnd(ledgerCollected), vnd(ledgerCollected)));
        when(payments.companyBankAccount()).thenReturn(configured
                ? Optional.of(new CompanyBankAccount("Vietcombank", "0071000888999", "CONG TY STOCKFLOW"))
                : Optional.empty());
    }

    @Test
    @DisplayName("not configured: 503 TRANSFER_INSTRUCTIONS_UNAVAILABLE - after the order lookup, so someone else's "
            + "order is still 404")
    void notConfigured() {
        given(order(OrderStatus.PENDING_PAYMENT, PaymentTerm.PREPAID, 0, null, 10_000_000, null), 0, false);
        assertThat(catchThrowableOfType(BusinessException.class, () -> service.of(customer, orderId)).errorCode())
                .isEqualTo(ErrorCode.TRANSFER_INSTRUCTIONS_UNAVAILABLE);
        assertThat(catchThrowableOfType(BusinessException.class, () -> service.of(UUID.randomUUID(), orderId)).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("the account and the order number as the content come with the amount")
    void theAccountAndTheContent() {
        given(order(OrderStatus.PENDING_PAYMENT, PaymentTerm.PREPAID, 0, null, 10_000_000, null), 0, true);
        var instructions = service.of(customer, orderId);
        assertThat(instructions.bankName()).isEqualTo("Vietcombank");
        assertThat(instructions.accountNumber()).isEqualTo("0071000888999");
        assertThat(instructions.accountName()).isEqualTo("CONG TY STOCKFLOW");
        assertThat(instructions.reference()).isEqualTo("SO-20261023-000001");
        assertThat(instructions.currency()).isEqualTo("VND");
        assertThat(instructions.amountDue()).isEqualByComparingTo("10000000");
        assertThat(instructions.dueBy()).isEqualTo(DueBy.TO_CONFIRM);
    }

    @Test
    @DisplayName("money the ledger holds that the order has not heard of yet is not asked for again")
    void theLedgerMayBeAhead() {
        given(order(OrderStatus.PENDING_PAYMENT, PaymentTerm.PREPAID, 0, null, 10_000_000, null), 4_000_000, true);
        assertThat(service.of(customer, orderId).amountDue()).isEqualByComparingTo("6000000");
        given(order(OrderStatus.PENDING_PAYMENT, PaymentTerm.PREPAID, 4_000_000, null, 6_000_000, null), 4_000_000, true);
        assertThat(service.of(customer, orderId).amountDue()).as("the order and the ledger agree").isEqualByComparingTo("6000000");
    }

    @Test
    @DisplayName("a deposit order: the deposit while it is short - not the whole total its balance policy wants before "
            + "delivery - then the balance")
    void depositFirst() {
        given(order(OrderStatus.PENDING_PAYMENT, PaymentTerm.DEPOSIT, 1_000_000, 3_000_000L, 9_000_000, null), 1_000_000, true);
        var first = service.of(customer, orderId);
        assertThat(first.amountDue()).isEqualByComparingTo("2000000");
        assertThat(first.dueBy()).isEqualTo(DueBy.TO_CONFIRM);

        given(order(OrderStatus.CONFIRMED, PaymentTerm.DEPOSIT, 3_000_000, 3_000_000L, 7_000_000, null), 3_000_000, true);
        var second = service.of(customer, orderId);
        assertThat(second.amountDue()).isEqualByComparingTo("7000000");
        assertThat(second.dueBy()).isEqualTo(DueBy.BEFORE_DELIVERY);
    }

    @Test
    @DisplayName("a payment hold is money to confirm; credit, a cancelled order and goods already gone owe nothing")
    void nothingDue() {
        given(order(OrderStatus.ON_HOLD, PaymentTerm.PREPAID, 8_000_000, null, 2_000_000, HoldReason.PAYMENT), 8_000_000, true);
        assertThat(service.of(customer, orderId).dueBy()).isEqualTo(DueBy.TO_CONFIRM);

        given(order(OrderStatus.CONFIRMED, PaymentTerm.CREDIT, 0, null, 0, null), 0, true);
        assertThat(service.of(customer, orderId).dueBy()).isEqualTo(DueBy.NONE);
        given(order(OrderStatus.CANCELLED, PaymentTerm.PREPAID, 0, null, 10_000_000, null), 0, true);
        assertThat(service.of(customer, orderId).amountDue()).isZero();
        given(order(OrderStatus.SHIPPED, PaymentTerm.PREPAID, 10_000_000, null, 0, null), 10_000_000, true);
        assertThat(service.of(customer, orderId).amountDue()).isZero();
    }
}
