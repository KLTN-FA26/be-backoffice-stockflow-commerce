package com.stockflow.payment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.payment.api.OverdueCustomer;
import com.stockflow.payment.api.OverdueReceivables;
import com.stockflow.payment.internal.domain.ReceivableBook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link OverdueReceivables} over the receivable book. Repeatable read, so the rows and the totals come from one
 * snapshot: under read committed each statement has its own, and the night's overdue job or a payment landing between
 * the two reads would make them disagree.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class OverdueReceivablesImpl implements OverdueReceivables {

    private final ReceivableBook book;

    OverdueReceivablesImpl(ReceivableBook book) {
        this.book = book;
    }

    @Override
    public OverdueCustomers overdueCustomers(boolean oldestFirst, int limit) {
        if (limit < 1 || limit > MAX_CUSTOMERS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_CUSTOMERS);
        }
        ReceivableBook.OverdueTotals totals = book.overdueTotals();
        return new OverdueCustomers(book.overdueCustomers(oldestFirst, limit).stream()
                .map(row -> new OverdueCustomer(row.customerId(), row.amount(), row.currency(), row.oldestDueDate(),
                        row.receivables(), row.orderNumbers()))
                .toList(), totals.customers(), totals.rows(), totals.amountByCurrency());
    }
}
