package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;

import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.inventory.consumption.PutBackCommand;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.support.CartonRows;
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261017000450 against a real PostgreSQL: units of one FIFO receipt layer may be put into a layer of the
 * same receipt elsewhere, in parts — picked goods put back after a cancellation — keeping the receipt time, lot and
 * total; replenishing an existing FIFO layer is still refused (a FOUND adjustment is posted into a new layer instead),
 * and so is a receipt dated more than a minute ahead of the database clock. A put-back never merges into a carton's
 * row. Orders are given whole cartons (team plan 4C-2), so the goods picked come from cartons of the receipt; a
 * put-back still lands loose until team plan 4C-4 puts the carton back whole.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class SameReceiptMergeIntegrationTest {

    private static final String SHELF = "HCM-A01-1-B";
    private static final String OTHER_BIN = "HCM-A02-2-A";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired InventoryService inventory;
    @Autowired StockOperations operations;
    @Autowired PickConsumption pickConsumption;
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

    /** Cartons of {@code factor} of the receipt at the shelf: what an order is given (team plan 4C-2). */
    private void cartons(int count, int factor) {
        tx.executeWithoutResult(s -> CartonRows.stock(jdbc, sku, SHELF, count, factor, lot, RECEIVED));
    }

    /** A loose layer of the receipt at {@code location}: what a put-back merges into. */
    private void looseLayer(String location, int units) {
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_item (id, sku, location_code, lot_number, received_at, on_hand, reserved,
                    status, version, created_at) values (?, ?, ?, ?, ?, ?, 0, 'AVAILABLE', 0, now())""",
                Identifiers.newId(), sku, location, lot, java.sql.Timestamp.from(RECEIVED), units));
    }

    /** The loose layers; the cartons' rows are not where a put-back goes. */
    private List<Map<String, Object>> layers() {
        return jdbc.queryForList("""
                select location_code, lot_number, received_at, on_hand, reserved, status from inventory.stock_item
                 where sku = ? and stock_package_id is null order by location_code""", sku);
    }

    /**
     * Was {@code replenishmentStillRefused}, which pinned an approved FOUND failing on the database. SCRUM-146 (FIFO
     * write-up ruling) changed the service, not the rule: a FIFO layer is still never raised, so a FOUND is posted
     * into a new layer beside it, received at approval - units found have no provable receipt and must not be as old
     * as the layer, first out. The trigger is unchanged, which the direct raise below still proves.
     */
    @Test
    @DisplayName("a FOUND on a FIFO layer opens a new layer received at approval; raising the old row is still refused")
    void foundOnFifoOpensANewLayer() {
        var adjustment = tx.execute(s -> operations.requestAdjustment(new RequestAdjustmentCommand(SHELF,
                new Sku(sku), lot, 2, StockAdjustmentReason.FOUND, "found two", clerk)));

        tx.executeWithoutResult(s -> operations.approve(adjustment.adjustmentId(), approver));

        List<Map<String, Object>> layers = jdbc.queryForList("""
                select location_code, lot_number, received_at, on_hand from inventory.stock_item
                 where sku = ? order by received_at""", sku);
        assertThat(layers).hasSize(2);
        assertThat(layers.get(0)).as("the counted layer is not raised").containsEntry("on_hand", 10);
        assertThat(((java.sql.Timestamp) layers.get(0).get("received_at")).toInstant()).isEqualTo(RECEIVED);
        assertThat(layers.get(1)).as("the found units, as a layer of their own at the same bin and lot")
                .containsEntry("on_hand", 2).containsEntry("location_code", SHELF).containsEntry("lot_number", lot);
        assertThat(((java.sql.Timestamp) layers.get(1).get("received_at")).toInstant()).isAfter(RECEIVED);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                update inventory.stock_item set on_hand = on_hand + 2 where sku = ? and received_at = ?""",
                sku, java.sql.Timestamp.from(RECEIVED))))
                .as("the database still refuses replenishing an existing FIFO layer")
                .hasMessageContaining("FIFO replenishment requires a new receipt layer");
        assertThat(jdbc.queryForObject("select on_hand from inventory.stock_item where sku = ? and received_at = ?",
                Integer.class, sku, java.sql.Timestamp.from(RECEIVED))).isEqualTo(10);
    }

    private UUID confirmedOrder() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, paid_amount, paid_in_full_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), 1, NOW())""", order,
                "SO-20991226-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)),
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        return order;
    }

    /**
     * SCRUM-152: a checkout reservation holds the SKU on no bin; the order's release allocates it. Each reservation's
     * one allocation here (a single FIFO layer serves it), in the order the reservations are given.
     */
    private List<UUID> allocated(UUID order, List<UUID> reservations) {
        java.util.Map<UUID, UUID> holdOf = new java.util.HashMap<>();
        inventory.allocate(new com.stockflow.inventory.api.AllocateStockCommand(order, "HCM")).allocations()
                .forEach(allocation -> holdOf.put(allocation.reservationId(), allocation.holdIds().getFirst()));
        return reservations.stream().map(holdOf::get).toList();
    }

    /** "location:status:unopened:on_hand" of each carton of the SKU's receipt, by location. */
    private List<String> cartonsOfTheReceipt() {
        return jdbc.queryForList("""
                select p.location_code || ':' || p.status || ':' || p.unopened || ':' || s.on_hand
                  from inventory.stock_package p join inventory.stock_item s on s.stock_package_id = p.id
                 where s.sku = ? order by p.location_code, p.lpn""", String.class, sku);
    }

    @Test
    @DisplayName("picked FIFO cartons put back come back whole as themselves - the bin they left or another - keeping"
            + " their receipt's age; once per line, and no loose layer is raised")
    void putBackRestocksTheCarton() {
        cartons(2, 3);
        UUID order = confirmedOrder();
        List<UUID> holds = tx.execute(s -> {
            UUID first = inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), 3, order))
                    .reservationIds().getFirst();
            UUID second = inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), 3, order))
                    .reservationIds().getFirst();
            inventory.pinReservations(order);
            return allocated(order, List.of(first, second));
        });
        List<UUID> lines = List.of(Identifiers.newId(), Identifiers.newId());
        for (int i = 0; i < holds.size(); i++) {
            UUID hold = holds.get(i);
            UUID line = lines.get(i);
            tx.executeWithoutResult(s -> pickConsumption.consumeForPick(hold, line, clerk));
        }
        looseLayer(OTHER_BIN, 1);
        assertThat(cartonsOfTheReceipt()).containsExactly(SHELF + ":CONSUMED:false:0", SHELF + ":CONSUMED:false:0");

        // The order was cancelled after picking: each carton goes back whole, the FIFO layer merge allowed by name.
        tx.executeWithoutResult(s -> pickConsumption.putBack(
                new PutBackCommand(lines.get(0), holds.get(0), sku, lot, SHELF, 3, clerk)));
        tx.executeWithoutResult(s -> pickConsumption.putBack(
                new PutBackCommand(lines.get(1), holds.get(1), sku, lot, OTHER_BIN, 3, clerk)));
        // The same line again changes nothing.
        tx.executeWithoutResult(s -> pickConsumption.putBack(
                new PutBackCommand(lines.get(0), holds.get(0), sku, lot, SHELF, 3, clerk)));

        assertThat(cartonsOfTheReceipt()).containsExactlyInAnyOrder(SHELF + ":IN_STOCK:true:3", OTHER_BIN + ":IN_STOCK:true:3");
        assertThat(layers()).extracting(row -> row.get("location_code") + ":" + row.get("on_hand"))
                .as("the loose layers untouched").containsExactlyInAnyOrder(OTHER_BIN + ":1", SHELF + ":10");
        assertThat(jdbc.queryForList("""
                select received_at from inventory.stock_item where sku = ? and stock_package_id is not null""",
                java.sql.Timestamp.class, sku)).allSatisfy(at -> assertThat(at.toInstant())
                .as("the receipt's age is kept").isEqualTo(RECEIVED));
        assertThat(jdbc.queryForObject("""
                select count(*) from inventory.stock_movement
                 where movement_type = 'PUT_BACK' and reference_type = 'PICK_LINE' and reference_id in (?, ?)
                   and to_stock_package_id is not null""",
                Integer.class, lines.get(0), lines.get(1))).as("one line each, naming its carton").isEqualTo(2);
    }

    @Test
    @DisplayName("a carton line is put back whole: part of it is STOCK_PACKAGE_TAKEN_WHOLE, and nothing changes")
    void putBackIsWholeCarton() {
        cartons(1, 5);
        UUID order = confirmedOrder();
        UUID hold = tx.execute(s -> {
            UUID id = inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), 5, order))
                    .reservationIds().getFirst();
            inventory.pinReservations(order);
            return allocated(order, List.of(id)).getFirst();
        });
        UUID line = Identifiers.newId();
        tx.executeWithoutResult(s -> pickConsumption.consumeForPick(hold, line, clerk));

        assertThat(catchThrowableOfType(com.stockflow.common.error.BusinessException.class,
                () -> tx.executeWithoutResult(s -> pickConsumption.putBack(
                        new PutBackCommand(line, hold, sku, lot, OTHER_BIN, 3, clerk)))).errorCode())
                .isEqualTo(com.stockflow.common.error.ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
        assertThat(cartonsOfTheReceipt()).containsExactly(SHELF + ":CONSUMED:false:0");
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
