package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.payment.api.PaymentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@link TransferInstructions}. What is due comes from the order's own terms: the deposit while it is unpaid, else
 * {@code amountDueBeforeHandover} (the prepayment, the balance of an order paid before delivery, nothing on credit),
 * less what payment's ledger already holds beyond what the order has heard of - the ledger can be a moment ahead of
 * the order's events (G2-3). Nothing about money is decided here that payment or the aggregate does not already decide.
 */
@Service
@Transactional(readOnly = true)
class TransferInstructionsService implements TransferInstructions {

    private final OrderService orders;
    private final PaymentService payments;
    private final Messages messages;

    TransferInstructionsService(OrderService orders, PaymentService payments, Messages messages) {
        this.orders = orders;
        this.payments = payments;
        this.messages = messages;
    }

    @Override
    public Instructions of(UUID customerId, UUID orderId) {
        OrderSummary order = orders.findById(orderId)
                .filter(o -> customerId.equals(o.customerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        var bank = payments.companyBankAccount()
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFER_INSTRUCTIONS_UNAVAILABLE,
                        "stockflow.payment.company-bank is not configured"));
        BigDecimal ledgerAhead = payments.ledgerOf(orderId).collected()
                .subtract(order.payment() == null ? BigDecimal.ZERO : order.payment().paidAmount()).max(BigDecimal.ZERO);
        Due due = due(order, ledgerAhead);
        DueBy dueBy = due.amount().signum() == 0 ? DueBy.NONE : due.toConfirm() ? DueBy.TO_CONFIRM : DueBy.BEFORE_DELIVERY;
        return new Instructions(order.orderId(), order.orderNumber(), bank.bankName(), bank.accountNumber(),
                bank.accountName(), order.orderNumber(), due.amount(), order.total().currency().getCurrencyCode(),
                dueBy, messages.get("order.transfer.dueBy." + dueBy.name(), dueBy.name()));
    }

    /** What is due now, and whether the order waits for it to be confirmed. */
    private record Due(BigDecimal amount, boolean toConfirm) {
    }

    /**
     * The deposit first while it is unpaid - the money that confirms a deposit order (15 BR-02), even when its balance
     * is also wanted before delivery - then what the order wants before its goods leave (G3-Q4), less the ledger's
     * lead. Nothing for a credit order, once the goods have left or when the order is cancelled.
     */
    private static Due due(OrderSummary order, BigDecimal ledgerAhead) {
        // Only while the goods are still here: a cancelled order owes nothing, and past the handover the terms were met.
        if (!order.status().holdsStock() || order.lifecycle() == null || order.payment() == null) {
            return new Due(BigDecimal.ZERO, false);
        }
        var payment = order.payment();
        BigDecimal collected = payment.paidAmount().add(ledgerAhead);
        boolean waiting = order.status() == OrderStatus.PENDING_PAYMENT
                || order.status() == OrderStatus.ON_HOLD && order.lifecycle().holdReason() == HoldReason.PAYMENT;
        if (order.paymentTerm() == PaymentTerm.DEPOSIT && payment.depositRequired() != null) {
            BigDecimal depositShort = payment.depositRequired().subtract(collected).max(BigDecimal.ZERO);
            if (depositShort.signum() > 0) {
                return new Due(depositShort, true);
            }
        }
        return new Due(order.lifecycle().amountDueBeforeHandover().subtract(ledgerAhead).max(BigDecimal.ZERO), waiting);
    }
}
