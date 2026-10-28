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
    private final CompanyBankProperties bank;

    PaymentServiceImpl(ReceivableBook book, CompanyBankProperties bank) {
        this.book = book;
        this.bank = bank;
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

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<com.stockflow.payment.api.CompanyBankAccount> companyBankAccount() {
        return bank.configured()
                ? java.util.Optional.of(new com.stockflow.payment.api.CompanyBankAccount(bank.bankName().strip(),
                        bank.accountNumber().strip(), bank.accountName().strip()))
                : java.util.Optional.empty();
    }
}
