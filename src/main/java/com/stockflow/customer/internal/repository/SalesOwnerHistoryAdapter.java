package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.domain.SalesOwnerChange;
import com.stockflow.customer.internal.domain.SalesOwnerHistory;
import com.stockflow.customer.internal.entity.CustomerSalesOwnerHistoryJpaEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Adapts {@link SalesOwnerHistory} onto {@code customer.customer_sales_owner_history}. */
@Repository
class SalesOwnerHistoryAdapter implements SalesOwnerHistory {

    private final SalesOwnerHistoryJpaRepository rows;

    SalesOwnerHistoryAdapter(SalesOwnerHistoryJpaRepository rows) {
        this.rows = rows;
    }

    @Override
    public void record(SalesOwnerChange change) {
        rows.save(new CustomerSalesOwnerHistoryJpaEntity(change.id(), change.customerId(), change.fromUserId(),
                change.toUserId(), change.toState(), change.reason(), change.note(), change.changedBy(),
                change.changedAt(), change.batchId()));
    }

    @Override
    public List<SalesOwnerChange> of(UUID customerId, int page, int size) {
        return rows.findByCustomerIdOrderByChangedAtDescIdDesc(customerId,
                        PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100))))
                .map(row -> new SalesOwnerChange(row.getId(), row.getCustomerId(), row.getFromUserId(),
                        row.getToUserId(), row.getToState(), row.getReason(), row.getNote(), row.getChangedBy(),
                        row.getChangedAt(), row.getBatchId()))
                .getContent();
    }

    @Override
    public long countOf(UUID customerId) {
        return rows.countByCustomerId(customerId);
    }
}
