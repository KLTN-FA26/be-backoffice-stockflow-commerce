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
import com.stockflow.inventory.api.StockPackageView;
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
                    currency, placed_at, version, created_at, contract_signed_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), NOW())""", order,
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
        moveByHand(Identifiers.newId(), lpns, to);
    }

    private List<String> moveByHand(UUID request, List<String> lpns, String to) {
        return tx.execute(s -> inventory.moveStockPackages(new MoveStockPackagesCommand(lpns, to,
                MoveReference.MOVE_TASK, request, clerk))).stream().map(StockPackageView::lpn).toList();
    }

    private int handMoves(String lpn) {
        return jdbc.queryForObject("""
                select count(*) from inventory.stock_movement m join inventory.stock_package p on p.id = m.to_stock_package_id
                 where p.lpn = ? and m.movement_type = 'MOVE' and m.reference_type = 'MOVE_TASK'""", Integer.class, lpn);
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
        String third = carton(4);
        UUID order = reserved(30);
        // Promised at checkout, the third carton is taken off the pick face before the release: two whole cartons are
        // left on pickable bins for a line of three.
        moveByHand(List.of(third), "HCM-RCV01");

        BusinessException refused = catchThrowableOfType(BusinessException.class, () -> release(order));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(refused.getMessage()).contains("20 pickable").contains("2 carton(s)").contains("30 are needed");
        assertThat(heldUnits()).isZero();
        assertThat(jdbc.queryForObject("""
                select status from inventory.stock_reservation where order_id = ? and allocated_from_id is null""",
                String.class, order)).isEqualTo("HELD");
    }

    @Test
    @DisplayName("a reservation counts cartons (20 §5.6): units that are no whole number of cartons are refused at"
            + " checkout, so no carton is ever broken for a remainder")
    void reservationCountsCartons() {
        carton(2);
        carton(3);

        BusinessException refused = catchThrowableOfType(BusinessException.class, () -> reserved(15));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESERVATION_NOT_WHOLE_CARTONS);
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_reservation where sku = ?",
                Integer.class, sku)).isZero();
    }

    @Test
    @DisplayName("available to promise counts only cartons a release could allocate: not a carton off a pickable"
            + " bin, nor loose units on one")
    void onlyAllocatableStockIsPromised() {
        carton(2);
        tx.executeWithoutResult(s -> {
            CartonRows.stock(jdbc, sku, "HCM-RCV01", 1, FACTOR, null, Instant.parse("2026-01-03T00:00:00Z"));
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version,
                        created_at, received_at) values (?, ?, ?, 7, 0, 'AVAILABLE', 0, now(), now())""",
                    Identifiers.newId(), sku, SHELF);
        });

        assertThat(inventory.availableToPromise(new Sku(sku))).isEqualTo(10);
        assertThat(catchThrowableOfType(BusinessException.class, () -> reserved(20)).errorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
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
    @DisplayName("11 BR-07: the same request again is answered from the ledger; its id reused for other cartons or"
            + " another place is IDEMPOTENCY_KEY_REUSED, and nothing moves")
    void moveRequestIdIsOneRequest() {
        String a = carton(2);
        String b = carton(3);
        UUID request = Identifiers.newId();
        moveByHand(request, List.of(a), OTHER_BIN);

        assertThat(moveByHand(request, List.of(a), OTHER_BIN)).as("the same request").containsExactly(a);
        assertThat(handMoves(a)).isEqualTo(1);
        for (Runnable other : List.<Runnable>of(() -> moveByHand(request, List.of(b), OTHER_BIN),
                () -> moveByHand(request, List.of(a, b), OTHER_BIN), () -> moveByHand(request, List.of(a), SHELF))) {
            assertThat(catchThrowableOfType(BusinessException.class, other::run).errorCode())
                    .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        assertThat(locationOf(a)).isEqualTo(OTHER_BIN);
        assertThat(locationOf(b)).isEqualTo(SHELF);
        assertThat(handMoves(a)).isEqualTo(1);
    }

    @Test
    @DisplayName("an LPN scanned in lower case, with spaces, names its carton, as at picking (11 BR-10)")
    void moveLpnIsCaseInsensitive() {
        String a = carton(2);

        assertThat(moveByHand(Identifiers.newId(), List.of(" " + a.toLowerCase(Locale.ROOT) + " "), OTHER_BIN))
                .containsExactly(a);
        assertThat(locationOf(a)).isEqualTo(OTHER_BIN);
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
