package com.stockflow.fulfillment;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.fulfillment.internal.service.PickLists;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The put-back queue (kltn-docs 07 §4.5 step 13, WF04 G8) against a real PostgreSQL: the cartons of the task cancelled
 * earliest come first, whatever order they were picked in; the cartons of one task keep the order they were picked in.
 * The rows are written directly, in a warehouse of their own: how a task comes to be cancelled and its cartons to be
 * waiting is the cancellation's and the picking's tests', not the queue's.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
class PutBackQueueIntegrationTest {

    @Autowired PickLists pickLists;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private UUID warehouse;
    private final List<UUID> orders = new ArrayList<>();
    private final Instant base = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void aWarehouseOfItsOwn() {
        warehouse = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Put-back queue', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""",
                warehouse, "Q" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT)));
    }

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(s -> {
            for (UUID order : orders) {
                jdbc.update("DELETE FROM fulfillment.pick_line WHERE pick_id IN (SELECT id FROM fulfillment.pick "
                        + "WHERE order_id = ?)", order);
                jdbc.update("DELETE FROM fulfillment.pack WHERE pick_id IN (SELECT id FROM fulfillment.pick "
                        + "WHERE order_id = ?)", order);
                jdbc.update("DELETE FROM fulfillment.pick WHERE order_id = ?", order);
                jdbc.update("DELETE FROM ordering.order_line WHERE order_id = ?", order);
                jdbc.update("DELETE FROM ordering.customer_order WHERE id = ?", order);
            }
            jdbc.update("DELETE FROM warehouse.warehouse WHERE id = ?", warehouse);
        });
    }

    /**
     * A task of a new order, CANCELLED at {@code cancelledAt} and needing a put-back, with one PICKED carton per entry of
     * {@code pickedAt}; the ids of its pick lines, in that order.
     */
    private List<UUID> cancelledTask(Instant cancelledAt, Instant... pickedAt) {
        UUID order = Identifiers.newId();
        orders.add(order);
        UUID pick = Identifiers.newId();
        UUID orderLine = Identifiers.newId();
        List<UUID> lines = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                         currency, placed_at, version, created_at, contract_signed_at)
                    VALUES (?, ?, ?, ?, 'CONFIRMED', 1000, 'VND', NOW(), 0, NOW(), NOW())""", order,
                    "SO-20991230-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)),
                    DemoData.CUSTOMER_ID, UUID.randomUUID());
            jdbc.update("INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency) "
                    + "VALUES (?, ?, 'LY-GIAY-DEMO-12OZ-XANH', 1, 10, 'VND')", orderLine, order);
            // What the cancellation left, written whole: the rows need no stock, only the queue reads them.
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at,
                                                  needs_put_back, cancelled_at)
                    VALUES (?, ?, 'SO-X', ?, 'CANCELLED', NOW(), TRUE, ?)""", pick, order, warehouse,
                    Timestamp.from(cancelledAt));
            int no = 0;
            for (Instant picked : pickedAt) {
                UUID line = Identifiers.newId();
                lines.add(line);
                jdbc.update("""
                        INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, lot_number,
                            sequence_no, allocated_qty, picked_qty, status, lpn, whole_carton, picked_by, picked_at)
                        VALUES (?, ?, ?, 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-A', 'L1', ?, 1, 1, 'PICKED', ?, TRUE, ?, ?)""", line,
                        pick, orderLine, ++no, "LPN-" + line.toString().substring(0, 8), UUID.randomUUID(),
                        Timestamp.from(picked));
            }
            jdbc.execute("SET LOCAL session_replication_role = origin");
        });
        return lines;
    }

    private List<UUID> queue() {
        PageResponse<PickLists.PutBackRow> page = WithCurrentUser.call(TestUsers.admin(), DataScope.ALL,
                () -> pickLists.putBacks(warehouse, 0, 200, TestUsers.admin()));
        return page.items().stream().map(PickLists.PutBackRow::lineId).toList();
    }

    @Test
    @DisplayName("the task cancelled earliest comes first, even when its cartons were picked last; a task's own cartons "
            + "go by when they were picked")
    void earliestCancellationFirst() {
        // A: picked first, cancelled last.  B: picked last, cancelled first.  C: between, two cartons.
        List<UUID> a = cancelledTask(base.plus(3, ChronoUnit.HOURS), base);
        List<UUID> b = cancelledTask(base.plus(1, ChronoUnit.HOURS), base.plus(2, ChronoUnit.HOURS));
        List<UUID> c = cancelledTask(base.plus(2, ChronoUnit.HOURS), base.plus(40, ChronoUnit.MINUTES),
                base.plus(30, ChronoUnit.MINUTES));

        assertThat(queue()).as("B (cancelled first), then C's two by pick time, then A")
                .containsExactly(b.get(0), c.get(1), c.get(0), a.get(0));
    }

    @Test
    @DisplayName("a page of the queue counts the same cartons the lines of it say: the ordering does not touch the count")
    void theCountIsUntouchedByTheOrdering() {
        cancelledTask(base.plus(1, ChronoUnit.HOURS), base);
        cancelledTask(base.plus(2, ChronoUnit.HOURS), base);

        PageResponse<PickLists.PutBackRow> page = WithCurrentUser.call(TestUsers.admin(), DataScope.ALL,
                () -> pickLists.putBacks(warehouse, 0, 1, TestUsers.admin()));

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().overflowLocationCodes()).as("no carton of stock: nowhere to offer").isEmpty();
        // The row names its task by number (drawn by tg_pick_task_number for a row written without one) and its warehouse.
        assertThat(page.items().getFirst().taskNumber()).matches("PK-\\d{8}-\\d{4,}");
        assertThat(page.items().getFirst().warehouseName()).isEqualTo("Put-back queue");
        assertThat(page.items().getFirst().warehouseCode()).startsWith("Q");
    }
}
