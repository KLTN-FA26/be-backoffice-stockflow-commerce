package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.domain.CustomerChange;
import com.stockflow.customer.internal.domain.CustomerChangeLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** Adapts {@link CustomerChangeLog} onto {@code customer.customer_change_history} (V20261017001100). */
@Repository
class CustomerChangeLogAdapter implements CustomerChangeLog {

    private final JdbcTemplate jdbc;

    CustomerChangeLogAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(CustomerChange change) {
        jdbc.update("""
                INSERT INTO customer.customer_change_history (id, customer_id, field, old_value, new_value, changed_by,
                    changed_at) VALUES (?, ?, ?, ?, ?, ?, ?)""", change.id(), change.customerId(), change.field().name(),
                change.oldValue(), change.newValue(), change.changedBy(), Timestamp.from(change.changedAt()));
    }

    @Override
    public List<CustomerChange> of(UUID customerId) {
        return jdbc.query("""
                SELECT id, customer_id, field, old_value, new_value, changed_by, changed_at
                  FROM customer.customer_change_history WHERE customer_id = ? ORDER BY changed_at, id""",
                (row, n) -> new CustomerChange(row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class),
                        CustomerChange.Field.valueOf(row.getString("field")), row.getString("old_value"),
                        row.getString("new_value"), row.getObject("changed_by", UUID.class),
                        row.getTimestamp("changed_at").toInstant()), customerId);
    }
}
