package com.stockflow.notification.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Caller transaction holds the row lock until transport finishes or the PO transition commits. */
@Repository
public class PoDeliveryControlRepository {
    /**
     * {@code cancellationRequested}: the order was cancelled after it was sent and its supplier is told so, which
     * makes any amendment notice of it that has not reached them yet moot (SCRUM-117).
     */
    public record Control(int generation, boolean suppressed, boolean cancellationRequested) {
        public Control(int generation, boolean suppressed) {
            this(generation, suppressed, false);
        }
    }

    private final JdbcTemplate jdbc;

    public PoDeliveryControlRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Control lock(UUID id) {
        jdbc.update(
                "insert into notification.po_delivery_control(purchase_order_id) values (?) on"
                    + " conflict do nothing",
                id);
        return jdbc.queryForObject(
                "select generation,suppressed,cancellation_requested from notification.po_delivery_control"
                    + " where purchase_order_id=? for update",
                (row, index) -> new Control(row.getInt(1), row.getBoolean(2), row.getBoolean(3)),
                id);
    }

    public void suppress(UUID id) {
        lock(id);
        jdbc.update(
                "update notification.po_delivery_control set suppressed=true where"
                    + " purchase_order_id=?",
                id);
    }

    public void advance(UUID id, int generation) {
        jdbc.update(
                "update notification.po_delivery_control set generation=? where"
                    + " purchase_order_id=?",
                generation,
                id);
    }

    public void requestCancellation(UUID id) {
        lock(id);
        jdbc.update(
                "update notification.po_delivery_control set cancellation_requested=true where"
                    + " purchase_order_id=?",
                id);
    }
}
