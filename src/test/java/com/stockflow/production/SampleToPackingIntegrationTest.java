package com.stockflow.production;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.contracts.ProductionCompleted;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.SampleStatus;
import com.stockflow.production.internal.service.MaterialIssues;
import com.stockflow.production.internal.service.PrintRuns;
import com.stockflow.production.internal.service.ProductionOrders;
import com.stockflow.production.internal.service.ProductionWorkflow;
import com.stockflow.production.internal.service.QcReferences;
import com.stockflow.production.internal.service.SampleRequests;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole print path against a real PostgreSQL, every step through the modules' own services: a quote, its
 * sample printed in the workshop (prepress, issue, press, QC) and approved, the quote accepted and converted, the
 * order paid and released, its production order printed, inspected and handed to packing — the goods still held
 * for the order and on its line, the order ready to fulfil. Only the payment arrives as its contract event
 * ({@code PaymentCaptured}): how money is captured is the payment module's, not this flow's.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class SampleToPackingIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    com.stockflow.common.security.RoleAuthorizationLookup grants;

    /** The role's permissions as seeded: services check permissions, not role names. */
    private java.util.Set<com.stockflow.common.security.PermissionCode> seeded(Role role) {
        return grants.resolve(java.util.List.of(role.authority())).permissions();
    }

    private static final String BLANK = ConfirmedDesigns.BLANK;
    private static final String PRINT_SHOP = "HCM-PRD01";
    private static final String PACKING = "HCM-PACK01";
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired SampleRequests samples;
    @Autowired Quotes quotes;
    @Autowired OrderService orders;
    @Autowired OrderReleases releases;
    @Autowired CustomerService customers;
    @Autowired ProductionWorkflow workflow;
    @Autowired MaterialIssues issues;
    @Autowired QcReferences qcReferences;
    @Autowired PrintRuns runs;
    @Autowired ProductionOrders productionOrders;
    @Autowired InventoryService inventory;
    /** Local storage in tests hands out no browser link (QC's preview); the URL itself is the storage module's. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean FileStorage storage;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired RecordedEvents.Recorder recorded;

    private UUID hcm;
    private UUID customerId;
    private CurrentUser sales;
    private CurrentUser portal;
    private CurrentUser printer;
    private CurrentUser storekeeper;
    private CurrentUser inspector;
    private CurrentUser manager;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.doAnswer(call -> "http://objects.test/" + call.getArgument(0)).when(storage)
                .presignedDownloadUrl(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        ConfirmedDesigns.stockBlanks(jdbc, tx, 40);
        UUID salesId = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesId, "sales-" + salesId, Set.of(Role.SALES_STAFF), seeded(Role.SALES_STAFF), DataScope.ALL,
                Set.of(), Set.of());
        UUID portalUser = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, phone, status, version, created_at)
                VALUES (?, ?, 'Cong ty In Ly C', ?, '0901234568', 'ACTIVE', 0, NOW())""",
                customerId, portalUser, "c-" + customerId + "@example.com"));
        customers.addAddress(customerId, salesId, true, new SaveAddressCommand("SHIPPING", "Nguyen Van C",
                "0901234568", "3 Le Loi", null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null, true, null));
        portal = new CurrentUser(portalUser, "portal-" + portalUser, Set.of(Role.CUSTOMER), seeded(Role.CUSTOMER), DataScope.ALL,
                Set.of(), Set.of());
        printer = staff(Role.PRODUCTION_STAFF);
        storekeeper = staff(Role.WAREHOUSE_STAFF);
        inspector = staff(Role.QC_STAFF);
        manager = staff(Role.WAREHOUSE_MANAGER);
        recorded.clear();
    }

    private CurrentUser staff(Role role) {
        UUID id = tx.execute(s -> ReferenceRows.user(entityManager));
        return new CurrentUser(id, role.name() + "-" + id, Set.of(role), seeded(role), DataScope.WAREHOUSE, Set.of("HCM"),
                Set.of(hcm));
    }

    /** Prepress (unless done), every blank issued to the print shop, the press, and a QC round passing everything. */
    private ProductionOrders.Detail printed(UUID productionOrderId, int quantity) {
        if (status(productionOrderId) == ProductionOrder.Status.PENDING_PREPRESS) {
            workflow.prepress(printer, productionOrderId, true, null);
        }
        for (int scans = 0; scans < 10 && status(productionOrderId) == ProductionOrder.Status.READY; scans++) {
            issues.issue(storekeeper, productionOrderId, new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP, null));
        }
        assertThat(status(productionOrderId)).isEqualTo(ProductionOrder.Status.MATERIAL_ISSUED);
        runs.startPrinting(printer, productionOrderId);
        runs.recordOutput(printer, productionOrderId, quantity, List.of());
        return runs.recordQc(inspector, productionOrderId, quantity, 0, null, PACKING, sampleOf(productionOrderId));
    }

    private ProductionOrder.Status status(UUID productionOrderId) {
        return productionOrders.get(manager, productionOrderId).summary().status();
    }

    @Test
    @DisplayName("quote -> sample printed and approved -> quote accepted and converted -> paid, released -> printed, QC'd -> held for the order in packing, ready to fulfil")
    void sampleQuoteOrderPrintQcPacking() throws Exception {
        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmedWithPreview(storage, jdbc, tx, entityManager,
                customerId);
        Quotes.QuoteView draft = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(BLANK, design.snapshotId(), "Logo front", 3,
                                List.of(new Quotes.Tier(2, new BigDecimal("3000"))))))));
        Quotes.QuoteView quote = quotes.send(sales, draft.id(), draft.version());

        // The sample, printed in the workshop like any job: its units leave stock as SAMPLE.
        SampleRequests.Sample sample = samples.create(sales, new SampleRequests.Create(customerId, quote.id(),
                new SampleRequests.Draft(design.snapshotId(), BLANK, 2, null)));
        UUID sampleLsx = samples.submit(sales, sample.id(), hcm).productionOrder().id();
        assertThat(printed(sampleLsx, 2).summary().status()).isEqualTo(ProductionOrder.Status.COMPLETED);
        assertThat(samples.get(sales, sample.id()).status()).isEqualTo(SampleStatus.READY);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory.stock_adjustment
                 WHERE reason_code = 'SAMPLE' AND note LIKE ?""", Integer.class,
                productionOrders.get(manager, sampleLsx).summary().orderNumber() + "%")).isEqualTo(-2);
        samples.markSent(sales, sample.id());
        samples.approve(portal, sample.id());
        Await.until("the order module records the approval", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM ordering.approved_sample WHERE sample_request_id = ?", Integer.class,
                sample.id()) == 1);

        // The quote becomes the order; it is paid and released from HCM.
        Quotes.QuoteView accepted = quotes.accept(portal, quote.id(), new Quotes.Acceptance(quote.version(), Map.of()));
        UUID orderId = quotes.convert(sales, quote.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        OrderSummary placed = orders.findById(orderId).orElseThrow();
        tx.executeWithoutResult(s -> publisher.publishEvent(new PaymentCaptured(orderId, UUID.randomUUID(),
                placed.total().amount(), "VND", PaymentMethod.BANK_TRANSFER)));
        Await.until("paid", ASYNC, () -> orders.findById(orderId).orElseThrow().status() == OrderStatus.CONFIRMED);
        UUID coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        assertThat(releases.release(orderId, hcm, coordinator).status()).isEqualTo(OrderStatus.IN_PRODUCTION);
        UUID lineId = placed.lines().getFirst().lineId();
        Await.until("the release becomes the line's production order", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM production.production_order WHERE sales_order_line_id = ?", Integer.class,
                lineId) == 1);
        UUID lsx = jdbc.queryForObject("SELECT id FROM production.production_order WHERE sales_order_line_id = ?",
                UUID.class, lineId);
        assertThat(jdbc.queryForObject("SELECT approved_sample_id FROM production.production_order WHERE id = ?",
                UUID.class, lsx)).isEqualTo(sample.id());
        int atp = inventory.availableToPromise(new Sku(BLANK));

        // SCRUM-418/419: QC sees the round the customer approved and the picture they approved, through the order.
        QcReferences.ApprovedSample reference = qcReferences.approvedSample(inspector, lsx);
        assertThat(reference.sampleRequestId()).isEqualTo(sample.id());
        assertThat(reference.sampleRequestNumber()).isEqualTo(sample.requestNumber());
        assertThat(reference.round()).isEqualTo(1);
        assertThat(reference.approvedAt()).isNotNull();
        assertThat(reference.designSnapshotId()).isEqualTo(design.snapshotId());
        assertThat(reference.sampleProductionOrderId()).as("the LSX that printed the physical sample").isEqualTo(sampleLsx);
        assertThat(reference.preview()).isNotNull();
        assertThat(reference.preview().contentType()).isEqualTo("image/png");
        assertThat(reference.preview().url()).isNotBlank();
        assertThat(reference.preview().expiresAt()).isAfter(Instant.now());
        assertThatThrownBy(() -> qcReferences.approvedSample(inspector, sampleLsx))
                .as("a SAMPLE is checked against its print file; it has no approved sample")
                .isInstanceOf(NotFoundException.class);
        assertThat(qcReferences.packingLocations(inspector, lsx)).containsExactly(PACKING);

        // SCRUM-409/410: the clerk sees what is still to bring and where, and a wrong scan moves nothing.
        workflow.prepress(printer, lsx, true, null);
        MaterialIssues.Pending pending = issues.pending(storekeeper, lsx);
        assertThat(pending.required()).isEqualTo(3);
        assertThat(pending.issued()).isZero();
        assertThat(pending.outstanding()).isEqualTo(3);
        assertThat(pending.holds()).isNotEmpty().allSatisfy(hold -> {
            assertThat(hold.sku()).isEqualTo(BLANK);
            assertThat(hold.locationCode()).startsWith("HCM-");
        });
        assertThat(pending.holds().stream().mapToInt(MaterialIssues.PendingHold::quantity).sum()).isEqualTo(3);
        MaterialIssues.PendingHold first = pending.holds().getFirst();
        assertThatThrownBy(() -> issues.issue(storekeeper, lsx, new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP,
                first.reservationId(), PACKING, BLANK)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ISSUE_LOCATION_MISMATCH));
        assertThatThrownBy(() -> issues.issue(storekeeper, lsx, new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP,
                first.reservationId(), first.locationCode(), "NOT-THE-BLANK")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ISSUE_ITEM_MISMATCH));
        assertThat(issues.pending(storekeeper, lsx).outstanding()).as("refused scans moved nothing").isEqualTo(3);
        // The right location (any case) and the SKU (any case) issue the hold.
        issues.issue(storekeeper, lsx, new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP, first.reservationId(),
                first.locationCode().toLowerCase(java.util.Locale.ROOT), BLANK.toLowerCase(java.util.Locale.ROOT)));
        assertThat(issues.pending(storekeeper, lsx).holds()).extracting(MaterialIssues.PendingHold::reservationId)
                .doesNotContain(first.reservationId());

        ProductionOrders.Detail done = printed(lsx, 3);

        assertThat(done.summary().status()).isEqualTo(ProductionOrder.Status.COMPLETED);
        // SCRUM-412 (decided 14/10/2026): the hand-over is automatic and the area is on the order and in the list.
        assertThat(done.summary().packingLocation()).isEqualTo(PACKING);
        assertThat(productionOrders.list(manager, new ProductionOrders.Criteria(null,
                List.of(ProductionOrder.Status.COMPLETED), null, null, null, null, orderId, null, 0, 20, null))
                .items()).singleElement().satisfies(row -> {
                    assertThat(row.id()).isEqualTo(lsx);
                    assertThat(row.packingLocation()).isEqualTo(PACKING);
                });
        assertThat(productionOrders.get(manager, sampleLsx).summary().packingLocation())
                .as("a SAMPLE's units are written off, not handed to packing").isNull();
        // G4-Q2 (2026-10-21): handed to packing and deducted there as the order completed - one CONSUMPTION row per
        // hold naming the production order; the holds stay the order line's, consumed.
        List<UUID> packed = jdbc.queryForList("""
                SELECT r.id FROM inventory.stock_reservation r JOIN inventory.stock_item s ON s.id = r.stock_item_id
                 WHERE r.status = 'CONSUMED' AND r.order_id = ? AND s.location_code = ?""", UUID.class, orderId, PACKING);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM inventory.stock_reservation WHERE id = ANY(?)",
                Integer.class, (Object) packed.toArray(UUID[]::new))).as("3 printed cups, the order's, consumed in packing")
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory.stock_movement WHERE movement_type = 'CONSUMPTION'
                   AND reference_type = 'PRODUCTION_ORDER' AND reference_id = ? AND from_location_code = ?""",
                Integer.class, lsx, PACKING)).isEqualTo(3);
        assertThat(orders.findById(orderId).orElseThrow().lines().getFirst().reservationIds()).containsAll(packed);
        assertThat(inventory.availableToPromise(new Sku(BLANK))).as("nobody else could take them").isEqualTo(atp);
        assertThat(recorded.matching(ProductionCompleted.class, e -> e.productionOrderId().equals(lsx)))
                .singleElement().isEqualTo(new ProductionCompleted(orderId, lineId, lsx, 3));
        Await.until("the order is ready to fulfil", ASYNC,
                () -> orders.findById(orderId).orElseThrow().status() == OrderStatus.READY_TO_FULFILL);
    }

    /** The approved sample a QC round of this order names (BR-PRD-05, SCRUM-487); null when it has none. */
    private UUID sampleOf(UUID productionOrderId) {
        return jdbc.queryForObject("SELECT approved_sample_id FROM production.production_order WHERE id = ?",
                UUID.class, productionOrderId);
    }
}
