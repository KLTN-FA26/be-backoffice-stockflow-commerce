package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.inventory.internal.service.PutawayService;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.order.internal.service.ProducerService;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.procurement.internal.service.GoodsReceipts.NewLine;
import com.stockflow.procurement.internal.service.GoodsReceipts.NewPackage;
import com.stockflow.procurement.internal.service.GoodsReceipts.ReceiptView;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4A-5 and 4A-6 together against the real database, through the real code paths only (no SQL standing in for a state
 * change): a short order of a contract waits for its production order, the goods received against it start mass
 * printing and close the production order, are put away and held, and the order is released; and a cancellation that
 * finds a goods receipt in progress leaves the production order to that receipt, which stops it when it is settled
 * (kltn-docs 17 sections 4.2-4.3, 4.8 step 30; BR-04, BR-09, BR-11; 03 edge cases).
 *
 * <p>Fixtures are {@code ProductionReceiptIntegrationTest}'s (an order of a contract, 20 cartons of 50, scheduled
 * 12 + 8, shipping from warehouse A with a RECEIVING area and one roomy bin) and so are the property source and mocks,
 * so the classes share one context. Nothing runs in a test transaction: cartons are checked at commit.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class AwaitingStockReceiptIntegrationTest {

    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(980_000);
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int PER_CARTON = 50;
    private static final int CARTONS = 20;

    private record Warehouse(UUID id, String code, String receiving, String bin) {
    }

    private record IssuedSku(String sku, UUID itemId, UUID uomConversionId) {
    }

    private static Warehouse a;

    @Autowired GoodsReceipts receipts;
    @Autowired ProductionOrderService productionOrders;
    @Autowired ProducerService producers;
    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderReleases releases;
    @Autowired ShippingWarehouseService shippingWarehouses;
    @Autowired PutawayService putaway;
    @Autowired PutawayTasks putawayTasks;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private UUID coordinator;
    private UUID manager;
    private UUID clerk;
    private UUID customerId;
    private IssuedSku sku;
    private Producer producer;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        if (a == null || !exists(a)) {
            a = warehouse();
        }
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, 'Awaiting Receipt Co', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "awaiting-receipt-" + customerId + "@example.com", "03" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        sku = issueSku();
        producer = producers.register("AR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT),
                new Producer.Details("Awaiting receipt workshop", null, null, null, Producer.Channel.PDF, 14, null));
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures (ProductionReceiptIntegrationTest's) ----------------------------------------------------------------

    private boolean exists(Warehouse warehouse) {
        return jdbc.queryForObject("SELECT count(*) FROM warehouse.warehouse WHERE id = ?", Long.class,
                warehouse.id()) == 1;
    }

    /** An active warehouse with a RECEIVING area and one active, pickable bin that takes putaways. */
    private Warehouse warehouse() {
        UUID id = Identifiers.newId();
        String code = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase(Locale.ROOT);
        String receivingArea = code + "-RCV01";
        String bin = code + "-B01-1-A";
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                     version, created_at)
                    VALUES (?, ?, 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", id, "Warehouse " + code, code);
            UUID area = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,
                                                            is_pickable, is_putaway_target, status)
                    VALUES (?, ?, 'AREA', ?, 'NORMAL', FALSE, FALSE, 'ACTIVE')""", area, id, receivingArea);
            jdbc.update("""
                    INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length,
                                                rotation, is_obstacle, status)
                    VALUES (?, ?, ?, 'RCV01', 'RECEIVING', 'Receiving', 1, 1, 5, 5, 0, FALSE, 'ACTIVE')""",
                    Identifiers.newId(), id, area);
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
                    VALUES (?, ?, ?, 'B01', 'Shelf', 5, 30, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'NORMAL',
                            'ACTIVE')""", shelf, id, zone);
            jdbc.update("""
                    INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
                    VALUES (?, ?, 1, 0, 1.4, 5000)""", level, shelf);
            jdbc.update("""
                    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,
                                                            capacity_units, max_weight, is_pickable,
                                                            is_putaway_target, status)
                    VALUES (?, ?, 'BIN', ?, 'NORMAL', 100000, 5000, TRUE, TRUE, 'ACTIVE')""", location, id, bin);
            jdbc.update("""
                    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
                    VALUES (?, ?, ?, 'A', 0, 0, 5, 1.2, 0, 'PALLET')""", Identifiers.newId(), level, location);
        });
        return new Warehouse(id, code, receivingArea, bin);
    }

    /**
     * A customer SKU as the design approval leaves it, made to a design the customer really confirmed, with its
     * logistics complete (a receipt line refuses an item without them, BR-03) and a CARTON of 50.
     */
    private IssuedSku issueSku() throws Exception {
        ReferenceRows.ProductRow product = tx.execute(s -> ReferenceRows.approvedProduct(entityManager));
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE product.products SET tax_class = 'STANDARD', kind = 'CUSTOMIZABLE' WHERE id = ?",
                product.productId()));
        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmedFor(storage, jdbc, tx, entityManager, customerId,
                product.productId());
        return tx.execute(s -> {
            UUID variant = Identifiers.newId();
            String code = product.sku() + "-R" + variant.toString().substring(24, 28).toUpperCase(Locale.ROOT);
            jdbc.update("""
                    INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature,
                        position, version, created_at, owner_customer_id, design_snapshot_id, source_variant_id)
                    VALUES (?, ?, ?, 'Printed cup', 'ACTIVE', FALSE, ?, 1, 0, NOW(), ?, ?, ?)""",
                    variant, product.productId(), code, "OWNED-SKU:" + code, customerId, design.snapshotId(),
                    product.variantId());
            UUID item = jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class, code);
            jdbc.update("""
                    UPDATE inventory.inventory_items SET weight_kg = 0.02, length_cm = 9, width_cm = 9, height_cm = 12,
                           storage_class = 'NORMAL' WHERE id = ?""", item);
            UUID carton = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base,
                        weight_kg, length_cm, width_cm, height_cm, default_purchase, active, version, created_at,
                        default_shipping)
                    VALUES (?, ?, 'CARTON', ?, FALSE, 1.2, 60, 40, 35, FALSE, TRUE, 0, NOW(), TRUE)""",
                    carton, item, PER_CARTON);
            return new IssuedSku(code, item, carton);
        });
    }

    /** A DRAFT order of 20 cartons of 50 at 2,000 a piece with 10% VAT - as a converted quote leaves it. */
    private UUID draftOrder() {
        return tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), CARTONS * PER_CARTON,
                    Money.vnd(2_000), null).withCartons(sku.uomConversionId(), CARTONS, PER_CARTON)
                    .withTax(BigDecimal.TEN);
            Order order = Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()),
                    customerId, Identifiers.newId(), List.of(line), Instant.now());
            return orderRepository.save(order).id().value();
        });
    }

    private UUID lineOf(UUID orderId) {
        return jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
    }

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    /**
     * A new order of a contract scheduled 12 cartons three weeks out and the other 8 two weeks later, everything paid
     * before delivery, signed today - so CONFIRMED - shipping from warehouse A, where nothing is held for it.
     */
    private UUID confirmedOrderAtA() {
        UUID orderId = draftOrder();
        UUID line = lineOf(orderId);
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, List.of(
                        new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                                List.of(new SalesContracts.BatchLine(line, 12))),
                        new SalesContracts.Batch(today.plusDays(35), today.plusDays(37),
                                List.of(new SalesContracts.BatchLine(line, 8)))),
                        List.of(new SalesContracts.Installment("Before delivery", "BEFORE_DELIVERY", null,
                                new BigDecimal("100"), null, null)))));
        SalesContracts.ContractView sent = asSales(() -> contracts.send(sales, orderId, drafted.version()));
        byte[] scan = TestPdfs.blank();
        asSales(() -> contracts.sign(sales, orderId, sent.version(),
                IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), today));
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.CONFIRMED);
        shippingWarehouses.choose(orderId, a.id(), sales.userId());
        return orderId;
    }

    /** Drawn up, the sample waived with a reason (goods can be received while SENT), submitted: PENDING_APPROVAL. */
    private ProductionOrder pendingWithoutSample(UUID orderId, int batch) {
        ProductionOrder draft = productionOrders.drawUp(orderId, batch, producer.id(), coordinator);
        ProductionOrder waived = productionOrders.edit(draft.id(), draft.version(), new ProductionOrder.Draft(
                draft.producerId(), draft.needByDate(), draft.unitPrice(), draft.note(), false,
                "The customer approved the same print on paper", null));
        return productionOrders.submit(waived.id(), waived.version(), coordinator);
    }

    /** As {@link #pendingWithoutSample}, approved by the manager: SENT. */
    private ProductionOrder sentWithoutSample(UUID orderId, int batch) {
        ProductionOrder pending = pendingWithoutSample(orderId, batch);
        ProductionOrder sent = productionOrders.approve(pending.id(), pending.version(), manager);
        assertThat(sent.status()).isEqualTo(ProductionOrderStatus.SENT);
        assertThat(sent.sampleApprovalRequired()).isFalse();
        return sent;
    }

    private static UUID lineId(ProductionOrder production) {
        return production.lines().getFirst().id();
    }

    /** {@code n} new cartons, their count left to the standard. */
    private static List<NewPackage> cartons(int n) {
        List<NewPackage> cartons = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cartons.add(new NewPackage(null, 0));
        }
        return cartons;
    }

    private ReceiptView count(ReceiptView receipt, UUID productionOrderLine, List<NewPackage> cartons) {
        return receipts.replaceLines(receipt.id(), List.of(NewLine.ofProductionOrderLine(productionOrderLine,
                a.receiving(), cartons)));
    }

    private ReceiptView start(ProductionOrder production) {
        return receipts.createForProductionOrder(new GoodsReceipts.CreateForProductionOrder(production.id(),
                "DN-" + production.code(), null), clerk);
    }

    private OrderStatus status(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    private ProductionOrder reread(UUID id) {
        return productionOrders.detail(id).order();
    }

    private Instant printingStartedAt(UUID orderId) {
        java.sql.Timestamp at = jdbc.queryForObject(
                "SELECT printing_started_at FROM ordering.customer_order WHERE id = ?", java.sql.Timestamp.class, orderId);
        return at == null ? null : at.toInstant();
    }

    private List<String> history(UUID orderId) {
        return jdbc.queryForList("""
                SELECT COALESCE(from_status, '-') || '>' || to_status || ' ' || COALESCE(reason, '-')
                  FROM ordering.order_status_history WHERE order_id = ? ORDER BY occurred_at, created_at""",
                String.class, orderId);
    }

    private String receiptStatus(UUID receiptId) {
        return jdbc.queryForObject("SELECT status FROM procurement.goods_receipts WHERE id = ?", String.class,
                receiptId);
    }

    private void cancelOrder(UUID orderId) {
        asSales(() -> orders.cancel(orderId, new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST,
                "Customer changed supplier", null, sales.userId())));
        assertThat(status(orderId)).isEqualTo(OrderStatus.CANCELLED);
    }

    /**
     * As a signed-in user: the receipt service names who cancelled a draft from the security context, which
     * {@code WithCurrentUser} (data scope only) does not fill.
     */
    private <T> T signedInAs(UUID userId, Supplier<T> call) {
        CurrentUser user = new CurrentUser(userId, "clerk-" + userId, Set.of(Role.WAREHOUSE_STAFF), Set.of(),
                DataScope.ALL, Set.of(), Set.of());
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject(userId.toString())
                .claim("preferred_username", user.username()).build();
        SecurityContext before = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return WithCurrentUser.call(user, DataScope.ALL, call);
        } finally {
            SecurityContextHolder.setContext(before);
        }
    }

    /** The cancellation listener has handled the event: its publication is completed in the registry. */
    /** {@code ProductionOrderStopped} published for the production order: what tells its producer (4A-3). */
    private long stopsPublished(UUID productionOrderId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?""", Long.class,
                com.stockflow.contracts.ProductionOrderStopped.class.getName(), "%" + productionOrderId + "%");
    }

    private void awaitCancellationHandled(UUID orderId) {
        Await.until("SalesOrderCancellationListener to handle the cancellation", Duration.ofSeconds(30), () ->
                jdbc.queryForObject("""
                        SELECT count(*) FROM event_publication
                         WHERE event_type LIKE '%OrderCancelled' AND serialized_event LIKE ?
                           AND listener_id LIKE '%SalesOrderCancellationListener%' AND completion_date IS NOT NULL""",
                        Long.class, "%" + orderId + "%") == 1);
    }

    /** A draft receipt counted at 3 cartons, against a production order SENT for batch 1 of an AWAITING_STOCK order. */
    private record Waiting(UUID orderId, ProductionOrder production, ReceiptView draft) {
    }

    private Waiting awaitingWithDraftReceipt() {
        UUID orderId = confirmedOrderAtA();
        ProductionOrder production = sentWithoutSample(orderId, 1);
        assertThat(status(orderId)).isEqualTo(OrderStatus.AWAITING_STOCK);
        ReceiptView draft = start(production);
        count(draft, lineId(production), cartons(3));
        return new Waiting(orderId, production, draft);
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("4A-5 + 4A-6 end to end: short order waits, receipt starts printing and receives, putaway tops up, order released")
    void shortOrderWaitsIsReceivedAndReleased() {
        UUID orderId = confirmedOrderAtA();
        assertThat(shippingWarehouses.coverage(orderId).fullyHeld()).isFalse();
        ProductionOrder production = sentWithoutSample(orderId, 1);
        assertThat(status(orderId)).as("approved and sent while batch 1 is short").isEqualTo(OrderStatus.AWAITING_STOCK);
        assertThat(printingStartedAt(orderId)).as("nothing printing yet").isNull();

        // First receipt: 5 cartons. Confirming moves SENT to IN_PRODUCTION (the listener records it), then partly received.
        ReceiptView first = start(production);
        ReceiptView counted = count(first, lineId(production), cartons(5));
        ReceiptView confirmed = receipts.confirm(first.id(), clerk);
        assertThat(reread(production.id()).status()).isEqualTo(ProductionOrderStatus.PARTIALLY_RECEIVED);
        Instant started = printingStartedAt(orderId);
        assertThat(started).as("BR-11: set by the first production order in production").isNotNull();
        assertThat(reread(production.id()).productionStartedAt()).isNotNull();
        assertThat(status(orderId)).isEqualTo(OrderStatus.AWAITING_STOCK);

        // Second receipt: the other 7 cartons. RECEIVED; printing_started_at is written once.
        ReceiptView second = start(reread(production.id()));
        ReceiptView countedRest = count(second, lineId(production), cartons(7));
        ReceiptView confirmedRest = receipts.confirm(second.id(), clerk);
        assertThat(reread(production.id()).status()).isEqualTo(ProductionOrderStatus.RECEIVED);
        assertThat(printingStartedAt(orderId)).as("written once").isEqualTo(started);
        assertThat(status(orderId)).as("nothing is held until the goods are put away").isEqualTo(OrderStatus.AWAITING_STOCK);

        // Batch 2 (8 cartons) is produced and received too: release wants the whole order held, not only batch 1.
        ProductionOrder later = sentWithoutSample(orderId, 2);
        ReceiptView third = start(later);
        count(third, lineId(later), cartons(8));
        ReceiptView confirmedLater = receipts.confirm(third.id(), clerk);
        assertThat(reread(later.id()).status()).isEqualTo(ProductionOrderStatus.RECEIVED);
        assertThat(printingStartedAt(orderId)).as("still written once").isEqualTo(started);

        // Putaway of every carton of the three receipts.
        Map<UUID, String> lpns = new HashMap<>();
        confirmed.lines().getFirst().packages().forEach(p -> lpns.put(p.id(), p.lpn()));
        confirmedRest.lines().getFirst().packages().forEach(p -> lpns.put(p.id(), p.lpn()));
        confirmedLater.lines().getFirst().packages().forEach(p -> lpns.put(p.id(), p.lpn()));
        for (ReceiptView receipt : List.of(confirmed, confirmedRest, confirmedLater)) {
            int cartonsOfReceipt = receipt.lines().getFirst().packages().size();
            Await.until("one putaway task per carton", Duration.ofSeconds(20),
                    () -> putawayTasks.tasksOfReceipt(receipt.id()).size() == cartonsOfReceipt);
            for (PutawayTaskView task : putawayTasks.tasksOfReceipt(receipt.id())) {
                putaway.confirm(task.id(), a.bin(), lpns.get(task.stockPackageId()), clerk);
            }
            Await.until("the receipt to close", Duration.ofSeconds(20),
                    () -> receiptStatus(receipt.id()).equals("CLOSED"));
        }

        Await.until("the waiting order to hold all 20 cartons", Duration.ofSeconds(30),
                () -> shippingWarehouses.coverage(orderId).fullyHeld());
        assertThat(status(orderId)).as("held but not released").isEqualTo(OrderStatus.AWAITING_STOCK);

        assertThat(releases.release(orderId, a.id(), coordinator).status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(history(orderId)).last().asString().startsWith("AWAITING_STOCK>READY_TO_FULFILL");
        assertThat(history(orderId)).filteredOn(row -> row.contains(">AWAITING_STOCK ")).hasSize(1);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity), 0) FROM inventory.stock_reservation
                 WHERE order_id = ? AND allocated_from_id IS NOT NULL AND status = 'HELD'""", Long.class, orderId))
                .as("4B-8 (17 BR-29): the release is batch 1's - its 12 cartons allocated").isEqualTo(12L * PER_CARTON);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity), 0) FROM inventory.stock_reservation
                 WHERE order_id = ? AND allocated_from_id IS NULL AND status = 'HELD'""", Long.class, orderId))
                .as("batch 2's 8 cartons stay held for it, on no bin yet").isEqualTo(8L * PER_CARTON);
        assertThat(counted.lines()).isNotEmpty();
        assertThat(countedRest.lines()).isNotEmpty();
    }

    @Test
    @DisplayName("BR-09, 03 edge: order cancelled with a draft receipt - production order stays open, cancelling the "
            + "last draft cancels it (mass printing not started)")
    void cancelledOrderDraftReceiptCancelledStopsProductionOrder() {
        Waiting w = awaitingWithDraftReceipt();

        cancelOrder(w.orderId());
        awaitCancellationHandled(w.orderId());
        assertThat(reread(w.production().id()).status()).as("passed by: a draft receipt is in progress")
                .isEqualTo(ProductionOrderStatus.SENT);
        assertThat(receiptStatus(w.draft().id())).isEqualTo("DRAFT");

        signedInAs(clerk, () -> receipts.cancel(w.draft().id()));

        ProductionOrder stopped = reread(w.production().id());
        assertThat(stopped.status()).isEqualTo(ProductionOrderStatus.CANCELLED);
        assertThat(jdbc.queryForMap("SELECT cancelled_by, cancellation_reason FROM ordering.production_order WHERE id = ?",
                w.production().id())).containsEntry("cancelled_by", clerk);
        assertThat(stopsPublished(w.production().id())).as("its producer is told to stop (17 §4.3 step 13)")
                .isPositive();
        assertThat(status(w.orderId())).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("BR-09, 03 edge: order cancelled with a draft receipt - confirming it closes the production order "
            + "(partly received), the sales order stays cancelled")
    void cancelledOrderDraftReceiptConfirmedClosesProductionOrder() {
        Waiting w = awaitingWithDraftReceipt();

        cancelOrder(w.orderId());
        awaitCancellationHandled(w.orderId());
        assertThat(reread(w.production().id()).status()).isEqualTo(ProductionOrderStatus.SENT);

        receipts.confirm(w.draft().id(), clerk);

        ProductionOrder closed = reread(w.production().id());
        assertThat(closed.status()).isEqualTo(ProductionOrderStatus.CLOSED);
        assertThat(closed.lines().getFirst().receivedCartons()).as("what arrived stays recorded").isEqualTo(3);
        assertThat(jdbc.queryForMap("SELECT closed_by FROM ordering.production_order WHERE id = ?",
                w.production().id())).containsEntry("closed_by", clerk);
        assertThat(stopsPublished(w.production().id())).as("its producer is told to deliver nothing more (step 12)")
                .isPositive();
        assertThat(status(w.orderId())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(printingStartedAt(w.orderId())).as("a cancelled order is left as it is").isNull();
    }

    @Test
    @DisplayName("17 5.1: an order on hold stays on hold when its production order is sent, and returns to AWAITING_STOCK")
    void onHoldOrderRemembersAwaitingStock() {
        UUID orderId = confirmedOrderAtA();
        // Drawn up and submitted while CONFIRMED (drawing up wants CONFIRMED); the hold comes before the approval.
        ProductionOrder pending = pendingWithoutSample(orderId, 1);
        orders.putOnHold(orderId, HoldReason.ADDRESS, "Wrong address", coordinator);
        assertThat(status(orderId)).isEqualTo(OrderStatus.ON_HOLD);

        assertThat(productionOrders.approve(pending.id(), pending.version(), manager).status())
                .isEqualTo(ProductionOrderStatus.SENT);

        assertThat(status(orderId)).as("a hold is not lifted by sending a production order")
                .isEqualTo(OrderStatus.ON_HOLD);
        assertThat(jdbc.queryForObject("SELECT status_before_hold FROM ordering.customer_order WHERE id = ?",
                String.class, orderId)).isEqualTo("AWAITING_STOCK");

        orders.resolveHold(orderId, "Address corrected", coordinator);
        assertThat(status(orderId)).isEqualTo(OrderStatus.AWAITING_STOCK);
        assertThat(history(orderId)).last().asString().startsWith("ON_HOLD>AWAITING_STOCK");
    }
}
