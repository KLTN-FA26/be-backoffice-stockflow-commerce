package com.stockflow.payment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.payment.api.PaymentQueues;
import com.stockflow.payment.internal.domain.BankTransactionLog;
import com.stockflow.payment.internal.domain.BankTransactionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link PaymentQueues} by the screens' own reads: {@link BankTransactionService#list} with NEEDS_REVIEW, as the bank
 * transfer screen's accountant queue, and {@link InstallmentService#installments} with {@code overdue=true}, as the
 * installment screen's aged-receivables reading - so a count here is the count the screen shows.
 */
@Service
@Transactional(readOnly = true)
class PaymentQueuesImpl implements PaymentQueues {

    private final BankTransactionService transactions;
    private final InstallmentService installments;

    PaymentQueuesImpl(BankTransactionService transactions, InstallmentService installments) {
        this.transactions = transactions;
        this.installments = installments;
    }

    @Override
    public PageResponse<TransferToReview> transfersToReview(int limit) {
        requireLimit(limit);
        BankTransactionLog.Page found = transactions.list(List.of(BankTransactionStatus.NEEDS_REVIEW), null, null, 0,
                limit);
        List<TransferToReview> rows = found.rows().stream().map(row -> new TransferToReview(row.id(), row.amount(),
                row.transfer() == null ? null : row.transfer().transactionAt(), row.receivedAt(),
                row.reviewReason() == null ? null : row.reviewReason().name())).toList();
        return PageResponse.of(rows, 0, limit, found.total());
    }

    @Override
    public PageResponse<OverdueInstallment> overdueInstallments(int limit) {
        requireLimit(limit);
        return installments.installments(null, null, null, null, null, null, null, true, null, 0, limit, "dueDate,asc")
                .map(i -> new OverdueInstallment(i.id(), i.orderId(), i.orderNumber(), i.contractNumber(),
                        i.customerId(), i.seq(), i.dueDate(), i.outstanding(), i.currency()));
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_ROWS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + MAX_ROWS + ", not " + limit);
        }
    }
}
