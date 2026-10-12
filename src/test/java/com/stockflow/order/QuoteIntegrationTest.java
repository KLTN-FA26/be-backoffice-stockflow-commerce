package com.stockflow.order;

import com.stockflow.catalog.internal.service.CustomerPriceService;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.contracts.SampleApproved;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.ProductionRecords;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.service.Quotes;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.stockflow.contracts.CustomerActedOnQuote;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * SCRUM-448 against a real PostgreSQL with every migration, the real design verification, real stock and
 * the real event plumbing — and the real sample flow of SCRUM-394: the approval that unlocks acceptance
 * is made by production's own services and reaches the order module as {@code SampleApproved} through its
 * asynchronous listener, exactly as in the running application. Nothing is set by SQL to stand for a
 * transition; the only SQL writes are the reference rows another module would own (users, a customer and
 * its portal account).
 *
 * <p>Security is off in this context: the caller is passed in, as the controller does. Who may call what
 * is covered over HTTP by {@code QuoteSecurityIntegrationTest}.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class QuoteIntegrationTest {

    private static final String BLANK = ConfirmedDesigns.BLANK;
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired Quotes quotes;
    @Autowired SampleRequests samples;
    @Autowired SampleProductionProgress workshop;
    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired ProductionRecords productionRecords;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired RecordedEvents.Recorder recorded;
    @Autowired CustomerPriceService prices;
    @Autowired com.stockflow.order.internal.service.OrderReleases releases;
    /** Sales notices are captured, never sent: no SMTP server is reached from a test. */
    @MockitoBean JavaMailSenderImpl mail;   // the concrete type: the actuator's mail health check needs it

    private UUID hcm;
    private CurrentUser sales;
    private UUID customerId;
    private CurrentUser portal;

    @BeforeEach
    void setUp() {
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        sales = staff(Role.SALES_STAFF);
        UUID portalUser = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = customerWithPortal(portalUser);
        portal = new CurrentUser(portalUser, "portal-" + portalUser, Set.of(Role.CUSTOMER), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        recorded.clear();
    }

    // ------------------------------------------------------------------------------------- fixtures

    private CurrentUser staff(Role role) {
        UUID id = tx.execute(s -> ReferenceRows.user(entityManager));
        return new CurrentUser(id, role.name().toLowerCase() + "-" + id, Set.of(role), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
    }

    /** An ACTIVE business customer with a portal account and a default shipping address. */
    private UUID customerWithPortal(UUID portalUser) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, phone, status, version, created_at)
                VALUES (?, ?, 'Cong ty In Ly A', ?, '0901234567', 'ACTIVE', 0, NOW())""",
                id, portalUser, "buyer-" + id + "@example.com"));
        customers.addAddress(id, sales.userId(), true, new SaveAddressCommand("SHIPPING", "Nguyen Van A",
                "0901234567", "1 Le Loi", null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null, true, null));
        return id;
    }

    private ConfirmedDesigns.Design design() throws Exception {
        return ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager, customerId);
    }

    /** Two price breaks: 2+ at 3,000 VND, 5+ at 2,500 VND; Sales proposes 3 units. */
    private Quotes.Draft draft(ConfirmedDesigns.Design design, PaymentTerm term, Instant validUntil) {
        return new Quotes.Draft(term, null, validUntil, "Prices exclude VAT",
                List.of(new Quotes.Line(BLANK, design.snapshotId(), "Logo front, 2 colours", 3,
                        List.of(new Quotes.Tier(2, new BigDecimal("3000")), new Quotes.Tier(5, new BigDecimal("2500"))))));
    }

    private Quotes.QuoteView sentQuote(ConfirmedDesigns.Design design) {
        Quotes.QuoteView created = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(design, PaymentTerm.PREPAID, Instant.now().plus(Duration.ofDays(7)))));
        return quotes.send(sales, created.id(), created.version());
    }

    /**
     * SCRUM-394 for real: draft, submit (SAMPLE production order), the workshop's completion through its
     * contract, sending, and the customer's approval — then production's SampleApproved reaches the order
     * module's listener.
     */
    private UUID approvedSample(ConfirmedDesigns.Design design) {
        SampleRequests.Sample sample = samples.create(sales, new SampleRequests.Create(customerId, null,
                new SampleRequests.Draft(design.snapshotId(), BLANK, 2, null)));
        SampleRequests.Sample submitted = samples.submit(sales, sample.id(), hcm);
        workshop.productionCompleted(submitted.productionOrder().id());
        samples.markSent(sales, sample.id());
        samples.approve(portal, sample.id());
        Await.until("the order module records the approved sample", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM ordering.approved_sample WHERE sample_request_id = ?", Integer.class,
                sample.id()) == 1);
        return sample.id();
    }

    private int ordersOf(UUID customer) {
        return jdbc.queryForObject("SELECT count(*) FROM ordering.customer_order WHERE customer_id = ?", Integer.class,
                customer);
    }

    private String emailOf(CurrentUser user) {
        return jdbc.queryForObject("SELECT email FROM identity.app_user WHERE id = ?", String.class, user.userId());
    }

    private List<CustomerActedOnQuote> noticesOf(UUID quoteId, CustomerActedOnQuote.Action action) {
        return recorded.matching(CustomerActedOnQuote.class, e -> e.quoteId().equals(quoteId) && e.action() == action);
    }

    /** The Sales notice reached the mail transport (a mock here), addressed to that Sales user. */
    private void mailedTo(String email, String quoteNumber) {
        verify(mail, timeout(15_000).atLeastOnce()).send(argThat((SimpleMailMessage m) -> m.getTo() != null
                && m.getTo().length == 1 && email.equals(m.getTo()[0]) && m.getText().contains(quoteNumber)));
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    // ------------------------------------------------------------------------------------- tests

    @Test
    @DisplayName("quote -> sample approved by the customer -> accepted at a tier -> converted into one order at the quote's price")
    void quoteSampleOrder() throws Exception {
        ConfirmedDesigns.Design design = design();
        Quotes.QuoteView created = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(design, PaymentTerm.PREPAID, Instant.now().plus(Duration.ofDays(7)))));
        assertThat(created.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(created.quoteNumber()).startsWith("QT-");
        assertThat(created.lines()).singleElement().satisfies(line -> {
            assertThat(line.moq()).isEqualTo(2);
            assertThat(line.sample().satisfied()).isFalse();
        });
        assertThatThrownBy(() -> quotes.get(portal, created.id())).as("a draft is Sales' working copy")
                .isInstanceOf(NotFoundException.class);

        Quotes.QuoteView sent = quotes.send(sales, created.id(), created.version());
        assertThat(sent.status()).isEqualTo(QuoteStatus.SENT);
        assertThat(quotes.get(portal, sent.id()).status()).isEqualTo(QuoteStatus.SENT);
        assertThat(quotes.list(portal, new Quotes.Criteria(null, null, null, 0, 20, null)).items())
                .extracting(Quotes.Row::id).containsExactly(sent.id());

        // BR-ORD-008: no approved sample of this customer for this design on this blank yet.
        assertThatThrownBy(() -> quotes.accept(portal, sent.id(), new Quotes.Acceptance(sent.version(), Map.of())))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED);
        assertThatThrownBy(() -> quotes.accept(sales, sent.id(), new Quotes.Acceptance(sent.version(), Map.of())))
                .as("only the customer answers").extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.FORBIDDEN);

        UUID sample = approvedSample(design);
        Quotes.QuoteView seen = quotes.get(portal, sent.id());
        assertThat(seen.lines().getFirst().sample()).isEqualTo(new Quotes.SampleGate(true, sample, false));

        UUID lineId = seen.lines().getFirst().id();
        assertThatThrownBy(() -> quotes.accept(portal, sent.id(), new Quotes.Acceptance(seen.version() - 1,
                Map.of(lineId, 5)))).as("accepting a version not seen").extracting(QuoteIntegrationTest::code)
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        assertThatThrownBy(() -> quotes.accept(portal, sent.id(), new Quotes.Acceptance(seen.version(),
                Map.of(lineId, 1)))).extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.QUOTE_BELOW_MOQ);

        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(seen.version(),
                Map.of(lineId, 5)));
        assertThat(accepted.status()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(accepted.respondedBy()).isEqualTo(portal.userId());
        assertThat(accepted.lines().getFirst().acceptedQuantity()).isEqualTo(5);
        assertThat(accepted.lines().getFirst().acceptedUnitPrice()).isEqualByComparingTo("2500");
        assertThat(accepted.acceptedTotal()).isEqualByComparingTo("12500");

        int before = ordersOf(customerId);
        Quotes.QuoteView converted = quotes.convert(sales, accepted.id(),
                new Quotes.Conversion(accepted.version(), null, null));
        assertThat(converted.status()).isEqualTo(QuoteStatus.CONVERTED);
        assertThat(converted.convertedOrder()).isNotNull();
        OrderSummary order = orders.findById(converted.convertedOrder().orderId()).orElseThrow();
        assertThat(order.customerId()).isEqualTo(customerId);
        assertThat(order.status()).as("prepaid: waits for the transfer").isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.paymentTerm()).isEqualTo(PaymentTerm.PREPAID);
        assertThat(order.total().amount()).isEqualByComparingTo("12500");
        assertThat(order.lines()).singleElement().satisfies(line -> {
            assertThat(line.sku()).isEqualTo(BLANK);
            assertThat(line.quantity()).isEqualTo(5);
            assertThat(line.unitPrice().amount()).as("the quote's price, not the catalogue's").isEqualByComparingTo("2500");
            assertThat(line.designSnapshotId()).isEqualTo(design.snapshotId());
            assertThat(line.designChecksum()).isEqualTo(design.checksum());
            assertThat(line.reservationIds()).as("the blanks are held").isNotEmpty();
        });
        assertThat(order.shippingAddress()).isNotNull();
        assertThat(ordersOf(customerId)).isEqualTo(before + 1);

        Quotes.QuoteView again = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null));
        assertThat(again.convertedOrder().orderId()).as("one quote, one order").isEqualTo(order.orderId());
        assertThat(ordersOf(customerId)).isEqualTo(before + 1);
        assertThat(converted.history()).extracting(Quotes.Transition::toStatus).containsExactly(QuoteStatus.DRAFT,
                QuoteStatus.SENT, QuoteStatus.ACCEPTED, QuoteStatus.CONVERTED);
        assertThat(jdbc.queryForObject("""
                SELECT offer ->> 'paymentTerm' FROM ordering.quote_status_history
                 WHERE quote_id = ? AND to_status = 'SENT'""", String.class, sent.id())).isEqualTo("PREPAID");
    }

    @Test
    @DisplayName("reorder of a negotiated order: a new draft at today's list price, nothing payable until Sales sends and the customer accepts")
    void reorderStartsANewQuoteAtTodaysPrice() throws Exception {
        // The customer's list: 3,000 from 2 units, 2,800 from 5. The deal below was 2,500 from 5.
        prices.create(customerId, BLANK, List.of(new CustomerPriceService.TierInput(2, new BigDecimal("3000")),
                new CustomerPriceService.TierInput(5, new BigDecimal("2800"))), null);
        ConfirmedDesigns.Design design = design();
        Quotes.QuoteView sent = sentQuote(design);
        approvedSample(design);
        Quotes.QuoteView seen = quotes.get(portal, sent.id());
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(seen.version(),
                Map.of(seen.lines().getFirst().id(), 5)));
        UUID orderId = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        int ordersBefore = ordersOf(customerId);

        // Another customer cannot reorder it, nor learn it exists.
        UUID strangerUser = tx.execute(s -> ReferenceRows.user(entityManager));
        customerWithPortal(strangerUser);
        CurrentUser stranger = new CurrentUser(strangerUser, "stranger", Set.of(Role.CUSTOMER), Set.of(),
                DataScope.ALL, Set.of(), Set.of());
        assertThatThrownBy(() -> quotes.reorder(stranger, new Quotes.Reorder(UUID.randomUUID(), orderId)))
                .isInstanceOf(NotFoundException.class);

        UUID requestId = UUID.randomUUID();
        Quotes.QuoteView reorder = quotes.reorder(portal, new Quotes.Reorder(requestId, orderId));
        assertThat(reorder.id()).isNotEqualTo(sent.id());
        assertThat(reorder.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(reorder.paymentTerm()).isEqualTo(PaymentTerm.PREPAID);
        assertThat(reorder.convertedOrder()).isNull();
        assertThat(reorder.lines()).singleElement().satisfies(line -> {
            assertThat(line.blankSku()).isEqualTo(BLANK);
            assertThat(line.designSnapshotId()).isEqualTo(design.snapshotId());
            assertThat(line.printSpec()).isEqualTo("Logo front, 2 colours");
            assertThat(line.quantity()).as("the quantity that was ordered").isEqualTo(5);
            assertThat(line.tiers()).as("today's list, not the 2,500 deal")
                    .extracting(t -> t.minQuantity() + "@" + t.unitPrice().stripTrailingZeros().toPlainString())
                    .containsExactly("2@3000", "5@2800");
            assertThat(line.acceptedQuantity()).isNull();
            assertThat(line.sample().satisfied()).as("the approved design needs no new sample").isTrue();
        });
        assertThat(reorder.history()).singleElement().satisfies(t ->
                assertThat(t.reason()).startsWith("Reorder of order "));
        assertThat(quotes.reorder(portal, new Quotes.Reorder(requestId, orderId)).id()).as("a retried click")
                .isEqualTo(reorder.id());
        String orderNumber = orders.findById(orderId).orElseThrow().orderNumber();
        assertThat(noticesOf(reorder.id(), CustomerActedOnQuote.Action.REORDER_REQUESTED)).as("once, not per click")
                .singleElement().satisfies(e -> {
                    assertThat(e.detail()).isEqualTo(orderNumber);
                    assertThat(e.salesUserId()).as("who negotiated the order's quote").isEqualTo(sales.userId());
                    assertThat(e.salesEmail()).isEqualTo(emailOf(sales));
                });
        mailedTo(emailOf(sales), reorder.quoteNumber());
        assertThat(ordersOf(customerId)).as("nothing payable was created").isEqualTo(ordersBefore);
        assertThatThrownBy(() -> quotes.get(portal, reorder.id())).as("Sales' draft until sent")
                .isInstanceOf(NotFoundException.class);

        // Sales negotiates again and sends; only the customer's acceptance fixes the new price.
        Quotes.QuoteView negotiated = quotes.edit(sales, reorder.id(), reorder.version(),
                new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(BLANK, design.snapshotId(), "Logo front, 2 colours", 5,
                                List.of(new Quotes.Tier(5, new BigDecimal("2700")))))));
        Quotes.QuoteView resent = quotes.send(sales, negotiated.id(), negotiated.version());
        assertThat(resent.status()).isEqualTo(QuoteStatus.SENT);
        assertThat(ordersOf(customerId)).isEqualTo(ordersBefore);
        Quotes.QuoteView reaccepted = quotes.accept(portal, resent.id(), new Quotes.Acceptance(
                quotes.get(portal, resent.id()).version(), Map.of()));
        assertThat(reaccepted.status()).as("accepted, not converted on its own").isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(reaccepted.lines().getFirst().acceptedUnitPrice()).isEqualByComparingTo("2700");
        assertThat(ordersOf(customerId)).isEqualTo(ordersBefore);

        // The first order keeps its deal, whatever the list or the new quote say.
        prices.replace(customerId, BLANK, List.of(new CustomerPriceService.TierInput(2, new BigDecimal("3500"))),
                null, 0);
        OrderSummary first = orders.findById(orderId).orElseThrow();
        assertThat(first.lines()).singleElement().satisfies(line ->
                assertThat(line.unitPrice().amount()).isEqualByComparingTo("2500"));
        assertThat(first.total().amount()).isEqualByComparingTo("12500");
    }

    @Test
    @DisplayName("below the list's first tier: Sales quotes the actual quantity at the agreed price, the customer accepts the latest revision, one order, no production before payment")
    void belowTheListTierSalesQuotesTheActualQuantity() throws Exception {
        // The customer's list starts at 10 units. The customer wants 4.
        prices.create(customerId, BLANK, List.of(new CustomerPriceService.TierInput(10, new BigDecimal("2000"))), null);
        ConfirmedDesigns.Design design = design();

        // Direct checkout does not fall back to another price: it says a quote is needed.
        assertThatThrownBy(() -> orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customerId, null, null,
                true, List.of(new PlaceOrderCommand.Line(new Sku(BLANK), 4, Money.vnd(2000), null)))))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.CUSTOMER_PRICE_BELOW_MOQ);
        // A quote line left to the list is refused rather than priced at the 1,000-unit tier.
        assertThatThrownBy(() -> quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(BLANK, design.snapshotId(), "Logo", 4, null))))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("enter the negotiated price");
                });

        // Sales enters the actual quantity and the agreed price.
        Quotes.QuoteView draft = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(BLANK, design.snapshotId(), "Logo", 4,
                                List.of(new Quotes.Tier(4, new BigDecimal("2300"))))))));
        assertThat(draft.lines()).singleElement().satisfies(line -> {
            assertThat(line.moq()).isEqualTo(4);
            assertThat(line.proposedUnitPrice()).isEqualByComparingTo("2300");
            assertThat(line.proposedLineTotal()).isEqualByComparingTo("9200");
        });
        assertThat(draft.proposedTotal()).isEqualByComparingTo("9200");
        Quotes.QuoteView first = quotes.send(sales, draft.id(), draft.version());
        approvedSample(design);

        // Only this customer answers.
        assertThatThrownBy(() -> quotes.accept(sales, first.id(), new Quotes.Acceptance(first.version(), Map.of())))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.FORBIDDEN);
        UUID strangerUser = tx.execute(s -> ReferenceRows.user(entityManager));
        customerWithPortal(strangerUser);
        CurrentUser stranger = new CurrentUser(strangerUser, "stranger", Set.of(Role.CUSTOMER), Set.of(),
                DataScope.ALL, Set.of(), Set.of());
        assertThatThrownBy(() -> quotes.accept(stranger, first.id(), new Quotes.Acceptance(first.version(), Map.of())))
                .isInstanceOf(NotFoundException.class);

        // A revision makes the customer accept again, and only what they saw last.
        long firstVersion = quotes.get(portal, first.id()).version();
        Quotes.QuoteView changed = quotes.requestChanges(portal, first.id(), firstVersion, "2,200?");
        assertThat(noticesOf(first.id(), CustomerActedOnQuote.Action.CHANGES_REQUESTED)).singleElement().satisfies(e -> {
            assertThat(e.detail()).isEqualTo("2,200?");
            assertThat(e.salesUserId()).isEqualTo(sales.userId());
            assertThat(e.salesEmail()).isEqualTo(emailOf(sales));
            assertThat(e.customerName()).isEqualTo("Cong ty In Ly A");
        });
        Quotes.QuoteView reopened = quotes.reopen(sales, first.id(), changed.version());
        Quotes.QuoteView edited = quotes.edit(sales, first.id(), reopened.version(),
                new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(BLANK, design.snapshotId(), "Logo", 4,
                                List.of(new Quotes.Tier(4, new BigDecimal("2200")))))));
        Quotes.QuoteView second = quotes.send(sales, first.id(), edited.version());
        assertThat(second.revision()).isEqualTo(2);
        assertThatThrownBy(() -> quotes.accept(portal, first.id(), new Quotes.Acceptance(firstVersion, Map.of())))
                .as("the revision-1 version").extracting(QuoteIntegrationTest::code)
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        Quotes.QuoteView accepted = quotes.accept(portal, first.id(), new Quotes.Acceptance(
                quotes.get(portal, first.id()).version(), Map.of()));
        assertThat(accepted.revision()).isEqualTo(2);
        assertThat(noticesOf(first.id(), CustomerActedOnQuote.Action.ACCEPTED)).singleElement()
                .satisfies(e -> assertThat(e.salesEmail()).isEqualTo(emailOf(sales)));
        mailedTo(emailOf(sales), accepted.quoteNumber());
        assertThat(accepted.lines().getFirst().acceptedQuantity()).isEqualTo(4);
        assertThat(accepted.lines().getFirst().acceptedUnitPrice()).isEqualByComparingTo("2200");
        assertThat(accepted.acceptedTotal()).isEqualByComparingTo("8800");

        // Converted exactly once, at the accepted quantity and price.
        int before = ordersOf(customerId);
        UUID orderId = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        assertThat(quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId()).isEqualTo(orderId);
        assertThat(ordersOf(customerId)).isEqualTo(before + 1);
        OrderSummary order = orders.findById(orderId).orElseThrow();
        assertThat(order.lines()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualTo(4);
            assertThat(line.unitPrice().amount()).isEqualByComparingTo("2200");
        });
        assertThat(order.total().amount()).isEqualByComparingTo("8800");

        // Prepaid and not paid: nothing goes to production.
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThatThrownBy(() -> releases.release(orderId, hcm, sales.userId())).isInstanceOf(RuntimeException.class);
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(recorded.matching(com.stockflow.contracts.OrderLinesReleasedForProduction.class,
                e -> e.orderId().equals(orderId))).isEmpty();
    }

    @Test
    @DisplayName("two designs on one blank pick the price break together; the stored acceptance is not recomputed")
    void designsOnOneBlankPickTheBreakTogether() throws Exception {
        ConfirmedDesigns.Design logoA = design();
        ConfirmedDesigns.Design logoB = design();
        List<Quotes.Tier> tiers = List.of(new Quotes.Tier(2, new BigDecimal("3000")),
                new Quotes.Tier(5, new BigDecimal("2500")));
        // 3 + 2 = 5 blanks: the 5-unit break for both lines, though neither reaches it alone.
        Quotes.QuoteView draft = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null, List.of(
                        new Quotes.Line(BLANK, logoA.snapshotId(), "Logo A", 3, tiers),
                        new Quotes.Line(BLANK, logoB.snapshotId(), "Logo B, 1 colour", 2, tiers)))));
        assertThat(draft.lines()).extracting(Quotes.LineView::proposedUnitPrice)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("2500"), new BigDecimal("2500"));
        assertThat(draft.proposedTotal()).isEqualByComparingTo("12500");

        Quotes.QuoteView sent = quotes.send(sales, draft.id(), draft.version());
        approvedSample(logoA);
        approvedSample(logoB);
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(
                quotes.get(portal, sent.id()).version(), Map.of()));
        assertThat(accepted.lines()).extracting(Quotes.LineView::acceptedUnitPrice)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("2500"), new BigDecimal("2500"));
        assertThat(accepted.acceptedTotal()).isEqualByComparingTo("12500");
        // What was accepted is read back as stored, not recomputed from the tiers.
        assertThat(jdbc.queryForList("SELECT accepted_unit_price FROM ordering.quote_line WHERE quote_id = ? ORDER BY line_no",
                BigDecimal.class, sent.id())).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("2500"), new BigDecimal("2500"));
    }

    @Test
    @DisplayName("R1: a reorder below today's list tier reaches Sales unpriced; nothing is sent, accepted or ordered until Sales prices it")
    void aReorderTheListCannotPriceWaitsForSales() throws Exception {
        ConfirmedDesigns.Design design = design();
        Quotes.QuoteView created = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(BLANK, design.snapshotId(), "Logo", 2,
                                List.of(new Quotes.Tier(2, new BigDecimal("3000"))))))));
        Quotes.QuoteView sent = quotes.send(sales, created.id(), created.version());
        approvedSample(design);
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(
                quotes.get(portal, sent.id()).version(), Map.of()));
        UUID orderId = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        // Today the customer's list starts at 1,000 units.
        prices.create(customerId, BLANK, List.of(new CustomerPriceService.TierInput(1000, new BigDecimal("2000"))), null);
        int ordersBefore = ordersOf(customerId);

        Quotes.QuoteView request = quotes.reorder(portal, new Quotes.Reorder(UUID.randomUUID(), orderId));
        assertThat(request.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(request.priced()).isFalse();
        assertThat(request.proposedTotal()).isNull();
        assertThat(request.lines()).singleElement().satisfies(line -> {
            assertThat(line.priced()).isFalse();
            assertThat(line.tiers()).as("neither the 3,000 deal nor the 1,000-unit tier").isEmpty();
            assertThat(line.moq()).isNull();
            assertThat(line.quantity()).isEqualTo(2);
            assertThat(line.designSnapshotId()).isEqualTo(design.snapshotId());
            assertThat(line.sample().satisfied()).isTrue();
        });
        assertThat(noticesOf(request.id(), CustomerActedOnQuote.Action.REORDER_REQUESTED)).hasSize(1);
        assertThat(ordersOf(customerId)).isEqualTo(ordersBefore);

        assertThatThrownBy(() -> quotes.send(sales, request.id(), request.version()))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.QUOTE_NOT_PRICED);
        assertThat(quotes.get(sales, request.id()).status()).isEqualTo(QuoteStatus.DRAFT);
        assertThatThrownBy(() -> quotes.accept(portal, request.id(), new Quotes.Acceptance(request.version(), Map.of())))
                .as("an unsent draft is not the customer's to see").isInstanceOf(NotFoundException.class);

        // Sales prices the actual quantity (P4), sends; only the customer's acceptance fixes it.
        Quotes.QuoteView priced = quotes.edit(sales, request.id(), request.version(),
                new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(BLANK, design.snapshotId(), "Logo", 2,
                                List.of(new Quotes.Tier(2, new BigDecimal("2900")))))));
        assertThat(priced.priced()).isTrue();
        assertThat(priced.proposedTotal()).isEqualByComparingTo("5800");
        Quotes.QuoteView resent = quotes.send(sales, priced.id(), priced.version());
        Quotes.QuoteView reaccepted = quotes.accept(portal, resent.id(), new Quotes.Acceptance(
                quotes.get(portal, resent.id()).version(), Map.of()));
        assertThat(reaccepted.status()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(reaccepted.lines().getFirst().acceptedUnitPrice()).isEqualByComparingTo("2900");
        assertThat(ordersOf(customerId)).isEqualTo(ordersBefore);
        assertThat(orders.findById(orderId).orElseThrow().lines().getFirst().unitPrice().amount())
                .isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("a customer's answer to a quote whose Sales account is disabled names nobody; nobody is guessed")
    void aDisabledSalesAccountIsNotMailed() throws Exception {
        Quotes.QuoteView sent = sentQuote(design());
        tx.executeWithoutResult(s -> jdbc.update("UPDATE identity.app_user SET status = 'DISABLED' WHERE id = ?",
                sales.userId()));
        quotes.requestChanges(portal, sent.id(), sent.version(), "Another colour?");
        assertThat(noticesOf(sent.id(), CustomerActedOnQuote.Action.CHANGES_REQUESTED)).singleElement().satisfies(e -> {
            assertThat(e.salesUserId()).isEqualTo(sales.userId());
            assertThat(e.salesEmail()).isNull();
        });
    }

    @Test
    @DisplayName("an order bought at the price list is not re-quoted: it is simply placed again")
    void aPriceListOrderIsNotRequoted() {
        OrderSummary listOrder = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customerId,
                List.of(new PlaceOrderCommand.Line(new Sku(BLANK), 2, Money.vnd(3000), null))));
        assertThatThrownBy(() -> quotes.reorder(portal, new Quotes.Reorder(UUID.randomUUID(), listOrder.orderId())))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.ORDER_NOT_QUOTED);
        assertThatThrownBy(() -> quotes.reorder(sales, new Quotes.Reorder(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("two conversions at once place one order; both get it")
    void concurrentConversion() throws Exception {
        ConfirmedDesigns.Design design = design();
        Quotes.QuoteView sent = sentQuote(design);
        approvedSample(design);
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(
                quotes.get(portal, sent.id()).version(), Map.of()));
        int before = ordersOf(customerId);

        List<Object> outcomes = race(2, () -> quotes.convert(sales, accepted.id(),
                new Quotes.Conversion(accepted.version(), null, null)));

        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(Quotes.QuoteView.class));
        assertThat(outcomes).extracting(o -> ((Quotes.QuoteView) o).convertedOrder().orderId()).containsOnly(
                ((Quotes.QuoteView) outcomes.getFirst()).convertedOrder().orderId());
        assertThat(ordersOf(customerId)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("a conversion the stock cannot cover places nothing and leaves the quote ACCEPTED")
    void conversionRollsBack() throws Exception {
        ConfirmedDesigns.Design design = design();
        Quotes.QuoteView sent = sentQuote(design);
        approvedSample(design);
        Quotes.QuoteView seen = quotes.get(portal, sent.id());
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(seen.version(),
                Map.of(seen.lines().getFirst().id(), 100_000)));
        int before = ordersOf(customerId);

        assertThatThrownBy(() -> quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null,
                null))).isInstanceOf(RuntimeException.class);

        assertThat(quotes.get(sales, accepted.id()).status()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(ordersOf(customerId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.quote_status_history WHERE quote_id = ? "
                + "AND to_status = 'CONVERTED'", Integer.class, accepted.id())).isZero();
    }

    @Test
    @DisplayName("an approval of the design counts only for the customer who gave it")
    void sampleApprovalIsTheCustomersOwn() throws Exception {
        ConfirmedDesigns.Design design = design();
        UUID sample = approvedSample(design);
        UUID stranger = tx.execute(s -> ReferenceRows.customer(entityManager));

        assertThat(productionRecords.approvedSampleOf(customerId, design.snapshotId(), BLANK)).contains(sample);
        assertThat(productionRecords.approvedSampleOf(stranger, design.snapshotId(), BLANK)).isEmpty();
        assertThat(productionRecords.approvedSampleOf(customerId, design.snapshotId(), "TABLE-OAK-160")).isEmpty();
        assertThat(recorded.matching(SampleApproved.class, e -> e.sampleRequestId().equals(sample))).hasSize(1);
    }

    @Test
    @DisplayName("a sent quote expires at its validity: read as EXPIRED at once, refused, then written by the sweep")
    void expiry() throws Exception {
        ConfirmedDesigns.Design design = design();
        Quotes.QuoteView created = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(design, PaymentTerm.PREPAID, Instant.now().plusSeconds(3))));
        Quotes.QuoteView sent = quotes.send(sales, created.id(), created.version());
        Await.until("the validity passes", Duration.ofSeconds(10), () -> Instant.now().isAfter(sent.validUntil()));

        assertThat(quotes.get(portal, sent.id()).status()).isEqualTo(QuoteStatus.EXPIRED);
        assertThatThrownBy(() -> quotes.accept(portal, sent.id(), new Quotes.Acceptance(sent.version(), Map.of())))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.QUOTE_EXPIRED);
        assertThatThrownBy(() -> quotes.requestChanges(portal, sent.id(), sent.version(), "cheaper"))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.QUOTE_EXPIRED);

        assertThat(quotes.expireDue(100)).isPositive();
        assertThat(jdbc.queryForObject("SELECT status FROM ordering.quote WHERE id = ?", String.class, sent.id()))
                .isEqualTo("EXPIRED");
        assertThat(quotes.expireDue(100)).as("nothing left of this one").isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.quote_status_history WHERE quote_id = ? "
                + "AND to_status = 'EXPIRED'", Integer.class, sent.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("changes requested keep the feedback; Sales revises and sends revision 2, both offers kept")
    void changesAndRevision() throws Exception {
        ConfirmedDesigns.Design design = design();
        Quotes.QuoteView sent = sentQuote(design);
        Quotes.QuoteView changed = quotes.requestChanges(portal, sent.id(), sent.version(), "2,400 from 5 units?");
        assertThat(changed.status()).isEqualTo(QuoteStatus.CHANGES_REQUESTED);
        assertThat(changed.customerFeedback()).isEqualTo("2,400 from 5 units?");
        assertThatThrownBy(() -> quotes.cancel(sales, sent.id(), changed.version(), "x"))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);

        Quotes.QuoteView reopened = quotes.reopen(sales, sent.id(), changed.version());
        assertThat(reopened.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(reopened.revision()).isEqualTo(2);
        assertThatThrownBy(() -> quotes.get(portal, sent.id())).as("being revised: not shown")
                .isInstanceOf(NotFoundException.class);
        Quotes.QuoteView edited = quotes.edit(sales, sent.id(), reopened.version(), new Quotes.Draft(PaymentTerm.PREPAID,
                null, Instant.now().plus(Duration.ofDays(7)), null, List.of(new Quotes.Line(BLANK, design.snapshotId(),
                null, 5, List.of(new Quotes.Tier(5, new BigDecimal("2400")))))));
        Quotes.QuoteView resent = quotes.send(sales, sent.id(), edited.version());

        assertThat(resent.lines()).singleElement().satisfies(l -> assertThat(l.tiers().getFirst().unitPrice())
                .isEqualByComparingTo("2400"));
        assertThat(resent.history()).filteredOn(t -> t.toStatus() == QuoteStatus.CHANGES_REQUESTED).singleElement()
                .satisfies(t -> assertThat(t.reason()).isEqualTo("2,400 from 5 units?"));
        assertThat(jdbc.queryForList("""
                SELECT offer -> 'lines' -> 0 -> 'tiers' -> 0 ->> 'unitPrice' FROM ordering.quote_status_history
                 WHERE quote_id = ? AND to_status = 'SENT' ORDER BY revision""", String.class, sent.id()))
                .containsExactly("3000", "2400");
    }

    @Test
    @DisplayName("idempotent creation; only ACTIVE customers, allowed terms and the customer's own designs")
    void creationRules() throws Exception {
        ConfirmedDesigns.Design design = design();
        UUID request = UUID.randomUUID();
        Quotes.Draft draft = draft(design, PaymentTerm.PREPAID, Instant.now().plus(Duration.ofDays(7)));
        Quotes.QuoteView first = quotes.create(sales, new Quotes.Create(request, customerId, draft));
        assertThat(quotes.create(sales, new Quotes.Create(request, customerId, draft)).id()).isEqualTo(first.id());
        UUID other = tx.execute(s -> ReferenceRows.customer(entityManager));
        assertThatThrownBy(() -> quotes.create(sales, new Quotes.Create(request, other, draft)))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);

        assertThatThrownBy(() -> quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(design, PaymentTerm.DEPOSIT, Instant.now().plus(Duration.ofDays(7))))))
                .as("no commercial terms set: prepaid only").extracting(QuoteIntegrationTest::code)
                .isEqualTo(ErrorCode.PAYMENT_TERM_NOT_ALLOWED);
        assertThatThrownBy(() -> quotes.create(sales, new Quotes.Create(UUID.randomUUID(), other, draft)))
                .as("another customer's design").extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> quotes.create(portal, new Quotes.Create(UUID.randomUUID(), customerId, draft)))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.FORBIDDEN);

        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET status = 'BLOCKED' WHERE id = ?",
                customerId));
        assertThatThrownBy(() -> quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId, draft)))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.CUSTOMER_NOT_ACTIVE);
        assertThatThrownBy(() -> quotes.send(sales, first.id(), first.version()))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.CUSTOMER_NOT_ACTIVE);
        assertThat(quotes.cancel(sales, first.id(), first.version(), "customer blocked").status())
                .isEqualTo(QuoteStatus.CANCELLED);
    }

    /** Starts {@code callers} copies of {@code work} at the same moment; each result or exception, in order. */
    private static List<Object> race(int callers, Callable<?> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return work.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
