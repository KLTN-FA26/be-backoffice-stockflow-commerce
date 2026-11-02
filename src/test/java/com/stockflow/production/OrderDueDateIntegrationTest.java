package com.stockflow.production;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.production.internal.service.ProductionDashboard;
import com.stockflow.production.internal.service.ProductionOrders;
import com.stockflow.production.internal.service.SampleProductionProgress;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-502 (owner decision PROD-DUE, 2026-11-02) through the real modules: the date the customer asks for is set on
 * the quote, copied onto the order's print line when the quote is converted, and becomes the due date of the
 * production order when the order is released - where the workshop queue and the overdue dashboard read it. Until
 * then an ORDER production order was created with no due date at all. Nothing is set by SQL to stand for a step; the
 * payment arrives as its contract event, as in {@code SampleToPackingIntegrationTest}.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class OrderDueDateIntegrationTest {

    private static final String BLANK = ConfirmedDesigns.BLANK;
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired SampleRequests samples;
    @Autowired SampleProductionProgress workshop;
    @Autowired Quotes quotes;
    @Autowired OrderService orders;
    @Autowired OrderReleases releases;
    @Autowired CustomerService customers;
    @Autowired ProductionOrders productionOrders;
    @Autowired ProductionDashboard dashboard;
    @Autowired FileStorage storage;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired RecordedEvents.Recorder recorded;
    @Autowired com.stockflow.common.security.RoleAuthorizationLookup grants;

    private UUID hcm;
    private UUID customerId;
    private CurrentUser sales;
    private CurrentUser portal;
    private CurrentUser manager;

    @BeforeEach
    void setUp() {
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        ConfirmedDesigns.stockBlanks(jdbc, tx, 40);
        UUID salesId = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesId, "sales-" + salesId, Set.of(Role.SALES_STAFF), seeded(Role.SALES_STAFF),
                DataScope.ALL, Set.of(), Set.of());
        UUID portalUser = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, phone, status, version, created_at)
                VALUES (?, ?, 'Cong ty In Ly D', ?, '0901234569', 'ACTIVE', 0, NOW())""",
                customerId, portalUser, "d-" + customerId + "@example.com"));
        customers.addAddress(customerId, salesId, true, new SaveAddressCommand("SHIPPING", "Nguyen Van D",
                "0901234569", "4 Le Loi", null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null, true, null));
        portal = new CurrentUser(portalUser, "portal-" + portalUser, Set.of(Role.CUSTOMER), seeded(Role.CUSTOMER),
                DataScope.ALL, Set.of(), Set.of());
        UUID managerId = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = new CurrentUser(managerId, "manager-" + managerId, Set.of(Role.WAREHOUSE_MANAGER),
                seeded(Role.WAREHOUSE_MANAGER), DataScope.WAREHOUSE, Set.of("HCM"), Set.of(hcm));
        recorded.clear();
    }

    private java.util.Set<com.stockflow.common.security.PermissionCode> seeded(Role role) {
        return grants.resolve(java.util.List.of(role.authority())).permissions();
    }

    /** A quote of 3 printed cups, sent; {@code asked} is the customer's requested delivery date, or null. */
    private Quotes.QuoteView sentQuote(ConfirmedDesigns.Design design, LocalDate asked) {
        Quotes.QuoteView draft = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(BLANK, design.snapshotId(), "Logo front", 3,
                                List.of(new Quotes.Tier(2, new BigDecimal("3000"))))), asked)));
        return quotes.send(sales, draft.id(), draft.version());
    }

    private void approveSample(ConfirmedDesigns.Design design) {
        SampleRequests.Sample sample = samples.create(sales, new SampleRequests.Create(customerId, null,
                new SampleRequests.Draft(design.snapshotId(), BLANK, 2, null)));
        SampleRequests.Sample submitted = samples.submit(sales, sample.id(), hcm);
        workshop.productionCompleted(submitted.productionOrder().id());
        samples.markSent(sales, sample.id());
        samples.approve(portal, sample.id());
        Await.until("the order module records the approved sample", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM ordering.approved_sample WHERE sample_request_id = ?", Integer.class,
                sample.id()) == 1);
    }

    /** Accepted by the customer, converted by Sales, paid and released from HCM; returns the order. */
    private OrderSummary releasedOrder(Quotes.QuoteView sent) {
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(sent.version(), Map.of()));
        UUID orderId = quotes.convert(sales, sent.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        OrderSummary placed = orders.findById(orderId).orElseThrow();
        tx.executeWithoutResult(s -> publisher.publishEvent(new PaymentCaptured(orderId, UUID.randomUUID(),
                placed.total().amount(), "VND", PaymentMethod.BANK_TRANSFER)));
        Await.until("paid", ASYNC, () -> orders.findById(orderId).orElseThrow().status() == OrderStatus.CONFIRMED);
        UUID coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        assertThat(releases.release(orderId, hcm, coordinator).status()).isEqualTo(OrderStatus.IN_PRODUCTION);
        return placed;
    }

    private UUID productionOrderOf(UUID lineId) {
        Await.until("the release becomes the line's production order", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM production.production_order WHERE sales_order_line_id = ?", Integer.class,
                lineId) == 1);
        return jdbc.queryForObject("SELECT id FROM production.production_order WHERE sales_order_line_id = ?",
                UUID.class, lineId);
    }

    @Test
    @DisplayName("SCRUM-502: quote date -> order print line -> production order due date, read by the queue; a quote without one gives none")
    void theRequestedDateBecomesTheProductionOrdersDueDate() throws Exception {
        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager, customerId);
        approveSample(design);
        LocalDate asked = LocalDate.now(BUSINESS_ZONE).plusDays(21);

        Quotes.QuoteView sent = sentQuote(design, asked);
        assertThat(sent.requestedDeliveryDate()).isEqualTo(asked);
        assertThat(quotes.get(portal, sent.id()).requestedDeliveryDate()).as("the customer sees what was asked").isEqualTo(asked);

        OrderSummary order = releasedOrder(sent);
        // The order's print line carries the date from the moment the order exists, and nothing can change it after.
        assertThat(order.lines()).singleElement().satisfies(line -> assertThat(line.requestedDeliveryDate()).isEqualTo(asked));
        UUID lineId = order.lines().getFirst().lineId();
        assertThat(jdbc.queryForObject("SELECT requested_delivery_date FROM ordering.order_line WHERE id = ?",
                LocalDate.class, lineId)).isEqualTo(asked);

        UUID lsx = productionOrderOf(lineId);
        assertThat(jdbc.queryForObject("SELECT due_date FROM production.production_order WHERE id = ?", LocalDate.class,
                lsx)).isEqualTo(asked);
        assertThat(productionOrders.get(manager, lsx).summary().dueDate()).isEqualTo(asked);
        // What the workshop queue filters and sorts on: due within the window, not before it.
        assertThat(productionOrders.list(manager, new ProductionOrders.Criteria(null, null, null, asked, asked, null,
                order.orderId(), null, 0, 20, null)).items()).extracting(ProductionOrders.Row::id).containsExactly(lsx);
        assertThat(productionOrders.list(manager, new ProductionOrders.Criteria(null, null, null, asked.plusDays(1), null,
                null, order.orderId(), null, 0, 20, null)).items()).isEmpty();
        // Not due yet: the overdue list (due before today) leaves it out.
        assertThat(dashboard.overdue(manager, 0, 200).items()).extracting(ProductionOrders.Row::id).doesNotContain(lsx);

        // A reorder starts a new draft for Sales: the date asked for the old order is not carried over.
        Quotes.QuoteView reorder = quotes.reorder(portal, new Quotes.Reorder(UUID.randomUUID(), order.orderId()));
        assertThat(reorder.requestedDeliveryDate()).isNull();

        // A quote with no date places a line with none, and its production order has none, as before.
        OrderSummary undated = releasedOrder(sentQuote(design, null));
        assertThat(undated.lines().getFirst().requestedDeliveryDate()).isNull();
        UUID undatedLsx = productionOrderOf(undated.lines().getFirst().lineId());
        assertThat(jdbc.queryForObject("SELECT due_date FROM production.production_order WHERE id = ?", LocalDate.class,
                undatedLsx)).isNull();
    }
}
