package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.HoldView;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.ReserveStockCommand;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Team plan 4C-2 against PostgreSQL: stock is whole cartons only (kltn-docs 20 BR-14, 07 BR-03), so a release holds
 * whole cartons in the SKU's removal order and refuses when the cartons fall short (holding k &lt; N waits for
 * AWAITING_STOCK, 4A-6); a carton held for an order leaves its bin only by being picked (11 BR-06), so a hand move
 * naming it is refused, and so is the rest of the list it came in.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class CartonAllocationIntegrationTest {

    private static final String SHELF = "HCM-A01-1-B";
    private static final String OTHER_BIN = "HCM-A01-2-A";
    private static final int FACTOR = 10;
    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(System.nanoTime() % 500_000);

    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private UUID clerk;

    @BeforeEach
    void setUp() {
        sku = "PKA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Carton fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Carton fixture");
        });
    }

    /** One sealed carton of ten on the shelf, received on {@code day} of January 2026. */
    private String carton(int day) {
        return tx.execute(s -> CartonRows.stock(jdbc, sku, SHELF, 1, FACTOR, null,
                Instant.parse("2026-01-%02dT00:00:00Z".formatted(day)))).getFirst().lpn();
    }

    private UUID confirmedOrder() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, paid_amount, paid_in_full_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), 1, NOW())""", order,
                "SO-20991226-%06d".formatted(ORDER_NUMBERS.incrementAndGet()),
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        return order;
    }

    /** Reserves {@code quantity} for a new confirmed order and pins it, ready to be released. */
    private UUID reserved(int quantity) {
        UUID order = confirmedOrder();
        tx.executeWithoutResult(s -> {
            inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), quantity, order));
            inventory.pinReservations(order);
        });
        return order;
    }

    /** Releases {@code order} to HCM; returns every hold it made. */
    private List<HoldView> release(UUID order) {
        List<UUID> holds = tx.execute(s -> inventory.allocate(new AllocateStockCommand(order, "HCM")).allocations()
                .stream().flatMap(allocation -> allocation.holdIds().stream()).toList());
        return inventory.holds(holds);
    }

    private static String describe(HoldView hold) {
        return hold.lpn() + ":" + hold.quantity() + (hold.wholeCarton() ? ":whole" : "");
    }

    private int heldUnits() {
        return jdbc.queryForObject("select coalesce(sum(reserved), 0) from inventory.stock_item where sku = ?",
                Integer.class, sku);
    }

    private String locationOf(String lpn) {
        return jdbc.queryForObject("select location_code from inventory.stock_package where lpn = ?", String.class,
                lpn);
    }

    private void moveByHand(List<String> lpns, String to) {
        tx.executeWithoutResult(s -> inventory.moveStockPackages(new MoveStockPackagesCommand(lpns, to,
                MoveReference.MOVE_TASK, Identifiers.newId(), clerk)));
    }

    @Test
    @DisplayName("FIFO: the earliest cartons go, each whole; the later one stays sealed and free")
    void wholeCartonsInFifoOrder() {
        String latest = carton(9);
        String first = carton(2);
        String second = carton(5);

        assertThat(release(reserved(20))).extracting(CartonAllocationIntegrationTest::describe)
                .containsExactlyInAnyOrder(first + ":10:whole", second + ":10:whole");
        assertThat(jdbc.queryForObject("""
                select i.reserved from inventory.stock_item i join inventory.stock_package p on p.id = i.stock_package_id
                 where p.lpn = ?""", Integer.class, latest)).isZero();
    }

    @Test
    @DisplayName("k < N: two pickable cartons for a three-carton line refuse the release whole, holding nothing")
    void shortCartonsRefuseTheRelease() {
        carton(2);
        carton(3);
        // The third carton is sellable - checkout promised it - but not on a pickable bin: the release cannot take it.
        tx.executeWithoutResult(s -> CartonRows.stock(jdbc, sku, "HCM-RCV01", 1, FACTOR, null,
                Instant.parse("2026-01-01T00:00:00Z")));
        UUID order = reserved(30);

        BusinessException refused = catchThrowableOfType(BusinessException.class, () -> release(order));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(refused.getMessage()).contains("20 pickable").contains("2 carton(s)").contains("30 are needed");
        assertThat(heldUnits()).isZero();
        assertThat(jdbc.queryForObject("""
                select status from inventory.stock_reservation where order_id = ? and allocated_from_id is null""",
                String.class, order)).isEqualTo("HELD");
    }

    @Test
    @DisplayName("a line that is no sum of whole cartons is short too: no carton is broken for the remainder")
    void noCartonIsBrokenForARemainder() {
        carton(2);
        carton(3);
        UUID order = reserved(15);

        BusinessException refused = catchThrowableOfType(BusinessException.class, () -> release(order));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(heldUnits()).isZero();
    }

    @Test
    @DisplayName("a carton held for an order is not moved by hand: STOCK_PACKAGE_HELD, naming it")
    void heldCartonIsNotMovedByHand() {
        String held = carton(2);
        release(reserved(10));

        BusinessException refused = catchThrowableOfType(BusinessException.class,
                () -> moveByHand(List.of(held), OTHER_BIN));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.STOCK_PACKAGE_HELD);
        assertThat(refused.getMessage()).contains(held);
        assertThat(locationOf(held)).isEqualTo(SHELF);
    }

    @Test
    @DisplayName("a hand move is all or nothing: one held carton in the list keeps every carton where it is")
    void moveListIsAllOrNothing() {
        String held = carton(2);
        String free = carton(3);
        release(reserved(10));

        BusinessException refused = catchThrowableOfType(BusinessException.class,
                () -> moveByHand(List.of(free, held), OTHER_BIN));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.STOCK_PACKAGE_HELD);
        assertThat(locationOf(free)).isEqualTo(SHELF);
        assertThat(locationOf(held)).isEqualTo(SHELF);
        assertThat(jdbc.queryForObject("""
                select count(*) from inventory.stock_movement m
                  join inventory.stock_package p on p.id in (m.from_stock_package_id, m.to_stock_package_id)
                 where p.lpn in (?, ?) and m.movement_type = 'MOVE'""", Integer.class, free, held)).isZero();

        moveByHand(List.of(free), OTHER_BIN);
        assertThat(locationOf(free)).isEqualTo(OTHER_BIN);
    }
}
