package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.DelayAlertType;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionScheduleQuery.AlertView;
import com.stockflow.order.internal.service.DelayAlertService;
import com.stockflow.order.internal.service.ProducerService;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.order.internal.service.ProductionScheduleService;
import com.stockflow.order.internal.service.ProductionScheduleService.Entry;
import com.stockflow.order.internal.service.ProductionScheduleService.ShortBatch;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import com.stockflow.support.ConfirmedDesigns;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 4A-7 against the real database (kltn-docs 17 §4.12 steps 47-49, BR-30, B44): the production schedule lists the open
 * production orders by need-by day and the delivery batches still short; the four delay alerts open when their
 * condition starts to hold, close themselves when it stops, are recomputed after the operations that change them and
 * each night - never twice - and tell the holders of the right permission.
 *
 * <p>Orders, contracts, shipping warehouses and production orders are made the way {@code ProductionOrderIntegrationTest}
 * makes them (the fixtures are its own, copied: they are private there), so a batch is short by the real coverage of
 * the holds. Days that must be in the past - a need-by day, a window that ended - are written by SQL: a contract's
 * schedule is fixed once sent ({@code tg_delivery_batch_draft}), so the test moves a window with triggers off for that
 * one statement, the way time passing would.</p>
 *
 * <p>The property source and mocks are those of {@code ProductionOrderIntegrationTest}, so both share one context.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ProductionScheduleIntegrationTest {

    /** Order numbers of this class: one JVM, one run, never the same twice. */
    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(700_000);

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int PER_CARTON = 50;
    private static final int CARTONS = 20;
    private static final Duration WAIT = Duration.ofSeconds(20);

    private record Warehouse(UUID id, String code, String bin) {
    }

    private record IssuedSku(String sku, UUID snapshotId, String checksum, UUID uomConversionId) {
    }

    private static Warehouse a;
    private static Warehouse b;

    @Autowired ProductionScheduleService schedule;
    @Autowired DelayAlertService alerts;
    @Autowired ProductionOrderService productionOrders;
    @Autowired com.stockflow.order.internal.service.ProductionSampleService samples;
    @Autowired ProducerService producers;
    @Autowired SalesContracts contracts;
    @Autowired OrderRepository orderRepository;
    @Autowired com.stockflow.order.api.OrderService orders;
    @Autowired ShippingWarehouseService shippingWarehouses;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired ApplicationContext context;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private UUID coordinator;
    private UUID manager;
    private UUID customerId;
    private IssuedSku sku;
    private Producer producer;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        if (a == null || !exists(a) || !exists(b)) {
            a = warehouse();
            b = warehouse();
        }
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, 'Schedule Test Co', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "schedule-" + customerId + "@example.com", "04" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        sku = issueSku();
        producer = producers.register(producerCode(), pdfProducer("Schedule test workshop", 14));
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures (as ProductionOrderIntegrationTest's) -------------------------------------------------------------

    private boolean exists(Warehouse warehouse) {
        return jdbc.queryForObject("SELECT count(*) FROM warehouse.warehouse WHERE id = ?", Long.class,
                warehouse.id()) == 1;
    }

    private Warehouse warehouse() {
        UUID id = Identifiers.newId();
        String code = "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase(Locale.ROOT);
        String bin = code + "-A01-1-A";
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                     version, created_at)
                    VALUES (?, ?, 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", id, "Warehouse " + code, code);
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

    private IssuedSku issueSku() throws Exception {
        ReferenceRows.ProductRow product = tx.execute(s -> ReferenceRows.approvedProduct(entityManager));
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE product.products SET tax_class = 'STANDARD', kind = 'CUSTOMIZABLE' WHERE id = ?",
                product.productId()));
        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmedFor(storage, jdbc, tx, entityManager, customerId,
                product.productId());
        return tx.execute(s -> {
            UUID variant = Identifiers.newId();
            String code = product.sku() + "-C" + variant.toString().substring(24, 28).toUpperCase(Locale.ROOT);
            jdbc.update("""
                    INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature,
                        position, version, created_at, owner_customer_id, design_snapshot_id, source_variant_id)
                    VALUES (?, ?, ?, 'Printed cup', 'ACTIVE', FALSE, ?, 1, 0, NOW(), ?, ?, ?)""",
                    variant, product.productId(), code, "OWNED-SKU:" + code, customerId, design.snapshotId(),
                    product.variantId());
            UUID item = jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class, code);
            UUID carton = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base,
                        weight_kg, length_cm, width_cm, height_cm, default_purchase, active, version, created_at,
                        default_shipping)
                    VALUES (?, ?, 'CARTON', ?, FALSE, 4.2, 60, 40, 35, FALSE, TRUE, 0, NOW(), TRUE)""",
                    carton, item, PER_CARTON);
            return new IssuedSku(code, design.snapshotId(), design.checksum(), carton);
        });
    }

    private static String producerCode() {
        return "PS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static Producer.Details pdfProducer(String name, int leadDays) {
        return new Producer.Details(name, null, null, null, Producer.Channel.PDF, leadDays, null);
    }

    private UUID draftOrder() {
        return tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), CARTONS * PER_CARTON,
                    Money.vnd(2_000), null).withCartons(sku.uomConversionId(), CARTONS, PER_CARTON)
                    .withTax(BigDecimal.TEN);
            Order order = Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()),
                    customerId, Identifiers.newId(), List.of(line), Instant.now());
            order.applyTerms(PaymentTerm.PREPAID, null, null, null);
            return orderRepository.save(order).id().value();
        });
    }

    private UUID lineOf(UUID orderId) {
        return jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
    }

    private UUID batchId(UUID orderId, int seq) {
        return jdbc.queryForObject("""
                SELECT b.id FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
                 WHERE c.order_id = ? AND b.seq = ?""", UUID.class, orderId, seq);
    }

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    /** 12 cartons three weeks out, the other 8 two weeks later. */
    private List<SalesContracts.Batch> twoBatches(UUID line) {
        return List.of(
                new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                        List.of(new SalesContracts.BatchLine(line, 12))),
                new SalesContracts.Batch(today.plusDays(35), today.plusDays(37),
                        List.of(new SalesContracts.BatchLine(line, 8))));
    }

    /** Every carton in one batch whose window starts {@code days} from today. */
    private Function<UUID, List<SalesContracts.Batch>> oneBatchIn(int days) {
        return line -> List.of(new SalesContracts.Batch(today.plusDays(days), today.plusDays(days + 2),
                List.of(new SalesContracts.BatchLine(line, CARTONS))));
    }

    /** A new order of a contract, signed today and CONFIRMED by it, its shipping warehouse {@code warehouse}. */
    private UUID confirmedOrder(Function<UUID, List<SalesContracts.Batch>> schedule, Warehouse warehouse) {
        UUID orderId = draftOrder();
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, schedule.apply(lineOf(orderId)), List.of(new SalesContracts.Installment(
                        "Before delivery", "BEFORE_DELIVERY", null, new BigDecimal("100"), null, null)))));
        SalesContracts.ContractView sent = asSales(() -> contracts.send(sales, orderId, drafted.version()));
        byte[] scan = TestPdfs.blank();
        asSales(() -> contracts.sign(sales, orderId, sent.version(),
                IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), today));
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.CONFIRMED);
        shippingWarehouses.choose(orderId, warehouse.id(), sales.userId());
        return orderId;
    }

    private ProductionOrder drawUp(UUID orderId, int batchSeq) {
        return productionOrders.drawUp(orderId, batchSeq, producer.id(), coordinator);
    }

    private ProductionOrder approved(ProductionOrder draft) {
        ProductionOrder pending = productionOrders.submit(draft.id(), draft.version(), coordinator);
        return productionOrders.approve(pending.id(), pending.version(), manager);
    }

    private void sql(String statement, Object... args) {
        tx.executeWithoutResult(s -> jdbc.update(statement, args));
    }

    /** A batch's window moved as time passing would: the schedule of a signed contract is fixed, so triggers are off. */
    private void moveWindow(UUID batchId, LocalDate start, LocalDate end) {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE ordering.delivery_batch SET window_start = ?, window_end = ? WHERE id = ?", start, end,
                    batchId);
        });
    }

    /** The open alert of {@code type} on {@code subject}, if any. */
    private UUID openAlert(DelayAlertType type, UUID subject) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM ordering.delay_alert WHERE type = ? AND subject_id = ? AND closed_at IS NULL""",
                UUID.class, type.name(), subject);
        assertThat(ids).as("at most one open %s on %s", type, subject).hasSizeLessThanOrEqualTo(1);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private long alertsOf(DelayAlertType type, UUID subject) {
        return jdbc.queryForObject("SELECT count(*) FROM ordering.delay_alert WHERE type = ? AND subject_id = ?",
                Long.class, type.name(), subject);
    }

    /** An ACTIVE staff account holding {@code role}, assigned to {@code warehouse} when given. */
    private UUID staff(String role, Warehouse warehouse) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        sql("""
                INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                SELECT ?, ?, r.id, 0, NOW() FROM identity.app_role r WHERE r.code = ?""", Identifiers.newId(), user,
                role);
        if (warehouse != null) {
            sql("""
                    INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                    VALUES (?, ?, ?, 0, NOW())""", Identifiers.newId(), user, warehouse.id());
        }
        return user;
    }

    private boolean told(UUID user, UUID alertId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification.user_notification
                 WHERE recipient_id = ? AND dedupe_key = ?""", Long.class, user, "delay-alert:" + alertId) > 0;
    }

    private long messages(UUID user, UUID alertId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification.user_notification
                 WHERE recipient_id = ? AND dedupe_key = ?""", Long.class, user, "delay-alert:" + alertId);
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    private ProductionScheduleService.Schedule scheduleOf(UUID producerId, UUID warehouseId, LocalDate from,
                                                          LocalDate to, boolean alertsOnly) {
        return asSales(() -> schedule.schedule(producerId, warehouseId, from, to, alertsOnly));
    }

    private static List<UUID> productionIds(ProductionScheduleService.Schedule found) {
        return found.productionOrders().stream().map(e -> e.production().productionOrderId()).toList();
    }

    private static List<ShortBatch> shortOf(ProductionScheduleService.Schedule found, UUID orderId) {
        return found.shortBatches().stream().filter(s -> s.salesOrderId().equals(orderId)).toList();
    }

    /**
     * The sweep job, through its proxy (it is package-private in the service package). Its lock is held at least ten
     * seconds after a run ({@code lockAtLeastFor}), during which a second run is skipped, as it should be on a second
     * instance; the test plays the next night by releasing it first.
     */
    private void sweep() throws Exception {
        // Expired rather than deleted: ShedLock remembers the row it inserted and only updates it afterwards. A day back,
        // not a second: with usingDbTime ShedLock compares against timezone('utc', CURRENT_TIMESTAMP), a UTC wall clock
        // read in the session's zone (the JVM's), so its frame sits hours away from NOW() on a machine east of UTC.
        sql("UPDATE platform.shedlock SET lock_until = NOW() - INTERVAL '1 day' WHERE name = 'order.delay-alert-sweep'");
        Object job = context.getBean("delayAlertSweepJob");
        job.getClass().getMethod("sweep").invoke(job);
    }

    // ---- the schedule -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("step 47: open production orders by need-by day, and the batches still short that nothing covers")
    void scheduleListsProductionOrdersAndShortBatches() {
        UUID orderId = confirmedOrder(this::twoBatches, a);
        UUID first = batchId(orderId, 1);
        UUID second = batchId(orderId, 2);

        ProductionScheduleService.Schedule before = scheduleOf(null, a.id(), null, null, false);
        assertThat(shortOf(before, orderId)).as("nothing held, nothing made: both batches short")
                .extracting(ShortBatch::seq, ShortBatch::shortCartons, ShortBatch::windowStart, ShortBatch::warehouseCode)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 12, today.plusDays(21), a.code()),
                        org.assertj.core.groups.Tuple.tuple(2, 8, today.plusDays(35), a.code()));
        assertThat(shortOf(before, orderId).getFirst().lines()).singleElement()
                .satisfies(line -> assertThat(line.sku()).isEqualTo(sku.sku()))
                .satisfies(line -> assertThat(line.cartons()).isEqualTo(12));

        ProductionOrder forFirst = drawUp(orderId, 1);
        ProductionOrder forSecond = drawUp(orderId, 2);
        ProductionScheduleService.Schedule after = scheduleOf(producer.id(), null, null, null, false);
        assertThat(productionIds(after)).as("by need-by day").containsExactly(forFirst.id(), forSecond.id());
        Entry entry = after.productionOrders().getFirst();
        assertThat(entry.production().needByDate()).isEqualTo(today.plusDays(21));
        assertThat(entry.production().deliveryBatchId()).isEqualTo(first);
        assertThat(entry.production().batchSeq()).isEqualTo(1);
        assertThat(entry.production().windowEnd()).isEqualTo(today.plusDays(23));
        assertThat(entry.production().cartons()).isEqualTo(12);
        assertThat(entry.production().receivedCartons()).isZero();
        assertThat(entry.production().producerCode()).isEqualTo(producer.code());
        assertThat(entry.production().orderNumber()).isNotBlank();
        assertThat(entry.warehouseCode()).isEqualTo(a.code());
        assertThat(entry.alerts()).as("three weeks out, nothing late").isEmpty();
        assertThat(shortOf(scheduleOf(null, a.id(), null, null, false), orderId))
                .as("both batches are covered now").isEmpty();
        assertThat(after.shortBatches().stream().map(ShortBatch::deliveryBatchId)).doesNotContain(first, second);

        assertThat(productionIds(scheduleOf(producer.id(), null, today.plusDays(30), null, false)))
                .as("need-by day from").containsExactly(forSecond.id());
        assertThat(productionIds(scheduleOf(producer.id(), null, null, today.plusDays(30), false)))
                .as("need-by day to").containsExactly(forFirst.id());
        assertThat(productionIds(scheduleOf(producer.id(), b.id(), null, null, false))).as("another warehouse")
                .isEmpty();
        Producer other = producers.register(producerCode(), pdfProducer("Other workshop", 14));
        assertThat(productionIds(scheduleOf(other.id(), null, null, null, false))).as("another producer").isEmpty();
        assertThat(productionIds(scheduleOf(producer.id(), null, null, null, true))).as("only with an alert")
                .isEmpty();

        productionOrders.cancel(forSecond.id(), forSecond.version(), "The customer changed the file", coordinator);
        ProductionScheduleService.Schedule cancelled = scheduleOf(producer.id(), a.id(), null, null, false);
        assertThat(productionIds(cancelled)).as("a cancelled one is not open").containsExactly(forFirst.id());
        assertThat(shortOf(cancelled, orderId)).extracting(ShortBatch::seq).as("its batch is short again")
                .containsExactly(2);
        assertThat(shortOf(scheduleOf(null, a.id(), today.plusDays(22), today.plusDays(40), false), orderId))
                .as("the window's first day in the range").extracting(ShortBatch::seq).containsExactly(2);
        assertThat(shortOf(scheduleOf(null, a.id(), null, today.plusDays(30), false), orderId)).isEmpty();

        assertThatThrownBy(() -> scheduleOf(null, null, today, today.minusDays(1), false))
                .extracting(ProductionScheduleIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("step 47: a warehouse-bound manager sees what concerns their warehouses only")
    void scopedToTheWarehouse() {
        UUID atA = confirmedOrder(this::twoBatches, a);
        UUID atB = confirmedOrder(oneBatchIn(5), b);
        ProductionOrder madeAtA = drawUp(atA, 1);
        ProductionOrder madeAtB = drawUp(atB, 1);
        sql("UPDATE ordering.production_order SET need_by_date = ? WHERE id = ?", today.minusDays(1), madeAtB.id());
        approved(productionOrders.detail(madeAtB.id()).order());
        alerts.recompute(atB);
        alerts.recompute(atA);
        assertThat(openAlert(DelayAlertType.PRODUCTION_LATE, madeAtB.id())).isNotNull();

        UUID managerId = tx.execute(s -> ReferenceRows.user(entityManager));
        CurrentUser managerOfB = new CurrentUser(managerId, "wm-" + managerId, Set.of(Role.WAREHOUSE_MANAGER),
                Set.of(), DataScope.WAREHOUSE, Set.of(b.code()), Set.of(b.id()));
        ProductionScheduleService.Schedule seen = WithCurrentUser.call(managerOfB, DataScope.ALL,
                () -> schedule.schedule(producer.id(), null, null, null, false));
        assertThat(productionIds(seen)).containsExactly(madeAtB.id());
        assertThat(seen.productionOrders().getFirst().alerts()).extracting(ProductionScheduleService.Flag::type)
                .containsExactly(DelayAlertType.PRODUCTION_LATE);
        ProductionScheduleService.Schedule unscoped = WithCurrentUser.call(managerOfB, DataScope.ALL,
                () -> schedule.schedule(null, null, null, null, false));
        assertThat(shortOf(unscoped, atA)).as("an order shipping from A").isEmpty();
        assertThat(productionIds(unscoped)).doesNotContain(madeAtA.id());

        List<AlertView> flags = WithCurrentUser.call(managerOfB, DataScope.ALL,
                () -> alerts.list(atB, null, true, 0, 50).items());
        assertThat(flags).extracting(AlertView::type).containsExactly(DelayAlertType.PRODUCTION_LATE);
        assertThat(flags.getFirst().productionOrderCode()).isEqualTo(madeAtB.code());
        assertThat(flags.getFirst().warehouseId()).isEqualTo(b.id());
        assertThat(WithCurrentUser.call(managerOfB, DataScope.ALL, () -> alerts.list(atA, null, false, 0, 50).items()))
                .isEmpty();

        CurrentUser assignedNowhere = new CurrentUser(managerId, "wm-" + managerId, Set.of(Role.WAREHOUSE_MANAGER),
                Set.of(), DataScope.WAREHOUSE, Set.of(), Set.of());
        ProductionScheduleService.Schedule nothing = WithCurrentUser.call(assignedNowhere, DataScope.ALL,
                () -> schedule.schedule(null, null, null, null, false));
        assertThat(nothing.productionOrders()).isEmpty();
        assertThat(nothing.shortBatches()).isEmpty();
        assertThat(asSales(() -> alerts.list(atB, DelayAlertType.PRODUCTION_LATE, true, 0, 50).items()))
                .as("a caller scoped to ALL is not limited").hasSize(1);
    }

    // ---- the alerts -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("PO_NEEDED opens within the lead time, tells production's followers, closes itself once drawn up")
    void poNeededOpensAndClosesItself() {
        UUID coordinatorUser = staff("PRODUCTION_COORDINATOR", null);
        UUID salesUser = staff("SALES_STAFF", null);
        UUID managerOfA = staff("WAREHOUSE_MANAGER", a);

        UUID later = confirmedOrder(oneBatchIn(15), a);
        alerts.recompute(later);
        assertThat(openAlert(DelayAlertType.PO_NEEDED, batchId(later, 1))).as("15 days out, lead time 14").isNull();

        UUID orderId = confirmedOrder(oneBatchIn(10), a);
        UUID batch = batchId(orderId, 1);
        alerts.recompute(orderId);
        UUID opened = openAlert(DelayAlertType.PO_NEEDED, batch);
        assertThat(opened).as("short, uncovered, 10 days out").isNotNull();
        assertThat(alerts.recompute(orderId).opened()).as("recomputed again: not opened twice").isZero();
        assertThat(openAlert(DelayAlertType.PO_NEEDED, batch)).isEqualTo(opened);
        assertThat(shortOf(scheduleOf(null, a.id(), null, null, true), orderId)).singleElement()
                .satisfies(s -> assertThat(s.alerts()).extracting(ProductionScheduleService.Flag::type)
                        .containsExactly(DelayAlertType.PO_NEEDED));

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(told(coordinatorUser, opened)).as("the coordinator").isTrue();
            assertThat(told(salesUser, opened)).as("Sales").isTrue();
        });
        assertThat(told(managerOfA, opened)).as("BR-30 names no warehouse manager for PO_NEEDED").isFalse();
        assertThat(jdbc.queryForMap("""
                SELECT category, severity, link, source_type, source_id FROM notification.user_notification
                 WHERE recipient_id = ? AND dedupe_key = ?""", salesUser, "delay-alert:" + opened))
                .containsEntry("category", "DELAY_ALERT").containsEntry("severity", "WARN")
                .containsEntry("link", "/production/schedule").containsEntry("source_id", opened.toString());

        // Drawn up: the listener recomputes after the commit, and the alert closes itself.
        ProductionOrder made = drawUp(orderId, 1);
        await().atMost(WAIT).untilAsserted(() -> assertThat(openAlert(DelayAlertType.PO_NEEDED, batch)).isNull());
        assertThat(jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM ordering.delay_alert WHERE id = ?",
                Boolean.class, opened)).isTrue();

        // Cancelled: the batch is uncovered again - a new alert, told again.
        productionOrders.cancel(made.id(), productionOrders.detail(made.id()).order().version(), "Wrong producer",
                coordinator);
        await().atMost(WAIT).untilAsserted(() -> assertThat(openAlert(DelayAlertType.PO_NEEDED, batch)).isNotNull());
        UUID again = openAlert(DelayAlertType.PO_NEEDED, batch);
        assertThat(again).isNotEqualTo(opened);
        assertThat(alertsOf(DelayAlertType.PO_NEEDED, batch)).isEqualTo(2);
        await().atMost(WAIT).untilAsserted(() -> assertThat(told(salesUser, again)).isTrue());
    }

    @Test
    @DisplayName("PRODUCTION_LATE and AT_RISK open and close on the production order's days and status")
    void productionLateAndAtRisk() {
        UUID orderId = confirmedOrder(this::twoBatches, a);
        ProductionOrder draft = drawUp(orderId, 1);
        // The need-by day already passed (B33: editable while a draft).
        sql("UPDATE ordering.production_order SET need_by_date = ? WHERE id = ?", today.minusDays(1), draft.id());
        alerts.recompute(orderId);
        assertThat(openAlert(DelayAlertType.PRODUCTION_LATE, draft.id())).as("a draft is with no producer").isNull();

        ProductionOrder sent = approved(productionOrders.detail(draft.id()).order());
        alerts.recompute(orderId);
        UUID late = openAlert(DelayAlertType.PRODUCTION_LATE, sent.id());
        assertThat(late).as("sent, past its need-by day").isNotNull();
        assertThat(openAlert(DelayAlertType.AT_RISK, sent.id())).as("no expected day yet").isNull();

        assertThatThrownBy(() -> sql("UPDATE ordering.production_order SET expected_completion_date = ? WHERE id = ?",
                today, sent.id())).as("the producer gives the day when it accepts").isInstanceOf(
                DataAccessException.class).hasMessageContaining("ck_production_order_expected_completion");
        // Accepted the way the coordinator records it (4A-4): the database refuses the status written around it.
        productionOrders.accept(sent.id(), productionOrders.detail(sent.id()).order().version(), today.plusDays(5),
                coordinator);
        alerts.recompute(orderId);
        UUID risk = openAlert(DelayAlertType.AT_RISK, sent.id());
        assertThat(risk).as("expected after the need-by day").isNotNull();
        assertThat(openAlert(DelayAlertType.PRODUCTION_LATE, sent.id())).as("the same episode").isEqualTo(late);

        // By SQL: the service takes no day in the past, and this one has to be on or before the need-by day, gone already.
        sql("UPDATE ordering.production_order SET expected_completion_date = ? WHERE id = ?", today.minusDays(1),
                sent.id());
        alerts.recompute(orderId);
        assertThat(openAlert(DelayAlertType.AT_RISK, sent.id())).as("updated to the need-by day: closed").isNull();
        assertThat(openAlert(DelayAlertType.PRODUCTION_LATE, sent.id())).isEqualTo(late);

        Map<String, Object> flagged = jdbc.queryForMap("SELECT type, closed_at FROM ordering.delay_alert WHERE id = ?",
                risk);
        assertThat(flagged.get("closed_at")).isNotNull();

        // Mass printing started, then closed short: the listener closes PRODUCTION_LATE.
        // Through the coordinator's start or the customer's approval of the sample (4A-4), as production takes it.
        ProductionOrder printing = com.stockflow.support.PrintSamples.started(productionOrders, samples,
                productionOrders.detail(sent.id()).order(), coordinator,
                com.stockflow.support.PrintSamples.portalUser(jdbc, tx, entityManager, customerId));
        productionOrders.close(printing.id(), printing.version(), "The producer stopped", coordinator);
        await().atMost(WAIT).untilAsserted(() -> assertThat(openAlert(DelayAlertType.PRODUCTION_LATE, sent.id()))
                .isNull());
    }

    @Test
    @DisplayName("DELIVERY_LATE: past the window's end, told to Sales and the shipping warehouse's manager; the sweep "
            + "is idempotent and closes it once the window moved")
    void deliveryLateAndTheNightlySweep() throws Exception {
        UUID coordinatorUser = staff("PRODUCTION_COORDINATOR", null);
        UUID salesUser = staff("SALES_STAFF", null);
        UUID managerOfA = staff("WAREHOUSE_MANAGER", a);
        UUID managerOfB = staff("WAREHOUSE_MANAGER", b);

        UUID orderId = confirmedOrder(this::twoBatches, a);
        UUID first = batchId(orderId, 1);
        UUID second = batchId(orderId, 2);
        drawUp(orderId, 2);
        moveWindow(first, today.minusDays(5), today.minusDays(2));

        sweep();
        UUID late = openAlert(DelayAlertType.DELIVERY_LATE, first);
        assertThat(late).as("the window ended, nothing handed over").isNotNull();
        assertThat(openAlert(DelayAlertType.PO_NEEDED, first)).as("and it is short, uncovered, its window started")
                .isNotNull();
        assertThat(openAlert(DelayAlertType.DELIVERY_LATE, second)).isNull();
        assertThat(openAlert(DelayAlertType.PO_NEEDED, second)).as("covered by its production order").isNull();

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(told(salesUser, late)).as("Sales").isTrue();
            assertThat(told(managerOfA, late)).as("the shipping warehouse's manager").isTrue();
        });
        assertThat(told(managerOfB, late)).as("another warehouse's manager").isFalse();
        assertThat(told(coordinatorUser, late)).as("BR-30: not the coordinator").isFalse();

        sweep();
        assertThat(alertsOf(DelayAlertType.DELIVERY_LATE, first)).as("the sweep run twice opens nothing twice")
                .isEqualTo(1);
        assertThat(alertsOf(DelayAlertType.PO_NEEDED, first)).isEqualTo(1);
        assertThat(messages(salesUser, late)).isEqualTo(1);
        assertThat(asSales(() -> alerts.list(orderId, null, true, 0, 50).items())).extracting(AlertView::type)
                .containsExactlyInAnyOrder(DelayAlertType.DELIVERY_LATE, DelayAlertType.PO_NEEDED);

        assertThatThrownBy(() -> sql("""
                INSERT INTO ordering.delay_alert (id, type, delivery_batch_id, sales_order_id, opened_at)
                VALUES (?, 'DELIVERY_LATE', ?, ?, NOW())""", Identifiers.newId(), first, orderId))
                .as("one open alert per type and subject").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_delay_alert_open");

        // The window moved (BR-28, by SQL until it is built): the next sweep closes it.
        moveWindow(first, today.plusDays(30), today.plusDays(32));
        sweep();
        assertThat(openAlert(DelayAlertType.DELIVERY_LATE, first)).isNull();
        assertThat(openAlert(DelayAlertType.PO_NEEDED, first)).as("16 days out again").isNull();
        assertThat(asSales(() -> alerts.list(orderId, DelayAlertType.DELIVERY_LATE, false, 0, 50).items()))
                .singleElement().satisfies(view -> assertThat(view.closedAt()).isNotNull());
        assertThatThrownBy(() -> sql("UPDATE ordering.delay_alert SET closed_at = NULL WHERE id = ?", late))
                .as("closed once, never reopened").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("never reopened");
    }

    @Test
    @DisplayName("the schedule's readers and the delay flags' readers")
    void grants() {
        assertThat(holders("production-schedule:READ")).containsExactly("PRODUCTION_COORDINATOR", "SALES_MANAGER",
                "SALES_STAFF", "SYSTEM_ADMIN", "WAREHOUSE_MANAGER");
        assertThat(holders("production-schedule:VIEW_PAGE")).containsExactly("PRODUCTION_COORDINATOR",
                "SALES_MANAGER", "SALES_STAFF", "SYSTEM_ADMIN", "WAREHOUSE_MANAGER");
        assertThat(holders("delay-alerts:READ")).as("BR-30: DELIVERY_LATE is not the coordinator's")
                .containsExactly("SALES_MANAGER", "SALES_STAFF", "SYSTEM_ADMIN", "WAREHOUSE_MANAGER");
    }

    @Test
    @DisplayName("each list is cut at the limit, the most urgent kept, and says so; a limit out of range is refused")
    void boundedByTheLimit() {
        Warehouse own = warehouse();
        UUID sooner = confirmedOrder(oneBatchIn(20), own);
        UUID later = confirmedOrder(oneBatchIn(30), own);
        ProductionOrder madeSooner = drawUp(confirmedOrder(oneBatchIn(25), own), 1);
        drawUp(confirmedOrder(oneBatchIn(35), own), 1);

        ProductionScheduleService.Schedule all = asSales(() -> schedule.schedule(null, own.id(), null, null, false, 10));
        assertThat(all.truncated()).isFalse();
        assertThat(all.shortBatches()).extracting(ShortBatch::salesOrderId).containsExactly(sooner, later);
        assertThat(all.productionOrders()).hasSize(2);

        ProductionScheduleService.Schedule cut = asSales(() -> schedule.schedule(null, own.id(), null, null, false, 1));
        assertThat(cut.truncated()).isTrue();
        assertThat(cut.shortBatches()).extracting(ShortBatch::salesOrderId).as("the earliest window kept")
                .containsExactly(sooner);
        assertThat(productionIds(cut)).as("the earliest need-by day kept").containsExactly(madeSooner.id());

        assertThat(asSales(() -> schedule.schedule(null, own.id(), today.plusDays(26), null, false, 1)).shortBatches())
                .as("candidates narrowed by the range before the limit").extracting(ShortBatch::salesOrderId)
                .containsExactly(later);
        assertThatThrownBy(() -> asSales(() -> schedule.schedule(null, own.id(), null, null, false, 0)))
                .extracting(ProductionScheduleIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> asSales(() -> schedule.schedule(null, own.id(), null, null, false,
                ProductionScheduleService.MAX_LIMIT + 1))).extracting(ProductionScheduleIntegrationTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("an order on hold is followed only when held after confirmation; a credit hold at signing is not")
    void heldOrders() {
        UUID orderId = confirmedOrder(oneBatchIn(10), a);
        UUID batch = batchId(orderId, 1);
        awaitListeners(orderId);
        alerts.recompute(orderId);
        UUID opened = openAlert(DelayAlertType.PO_NEEDED, batch);
        assertThat(opened).isNotNull();

        hold(orderId, "PAYMENT", "CONFIRMED");
        assertThat(alerts.recompute(orderId)).as("held for payment after confirmation: still owes its batch")
                .isEqualTo(new DelayAlertService.Recomputed(0, 0));
        assertThat(openAlert(DelayAlertType.PO_NEEDED, batch)).isEqualTo(opened);
        assertThat(shortOf(scheduleOf(null, a.id(), null, null, false), orderId)).hasSize(1);
        assertThat(alerts.sweepCandidates()).contains(orderId);

        hold(orderId, "CREDIT", null);
        assertThat(alerts.recompute(orderId).closed()).as("a credit hold at signing: not confirmed, not followed")
                .isEqualTo(1);
        assertThat(openAlert(DelayAlertType.PO_NEEDED, batch)).isNull();
        assertThat(shortOf(scheduleOf(null, a.id(), null, null, false), orderId)).isEmpty();
        assertThat(alerts.sweepCandidates()).doesNotContain(orderId);
    }

    /** The order put on hold for {@code reason}, from {@code before} (null: a credit hold placed at signing) - by SQL. */
    private void hold(UUID orderId, String reason, String before) {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE ordering.customer_order SET status = 'ON_HOLD', hold_reason = ?, status_before_hold = ? "
                    + "WHERE id = ?", reason, before, orderId);
        });
    }

    @Test
    @DisplayName("17 §4.2 step 5: an order released for its first batch is still followed - a later batch short and "
            + "uncovered keeps its PO_NEEDED, stays on the schedule, and may be drawn up")
    void followedPastRelease() {
        UUID orderId = confirmedOrder(line -> List.of(
                new SalesContracts.Batch(today.plusDays(5), today.plusDays(6),
                        List.of(new SalesContracts.BatchLine(line, 8))),
                new SalesContracts.Batch(today.plusDays(10), today.plusDays(11),
                        List.of(new SalesContracts.BatchLine(line, 8))),
                new SalesContracts.Batch(today.plusDays(40), today.plusDays(41),
                        List.of(new SalesContracts.BatchLine(line, 4)))), a);
        UUID first = batchId(orderId, 1);
        UUID second = batchId(orderId, 2);
        drawUp(orderId, 1);
        awaitListeners(orderId);
        alerts.recompute(orderId);
        UUID opened = openAlert(DelayAlertType.PO_NEEDED, second);
        assertThat(opened).as("batch 2: short, uncovered, 10 days out").isNotNull();
        assertThat(openAlert(DelayAlertType.PO_NEEDED, first)).as("batch 1: its production order covers it").isNull();

        // Released for batch 1 while batch 2 is still short (until 4B-8 releases batch by batch: by SQL).
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE ordering.customer_order SET status = 'READY_TO_FULFILL', released_at = NOW(), "
                    + "released_by = ?, warehouse_id = shipping_warehouse_id WHERE id = ?", manager, orderId);
        });
        assertThat(alerts.recompute(orderId)).as("the release's recompute closes nothing")
                .isEqualTo(new DelayAlertService.Recomputed(0, 0));
        assertThat(openAlert(DelayAlertType.PO_NEEDED, second)).isEqualTo(opened);
        assertThat(shortOf(scheduleOf(null, a.id(), null, null, false), orderId)).extracting(ShortBatch::seq)
                .as("still on the schedule").containsExactly(2, 3);

        ProductionOrder forSecond = drawUp(orderId, 2);
        assertThat(forSecond.deliveryBatchId()).as("drawn up batch by batch past release").isEqualTo(second);
        alerts.recompute(orderId);
        assertThat(openAlert(DelayAlertType.PO_NEEDED, second)).as("covered now").isNull();
    }

    @Test
    @DisplayName("step 48: the order's own writes are triggers - signed and its warehouse chosen, PO_NEEDED opens with "
            + "no recompute asked for")
    void theOrdersWritesAreTriggers() {
        UUID salesUser = staff("SALES_STAFF", null);
        UUID orderId = confirmedOrder(oneBatchIn(10), a);
        UUID batch = batchId(orderId, 1);

        await().atMost(WAIT).untilAsserted(() -> assertThat(openAlert(DelayAlertType.PO_NEEDED, batch)).isNotNull());
        UUID opened = openAlert(DelayAlertType.PO_NEEDED, batch);
        await().atMost(WAIT).untilAsserted(() -> assertThat(told(salesUser, opened)).isTrue());
    }

    @Test
    @DisplayName("a recompute joins its caller's transaction: nothing of it is seen outside, and it rolls back with it")
    void recomputeJoinsTheCallersTransaction() {
        UUID orderId = confirmedOrder(oneBatchIn(15), a);
        UUID batch = batchId(orderId, 1);
        awaitListeners(orderId);
        moveWindow(batch, today.minusDays(5), today.minusDays(2));

        tx.executeWithoutResult(status -> {
            assertThat(alerts.recompute(orderId).opened()).as("DELIVERY_LATE and PO_NEEDED").isEqualTo(2);
            assertThat(openAlert(DelayAlertType.DELIVERY_LATE, batch)).as("seen inside").isNotNull();
            assertThat(committedOpen(batch)).as("not committed by a transaction of its own").isZero();
            status.setRollbackOnly();
        });
        assertThat(openAlert(DelayAlertType.DELIVERY_LATE, batch)).as("rolled back with its caller").isNull();
        assertThat(alerts.recompute(orderId).opened()).as("on its own: a transaction of its own").isEqualTo(2);
        assertThat(committedOpen(batch)).isEqualTo(2);
    }

    /** Open alerts on {@code subject} as another connection sees them: committed ones only. */
    private long committedOpen(UUID subject) {
        TransactionTemplate other = new TransactionTemplate(tx.getTransactionManager());
        other.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        return other.execute(s -> jdbc.queryForObject(
                "SELECT count(*) FROM ordering.delay_alert WHERE subject_id = ? AND closed_at IS NULL", Long.class,
                subject));
    }

    /** Every listener of the events naming {@code id} has finished, so none of them races what the test does next. */
    private void awaitListeners(UUID id) {
        await().atMost(WAIT).until(() -> jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE serialized_event LIKE ? AND completion_date IS NULL""",
                Long.class, "%" + id + "%") == 0);
    }

    private List<String> holders(String permission) {
        return jdbc.queryForList("""
                SELECT r.code FROM identity.role_permission rp
                  JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE p.code = ? ORDER BY 1""", String.class, permission);
    }
}
