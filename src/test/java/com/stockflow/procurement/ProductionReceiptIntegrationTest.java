package com.stockflow.procurement;

import com.stockflow.common.api.FieldError;
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
import com.stockflow.inventory.internal.service.PutawayService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.ProductionOrderReceiving;
import com.stockflow.order.api.ReceivableProductionOrder;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderRepository.HistoryEntry;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.service.ProducerService;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.GoodsReceiptType;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.procurement.internal.service.GoodsReceipts.NewLine;
import com.stockflow.procurement.internal.service.GoodsReceipts.NewPackage;
import com.stockflow.procurement.internal.service.GoodsReceipts.PackageView;
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
import java.util.ArrayList;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 4A-5 against the real database (kltn-docs 03 §4.1, BR-01..BR-15 and edge cases; 17 §4.3 steps 10-11, §5.3): finished
 * goods received against a production order - counted in whole cartons of the line's standard count, in the
 * production order's lot, never more than ordered on all its receipts not cancelled - which moves the production order
 * on; put away, the cartons close the receipt and are held for the confirmed sales order waiting for them.
 *
 * <p>The production orders are real ones: an order of a contract confirmed by its signature (20 cartons of 50,
 * scheduled 12 + 8), its shipping warehouse chosen, a production order drawn up by the coordinator for a batch and
 * approved by the Sales Manager, so SENT - with its print sample waived, or not. Each test issues a customer SKU of its
 * own on a confirmed design (see {@code ProductionOrderIntegrationTest}), so its stock and lots are exact whatever other
 * tests left; the property source and mocks are that test's, so the two share one context.</p>
 *
 * <p>Nothing runs in a test transaction: cartons are checked against their stock rows at commit (V20261020000200),
 * and the registration of a production receipt with its production order is checked at commit too.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ProductionReceiptIntegrationTest {

    /** Order numbers of this class, apart from those of the other production test classes. */
    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(870_000);

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int PER_CARTON = 50;
    private static final int CARTONS = 20;
    private static final String LPN = "^LPN-\\d{8}-\\d{6}$";

    /** A warehouse of the test: its code (the prefix of its location codes), its RECEIVING area and one roomy bin. */
    private record Warehouse(UUID id, String code, String receiving, String bin) {
    }

    /** The test's own customer SKU, its confirmed design and its carton unit. */
    private record IssuedSku(String sku, UUID itemId, UUID uomConversionId) {
    }

    /** Created once per database: more would only grow every list. */
    private static Warehouse a;
    private static Warehouse b;

    @Autowired GoodsReceipts receipts;
    @Autowired ProductionOrderReceiving receiving;
    @Autowired ProductionOrderService productionOrders;
    @Autowired ProducerService producers;
    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
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
        if (a == null || !exists(a) || !exists(b)) {
            a = warehouse();
            b = warehouse();
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
                    VALUES (?, 'Receiving Test Co', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "receiving-" + customerId + "@example.com", "03" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        sku = issueSku();
        producer = producers.register("PR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT),
                new Producer.Details("Receiving test workshop", null, null, null, Producer.Channel.PDF, 14, null));
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

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
            order.applyTerms(PaymentTerm.PREPAID, null, null, null);
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

    /** Drawn up for {@code batch}, submitted and approved by someone else: SENT, a print sample to approve first. */
    private ProductionOrder sentWithSample(UUID orderId, int batch) {
        ProductionOrder draft = productionOrders.drawUp(orderId, batch, producer.id(), coordinator);
        assertThat(draft.sampleApprovalRequired()).as("BR-24: a SKU never produced wants a sample").isTrue();
        ProductionOrder pending = productionOrders.submit(draft.id(), draft.version(), coordinator);
        return productionOrders.approve(pending.id(), pending.version(), manager);
    }

    /** As {@link #sentWithSample}, the sample waived with a reason: goods can be received while it is SENT. */
    private ProductionOrder sentWithoutSample(UUID orderId, int batch) {
        ProductionOrder draft = productionOrders.drawUp(orderId, batch, producer.id(), coordinator);
        ProductionOrder waived = productionOrders.edit(draft.id(), draft.version(), new ProductionOrder.Draft(
                draft.producerId(), draft.needByDate(), draft.unitPrice(), draft.note(), false,
                "The customer approved the same print on paper", null));
        ProductionOrder pending = productionOrders.submit(waived.id(), waived.version(), coordinator);
        ProductionOrder sent = productionOrders.approve(pending.id(), pending.version(), manager);
        assertThat(sent.status()).isEqualTo(ProductionOrderStatus.SENT);
        assertThat(sent.sampleApprovalRequired()).isFalse();
        return sent;
    }

    private ReceiptView start(ProductionOrder production) {
        return receipts.createForProductionOrder(new GoodsReceipts.CreateForProductionOrder(production.id(),
                "DN-" + production.code(), null), clerk);
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

    private ProductionOrder reread(UUID id) {
        return productionOrders.detail(id).order();
    }

    private List<HistoryEntry> history(UUID id) {
        return productionOrders.detail(id).history();
    }

    private String registration(UUID receiptId) {
        return jdbc.queryForObject("SELECT status FROM ordering.production_order_receipt WHERE goods_receipt_id = ?",
                String.class, receiptId);
    }

    private String status(UUID receiptId) {
        return jdbc.queryForObject("SELECT status FROM procurement.goods_receipts WHERE id = ?", String.class,
                receiptId);
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    private static List<String> fields(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).fieldErrors().stream().map(e -> e.field() + ":" + e.code()).toList();
    }

    /** The statements in one transaction, the deferred checks run before it ends. */
    private void sqlAtCommit(String statement, Object... args) {
        tx.executeWithoutResult(s -> {
            jdbc.update(statement, args);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("03 §4.1 step 1, BR-01, BR-15: started against a SENT production order with no sample to approve")
    void startedAgainstASentProductionOrder() {
        UUID orderId = confirmedOrderAtA();
        ProductionOrder production = sentWithoutSample(orderId, 1);

        ReceiptView receipt = start(production);

        assertThat(receipt.status()).isEqualTo(GoodsReceiptStatus.DRAFT);
        assertThat(receipt.receiptType()).isEqualTo(GoodsReceiptType.PRODUCTION);
        assertThat(receipt.productionOrderId()).isEqualTo(production.id());
        assertThat(receipt.productionOrderCode()).isEqualTo(production.code());
        assertThat(receipt.productionOrderStatus()).isEqualTo("SENT");
        assertThat(receipt.producerId()).isEqualTo(producer.id());
        assertThat(receipt.producerName()).isEqualTo("Receiving test workshop");
        assertThat(receipt.warehouseId()).as("BR-01: at the warehouse it is received at").isEqualTo(a.id());
        assertThat(receipt.purchaseOrderId()).isNull();
        assertThat(receipt.supplierId()).isNull();
        assertThat(receipt.withoutPurchaseOrder()).as("not a blind receipt").isFalse();
        assertThat(receipt.number()).matches("GR-\\d{8}-\\d{4,}");
        assertThat(receipt.lines()).isEmpty();
        assertThat(registration(receipt.id())).as("the production order knows of the draft").isEqualTo("DRAFT");
        assertThat(jdbc.queryForMap("SELECT receipt_type, production_order_id, po_id, supplier_id "
                + "FROM procurement.goods_receipts WHERE id = ?", receipt.id()))
                .containsEntry("receipt_type", "PRODUCTION").containsEntry("production_order_id", production.id())
                .containsEntry("po_id", null).containsEntry("supplier_id", null);
        assertThat(reread(production.id()).status()).as("nothing moves before a receipt is confirmed")
                .isEqualTo(ProductionOrderStatus.SENT);

        assertThat(receipts.list(null, null, null, null, null, null, null, production.id(), null, 0, 20, null)
                .items()).singleElement().satisfies(row -> {
                    assertThat(row.id()).isEqualTo(receipt.id());
                    assertThat(row.receiptType()).isEqualTo(GoodsReceiptType.PRODUCTION);
                    assertThat(row.productionOrderCode()).isEqualTo(production.code());
                    assertThat(row.producerName()).isEqualTo("Receiving test workshop");
                    assertThat(row.withoutPurchaseOrder()).isFalse();
                });
        assertThat(receipts.list(null, null, a.id(), null, null, null, null, null, GoodsReceiptType.PRODUCTION, 0,
                100, null).items()).extracting(GoodsReceipts.Row::id).contains(receipt.id());
        assertThat(receipts.list(null, null, a.id(), null, null, null, null, null, GoodsReceiptType.PURCHASE, 0, 100,
                null).items()).extracting(GoodsReceipts.Row::id).doesNotContain(receipt.id());
        assertThat(receipts.list(null, null, a.id(), null, null, null, true, null, null, 0, 100, null).items())
                .as("withoutPo keeps blind purchase receipts").extracting(GoodsReceipts.Row::id)
                .doesNotContain(receipt.id());
        assertThat(receipts.get(receipt.id()).productionOrderCode()).isEqualTo(production.code());
    }

    @Test
    @DisplayName("BR-15, BR-01: refused while a print sample waits, before it is sent, unknown, or at another warehouse")
    void refusedWhereGoodsCannotBeReceived() {
        UUID orderId = confirmedOrderAtA();
        ProductionOrder sample = sentWithSample(orderId, 1);
        assertThatThrownBy(() -> start(sample)).as("SENT with a print sample to approve")
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_NOT_RECEIVABLE))
                .hasMessageContaining("print sample");
        assertThat(ErrorCode.PRODUCTION_ORDER_NOT_RECEIVABLE.httpStatus()).isEqualTo(409);

        ProductionOrder draft = productionOrders.drawUp(orderId, 2, producer.id(), coordinator);
        assertThatThrownBy(() -> start(draft)).extracting(ProductionReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_NOT_RECEIVABLE);
        ProductionOrder pending = productionOrders.submit(draft.id(), draft.version(), coordinator);
        assertThatThrownBy(() -> start(pending)).as("PENDING_APPROVAL")
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_NOT_RECEIVABLE))
                .hasMessageContaining("PENDING_APPROVAL");
        assertThatThrownBy(() -> receipts.createForProductionOrder(new GoodsReceipts.CreateForProductionOrder(
                Identifiers.newId(), null, null), clerk)).extracting(ProductionReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_NOT_FOUND);

        // BR-01: the staff of another warehouse do not receive against it.
        UUID otherOrder = confirmedOrderAtA();
        ProductionOrder receivable = sentWithoutSample(otherOrder, 1);
        CurrentUser staffOfB = new CurrentUser(clerk, "clerk-" + clerk, Set.of(Role.WAREHOUSE_STAFF), Set.of(),
                DataScope.WAREHOUSE, Set.of(b.code()), Set.of(b.id()));
        assertThatThrownBy(() -> WithCurrentUser.call(staffOfB, DataScope.WAREHOUSE, () -> start(receivable)))
                .extracting(ProductionReceiptIntegrationTest::code).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        CurrentUser staffOfA = new CurrentUser(clerk, "clerk-" + clerk, Set.of(Role.WAREHOUSE_STAFF), Set.of(),
                DataScope.WAREHOUSE, Set.of(a.code()), Set.of(a.id()));
        assertThat(WithCurrentUser.call(staffOfA, DataScope.WAREHOUSE, () -> start(receivable)).warehouseId())
                .as("its own warehouse's staff do").isEqualTo(a.id());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement.goods_receipts WHERE production_order_id "
                + "IN (?, ?, ?)", Long.class, sample.id(), draft.id(), receivable.id()))
                .as("only the receipt allowed was started").isEqualTo(1);
    }

    @Test
    @DisplayName("03 §4.1 step 2, BR-12, BR-13: counted in labelled cartons of 50; a recount keeps, adds and voids")
    void countedInWholeCartons() {
        UUID orderId = confirmedOrderAtA();
        ProductionOrder production = sentWithoutSample(orderId, 1);
        ReceiptView receipt = start(production);

        GoodsReceipts.LineView line = count(receipt, lineId(production), cartons(3)).lines().getFirst();

        assertThat(line.productionOrderLineId()).isEqualTo(lineId(production));
        assertThat(line.quantity()).as("3 cartons of 50").isEqualTo(150);
        assertThat(line.lotNumber()).as("BR-03: the production order's code").isEqualTo(production.code());
        assertThat(line.expiryDate()).as("BR-06").isNull();
        assertThat(line.unitOfMeasure()).isEqualTo("CARTON");
        assertThat(line.looseQuantity()).as("BR-12: nothing loose").isZero();
        assertThat(line.packages()).hasSize(3).allSatisfy(p -> {
            assertThat(p.lpn()).matches(LPN);
            assertThat(p.status()).isEqualTo("RECEIVING");
            assertThat(p.quantity()).isEqualTo(PER_CARTON);
            assertThat(p.nominalQty()).isEqualTo(PER_CARTON);
            assertThat(p.locationCode()).isEqualTo(a.receiving());
        });
        assertThat(line.production()).satisfies(p -> {
            assertThat(p.unitsPerCarton()).isEqualTo(PER_CARTON);
            assertThat(p.orderedCartons()).isEqualTo(12);
            assertThat(p.receivedCartons()).isZero();
            assertThat(p.thisReceiptCartons()).isEqualTo(3);
            assertThat(p.remainingCartons()).as("12 - 0 - 3, the draft counted").isEqualTo(9);
            assertThat(p.orderedUnits()).isEqualTo(600);
            assertThat(p.thisReceiptUnits()).isEqualTo(150);
            assertThat(p.remainingUnits()).isEqualTo(450);
        });

        List<PackageView> first = line.packages();
        GoodsReceipts.LineView again = count(receipt, lineId(production), List.of(
                new NewPackage(first.get(0).lpn(), PER_CARTON), new NewPackage(first.get(1).lpn().toLowerCase(Locale.ROOT),
                        0), new NewPackage(null, 0))).lines().getFirst();
        assertThat(again.id()).as("the line keeps its id").isEqualTo(line.id());
        assertThat(again.packages()).extracting(PackageView::lpn).hasSize(3)
                .contains(first.get(0).lpn(), first.get(1).lpn()).doesNotContain(first.get(2).lpn());
        assertThat(jdbc.queryForObject("SELECT status FROM inventory.stock_package WHERE lpn = ?", String.class,
                first.get(2).lpn())).as("the carton left out").isEqualTo("VOID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_package WHERE goods_receipt_line_id = ?",
                Long.class, line.id())).as("three live, one void").isEqualTo(4);

        // Every refusal of the request at once, each on its field.
        UUID unknown = Identifiers.newId();
        Throwable refused = catchThrowable(() -> receipts.replaceLines(receipt.id(), List.of(
                new NewLine(null, 0, null, null, a.receiving(), null, null,
                        List.of(new NewPackage(null, 0), new NewPackage(null, 48)), null, lineId(production)),
                new NewLine(Identifiers.newId(), 0, "LOT-A", today.plusYears(1), a.bin(), null, null, List.of(), null,
                        unknown))));
        assertThat(code(refused)).isEqualTo(ErrorCode.RECEIPT_CARTON_NOT_STANDARD);
        assertThat(fields(refused)).containsExactlyInAnyOrder(
                "lines[0].packages[1].quantity:RECEIPT_CARTON_NOT_STANDARD",
                "lines[1].purchaseOrderLineId:VALIDATION_FAILED",
                "lines[1].lotNumber:LOT_NOT_ALLOWED",
                "lines[1].expiryDate:EXPIRY_NOT_ALLOWED",
                "lines[1].productionOrderLineId:RECEIPT_LINE_NOT_ON_PRODUCTION_ORDER");
        assertThat(ErrorCode.RECEIPT_CARTON_NOT_STANDARD.httpStatus()).isEqualTo(422);
        Throwable noCarton = catchThrowable(() -> receipts.replaceLines(receipt.id(), List.of(
                new NewLine(null, 100, null, null, a.bin(), null, "BOX", List.of(), "SOMETHING-ELSE",
                        lineId(production)))));
        assertThat(fields(noCarton)).containsExactlyInAnyOrder("lines[0].packages:VALIDATION_FAILED",
                "lines[0].sku:RECEIPT_LINE_NOT_ON_PRODUCTION_ORDER", "lines[0].locationCode:LOCATION_AREA_MISMATCH",
                "lines[0].unitOfMeasure:UOM_CONVERSION_NOT_FOUND", "lines[0].quantity:VALIDATION_FAILED");
        Throwable twice = catchThrowable(() -> receipts.replaceLines(receipt.id(), List.of(
                NewLine.ofProductionOrderLine(lineId(production), a.receiving(), cartons(1)),
                NewLine.ofProductionOrderLine(lineId(production), a.receiving(), cartons(1)))));
        assertThat(fields(twice)).containsExactly("lines[1].productionOrderLineId:DUPLICATE_RECEIPT_LINE");

        assertThat(receipts.get(receipt.id()).lines()).singleElement().satisfies(kept -> {
            assertThat(kept.quantity()).as("a refused count changes nothing").isEqualTo(150);
            assertThat(kept.packages()).hasSize(3);
        });
    }

    @Test
    @DisplayName("BR-02: never more cartons than ordered on all its receipts not cancelled - another draft counts")
    void overReceiptRefusedCountingAnotherDraft() {
        UUID orderId = confirmedOrderAtA();
        ProductionOrder production = sentWithoutSample(orderId, 1);
        ReceiptView first = start(production);
        ReceiptView second = start(production);
        count(first, lineId(production), cartons(8));

        Throwable over = catchThrowable(() -> count(second, lineId(production), cartons(5)));
        assertThat(code(over)).isEqualTo(ErrorCode.RECEIPT_EXCEEDS_PRODUCTION_ORDER);
        assertThat(fields(over)).containsExactly("lines[0].packages:RECEIPT_EXCEEDS_PRODUCTION_ORDER");
        assertThat(((BusinessException) over).fieldErrors()).extracting(FieldError::message).singleElement()
                .asString().contains("orders 12 carton(s) and 8", "4 more at most, not 5");
        assertThat(receipts.get(second.id()).lines()).as("nothing was counted").isEmpty();

        assertThat(count(second, lineId(production), cartons(4)).lines().getFirst().quantity()).isEqualTo(200);
        assertThat(catchThrowable(() -> count(second, lineId(production), cartons(5))))
                .as("a recount of the same receipt counts its own cartons once")
                .extracting(ProductionReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.RECEIPT_EXCEEDS_PRODUCTION_ORDER);

        receipts.cancel(first.id());
        assertThat(registration(first.id())).isEqualTo("CANCELLED");
        assertThat(count(second, lineId(production), cartons(12)).lines().getFirst().quantity())
                .as("a cancelled receipt holds nothing").isEqualTo(600);
    }

    @Test
    @DisplayName("03 §4.1 step 6, 17 §4.3 step 11: confirmed into INBOUND cartons in the production order's lot; it moves on")
    void confirmedIntoStockAndTheProductionOrderMovesOn() {
        UUID orderId = confirmedOrderAtA();
        ProductionOrder production = sentWithoutSample(orderId, 1);
        ReceiptView receipt = start(production);
        count(receipt, lineId(production), cartons(5));

        ReceiptView confirmed = receipts.confirm(receipt.id(), clerk);

        assertThat(confirmed.status()).as("BR-07: no quality check, straight to putaway")
                .isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(confirmed.confirmedBy()).isEqualTo(clerk);
        assertThat(confirmed.productionOrderStatus()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(confirmed.lines().getFirst().production()).satisfies(p -> {
            assertThat(p.receivedCartons()).isEqualTo(5);
            assertThat(p.thisReceiptCartons()).isEqualTo(5);
            assertThat(p.remainingCartons()).as("counted in now: 12 - 5").isEqualTo(7);
        });
        assertThat(registration(receipt.id())).isEqualTo("CONFIRMED");
        // INBOUND in its cartons at the receiving area, nothing loose (BR-04, BR-12).
        assertThat(jdbc.queryForList("""
                SELECT si.on_hand FROM inventory.stock_item si
                 WHERE si.sku = ? AND si.status = 'INBOUND' AND si.location_code = ? AND si.stock_package_id IS NOT NULL""",
                Integer.class, sku.sku(), a.receiving())).hasSize(5).containsOnly(PER_CARTON);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_item WHERE sku = ? "
                + "AND stock_package_id IS NULL", Long.class, sku.sku())).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM inventory.stock_package WHERE "
                + "goods_receipt_line_id = ? AND status <> 'VOID'", String.class, confirmed.lines().getFirst().id()))
                .containsExactly("IN_STOCK");
        // BR-03: the lot is the production order's, numbered with its code.
        Map<String, Object> lot = jdbc.queryForMap("SELECT * FROM inventory.stock_lot WHERE sku = ?", sku.sku());
        assertThat(lot).containsEntry("origin_type", "PRODUCTION_ORDER").containsEntry("lot_number", production.code())
                .containsEntry("production_order_id", production.id())
                .containsEntry("production_order_code", production.code()).containsEntry("goods_receipt_id", null)
                .containsEntry("purchase_order_id", null).containsEntry("expiry_date", null)
                .containsEntry("warehouse_id", a.id());
        assertThat(jdbc.queryForList("SELECT DISTINCT lot_id FROM inventory.stock_item WHERE sku = ?", UUID.class,
                sku.sku())).containsExactly((UUID) lot.get("id"));
        assertThat(jdbc.queryForList("SELECT DISTINCT lot_number FROM inventory.stock_item WHERE sku = ?",
                String.class, sku.sku())).containsExactly(production.code());

        // BR-15: still SENT, so in production first - when the goods came - then partly received.
        ProductionOrder after = reread(production.id());
        assertThat(after.status()).isEqualTo(ProductionOrderStatus.PARTIALLY_RECEIVED);
        assertThat(after.productionStartedAt()).isNotNull();
        assertThat(after.lines().getFirst().receivedCartons()).isEqualTo(5);
        assertThat(history(production.id())).hasSize(5).last().satisfies(h -> {
            assertThat(h.from()).isEqualTo(ProductionOrderStatus.IN_PRODUCTION);
            assertThat(h.to()).isEqualTo(ProductionOrderStatus.PARTIALLY_RECEIVED);
            assertThat(h.by()).isEqualTo(clerk);
            assertThat(h.reason()).contains(receipt.number());
        });
        assertThat(history(production.id()).get(3)).satisfies(h -> {
            assertThat(h.from()).isEqualTo(ProductionOrderStatus.SENT);
            assertThat(h.to()).isEqualTo(ProductionOrderStatus.IN_PRODUCTION);
            assertThat(h.by()).isEqualTo(clerk);
            assertThat(h.reason()).contains(receipt.number(), "before mass printing was recorded");
        });

        // Confirming twice, or recording the receipt again, changes nothing.
        assertThatThrownBy(() -> receipts.confirm(receipt.id(), clerk))
                .extracting(ProductionReceiptIntegrationTest::code).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        ReceivableProductionOrder replayed = receiving.recordReceipt(production.id(), receipt.id(),
                Map.of(lineId(production), 5), clerk, Instant.now());
        assertThat(replayed.status()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(replayed.lines()).singleElement().extracting(ReceivableProductionOrder.Line::receivedCartons)
                .isEqualTo(5);
        assertThat(history(production.id())).hasSize(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_item WHERE sku = ?", Long.class,
                sku.sku())).isEqualTo(5);

        // A second receipt brings the other 7: the same lot, and the production order is received.
        ReceiptView rest = start(after);
        count(rest, lineId(production), cartons(7));
        assertThat(receipts.confirm(rest.id(), clerk).productionOrderStatus()).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_lot WHERE sku = ?", Long.class,
                sku.sku())).as("one lot across receipts (BR-03)").isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT DISTINCT lot_id FROM inventory.stock_item WHERE sku = ?", UUID.class,
                sku.sku())).containsExactly((UUID) lot.get("id"));
        assertThat(jdbc.queryForObject("SELECT sum(on_hand) FROM inventory.stock_item WHERE sku = ?", Long.class,
                sku.sku())).isEqualTo(12L * PER_CARTON);
        ProductionOrder received = reread(production.id());
        assertThat(received.status()).isEqualTo(ProductionOrderStatus.RECEIVED);
        assertThat(received.lines().getFirst().receivedCartons()).isEqualTo(12);
        assertThat(history(production.id())).last().satisfies(h -> {
            assertThat(h.from()).isEqualTo(ProductionOrderStatus.PARTIALLY_RECEIVED);
            assertThat(h.to()).isEqualTo(ProductionOrderStatus.RECEIVED);
        });
        assertThatThrownBy(() -> start(received)).as("BR-01: received in full")
                .extracting(ProductionReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_NOT_RECEIVABLE);
    }

    @Test
    @DisplayName("03 edge cases: a draft receipt keeps the production order from being cancelled or closed")
    void draftReceiptHoldsTheProductionOrderOpen() {
        UUID orderId = confirmedOrderAtA();
        ProductionOrder sent = sentWithoutSample(orderId, 1);
        ReceiptView draft = start(sent);
        ProductionOrder current = reread(sent.id());
        assertThatThrownBy(() -> productionOrders.cancel(current.id(), current.version(), "Customer cancelled",
                coordinator)).satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_HAS_DRAFT_RECEIPTS))
                .hasMessageContaining(draft.number());
        assertThat(ErrorCode.PRODUCTION_ORDER_HAS_DRAFT_RECEIPTS.httpStatus()).isEqualTo(409);
        receipts.cancel(draft.id());
        assertThat(registration(draft.id())).isEqualTo("CANCELLED");
        assertThat(productionOrders.cancel(current.id(), current.version(), "Customer cancelled", coordinator)
                .status()).as("allowed once the draft is cancelled").isEqualTo(ProductionOrderStatus.CANCELLED);

        // Closed short: a receipt counted in, another one a draft.
        ProductionOrder other = sentWithoutSample(orderId, 1);
        ReceiptView counted = start(other);
        count(counted, lineId(other), cartons(2));
        receipts.confirm(counted.id(), clerk);
        ReceiptView open = start(reread(other.id()));
        ProductionOrder partly = reread(other.id());
        assertThat(partly.status()).isEqualTo(ProductionOrderStatus.PARTIALLY_RECEIVED);
        assertThatThrownBy(() -> productionOrders.close(partly.id(), partly.version(), "The producer stops",
                coordinator)).extracting(ProductionReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_HAS_DRAFT_RECEIPTS);
        receipts.cancel(open.id());
        ProductionOrder closed = productionOrders.close(partly.id(), partly.version(), "The producer stops",
                coordinator);
        assertThat(closed.status()).as("BR-10: closed short by the coordinator only").isEqualTo(
                ProductionOrderStatus.CLOSED);
        assertThat(closed.lines().getFirst().receivedCartons()).isEqualTo(2);
        assertThatThrownBy(() -> start(closed)).extracting(ProductionReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_NOT_RECEIVABLE);
    }

    @Test
    @DisplayName("05, 17 §4.3 step 11: the cartons put away close the receipt and are held for the waiting order")
    void putAwayClosesTheReceiptAndHoldsStockForTheOrder() {
        UUID orderId = confirmedOrderAtA();
        assertThat(shippingWarehouses.coverage(orderId).lines()).singleElement()
                .satisfies(l -> assertThat(l.heldCartons()).isZero());
        ProductionOrder production = sentWithoutSample(orderId, 1);
        ReceiptView receipt = start(production);
        count(receipt, lineId(production), cartons(3));
        ReceiptView confirmed = receipts.confirm(receipt.id(), clerk);
        Map<UUID, String> lpns = new java.util.HashMap<>();
        confirmed.lines().getFirst().packages().forEach(p -> lpns.put(p.id(), p.lpn()));

        Await.until("one putaway task per carton", Duration.ofSeconds(20),
                () -> putawayTasks.tasksOfReceipt(receipt.id()).size() == 3);
        List<PutawayTaskView> tasks = putawayTasks.tasksOfReceipt(receipt.id());
        assertThat(tasks).as("a carton each, no loose task (BR-12)").allSatisfy(task -> {
            assertThat(task.stockPackageId()).isNotNull();
            assertThat(task.quantity()).isEqualTo(PER_CARTON);
        });
        for (PutawayTaskView task : tasks) {
            putaway.confirm(task.id(), a.bin(), lpns.get(task.stockPackageId()), null, clerk);
        }

        Await.until("the receipt to close", Duration.ofSeconds(20), () -> status(receipt.id()).equals("CLOSED"));
        Await.until("the order to hold the cartons put away", Duration.ofSeconds(30),
                () -> shippingWarehouses.coverage(orderId).lines().getFirst().heldCartons() == 3);
        assertThat(jdbc.queryForObject("SELECT sum(on_hand) FROM inventory.stock_item WHERE sku = ? "
                + "AND status = 'AVAILABLE' AND location_code = ?", Long.class, sku.sku(), a.bin()))
                .isEqualTo(3L * PER_CARTON);
        assertThat(reread(production.id()).status()).isEqualTo(ProductionOrderStatus.PARTIALLY_RECEIVED);
    }

    @Test
    @DisplayName("the database holds the rules too: receivable, its own lines, whole cartons, BR-02, drafts hold it open")
    void databaseHoldsTheRules() {
        UUID orderId = confirmedOrderAtA();
        ProductionOrder production = sentWithoutSample(orderId, 1);
        ProductionOrder draftOrder = productionOrders.drawUp(orderId, 2, producer.id(), coordinator);
        ReceiptView receipt = start(production);
        UUID countedLine = count(receipt, lineId(production), cartons(4)).lines().getFirst().id();
        UUID area = jdbc.queryForObject("SELECT id FROM warehouse.storage_location WHERE location_code = ?",
                UUID.class, a.receiving());

        assertThatThrownBy(() -> sqlAtCommit("""
                INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, production_order_id,
                                                        warehouse_id, status, received_at, received_by)
                VALUES (?, ?, 'PRODUCTION', ?, ?, 'DRAFT', NOW(), ?)""", Identifiers.newId(),
                "GR-T-" + ThreadLocalRandom.current().nextInt(1_000_000), draftOrder.id(), a.id(), clerk))
                .as("BR-01: against a production order still a draft").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("goods are not received against it");
        assertThatThrownBy(() -> sqlAtCommit("""
                INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id,
                                                             inventory_item_id, location_id, received_qty, lot_number,
                                                             uom_conversion_id)
                VALUES (?, ?, ?, ?, ?, 100, ?, ?)""", Identifiers.newId(), receipt.id(), lineId(draftOrder),
                sku.itemId(), area, production.code(), sku.uomConversionId()))
                .as("a line of another production order").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("another production order");
        assertThatThrownBy(() -> sqlAtCommit("UPDATE procurement.goods_receipt_lines SET received_qty = 175 "
                + "WHERE id = ?", countedLine)).as("BR-13: not whole cartons").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("not whole cartons");
        assertThatThrownBy(() -> sqlAtCommit("UPDATE procurement.goods_receipt_lines SET received_qty = 650 "
                + "WHERE id = ?", countedLine)).as("BR-02: 13 cartons of the 12 ordered")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("03 BR-02");
        assertThatThrownBy(() -> sqlAtCommit("""
                UPDATE ordering.production_order SET status = 'CANCELLED', cancelled_at = NOW(), cancelled_by = ?,
                       cancellation_reason = 'by hand' WHERE id = ?""", coordinator, production.id()))
                .as("a draft holds it open").isInstanceOf(DataAccessException.class)
                .hasMessageContaining(receipt.number());
        assertThatThrownBy(() -> sqlAtCommit("UPDATE procurement.goods_receipts SET status = 'CANCELLED' "
                + "WHERE id = ?", receipt.id())).as("a receipt cancelled behind its production order's back")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("not registered CANCELLED");

        assertThat(receipts.get(receipt.id()).lines()).singleElement()
                .satisfies(l -> assertThat(l.quantity()).isEqualTo(200));
        assertThat(status(receipt.id())).isEqualTo("DRAFT");
        assertThat(reread(production.id()).status()).isEqualTo(ProductionOrderStatus.SENT);
    }
}
