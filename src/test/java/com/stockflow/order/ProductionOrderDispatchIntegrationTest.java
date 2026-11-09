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
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.contracts.ProductionOrderSent;
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
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.service.ProducerService;
import com.stockflow.order.internal.service.ProductionOrderDispatch;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4A-3 against the real database (kltn-docs 17 §4.3 steps 7, 10, 12-13, BR-23; 00 §6; QD-7): approving a production
 * order sends it - the slip drawn and kept, the producer e-mailed once with time-limited links that check the files on
 * every use, the receiving warehouse's managers told in-app - and a sent production order that is cancelled or closed
 * tells its producer to stop, on the same channel.
 *
 * <p>The fixtures are {@link ProductionOrderIntegrationTest}'s - a confirmed order of a signed contract, its shipping
 * warehouse chosen, a customer SKU on a design the customer really confirmed - with the same property source and mocks,
 * so both share one context. E-mail goes to the test profile's recording transport; each test's producer has an address
 * of its own, so what it was sent is exact whatever the other tests sent.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ProductionOrderDispatchIntegrationTest {

    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(600_000);
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final int PER_CARTON = 50;
    private static final int CARTONS = 20;
    private static final Pattern LINK = Pattern.compile("(http://localhost:8080(/api/v1/production-order-files/\\S+?))"
            + "\\?expires=(\\d+)&signature=([A-Za-z0-9_-]+)");

    private record Warehouse(UUID id, String code, String bin) {
    }

    private record IssuedSku(String sku, UUID snapshotId, String checksum, UUID uomConversionId, String printFileKey) {
    }

    /** One link out of an e-mail: its route, expiry and signature. */
    private record Link(String path, long expires, String signature) {
    }

    private static Warehouse a;

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

    private CurrentUser sales;
    private UUID coordinator;
    private UUID approver;
    private UUID warehouseManager;
    private UUID customerId;
    private IssuedSku sku;
    private String producerEmail;
    private Producer producer;
    private LocalDate today;

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

    private Warehouse warehouse() {
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
    private UUID managerOf(Warehouse warehouse) {
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

    private static String producerCode() {
        return "PD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private UUID confirmedOrder() {
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
    private ProductionOrder pending(Producer by) {
        ProductionOrder drawn = productionOrders.drawUp(confirmedOrder(), 1, by.id(), coordinator);
        return productionOrders.submit(drawn.id(), drawn.version(), coordinator);
    }

    private ProductionOrder approved(Producer by) {
        ProductionOrder pending = pending(by);
        return productionOrders.approve(pending.id(), pending.version(), approver);
    }

    private ProductionOrder reread(UUID id) {
        return productionOrders.detail(id).order();
    }

    private RecordingEmailTransport recording() {
        return (RecordingEmailTransport) transport;
    }

    private List<Email> mailTo(String address) {
        return recording().sentTo(address);
    }

    private Email awaitMail(String subjectPart) {
        Await.until("the producer's '" + subjectPart + "' e-mail", ASYNC, () -> mailTo(producerEmail).stream()
                .anyMatch(email -> email.subject().contains(subjectPart)));
        return mailTo(producerEmail).stream().filter(email -> email.subject().contains(subjectPart)).findFirst()
                .orElseThrow();
    }

    /**
     * The "sent" message is recorded - its transaction committed, the production order's lock released. A stop asked for
     * before that waits for the retry job (it never overtakes the order), which a test does not wait a minute for.
     */
    private void awaitRecorded(UUID id) {
        Await.until("the sent message is recorded", ASYNC, () -> deliveries(id, "sent").stream()
                .anyMatch(row -> "SENT".equals(row.get("status"))));
    }

    private List<Map<String, Object>> deliveries(UUID id, String kind) {
        return jdbc.queryForList("""
                SELECT status, error, terminal, template_code, recipient, external_reference
                  FROM notification.delivery_log WHERE operation_reference = ? ORDER BY created_at, id""",
                "production-order:" + id + ":" + kind);
    }

    private long pendingPublications(UUID productionOrderId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?""",
                Long.class, "%" + productionOrderId + "%");
    }

    private long publications(Class<?> type, UUID productionOrderId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?""",
                Long.class, type.getName(), "%" + productionOrderId + "%");
    }

    private static Link link(String text, String routeEnd) {
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
    private static void handedOut(java.util.function.Supplier<DownloadLink> open) {
        try {
            assertThat(open.get().url()).isNotBlank();
        } catch (StorageException localStore) {
            assertThat(localStore).hasMessageContaining("S3/MinIO");
        }
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    private byte[] bytes(String key) throws Exception {
        try (InputStream in = storage.download(key)) {
            return in.readAllBytes();
        }
    }

    private String slipKey(UUID id) {
        return jdbc.queryForObject("SELECT storage_key FROM ordering.production_order_slip WHERE production_order_id = ?",
                String.class, id);
    }

    /** Overwrites a stored object with other bytes, as a store tampered with or a bad copy would. */
    private void tamper(String key, FileCategory category) {
        byte[] other = ("%PDF-1.4\n% something else " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
        StoredFile replacement = storage.store(category, "other.pdf", "application/pdf", other.length,
                new ByteArrayInputStream(other));
        storage.copy(replacement.key(), key);
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("17 §4.3 step 7: approved, so sent - the slip kept, one e-mail to the producer, the warehouse told")
    void approvalSendsIt() throws Exception {
        ProductionOrder sent = approved(producer);

        assertThat(sent.status()).isEqualTo(ProductionOrderStatus.SENT);
        Map<String, Object> slip = jdbc.queryForMap(
                "SELECT * FROM ordering.production_order_slip WHERE production_order_id = ?", sent.id());
        assertThat((String) slip.get("storage_key")).startsWith("documents/");
        byte[] pdf = bytes((String) slip.get("storage_key"));
        assertThat(slip.get("sha256")).as("the digest is the stored object's").isEqualTo(Checksums.sha256Hex(pdf));
        assertThat(slip.get("size_bytes")).isEqualTo((long) pdf.length);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("PHIẾU ĐƠN SẢN XUẤT", sent.code(), sku.sku(),
                    "Dispatch Test Co", "Xưởng in thử", "Chị Lan - 0909123456 - " + producerEmail,
                    "Warehouse " + a.code(), "7 Kho Street", "12 x 50 cái/thùng = 600 cái", "600 x 400 x 350 mm",
                    sku.snapshotId().toString(), sku.checksum());
        }

        Email email = awaitMail(sent.code());
        assertThat(email.to()).containsExactly(producerEmail);
        assertThat(email.idempotencyKey()).isEqualTo("production-order:" + sent.id() + ":sent");
        assertThat(email.text()).contains("/api/v1/production-order-files/" + sent.id() + "/slip?expires=",
                "/print-files/" + sku.snapshotId() + "?expires=");
        Await.until("the delivery is recorded", ASYNC, () -> !deliveries(sent.id(), "sent").isEmpty());
        assertThat(deliveries(sent.id(), "sent")).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", "SENT").containsEntry("recipient", producerEmail)
                    .containsEntry("template_code", "production_order_sent")
                    .containsEntry("external_reference", "production-order:" + sent.id() + ":sent");
        });

        Await.until("the warehouse manager is told", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? AND dedupe_key = ?""",
                Long.class, warehouseManager, "production-order-sent:" + sent.id()) == 1);
        Map<String, Object> notice = jdbc.queryForMap("""
                SELECT severity, title, body, link FROM notification.user_notification
                 WHERE recipient_id = ? AND dedupe_key = ?""", warehouseManager, "production-order-sent:" + sent.id());
        assertThat(notice).containsEntry("severity", "INFO").containsEntry("link", "/production/orders/" + sent.id());
        assertThat((String) notice.get("title")).contains(sent.code(), "Warehouse " + a.code());
        assertThat((String) notice.get("body")).as("cartons, volume (12 x 0.6 x 0.4 x 0.35 m), need-by date")
                .contains("12 thùng", "1.01 m³", sent.needByDate().format(java.time.format.DateTimeFormatter
                        .ofPattern("dd/MM/yyyy")));

        // The event delivered again (a redelivery, a restart's republication): nothing is sent twice.
        Await.until("the first deliveries complete", ASYNC, () -> pendingPublications(sent.id()) == 0);
        tx.executeWithoutResult(s -> events.publishEvent(new ProductionOrderSent(UUID.randomUUID(), sent.id(),
                sent.code(), a.id(), sent.needByDate(), 12, BigDecimal.ONE, sent.sentAt())));
        Await.until("the redelivery completes", ASYNC, () -> pendingPublications(sent.id()) == 0);
        assertThat(mailTo(producerEmail)).as("each approval sends one e-mail").hasSize(1);
        assertThat(deliveries(sent.id(), "sent")).hasSize(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? AND dedupe_key = ?""",
                Long.class, warehouseManager, "production-order-sent:" + sent.id())).isEqualTo(1);

        ProductionOrderDispatch.DispatchView view = dispatch.dispatch(sent.id(), 0, 20);
        assertThat(view.channel()).isEqualTo("EMAIL");
        assertThat(view.slip().sha256()).isEqualTo(slip.get("sha256"));
        assertThat(view.messages().items()).singleElement().satisfies(attempt -> {
            assertThat(attempt.templateCode()).isEqualTo("production_order_sent");
            assertThat(attempt.status()).isEqualTo("SENT");
        });
        handedOut(() -> dispatch.slipLink(sent.id()));
    }

    @Test
    @DisplayName("17 §4.3 step 7: the e-mailed links work until their time, and check the file on every use")
    void linksCheckEverything() {
        ProductionOrder sent = approved(producer);
        Email email = awaitMail(sent.code());
        Link slip = link(email.text(), "/slip");
        Link print = link(email.text(), "/print-files/" + sku.snapshotId());
        assertThat(Instant.ofEpochSecond(slip.expires())).as("valid for the configured 30 days")
                .isAfter(Instant.now().plus(Duration.ofDays(29)))
                .isBefore(Instant.now().plus(Duration.ofDays(31)));

        handedOut(() -> dispatch.openSlip(sent.id(), slip.expires(), slip.signature()));
        handedOut(() -> dispatch.openPrintFile(sent.id(), sku.snapshotId(), print.expires(), print.signature()));

        assertThatThrownBy(() -> dispatch.openSlip(sent.id(), slip.expires(), slip.signature() + "x"))
                .as("an altered signature").extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_LINK_INVALID);
        assertThatThrownBy(() -> dispatch.openSlip(sent.id(), slip.expires() + 86_400, slip.signature()))
                .as("a later expiry written in").extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_LINK_INVALID);
        assertThatThrownBy(() -> dispatch.openPrintFile(sent.id(), sku.snapshotId(), slip.expires(),
                slip.signature())).as("the slip's link used for a print file")
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_LINK_INVALID);
        UUID otherDesign = UUID.randomUUID();
        String foreign = links.url(ProductionOrderDispatch.printFilePath(sent.id(), otherDesign),
                Instant.now().plusSeconds(600));
        Link foreignLink = link(foreign, "/print-files/" + otherDesign);
        assertThatThrownBy(() -> dispatch.openPrintFile(sent.id(), otherDesign, foreignLink.expires(),
                foreignLink.signature())).as("a signed link to a design that is not the production order's")
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_LINK_INVALID);
        Link expired = link(links.url(ProductionOrderDispatch.slipPath(sent.id()), Instant.now().minusSeconds(1)),
                "/slip");
        assertThatThrownBy(() -> dispatch.openSlip(sent.id(), expired.expires(), expired.signature()))
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_LINK_EXPIRED);
        assertThat(ErrorCode.PRODUCTION_ORDER_LINK_EXPIRED.httpStatus()).isEqualTo(410);
        assertThat(ErrorCode.PRODUCTION_ORDER_LINK_INVALID.httpStatus()).isEqualTo(403);

        // The files checked on every use: a print file or a slip that changed in the store is not handed out.
        tamper(sku.printFileKey(), FileCategory.DESIGN_RENDER);
        assertThatThrownBy(() -> dispatch.openPrintFile(sent.id(), sku.snapshotId(), print.expires(),
                print.signature())).extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRINT_FILE_MISMATCH);
        tamper(slipKey(sent.id()), FileCategory.DOCUMENT);
        assertThatThrownBy(() -> dispatch.openSlip(sent.id(), slip.expires(), slip.signature()))
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_SLIP_MISMATCH);
        assertThatThrownBy(() -> dispatch.slipLink(sent.id())).as("staff are refused the changed slip too")
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_SLIP_MISMATCH);
    }

    @Test
    @DisplayName("17 §4.3 step 13: cancelled after it was sent - the producer is told to stop, and its links die")
    void cancelledAfterSent() {
        ProductionOrder sent = approved(producer);
        Email first = awaitMail(sent.code());
        Link slip = link(first.text(), "/slip");
        awaitRecorded(sent.id());

        ProductionOrder cancelled = productionOrders.cancel(sent.id(), reread(sent.id()).version(),
                "Khách sửa file thiết kế", coordinator);

        Email stop = awaitMail("ĐÃ HUỶ");
        assertThat(stop.subject()).contains(cancelled.code());
        assertThat(stop.text()).contains("Lý do: Khách sửa file thiết kế").doesNotContain("signature=");
        assertThat(stop.idempotencyKey()).isEqualTo("production-order:" + sent.id() + ":stopped");
        assertThat(mailTo(producerEmail)).hasSize(2);
        assertThatThrownBy(() -> dispatch.openSlip(sent.id(), slip.expires(), slip.signature()))
                .as("a stopped production order's files are no longer handed out")
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_LINK_EXPIRED);
        Await.until("the deliveries complete", ASYNC, () -> pendingPublications(sent.id()) == 0);
        assertThat(dispatch.dispatch(sent.id(), 0, 20).messages().items())
                .extracting(attempt -> attempt.templateCode() + ":" + attempt.status())
                .containsExactlyInAnyOrder("production_order_sent:SENT", "production_order_stopped:SENT");
    }

    @Test
    @DisplayName("17 §4.3 step 13: a production order cancelled before it was sent tells no producer anything")
    void cancelledBeforeSent() {
        ProductionOrder pending = pending(producer);

        productionOrders.cancel(pending.id(), pending.version(), "Hàng đã có", coordinator);

        assertThat(publications(com.stockflow.contracts.ProductionOrderStopped.class, pending.id())).isZero();
        assertThat(publications(ProductionOrderSent.class, pending.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.production_order_slip WHERE production_order_id = ?",
                Long.class, pending.id())).isZero();
    }

    @Test
    @DisplayName("17 §4.3 step 12: closed after mass printing started - the producer is told to deliver nothing more")
    void closedAfterMassPrinting() {
        ProductionOrder sent = approved(producer);
        awaitMail(sent.code());
        awaitRecorded(sent.id());
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE ordering.production_order SET status = 'ACCEPTED', version = version + 1 WHERE id = ?",
                    sent.id());
            jdbc.update("""
                    UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW(),
                           version = version + 1 WHERE id = ?""", sent.id());
        });

        productionOrders.close(sent.id(), reread(sent.id()).version(), "Xưởng dừng hẳn", coordinator);

        Email stop = awaitMail("ĐÃ ĐÓNG");
        assertThat(stop.text()).contains("đã được đóng", "Lý do: Xưởng dừng hẳn");
    }

    @Test
    @DisplayName("B31: the customer approved the sample - the producer is told to mass print, with fresh links")
    void sampleApproved() {
        ProductionOrder sent = approved(producer);
        awaitMail(sent.code());
        assertThat(sent.sampleApprovalRequired()).as("BR-24: the SKU was never produced").isTrue();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> dispatch.sampleApproved(reread(sent.id()),
                Instant.now()))).as("not in production yet")
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE ordering.production_order SET status = 'ACCEPTED', version = version + 1 WHERE id = ?",
                    sent.id());
            jdbc.update("UPDATE ordering.production_order SET status = 'SAMPLE_PENDING', version = version + 1 "
                    + "WHERE id = ?", sent.id());
            jdbc.update("""
                    UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW(),
                           version = version + 1 WHERE id = ?""", sent.id());
        });

        tx.executeWithoutResult(s -> dispatch.sampleApproved(reread(sent.id()), Instant.now()));

        Email go = awaitMail("mẫu in đã duyệt");
        assertThat(go.text()).contains("bắt đầu in hàng loạt", "/print-files/" + sku.snapshotId() + "?expires=");
        Link print = link(go.text(), "/print-files/" + sku.snapshotId());
        handedOut(() -> dispatch.openPrintFile(sent.id(), sku.snapshotId(), print.expires(), print.signature()));
    }

    @Test
    @DisplayName("a producer the coordinator hands the slip: no e-mail, the message recorded as skipped")
    void handedTheSlip() {
        Producer handed = producers.register(producerCode(), new Producer.Details("Xưởng nhận phiếu tay", null, null,
                null, Producer.Channel.PDF, 14, null));
        ProductionOrder sent = approved(handed);

        Await.until("the message is settled", ASYNC, () -> !deliveries(sent.id(), "sent").isEmpty());
        assertThat(deliveries(sent.id(), "sent")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "SKIPPED").containsEntry("error", "PRODUCER_CHANNEL_PDF")
                .containsEntry("terminal", true));
        assertThat(slipKey(sent.id())).as("the slip is kept for the coordinator to hand over").isNotNull();
        assertThat(dispatch.dispatch(sent.id(), 0, 20).channel()).isEqualTo("PDF");
        Await.until("the warehouse manager is told all the same", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? AND dedupe_key = ?""",
                Long.class, warehouseManager, "production-order-sent:" + sent.id()) == 1);
    }

    @Test
    @DisplayName("a slip drawn for an approval that does not commit is deleted from the store")
    void unadoptedSlipIsDeleted() throws Exception {
        ProductionOrder pending = pending(producer);
        String key;
        try (ProductionOrderDispatch.PreparedSlip slip = dispatch.prepareSlip(reread(pending.id()), approver,
                Instant.now())) {
            key = slip.storageKey();
            assertThat(storage.exists(key)).isTrue();
            try (PDDocument doc = Loader.loadPDF(bytes(key))) {
                assertThat(new PDFTextStripper().getText(doc)).contains(pending.code());
            }
        }
        assertThat(storage.exists(key)).as("never adopted by a committed approval").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.production_order_slip WHERE production_order_id = ?",
                Long.class, pending.id())).isZero();
    }

    @Test
    @DisplayName("a stale approval is refused before any slip is drawn, and sends nothing")
    void failedApprovalSendsNothing() {
        ProductionOrder pending = pending(producer);

        assertThatThrownBy(() -> productionOrders.approve(pending.id(), pending.version() - 1, approver))
                .extracting(ProductionOrderDispatchIntegrationTest::code).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        assertThat(reread(pending.id()).status()).isEqualTo(ProductionOrderStatus.PENDING_APPROVAL);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.production_order_slip WHERE production_order_id = ?",
                Long.class, pending.id())).isZero();
        assertThat(publications(ProductionOrderSent.class, pending.id())).isZero();
        assertThatThrownBy(() -> dispatch.slipLink(pending.id()))
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_SLIP_NOT_FOUND);
    }
}
