package com.stockflow.fulfillment;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261028003000 (SCRUM-473 picking by carton): a pick line names the carton of the hold it consumes - that carton's
 * LPN exactly, and none for a hold on loose units - and lines planned before the migration are given theirs.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class PickLineCartonMigrationTest {

    /** The migration's backfill, verbatim: kept in step with V20261028003000 by hand. */
    private static final String BACKFILL = """
            UPDATE fulfillment.pick_line l
               SET lpn = p.lpn
              FROM inventory.stock_reservation r
              JOIN inventory.stock_item s ON s.id = r.stock_item_id
              JOIN inventory.stock_package p ON p.id = s.stock_package_id
             WHERE r.id = l.reservation_id
               AND l.lpn IS NULL""";

    private static final String SHELF = "HCM-A01-1-B";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private String sku;
    private UUID item;
    private UUID unit;
    private UUID pick;
    private UUID orderLine;
    private UUID order;

    @BeforeEach
    void setUp() {
        sku = "PKL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        order = Identifiers.newId();
        orderLine = Identifiers.newId();
        pick = Identifiers.newId();
        String number = "SO-20991227-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Carton fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Carton fixture");
            item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            unit = Identifiers.newId();
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', 10)", unit, item);
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                        currency, placed_at, version, created_at, paid_amount, paid_in_full_at)
                    VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), 1, NOW())""", order, number,
                    DemoData.CUSTOMER_ID, UUID.randomUUID());
            jdbc.update("INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency) "
                    + "VALUES (?, ?, ?, 20, 1000, 'VND')", orderLine, order, sku);
            jdbc.update("""
                    INSERT INTO fulfillment.pick (id, order_id, status, order_number, warehouse_id, created_at)
                    VALUES (?, ?, 'PICKING', ?, ?, NOW())""", pick, order, number, DemoData.WAREHOUSE_HCM);
        });
    }

    /** A sealed carton of 10 on the shelf; returns its LPN. */
    private String carton(String name) {
        String lpn = "LPN-" + sku + "-" + name;
        tx.executeWithoutResult(s -> {
            UUID id = Identifiers.newId();
            jdbc.update("""
                    insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                        received_qty, location_code, status) values (?, ?, ?, ?, 10, 10, ?, 'IN_STOCK')""",
                    id, lpn, item, unit, SHELF);
            stockRow(10, id);
        });
        return lpn;
    }

    private void loose(int quantity) {
        tx.executeWithoutResult(s -> stockRow(quantity, null));
    }

    private void stockRow(int onHand, UUID carton) {
        jdbc.update("""
                insert into inventory.stock_item (id, sku, location_code, received_at, on_hand, reserved, status,
                    version, created_at, stock_package_id) values (?, ?, ?, ?, ?, 0, 'AVAILABLE', 0, now(), ?)""",
                Identifiers.newId(), sku, SHELF, java.sql.Timestamp.from(RECEIVED), onHand, carton);
    }

    /** Reserves and allocates {@code quantity} for the order; returns the one hold it made. */
    private UUID hold(int quantity) {
        List<UUID> holds = tx.execute(s -> {
            inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), quantity, order));
            return inventory.allocate(new AllocateStockCommand(order, "HCM")).allocations().stream()
                    .flatMap(allocation -> allocation.holdIds().stream()).toList();
        });
        assertThat(holds).hasSize(1);
        return holds.getFirst();
    }

    private void line(UUID reservation, String lpn, boolean wholeCarton) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code,
                    sequence_no, allocated_qty, lpn, whole_carton)
                VALUES (?, ?, ?, ?, ?, ?, 1, 1, ?, ?)""",
                Identifiers.newId(), pick, orderLine, reservation, sku, SHELF, lpn, wholeCarton));
    }

    @Test
    @DisplayName("a line on a carton hold takes that carton's LPN")
    void lineOnACartonHoldTakesItsLpn() {
        String lpn = carton("A");
        UUID reservation = hold(10);

        line(reservation, lpn, true);

        assertThat(jdbc.queryForObject("select lpn from fulfillment.pick_line where reservation_id = ?",
                String.class, reservation)).isEqualTo(lpn);
    }

    @Test
    @DisplayName("naming another carton is refused")
    void wrongLpnIsRefused() {
        carton("A");
        String other = carton("B");
        UUID reservation = hold(10);
        String held = jdbc.queryForObject("""
                select p.lpn from inventory.stock_reservation r join inventory.stock_item s on s.id = r.stock_item_id
                  join inventory.stock_package p on p.id = s.stock_package_id where r.id = ?""", String.class, reservation);
        String wrong = held.equals(other) ? "LPN-" + sku + "-A" : other;

        assertThatThrownBy(() -> line(reservation, wrong, false)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining(held);
    }

    @Test
    @DisplayName("a hold on loose units names no carton")
    void lpnOnALooseHoldIsRefused() {
        String lpn = carton("A");
        loose(5);
        UUID reservation = hold(3);

        assertThatThrownBy(() -> line(reservation, lpn, false)).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a line on a carton hold must name the carton")
    void lineOnACartonHoldWithoutLpnIsRefused() {
        carton("A");
        UUID reservation = hold(4);

        assertThatThrownBy(() -> line(reservation, null, false)).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a whole-carton line names its carton")
    void wholeCartonNeedsAnLpn() {
        loose(5);
        UUID reservation = hold(5);

        assertThatThrownBy(() -> line(reservation, null, true)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_pick_line_whole_carton");
    }

    @Test
    @DisplayName("lines planned before the migration are given the LPN of their hold's carton")
    void backfillNamesTheCartonOfExistingLines() {
        String lpn = carton("A");
        UUID reservation = hold(10);
        tx.executeWithoutResult(s -> {
            jdbc.execute("ALTER TABLE fulfillment.pick_line DISABLE TRIGGER tg_pick_line_carton");
            line(reservation, null, false);
            // The line's deferred keys (fk_pick_line_damage_report, SCRUM-277) are checked now: PostgreSQL refuses an
            // ALTER TABLE while a trigger event of the table is still pending.
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.execute("ALTER TABLE fulfillment.pick_line ENABLE TRIGGER tg_pick_line_carton");
            jdbc.update(BACKFILL);
        });

        assertThat(jdbc.queryForObject("select lpn from fulfillment.pick_line where reservation_id = ?",
                String.class, reservation)).isEqualTo(lpn);
    }
}
