package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.IssueReservedStockCommand;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261014000450 against a real PostgreSQL: units of one FIFO receipt layer may be moved into a layer of the
 * same receipt elsewhere, in parts — two moves to the same bin, two holds issued to the print shop — keeping
 * the receipt time, lot and total; replenishing an existing FIFO layer (a FOUND adjustment) is still refused,
 * and so is a receipt dated more than a minute ahead of the database clock.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class SameReceiptMergeIntegrationTest {

    private static final String SHELF = "HCM-A01-1-B";
    private static final String OTHER_BIN = "HCM-A02-2-A";
    private static final String PRINT_SHOP = "HCM-PRD01";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired InventoryService inventory;
    @Autowired StockOperations operations;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private String lot;
    private UUID clerk;
    private UUID approver;

    @BeforeEach
    void setUp() {
        sku = "FIFO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        lot = "LOT-" + UUID.randomUUID().toString().substring(0, 6);
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        approver = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "FIFO fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "FIFO fixture");
            jdbc.update("update inventory.inventory_items set lot_tracked=true, removal_strategy='FIFO',"
                    + " policy_configured=true where sku=?", sku);
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, lot_number, received_at, on_hand, reserved,
                        status, version, created_at) values (?, ?, ?, ?, ?, 10, 0, 'AVAILABLE', 0, now())""",
                    Identifiers.newId(), sku, SHELF, lot, java.sql.Timestamp.from(RECEIVED));
        });
    }

    private void move(int quantity, String to) {
        tx.executeWithoutResult(s -> inventory.move(new MoveStockCommand(UUID.randomUUID(), new Sku(sku), lot, SHELF,
                to, quantity, MoveReference.MOVE_TASK, Identifiers.newId(), clerk, RECEIVED)));
    }

    private List<Map<String, Object>> layers() {
        return jdbc.queryForList("""
                select location_code, lot_number, received_at, on_hand, reserved, status from inventory.stock_item
                 where sku = ? order by location_code""", sku);
    }

    @Test
    @DisplayName("two moves of one FIFO receipt to the same bin merge into one layer of that receipt; nothing is created or lost")
    void secondMoveMerges() {
        move(3, OTHER_BIN);
        move(2, OTHER_BIN);

        assertThat(layers()).extracting(row -> row.get("location_code") + ":" + row.get("on_hand"))
                .containsExactly(SHELF + ":5", OTHER_BIN + ":5");
        assertThat(layers()).allSatisfy(row -> {
            assertThat(row).containsEntry("lot_number", lot);
            assertThat(((java.sql.Timestamp) row.get("received_at")).toInstant()).as("the receipt's age is kept")
                    .isEqualTo(RECEIVED);
        });
    }

    @Test
    @DisplayName("replenishing an existing FIFO layer is still refused: a FOUND adjustment cannot be posted onto it")
    void replenishmentStillRefused() {
        var adjustment = tx.execute(s -> operations.requestAdjustment(new RequestAdjustmentCommand(SHELF,
                new Sku(sku), lot, 2, StockAdjustmentReason.FOUND, "found two", clerk)));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> operations.approve(adjustment.adjustmentId(), approver)))
                .hasMessageContaining("FIFO replenishment requires a new receipt layer");
        assertThat(layers()).singleElement().satisfies(row -> assertThat(row).containsEntry("on_hand", 10));
    }

    @Test
    @DisplayName("two holds of one FIFO receipt issued to the print shop land in one layer there, both still held")
    void secondIssueMerges() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, paid_amount, paid_in_full_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), 1, NOW())""", order,
                "SO-20991226-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)),
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        List<UUID> holds = tx.execute(s -> {
            List<UUID> ids = new java.util.ArrayList<>();
            ids.addAll(inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), 3, order)).reservationIds());
            ids.addAll(inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), 4, order)).reservationIds());
            inventory.pinReservations(order);
            return ids;
        });
        for (UUID hold : holds) {
            tx.executeWithoutResult(s -> inventory.issueReserved(new IssueReservedStockCommand(hold, order,
                    Identifiers.newId(), PRINT_SHOP, clerk)));
        }

        assertThat(layers()).extracting(row -> row.get("location_code") + ":" + row.get("on_hand") + ":"
                        + row.get("reserved") + ":" + row.get("status"))
                .containsExactly(SHELF + ":3:0:AVAILABLE", PRINT_SHOP + ":7:7:IN_PRODUCTION");
    }

    @Test
    @DisplayName("a receipt dated an hour ahead is still refused; the clock skew between application and database is not")
    void receiptTime() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_item (id, sku, location_code, lot_number, received_at, on_hand, reserved,
                    status, version, created_at) values (?, ?, ?, ?, clock_timestamp() + interval '1 hour', 1, 0,
                    'INBOUND', 0, now())""", Identifiers.newId(), sku, "HCM-RCV01", lot + "-L")))
                .hasMessageContaining("Invalid receipt/expiry date");
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_item (id, sku, location_code, lot_number, received_at, on_hand, reserved,
                    status, version, created_at) values (?, ?, ?, ?, clock_timestamp() + interval '2 seconds', 1, 0,
                    'INBOUND', 0, now())""", Identifiers.newId(), sku, "HCM-RCV01", lot + "-S"));
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_item where sku = ? and lot_number = ?",
                Integer.class, sku, lot + "-S")).isEqualTo(1);
    }
}
