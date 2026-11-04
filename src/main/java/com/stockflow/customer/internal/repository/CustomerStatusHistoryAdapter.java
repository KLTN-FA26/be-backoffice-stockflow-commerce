package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.domain.CustomerStatusChange;
import com.stockflow.customer.internal.domain.CustomerStatusHistory;
import com.stockflow.customer.internal.entity.CustomerStatusHistoryJpaEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Adapts {@link CustomerStatusHistory} onto {@code customer.customer_status_history}. */
@Repository
class CustomerStatusHistoryAdapter implements CustomerStatusHistory {

    private final CustomerStatusHistoryJpaRepository rows;

    CustomerStatusHistoryAdapter(CustomerStatusHistoryJpaRepository rows) {
        this.rows = rows;
    }

    @Override
    public void record(CustomerStatusChange change) {
        rows.save(new CustomerStatusHistoryJpaEntity(change.id(), change.customerId(), change.from(), change.to(),
                change.reason(), change.changedBy(), change.changedAt()));
    }

    @Override
    public List<CustomerStatusChange> of(UUID customerId) {
        return rows.findByCustomerIdOrderByChangedAtAscCreatedAtAsc(customerId).stream()
                .map(row -> new CustomerStatusChange(row.getId(), row.getCustomerId(), row.getFromStatus(),
                        row.getToStatus(), row.getReason(), row.getChangedBy(), row.getChangedAt()))
                .toList();
    }
}
