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
        return new CreditPosition(book.outstanding(customerId), book.hasOverdue(customerId));
    }
}
