package com.stockflow.payment.internal.service;

import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.api.PaymentService;
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

    private final CompanyBankProperties bank;
    private final com.stockflow.payment.internal.domain.InstallmentBook installmentBook;
    private final InstallmentService installments;
    private final OrderSettlementService settlements;

    PaymentServiceImpl(CompanyBankProperties bank,
                       com.stockflow.payment.internal.domain.InstallmentBook installmentBook,
                       InstallmentService installments, OrderSettlementService settlements) {
        this.bank = bank;
        this.installmentBook = installmentBook;
        this.installments = installments;
        this.settlements = settlements;
    }

    /**
     * The installments of the customer's signed contracts (15 BR-10): what those paid after delivery still owe, not yet
     * due included, with the cancellation fee items; and whatever installment is overdue (§4.3 step 9: an overdue
     * installment on signing counts as much as one after delivery). Since 4B-10 nothing else: the money ledger's
     * receivables went with it.
     */
    @Override
    @Transactional(readOnly = true)
    public CreditPosition creditPosition(UUID customerId) {
        var overdue = installmentBook.overdue(customerId, installments.today());
        return new CreditPosition(installmentBook.afterDeliveryUnpaid(customerId), overdue.amount().signum() > 0,
                overdue.amount(), overdue.oldestDueDate());
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
    @Transactional(readOnly = true)
    public java.math.BigDecimal dueBeforeHandover(UUID orderId, UUID deliveryBatchId) {
        return installments.dueBeforeHandover(orderId, deliveryBatchId);
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
    public java.util.Optional<UUID> chargeFailedDelivery(com.stockflow.payment.api.ChargeFailedDeliveryCommand command) {
        return installments.chargeFailedDelivery(command);
    }

    @Override
    public java.util.Optional<com.stockflow.payment.api.OrderSettlementResult> settleReturn(
            com.stockflow.payment.api.SettleReturnCommand command) {
        return settlements.settleReturn(command);
    }

    @Override
    @Transactional(readOnly = true)
    public com.stockflow.payment.api.RefundProgress refundProgress(UUID orderId) {
        return settlements.refundProgress(orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<com.stockflow.payment.api.CompanyBankAccount> companyBankAccount() {
        return bank.configured()
                ? java.util.Optional.of(new com.stockflow.payment.api.CompanyBankAccount(bank.bankName().strip(),
                        bank.accountNumber().strip(), bank.accountName().strip(), bank.bin()))
                : java.util.Optional.empty();
    }
}
