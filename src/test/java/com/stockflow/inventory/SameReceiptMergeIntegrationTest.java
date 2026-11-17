package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;

import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.inventory.consumption.PutBackCommand;
import com.stockflow.inventory.internal.service.StockOperations;
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
 * V20261017000450 against a real PostgreSQL: units of one FIFO receipt layer may be put back into a row of the
 * same receipt - picked goods put back after a cancellation - keeping the receipt time, lot and total; replenishing an
 * existing FIFO layer is still refused, and so is a receipt dated more than a minute ahead of the database clock. Stock
 * is whole cartons only (V20261117000100, review M-5): units found loose are refused, not posted into a new layer. Orders are given whole cartons (team plan
 * 4C-2) and a cancelled order's carton goes back whole (4C-4): its own emptied row takes its count again - the very
 * same-receipt raise the trigger allows - at the bin it left or at another, without ever merging into another carton's
 * row.
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

    /** The loose rows: none holds anything since V20261117000100; a carton goes back into its own row. */
    private List<Map<String, Object>> layers() {
        return jdbc.queryForList("""
                select location_code, lot_number, received_at, on_hand, reserved, status from inventory.stock_item
                 where sku = ? and stock_package_id is null order by location_code""", sku);
    }

    /** The carton a hold is in, by its LPN: what a put-back is scanned by. */
    private String lpnOf(UUID hold) {
        return jdbc.queryForObject("""
                select p.lpn from inventory.stock_package p join inventory.stock_item i on i.stock_package_id = p.id
                  join inventory.stock_reservation r on r.stock_item_id = i.id where r.id = ?""", String.class, hold);
    }

    /** Each carton of the SKU with its status, bin and its rows' on_hand, in LPN order. */
    private List<Map<String, Object>> cartonRows() {
        return jdbc.queryForList("""
                select p.lpn, p.status as carton_status, p.location_code as carton_location,
                       i.location_code, i.on_hand, i.reserved, i.status,
                       i.lot_number, i.received_at
                  from inventory.stock_package p join inventory.stock_item i on i.stock_package_id = p.id
                 where i.sku = ? order by p.lpn""", sku);
    }

    /**
     * Was {@code foundOnFifoOpensANewLayer}: a FOUND posted its units into a new loose layer beside the counted one.
     * Units in no carton no longer exist (V20261117000100, review M-5), so a FOUND of loose units is refused when asked
     * ({@code STOCK_NOT_IN_CARTON}); a carton found is restocked by counting it. The trigger is unchanged: raising the
     * carton's FIFO row in place is still refused.
     */
    @Test
    @DisplayName("a FOUND of loose units is refused (whole cartons only); raising a carton's FIFO row is still refused")
    void foundOfLooseUnitsIsRefused() {
        cartons(1, 10);

        assertThatThrownBy(() -> tx.execute(s -> operations.requestAdjustment(new RequestAdjustmentCommand(SHELF,
                new Sku(sku), lot, 2, StockAdjustmentReason.FOUND, "found two", clerk))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.STOCK_NOT_IN_CARTON);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                update inventory.stock_item set on_hand = on_hand + 2 where sku = ? and received_at = ?""",
                sku, java.sql.Timestamp.from(RECEIVED))))
                .as("the database still refuses replenishing an existing FIFO layer")
                .hasMessageContaining("FIFO replenishment requires a new receipt layer");
        assertThat(jdbc.queryForList("select on_hand from inventory.stock_item where sku = ?", Integer.class, sku))
                .containsExactly(10);
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

    /** "location:status:on_hand" of each carton of the SKU's receipt, by location. */
    private List<String> cartonsOfTheReceipt() {
        return jdbc.queryForList("""
                select p.location_code || ':' || p.status || ':' || s.on_hand
                  from inventory.stock_package p join inventory.stock_item s on s.stock_package_id = p.id
                 where s.sku = ? order by p.location_code, p.lpn""", String.class, sku);
    }

    @Test
    @DisplayName("picked FIFO cartons put back go home whole, onto the bin they left, once per line")
    void putBackMerges() {
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
        assertThat(cartonRows()).extracting(row -> row.get("carton_status") + ":" + row.get("on_hand"))
                .containsExactly("CONSUMED:0", "CONSUMED:0");

        // The order was cancelled after picking: a FIFO row is never raised, except by its own carton coming home
        // (V20261017000450 refused the same-receipt raise before the fix: 409).
        tx.executeWithoutResult(s -> pickConsumption.putBack(
                new PutBackCommand(lines.get(0), holds.get(0), lpns.get(0), SHELF, clerk)));
        tx.executeWithoutResult(s -> pickConsumption.putBack(
                new PutBackCommand(lines.get(1), holds.get(1), lpns.get(1), SHELF, clerk)));
        // The same line again changes nothing.
        tx.executeWithoutResult(s -> pickConsumption.putBack(
                new PutBackCommand(lines.get(0), holds.get(0), lpns.get(0), SHELF, clerk)));

        assertThat(cartonRows()).hasSize(2).allSatisfy(row -> {
            assertThat(row).containsEntry("carton_status", "IN_STOCK")
                    .containsEntry("on_hand", 3).containsEntry("reserved", 0).containsEntry("status", "AVAILABLE")
                    .containsEntry("lot_number", lot);
            assertThat(((java.sql.Timestamp) row.get("received_at")).toInstant()).as("the receipt's age is kept")
                    .isEqualTo(RECEIVED);
            assertThat(row.get("location_code")).as("its row is where it is").isEqualTo(row.get("carton_location"));
        });
        Map<String, String> binOf = new java.util.HashMap<>();
        cartonRows().forEach(row -> binOf.put((String) row.get("lpn"), (String) row.get("carton_location")));
        assertThat(binOf).containsEntry(lpns.get(0), SHELF).containsEntry(lpns.get(1), SHELF);
        assertThat(layers()).as("no row outside a carton is made or raised").isEmpty();
        assertThat(jdbc.queryForObject("""
                select count(*) from inventory.stock_movement
                 where movement_type = 'PUT_BACK' and reference_type = 'PICK_LINE' and reference_id in (?, ?)
                   and to_stock_package_id is not null""",
                Integer.class, lines.get(0), lines.get(1))).as("one per line, naming its carton").isEqualTo(2);
    }

    @Test
    @DisplayName("put back onto a bin holding another carton of the same receipt: two cartons side by side, each its own row")
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
                        received_qty, location_code, status) values (?, ?, ?, ?, 5, 5, ?, 'IN_STOCK')""",
                    carton, "LPN-" + sku, item, unit, SHELF);
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, lot_number, received_at, on_hand, reserved,
                        status, version, created_at, stock_package_id) values (?, ?, ?, ?, ?, 5, 0, 'AVAILABLE', 0, now(), ?)""",
                    Identifiers.newId(), sku, SHELF, lot, java.sql.Timestamp.from(RECEIVED), carton);
        });

        tx.executeWithoutResult(s -> pickConsumption.putBack(
                new PutBackCommand(line, hold, picked, SHELF, clerk)));

        assertThat(jdbc.queryForList("""
                select i.on_hand, i.stock_package_id from inventory.stock_item i
                 where i.sku = ? and i.location_code = ? and i.stock_package_id is not null
                 order by i.stock_package_id""", sku, SHELF))
                .as("no loose row, no merge: one row per carton, each holding its own five")
                .hasSize(2).allSatisfy(row -> {
                    assertThat(row.get("on_hand")).isEqualTo(5);
                    assertThat(row.get("stock_package_id")).isNotNull();
                });
        assertThat(cartonRows()).extracting(row -> row.get("carton_status") + ":" + row.get("carton_location"))
                .containsExactly("IN_STOCK:" + SHELF, "IN_STOCK:" + SHELF);
    }

    @Test
    @DisplayName("put back onto ANOTHER bin: the carton and its row follow it there whole")
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
                .containsEntry("location_code", OTHER_BIN).containsEntry("on_hand", 3));
    }

    @Test
    @DisplayName("a receipt dated an hour ahead is still refused; the clock skew between application and database is not")
    void receiptTime() {
        // A carton just received (stock is whole cartons only, V20261117000100).
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> CartonRows.carton(jdbc, sku, "HCM-RCV01", 2, lot + "-L",
                Instant.now().plusSeconds(3600), "INBOUND")))
                .hasMessageContaining("Invalid receipt date");
        tx.executeWithoutResult(s -> CartonRows.carton(jdbc, sku, "HCM-RCV01", 2, lot + "-S",
                Instant.now().plusSeconds(2), "INBOUND"));
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_item where sku = ? and lot_number = ?",
                Integer.class, sku, lot + "-S")).isEqualTo(1);
    }
}
