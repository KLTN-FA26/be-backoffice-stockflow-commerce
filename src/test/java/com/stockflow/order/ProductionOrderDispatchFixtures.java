package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.SignedLinks;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.notification.internal.domain.Email;
import com.stockflow.notification.internal.domain.EmailTransport;
import com.stockflow.notification.internal.service.RecordingEmailTransport;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.service.ProducerService;
import com.stockflow.order.internal.service.ProductionOrderDispatch;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fixtures of the 4A-3 integration tests (kltn-docs 17 §4.3 step 7): a confirmed order of a signed contract, its
 * shipping warehouse chosen, a customer SKU on a design the customer really confirmed, a producer reached by e-mail -
 * and the helpers that read what the producer was sent. Shared by {@link ProductionOrderDispatchIntegrationTest} (the
 * services, security off, its context shared with {@link ProductionOrderIntegrationTest}) and
 * {@link ProductionOrderFileLinksSecurityIntegrationTest} (the public routes through the real filter chain), which
 * carry their own context annotations.
 */
abstract class ProductionOrderDispatchFixtures {

    protected static final AtomicLong ORDER_NUMBERS = new AtomicLong(600_000);
    protected static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    protected static final Duration ASYNC = Duration.ofSeconds(20);
    protected static final int PER_CARTON = 50;
    protected static final int CARTONS = 20;
    protected static final Pattern LINK = Pattern.compile("(http://localhost:8080(/api/v1/production-order-files/\\S+?))"
            + "\\?expires=(\\d+)&signature=([A-Za-z0-9_-]+)");

    protected record Warehouse(UUID id, String code, String bin) {
    }

    protected record IssuedSku(String sku, UUID snapshotId, String checksum, UUID uomConversionId, String printFileKey) {
    }

    /** One link out of an e-mail: its route, expiry and signature. */
    protected record Link(String path, long expires, String signature) {
    }

    protected static Warehouse a;

    @Autowired ProductionOrderService productionOrders;
    @Autowired ProductionOrderDispatch dispatch;
    @Autowired ProducerService producers;
    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired ShippingWarehouseService shippingWarehouses;
    @Autowired FileStorage storage;
    @Autowired SignedLinks links;
    @Autowired EmailTransport transport;
    @Autowired ApplicationEventPublisher events;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    protected CurrentUser sales;
    protected UUID coordinator;
    protected UUID approver;
    protected UUID warehouseManager;
    protected UUID customerId;
    protected IssuedSku sku;
    protected String producerEmail;
    protected Producer producer;
    protected LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        if (a == null || jdbc.queryForObject("SELECT count(*) FROM warehouse.warehouse WHERE id = ?", Long.class,
                a.id()) == 0) {
            a = warehouse();
        }
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        approver = tx.execute(s -> ReferenceRows.user(entityManager));
        warehouseManager = managerOf(a);
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, 'Dispatch Test Co', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "dispatch-" + customerId + "@example.com", "03" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        sku = issueSku();
        producerEmail = "don-" + UUID.randomUUID().toString().substring(0, 8) + "@xuong.example.vn";
        producer = producers.register(producerCode(), new Producer.Details("Xưởng in thử", "Chị Lan", "0909123456",
                producerEmail, Producer.Channel.EMAIL, 14, null));
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures (as ProductionOrderIntegrationTest's) -------------------------------------------------------------

    protected Warehouse warehouse() {
        UUID id = Identifiers.newId();
        String code = "D" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase(Locale.ROOT);
        String bin = code + "-A01-1-A";
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                     version, created_at)
                    VALUES (?, ?, 'ACTIVE', ?, '7 Kho Street', 'M', 50, 50, 0, NOW())""", id, "Warehouse " + code, code);
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

    /** An ACTIVE warehouse manager assigned to {@code warehouse} only. */
    protected UUID managerOf(Warehouse warehouse) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT ?, ?, r.id, 0, NOW() FROM identity.app_role r WHERE r.code = 'WAREHOUSE_MANAGER'""",
                    Identifiers.newId(), user);
            jdbc.update("""
                    INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                    VALUES (?, ?, ?, 0, NOW())""", Identifiers.newId(), user, warehouse.id());
        });
        return user;
    }

    protected IssuedSku issueSku() throws Exception {
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
            String code = product.sku() + "-D" + variant.toString().substring(24, 28).toUpperCase(Locale.ROOT);
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
            return new IssuedSku(code, design.snapshotId(), design.checksum(), carton, printFileKey);
        });
    }

    protected static String producerCode() {
        return "PD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    protected UUID confirmedOrder() {
        UUID orderId = tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), CARTONS * PER_CARTON,
                    Money.vnd(2_000), null).withCartons(sku.uomConversionId(), CARTONS, PER_CARTON)
                    .withTax(BigDecimal.TEN);
            Order order = Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()),
                    customerId, Identifiers.newId(), List.of(line), Instant.now());
            order.applyTerms(PaymentTerm.PREPAID, null, null, null);
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
        SalesContracts.ContractView drafted = WithCurrentUser.call(sales, DataScope.ALL, () -> contracts.create(sales,
                orderId, new SalesContracts.Draft(null, schedule, plan)));
        SalesContracts.ContractView sent = WithCurrentUser.call(sales, DataScope.ALL, () -> contracts.send(sales,
                orderId, drafted.version()));
        byte[] scan = TestPdfs.blank();
        WithCurrentUser.call(sales, DataScope.ALL, () -> contracts.sign(sales, orderId, sent.version(),
                IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), today));
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.CONFIRMED);
        shippingWarehouses.choose(orderId, a.id(), sales.userId());
        return orderId;
    }

    /** A production order for batch 1 (12 cartons), submitted by the coordinator. */
    protected ProductionOrder pending(Producer by) {
        ProductionOrder drawn = productionOrders.drawUp(confirmedOrder(), 1, by.id(), coordinator);
        return productionOrders.submit(drawn.id(), drawn.version(), coordinator);
    }

    protected ProductionOrder approved(Producer by) {
        ProductionOrder pending = pending(by);
        return productionOrders.approve(pending.id(), pending.version(), approver);
    }

    protected ProductionOrder reread(UUID id) {
        return productionOrders.detail(id).order();
    }

    protected RecordingEmailTransport recording() {
        return (RecordingEmailTransport) transport;
    }

    protected List<Email> mailTo(String address) {
        return recording().sentTo(address);
    }

    protected Email awaitMail(String subjectPart) {
        Await.until("the producer's '" + subjectPart + "' e-mail", ASYNC, () -> mailTo(producerEmail).stream()
                .anyMatch(email -> email.subject().contains(subjectPart)));
        return mailTo(producerEmail).stream().filter(email -> email.subject().contains(subjectPart)).findFirst()
                .orElseThrow();
    }

    /**
     * The "sent" message is recorded - its transaction committed, the production order's lock released. A stop asked for
     * before that waits for the retry job (it never overtakes the order), which a test does not wait a minute for.
     */
    protected void awaitRecorded(UUID id) {
        Await.until("the sent message is recorded", ASYNC, () -> deliveries(id, "sent").stream()
                .anyMatch(row -> "SENT".equals(row.get("status"))));
    }

    protected List<Map<String, Object>> deliveries(UUID id, String kind) {
        return jdbc.queryForList("""
                SELECT status, error, terminal, template_code, recipient, external_reference
                  FROM notification.delivery_log WHERE operation_reference = ? ORDER BY created_at, id""",
                "production-order:" + id + ":" + kind);
    }

    protected long pendingPublications(UUID productionOrderId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?""",
                Long.class, "%" + productionOrderId + "%");
    }

    protected long publications(Class<?> type, UUID productionOrderId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?""",
                Long.class, type.getName(), "%" + productionOrderId + "%");
    }

    protected static Link link(String text, String routeEnd) {
        Matcher found = LINK.matcher(text);
        while (found.find()) {
            if (found.group(2).endsWith(routeEnd)) {
                return new Link(found.group(2), Long.parseLong(found.group(3)), found.group(4));
            }
        }
        throw new AssertionError("no link to …" + routeEnd + " in the e-mail");
    }

    /**
     * Every check passed and the file is handed out. On the test profile's local store that last step - signing a URL a
     * browser fetches - is the one thing that cannot happen ({@code FileTransfers} refuses a {@code file:} URL), so
     * reaching it is the success here; any refusal before it is a {@link BusinessException} and fails the test.
     */
    protected static void handedOut(java.util.function.Supplier<DownloadLink> open) {
        try {
            assertThat(open.get().url()).isNotBlank();
        } catch (StorageException localStore) {
            assertThat(localStore).hasMessageContaining("S3/MinIO");
        }
    }

    protected static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    protected byte[] bytes(String key) throws Exception {
        try (InputStream in = storage.download(key)) {
            return in.readAllBytes();
        }
    }

    protected String slipKey(UUID id) {
        return jdbc.queryForObject("SELECT storage_key FROM ordering.production_order_slip WHERE production_order_id = ?",
                String.class, id);
    }

    /** Overwrites a stored object with other bytes, as a store tampered with or a bad copy would. */
    protected void tamper(String key, FileCategory category) {
        byte[] other = ("%PDF-1.4\n% something else " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
        StoredFile replacement = storage.store(category, "other.pdf", "application/pdf", other.length,
                new ByteArrayInputStream(other));
        storage.copy(replacement.key(), key);
    }
}
