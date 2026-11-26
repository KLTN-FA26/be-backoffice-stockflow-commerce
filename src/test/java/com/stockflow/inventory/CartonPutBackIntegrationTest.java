package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;

import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.inventory.consumption.PutBackCommand;
import com.stockflow.support.CartonRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.Holds;
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
 * A picked carton put back after its order was cancelled, against a real PostgreSQL (team plan 4C-4; kltn-docs 07
 * BR-05, 20 §4.6): it comes back whole - the very carton, CONSUMED to IN_STOCK, AVAILABLE, at the bin it is put on -
 * and keeps its lot and its FIFO date, so first in, first out never loses the age of stock (ADR-0011: the lot and the
 * receipt date are written when the carton goes into stock and never after). Once per pick line. And a carton is
 * never received in the future ({@code tg_stock_package_lot}).
 *
 * <p>Took the place of {@code SameReceiptMergeIntegrationTest}: the same put-backs, when a carton coming home meant
 * raising its emptied stock row under a marker the FIFO trigger had to be shown. There is no row to raise any more.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class CartonPutBackIntegrationTest {

    private static final String SHELF = "HCM-A01-1-B";
    private static final String OTHER_BIN = "HCM-A02-2-A";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired InventoryService inventory;
    @Autowired PickConsumption pickConsumption;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private String lot;
    private UUID clerk;

    @BeforeEach
    void setUp() {
        sku = "FIFO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        lot = "LOT-" + UUID.randomUUID().toString().substring(0, 6);
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "FIFO fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "FIFO fixture");
        });
    }

    /** Cartons of {@code factor} of the receipt at the shelf: what an order is given (team plan 4C-2). */
    private void cartons(int count, int factor) {
        tx.executeWithoutResult(s -> CartonRows.stockOfUnit(jdbc, sku, CartonRows.unitOf(jdbc, sku, factor), SHELF,
                count, lot, RECEIVED, "AVAILABLE"));
    }

    /** The carton a hold is in, by its LPN: what a put-back is scanned by. */
    private String lpnOf(UUID hold) {
        return jdbc.queryForObject("""
                select p.lpn from inventory.stock_package p
                  join inventory.stock_reservation r on r.stock_package_id = p.id where r.id = ?""", String.class, hold);
    }

    /** Each carton of the SKU: where it is, as what, whether an order holds it, its lot and its FIFO date; in LPN order. */
    private List<Map<String, Object>> cartonRows() {
        return jdbc.queryForList("""
                select p.lpn, p.status as carton_status, p.location_code as carton_location, p.stock_status,
                       (select count(*) from inventory.stock_reservation r
                         where r.stock_package_id = p.id and r.status = 'HELD') as live_holds,
                       l.lot_number, p.received_at
                  from inventory.stock_package p
                  join inventory.inventory_items i on i.id = p.inventory_item_id
                  join inventory.stock_lot l on l.id = p.lot_id
                 where i.sku = ? order by p.lpn""", sku);
    }

    private UUID confirmedOrder() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, contract_signed_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), NOW())""", order,
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

    @Test
    @DisplayName("picked FIFO cartons put back go home whole, onto the bin they left, once per line")
    void putBackGoesHomeWholeOncePerLine() {
        cartons(2, 3);
        UUID order = confirmedOrder();
        List<UUID> holds = tx.execute(s -> {
            UUID first = Holds.units(inventory, order, sku, "HCM", 3);
            UUID second = Holds.units(inventory, order, sku, "HCM", 3);
            return allocated(order, List.of(first, second));
        });
        List<String> lpns = holds.stream().map(this::lpnOf).toList();
        List<UUID> lines = List.of(Identifiers.newId(), Identifiers.newId());
        for (int i = 0; i < holds.size(); i++) {
            UUID hold = holds.get(i);
            UUID line = lines.get(i);
            tx.executeWithoutResult(s -> pickConsumption.consumeForPick(hold, line, clerk));
        }
        assertThat(cartonRows()).extracting(row -> row.get("carton_status") + ":" + row.get("stock_status"))
                .as("picked: out of stock, with no stock status").containsExactly("CONSUMED:null", "CONSUMED:null");

        // The order was cancelled after picking: each carton comes home, the very carton it was.
        tx.executeWithoutResult(s -> pickConsumption.putBack(
                new PutBackCommand(lines.get(0), holds.get(0), lpns.get(0), SHELF, clerk)));
        tx.executeWithoutResult(s -> pickConsumption.putBack(
                new PutBackCommand(lines.get(1), holds.get(1), lpns.get(1), SHELF, clerk)));
        // The same line again changes nothing.
        tx.executeWithoutResult(s -> pickConsumption.putBack(
                new PutBackCommand(lines.get(0), holds.get(0), lpns.get(0), SHELF, clerk)));

        assertThat(cartonRows()).hasSize(2).allSatisfy(row -> {
            assertThat(row).containsEntry("carton_status", "IN_STOCK")
                    .containsEntry("stock_status", "AVAILABLE").containsEntry("live_holds", 0L)
                    .containsEntry("lot_number", lot);
            assertThat(((java.sql.Timestamp) row.get("received_at")).toInstant()).as("the receipt's age is kept")
                    .isEqualTo(RECEIVED);
        });
        Map<String, String> binOf = new java.util.HashMap<>();
        cartonRows().forEach(row -> binOf.put((String) row.get("lpn"), (String) row.get("carton_location")));
        assertThat(binOf).containsEntry(lpns.get(0), SHELF).containsEntry(lpns.get(1), SHELF);
        assertThat(cartonRows()).as("the two cartons that left, no third").hasSize(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from inventory.stock_movement
                 where movement_type = 'PUT_BACK' and reference_type = 'PICK_LINE' and reference_id in (?, ?)
                   and stock_package_id is not null""",
                Integer.class, lines.get(0), lines.get(1))).as("one per line, naming its carton").isEqualTo(2);
    }

    @Test
    @DisplayName("put back onto a bin holding another carton of the same receipt: two cartons side by side, each itself")
    void putBackLeavesOtherCartonsAlone() {
        cartons(1, 5);
        UUID order = confirmedOrder();
        UUID hold = tx.execute(s -> {
            UUID id = Holds.units(inventory, order, sku, "HCM", 5);
            return allocated(order, List.of(id)).getFirst();
        });
        String picked = lpnOf(hold);
        UUID line = Identifiers.newId();
        tx.executeWithoutResult(s -> pickConsumption.consumeForPick(hold, line, clerk));
        // After the pick, so the hold was drawn from the shelf: a carton of 5, of the very receipt, on the target.
        UUID carton = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            UUID unit = CartonRows.unitOf(jdbc, sku, 5);
            jdbc.update("""
                    insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                        counted_at, location_code, status, stock_status, lot_id, received_at)
                    values (?, ?, ?, ?, 5, now(), ?, 'IN_STOCK', 'AVAILABLE', ?, ?)""",
                    carton, "LPN-" + sku, item, unit, SHELF, CartonRows.lot(jdbc, sku, lot, RECEIVED),
                    java.sql.Timestamp.from(RECEIVED));
        });

        tx.executeWithoutResult(s -> pickConsumption.putBack(
                new PutBackCommand(line, hold, picked, SHELF, clerk)));

        assertThat(jdbc.queryForList("""
                select p.nominal_qty from inventory.stock_package p
                  join inventory.inventory_items i on i.id = p.inventory_item_id
                 where i.sku = ? and p.location_code = ? and p.status = 'IN_STOCK' order by p.id""", Integer.class,
                sku, SHELF)).as("nothing merges: two cartons in stock, each its own five").containsExactly(5, 5);
        assertThat(cartonRows()).extracting(row -> row.get("carton_status") + ":" + row.get("carton_location"))
                .containsExactly("IN_STOCK:" + SHELF, "IN_STOCK:" + SHELF);
    }

    @Test
    @DisplayName("put back onto ANOTHER bin: the carton goes there whole")
    void putBackOntoAnotherBin() {
        cartons(1, 3);
        UUID order = confirmedOrder();
        UUID hold = tx.execute(s -> {
            UUID id = Holds.units(inventory, order, sku, "HCM", 3);
            return allocated(order, List.of(id)).getFirst();
        });
        String lpn = lpnOf(hold);
        UUID line = Identifiers.newId();
        tx.executeWithoutResult(s -> pickConsumption.consumeForPick(hold, line, clerk));

        tx.executeWithoutResult(s -> pickConsumption.putBack(new PutBackCommand(line, hold, lpn, OTHER_BIN, clerk)));

        assertThat(cartonRows()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("carton_status", "IN_STOCK").containsEntry("carton_location", OTHER_BIN)
                .containsEntry("stock_status", "AVAILABLE").containsEntry("lot_number", lot));
    }

    @Test
    @DisplayName("a receipt dated an hour ahead is still refused; the clock skew between application and database is not")
    void receiptTime() {
        // A carton just received.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> CartonRows.carton(jdbc, sku, "HCM-RCV01", 2, lot + "-L",
                Instant.now().plusSeconds(3600), "INBOUND")))
                .hasMessageContaining("invalid receipt date");
        tx.executeWithoutResult(s -> CartonRows.carton(jdbc, sku, "HCM-RCV01", 2, lot + "-S",
                Instant.now().plusSeconds(2), "INBOUND"));
        assertThat(jdbc.queryForObject("""
                select count(*) from inventory.stock_package p join inventory.stock_lot l on l.id = p.lot_id
                 where l.sku = ? and l.lot_number = ?""", Integer.class, sku, lot + "-S")).isEqualTo(1);
    }
}
