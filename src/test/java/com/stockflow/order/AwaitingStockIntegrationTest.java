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
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.domain.ProductionOrderStatusChanged;
import com.stockflow.order.internal.service.DeliveryBatchService;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.order.internal.service.ProducerService;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import com.stockflow.order.internal.service.ShippingWarehouseService.BatchCoverage;
import com.stockflow.support.Await;
import com.stockflow.support.CartonRows;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.DemoData;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4A-6 against the real database (kltn-docs 17 §4.2 step 5, §4.3 steps 7 and 10, §4.8 steps 30 and 32, §5.1; BR-04,
 * BR-09, BR-11; team plan QD-16): an order of a contract short of stock waits for it once a production order for its
 * next batch is sent, records once when printing started, holds what is put away, and is released with every carton
 * held; a legacy print line is still not released; cancelling the order cancels the production orders not printing
 * yet and closes those printing - once, whatever a redelivery does.
 *
 * <p>The fixtures are {@code ProductionOrderIntegrationTest}'s - a customer SKU of the test's own on a design the
 * customer really confirmed, an order confirmed by its signed contract (20 cartons of 50: 12 three weeks out, 8 two
 * weeks later), its shipping warehouse chosen - and so are the property source and mocks, so the classes share one
 * context. Mass printing has no domain path yet (4A-4 records progress, 4A-5 starts it on receipt): it is written by SQL
 * and announced the way the repository announces a saved change, in the same transaction.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class AwaitingStockIntegrationTest {

    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(950_000);
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int PER_CARTON = 50;
    private static final int CARTONS = 20;

    private record Warehouse(UUID id, String code, String bin) {
    }

    private record IssuedSku(String sku, UUID uomConversionId) {
    }

    private static Warehouse a;

    @Autowired ProductionOrderService productionOrders;
    @Autowired DeliveryBatchService deliveryBatches;
    @Autowired ProducerService producers;
    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderReleases releases;
    @Autowired ShippingWarehouseService shippingWarehouses;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired ApplicationEventPublisher events;
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
        if (a == null || jdbc.queryForObject("SELECT count(*) FROM warehouse.warehouse WHERE id = ?", Long.class,
                a.id()) != 1) {
            a = warehouse();
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
                    VALUES (?, 'Awaiting Stock Co', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "awaiting-" + customerId + "@example.com", "03" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        sku = issueSku();
        producer = producers.register("PO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT),
                new Producer.Details("Awaiting stock workshop", null, null, null, Producer.Channel.PDF, 14, null));
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures (ProductionOrderIntegrationTest's) ----------------------------------------------------------------

    /** An active warehouse with one active, pickable bin. */
    private Warehouse warehouse() {
        UUID id = Identifiers.newId();
        String code = "W" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase(Locale.ROOT);
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

    /** The test's own customer SKU, on a design the customer confirmed, in cartons of 50. */
    private IssuedSku issueSku() throws Exception {
        ReferenceRows.ProductRow product = tx.execute(s -> ReferenceRows.approvedProduct(entityManager));
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE product.products SET tax_class = 'STANDARD', kind = 'CUSTOMIZABLE' WHERE id = ?",
                product.productId()));
        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmedFor(storage, jdbc, tx, entityManager, customerId,
                product.productId());
        return tx.execute(s -> {
            UUID variant = Identifiers.newId();
            String code = product.sku() + "-W" + variant.toString().substring(24, 28).toUpperCase(Locale.ROOT);
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
            return new IssuedSku(code, carton);
        });
    }

    /**
     * {@code units} pieces of the test's SKU free in the warehouse's bin as whole sealed cartons of 50: goods put away
     * (stock is whole LPN cartons since team plan 4C-2).
     */
    private void stock(int units) {
        assertThat(units % PER_CARTON).as("whole cartons").isZero();
        tx.executeWithoutResult(s -> com.stockflow.support.CartonRows.stockOfUnit(jdbc, sku.sku(),
                sku.uomConversionId(), a.bin(), units / PER_CARTON, null, null,
                Instant.now().minus(java.time.Duration.ofDays(2)), "AVAILABLE"));
    }

    /** A new order of a contract, 12 + 8 cartons, everything paid before delivery: CONFIRMED at signing. */
    private UUID confirmedOrder() {
        UUID orderId = tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), CARTONS * PER_CARTON,
                    Money.vnd(2_000), null).withCartons(sku.uomConversionId(), CARTONS, PER_CARTON)
                    .withTax(BigDecimal.TEN);
            Order order = Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()),
                    customerId, Identifiers.newId(), List.of(line), Instant.now());
            return orderRepository.save(order).id().value();
        });
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        List<SalesContracts.Batch> schedule = List.of(
                new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                        List.of(new SalesContracts.BatchLine(line, 12))),
                new SalesContracts.Batch(today.plusDays(35), today.plusDays(37),
                        List.of(new SalesContracts.BatchLine(line, 8))));
        List<SalesContracts.Installment> plan = List.of(new SalesContracts.Installment("Before delivery",
                "BEFORE_DELIVERY", null, new BigDecimal("100"), null, null));
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, schedule, plan)));
        SalesContracts.ContractView sent = asSales(() -> contracts.send(sales, orderId, drafted.version()));
        byte[] scan = TestPdfs.blank();
        asSales(() -> contracts.sign(sales, orderId, sent.version(),
                IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), today));
        assertThat(status(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        return orderId;
    }

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    private OrderStatus status(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    private ShippingWarehouseService.Coverage choose(UUID orderId) {
        return shippingWarehouses.choose(orderId, a.id(), sales.userId());
    }

    private ProductionOrder drawUp(UUID orderId, int batchSeq) {
        return productionOrders.drawUp(orderId, batchSeq, producer.id(), coordinator);
    }

    /** Drawn up by the coordinator, submitted, approved by the manager: SENT. */
    private ProductionOrder sent(ProductionOrder draft) {
        ProductionOrder pending = productionOrders.submit(draft.id(), draft.version(), coordinator);
        return productionOrders.approve(pending.id(), pending.version(), manager);
    }

    /**
     * Mass printing started: SENT → ACCEPTED → IN_PRODUCTION, written as 4A-4 / 4A-5 will write it - the sales order
     * locked first, the production order's row, and its saved change announced in the same transaction.
     *
     * @return when it started, as stored
     */
    private Instant startMassPrinting(ProductionOrder production) {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT id FROM ordering.customer_order WHERE id = ? FOR UPDATE", UUID.class,
                    production.salesOrderId());
            jdbc.update("UPDATE ordering.production_order SET status = 'ACCEPTED', version = version + 1 WHERE id = ?",
                    production.id());
            events.publishEvent(new ProductionOrderStatusChanged(production.id(), production.code(),
                    production.salesOrderId(), production.deliveryBatchId(), ProductionOrderStatus.SENT,
                    ProductionOrderStatus.ACCEPTED, coordinator, at));
            jdbc.update("""
                    UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = ?,
                           version = version + 1
                     WHERE id = ?""", java.sql.Timestamp.from(at), production.id());
            events.publishEvent(new ProductionOrderStatusChanged(production.id(), production.code(),
                    production.salesOrderId(), production.deliveryBatchId(), ProductionOrderStatus.ACCEPTED,
                    ProductionOrderStatus.IN_PRODUCTION, coordinator, at));
        });
        return at;
    }

    private Instant printingStartedAt(UUID orderId) {
        java.sql.Timestamp at = jdbc.queryForObject(
                "SELECT printing_started_at FROM ordering.customer_order WHERE id = ?", java.sql.Timestamp.class, orderId);
        return at == null ? null : at.toInstant();
    }

    /** The order's status history, "FROM>TO reason by", oldest first. */
    private List<String> history(UUID orderId) {
        return jdbc.queryForList("""
                SELECT COALESCE(from_status, '-') || '>' || to_status || ' ' || COALESCE(reason, '-') || ' '
                       || COALESCE(actor_id::text, '-')
                  FROM ordering.order_status_history WHERE order_id = ? ORDER BY occurred_at, created_at""",
                String.class, orderId);
    }

    private ProductionOrderStatus statusOf(UUID productionOrderId) {
        return productionOrders.detail(productionOrderId).order().status();
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R2 (17 §4.5, BR-26): a moved window moves the needed-by date of its batch's DRAFT "
            + "production order and is the default of one drawn up after it; one already sent keeps its date; coverage "
            + "shows the window in force")
    void aMovedWindowMovesTheDraftsNeededByDate() {
        UUID orderId = confirmedOrder();
        choose(orderId);
        ProductionOrder sentFirst = sent(drawUp(orderId, 1));
        ProductionOrder draftSecond = drawUp(orderId, 2);
        assertThat(sentFirst.needByDate()).isEqualTo(today.plusDays(21));
        assertThat(draftSecond.needByDate()).isEqualTo(today.plusDays(35));

        UUID consent = com.stockflow.support.ChatRows.message(jdbc, tx, customerId, orderId, "CUSTOMER");
        moveWindow(orderId, 1, today.plusDays(24), consent);
        moveWindow(orderId, 2, today.plusDays(42), consent);

        assertThat(productionOrders.detail(sentFirst.id()).order().needByDate())
                .as("sent: the coordinator tells the producer, the date stays").isEqualTo(today.plusDays(21));
        ProductionOrder followed = productionOrders.detail(draftSecond.id()).order();
        assertThat(followed.needByDate()).as("a draft follows the window in force").isEqualTo(today.plusDays(42));
        assertThat(shippingWarehouses.coverage(orderId).batches()).extracting(BatchCoverage::windowStart)
                .as("coverage reads the window in force").containsExactly(today.plusDays(24), today.plusDays(42));

        productionOrders.cancel(followed.id(), followed.version(), "drawn up again", coordinator);
        assertThat(drawUp(orderId, 2).needByDate()).as("drawn up after the move: the new window's first day")
                .isEqualTo(today.plusDays(42));
    }

    /** Sales moves batch {@code seq}'s window to start on {@code start}, with the customer's consent in chat (BR-28). */
    private void moveWindow(UUID orderId, int seq, LocalDate start, UUID consent) {
        UUID batch = jdbc.queryForObject("""
                SELECT b.id FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
                 WHERE c.order_id = ? AND b.seq = ?""", UUID.class, orderId, seq);
        asSales(() -> deliveryBatches.changeWindow(sales, orderId, batch, new DeliveryBatchService.WindowChange(start,
                start.plusDays(2), com.stockflow.order.internal.domain.WindowChangeReason.SELLER_DELAY,
                "The producer is late", null, consent)));
    }


    @Test
    @DisplayName("BR-04, BR-11, 17 §5.1: a short order waits once a production order is sent, records printing once, "
            + "holds what is put away and is released from AWAITING_STOCK with every carton held")
    void waitsForStockThenReleased() {
        UUID orderId = confirmedOrder();
        assertThat(choose(orderId).batches()).as("nothing at the warehouse: both batches short")
                .extracting(BatchCoverage::fullyHeld).containsExactly(false, false);

        ProductionOrder first = sent(drawUp(orderId, 1));

        assertThat(first.status()).isEqualTo(ProductionOrderStatus.SENT);
        assertThat(status(orderId)).as("17 §4.3 step 7: approved and sent, the order waits for stock")
                .isEqualTo(OrderStatus.AWAITING_STOCK);
        assertThat(history(orderId)).last().isEqualTo("CONFIRMED>AWAITING_STOCK PRODUCTION_ORDER_SENT " + manager);

        ProductionOrder second = sent(drawUp(orderId, 2));
        assertThat(second.status()).as("drawn up and sent while the order waits").isEqualTo(ProductionOrderStatus.SENT);
        assertThat(status(orderId)).isEqualTo(OrderStatus.AWAITING_STOCK);
        assertThat(history(orderId)).filteredOn(row -> row.contains(">AWAITING_STOCK ")).as("entered once").hasSize(1);
        assertThatThrownBy(() -> releases.release(orderId, a.id(), coordinator)).as("nothing held yet")
                .extracting(AwaitingStockIntegrationTest::code).isEqualTo(ErrorCode.ORDER_STOCK_NOT_HELD);

        assertThat(printingStartedAt(orderId)).isNull();
        Instant started = startMassPrinting(first);
        assertThat(printingStartedAt(orderId)).as("BR-11: the first production order in production")
                .isEqualTo(started);
        startMassPrinting(second);
        assertThat(printingStartedAt(orderId)).as("written once").isEqualTo(started);
        assertThat(status(orderId)).isEqualTo(OrderStatus.AWAITING_STOCK);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET printing_started_at = NOW() WHERE id = ?", orderId)))
                .as("the database keeps it too").hasMessageContaining("printing started at");

        // The producer's goods arrive and are put away: the waiting order holds them, the earliest batch first.
        stock(CARTONS * PER_CARTON);
        assertThat(shippingWarehouses.topUpWaiting()).isPositive();
        assertThat(shippingWarehouses.coverage(orderId).fullyHeld()).isTrue();
        assertThat(status(orderId)).as("held in full but not released yet: READY_TO_FULFILL means released, so it still waits")
                .isEqualTo(OrderStatus.AWAITING_STOCK);

        assertThat(releases.release(orderId, a.id(), coordinator).status())
                .as("QD-16: a line of the customer's own SKU is stock - not refused as a print line")
                .isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(history(orderId)).last().satisfies(row -> assertThat(row).startsWith("AWAITING_STOCK>READY_TO_FULFILL"));
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity), 0) FROM inventory.stock_reservation
                 WHERE order_id = ? AND allocated_from_id IS NOT NULL AND status = 'HELD'""", Long.class, orderId))
                .as("4B-8 (17 BR-29): the release is batch 1's - its 12 cartons allocated on the bin")
                .isEqualTo(12L * PER_CARTON);
    }

    @Test
    @DisplayName("17 §4.2 step 5, BR-04: only the next batch decides - a production order for a later batch, or one "
            + "sent while the next batch has none of its own, leaves the order CONFIRMED")
    void onlyTheNextBatchDecides() {
        stock(12 * PER_CARTON);
        UUID held = confirmedOrder();
        assertThat(choose(held).batches()).extracting(BatchCoverage::fullyHeld).containsExactly(true, false);
        sent(drawUp(held, 2));
        assertThat(status(held)).as("batch 1 is held in full").isEqualTo(OrderStatus.CONFIRMED);

        UUID shortOrder = confirmedOrder();
        assertThat(choose(shortOrder).batches()).as("the first order holds what there was")
                .extracting(BatchCoverage::fullyHeld).containsExactly(false, false);
        sent(drawUp(shortOrder, 2));
        assertThat(status(shortOrder)).as("BR-04: nothing sent for batch 1 yet").isEqualTo(OrderStatus.CONFIRMED);
        sent(drawUp(shortOrder, 1));
        assertThat(status(shortOrder)).isEqualTo(OrderStatus.AWAITING_STOCK);
    }

    @Test
    @DisplayName("QD-16: a legacy print line - a blank with a design snapshot - is still not released")
    void legacyPrintLineStillRefused() {
        UUID hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        UUID draft = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, DemoData.CUSTOMER_ID, DemoData.PRODUCT_SOFA);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                                                        artifact_manifest)
                    VALUES (?, ?, ?, 'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb)""",
                    snapshot, draft, "a".repeat(64));
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                         currency, placed_at, version, created_at,
                                                         contract_signed_at)
                    VALUES (?, ?, ?, ?, 'CONFIRMED', 20000, 'VND', NOW(), 0, NOW(), NOW())""",
                    order, "SO-20991230-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)),
                    DemoData.CUSTOMER_ID, UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, design_snapshot_id,
                                                     design_checksum) VALUES (?, ?, 'SOFA-3S-GREY', 10, 2000, 'VND', ?, ?)""",
                    Identifiers.newId(), order, snapshot, "a".repeat(64));
        });

        assertThatThrownBy(() -> releases.release(order, hcm, coordinator))
                .extracting(AwaitingStockIntegrationTest::code).isEqualTo(ErrorCode.ORDER_PRINT_GOODS_NOT_RECEIVED);
        assertThat(status(order)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("BR-09, 17 §4.8 step 30: cancelling the order cancels the production orders not mass printing and closes "
            + "those printing, by who cancelled it; a redelivery changes nothing")
    void cancellationStopsTheProductionOrders() {
        UUID orderId = confirmedOrder();
        choose(orderId);
        ProductionOrder printing = sent(drawUp(orderId, 1));
        ProductionOrder drawn = drawUp(orderId, 2);
        ProductionOrder five = sent(productionOrders.edit(drawn.id(), drawn.version(), new ProductionOrder.Draft(
                drawn.producerId(), drawn.needByDate(), null, null, true, null,
                Map.of(drawn.lines().getFirst().orderLineId(), new ProductionOrder.LineEdit(5, null)))));
        ProductionOrder draft = drawUp(orderId, 2);
        assertThat(draft.lines()).singleElement().extracting(ProductionOrder.Line::cartons).isEqualTo(3);
        startMassPrinting(printing);
        assertThat(status(orderId)).isEqualTo(OrderStatus.AWAITING_STOCK);
        long historyBefore = jdbc.queryForObject("""
                SELECT count(*) FROM ordering.production_order_status_history h
                  JOIN ordering.production_order p ON p.id = h.production_order_id WHERE p.sales_order_id = ?""",
                Long.class, orderId);

        asSales(() -> orders.cancel(orderId, new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST,
                "Customer changed supplier", null, sales.userId())));
        assertThat(status(orderId)).isEqualTo(OrderStatus.CANCELLED);

        Await.until("the production orders are stopped", Duration.ofSeconds(20), () ->
                jdbc.queryForObject("""
                        SELECT count(*) FROM ordering.production_order
                         WHERE sales_order_id = ? AND status NOT IN ('CANCELLED', 'CLOSED')""", Long.class, orderId) == 0);
        String orderNumber = orders.findById(orderId).orElseThrow().orderNumber();
        String reason = "Sales order " + orderNumber + " was cancelled: CUSTOMER_REQUEST - Customer changed supplier";
        assertThat(jdbc.queryForMap("SELECT status, closed_by, closing_reason FROM ordering.production_order WHERE id = ?",
                printing.id())).as("mass printing started: closed, not cancelled (BR-06)")
                .containsEntry("status", "CLOSED").containsEntry("closed_by", sales.userId())
                .containsEntry("closing_reason", reason);
        for (UUID cancelled : List.of(five.id(), draft.id())) {
            assertThat(jdbc.queryForMap("""
                    SELECT status, cancelled_by, cancellation_reason FROM ordering.production_order WHERE id = ?""",
                    cancelled)).containsEntry("status", "CANCELLED").containsEntry("cancelled_by", sales.userId())
                    .containsEntry("cancellation_reason", reason);
        }
        assertThat(productionOrders.detail(five.id()).history()).last().satisfies(h -> {
            assertThat(h.from()).isEqualTo(ProductionOrderStatus.SENT);
            assertThat(h.to()).isEqualTo(ProductionOrderStatus.CANCELLED);
            assertThat(h.by()).isEqualTo(sales.userId());
            assertThat(h.reason()).isEqualTo(reason);
        });
        assertThat(productionOrders.detail(draft.id()).history()).last()
                .satisfies(h -> assertThat(h.from()).isEqualTo(ProductionOrderStatus.DRAFT));

        // Delivered again (a restart republishes what was not completed): everything is stopped already.
        Object listener = AopTestUtils.getUltimateTargetObject(context.getBean("salesOrderCancellationListener"));
        ReflectionTestUtils.invokeMethod(listener, "on", new OrderCancelled(orderId, orderNumber, customerId,
                "AWAITING_STOCK", "CUSTOMER_REQUEST", "Customer changed supplier", BigDecimal.ZERO, BigDecimal.ZERO,
                "VND", sales.userId(), Instant.now()));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ordering.production_order_status_history h
                  JOIN ordering.production_order p ON p.id = h.production_order_id WHERE p.sales_order_id = ?""",
                Long.class, orderId)).as("one more row for each of the three, none from the redelivery")
                .isEqualTo(historyBefore + 3);
        assertThat(List.of(statusOf(printing.id()), statusOf(five.id()), statusOf(draft.id())))
                .containsExactly(ProductionOrderStatus.CLOSED, ProductionOrderStatus.CANCELLED,
                        ProductionOrderStatus.CANCELLED);
    }
}
