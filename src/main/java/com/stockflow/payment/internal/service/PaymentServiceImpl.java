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
    private final com.stockflow.payment.internal.domain.InstallmentBook installmentBook;
    private final InstallmentService installments;
    private final OrderSettlementService settlements;

    PaymentServiceImpl(ReceivableBook book, CompanyBankProperties bank,
                       com.stockflow.payment.internal.domain.InstallmentBook installmentBook,
                       InstallmentService installments, OrderSettlementService settlements) {
        this.book = book;
        this.bank = bank;
        this.installmentBook = installmentBook;
        this.installments = installments;
        this.settlements = settlements;
    }

    /**
     * The money ledger's receivables, and since 4B-6 the installments of the customer's signed contracts: what those paid
     * after delivery still owe, not yet due included (15 BR-10), and whatever installment is overdue (§4.3 step 9: an
     * overdue installment on signing counts as much as a receivable).
     */
    @Override
    @Transactional(readOnly = true)
    public CreditPosition creditPosition(UUID customerId) {
        var overdue = book.overdue(customerId);
        var overdueInstallments = installmentBook.overdue(customerId);
        java.time.LocalDate oldest = overdue.oldestDueDate() == null ? overdueInstallments.oldestDueDate()
                : overdueInstallments.oldestDueDate() == null || overdue.oldestDueDate()
                        .isBefore(overdueInstallments.oldestDueDate()) ? overdue.oldestDueDate()
                : overdueInstallments.oldestDueDate();
        java.math.BigDecimal overdueAmount = overdue.amount().add(overdueInstallments.amount());
        return new CreditPosition(book.outstanding(customerId).add(installmentBook.afterDeliveryUnpaid(customerId)),
                book.hasOverdue(customerId) || overdueInstallments.amount().signum() > 0, overdueAmount, oldest);
    }

    @Override
    public void openInstallments(com.stockflow.payment.api.ContractInstallments contract) {
        installments.open(contract);
    }

    @Override
    @Transactional(readOnly = true)
    public com.stockflow.payment.api.InstallmentStanding installmentStanding(UUID orderId) {
        return installments.standing(orderId);
    }

    @Override
    public java.util.Optional<com.stockflow.payment.api.OrderSettlementResult> settleCancellation(
            com.stockflow.payment.api.SettleCancellationCommand command) {
        return settlements.settleCancellation(command);
    }

    @Override
    public java.util.Optional<com.stockflow.payment.api.OrderSettlementResult> settleRemainderClosure(
            com.stockflow.payment.api.SettleRemainderClosureCommand command) {
        return settlements.settleRemainderClosure(command);
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
