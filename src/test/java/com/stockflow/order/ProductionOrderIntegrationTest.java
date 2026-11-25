package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
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
import com.stockflow.order.internal.service.ProductionSampleService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import com.stockflow.order.internal.service.ShippingWarehouseService.BatchCoverage;
import com.stockflow.order.internal.service.ShippingWarehouseService.Coverage;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.PrintSamples;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
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
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 4A-2 against the real database (kltn-docs 17 §4.3 steps 6-7, 12-13, §5.3; BR-05, BR-06, BR-22, BR-23, BR-24, BR-26):
 * the Production Coordinator draws a production order up for what one delivery batch of a confirmed order of a contract
 * lacks at the order's shipping warehouse and sends it to its producer - no approval step since 2026-11-20 (BR-23
 * rewritten); it is cancelled before mass printing and closed after it started.
 *
 * <p>Orders are confirmed the way production confirms them - a contract drawn up, sent and signed through
 * {@link SalesContracts} with a plan paying everything before delivery - and their shipping warehouse is chosen through
 * {@link ShippingWarehouseService}, so what a batch "lacks" is the real coverage of the holds at that warehouse, never a
 * number written into a table. Every order is 20 cartons of 50, scheduled as 12 cartons three weeks out and the other 8
 * two weeks later.</p>
 *
 * <p>Each test issues a customer SKU of its own on a design confirmed the way the design module leaves it: real bytes in
 * the (local, test) object store and a manifest adding up to the snapshot checksum. So the real {@code DesignService}
 * runs - {@code verifySnapshotForSku} when a production order is drawn up, and the byte-by-byte
 * {@code verifyForPrinting} when it is sent - instead of a mock that would agree with anything. ({@code CustomerSkuRows}
 * writes the legacy snapshot shape, which {@code verifySnapshot} refuses as unconfirmed, and a snapshot is immutable, so
 * the SKU is written here on a {@link ConfirmedDesigns} snapshot.) The SKU being the test's own also makes its stock,
 * and whether it was "produced before" (BR-24), exact whatever other tests left in the shared database.</p>
 *
 * <p>The property source and mocks are those of {@code ShippingWarehouseIntegrationTest}, so both share one context.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ProductionOrderIntegrationTest {

    /** Order numbers of this class: one JVM, one run, never the same twice (a random pick collided). */
    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(500_000);

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int PER_CARTON = 50;
    private static final int CARTONS = 20;

    /** A warehouse of the test, its code (the prefix of its location codes) and its one pickable bin. */
    private record Warehouse(UUID id, String code, String bin) {
    }

    /** The test's own customer SKU, the confirmed design it is made to, and the key of that design's print file. */
    private record IssuedSku(String sku, UUID snapshotId, String checksum, UUID uomConversionId, String printFileKey) {
    }

    /** Created once per database, as in {@code ShippingWarehouseIntegrationTest}: more would only grow every list. */
    private static Warehouse a;
    private static Warehouse b;

    @Autowired ProductionOrderService productionOrders;
    @Autowired com.stockflow.order.internal.service.ProductionOrderLabels labels;
    @Autowired ProductionSampleService samples;
    @Autowired ProducerService producers;
    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired ShippingWarehouseService shippingWarehouses;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private UUID coordinator;
    private UUID customerId;
    private UUID customerUser;
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
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, 'Production Test Co', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "production-" + customerId + "@example.com", "03" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        customerUser = PrintSamples.portalUser(jdbc, tx, entityManager, customerId);
        sku = issueSku();
        producer = producers.register(producerCode(), pdfProducer("Production test workshop"));
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    private boolean exists(Warehouse warehouse) {
        return jdbc.queryForObject("SELECT count(*) FROM warehouse.warehouse WHERE id = ?", Long.class,
                warehouse.id()) == 1;
    }

    /** An active warehouse with one active, pickable bin. */
    private Warehouse warehouse() {
        UUID id = Identifiers.newId();
        String code = "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase(Locale.ROOT);
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
                                                 is_obstacle, pick_north, pick_east, pick_south, pick_west, status)
                    VALUES (?, ?, ?, 'A01', 'Shelf', 5, 5, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'ACTIVE')""", shelf, id, zone);
            jdbc.update("""
                    INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
                    VALUES (?, ?, 1, 0, 1.4, 500)""", level, shelf);
            jdbc.update("""
                    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, max_weight, is_pickable,
                                                            is_putaway_target, status)
                    VALUES (?, ?, 'BIN', ?, 250, TRUE, TRUE, 'ACTIVE')""", location, id, bin);
            jdbc.update("""
                    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
                    VALUES (?, ?, ?, 'A', 0, 0, 5, 1.2, 0, 'PALLET')""", Identifiers.newId(), level, location);
        });
        return new Warehouse(id, code, bin);
    }

    /**
     * A customer SKU as the design approval leaves it (cf. {@code CustomerSkuRows.issueOn}), but made to a design the
     * customer really confirmed - see the class comment for why.
     */
    private IssuedSku issueSku() throws Exception {
        ReferenceRows.ProductRow product = tx.execute(s -> ReferenceRows.approvedProduct(entityManager));
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE product.products SET tax_class = 'STANDARD', kind = 'CUSTOMIZABLE' WHERE id = ?",
                product.productId()));
        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmedFor(storage, jdbc, tx, entityManager, customerId,
                product.productId());
        String printFileKey = jdbc.queryForObject("SELECT artifact_url FROM design.design_snapshot WHERE id = ?",
                String.class, design.snapshotId());
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
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor,
                        weight_kg, length_cm, width_cm, height_cm, active, version, created_at)
                    VALUES (?, ?, 'CARTON', ?, 4.2, 60, 40, 35, TRUE, 0, NOW())""",
                    carton, item, PER_CARTON);
            return new IssuedSku(code, design.snapshotId(), design.checksum(), carton, printFileKey);
        });
    }

    private static String producerCode() {
        return "PO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static Producer.Details pdfProducer(String name) {
        return new Producer.Details(name, null, null, null, Producer.Channel.PDF, 14, null);
    }

    /**
     * {@code units} pieces of the test's SKU free in {@code warehouse}'s bin, received a while ago: whole cartons of
     * {@link #PER_CARTON}, since every unit is in a carton (team plan 4C-2), so {@code units} is a multiple of it.
     */
    private void stock(Warehouse warehouse, int units) {
        tx.executeWithoutResult(s -> com.stockflow.support.CartonRows.stock(jdbc, sku.sku(), warehouse.bin(),
                units / PER_CARTON, PER_CARTON, null, java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.DAYS)));
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

    private UUID batchId(UUID orderId, int seq) {
        return jdbc.queryForObject("""
                SELECT b.id FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
                 WHERE c.order_id = ? AND b.seq = ?""", UUID.class, orderId, seq);
    }

    private static SalesContracts.Installment share(String name, String milestone, String percent, Integer dueDays) {
        return new SalesContracts.Installment(name, milestone, null, new BigDecimal(percent), dueDays, null);
    }

    /** Everything paid before delivery: signing confirms the order at once. */
    private static List<SalesContracts.Installment> payBeforeDelivery() {
        return List.of(share("Before delivery", "BEFORE_DELIVERY", "100", null));
    }

    /** A deposit on signing: signed, the order waits PENDING_PAYMENT for it. */
    private static List<SalesContracts.Installment> depositOnSigning() {
        return List.of(share("Deposit", "ON_SIGNING", "30", 0), share("Rest", "BEFORE_DELIVERY", "70", null));
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
    private SalesContracts.ContractView contractSent(UUID orderId, List<SalesContracts.Batch> schedule,
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

    /** A new order of a contract scheduled in two batches (12 + 8 cartons), signed today and CONFIRMED by it. */
    private UUID confirmedOrder() {
        return confirmedOrder(this::twoBatches);
    }

    private UUID confirmedOrder(Function<UUID, List<SalesContracts.Batch>> schedule) {
        UUID orderId = draftOrder();
        sign(orderId, contractSent(orderId, schedule.apply(lineOf(orderId)), payBeforeDelivery()));
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

    private ProductionOrder drawUp(UUID orderId, int batchSeq) {
        return productionOrders.drawUp(orderId, batchSeq, producer.id(), coordinator);
    }

    /** Sent to its producer by the coordinator who drew it up: SENT. */
    private ProductionOrder sent(ProductionOrder draft) {
        return productionOrders.send(draft.id(), draft.version(), coordinator);
    }

    /** Another file put under the key of the test's print file: its bytes no longer match the approved design. */
    private void changePrintFile() {
        byte[] other = ("%PDF-1.4\n% another print " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
        StoredFile replacement = storage.store(FileCategory.DESIGN_RENDER, "other.pdf", "application/pdf",
                other.length, new ByteArrayInputStream(other));
        storage.copy(replacement.key(), sku.printFileKey());
    }

    private long slips(UUID productionOrderId) {
        return jdbc.queryForObject("SELECT count(*) FROM ordering.production_order_slip WHERE production_order_id = ?",
                Long.class, productionOrderId);
    }

    /** {@code ProductionOrderSent} events published for it: one row per listener, so the distinct events are counted. */
    private long sentEvents(UUID productionOrderId) {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT serialized_event) FROM event_publication
                 WHERE event_type = ? AND serialized_event LIKE ?""",
                Long.class, com.stockflow.contracts.ProductionOrderSent.class.getName(), "%" + productionOrderId + "%");
    }

    /** Nothing was sent: still a DRAFT, no sender, no slip, no event. */
    private void assertNotSent(UUID productionOrderId) {
        ProductionOrder still = reread(productionOrderId);
        assertThat(still.status()).isEqualTo(ProductionOrderStatus.DRAFT);
        assertThat(still.sentBy()).isNull();
        assertThat(still.sentAt()).isNull();
        assertThat(slips(productionOrderId)).as("no slip kept").isZero();
        assertThat(sentEvents(productionOrderId)).as("nobody told").isZero();
    }

    /** As stored now - what a client reads before acting on it. */
    private ProductionOrder reread(UUID id) {
        return productionOrders.detail(id).order();
    }

    /** The draft's header as it is, with {@code lines} changed. */
    private static ProductionOrder.Draft draftOf(ProductionOrder production,
                                                 Map<UUID, ProductionOrder.LineEdit> lines) {
        return new ProductionOrder.Draft(production.producerId(), production.needByDate(),
                production.note(), production.sampleApprovalRequired(), production.sampleWaiverReason(), lines);
    }

    private static ProductionOrder.Draft withCartons(ProductionOrder production, int cartons) {
        return draftOf(production, Map.of(production.lines().get(0).orderLineId(),
                new ProductionOrder.LineEdit(cartons, production.lines().get(0).packingRequirements())));
    }

    private static ProductionOrder.Draft withSample(ProductionOrder production, boolean required, String reason) {
        return new ProductionOrder.Draft(production.producerId(), production.needByDate(),
                production.note(), required, reason, null);
    }

    /**
     * Mass printing started, as 4A-4 records it: SENT → ACCEPTED, then round 1 of its print sample approved by the
     * customer - or the coordinator's start when no sample is to be approved.
     */
    private void startMassPrinting(UUID id) {
        PrintSamples.massPrinting(productionOrders, samples, reread(id), coordinator, customerUser);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM ordering.production_order WHERE id = ?", id);
    }

    private void sql(String statement, Object... args) {
        tx.executeWithoutResult(s -> jdbc.update(statement, args));
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    private void assertCancelled(ProductionOrder cancelled, String reason) {
        assertThat(cancelled.status()).isEqualTo(ProductionOrderStatus.CANCELLED);
        assertThat(cancelled.cancelledBy()).isEqualTo(coordinator);
        assertThat(cancelled.cancelledAt()).isNotNull();
        assertThat(cancelled.cancellationReason()).isEqualTo(reason);
        assertThat(row(cancelled.id())).containsEntry("status", "CANCELLED").containsEntry("cancelled_by", coordinator)
                .containsEntry("cancellation_reason", reason);
        assertThat(productionOrders.detail(cancelled.id()).history()).last().satisfies(h -> {
            assertThat(h.to()).isEqualTo(ProductionOrderStatus.CANCELLED);
            assertThat(h.reason()).isEqualTo(reason);
            assertThat(h.by()).isEqualTo(coordinator);
        });
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("17 §4.3 step 6, BR-05, BR-24, BR-26: drawn up for what one batch lacks at the shipping warehouse")
    void drawnUpForWhatTheBatchLacks() {
        stock(a, 12 * PER_CARTON);
        UUID orderId = confirmedOrder();
        UUID line = lineOf(orderId);
        Coverage coverage = choose(orderId, a);
        assertThat(coverage.batches()).as("the 12 held cartons cover batch 1; batch 2 lacks its 8")
                .extracting(BatchCoverage::fullyHeld).containsExactly(true, false);

        ProductionOrder drawn = drawUp(orderId, 2);

        assertThat(drawn.status()).isEqualTo(ProductionOrderStatus.DRAFT);
        assertThat(drawn.code()).as("QD-6").matches("PRD-" + today.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-\\d{4,}");
        assertThat(drawn.salesOrderId()).isEqualTo(orderId);
        assertThat(drawn.deliveryBatchId()).as("exactly one batch of the schedule").isEqualTo(batchId(orderId, 2));
        assertThat(drawn.customerId()).isEqualTo(customerId);
        assertThat(drawn.producerId()).isEqualTo(producer.id());
        assertThat(drawn.receivingWarehouseId()).as("received at the order's shipping warehouse").isEqualTo(a.id());
        assertThat(drawn.needByDate()).as("BR-26: the batch's window start").isEqualTo(today.plusDays(35));
        assertThat(drawn.drawnUpBy()).isEqualTo(coordinator);
        assertThat(drawn.sampleRequiredByDefault()).as("BR-24: the SKU was never mass printed").isTrue();
        assertThat(drawn.sampleApprovalRequired()).isTrue();
        assertThat(drawn.sampleWaiverReason()).isNull();
        assertThat(drawn.submittedAt()).isNull();
        assertThat(drawn.approvedBy()).isNull();
        assertThat(drawn.sentAt()).isNull();
        assertThat(drawn.lines()).singleElement().satisfies(l -> {
            assertThat(l.orderLineId()).isEqualTo(line);
            assertThat(l.sku()).isEqualTo(sku.sku());
            assertThat(l.cartons()).as("what batch 2 lacks").isEqualTo(8);
            assertThat(l.unitsPerCarton()).isEqualTo(PER_CARTON);
            assertThat(l.totalUnits()).isEqualTo(400);
            assertThat(l.designSnapshotId()).isEqualTo(sku.snapshotId());
            assertThat(l.printFileChecksum()).as("the approved design's checksum").isEqualTo(sku.checksum());
            assertThat(l.receivedCartons()).isZero();
        });
        assertThat(jdbc.queryForObject(
                "SELECT total_units FROM ordering.production_order_line WHERE production_order_id = ?", Integer.class,
                drawn.id())).isEqualTo(400);
        assertThat(row(drawn.id())).containsEntry("status", "DRAFT").containsEntry("receiving_warehouse_id", a.id())
                .containsEntry("sample_required_by_default", true).containsEntry("code", drawn.code());

        ProductionOrderService.Detail detail = productionOrders.detail(drawn.id());
        assertThat(detail.order().code()).isEqualTo(drawn.code());
        assertThat(detail.history()).singleElement().satisfies(h -> {
            assertThat(h.from()).isNull();
            assertThat(h.to()).isEqualTo(ProductionOrderStatus.DRAFT);
            assertThat(h.by()).isEqualTo(coordinator);
            assertThat(h.reason()).isNull();
            assertThat(h.at()).isNotNull();
        });
        assertThat(productionOrders.lacking(drawn.id())).as("its own cartons still count for it")
                .containsExactly(entry(line, 8));

        assertThatThrownBy(() -> drawUp(orderId, 1)).as("batch 1 is held in full")
                .extracting(ProductionOrderIntegrationTest::code).isEqualTo(ErrorCode.PRODUCTION_ORDER_NOTHING_SHORT);
        assertThatThrownBy(() -> drawUp(orderId, 2)).as("the open production order brings batch 2's 8 cartons")
                .extracting(ProductionOrderIntegrationTest::code).isEqualTo(ErrorCode.PRODUCTION_ORDER_NOTHING_SHORT);
        assertThat(ErrorCode.PRODUCTION_ORDER_NOTHING_SHORT.httpStatus()).isEqualTo(409);
        assertThat(productionOrders.ofSalesOrder(orderId)).extracting(ProductionOrder::id).containsExactly(drawn.id());
    }

    @Test
    @DisplayName("17 §4.3 step 6: only for a confirmed order of a contract with a shipping warehouse, from an active producer")
    void refusedWhereItDoesNotApply() {
        UUID unchosen = confirmedOrder();
        assertThatThrownBy(() -> drawUp(unchosen, 1))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_NOT_ALLOWED))
                .hasMessageContaining("no shipping warehouse");

        UUID pending = draftOrder();
        sign(pending, contractSent(pending, twoBatches(lineOf(pending)), depositOnSigning()));
        assertThat(status(pending)).as("waits for its deposit").isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThatThrownBy(() -> drawUp(pending, 1))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_NOT_ALLOWED))
                .hasMessageContaining("PENDING_PAYMENT");

        UUID noContract = draftOrder();
        assertThatThrownBy(() -> drawUp(noContract, 1)).extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_NOT_ALLOWED);
        assertThatThrownBy(() -> drawUp(Identifiers.newId(), 1)).extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.NOT_FOUND);

        UUID orderId = confirmedOrder();
        choose(orderId, a);
        assertThatThrownBy(() -> drawUp(orderId, 3)).as("the schedule has two batches")
                .extracting(ProductionOrderIntegrationTest::code).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> productionOrders.drawUp(orderId, 1, Identifiers.newId(), coordinator))
                .extracting(ProductionOrderIntegrationTest::code).isEqualTo(ErrorCode.PRODUCER_NOT_FOUND);
        Producer registered = producers.register(producerCode(), pdfProducer("Closed workshop"));
        Producer inactive = producers.update(registered.id(), registered.version(), pdfProducer("Closed workshop"),
                Producer.Status.INACTIVE);
        assertThatThrownBy(() -> productionOrders.drawUp(orderId, 1, inactive.id(), coordinator))
                .extracting(ProductionOrderIntegrationTest::code).isEqualTo(ErrorCode.PRODUCER_INACTIVE);

        for (UUID order : List.of(unchosen, pending, noContract, orderId)) {
            assertThat(productionOrders.ofSalesOrder(order)).as("nothing was drawn up for %s", order).isEmpty();
        }
        // The same order and batch with an active producer: the refusals above were about the producer alone.
        assertThat(drawUp(orderId, 1).lines()).singleElement()
                .satisfies(l -> assertThat(l.cartons()).as("A has none of it: all 12").isEqualTo(12));
    }

    @Test
    @DisplayName("B33, BR-24: a draft is edited within what the batch lacks; the sample is waived only with a reason")
    void draftEditedWithinTheShortage() {
        UUID orderId = confirmedOrder();
        UUID line = lineOf(orderId);
        choose(orderId, a);
        ProductionOrder drawn = drawUp(orderId, 2);

        assertThatThrownBy(() -> productionOrders.edit(drawn.id(), drawn.version(), withCartons(drawn, 9)))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PRODUCTION_ORDER_EXCEEDS_SHORTAGE))
                .hasMessageContaining("lacks 8");

        LocalDate earlier = today.plusDays(30);
        ProductionOrder edited = productionOrders.edit(drawn.id(), drawn.version(), new ProductionOrder.Draft(
                producer.id(), earlier, "Deliver to dock 2", true, null,
                Map.of(line, new ProductionOrder.LineEdit(5, "LPN label on every carton"))));

        assertThat(edited.status()).isEqualTo(ProductionOrderStatus.DRAFT);
        assertThat(edited.version()).isGreaterThan(drawn.version());
        assertThat(edited.needByDate()).as("B33: editable while a draft").isEqualTo(earlier);
        assertThat(edited.note()).isEqualTo("Deliver to dock 2");
        assertThat(edited.lines()).singleElement().satisfies(l -> {
            assertThat(l.cartons()).isEqualTo(5);
            assertThat(l.totalUnits()).isEqualTo(250);
            assertThat(l.packingRequirements()).isEqualTo("LPN label on every carton");
            assertThat(l.printFileChecksum()).isEqualTo(sku.checksum());
        });
        assertThat(row(edited.id())).containsEntry("need_by_date", java.sql.Date.valueOf(earlier));

        assertThatThrownBy(() -> productionOrders.edit(drawn.id(), drawn.version(), draftOf(edited, null)))
                .as("read before the edit").extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        // The 3 cartons it no longer makes are lacking again: a second production order makes them.
        ProductionOrder rest = drawUp(orderId, 2);
        assertThat(rest.lines()).singleElement().satisfies(l -> assertThat(l.cartons()).isEqualTo(3));
        assertThatThrownBy(() -> productionOrders.edit(edited.id(), edited.version(), withCartons(edited, 6)))
                .as("the second now brings 3 of the 8").extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_EXCEEDS_SHORTAGE);

        assertThatThrownBy(() -> productionOrders.edit(edited.id(), edited.version(), withSample(edited, false, null)))
                .as("BR-24: off by default for a first production, off only with a reason")
                .extracting(ProductionOrderIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> productionOrders.edit(edited.id(), edited.version(), withSample(edited, false, "  ")))
                .extracting(ProductionOrderIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        ProductionOrder waived = productionOrders.edit(edited.id(), edited.version(),
                withSample(edited, false, "Same print as the customer's previous cups"));
        assertThat(waived.sampleApprovalRequired()).isFalse();
        assertThat(waived.sampleRequiredByDefault()).as("what the default was does not change").isTrue();
        assertThat(waived.sampleWaiverReason()).isEqualTo("Same print as the customer's previous cups");
        assertThat(waived.lines()).singleElement().satisfies(l -> assertThat(l.cartons()).isEqualTo(5));
        assertThat(row(waived.id())).containsEntry("sample_approval_required", false)
                .containsEntry("sample_waiver_reason", "Same print as the customer's previous cups");

        ProductionOrder back = productionOrders.edit(waived.id(), waived.version(), withSample(waived, true, null));
        assertThat(back.sampleApprovalRequired()).isTrue();
        assertThat(back.sampleWaiverReason()).as("required again, nothing is waived").isNull();

        Producer another = producers.register(producerCode(), pdfProducer("Second workshop"));
        ProductionOrder moved = productionOrders.edit(back.id(), back.version(), new ProductionOrder.Draft(
                another.id(), back.needByDate(), back.note(), true, null, null));
        assertThat(moved.producerId()).isEqualTo(another.id());
        producers.update(another.id(), another.version(), pdfProducer("Second workshop"), Producer.Status.INACTIVE);
        assertThatThrownBy(() -> productionOrders.edit(moved.id(), moved.version(), draftOf(moved, null)))
                .as("an inactive producer takes no new production order").extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCER_INACTIVE);
    }

    @Test
    @DisplayName("A13: a production order's labels name its sales order, customer, producer, receiving warehouse and "
            + "the people who drew it up, sent it and moved its status")
    void labelsNameWhatAProductionOrderRefersTo() {
        UUID orderId = confirmedOrder();
        choose(orderId, a);
        ProductionOrder sent = sent(drawUp(orderId, 2));
        List<HistoryEntry> history = productionOrders.detail(sent.id()).history();

        var found = labels.of(List.of(sent), history);

        assertThat(found.orderNumber(sent.salesOrderId())).isEqualTo(jdbc.queryForObject(
                "SELECT order_number FROM ordering.customer_order WHERE id = ?", String.class, orderId));
        assertThat(found.customer(sent.customerId()).name()).isEqualTo("Production Test Co");
        assertThat(found.producer(sent.producerId())).isEqualTo(producer.name());
        assertThat(found.warehouse(sent.receivingWarehouseId()).prefix()).isEqualTo(a.code());
        assertThat(found.warehouse(sent.receivingWarehouseId()).name()).isEqualTo("Warehouse " + a.code());
        assertThat(found.person(sent.drawnUpBy())).isEqualTo("test-" + coordinator);
        assertThat(found.person(sent.sentBy())).isEqualTo("test-" + coordinator);
        assertThat(history).isNotEmpty().allSatisfy(h -> assertThat(found.person(h.by()))
                .isEqualTo(h.by() == null ? null : "test-" + h.by()));
        assertThat(labels.of(List.of(), null).person(coordinator)).as("nothing asked, nothing read").isNull();
    }

    @Test
    @DisplayName("B33: a draft read before an edit of its lines alone is refused too (OPTIMISTIC_LOCK)")
    void lineOnlyEditMovesTheVersion() {
        UUID orderId = confirmedOrder();
        choose(orderId, a);
        ProductionOrder drawn = drawUp(orderId, 2);

        ProductionOrder edited = productionOrders.edit(drawn.id(), drawn.version(), withCartons(drawn, 5));
        assertThat(edited.lines()).singleElement().satisfies(l -> assertThat(l.cartons()).isEqualTo(5));
        assertThat(edited.version()).as("a change of its lines is a change of the production order: the version a "
                + "client read before it must no longer be current").isGreaterThan(drawn.version());

        // A second coordinator who read the draft before that edit must not overwrite it unseen.
        assertThatThrownBy(() -> productionOrders.edit(drawn.id(), drawn.version(), withCartons(drawn, 7)))
                .extracting(ProductionOrderIntegrationTest::code).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(reread(drawn.id()).lines()).singleElement()
                .satisfies(l -> assertThat(l.cartons()).isEqualTo(5));
    }

    @Test
    @DisplayName("BR-05: stock arriving after the draft is not produced again - sending checks the batch still lacks "
            + "what the production order makes")
    void shortageCheckedAgainWhenSent() {
        stock(a, 12 * PER_CARTON);
        UUID orderId = confirmedOrder();
        choose(orderId, a);
        ProductionOrder draft = drawUp(orderId, 2);

        stock(a, 8 * PER_CARTON);
        assertThat(choose(orderId, a).fullyHeld()).as("the same warehouse chosen again holds what arrived")
                .isTrue();

        assertThatThrownBy(() -> sent(draft)).extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_EXCEEDS_SHORTAGE);
        assertNotSent(draft.id());
        ProductionOrder still = reread(draft.id());
        assertThatThrownBy(() -> productionOrders.edit(still.id(), still.version(), withCartons(still, 1)))
                .as("not even one carton: the batch lacks none").extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_EXCEEDS_SHORTAGE);
        assertCancelled(productionOrders.cancel(still.id(), still.version(), "covered by stock", coordinator),
                "covered by stock");
    }

    @Test
    @DisplayName("17 §4.2 step 3: goods arriving fill the earliest batch still short, so an earlier batch's production "
            + "order covers a later batch once stock covered its own")
    void earlierProductionOrderCarriesIntoLaterBatches() {
        UUID orderId = confirmedOrder();
        assertThat(choose(orderId, a).batches()).as("nothing held: both batches short")
                .extracting(BatchCoverage::fullyHeld).containsExactly(false, false);
        ProductionOrder first = drawUp(orderId, 1);
        assertThat(first.lines()).singleElement().extracting(ProductionOrder.Line::cartons).isEqualTo(12);

        stock(a, 12 * PER_CARTON);
        assertThat(choose(orderId, a).batches()).as("stock arrived for batch 1")
                .extracting(BatchCoverage::fullyHeld).containsExactly(true, false);

        assertThatThrownBy(() -> drawUp(orderId, 2))
                .as("the 12 cartons of batch 1's production order now go to batch 2's 8")
                .extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_NOTHING_SHORT);

        productionOrders.cancel(first.id(), first.version(), "covered by stock", coordinator);
        assertThat(drawUp(orderId, 2).lines()).as("without it, batch 2 lacks its 8 again").singleElement()
                .extracting(ProductionOrder.Line::cartons).isEqualTo(8);
    }

    @Test
    @DisplayName("17 §4.3 step 7: sending does what approval did - the slip kept, the producer and the warehouse told "
            + "once, the sales order waiting for stock")
    void sendingDoesWhatApprovalDid() {
        UUID orderId = confirmedOrder();
        choose(orderId, a);
        ProductionOrder drawn = drawUp(orderId, 1);

        ProductionOrder sent = sent(drawn);

        assertThat(sent.status()).isEqualTo(ProductionOrderStatus.SENT);
        assertThat(sent.sentBy()).isEqualTo(coordinator);
        assertThat(sent.sentAt()).isNotNull();
        assertThat(sent.approvedBy()).as("nobody approves it any more").isNull();
        assertThat(sent.approvedAt()).isNull();
        assertThat(sent.submittedAt()).isNull();
        Map<String, Object> stored = row(sent.id());
        assertThat(stored).containsEntry("status", "SENT").containsEntry("sent_by", coordinator)
                .containsEntry("approved_by", null).containsEntry("approved_at", null);
        assertThat(stored.get("sent_at")).isNotNull();
        assertThat(slips(sent.id())).as("the slip as sent is kept").isEqualTo(1);
        assertThat(sentEvents(sent.id())).as("ProductionOrderSent, once: the producer and the warehouse are told")
                .isEqualTo(1);
        assertThat(status(orderId)).as("BR-04: batch 1 is short and its production order is sent")
                .isEqualTo(OrderStatus.AWAITING_STOCK);
        assertThat(productionOrders.detail(sent.id()).history())
                .extracting(HistoryEntry::from, HistoryEntry::to, HistoryEntry::reason, HistoryEntry::by)
                .containsExactly(
                        tuple(null, ProductionOrderStatus.DRAFT, null, coordinator),
                        tuple(ProductionOrderStatus.DRAFT, ProductionOrderStatus.SENT, null, coordinator));

        assertThatThrownBy(() -> productionOrders.edit(sent.id(), sent.version(), draftOf(sent, null)))
                .as("B33: settled once sent").extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);
        assertThatThrownBy(() -> sent(sent)).as("sent once").extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);
        assertThat(sentEvents(sent.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-23 rewritten: the coordinator who drew a production order up sends it - no second person")
    void theDrawerSendsTheirOwnOrder() {
        UUID orderId = confirmedOrder();
        choose(orderId, a);
        ProductionOrder drawn = drawUp(orderId, 1);
        assertThat(drawn.drawnUpBy()).isEqualTo(coordinator);

        ProductionOrder sent = productionOrders.send(drawn.id(), drawn.version(), drawn.drawnUpBy());

        assertThat(sent.status()).isEqualTo(ProductionOrderStatus.SENT);
        assertThat(sent.sentBy()).isEqualTo(sent.drawnUpBy());
        assertThat(row(sent.id())).containsEntry("sent_by", coordinator).containsEntry("drawn_up_by", coordinator);
    }

    @Test
    @DisplayName("17 §4.3 step 7: the print files are re-read before it is sent; a changed file is not sent, and "
            + "nothing is stored")
    void sendingRefusesAChangedPrintFileAndStoresNothing() {
        UUID orderId = confirmedOrder();
        choose(orderId, a);
        ProductionOrder draft = drawUp(orderId, 1);
        changePrintFile();

        assertThatThrownBy(() -> sent(draft)).extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.PRINT_FILE_MISMATCH);
        assertNotSent(draft.id());
        assertThat(status(orderId)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("a stale send is refused before any download: OPTIMISTIC_LOCK even with a changed print file")
    void aStaleVersionIsRefusedBeforeAnyDownload() {
        UUID orderId = confirmedOrder();
        choose(orderId, a);
        ProductionOrder drawn = drawUp(orderId, 1);
        productionOrders.edit(drawn.id(), drawn.version(), withCartons(drawn, 10));
        // Were the files downloaded first, this would answer PRINT_FILE_MISMATCH: the version is checked before.
        changePrintFile();

        assertThatThrownBy(() -> sent(drawn)).as("read before the edit")
                .extracting(ProductionOrderIntegrationTest::code).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        assertNotSent(drawn.id());
    }

    @Test
    @DisplayName("a producer made inactive after the draft takes no production order: refused before any download")
    void sendingRefusesAnInactiveProducer() {
        UUID orderId = confirmedOrder();
        choose(orderId, a);
        ProductionOrder draft = drawUp(orderId, 1);
        Producer current = producers.get(producer.id());
        producers.update(current.id(), current.version(), pdfProducer("Production test workshop"),
                Producer.Status.INACTIVE);
        // Were the files downloaded first, this would answer PRINT_FILE_MISMATCH.
        changePrintFile();

        assertThatThrownBy(() -> sent(draft)).extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCER_INACTIVE);
        assertNotSent(draft.id());
    }

    @Test
    @DisplayName("BR-06, 17 §4.3 steps 12-13: cancelled before mass printing, closed after it started - each with a reason")
    void cancelledBeforeMassPrintingClosedAfter() {
        UUID orderId = confirmedOrder();
        choose(orderId, a);

        ProductionOrder draft = drawUp(orderId, 1);
        assertThatThrownBy(() -> productionOrders.cancel(draft.id(), draft.version(), null, coordinator))
                .extracting(ProductionOrderIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> productionOrders.cancel(draft.id(), draft.version(), "  ", coordinator))
                .extracting(ProductionOrderIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        ProductionOrder cancelledDraft = productionOrders.cancel(draft.id(), draft.version(),
                "Customer revised the design", coordinator);
        assertCancelled(cancelledDraft, "Customer revised the design");
        assertThatThrownBy(() -> productionOrders.cancel(cancelledDraft.id(), cancelledDraft.version(), "Again",
                coordinator)).as("cancelled is final").extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);

        // Cancelled, it brings nothing: the batch lacks its 12 cartons again.
        ProductionOrder again = drawUp(orderId, 1);
        assertThat(again.lines()).singleElement().satisfies(l -> assertThat(l.cartons()).isEqualTo(12));
        assertCancelled(productionOrders.cancel(again.id(), again.version(), "Stock came back from a return",
                coordinator), "Stock came back from a return");

        ProductionOrder sent = sent(drawUp(orderId, 1));
        assertThat(sent.status()).isEqualTo(ProductionOrderStatus.SENT);
        assertThatThrownBy(() -> productionOrders.close(sent.id(), sent.version(), "Stopped", coordinator))
                .as("closed only once mass printing started").extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);
        ProductionOrder cancelledSent = productionOrders.cancel(sent.id(), sent.version(), "Sales order cancelled",
                coordinator);
        assertCancelled(cancelledSent, "Sales order cancelled");
        assertThat(productionOrders.detail(sent.id()).history()).last()
                .satisfies(h -> assertThat(h.from()).isEqualTo(ProductionOrderStatus.SENT));

        ProductionOrder printing = sent(drawUp(orderId, 1));
        startMassPrinting(printing.id());
        ProductionOrder inProduction = reread(printing.id());
        assertThat(inProduction.status()).isEqualTo(ProductionOrderStatus.IN_PRODUCTION);
        assertThat(inProduction.productionStartedAt()).isNotNull();

        assertThatThrownBy(() -> productionOrders.cancel(inProduction.id(), inProduction.version(), "Too late",
                coordinator)).as("BR-06: not cancelled once mass printing started")
                .extracting(ProductionOrderIntegrationTest::code).isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);
        assertThatThrownBy(() -> productionOrders.close(inProduction.id(), inProduction.version(), null, coordinator))
                .extracting(ProductionOrderIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        ProductionOrder closed = productionOrders.close(inProduction.id(), inProduction.version(),
                "The producer stopped before delivering anything", coordinator);

        assertThat(closed.status()).isEqualTo(ProductionOrderStatus.CLOSED);
        assertThat(closed.closedBy()).isEqualTo(coordinator);
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.closingReason()).isEqualTo("The producer stopped before delivering anything");
        assertThat(closed.productionStartedAt()).as("when mass printing started stays known")
                .isEqualTo(inProduction.productionStartedAt());
        assertThat(row(closed.id())).containsEntry("status", "CLOSED").containsEntry("closed_by", coordinator)
                .containsEntry("closing_reason", "The producer stopped before delivering anything");
        assertThat(productionOrders.detail(closed.id()).history()).last()
                .extracting(HistoryEntry::from, HistoryEntry::to, HistoryEntry::reason, HistoryEntry::by)
                .containsExactly(ProductionOrderStatus.IN_PRODUCTION, ProductionOrderStatus.CLOSED,
                        "The producer stopped before delivering anything", coordinator);

        // Closed, it delivers nothing more; and its SKU has been mass printed - no sample by default now (BR-24).
        ProductionOrder after = drawUp(orderId, 1);
        assertThat(after.lines()).singleElement().satisfies(l -> assertThat(l.cartons()).isEqualTo(12));
        assertThat(after.sampleRequiredByDefault()).isFalse();
        assertThat(after.sampleApprovalRequired()).isFalse();
        ProductionOrder off = productionOrders.edit(after.id(), after.version(), withSample(after, false, null));
        assertThat(off.sampleApprovalRequired()).as("no reason needed when it was not on by default").isFalse();
    }

    @Test
    @DisplayName("BR-22: a production order not yet sent follows the shipping warehouse; a sent one locks it")
    void shippingWarehouseAndProductionOrders() {
        stock(a, 12 * PER_CARTON);

        // Stock held at A: the warehouse is locked by the holds, whatever the production orders.
        UUID holding = confirmedOrder();
        assertThat(choose(holding, a).lines()).singleElement()
                .satisfies(l -> assertThat(l.heldCartons()).isEqualTo(12));
        ProductionOrder heldDraft = drawUp(holding, 2);
        assertThatThrownBy(() -> choose(holding, b)).extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.ORDER_SHIPPING_WAREHOUSE_LOCKED);
        assertThat(reread(heldDraft.id()).receivingWarehouseId()).isEqualTo(a.id());

        // Nothing held (A's stock is the first order's): the warehouse may change, and the drafts move with it.
        UUID orderId = confirmedOrder();
        assertThat(choose(orderId, a).lines()).singleElement()
                .satisfies(l -> assertThat(l.heldCartons()).as("A's free stock is held by the other order").isZero());
        ProductionOrder draft = drawUp(orderId, 1);
        ProductionOrder other = drawUp(orderId, 2);

        Coverage atB = choose(orderId, b);

        assertThat(atB.warehouseId()).isEqualTo(b.id());
        assertThat(reread(draft.id()).receivingWarehouseId()).as("a draft is received at the new one")
                .isEqualTo(b.id());
        assertThat(reread(other.id()).receivingWarehouseId()).as("so is every draft").isEqualTo(b.id());
        assertThat(row(draft.id())).containsEntry("receiving_warehouse_id", b.id());
        assertThat(row(other.id())).containsEntry("receiving_warehouse_id", b.id()).containsEntry("status", "DRAFT");

        ProductionOrder sent = sent(reread(other.id()));
        assertThat(sent.receivingWarehouseId()).isEqualTo(b.id());

        assertThatThrownBy(() -> choose(orderId, a))
                .as("17 §4.2 step 2: a production order sent delivers to the warehouse it names")
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.ORDER_SHIPPING_WAREHOUSE_LOCKED))
                .hasMessageContaining("production order");
        assertThat(jdbc.queryForObject("SELECT shipping_warehouse_id FROM ordering.customer_order WHERE id = ?",
                UUID.class, orderId)).isEqualTo(b.id());
        assertThat(reread(draft.id()).receivingWarehouseId()).isEqualTo(b.id());
        assertThat(reread(sent.id()).receivingWarehouseId()).isEqualTo(b.id());
        assertThat(choose(orderId, b).warehouseId()).as("the same warehouse again changes nothing").isEqualTo(b.id());
    }

    @Test
    @DisplayName("17 §5.3: the database holds the edges, the sender, the historical approver rule, the append-only "
            + "history and the fixed header")
    void databaseHoldsTheRules() {
        Set<String> rows = new HashSet<>(jdbc.queryForList(
                "SELECT from_status || '>' || to_status FROM ordering.production_order_status_transition",
                String.class));
        assertThat(rows).as("the enum and the transition table are one list of edges")
                .containsExactlyInAnyOrderElementsOf(ProductionOrderStatus.edges());

        UUID orderId = confirmedOrder();
        choose(orderId, a);
        ProductionOrder draft = drawUp(orderId, 1);
        ProductionOrder sentOne = sent(drawUp(orderId, 2));

        assertThatThrownBy(() -> sql("UPDATE ordering.production_order SET status = 'RECEIVED' WHERE id = ?",
                draft.id())).isInstanceOf(DataAccessException.class).hasMessageContaining("is not a transition");
        assertThatThrownBy(() -> sql("UPDATE ordering.production_order SET status = 'PENDING_APPROVAL' WHERE id = ?",
                draft.id())).as("no approval step any more").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("is not a transition");
        assertThatThrownBy(() -> sql("UPDATE ordering.production_order SET status = 'SENT', sent_at = NOW() "
                + "WHERE id = ?", draft.id())).as("sent says by whom").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_production_order_sent");
        assertThatThrownBy(() -> sql("UPDATE ordering.production_order SET status = 'SENT', sent_at = NOW(), "
                + "sent_by = drawn_up_by, approved_by = drawn_up_by, approved_at = NOW() WHERE id = ?", draft.id()))
                .as("an approval, where a row has one, is still by someone else")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_production_order_approval");
        tx.executeWithoutResult(s -> {
            // Sent by the one who drew it up, with no approval: what the coordinator does.
            assertThat(jdbc.update("UPDATE ordering.production_order SET status = 'SENT', sent_at = NOW(), "
                    + "sent_by = drawn_up_by WHERE id = ?", draft.id())).isEqualTo(1);
            s.setRollbackOnly();
        });
        UUID someoneElse = tx.execute(s -> ReferenceRows.user(entityManager));
        assertThatThrownBy(() -> sql("UPDATE ordering.production_order SET sent_by = ? WHERE id = ?", someoneElse,
                sentOne.id())).as("who sent it is kept").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("its sending is kept as written");

        assertThatThrownBy(() -> sql("UPDATE ordering.production_order SET receiving_warehouse_id = ? WHERE id = ?",
                b.id(), draft.id())).as("BR-22").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("shipping warehouse");
        assertThatThrownBy(() -> sql("UPDATE ordering.production_order SET sample_approval_required = FALSE "
                + "WHERE id = ?", draft.id())).as("BR-24").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_production_order_sample");
        assertThatThrownBy(() -> sql("UPDATE ordering.production_order SET delivery_batch_id = ? WHERE id = ?",
                batchId(orderId, 2), draft.id())).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("does not change");
        assertThatThrownBy(() -> sql("DELETE FROM ordering.production_order WHERE id = ?", draft.id()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("production orders are kept");
        assertThatThrownBy(() -> sql("UPDATE ordering.production_order_line SET cartons = 1 "
                + "WHERE production_order_id = ?", sentOne.id())).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("lines are not edited");

        assertThatThrownBy(() -> sql("UPDATE ordering.production_order_status_history SET reason = 'rewritten' "
                + "WHERE production_order_id = ?", sentOne.id())).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("kept as written");
        assertThatThrownBy(() -> sql("DELETE FROM ordering.production_order_status_history "
                + "WHERE production_order_id = ?", sentOne.id())).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("kept as written");

        assertThat(row(draft.id())).containsEntry("status", "DRAFT").containsEntry("receiving_warehouse_id", a.id())
                .containsEntry("sample_approval_required", true);
        assertThat(row(sentOne.id())).containsEntry("status", "SENT").containsEntry("sent_by", coordinator);
        assertThat(productionOrders.detail(sentOne.id()).history()).hasSize(2);
        assertThat(reread(sentOne.id()).lines()).singleElement().satisfies(l -> assertThat(l.cartons()).isEqualTo(8));
    }

    @Test
    @DisplayName("00 §2.1, 17 §4.3: the coordinator draws up and sends, nobody approves, the Sales Manager is told, "
            + "Sales and the warehouse manager read")
    void grants() {
        assertThat(actions("PRODUCTION_COORDINATOR")).containsExactly("CREATE", "READ", "UPDATE", "VIEW_PAGE");
        assertThat(actions("SALES_MANAGER")).containsExactly("READ", "VIEW_PAGE");
        assertThat(actions("SALES_STAFF")).containsExactly("READ", "VIEW_PAGE");
        assertThat(actions("WAREHOUSE_MANAGER")).containsExactly("READ", "VIEW_PAGE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.permission WHERE code = 'production-orders:APPROVE'",
                Long.class)).as("approval is retired (V20261213000200)").isZero();
        assertThat(jdbc.queryForList("""
                SELECT r.code FROM identity.role_permission rp
                  JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE p.code = 'production-order-sends:READ' AND r.is_system ORDER BY 1""", String.class))
                .as("told of production orders sent").containsExactly("SALES_MANAGER", "SYSTEM_ADMIN");
    }

    private List<String> actions(String role) {
        return jdbc.queryForList("""
                SELECT p.action FROM identity.role_permission rp
                  JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE r.code = ? AND p.resource = 'production-orders' ORDER BY 1""", String.class, role);
    }

    @Test
    @DisplayName("listed by order, status and producer; a warehouse-bound manager sees what comes to their warehouses only")
    void listedAndScopedToTheWarehouse() {
        Producer other = producers.register(producerCode(), pdfProducer("Other workshop"));
        UUID first = confirmedOrder();
        choose(first, a);
        UUID second = confirmedOrder();
        choose(second, b);
        ProductionOrder atA = drawUp(first, 1);
        ProductionOrder sentAtA = sent(productionOrders.drawUp(first, 2, other.id(), coordinator));
        ProductionOrder atB = drawUp(second, 1);

        assertThat(ids(productionOrders.list(null, null, null, first, 0, 50, null).items()))
                .containsExactlyInAnyOrder(atA.id(), sentAtA.id());
        assertThat(ids(productionOrders.list(null, ProductionOrderStatus.SENT, null, first, 0, 50, null)
                .items())).containsExactly(sentAtA.id());
        assertThat(ids(productionOrders.list(null, null, producer.id(), null, 0, 50, null).items()))
                .as("newest first by default").containsExactly(atB.id(), atA.id());
        assertThat(ids(productionOrders.list(a.id(), null, producer.id(), null, 0, 50, null).items()))
                .containsExactly(atA.id());
        assertThat(ids(productionOrders.list(null, null, other.id(), null, 0, 50, null).items()))
                .containsExactly(sentAtA.id());

        assertThat(jdbc.queryForObject("SELECT data_scope FROM identity.app_role WHERE code = 'WAREHOUSE_MANAGER'",
                String.class)).as("what makes the warehouse manager warehouse-bound").isEqualTo("WAREHOUSE");
        UUID managerId = tx.execute(s -> ReferenceRows.user(entityManager));
        CurrentUser managerOfB = new CurrentUser(managerId, "wm-" + managerId, Set.of(Role.WAREHOUSE_MANAGER),
                Set.of(), DataScope.WAREHOUSE, Set.of(b.code()), Set.of(b.id()));
        // DataScope.ALL is what the controller's READ endpoints declare.
        assertThat(ids(WithCurrentUser.call(managerOfB, DataScope.ALL,
                () -> productionOrders.list(null, null, producer.id(), null, 0, 50, null).items())))
                .containsExactly(atB.id());
        assertThat(WithCurrentUser.call(managerOfB, DataScope.ALL,
                () -> productionOrders.list(null, null, null, first, 0, 50, null).items())).isEmpty();
        assertThat(WithCurrentUser.call(managerOfB, DataScope.ALL, () -> productionOrders.detail(atB.id()))
                .order().id()).isEqualTo(atB.id());
        assertThatThrownBy(() -> WithCurrentUser.call(managerOfB, DataScope.ALL,
                () -> productionOrders.detail(atA.id()))).extracting(ProductionOrderIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_NOT_FOUND);
        assertThat(WithCurrentUser.call(managerOfB, DataScope.ALL, () -> productionOrders.ofSalesOrder(first)))
                .isEmpty();

        CurrentUser assignedNowhere = new CurrentUser(managerId, "wm-" + managerId, Set.of(Role.WAREHOUSE_MANAGER),
                Set.of(), DataScope.WAREHOUSE, Set.of(), Set.of());
        assertThat(WithCurrentUser.call(assignedNowhere, DataScope.ALL,
                () -> productionOrders.list(null, null, producer.id(), null, 0, 50, null).items()))
                .as("assigned no warehouse: sees nothing").isEmpty();
        assertThat(ids(asSales(() -> productionOrders.list(null, null, producer.id(), null, 0, 50, null).items())))
                .as("a caller scoped to ALL is not limited").containsExactlyInAnyOrder(atA.id(), atB.id());
    }

    private static List<UUID> ids(List<ProductionOrder> productionOrders) {
        return productionOrders.stream().map(ProductionOrder::id).toList();
    }
}
