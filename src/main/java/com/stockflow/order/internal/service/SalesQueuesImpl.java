package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.order.api.SalesQueues;
import com.stockflow.order.internal.repository.DeliveryBatchesDueQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * {@link SalesQueues} by the screens' own reads where there is a screen - {@link SalesContracts#creditReviewsWaiting}
 * for accounting's queue, {@link Quotes#list} with {@code priceReviewPendingOnly} for the Sales Manager's - and by
 * {@link DeliveryBatchesDueQuery} for the batches due, whose "still owing deliveries" is the delay alerts' own
 * {@link DelayAlertService#DELIVERY_FOLLOWED}, so the two never disagree on which orders still owe a batch.
 */
@Service
@Transactional(readOnly = true)
class SalesQueuesImpl implements SalesQueues {

    private final SalesContracts contracts;
    private final Quotes quotes;
    private final DeliveryBatchesDueQuery batches;

    SalesQueuesImpl(SalesContracts contracts, Quotes quotes, DeliveryBatchesDueQuery batches) {
        this.contracts = contracts;
        this.quotes = quotes;
        this.batches = batches;
    }

    @Override
    public PageResponse<BatchDue> batchesDue(LocalDate day, Set<UUID> warehouseIds, int limit) {
        requireLimit(limit);
        return batches.due(day, DelayAlertService.DELIVERY_FOLLOWED.stream().map(Enum::name).sorted().toList(),
                warehouseIds, limit);
    }

    @Override
    public PageResponse<CreditReviewWaiting> creditReviewsWaiting(int limit) {
        requireLimit(limit);
        return contracts.creditReviewsWaiting(0, limit).map(w -> new CreditReviewWaiting(w.orderId(), w.orderNumber(),
                w.contractNumber(), w.customerId(), w.submittedAt()));
    }

    @Override
    public PageResponse<QuoteWaiting> quotesAwaitingPriceApproval(CurrentUser user, int limit) {
        requireLimit(limit);
        return quotes.list(user, new Quotes.Criteria(null, null, null, 0, limit, null, true))
                .map(q -> new QuoteWaiting(q.id(), q.quoteNumber(), q.customerId(), q.createdAt()));
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_ROWS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + MAX_ROWS + ", not " + limit);
        }
    }
}
