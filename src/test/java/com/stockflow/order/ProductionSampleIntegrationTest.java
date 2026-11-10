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
import com.stockflow.contracts.ProductionOrderSampleApproved;
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
import com.stockflow.order.internal.domain.ReprintReason;
import com.stockflow.order.internal.domain.SampleRound;
import com.stockflow.order.internal.service.ProducerService;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.order.internal.service.ProductionSampleService;
import com.stockflow.order.internal.service.ProductionSampleService.RoundsView;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.ShippingWarehouseService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4A-4 against the real database (kltn-docs 17 §4.3 steps 8-10, §5.3, BR-24; 13 §4.3 step 5, BR-03, BR-13; 14 BR-18):
 * the producer's acceptance with its expected completion day; the coordinator's start of mass printing when no print
 * sample is to be approved; and the print sample rounds - uploaded by the coordinator, answered once by the customer
 * who owns the production order, counted against the contract's free rounds.
 *
 * <p>Orders are confirmed as {@link ProductionOrderIntegrationTest} confirms them (a contract drawn up, sent and signed,
 * its shipping warehouse chosen), and its fixtures - warehouse, a customer SKU on a really confirmed design - are those
 * of that class; the property source and mocks are its own, so all share one context. The photos are stored through
 * the real storage layer (the local store of the test profile). Receiving against a production order whose sample
 * waits is {@code ProductionReceiptIntegrationTest}'s.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
@RecordApplicationEvents
class ProductionSampleIntegrationTest {

    /** Order numbers of this class, apart from those of the other production test classes. */
    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(640_000);

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int PER_CARTON = 50;
    private static final int CARTONS = 20;

    private record Warehouse(UUID id, String code, String bin) {
    }

    private record IssuedSku(String sku, UUID uomConversionId) {
    }

    private static Warehouse a;

    @Autowired ProductionOrderService productionOrders;
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
    @Autowired ApplicationEvents events;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private UUID coordinator;
    private UUID manager;
    private UUID customerId;
    private UUID customerUser;
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
        customerId = customer("Print Sample Co");
        customerUser = PrintSamples.portalUser(jdbc, tx, entityManager, customerId);
        sku = issueSku();
        producer = producers.register("PO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT),
                new Producer.Details("Sample workshop", null, null, null, Producer.Channel.PDF, 14, null));
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures (ProductionOrderIntegrationTest's) ----------------------------------------------------------------

    private UUID customer(String name) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, ?, ?, ?, 'ACTIVE', 0, NOW())""", id, name, "sample-" + id + "@example.com",
                    "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), id);
        });
        return id;
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

    /** The test's own customer SKU on a design the customer really confirmed: it was never produced (BR-24). */
    private IssuedSku issueSku() throws Exception {
        ReferenceRows.ProductRow product = tx.execute(s -> ReferenceRows.approvedProduct(entityManager));
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE product.products SET tax_class = 'STANDARD', kind = 'CUSTOMIZABLE' WHERE id = ?",
                product.productId()));
        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmedFor(storage, jdbc, tx, entityManager, customerId,
                product.productId());
        return tx.execute(s -> {
            UUID variant = Identifiers.newId();
            String code = product.sku() + "-S" + variant.toString().substring(24, 28).toUpperCase(Locale.ROOT);
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

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    /**
     * A CONFIRMED order of 20 cartons in one batch three weeks out, with {@code freeRounds} free sample rounds on its
     * contract (null: the default, 14 BR-18), its shipping warehouse chosen.
     */
    private UUID confirmedOrder(Integer freeRounds) {
        UUID orderId = tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), CARTONS * PER_CARTON,
                    Money.vnd(2_000), null).withCartons(sku.uomConversionId(), CARTONS, PER_CARTON)
                    .withTax(BigDecimal.TEN);
            Order order = Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()), customerId,
                    Identifiers.newId(), List.of(line), Instant.now());
            return orderRepository.save(order).id().value();
        });
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId, new SalesContracts.Draft(
                freeRounds, List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                List.of(new SalesContracts.BatchLine(line, CARTONS)))),
                List.of(new SalesContracts.Installment("Before delivery", "BEFORE_DELIVERY", null,
                        new BigDecimal("100"), null, null)))));
        SalesContracts.ContractView sent = asSales(() -> contracts.send(sales, orderId, drafted.version()));
        byte[] scan = TestPdfs.blank();
        asSales(() -> contracts.sign(sales, orderId, sent.version(), IncomingFiles.sent(List.of(new FileUpload(
                "signed.pdf", "application/pdf", scan.length, new ByteArrayInputStream(scan)))), today));
        assertThat(status(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        shippingWarehouses.choose(orderId, a.id(), sales.userId());
        return orderId;
    }

    private OrderStatus status(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    /** Drawn up for the order's batch, submitted, approved by the manager: SENT, a print sample wanted (BR-24). */
    private ProductionOrder sent(UUID orderId) {
        ProductionOrder draft = productionOrders.drawUp(orderId, 1, producer.id(), coordinator);
        ProductionOrder pending = productionOrders.submit(draft.id(), draft.version(), coordinator);
        return productionOrders.approve(pending.id(), pending.version(), manager);
    }

    private ProductionOrder accepted(UUID orderId) {
        ProductionOrder sent = sent(orderId);
        return productionOrders.accept(sent.id(), sent.version(), today.plusDays(14), coordinator);
    }

    private ProductionOrder reread(UUID id) {
        return productionOrders.detail(id).order();
    }

    private static UUID waiting(RoundsView view) {
        return view.rounds().waiting().map(SampleRound::id).orElseThrow();
    }

    private void sql(String statement, Object... args) {
        tx.executeWithoutResult(s -> jdbc.update(statement, args));
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    private java.sql.Timestamp printingStartedAt(UUID orderId) {
        return jdbc.queryForObject("SELECT printing_started_at FROM ordering.customer_order WHERE id = ?",
                java.sql.Timestamp.class, orderId);
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("17 §4.3 steps 8, 10: accepted with the producer's day; started by hand only with no sample (BR-24)")
    void acceptedAndStartedByHand() {
        UUID orderId = confirmedOrder(null);
        ProductionOrder sent = sent(orderId);
        assertThat(sent.sampleApprovalRequired()).as("a SKU never produced wants a sample").isTrue();
        assertThatThrownBy(() -> productionOrders.expectCompletion(sent.id(), sent.version(), today.plusDays(9)))
                .as("no day before the producer accepted").extracting(ProductionSampleIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);

        ProductionOrder accepted = productionOrders.accept(sent.id(), sent.version(), today.plusDays(14), coordinator);
        assertThat(accepted.status()).isEqualTo(ProductionOrderStatus.ACCEPTED);
        assertThat(accepted.expectedCompletionDate()).isEqualTo(today.plusDays(14));
        assertThat(jdbc.queryForObject("SELECT expected_completion_date FROM ordering.production_order WHERE id = ?",
                LocalDate.class, sent.id())).isEqualTo(today.plusDays(14));
        assertThat(productionOrders.detail(sent.id()).history()).last()
                .extracting(HistoryEntry::from, HistoryEntry::to, HistoryEntry::by)
                .containsExactly(ProductionOrderStatus.SENT, ProductionOrderStatus.ACCEPTED, coordinator);

        ProductionOrder moved = productionOrders.expectCompletion(accepted.id(), accepted.version(),
                today.plusDays(20));
        assertThat(moved.expectedCompletionDate()).isEqualTo(today.plusDays(20));
        assertThat(moved.version()).as("a new day is a change a later edit is checked against")
                .isGreaterThan(accepted.version());
        assertThatThrownBy(() -> productionOrders.expectCompletion(moved.id(), moved.version(), today.minusDays(1)))
                .as("a day already gone").extracting(ProductionSampleIntegrationTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> productionOrders.expectCompletion(accepted.id(), accepted.version(),
                today.plusDays(21))).as("read before the move").extracting(ProductionSampleIntegrationTest::code)
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        assertThatThrownBy(() -> productionOrders.startProduction(moved.id(), moved.version(), null, coordinator))
                .as("BR-24: the customer's approval starts it").extracting(ProductionSampleIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_SAMPLE_REQUIRED);
        assertThat(reread(moved.id()).status()).isEqualTo(ProductionOrderStatus.ACCEPTED);

        // Waived: the coordinator starts it when the producer says so, with a new day.
        UUID otherOrder = confirmedOrder(null);
        ProductionOrder draft = productionOrders.drawUp(otherOrder, 1, producer.id(), coordinator);
        ProductionOrder waived = productionOrders.edit(draft.id(), draft.version(), new ProductionOrder.Draft(
                draft.producerId(), draft.needByDate(), null, null, false, "A re-run of a tested design", null));
        ProductionOrder pending = productionOrders.submit(waived.id(), waived.version(), coordinator);
        ProductionOrder sentWaived = productionOrders.approve(pending.id(), pending.version(), manager);
        assertThatThrownBy(() -> samples.open(sentWaived.id(), null, null, PrintSamples.photos(1), coordinator))
                .extracting(ProductionSampleIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_SAMPLE_NOT_REQUIRED);
        ProductionOrder acceptedWaived = productionOrders.accept(sentWaived.id(), sentWaived.version(),
                today.plusDays(10), coordinator);
        assertThat(printingStartedAt(otherOrder)).isNull();
        ProductionOrder started = productionOrders.startProduction(acceptedWaived.id(), acceptedWaived.version(),
                today.plusDays(12), coordinator);
        assertThat(started.status()).isEqualTo(ProductionOrderStatus.IN_PRODUCTION);
        assertThat(started.productionStartedAt()).isNotNull();
        assertThat(started.expectedCompletionDate()).isEqualTo(today.plusDays(12));
        assertThat(printingStartedAt(otherOrder)).as("BR-11: the sales order records printing started").isNotNull();
    }

    @Test
    @DisplayName("17 §4.3 steps 9-10, 13 BR-13: round 1 makes it pending; the owner approves once and printing starts")
    void approvedByItsOwnerOnce() {
        UUID orderId = confirmedOrder(null);
        ProductionOrder accepted = accepted(orderId);
        UUID other = customer("Other Co");
        PrintSamples.portalUser(jdbc, tx, entityManager, other);
        assertThat(samples.awaiting(customerId)).isEmpty();

        RoundsView opened = samples.open(accepted.id(), null, today, PrintSamples.photos(2), coordinator);
        UUID round = waiting(opened);
        assertThat(opened.order().status()).isEqualTo(ProductionOrderStatus.SAMPLE_PENDING);
        assertThat(opened.rounds().rounds()).singleElement().satisfies(r -> {
            assertThat(r.roundNo()).isEqualTo(1);
            assertThat(r.physicalSampleSentOn()).isEqualTo(today);
            assertThat(r.media()).hasSize(2).allSatisfy(m -> {
                assertThat(m.storageKey()).startsWith("print-samples/").endsWith(".jpg");
                assertThat(m.contentType()).isEqualTo("image/jpeg");
                assertThat(storage.exists(m.storageKey())).as("adopted, so kept").isTrue();
            });
        });
        assertThat(opened.allowance()).isEqualTo(new com.stockflow.order.internal.domain.SampleRounds.Allowance(2, 1));
        assertThat(productionOrders.detail(accepted.id()).history()).last()
                .extracting(HistoryEntry::to, HistoryEntry::by)
                .containsExactly(ProductionOrderStatus.SAMPLE_PENDING, coordinator);

        // The customer's page: their own pending sample only.
        assertThat(samples.awaiting(customerId)).singleElement().satisfies(view -> {
            assertThat(view.order().id()).isEqualTo(accepted.id());
            assertThat(waiting(view)).isEqualTo(round);
        });
        assertThat(samples.awaiting(other)).as("BR-15: another customer's samples do not exist for them").isEmpty();
        UUID media = opened.rounds().rounds().getFirst().media().getFirst().id();
        // The owner's link itself needs the S3 provider (the test profile stores locally); its owner check comes first.

        // Another customer: no such round, whatever they try.
        assertThatThrownBy(() -> samples.customerRound(other, round)).extracting(ProductionSampleIntegrationTest::code)
                .isEqualTo(ErrorCode.SAMPLE_ROUND_NOT_FOUND);
        assertThatThrownBy(() -> samples.customerMediaLink(other, round, media))
                .extracting(ProductionSampleIntegrationTest::code).isEqualTo(ErrorCode.SAMPLE_ROUND_NOT_FOUND);
        assertThatThrownBy(() -> samples.answer(other, customerUser, round, SampleRound.Result.APPROVED, null))
                .extracting(ProductionSampleIntegrationTest::code).isEqualTo(ErrorCode.SAMPLE_ROUND_NOT_FOUND);
        assertThat(reread(accepted.id()).status()).isEqualTo(ProductionOrderStatus.SAMPLE_PENDING);
        assertThat(samples.awaiting(customerId)).hasSize(1);

        assertThat(printingStartedAt(orderId)).isNull();
        RoundsView approved = samples.answer(customerId, customerUser, round, SampleRound.Result.APPROVED, null);
        ProductionOrder printing = reread(accepted.id());
        assertThat(approved.order().status()).isEqualTo(ProductionOrderStatus.IN_PRODUCTION);
        assertThat(printing.status()).isEqualTo(ProductionOrderStatus.IN_PRODUCTION);
        assertThat(printing.productionStartedAt()).isNotNull();
        assertThat(printing.receivable()).as("approved, goods are received against it").isTrue();
        assertThat(productionOrders.detail(accepted.id()).history()).last()
                .extracting(HistoryEntry::from, HistoryEntry::to, HistoryEntry::by)
                .containsExactly(ProductionOrderStatus.SAMPLE_PENDING, ProductionOrderStatus.IN_PRODUCTION,
                        customerUser);
        assertThat(printingStartedAt(orderId)).as("4A-6, BR-11: printing started recorded on the sales order")
                .isEqualTo(java.sql.Timestamp.from(printing.productionStartedAt()));
        assertThat(events.stream(ProductionOrderSampleApproved.class))
                .as("B31: the producer is told to print the batch").singleElement()
                .satisfies(e -> {
                    assertThat(e.productionOrderId()).isEqualTo(accepted.id());
                    assertThat(e.code()).isEqualTo(accepted.code());
                });
        assertThat(jdbc.queryForMap("""
                SELECT result, answered_by, customer_note FROM ordering.production_order_sample_round WHERE id = ?""",
                round)).containsEntry("result", "APPROVED").containsEntry("answered_by", customerUser)
                .containsEntry("customer_note", null);

        // Answered once: neither answer again, nor the other one.
        assertThatThrownBy(() -> samples.answer(customerId, customerUser, round, SampleRound.Result.APPROVED, null))
                .extracting(ProductionSampleIntegrationTest::code).isEqualTo(ErrorCode.SAMPLE_ROUND_ANSWERED);
        assertThatThrownBy(() -> samples.answer(customerId, customerUser, round,
                SampleRound.Result.REPRINT_REQUESTED, "too late")).extracting(ProductionSampleIntegrationTest::code)
                .isEqualTo(ErrorCode.SAMPLE_ROUND_ANSWERED);
        assertThat(samples.awaiting(customerId)).as("nothing waits any more").isEmpty();
        assertThat(samples.customerRound(customerId, round).rounds().rounds()).singleElement()
                .extracting(SampleRound::result).as("the round shows its answer").isEqualTo(SampleRound.Result.APPROVED);
        assertThatThrownBy(() -> samples.open(accepted.id(), ReprintReason.CUSTOMER_CHANGE, null,
                PrintSamples.photos(1), coordinator)).extracting(ProductionSampleIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);
    }

    @Test
    @DisplayName("17 §4.3 step 9, 14 BR-18: reprints counted but PRINT_DEFECT, past the contract's free rounds warns")
    void reprintsCountedAgainstTheFreeRounds() {
        UUID orderId = confirmedOrder(1);
        ProductionOrder accepted = accepted(orderId);
        RoundsView first = samples.open(accepted.id(), null, null, PrintSamples.photos(1), coordinator);
        assertThat(first.allowance().free()).as("the contract's free rounds").isEqualTo(1);
        assertThat(first.allowance().exceeded()).isFalse();

        assertThatThrownBy(() -> samples.open(accepted.id(), ReprintReason.PRINT_DEFECT, null,
                PrintSamples.photos(1), coordinator)).as("one round waits at a time")
                .extracting(ProductionSampleIntegrationTest::code).isEqualTo(ErrorCode.SAMPLE_ROUND_NOT_OPENABLE);
        assertThatThrownBy(() -> samples.answer(customerId, customerUser, waiting(first),
                SampleRound.Result.REPRINT_REQUESTED, " ")).as("a reprint says what to change")
                .extracting(ProductionSampleIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        RoundsView sentBack = samples.answer(customerId, customerUser, waiting(first),
                SampleRound.Result.REPRINT_REQUESTED, "The logo is smudged");
        assertThat(sentBack.order().status()).isEqualTo(ProductionOrderStatus.SAMPLE_PENDING);
        assertThat(samples.awaiting(customerId)).as("nothing waits for the customer until the next round").isEmpty();

        assertThatThrownBy(() -> samples.open(accepted.id(), null, null, PrintSamples.photos(1), coordinator))
                .as("a reprint is classified").extracting(ProductionSampleIntegrationTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        RoundsView defect = samples.open(accepted.id(), ReprintReason.PRINT_DEFECT, null, PrintSamples.photos(1),
                coordinator);
        assertThat(defect.allowance().counted()).as("the producer's defect is not the customer's round").isEqualTo(1);
        assertThat(defect.allowance().exceeded()).isFalse();
        RoundsView photographed = samples.physicalSampleSent(accepted.id(), waiting(defect), today);
        assertThat(photographed.rounds().latest().orElseThrow().physicalSampleSentOn()).isEqualTo(today);
        RoundsView more = samples.addMedia(accepted.id(), waiting(defect), PrintSamples.photos(2), coordinator);
        assertThat(more.rounds().latest().orElseThrow().media()).hasSize(3);

        samples.answer(customerId, customerUser, waiting(defect), SampleRound.Result.REPRINT_REQUESTED,
                "A darker blue after all");
        RoundsView change = samples.open(accepted.id(), ReprintReason.CUSTOMER_CHANGE, null, PrintSamples.photos(1),
                coordinator);
        assertThat(change.rounds().rounds()).extracting(SampleRound::roundNo, SampleRound::reprintReason,
                SampleRound::counted).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1, null, true),
                org.assertj.core.groups.Tuple.tuple(2, ReprintReason.PRINT_DEFECT, false),
                org.assertj.core.groups.Tuple.tuple(3, ReprintReason.CUSTOMER_CHANGE, true));
        assertThat(change.allowance().counted()).isEqualTo(2);
        assertThat(change.allowance().exceeded()).as("2 counted, 1 free: warned, not refused").isTrue();
        assertThat(samples.rounds(accepted.id()).allowance().exceeded()).as("Sales reads the same warning").isTrue();

        // Cancelled while it waits: the round is no longer answered.
        ProductionOrder pending = reread(accepted.id());
        productionOrders.cancel(pending.id(), pending.version(), "The customer revised the design", coordinator);
        assertThatThrownBy(() -> samples.answer(customerId, customerUser, waiting(change),
                SampleRound.Result.APPROVED, null)).extracting(ProductionSampleIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);
        assertThat(samples.awaiting(customerId)).isEmpty();
        assertThatThrownBy(() -> samples.physicalSampleSent(accepted.id(), waiting(change), today))
                .extracting(ProductionSampleIntegrationTest::code).isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);
        assertThatThrownBy(() -> sql("""
                UPDATE ordering.production_order_sample_round SET physical_sample_sent_on = CURRENT_DATE
                 WHERE id = ?""", waiting(change))).as("the database too").hasMessageContaining("pending");
        assertThatThrownBy(() -> sql("""
                INSERT INTO ordering.production_order_sample_media (id, sample_round_id, storage_key, file_name,
                                                                    content_type, size_bytes, uploaded_by, uploaded_at)
                VALUES (?, ?, 'print-samples/x/late.jpg', 'a.jpg', 'image/jpeg', 10, ?, NOW())""",
                Identifiers.newId(), waiting(change), coordinator)).hasMessageContaining("pending");
        assertThat(events.stream(ProductionOrderSampleApproved.class)).isEmpty();
    }

    @Test
    @DisplayName("BR-24, 13 BR-13: the database holds the rules of the rounds as well")
    void databaseHoldsTheRules() {
        UUID orderId = confirmedOrder(null);
        ProductionOrder sent = sent(orderId);
        UUID roundId = Identifiers.newId();
        String insertRound = """
                INSERT INTO ordering.production_order_sample_round (id, production_order_id, round_no, reprint_reason,
                                                                    opened_by, opened_at)
                VALUES (?, ?, ?, ?, ?, NOW())""";
        String insertMedia = """
                INSERT INTO ordering.production_order_sample_media (id, sample_round_id, storage_key, file_name,
                                                                    content_type, size_bytes, uploaded_by, uploaded_at)
                VALUES (?, ?, ?, 'a.jpg', 'image/jpeg', 10, ?, NOW())""";
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update(insertRound, roundId, sent.id(), 1, null, coordinator);
            jdbc.update(insertMedia, Identifiers.newId(), roundId, "print-samples/x/" + roundId + ".jpg", coordinator);
        })).as("round 1 before the producer accepted").hasMessageContaining("accepted");
        assertThatThrownBy(() -> sql("""
                UPDATE ordering.production_order SET status = 'ACCEPTED', version = version + 1 WHERE id = ?""",
                sent.id())).as("accepted without the producer's day").hasMessageContaining("ck_production_order_accepted");
        assertThatThrownBy(() -> sql("""
                UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW()
                 WHERE id = ?""", sent.id())).as("BR-24: from SENT with a sample to approve")
                .hasMessageContaining("print sample");

        ProductionOrder accepted = productionOrders.accept(sent.id(), sent.version(), today.plusDays(7), coordinator);
        assertThatThrownBy(() -> sql("""
                UPDATE ordering.production_order SET status = 'SAMPLE_PENDING' WHERE id = ?""", accepted.id()))
                .as("pending before round 1 is there").hasMessageContaining("round 1");
        assertThatThrownBy(() -> sql("""
                UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW()
                 WHERE id = ?""", accepted.id())).as("BR-24: from ACCEPTED with a sample to approve")
                .hasMessageContaining("print sample");
        assertThatThrownBy(() -> sql(insertRound, roundId, accepted.id(), 1, null, coordinator))
                .as("a round shows a photo or video, at commit").hasMessageContaining("photo or video");

        RoundsView opened = samples.open(accepted.id(), null, null, PrintSamples.photos(1), coordinator);
        UUID round = waiting(opened);
        assertThatThrownBy(() -> sql("""
                UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW()
                 WHERE id = ?""", accepted.id())).as("no round approved").hasMessageContaining("approved");
        assertThatThrownBy(() -> sql("""
                UPDATE ordering.production_order_sample_round SET result = 'APPROVED', answered_by = ?,
                       answered_at = NOW() WHERE id = ?""", coordinator, round))
                .as("13 BR-13: answered by the customer who owns it").hasMessageContaining("customer who owns it");
        assertThatThrownBy(() -> sql("""
                UPDATE ordering.production_order_sample_round SET result = 'REPRINT_REQUESTED', answered_by = ?,
                       answered_at = opened_at WHERE id = ?""", customerUser, round))
                .as("a reprint request says what to change").hasMessageContaining("ck_production_order_sample_round_note");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update(insertRound, Identifiers.newId(), accepted.id(), 2, "PRINT_DEFECT", coordinator);
        })).as("round 2 before round 1 was sent back").hasMessageContaining("follows a reprint request");
        assertThatThrownBy(() -> sql("DELETE FROM ordering.production_order_sample_round WHERE id = ?", round))
                .hasMessageContaining("kept");
        assertThatThrownBy(() -> sql("""
                UPDATE ordering.production_order_sample_media SET file_name = 'other.jpg' WHERE sample_round_id = ?""",
                round)).hasMessageContaining("kept as uploaded");
        assertThatThrownBy(() -> sql("""
                UPDATE ordering.production_order SET expected_completion_date = NULL WHERE id = ?""", accepted.id()))
                .hasMessageContaining("ck_production_order_accepted");

        samples.answer(customerId, customerUser, round, SampleRound.Result.APPROVED, null);
        assertThatThrownBy(() -> sql("""
                UPDATE ordering.production_order_sample_round SET result = 'REPRINT_REQUESTED',
                       customer_note = 'changed my mind' WHERE id = ?""", round))
                .as("answered once").hasMessageContaining("answered, once");
        assertThatThrownBy(() -> sql(insertMedia, Identifiers.newId(), round, "print-samples/x/late.jpg", coordinator))
                .as("in production, a round keeps what the customer saw").hasMessageContaining("pending");
    }

    @Test
    @DisplayName("13 BR-13: the customer's portal permission, nobody else's")
    void grants() {
        assertThat(jdbc.queryForList("""
                SELECT r.code || ':' || p.action FROM identity.role_permission rp
                  JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE p.resource = 'production-samples' ORDER BY 1""", String.class))
                .containsExactly("CUSTOMER:APPROVE", "CUSTOMER:READ", "CUSTOMER:VIEW_PAGE", "SYSTEM_ADMIN:APPROVE",
                        "SYSTEM_ADMIN:READ", "SYSTEM_ADMIN:VIEW_PAGE");
    }
}
