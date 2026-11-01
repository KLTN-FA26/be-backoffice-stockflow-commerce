package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.HoldView;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-473 picking by carton, against PostgreSQL: the release allocates whole cartons for the round part and the
 * opened carton for the rest, and every hold says which carton it is in and whether it is that whole carton - what
 * fulfillment copies onto the pick line the picker scans.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class CartonAllocationIntegrationTest {

    private static final String SHELF = "HCM-A01-1-B";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private String sku;
    private UUID item;
    private UUID unit;

    @BeforeEach
    void setUp() {
        sku = "PKA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Carton fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Carton fixture");
            jdbc.update("update inventory.inventory_items set removal_strategy='FIFO', policy_configured=true"
                    + " where sku=?", sku);
            item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            unit = Identifiers.newId();
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', 10)", unit, item);
        });
    }

    /** A carton of 10 counted in, holding {@code left} on the shelf: sealed when {@code left} is 10. */
    private String carton(String name, int left) {
        String lpn = "LPN-" + sku + "-" + name;
        tx.executeWithoutResult(s -> {
            UUID id = Identifiers.newId();
            jdbc.update("""
                    insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                        received_qty, location_code, status, unopened) values (?, ?, ?, ?, 10, 10, ?, 'IN_STOCK', ?)""",
                    id, lpn, item, unit, SHELF, left == 10);
            stockRow(left, id);
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

    private UUID confirmedOrder() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, paid_amount, paid_in_full_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), 1, NOW())""", order,
                "SO-20991227-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)),
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        return order;
    }

    /**
     * Reserves each of {@code quantities} - one order line apiece - for a new confirmed order and releases it to HCM;
     * returns every hold it made.
     */
    private List<HoldView> released(int... quantities) {
        UUID order = confirmedOrder();
        List<UUID> holds = tx.execute(s -> {
            for (int quantity : quantities) {
                inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), quantity, order));
            }
            inventory.pinReservations(order);
            return inventory.allocate(new AllocateStockCommand(order, "HCM")).allocations().stream()
                    .flatMap(allocation -> allocation.holdIds().stream()).toList();
        });
        return inventory.holds(holds);
    }

    private static String describe(HoldView hold) {
        return hold.lpn() + ":" + hold.quantity() + (hold.wholeCarton() ? ":whole" : "");
    }

    @Test
    @DisplayName("two sealed cartons whole and the opened one for the rest; the loose units stay")
    void allocationTakesWholeCartonsThenTheOpenedOne() {
        String a = carton("A", 10);
        String b = carton("B", 10);
        String c = carton("C", 4);
        loose(3);

        assertThat(released(24)).extracting(CartonAllocationIntegrationTest::describe)
                .containsExactlyInAnyOrder(a + ":10:whole", b + ":10:whole", c + ":4");
    }

    @Test
    @DisplayName("a second line of the same SKU still sees which cartons are whole: no sealed carton is broken")
    void secondLineOfTheSameSkuKeepsTheCartonOrder() {
        carton("A", 10);
        carton("B", 10);
        carton("C", 10);
        String d = carton("D", 4);

        List<String> holds = released(10, 13).stream().map(CartonAllocationIntegrationTest::describe).toList();

        // which two of the three equal cartons go whole is immaterial; that none is broken is the point
        assertThat(holds).hasSize(3).contains(d + ":3");
        assertThat(holds).filteredOn(hold -> !hold.startsWith(d)).allMatch(hold -> hold.endsWith(":10:whole"));
    }

    @Test
    @DisplayName("a sealed carton another order holds part of is not handed out as whole")
    void partlyHeldCartonIsNotWhole() {
        String a = carton("A", 10);

        assertThat(released(3)).extracting(CartonAllocationIntegrationTest::describe).containsExactly(a + ":3");
        assertThat(released(7)).extracting(CartonAllocationIntegrationTest::describe).containsExactly(a + ":7");
    }

    @Test
    @DisplayName("a hold on loose units names no carton")
    void looseHoldHasNoLpn() {
        loose(5);

        assertThat(released(2)).extracting(CartonAllocationIntegrationTest::describe).containsExactly("null:2");
    }
}
