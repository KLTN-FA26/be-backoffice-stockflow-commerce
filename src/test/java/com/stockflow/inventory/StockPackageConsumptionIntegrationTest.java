package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.IssueReservedStockCommand;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.consumption.PickConsumption;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-473 (#141) against a real PostgreSQL and its commit-time checks (V20261020000200): units taken out of a
 * carton open it, the last unit closes it CONSUMED, the ledger names the carton the units left, and two picks
 * from one carton are serialised on it. Before #141 the first partial pick of an unopened carton was refused at
 * commit as a 500 ("unopened with N counted in, but holds M").
 */
@IntegrationTest
@Import(PostgresContainer.class)
class StockPackageConsumptionIntegrationTest {

    private static final String SHELF = "HCM-A01-1-B";
    private static final String PRINT_SHOP = "HCM-PRD01";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired InventoryService inventory;
    @Autowired PickConsumption pickConsumption;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private UUID item;
    private UUID carton;
    private UUID clerk;

    @BeforeEach
    void setUp() {
        sku = "PKC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Carton fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Carton fixture");
            jdbc.update("update inventory.inventory_items set removal_strategy='FIFO', policy_configured=true"
                    + " where sku=?", sku);
            item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            carton = Identifiers.newId();
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', 5)", carton, item);
        });
    }

    /** An unopened carton of 5 on the shelf, counted in at 5: the only stock of the SKU unless another is added. */
    private UUID cartonOnShelf(String name) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                        received_qty, location_code, status) values (?, ?, ?, ?, 5, 5, ?, 'IN_STOCK')""",
                    id, "LPN-" + sku + "-" + name, item, carton, SHELF);
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, received_at, on_hand, reserved, status,
                        version, created_at, stock_package_id) values (?, ?, ?, ?, 5, 0, 'AVAILABLE', 0, now(), ?)""",
                    Identifiers.newId(), sku, SHELF, java.sql.Timestamp.from(RECEIVED), id);
        });
        return id;
    }

    private UUID confirmedOrder() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, paid_amount, paid_in_full_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), 1, NOW())""", order,
                "SO-20991227-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)),
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        return order;
    }

    private UUID hold(UUID order, int quantity) {
        return tx.execute(s -> {
            UUID id = inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), quantity, order))
                    .reservationIds().getFirst();
            inventory.pinReservations(order);
            return id;
        });
    }

    private Map<String, Object> carton(UUID id) {
        return jdbc.queryForMap("""
                select p.status, p.unopened, p.closed_at is not null as closed,
                       (select coalesce(sum(s.on_hand), 0) from inventory.stock_item s where s.stock_package_id = p.id) as held
                  from inventory.stock_package p where p.id = ?""", id);
    }

    @Test
    @DisplayName("a pick out of a carton opens it, the last unit closes it CONSUMED, and the ledger names the carton")
    void pickOpensThenEmpties() {
        UUID box = cartonOnShelf("A");
        UUID order = confirmedOrder();

        UUID first = hold(order, 3);
        tx.executeWithoutResult(s -> pickConsumption.consumeForPick(first, Identifiers.newId(), clerk));
        assertThat(carton(box)).containsEntry("status", "IN_STOCK").containsEntry("unopened", false)
                .containsEntry("closed", false).containsEntry("held", 2L);

        UUID rest = hold(order, 2);
        tx.executeWithoutResult(s -> pickConsumption.consumeForPick(rest, Identifiers.newId(), clerk));
        assertThat(carton(box)).containsEntry("status", "CONSUMED").containsEntry("closed", true)
                .containsEntry("held", 0L);

        assertThat(jdbc.queryForList("""
                select from_stock_package_id from inventory.stock_movement
                 where sku = ? and movement_type = 'PICK'""", UUID.class, sku)).containsExactly(box, box);
    }

    @Test
    @DisplayName("a hold issued to the print shop out of a carton leaves it loose there; the carton is emptied")
    void issueLeavesTheCarton() {
        UUID box = cartonOnShelf("I");
        UUID order = confirmedOrder();
        UUID hold = hold(order, 5);

        tx.executeWithoutResult(s -> inventory.issueReserved(new IssueReservedStockCommand(hold, order,
                Identifiers.newId(), PRINT_SHOP, clerk)));

        assertThat(carton(box)).containsEntry("status", "CONSUMED").containsEntry("held", 0L);
        assertThat(jdbc.queryForList("""
                select on_hand, status, stock_package_id from inventory.stock_item
                 where sku = ? and location_code = ?""", sku, PRINT_SHOP)).singleElement().satisfies(row -> {
                    assertThat(row).containsEntry("on_hand", 5).containsEntry("status", "IN_PRODUCTION");
                    assertThat(row.get("stock_package_id")).as("arrives loose").isNull();
                });
        assertThat(jdbc.queryForObject("""
                select from_stock_package_id from inventory.stock_movement
                 where sku = ? and movement_type = 'MOVE' and to_location_code = ?""", UUID.class, sku, PRINT_SHOP))
                .isEqualTo(box);
    }

    @Test
    @DisplayName("two picks out of one carton at once are serialised on the carton: both commit, nothing is refused")
    void concurrentPicksAreSerialised() throws Exception {
        UUID box = cartonOnShelf("C");
        UUID order = confirmedOrder();
        UUID one = hold(order, 2);
        UUID two = hold(order, 2);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> {
            await(start);
            tx.executeWithoutResult(s -> pickConsumption.consumeForPick(one, Identifiers.newId(), clerk));
        });
        CompletableFuture<Void> b = CompletableFuture.runAsync(() -> {
            await(start);
            tx.executeWithoutResult(s -> pickConsumption.consumeForPick(two, Identifiers.newId(), clerk));
        });
        start.countDown();
        CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);

        assertThat(carton(box)).containsEntry("status", "IN_STOCK").containsEntry("unopened", false)
                .containsEntry("held", 1L);
        assertThat(jdbc.queryForList("select status from inventory.stock_reservation where id in (?, ?)",
                String.class, one, two)).containsOnly("CONSUMED");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
