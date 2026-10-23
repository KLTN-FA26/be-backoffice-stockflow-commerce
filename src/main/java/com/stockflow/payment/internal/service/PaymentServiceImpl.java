package com.stockflow.payment.internal.service;

import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.internal.domain.ReceivableBook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The only implementation of {@link PaymentService}, and the module's transaction boundary.
 * Package-private class, public interface: Spring injects it by the interface.
 */
@Service
@Transactional
class PaymentServiceImpl implements PaymentService {

    private final ReceivableBook book;

    PaymentServiceImpl(ReceivableBook book) {
        this.book = book;
    }

    @Override
    @Transactional(readOnly = true)
    public CreditPosition creditPosition(UUID customerId) {
        var overdue = book.overdue(customerId);
        return new CreditPosition(book.outstanding(customerId), book.hasOverdue(customerId), overdue.amount(), overdue.oldestDueDate());
    }

    @Override
    @Transactional(readOnly = true)
    public com.stockflow.payment.api.OrderLedger ledgerOf(UUID orderId) {
        java.math.BigDecimal collected = book.collectedFor(orderId);
        return new com.stockflow.payment.api.OrderLedger(collected,
                collected.subtract(book.collectedOnReceivablesOf(orderId)).max(java.math.BigDecimal.ZERO));
    }
}
