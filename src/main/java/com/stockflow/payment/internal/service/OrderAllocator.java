package com.stockflow.payment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.payment.api.OrderPayables;
import com.stockflow.payment.api.PayableOrder;
import com.stockflow.payment.internal.domain.CustomerTransfer;
import com.stockflow.payment.internal.domain.ReceivableBook;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/**
 * Money of a transfer paid to an order itself (G2-3, decision G3-Q3: one ledger): a prepaid order's price, a deposit,
 * or a deposit order's balance before delivery (kltn-docs 15 §4.1 step 3, §4.2 steps 2-4).
 *
 * <p>An allocation never takes an order past what it is worth (15 BR-05 read forward: what is collected is never more
 * than the order, so no refund is manufactured by a slip). What the order may still take is measured on payment's own
 * ledger, under the order's row lock ({@link OrderPayables#lockForPayment}), so two allocations to one order never fit
 * into the same gap:</p>
 * <pre>
 *   billable - collected (net allocations, earlier payments, less refunds) - receivables outstanding - COD in transit
 * </pre>
 * <p>Receivables outstanding are billed already (a credit delivery, a carrier-collected balance); COD in transit is what
 * a carrier collects for goods it is carrying. Neither is paid twice by a transfer.</p>
 *
 * <p>The order learns of the money from {@code PaymentCaptured} (source {@code ORDER_ALLOCATION}), after commit: it
 * confirms a prepaid order paid in full and a deposit order whose deposit is in (15 BR-02).</p>
 */
@Component
class OrderAllocator {

    static final String ORDER_ALLOCATION = "ORDER_ALLOCATION";

    /** Statuses in which an order takes money by allocation; CANCELLED has its own answer (the refund path). */
    private static final Set<String> PAYABLE = Set.of("PENDING_PAYMENT", "CONFIRMED", "IN_PRODUCTION",
            "READY_TO_FULFILL", "PICKING", "PARTIALLY_FULFILLED", "PACKED", "ON_HOLD", "SHIPPED", "DELIVERY_FAILED",
            "DELIVERED");

    private final ReceivableBook book;
    private final OrderPayables orders;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OrderAllocator(ReceivableBook book, OrderPayables orders, ApplicationEventPublisher events, Clock clock) {
        this.book = book;
        this.orders = orders;
        this.events = events;
        this.clock = clock;
    }

    /**
     * The order, locked, checked to take money from {@code customerId}'s transfers.
     *
     * @throws BusinessException {@code NOT_FOUND}; {@code ALLOCATION_CUSTOMER_MISMATCH};
     *         {@code ALLOCATION_ORDER_CANCELLED} (15 edge: the money stays the customer's credit - another order, or
     *         a refund of the credit); {@code ALLOCATION_ORDER_NOT_PAYABLE} for a draft, a settled order or a credit
     *         order (paid on its receivables, 15 §4.3)
     */
    PayableOrder lockPayable(UUID orderId, UUID customerId) {
        PayableOrder order = orders.lockForPayment(orderId).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        if (order.customerId() == null || !order.customerId().equals(customerId)) {
            throw new BusinessException(ErrorCode.ALLOCATION_CUSTOMER_MISMATCH,
                    "Order %s belongs to another customer".formatted(order.orderNumber()));
        }
        if ("CANCELLED".equals(order.status())) {
            throw new BusinessException(ErrorCode.ALLOCATION_ORDER_CANCELLED,
                    "Order %s is cancelled; the money stays as the customer's credit".formatted(order.orderNumber()));
        }
        if ("CREDIT".equals(order.paymentTerm())) {
            throw new BusinessException(ErrorCode.ALLOCATION_ORDER_NOT_PAYABLE,
                    "Order %s is sold on credit: its deliveries are billed as receivables; pay those"
                            .formatted(order.orderNumber()));
        }
        if (!PAYABLE.contains(order.status())) {
            throw new BusinessException(ErrorCode.ALLOCATION_ORDER_NOT_PAYABLE,
                    "Order %s is %s and takes no payment".formatted(order.orderNumber(), order.status()));
        }
        return order;
    }

    /** What the order may still take by transfer, never below zero. */
    BigDecimal mayStillTake(PayableOrder order) {
        return order.billable()
                .subtract(book.collectedFor(order.orderId()))
                .subtract(book.outstandingReceivablesOf(order.orderId()))
                .subtract(order.codInTransit() == null ? BigDecimal.ZERO : order.codInTransit())
                .max(BigDecimal.ZERO);
    }

    /**
     * Pays exactly {@code amount} of {@code transfer} to {@code order}, or nothing.
     *
     * @throws BusinessException {@code ALLOCATION_EXCEEDS_AMOUNT_DUE}; {@code ALLOCATION_EXCEEDS_TRANSFER}
     */
    ReceivableBook.Allocation allocate(CustomerTransfer transfer, PayableOrder order, BigDecimal amount, UUID by) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "An allocation brings money");
        }
        if (!transfer.currency().equals(order.currency())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Order %s is in %s, the transfer in %s"
                    .formatted(order.orderNumber(), order.currency(), transfer.currency()));
        }
        BigDecimal room = mayStillTake(order);
        if (amount.compareTo(room) > 0) {
            throw new BusinessException(ErrorCode.ALLOCATION_EXCEEDS_AMOUNT_DUE,
                    "Order %s may take %s %s more, not %s".formatted(order.orderNumber(), plain(room),
                            order.currency(), plain(amount)));
        }
        transfer.takeExactly(amount);
        ReceivableBook.Allocation allocation = book.recordAllocation(transfer.id(), order.orderId(), null, amount,
                clock.instant(), by);
        events.publishEvent(new PaymentCaptured(order.orderId(), allocation.id(), amount, transfer.currency(),
                PaymentMethod.BANK_TRANSFER, ORDER_ALLOCATION));
        return allocation;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
