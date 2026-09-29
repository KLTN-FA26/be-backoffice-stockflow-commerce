package com.stockflow.notification.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

/** Caller transaction holds the row lock until transport finishes or the PO transition commits. */
@Repository
public class PoDeliveryControlRepository {
    public record Control(int generation, boolean suppressed) {}
    private final JdbcTemplate jdbc;

    public PoDeliveryControlRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Control lock(UUID id) {
        jdbc.update("insert into notification.po_delivery_control(purchase_order_id) values (?) on conflict do nothing", id);
        return jdbc.queryForObject("select generation,suppressed from notification.po_delivery_control where purchase_order_id=? for update",
                (row, index) -> new Control(row.getInt(1), row.getBoolean(2)), id);
    }

    public void suppress(UUID id) {
        lock(id);
        jdbc.update("update notification.po_delivery_control set suppressed=true where purchase_order_id=?", id);
    }

    public void advance(UUID id, int generation) {
        jdbc.update("update notification.po_delivery_control set generation=? where purchase_order_id=?", generation, id);
    }
}
