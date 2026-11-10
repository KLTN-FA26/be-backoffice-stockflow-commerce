package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.contracts.PutawayCompleted;
import com.stockflow.inventory.api.HoldStockCommand;
import com.stockflow.inventory.api.HoldStockResult;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import com.stockflow.order.internal.service.ShippingWarehouseService.BatchCoverage;
import com.stockflow.order.internal.service.ShippingWarehouseService.Coverage;
import com.stockflow.order.internal.service.ShippingWarehouseService.LineCoverage;
import com.stockflow.order.internal.service.ShippingWarehouseService.Option;
import com.stockflow.order.internal.service.ShippingWarehouseService.Options;
import com.stockflow.support.Await;
import com.stockflow.support.CartonRows;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4B-7 (kltn-docs 17 §4.2 steps 2-3, BR-21, BR-22; 20 §5.6, BR-01, BR-17) against the real database: Sales chooses the
 * one warehouse a confirmed order of a contract ships from, the order holds whole cartons of each line there by SKU,
 * what is missing is shown per delivery batch and topped up when goods arrive, and the order is released from that
 * warehouse only once every carton is held there.
 *
 * <p>Every order is confirmed the way production confirms it - a contract drawn up, sent and signed through
 * {@link SalesContracts} - never by writing a status: whether an order is "of a contract" is its signature
 * ({@code contract_signed_at}), and {@code ck_order_shipping_warehouse} refuses a shipping warehouse without it. A plan
 * paying everything before delivery confirms at signing, so no payment has to be awaited.</p>
 *
 * <p>Each test issues a SKU of its own, so the stock it puts into the two test warehouses is the only stock of that SKU
 * anywhere: every figure of available to promise below is exact, whatever other tests left in the shared database. The
 * warehouses themselves are created once per database and shared - a new pair per test would only grow the list of
 * active warehouses every other test sharing this context reads.</p>
 *
 * <p>Every order is 20 cartons of 50 (1,000 pieces at 2,000 with 10% VAT), so a warehouse's whole cartons are its free
 * units divided by 50, rounded down (17 BR-21).</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ShippingWarehouseIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final int PER_CARTON = 50;
    private static final int CARTONS = 20;

    /** A warehouse of the test, its code (the prefix of its location codes) and its one pickable bin. */
    private record Warehouse(UUID id, String code, String bin) {
    }

    /** Created once per database: see the class comment. */
    private static Warehouse a;
    private static Warehouse b;
    private static Warehouse inactive;

    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired ShippingWarehouseService shippingWarehouses;
    @Autowired OrderReleases releases;
    @Autowired InventoryService inventory;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private UUID coordinator;
    private UUID customerId;
    private CustomerSkuRows.Issued sku;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        if (a == null || !exists(a) || !exists(b) || !exists(inactive)) {
            a = warehouse("ACTIVE");
            b = warehouse("ACTIVE");
            inactive = warehouse("INACTIVE");
        }
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, 'Cong ty Kho Giao', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "shipping-" + customerId + "@example.com", "03" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, PER_CARTON, "STANDARD", true);
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    private boolean exists(Warehouse warehouse) {
        return jdbc.queryForObject("SELECT count(*) FROM warehouse.warehouse WHERE id = ?", Long.class,
                warehouse.id()) == 1;
    }

    /** A warehouse with one active, pickable bin - what a release allocates from (PickableStock). */
    private Warehouse warehouse(String status) {
        UUID id = Identifiers.newId();
        String code = "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase(Locale.ROOT);
        String bin = code + "-A01-1-A";
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                     version, created_at)
                    VALUES (?, ?, ?, ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", id, "Warehouse " + code, status, code);
            UUID zone = Identifiers.newId();
            UUID shelf = Identifiers.newId();
            UUID level = Identifiers.newId();
            UUID location = Identifiers.newId();
            jdbc.update("INSERT INTO warehouse.zone (id, warehouse_id, name, color) VALUES (?, ?, 'Zone', '#4F81BD')",
                    zone, id);
            jdbc.update("""
                    INSERT INTO warehouse.shelf (id, warehouse_id, zone_id, code, name, x, y, width, length, rotation,
                                                 is_obstacle, pick_north, pick_east, pick_south, pick_west,
                                                 default_storage_class, status)
                    VALUES (?, ?, ?, 'A01', 'Shelf', 5, 5, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'OVERSIZE',
                            'ACTIVE')""", shelf, id, zone);
            jdbc.update("""
                    INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
                    VALUES (?, ?, 1, 0, 1.4, 500)""", level, shelf);
            jdbc.update("""
                    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,
                                                            capacity_units, max_weight, is_pickable,
                                                            is_putaway_target, status)
                    VALUES (?, ?, 'BIN', ?, 'OVERSIZE', 40, 250, TRUE, TRUE, 'ACTIVE')""", location, id, bin);
            jdbc.update("""
                    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
                    VALUES (?, ?, ?, 'A', 0, 0, 5, 1.2, 0, 'PALLET')""", Identifiers.newId(), level, location);
        });
        return new Warehouse(id, code, bin);
    }

    /**
     * {@code units} pieces of the test's SKU free in {@code warehouse}'s bin, received a while ago, as the warehouse keeps
     * stock since team plan 4C-2: whole sealed cartons of 50, and what is short of one as a single odd carton of its own
     * unit ({@code PK<n>}) - the pieces no carton of 50 can be made of, which a hold of cartons of 50 never takes (BR-21)
     * but available to promise still counts.
     */
    private void stock(Warehouse warehouse, int units) {
        Instant received = Instant.now().minus(Duration.ofDays(2));
        tx.executeWithoutResult(s -> {
            CartonRows.stockOfUnit(jdbc, sku.sku(), sku.uomConversionId(), warehouse.bin(), units / PER_CARTON, null,
                    null, received, "AVAILABLE");
            int rest = units % PER_CARTON;
            if (rest > 0) {
                UUID item = jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class,
                        sku.sku());
                jdbc.update("""
                        INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base,
                            default_purchase, active, version, created_at, default_shipping)
                        VALUES (?, ?, ?, ?, FALSE, FALSE, TRUE, 0, NOW(), FALSE)
                        ON CONFLICT (inventory_item_id, unit_of_measure) DO NOTHING""", Identifiers.newId(), item,
                        "PK" + rest, rest);
                UUID odd = jdbc.queryForObject("SELECT id FROM inventory.uom_conversions WHERE inventory_item_id = ? "
                        + "AND unit_of_measure = ?", UUID.class, item, "PK" + rest);
                CartonRows.stockOfUnit(jdbc, sku.sku(), odd, warehouse.bin(), 1, null, null, received, "AVAILABLE");
            }
        });
    }

    /** A DRAFT order of 20 cartons of 50 at 2,000 a piece with 10% VAT - as a converted quote leaves it. */
    private UUID draftOrder() {
        return tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), CARTONS * PER_CARTON,
                    Money.vnd(2_000), null).withCartons(sku.uomConversionId(), CARTONS, PER_CARTON)
                    .withTax(BigDecimal.TEN);
            Order order = Order.draft(OrderNumber.of(today, 700_000 + ThreadLocalRandom.current().nextInt(99_999)),
                    customerId, Identifiers.newId(), List.of(line), Instant.now());
            return orderRepository.save(order).id().value();
        });
    }

    private UUID lineOf(UUID orderId) {
        return jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
    }

    private static SalesContracts.Installment share(String name, String milestone, String percent, Integer dueDays) {
        return new SalesContracts.Installment(name, milestone, null, new BigDecimal(percent), dueDays, null);
    }

    /** Everything paid before delivery: no installment on signing, no credit - signing confirms the order at once. */
    private static List<SalesContracts.Installment> payBeforeDelivery() {
        return List.of(share("Before delivery", "BEFORE_DELIVERY", "100", null));
    }

    /** A deposit on signing: signed, the order waits PENDING_PAYMENT for it (15 BR-07). */
    private static List<SalesContracts.Installment> depositOnSigning() {
        return List.of(share("Deposit", "ON_SIGNING", "30", 0), share("Rest", "BEFORE_DELIVERY", "70", null));
    }

    /** Every carton in one batch three weeks out. */
    private List<SalesContracts.Batch> oneBatch(UUID line) {
        return List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                List.of(new SalesContracts.BatchLine(line, CARTONS))));
    }

    /** 12 cartons three weeks out, the other 8 two weeks later. */
    private List<SalesContracts.Batch> twoBatches(UUID line) {
        return List.of(
                new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                        List.of(new SalesContracts.BatchLine(line, 12))),
                new SalesContracts.Batch(today.plusDays(35), today.plusDays(37),
                        List.of(new SalesContracts.BatchLine(line, 8))));
    }

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    /** The order's contract with {@code schedule} and {@code plan}, drawn up and sent. */
    private SalesContracts.ContractView sent(UUID orderId, List<SalesContracts.Batch> schedule,
                                             List<SalesContracts.Installment> plan) {
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, schedule, plan)));
        return asSales(() -> contracts.send(sales, orderId, drafted.version()));
    }

    /** The sent contract recorded signed today with a real one-page PDF scan. */
    private void sign(UUID orderId, SalesContracts.ContractView sent) {
        byte[] scan = TestPdfs.blank();
        asSales(() -> contracts.sign(sales, orderId, sent.version(),
                IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), today));
    }

    /** A new order of a contract signed today, CONFIRMED by its signature. */
    private UUID confirmedOrder() {
        return confirmedOrder(this::oneBatch);
    }

    private UUID confirmedOrder(java.util.function.Function<UUID, List<SalesContracts.Batch>> schedule) {
        UUID orderId = draftOrder();
        sign(orderId, sent(orderId, schedule.apply(lineOf(orderId)), payBeforeDelivery()));
        assertThat(status(orderId)).as("a plan paying before delivery confirms at signing")
                .isEqualTo(OrderStatus.CONFIRMED);
        return orderId;
    }

    private OrderStatus status(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    private Coverage choose(UUID orderId, Warehouse warehouse) {
        return shippingWarehouses.choose(orderId, warehouse.id(), sales.userId());
    }

    /** Every reservation row of the order: SKU reservations and allocations alike. */
    private List<Map<String, Object>> reservationsOf(UUID orderId) {
        return jdbc.queryForList("""
                SELECT id, sku, warehouse_code, stock_item_id, expires_at, status, quantity, allocated_from_id,
                       release_reason
                  FROM inventory.stock_reservation WHERE order_id = ? ORDER BY reserved_at, id""", orderId);
    }

    /** The reservation ids the order's line carries ({@code ordering.order_line_reservation}). */
    private List<UUID> lineReservations(UUID lineId) {
        return jdbc.queryForList("SELECT reservation_id FROM ordering.order_line_reservation WHERE order_line_id = ?",
                UUID.class, lineId);
    }

    private Map<String, Object> orderRow(UUID orderId) {
        return jdbc.queryForMap("""
                SELECT status, shipping_warehouse_id, shipping_warehouse_chosen_at, shipping_warehouse_chosen_by,
                       warehouse_id
                  FROM ordering.customer_order WHERE id = ?""", orderId);
    }

    private Map<String, Integer> atpByWarehouse() {
        return inventory.availableToPromiseByWarehouse(new Sku(sku.sku()));
    }

    private int atp(Warehouse warehouse) {
        return atpByWarehouse().getOrDefault(warehouse.code(), 0);
    }

    private int systemAtp() {
        return inventory.availableToPromise(new Sku(sku.sku()));
    }

    /** A warehouse-bound SKU reservation written straight to the table, as nothing in the application writes it. */
    private UUID insertHold(UUID orderId, String warehouseCode, int units) {
        UUID id = Identifiers.newId();
        UUID request = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, quantity,
                    reserved_at, status, sku, warehouse_code)
                VALUES (?, ?, ?, ?, ?, NOW(), 'HELD', ?, ?)""", id, orderId, request, request, units, sku.sku(),
                warehouseCode));
        return id;
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    /**
     * Every listener of the events naming {@code id} has finished (Spring Modulith completes a publication when its
     * listener returns). The publication is written in the publishing transaction, so it must be there already - else
     * the wait would pass on nothing at all.
     */
    private void awaitListeners(UUID id) {
        String naming = "%" + id + "%";
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                Long.class, naming)).as("the events naming %s are in the publication registry", id).isPositive();
        Await.until("listeners of " + id + " done", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE serialized_event LIKE ? AND completion_date IS NULL""",
                Long.class, naming) == 0);
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("17 §4.2 step 2: every active warehouse is an option, the one holding every line first, in whole cartons")
    void optionsRankWarehousesByWholeCartons() {
        stock(a, 1_030);
        stock(b, 990);
        UUID orderId = confirmedOrder();
        UUID line = lineOf(orderId);

        Options options = shippingWarehouses.options(orderId);

        assertThat(options.orderId()).isEqualTo(orderId);
        assertThat(options.chosenWarehouseId()).as("nothing chosen yet").isNull();
        assertThat(options.capacityComputed()).as("no warehouse capacity table yet").isFalse();
        List<UUID> active = jdbc.queryForList("SELECT id FROM warehouse.warehouse WHERE status = 'ACTIVE'",
                UUID.class);
        assertThat(options.warehouses()).extracting(Option::warehouseId)
                .as("every active warehouse, and only those").containsExactlyInAnyOrderElementsOf(active)
                .doesNotContain(inactive.id());

        Option first = options.warehouses().get(0);
        assertThat(first.warehouseId()).as("the warehouse holding every line comes first").isEqualTo(a.id());
        assertThat(first.warehouseCode()).isEqualTo(a.code());
        assertThat(first.holdsEveryLine()).isTrue();
        assertThat(first.availableCartons()).isEqualTo(CARTONS);
        assertThat(first.lines()).singleElement().satisfies(l -> {
            assertThat(l.lineId()).isEqualTo(line);
            assertThat(l.sku()).isEqualTo(sku.sku());
            assertThat(l.cartons()).isEqualTo(CARTONS);
            assertThat(l.availableCartons()).as("1,030 pieces are 20 whole cartons; 30 loose are none (BR-21)")
                    .isEqualTo(20);
        });

        Option second = options.warehouses().get(1);
        assertThat(second.warehouseId()).as("then the one holding more").isEqualTo(b.id());
        assertThat(second.holdsEveryLine()).isFalse();
        assertThat(second.availableCartons()).as("990 pieces are 19 whole cartons").isEqualTo(19);
        assertThat(second.lines()).singleElement().satisfies(l -> assertThat(l.availableCartons()).isEqualTo(19));

        assertThat(options.warehouses().subList(2, options.warehouses().size()))
                .as("every other warehouse has none of this SKU")
                .allSatisfy(o -> {
                    assertThat(o.holdsEveryLine()).isFalse();
                    assertThat(o.availableCartons()).isZero();
                });
    }

    @Test
    @DisplayName("17 §4.2 step 3, BR-22: chosen with enough stock, every carton is held there; another warehouse is locked")
    void chooseHoldsEveryCartonAndLocksTheWarehouse() {
        stock(a, 1_000);
        stock(b, 1_000);
        UUID orderId = confirmedOrder();
        UUID line = lineOf(orderId);

        Coverage coverage = choose(orderId, a);

        assertThat(coverage.orderId()).isEqualTo(orderId);
        assertThat(coverage.warehouseId()).isEqualTo(a.id());
        assertThat(coverage.warehouseCode()).isEqualTo(a.code());
        assertThat(coverage.fullyHeld()).isTrue();
        assertThat(coverage.lines()).containsExactly(new LineCoverage(line, sku.sku(), CARTONS, CARTONS, 0));
        assertThat(coverage.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.seq()).isEqualTo(1);
            assertThat(batch.fullyHeld()).isTrue();
            assertThat(batch.lines()).containsExactly(new LineCoverage(line, sku.sku(), CARTONS, CARTONS, 0));
        });
        assertThat(shippingWarehouses.coverage(orderId)).as("read back, the same").isEqualTo(coverage);

        Map<String, Object> hold = reservationsOf(orderId).stream().collect(onlyOne());
        assertThat(hold).as("a SKU reservation bound to the warehouse, with no deadline (20 §5.6, BR-17)")
                .containsEntry("warehouse_code", a.code())
                .containsEntry("sku", sku.sku())
                .containsEntry("status", "HELD")
                .containsEntry("quantity", CARTONS * PER_CARTON);
        assertThat(hold.get("stock_item_id")).as("on no stock item: bins are chosen at release").isNull();
        assertThat(hold.get("expires_at")).isNull();
        UUID reservationId = (UUID) hold.get("id");
        assertThat(lineReservations(line)).containsExactly(reservationId);

        Map<String, Object> order = orderRow(orderId);
        assertThat(order).containsEntry("shipping_warehouse_id", a.id())
                .containsEntry("shipping_warehouse_chosen_by", sales.userId());
        Timestamp chosenAt = (Timestamp) order.get("shipping_warehouse_chosen_at");
        assertThat(chosenAt).isNotNull();

        // The same warehouse again changes nothing: no second hold, the same choice.
        assertThat(choose(orderId, a)).isEqualTo(coverage);
        assertThat(reservationsOf(orderId)).extracting(r -> r.get("id")).containsExactly(reservationId);
        assertThat(lineReservations(line)).containsExactly(reservationId);
        assertThat(orderRow(orderId).get("shipping_warehouse_chosen_at")).isEqualTo(chosenAt);

        // BR-22: stock is held at A, so the warehouse no longer changes.
        assertThatThrownBy(() -> choose(orderId, b))
                .extracting(ShippingWarehouseIntegrationTest::code)
                .isEqualTo(ErrorCode.ORDER_SHIPPING_WAREHOUSE_LOCKED);
        assertThat(ErrorCode.ORDER_SHIPPING_WAREHOUSE_LOCKED.httpStatus()).isEqualTo(409);
        assertThat(orderRow(orderId)).containsEntry("shipping_warehouse_id", a.id());
        assertThat(reservationsOf(orderId)).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("warehouse_code", a.code()).containsEntry("status", "HELD"));
        assertThat(atp(b)).as("nothing was held at B").isEqualTo(1_000);
    }

    @Test
    @DisplayName("17 §4.2 step 3: k of N cartons available - k held, the earliest batch covered first; release refused")
    void partialHoldCoversTheEarliestBatchFirst() {
        stock(a, 780);
        stock(b, 1_000);
        UUID orderId = confirmedOrder(this::twoBatches);
        UUID line = lineOf(orderId);

        Coverage coverage = choose(orderId, a);

        assertThat(coverage.fullyHeld()).as("a shortage is not refused, it is shown").isFalse();
        assertThat(coverage.lines()).containsExactly(new LineCoverage(line, sku.sku(), CARTONS, 15, 5));
        assertThat(coverage.batches()).extracting(BatchCoverage::seq).containsExactly(1, 2);
        BatchCoverage first = coverage.batches().get(0);
        assertThat(first.windowStart()).isEqualTo(today.plusDays(21));
        assertThat(first.fullyHeld()).as("the 15 held cartons cover batch 1's 12 first").isTrue();
        assertThat(first.lines()).containsExactly(new LineCoverage(line, sku.sku(), 12, 12, 0));
        BatchCoverage second = coverage.batches().get(1);
        assertThat(second.windowStart()).isEqualTo(today.plusDays(35));
        assertThat(second.fullyHeld()).isFalse();
        assertThat(second.lines()).containsExactly(new LineCoverage(line, sku.sku(), 8, 3, 5));
        assertThat(shippingWarehouses.coverage(orderId)).isEqualTo(coverage);

        // 4B-8: held batch by batch, the earliest first - batch 1's 12 cartons, then 3 of batch 2's 8.
        assertThat(reservationsOf(orderId)).as("whole cartons only: the odd 30 pieces are not held (BR-21)")
                .extracting(r -> r.get("quantity")).containsExactlyInAnyOrder(12 * PER_CARTON, 3 * PER_CARTON);
        assertThat(reservationsOf(orderId)).allSatisfy(r -> assertThat(r)
                .containsEntry("warehouse_code", a.code())
                .containsEntry("status", "HELD"));
        assertThat(atp(a)).as("the odd 30 pieces stay free").isEqualTo(30);

        // 4B-8 (17 §4.5, BR-29): released batch by batch - batch 1 is held in full, so it goes while batch 2 is short;
        // only batch 1's hold is allocated, batch 2's stays held on no bin.
        assertThat(releases.release(orderId, a.id(), coordinator).status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(orderRow(orderId).get("warehouse_id")).isEqualTo(a.id());
        assertThat(reservationsOf(orderId)).filteredOn(r -> r.get("allocated_from_id") == null
                        && "HELD".equals(r.get("status")))
                .extracting(r -> r.get("quantity")).as("batch 2's 3 cartons").containsExactly(3 * PER_CARTON);
        assertThat(reservationsOf(orderId).stream().filter(r -> r.get("allocated_from_id") != null)
                .mapToInt(r -> ((Number) r.get("quantity")).intValue()).sum()).as("batch 1's 12 cartons allocated")
                .isEqualTo(12 * PER_CARTON);
    }

    @Test
    @DisplayName("17 §4.2 step 3: goods put away top up an order waiting at its warehouse with a hold of their own")
    void putawayTopsUpAWaitingOrder() {
        stock(a, 750);
        UUID orderId = confirmedOrder();
        UUID line = lineOf(orderId);
        assertThat(choose(orderId, a).lines()).containsExactly(new LineCoverage(line, sku.sku(), CARTONS, 15, 5));
        UUID firstHold = lineReservations(line).get(0);

        stock(a, 250);
        UUID task = Identifiers.newId();
        tx.executeWithoutResult(s -> publisher.publishEvent(new PutawayCompleted(Identifiers.newId(),
                Identifiers.newId(), task)));
        Await.until("order " + orderId + " fully held", ASYNC, () -> shippingWarehouses.coverage(orderId).fullyHeld());
        awaitListeners(task);

        Coverage coverage = shippingWarehouses.coverage(orderId);
        assertThat(coverage.lines()).containsExactly(new LineCoverage(line, sku.sku(), CARTONS, CARTONS, 0));
        List<UUID> held = lineReservations(line);
        assertThat(held).as("the first hold and the top-up").hasSize(2).contains(firstHold);
        UUID topUp = held.stream().filter(id -> !id.equals(firstHold)).findFirst().orElseThrow();
        assertThat(reservationsOf(orderId)).filteredOn(r -> topUp.equals(r.get("id"))).singleElement()
                .satisfies(r -> {
                    assertThat(r).containsEntry("quantity", 5 * PER_CARTON).containsEntry("warehouse_code", a.code())
                            .containsEntry("status", "HELD");
                    assertThat(r.get("expires_at")).isNull();
                    assertThat(r.get("stock_item_id")).isNull();
                });
        assertThat(atp(a)).isZero();
    }

    @Test
    @DisplayName("17 §4.2 step 3: stock arriving for two waiting orders goes to the earliest signed; not to another warehouse's")
    void topUpServesTheEarliestSignedFirst() {
        UUID createdFirst = draftOrder();
        UUID createdSecond = draftOrder();
        SalesContracts.ContractView sentFirst = sent(createdFirst, oneBatch(lineOf(createdFirst)), payBeforeDelivery());
        SalesContracts.ContractView sentSecond = sent(createdSecond, oneBatch(lineOf(createdSecond)),
                payBeforeDelivery());
        // Signed in the other order than created: the earliest signed is served, not the oldest order or lowest id.
        sign(createdSecond, sentSecond);
        sign(createdFirst, sentFirst);
        UUID signedFirst = createdSecond;
        UUID signedSecond = createdFirst;
        assertThat(status(signedFirst)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(status(signedSecond)).isEqualTo(OrderStatus.CONFIRMED);

        assertThat(choose(signedSecond, a).lines()).singleElement()
                .satisfies(l -> assertThat(l.heldCartons()).as("A has none yet").isZero());
        assertThat(choose(signedFirst, a).lines()).singleElement()
                .satisfies(l -> assertThat(l.heldCartons()).isZero());
        assertThat(reservationsOf(signedFirst)).isEmpty();
        assertThat(reservationsOf(signedSecond)).isEmpty();

        // Stock at B is not stock of A (20 BR-17): neither order holds it.
        stock(b, 1_000);
        shippingWarehouses.topUpWaiting();
        assertThat(reservationsOf(signedFirst)).isEmpty();
        assertThat(reservationsOf(signedSecond)).isEmpty();

        // 20 cartons arrive at A: enough for one of the two.
        stock(a, 1_000);
        assertThat(shippingWarehouses.topUpWaiting()).as("at least the earliest signed held more").isPositive();

        assertThat(shippingWarehouses.coverage(signedFirst).fullyHeld()).as("signed first, served first").isTrue();
        assertThat(reservationsOf(signedFirst)).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("warehouse_code", a.code()).containsEntry("quantity", CARTONS * PER_CARTON));
        Coverage later = shippingWarehouses.coverage(signedSecond);
        assertThat(later.fullyHeld()).isFalse();
        assertThat(later.lines()).singleElement().satisfies(l -> {
            assertThat(l.heldCartons()).isZero();
            assertThat(l.shortCartons()).isEqualTo(CARTONS);
        });
        assertThat(reservationsOf(signedSecond)).isEmpty();
        assertThat(atp(b)).as("B's stock was left alone").isEqualTo(1_000);
    }

    @Test
    @DisplayName("20 BR-01, BR-17: a hold at a warehouse is promised from it only - its ATP falls, the other's does not (B's 950 are 19 whole cartons: pieces short of a carton are no ATP, BR-21)")
    void atpPerWarehouse() {
        stock(a, 1_000);
        stock(b, 950);
        assertThat(atpByWarehouse()).containsEntry(a.code(), 1_000).containsEntry(b.code(), 950);
        assertThat(systemAtp()).isEqualTo(1_950);

        UUID orderId = confirmedOrder();
        assertThat(choose(orderId, a).fullyHeld()).isTrue();

        assertThat(atpByWarehouse()).as("A falls by the 1,000 held; B does not")
                .containsEntry(a.code(), 0).containsEntry(b.code(), 950);
        assertThat(inventory.availableToPromise(new Sku(sku.sku()), a.code())).isZero();
        assertThat(inventory.availableToPromise(new Sku(sku.sku()), b.code())).isEqualTo(950);
        assertThat(systemAtp()).as("system-wide it falls too").isEqualTo(950);

        // A second order choosing A: A has nothing left, and what only B has is not A's to hold.
        UUID other = confirmedOrder();
        Coverage atA = choose(other, a);
        assertThat(atA.fullyHeld()).isFalse();
        assertThat(atA.lines()).singleElement().satisfies(l -> {
            assertThat(l.heldCartons()).isZero();
            assertThat(l.shortCartons()).isEqualTo(CARTONS);
        });
        assertThat(reservationsOf(other)).as("nothing held, no reservation").isEmpty();
        HoldStockResult direct = inventory.holdAtWarehouse(new HoldStockCommand(Identifiers.newId(), other,
                new Sku(sku.sku()), a.code(), CARTONS, PER_CARTON));
        assertThat(direct.cartonsHeld()).isZero();
        assertThat(direct.reservationIds()).isEmpty();
        assertThat(reservationsOf(other)).isEmpty();
        assertThat(atp(b)).isEqualTo(950);

        // BR-22: nothing is held for it, so its warehouse may still change - and B holds its whole cartons.
        Coverage atB = choose(other, b);
        assertThat(atB.warehouseId()).isEqualTo(b.id());
        assertThat(atB.lines()).singleElement().satisfies(l -> {
            assertThat(l.heldCartons()).isEqualTo(19);
            assertThat(l.shortCartons()).isEqualTo(1);
        });
        assertThat(orderRow(other)).containsEntry("shipping_warehouse_id", b.id());
        assertThat(reservationsOf(other)).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("warehouse_code", b.code()).containsEntry("quantity", 19 * PER_CARTON));
        assertThat(atpByWarehouse()).as("B's 19 cartons are held now").containsEntry(a.code(), 0)
                .containsEntry(b.code(), 0);
        assertThat(systemAtp()).isZero();
    }

    @Test
    @DisplayName("17 §4.2 step 2: only a confirmed order of a contract chooses, and only an active warehouse")
    void chooseIsRefusedWhereItDoesNotApply() {
        stock(a, 1_000);

        UUID pending = draftOrder();
        sign(pending, sent(pending, oneBatch(lineOf(pending)), depositOnSigning()));
        assertThat(status(pending)).as("waits for its deposit").isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThatThrownBy(() -> choose(pending, a)).extracting(ShippingWarehouseIntegrationTest::code)
                .isEqualTo(ErrorCode.ORDER_SHIPPING_WAREHOUSE_NOT_ALLOWED);
        assertThat(ErrorCode.ORDER_SHIPPING_WAREHOUSE_NOT_ALLOWED.httpStatus()).isEqualTo(409);
        assertThat(orderRow(pending).get("shipping_warehouse_id")).isNull();
        assertThat(reservationsOf(pending)).isEmpty();

        UUID noContract = draftOrder();
        assertThatThrownBy(() -> choose(noContract, a)).extracting(ShippingWarehouseIntegrationTest::code)
                .isEqualTo(ErrorCode.ORDER_SHIPPING_WAREHOUSE_NOT_ALLOWED);
        assertThatThrownBy(() -> shippingWarehouses.options(noContract))
                .extracting(ShippingWarehouseIntegrationTest::code)
                .isEqualTo(ErrorCode.ORDER_SHIPPING_WAREHOUSE_NOT_ALLOWED);
        assertThat(reservationsOf(noContract)).isEmpty();

        UUID confirmed = confirmedOrder();
        assertThatThrownBy(() -> shippingWarehouses.choose(confirmed, Identifiers.newId(), sales.userId()))
                .extracting(ShippingWarehouseIntegrationTest::code).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        assertThatThrownBy(() -> choose(confirmed, inactive))
                .as("an inactive warehouse ships nothing").extracting(ShippingWarehouseIntegrationTest::code)
                .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        assertThat(orderRow(confirmed).get("shipping_warehouse_id")).isNull();
        assertThat(reservationsOf(confirmed)).isEmpty();
        assertThat(atp(a)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("17 §4.2 step 5, BR-22: released from its shipping warehouse, fully held - allocated on that warehouse's bins")
    void releaseNeedsTheShippingWarehouseFullyHeld() {
        stock(a, 600);
        stock(a, 400);
        stock(b, 1_000);

        UUID unchosen = confirmedOrder();
        assertThatThrownBy(() -> releases.release(unchosen, a.id(), coordinator))
                .as("no shipping warehouse chosen: nothing held")
                .extracting(ShippingWarehouseIntegrationTest::code).isEqualTo(ErrorCode.ORDER_STOCK_NOT_HELD);
        assertThat(status(unchosen)).isEqualTo(OrderStatus.CONFIRMED);

        UUID orderId = confirmedOrder();
        UUID line = lineOf(orderId);
        assertThat(choose(orderId, a).fullyHeld()).isTrue();
        UUID reservationId = lineReservations(line).get(0);

        assertThatThrownBy(() -> releases.release(orderId, b.id(), coordinator))
                .extracting(ShippingWarehouseIntegrationTest::code)
                .isEqualTo(ErrorCode.ORDER_SHIPPING_WAREHOUSE_MISMATCH);
        assertThat(ErrorCode.ORDER_SHIPPING_WAREHOUSE_MISMATCH.httpStatus()).isEqualTo(409);
        assertThat(status(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationsOf(orderId)).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("status", "HELD"));

        OrderSummary released = releases.release(orderId, a.id(), coordinator);

        assertThat(released.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(orderRow(orderId)).containsEntry("warehouse_id", a.id());
        assertThat(jdbc.queryForObject("SELECT status FROM inventory.stock_reservation WHERE id = ?", String.class,
                reservationId)).as("the SKU reservation became allocations").isEqualTo("ALLOCATED");
        List<Map<String, Object>> allocations = jdbc.queryForList("""
                SELECT r.id, r.quantity, r.status, r.expires_at, s.location_code
                  FROM inventory.stock_reservation r JOIN inventory.stock_item s ON s.id = r.stock_item_id
                 WHERE r.allocated_from_id = ?""", reservationId);
        assertThat(allocations).isNotEmpty();
        assertThat(allocations).extracting(r -> r.get("location_code")).as("on bins of A only").containsOnly(a.bin());
        assertThat(allocations).extracting(r -> r.get("status")).containsOnly("HELD");
        assertThat(allocations.stream().mapToInt(r -> (Integer) r.get("quantity")).sum())
                .isEqualTo(CARTONS * PER_CARTON);
        assertThat(allocations).allSatisfy(r -> assertThat(r.get("expires_at")).isNull());
        assertThat(lineReservations(line)).as("the line now names the allocations")
                .containsExactlyInAnyOrderElementsOf(allocations.stream().map(r -> (UUID) r.get("id")).toList());
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved), 0) FROM inventory.stock_item WHERE sku = ? AND location_code = ?""",
                Long.class, sku.sku(), b.bin())).as("B's stock untouched").isZero();
    }

    @Test
    @DisplayName("17 §4.8: cancelling the order releases its holds at the warehouse, and the warehouse's ATP comes back")
    void cancellingReleasesTheWarehouseHolds() {
        stock(a, 1_000);
        UUID orderId = confirmedOrder();
        assertThat(choose(orderId, a).fullyHeld()).isTrue();
        assertThat(atp(a)).isZero();
        UUID reservationId = (UUID) reservationsOf(orderId).get(0).get("id");

        WithCurrentUser.run(sales, DataScope.ALL, () -> orders.cancel(orderId, new CancelOrderCommand(
                CancellationReasonCode.CUSTOMER_REQUEST, "agreed with the customer", null, sales.userId())));

        assertThat(status(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForMap("""
                SELECT status, release_reason, closed_at IS NOT NULL AS closed FROM inventory.stock_reservation
                 WHERE id = ?""", reservationId)).containsEntry("status", "RELEASED")
                .containsEntry("release_reason", "ORDER_CANCELLED").containsEntry("closed", true);
        assertThat(atp(a)).as("A's stock is free again").isEqualTo(1_000);
        assertThat(systemAtp()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("20 BR-17: the database refuses a warehouse hold above that warehouse's unheld stock, and a moved one")
    void databaseGuardsTheWarehouseHolds() {
        stock(a, 500);
        stock(b, 1_000);
        UUID orderId = draftOrder();

        // 600 at A: the system as a whole has 1,500, but A has 500 - the per-warehouse rule refuses it.
        assertThatThrownBy(() -> insertHold(orderId, a.code(), 600))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("exceed its unheld available stock there")
                .hasMessageContaining(a.code());
        assertThat(reservationsOf(orderId)).isEmpty();

        UUID held = insertHold(orderId, a.code(), 500);
        assertThatThrownBy(() -> insertHold(orderId, a.code(), PER_CARTON))
                .as("A's 500 are all held now")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("exceed its unheld available stock there");
        assertThat(insertHold(orderId, b.code(), PER_CARTON)).as("B still has its own").isNotNull();

        // Where a hold is held never changes.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE inventory.stock_reservation SET warehouse_code = ? WHERE id = ?", b.code(), held)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("does not change");
        assertThat(jdbc.queryForObject("SELECT warehouse_code FROM inventory.stock_reservation WHERE id = ?",
                String.class, held)).isEqualTo(a.code());
    }

    @Test
    @DisplayName("review of 4B-7: a hold released by hand is held again from the freed stock, and the order still ships")
    void heldAgainAfterAHandRelease() {
        stock(a, 1_000);
        UUID orderId = confirmedOrder();
        UUID line = lineOf(orderId);
        assertThat(choose(orderId, a).fullyHeld()).isTrue();

        // 20 §5.6: someone with the right releases the hold by hand; the freed stock goes back to the waiting orders.
        UUID first = lineReservations(line).get(0);
        tx.executeWithoutResult(s -> inventory.release(first, "MANUAL_OVERRIDE"));
        awaitListeners(orderId);
        assertThat(shippingWarehouses.coverage(orderId).fullyHeld()).as("held again from what came free").isTrue();
        assertThat(lineReservations(line)).hasSize(2).contains(first);

        // Again: each new hold is a request of its own, never the id of one released before (uk_stock_reservation_request).
        UUID second = lineReservations(line).stream().filter(id -> !id.equals(first)).findFirst().orElseThrow();
        tx.executeWithoutResult(s -> inventory.release(second, "MANUAL_OVERRIDE"));
        awaitListeners(orderId);
        assertThat(shippingWarehouses.coverage(orderId).fullyHeld()).isTrue();
        assertThat(lineReservations(line)).hasSize(3);

        // Released holds of a contract order do not stop its allocation (only a checkout's lapsed hold does).
        assertThat(releases.release(orderId, a.id(), coordinator).status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
    }

    @Test
    @DisplayName("review of 4B-7: an order released at a warehouse leaves the stock another order holds there by SKU")
    void allocationLeavesAnotherOrdersWarehouseHold() {
        stock(a, 1_000);
        stock(b, 1_000);
        UUID holder = confirmedOrder();
        assertThat(choose(holder, a).fullyHeld()).isTrue();

        // A checkout's hold names no warehouse: promised against the whole system, which still has B's 1,000.
        UUID other = draftOrder();
        tx.executeWithoutResult(s -> inventory.reserve(new com.stockflow.inventory.api.ReserveStockCommand(
                Identifiers.newId(), new Sku(sku.sku()), 1_000, other)));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> inventory.allocate(
                new com.stockflow.inventory.api.AllocateStockCommand(other, a.code()))))
                .as("A's stock is the holder's").extracting(ShippingWarehouseIntegrationTest::code)
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        tx.executeWithoutResult(s -> inventory.allocate(new com.stockflow.inventory.api.AllocateStockCommand(other,
                b.code())));

        assertThat(shippingWarehouses.coverage(holder).fullyHeld()).isTrue();
        assertThat(releases.release(holder, a.id(), coordinator).status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
    }

    @Test
    @DisplayName("review of 4B-7: what the order holds counts as available to it at its own warehouse")
    void optionsCountTheOrdersOwnHolds() {
        stock(a, 1_000);
        stock(b, 990);
        UUID orderId = confirmedOrder();
        assertThat(choose(orderId, a).fullyHeld()).isTrue();
        assertThat(atp(a)).as("to everyone else").isZero();

        ShippingWarehouseService.Options options = shippingWarehouses.options(orderId);

        assertThat(options.chosenWarehouseId()).isEqualTo(a.id());
        assertThat(options.warehouses().get(0).warehouseId()).as("still the best for this order").isEqualTo(a.id());
        assertThat(options.warehouses().get(0).holdsEveryLine()).isTrue();
        assertThat(options.warehouses().get(0).availableCartons()).isEqualTo(CARTONS);
    }

    private static <T> java.util.stream.Collector<T, ?, T> onlyOne() {
        return java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), list -> {
            assertThat(list).hasSize(1);
            return list.get(0);
        });
    }
}
