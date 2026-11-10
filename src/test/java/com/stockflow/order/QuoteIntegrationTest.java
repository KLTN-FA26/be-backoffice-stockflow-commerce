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
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.support.Await;
import com.stockflow.support.CustomerSkuRows;
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
import java.time.LocalDate;
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
 * SCRUM-448 against a real PostgreSQL with every migration, real stock and the real event plumbing - since 4B-2 on
 * cartons of the customer's own SKU (kltn-docs 14 BR-04, BR-07, BR-08). Acceptance waits for no print sample: samples
 * are approved on the Production Order, outside the quote (kltn-docs 17). Nothing is set by SQL to stand for a
 * transition; the only SQL writes are the reference rows another module would own (users, a customer and its portal
 * account, the customer's SKU and its stock).
 *
 * <p>Security is off in this context: the caller is passed in, as the controller does. Who may call what
 * is covered over HTTP by {@code QuoteSecurityIntegrationTest}.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
// The Sales notices are checked on the mocked JavaMailSender: SMTP, not the test profile's in-memory recorder. Customer
// order mail (HTML, a MimeMessage the mock cannot make) is not what this test is about.
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false", "stockflow.notification.email.provider=smtp",
        "stockflow.notification.email.customer.enabled=false"})
class QuoteIntegrationTest {

    /** Pieces in one carton of the customer's SKU. */
    private static final int PER_CARTON = 10;
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired Quotes quotes;
    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired RecordedEvents.Recorder recorded;
    @Autowired CustomerPriceService prices;
    @Autowired com.stockflow.order.internal.service.OrderReleases releases;
    @Autowired com.stockflow.order.internal.service.SalesContracts contracts;
    /** Sales notices are captured, never sent: no SMTP server is reached from a test. */
    @MockitoBean JavaMailSenderImpl mail;   // the concrete type: the actuator's mail health check needs it

    private UUID hcm;
    private CurrentUser sales;
    private UUID customerId;
    private CurrentUser portal;
    /** The customer's own SKU, sold in cartons of {@link #PER_CARTON}. */
    private String sku;

    @BeforeEach
    void setUp() {
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        sales = staff(Role.SALES_STAFF);
        UUID portalUser = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = customerWithPortal(portalUser);
        // The orders converted here hold the customer's own SKU: this test's own, with stock in HCM.
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, PER_CARTON).sku();
        CustomerSkuRows.stock(jdbc, tx, sku, 1000);
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

    /** Two price breaks: 2+ cartons at 3,000 VND a piece, 5+ at 2,500; Sales proposes 3 cartons. */
    private Quotes.Draft draft(Instant validUntil) {
        return new Quotes.Draft(validUntil, "Prices exclude VAT",
                List.of(new Quotes.Line(sku, 3,
                        List.of(new Quotes.Tier(2, new BigDecimal("3000")), new Quotes.Tier(5, new BigDecimal("2500"))))));
    }

    /**
     * The converted order's contract drawn up - one batch three weeks out, the customer's default plan - sent and signed
     * today with a one-page scan (4B-5): the order is then the customer's to see.
     */
    private void signContract(UUID orderId) {
        tx.executeWithoutResult(status -> jdbc.update("INSERT INTO customer.customer_legal_profile (id, customer_id, "
                + "registered_address, representative_name, representative_title, version, created_at) VALUES (?, ?, "
                + "'1 Le Loi', 'Tran Van A', 'Director', 0, NOW()) ON CONFLICT (customer_id) DO NOTHING",
                Identifiers.newId(), customerId));
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        Integer cartons = jdbc.queryForObject("SELECT cartons FROM ordering.order_line WHERE order_id = ?",
                Integer.class, orderId);
        var drafted = com.stockflow.support.WithCurrentUser.call(sales, DataScope.ALL, () -> contracts.create(sales,
                orderId, new com.stockflow.order.internal.service.SalesContracts.Draft(null, List.of(
                        new com.stockflow.order.internal.service.SalesContracts.Batch(today.plusDays(21),
                                today.plusDays(21), List.of(new com.stockflow.order.internal.service.SalesContracts
                                .BatchLine(line, cartons)))), null)));
        var sent = com.stockflow.support.WithCurrentUser.call(sales, DataScope.ALL,
                () -> contracts.send(sales, orderId, drafted.version()));
        byte[] scan;
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var bytes = new java.io.ByteArrayOutputStream();
            document.save(bytes);
            scan = bytes.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        com.stockflow.support.WithCurrentUser.call(sales, DataScope.ALL, () -> contracts.sign(sales, orderId,
                sent.version(), com.stockflow.common.storage.IncomingFiles.sent(List.of(
                        new com.stockflow.common.storage.FileUpload("signed.pdf", "application/pdf", scan.length,
                                new java.io.ByteArrayInputStream(scan)))), today));
    }

    private Quotes.QuoteView sentQuote() {
        Quotes.QuoteView created = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(Instant.now().plus(Duration.ofDays(7)))));
        return quotes.send(sales, created.id(), created.version());
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
    @DisplayName("quote -> accepted at a tier -> converted into one order at the quote's price")
    void quoteToOrder() throws Exception {
        Quotes.QuoteView created = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(Instant.now().plus(Duration.ofDays(7)))));
        assertThat(created.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(created.quoteNumber()).startsWith("QT-");
        assertThat(created.lines()).singleElement().satisfies(line -> assertThat(line.moq()).isEqualTo(2));
        assertThatThrownBy(() -> quotes.get(portal, created.id())).as("a draft is Sales' working copy")
                .isInstanceOf(NotFoundException.class);

        Quotes.QuoteView sent = quotes.send(sales, created.id(), created.version());
        assertThat(sent.status()).isEqualTo(QuoteStatus.SENT);
        assertThat(quotes.get(portal, sent.id()).status()).isEqualTo(QuoteStatus.SENT);
        assertThat(quotes.list(portal, new Quotes.Criteria(null, null, null, 0, 20, null)).items())
                .extracting(Quotes.Row::id).containsExactly(sent.id());

        assertThatThrownBy(() -> quotes.accept(sales, sent.id(), new Quotes.Acceptance(sent.version(), Map.of())))
                .as("only the customer answers").extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.FORBIDDEN);

        Quotes.QuoteView seen = quotes.get(portal, sent.id());

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
        assertThat(accepted.lines().getFirst().acceptedCartons()).isEqualTo(5);
        assertThat(accepted.lines().getFirst().acceptedUnits()).isEqualTo(5 * PER_CARTON);
        assertThat(accepted.lines().getFirst().acceptedUnitPrice()).isEqualByComparingTo("2500");
        assertThat(accepted.acceptedTotal()).as("5 cartons x 10 pieces x 2,500").isEqualByComparingTo("125000");

        int before = ordersOf(customerId);
        Quotes.QuoteView converted = quotes.convert(sales, accepted.id(),
                new Quotes.Conversion(accepted.version(), null, null));
        assertThat(converted.status()).isEqualTo(QuoteStatus.CONVERTED);
        assertThat(converted.convertedOrder()).isNotNull();
        OrderSummary order = orders.findById(converted.convertedOrder().orderId()).orElseThrow();
        assertThat(order.customerId()).isEqualTo(customerId);
        // 4B-5 (14 §4.2 step 7): a DRAFT order - its contract takes it on; since 4B-10 it has no terms of its own at all.
        assertThat(order.status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.total().amount()).isEqualByComparingTo("125000");
        assertThat(order.lines()).singleElement().satisfies(line -> {
            assertThat(line.sku()).isEqualTo(sku);
            assertThat(line.quantity()).as("in pieces").isEqualTo(5 * PER_CARTON);
            assertThat(line.cartons()).as("ordered by the carton (14 BR-08)").isEqualTo(5);
            assertThat(line.unitsPerCarton()).isEqualTo(PER_CARTON);
            assertThat(line.unitPrice().amount()).as("the quote's price, not the catalogue's").isEqualByComparingTo("2500");
            assertThat(line.designSnapshotId()).as("the SKU is the design").isNull();
            assertThat(line.reservationIds()).as("nothing held before the contract is signed (4B-5)").isEmpty();
        });
        assertThat(jdbc.queryForObject("SELECT cartons || 'x' || units_per_carton FROM ordering.order_line "
                + "WHERE order_id = ?", String.class, order.orderId())).isEqualTo("5x10");
        assertThat(order.shippingAddress()).isNotNull();
        assertThat(ordersOf(customerId)).isEqualTo(before + 1);

        assertThat(quotes.get(portal, accepted.id()).convertedOrder())
                .as("17 §5.2: the customer meets the order once its contract is sent").isNull();
        assertThat(quotes.get(sales, accepted.id()).convertedOrder().orderId()).isEqualTo(order.orderId());
        Quotes.QuoteView again = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null));
        assertThat(again.convertedOrder().orderId()).as("one quote, one order").isEqualTo(order.orderId());
        assertThat(ordersOf(customerId)).isEqualTo(before + 1);
        assertThat(converted.history()).extracting(Quotes.Transition::toStatus).containsExactly(QuoteStatus.DRAFT,
                QuoteStatus.SENT, QuoteStatus.ACCEPTED, QuoteStatus.CONVERTED);
        assertThat(jdbc.queryForObject("""
                SELECT (offer -> 'paymentTerm' IS NOT NULL)::text || ':' || (offer -> 'validUntil' IS NOT NULL)::text
                  FROM ordering.quote_status_history WHERE quote_id = ? AND to_status = 'SENT'""", String.class,
                sent.id())).as("4B-10: the offer as sent names no payment term - the contract's plan will")
                .isEqualTo("false:true");
    }

    @Test
    @DisplayName("reorder of a negotiated order: a new draft at today's list price, nothing payable until Sales sends and the customer accepts")
    void reorderStartsANewQuoteAtTodaysPrice() throws Exception {
        // The customer's own price: 3,000 a piece from 2 cartons, 2,800 from 5. The deal below was 2,500 from 5.
        prices.create(customerId, sku, List.of(new CustomerPriceService.TierInput(2, new BigDecimal("3000")),
                new CustomerPriceService.TierInput(5, new BigDecimal("2800"))), null);
        Quotes.QuoteView sent = sentQuote();
        Quotes.QuoteView seen = quotes.get(portal, sent.id());
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(seen.version(),
                Map.of(seen.lines().getFirst().id(), 5)));
        UUID orderId = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        // A past order: its contract signed, so the customer sees it and may reorder it (4B-5, 17 §5.2).
        signContract(orderId);
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
        assertThat(reorder.convertedOrder()).isNull();
        assertThat(reorder.lines()).singleElement().satisfies(line -> {
            assertThat(line.sku()).isEqualTo(sku);
            assertThat(line.cartons()).as("the cartons that were ordered").isEqualTo(5);
            assertThat(line.tiers()).as("today's own price, not the 2,500 deal")
                    .extracting(t -> t.minQuantity() + "@" + t.unitPrice().stripTrailingZeros().toPlainString())
                    .containsExactly("2@3000", "5@2800");
            assertThat(line.acceptedCartons()).isNull();
        });
        assertThat(reorder.history()).singleElement().satisfies(t ->
                assertThat(t.reason()).startsWith("Reorder of order "));
        assertThat(quotes.reorder(portal, new Quotes.Reorder(requestId, orderId)).id()).as("a retried click")
                .isEqualTo(reorder.id());
        String orderNumber = orders.findById(orderId).orElseThrow().orderNumber();
        assertThat(noticesOf(reorder.id(), CustomerActedOnQuote.Action.REORDER_REQUESTED)).as("once, not per click")
                .singleElement().satisfies(e -> {
                    assertThat(e.sourceOrderNumber()).isEqualTo(orderNumber);
                    assertThat(e.salesUserId()).as("who negotiated the order's quote").isEqualTo(sales.userId());
                });
        mailedTo(emailOf(sales), reorder.quoteNumber());
        assertThat(ordersOf(customerId)).as("nothing payable was created").isEqualTo(ordersBefore);
        assertThatThrownBy(() -> quotes.get(portal, reorder.id())).as("Sales' draft until sent")
                .isInstanceOf(NotFoundException.class);

        // Sales negotiates again and sends; only the customer's acceptance fixes the new price.
        Quotes.QuoteView negotiated = quotes.edit(sales, reorder.id(), reorder.version(),
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(sku, 5,
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
        prices.replace(customerId, sku, List.of(new CustomerPriceService.TierInput(2, new BigDecimal("3500"))),
                null, 0);
        OrderSummary first = orders.findById(orderId).orElseThrow();
        assertThat(first.lines()).singleElement().satisfies(line ->
                assertThat(line.unitPrice().amount()).isEqualByComparingTo("2500"));
        assertThat(first.total().amount()).isEqualByComparingTo("125000");
    }

    @Test
    @DisplayName("below the list's first tier: Sales quotes the actual quantity at the agreed price, the customer accepts the latest revision, one order, no production before payment")
    void belowTheListTierSalesQuotesTheActualQuantity() throws Exception {
        // The customer's own price starts at 10 cartons. The customer wants 4.
        prices.create(customerId, sku, List.of(new CustomerPriceService.TierInput(10, new BigDecimal("2000"))), null);

        // The customer's own SKU is quoted, not bought at checkout (14 BR-01): nothing to fall back to.
        assertThatThrownBy(() -> orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customerId, null, null,
                true, List.of(new PlaceOrderCommand.Line(new Sku(sku), 40, Money.vnd(2000), null)))))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.PRODUCT_NOT_PURCHASABLE);
        // A quote line left to the own price is refused rather than priced at the 10-carton tier.
        assertThatThrownBy(() -> quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(sku, 4, null))))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("enter the negotiated price");
                });

        // Sales enters the actual cartons and the agreed price.
        Quotes.QuoteView draft = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(sku, 4,
                                List.of(new Quotes.Tier(4, new BigDecimal("2300"))))))));
        assertThat(draft.lines()).singleElement().satisfies(line -> {
            assertThat(line.moq()).isEqualTo(4);
            assertThat(line.proposedUnitPrice()).isEqualByComparingTo("2300");
            assertThat(line.proposedLineTotal()).as("4 cartons x 10 x 2,300").isEqualByComparingTo("92000");
        });
        assertThat(draft.proposedTotal()).isEqualByComparingTo("92000");
        Quotes.QuoteView first = quotes.send(sales, draft.id(), draft.version());

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
            assertThat(e.sourceOrderNumber()).as("B-4: what the customer typed stays on the quote").isNull();
            assertThat(e.toString()).doesNotContain("2,200?");
            assertThat(e.salesUserId()).isEqualTo(sales.userId());
            // Review of #109, item 10: ids only in the stored event - the address and the name are looked up on
            // delivery (the mail below still reaches the right person).
            assertThat(e.toString()).doesNotContain("Cong ty In Ly A", emailOf(sales));
        });
        Quotes.QuoteView reopened = quotes.reopen(sales, first.id(), changed.version());
        Quotes.QuoteView edited = quotes.edit(sales, first.id(), reopened.version(),
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(sku, 4,
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
                .satisfies(e -> assertThat(e.salesUserId()).isEqualTo(sales.userId()));
        mailedTo(emailOf(sales), accepted.quoteNumber());
        assertThat(accepted.lines().getFirst().acceptedCartons()).isEqualTo(4);
        assertThat(accepted.lines().getFirst().acceptedUnitPrice()).isEqualByComparingTo("2200");
        assertThat(accepted.acceptedTotal()).isEqualByComparingTo("88000");

        // Converted exactly once, at the accepted cartons and price.
        int before = ordersOf(customerId);
        UUID orderId = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        assertThat(quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId()).isEqualTo(orderId);
        assertThat(ordersOf(customerId)).isEqualTo(before + 1);
        OrderSummary order = orders.findById(orderId).orElseThrow();
        assertThat(order.lines()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualTo(4 * PER_CARTON);
            assertThat(line.cartons()).isEqualTo(4);
            assertThat(line.unitPrice().amount()).isEqualByComparingTo("2200");
        });
        assertThat(order.total().amount()).isEqualByComparingTo("88000");

        // A DRAFT order - its contract not even drawn up: it is not released.
        assertThat(order.status()).isEqualTo(OrderStatus.DRAFT);
        assertThatThrownBy(() -> releases.release(orderId, hcm, sales.userId())).isInstanceOf(RuntimeException.class);
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.DRAFT);
    }

    @Test
    @DisplayName("each SKU picks its break by its own cartons (14 BR-07), a SKU is on a quote once (BR-22); the stored "
            + "acceptance is not recomputed")
    void eachSkuPicksItsBreakByItsOwnCartons() {
        String second = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 20).sku();
        List<Quotes.Tier> tiers = List.of(new Quotes.Tier(2, new BigDecimal("3000")),
                new Quotes.Tier(5, new BigDecimal("2500")));
        assertThatThrownBy(() -> quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null, List.of(
                        new Quotes.Line(sku, 3, tiers), new Quotes.Line(sku, 2, tiers))))))
                .as("one line per SKU").extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        // 3 cartons of one SKU and 5 of another: 3,000 and 2,500 - the two are not added together.
        Quotes.QuoteView draft = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null, List.of(
                        new Quotes.Line(sku, 3, tiers), new Quotes.Line(second, 5, tiers)))));
        assertThat(draft.lines()).extracting(Quotes.LineView::proposedUnitPrice)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("3000"), new BigDecimal("2500"));
        assertThat(draft.lines()).extracting(Quotes.LineView::unitsPerCarton).containsExactly(PER_CARTON, 20);
        // 3 x 10 x 3,000 + 5 x 20 x 2,500
        assertThat(draft.proposedTotal()).isEqualByComparingTo("340000");

        Quotes.QuoteView sent = quotes.send(sales, draft.id(), draft.version());
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(
                quotes.get(portal, sent.id()).version(), Map.of()));
        assertThat(accepted.lines()).extracting(Quotes.LineView::acceptedUnitPrice)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("3000"), new BigDecimal("2500"));
        assertThat(accepted.acceptedTotal()).isEqualByComparingTo("340000");
        // What was accepted is read back as stored, not recomputed from the tiers.
        assertThat(jdbc.queryForList("SELECT accepted_cartons || '@' || TRUNC(accepted_unit_price) FROM "
                + "ordering.quote_line WHERE quote_id = ? ORDER BY line_no", String.class, sent.id()))
                .containsExactly("3@3000", "5@2500");
    }

    @Test
    @DisplayName("R1: a reorder below today's list tier reaches Sales unpriced; nothing is sent, accepted or ordered until Sales prices it")
    void aReorderTheListCannotPriceWaitsForSales() throws Exception {
        Quotes.QuoteView created = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(sku, 2,
                                List.of(new Quotes.Tier(2, new BigDecimal("3000"))))))));
        Quotes.QuoteView sent = quotes.send(sales, created.id(), created.version());
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(
                quotes.get(portal, sent.id()).version(), Map.of()));
        UUID orderId = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        // A past order: its contract signed, so the customer sees it and may reorder it (4B-5, 17 §5.2).
        signContract(orderId);
        // Today the customer's own price starts at 1,000 cartons.
        prices.create(customerId, sku, List.of(new CustomerPriceService.TierInput(1000, new BigDecimal("2000"))), null);
        int ordersBefore = ordersOf(customerId);

        Quotes.QuoteView request = quotes.reorder(portal, new Quotes.Reorder(UUID.randomUUID(), orderId));
        assertThat(request.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(request.priced()).isFalse();
        assertThat(request.proposedTotal()).isNull();
        assertThat(request.lines()).singleElement().satisfies(line -> {
            assertThat(line.priced()).isFalse();
            assertThat(line.tiers()).as("neither the 3,000 deal nor the 1,000-carton tier").isEmpty();
            assertThat(line.moq()).isNull();
            assertThat(line.cartons()).isEqualTo(2);
        });
        assertThat(noticesOf(request.id(), CustomerActedOnQuote.Action.REORDER_REQUESTED)).hasSize(1);
        assertThat(ordersOf(customerId)).isEqualTo(ordersBefore);

        assertThatThrownBy(() -> quotes.send(sales, request.id(), request.version()))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.QUOTE_NOT_PRICED);
        assertThat(quotes.get(sales, request.id()).status()).isEqualTo(QuoteStatus.DRAFT);
        assertThatThrownBy(() -> quotes.accept(portal, request.id(), new Quotes.Acceptance(request.version(), Map.of())))
                .as("an unsent draft is not the customer's to see").isInstanceOf(NotFoundException.class);

        // Sales prices the actual cartons (P4), sends; only the customer's acceptance fixes it.
        Quotes.QuoteView priced = quotes.edit(sales, request.id(), request.version(),
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(sku, 2,
                                List.of(new Quotes.Tier(2, new BigDecimal("2900")))))));
        assertThat(priced.priced()).isTrue();
        assertThat(priced.proposedTotal()).isEqualByComparingTo("58000");
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
    @DisplayName("N1: the Sales account of a quote is disabled - the customer's answer goes to the active Sales team to take over")
    void aDisabledSalesAccountHandsTheCustomerToTheActiveSalesTeam() throws Exception {
        Quotes.QuoteView sent = sentQuote();
        CurrentUser colleague = staff(Role.SALES_STAFF);
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE identity.app_user SET status = 'DISABLED' WHERE id = ?", sales.userId());
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT ?, ?, id, 0, NOW() FROM identity.app_role WHERE code = 'SALES_STAFF'""",
                    Identifiers.newId(), colleague.userId());
        });
        quotes.requestChanges(portal, sent.id(), sent.version(), "Another colour?");
        assertThat(noticesOf(sent.id(), CustomerActedOnQuote.Action.CHANGES_REQUESTED)).singleElement().satisfies(e -> {
            assertThat(e.salesUserId()).as("still names who handled it; the listener finds the account inactive")
                    .isEqualTo(sales.userId());
        });
        String disabled = emailOf(sales);
        verify(mail, timeout(15_000).atLeastOnce()).send(argThat((SimpleMailMessage m) -> m.getTo() != null
                && java.util.Arrays.asList(m.getTo()).contains(emailOf(colleague))
                && !java.util.Arrays.asList(m.getTo()).contains(disabled)
                && m.getSubject().startsWith("[Cần tiếp nhận]") && m.getText().contains(sent.quoteNumber())));
        // Telling the team changes no access: the colleague answers nothing for the customer.
        assertThatThrownBy(() -> quotes.accept(colleague, sent.id(), new Quotes.Acceptance(sent.version(), Map.of())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("an order bought at the price list is not re-quoted: it is simply placed again")
    void aPriceListOrderIsNotRequoted() {
        OrderSummary listOrder = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customerId,
                List.of(new PlaceOrderCommand.Line(new Sku(sku), 2, Money.vnd(3000), null))));
        assertThatThrownBy(() -> quotes.reorder(portal, new Quotes.Reorder(UUID.randomUUID(), listOrder.orderId())))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.ORDER_NOT_QUOTED);
        assertThatThrownBy(() -> quotes.reorder(sales, new Quotes.Reorder(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("two conversions at once place one order; both get it")
    void concurrentConversion() throws Exception {
        Quotes.QuoteView sent = sentQuote();
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
    @DisplayName("4B-5: a conversion holds no stock - more than is in stock is drafted; production covers the rest")
    void conversionHoldsNoStock() throws Exception {
        Quotes.QuoteView sent = sentQuote();
        Quotes.QuoteView seen = quotes.get(portal, sent.id());
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(seen.version(),
                Map.of(seen.lines().getFirst().id(), 100_000)));
        int before = ordersOf(customerId);

        // 14 §4.2 step 8: a shortage does not stop the order; stock is held at the shipping warehouse once it is
        // confirmed (4B-7), and a production order covers what is missing (4A).
        Quotes.QuoteView converted = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(),
                null, null));
        assertThat(converted.status()).isEqualTo(QuoteStatus.CONVERTED);
        assertThat(ordersOf(customerId)).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_reservation WHERE order_id = ?",
                Integer.class, converted.convertedOrder().orderId())).isZero();
    }

    @Test
    @DisplayName("a sent quote expires at its validity: read as EXPIRED at once, refused, then written by the sweep")
    void expiry() throws Exception {
        Quotes.QuoteView created = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(Instant.now().plusSeconds(3))));
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
        Quotes.QuoteView sent = sentQuote();
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
        Quotes.QuoteView edited = quotes.edit(sales, sent.id(), reopened.version(), new Quotes.Draft(
                Instant.now().plus(Duration.ofDays(7)), null, List.of(new Quotes.Line(sku, 5, List.of(new Quotes.Tier(5, new BigDecimal("2400")))))));
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
    @DisplayName("SCRUM-502: the requested delivery date is kept with the draft, moved only while a draft, "
            + "recorded in each sent offer, and refused in the past")
    void requestedDeliveryDate() throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        LocalDate asked = today.plusDays(30);
        Instant validUntil = Instant.now().plus(Duration.ofDays(7));
        List<Quotes.Line> lines = draft(validUntil).lines();

        // Not in the past: refused on creation and on edit, and nothing is stored.
        assertThatThrownBy(() -> quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(validUntil, null, lines, today.minusDays(1)))))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        // Today is a date that can still be asked for.
        Quotes.QuoteView todays = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(validUntil, null, lines, today)));
        assertThat(todays.requestedDeliveryDate()).isEqualTo(today);

        Quotes.QuoteView created = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(validUntil, null, lines, asked)));
        assertThat(created.requestedDeliveryDate()).isEqualTo(asked);
        assertThat(jdbc.queryForObject("SELECT requested_delivery_date FROM ordering.quote WHERE id = ?", LocalDate.class,
                created.id())).isEqualTo(asked);
        assertThatThrownBy(() -> quotes.edit(sales, created.id(), created.version(),
                new Quotes.Draft(validUntil, null, lines, today.minusDays(1))))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(quotes.get(sales, created.id()).requestedDeliveryDate()).isEqualTo(asked);

        // Moved while a draft; an edit without a date clears it (the edit replaces the whole offer).
        Quotes.QuoteView moved = quotes.edit(sales, created.id(), created.version(),
                new Quotes.Draft(validUntil, null, lines, asked.plusDays(5)));
        assertThat(moved.requestedDeliveryDate()).isEqualTo(asked.plusDays(5));
        Quotes.QuoteView cleared = quotes.edit(sales, created.id(), moved.version(),
                new Quotes.Draft(validUntil, null, lines));
        assertThat(cleared.requestedDeliveryDate()).isNull();
        Quotes.QuoteView again = quotes.edit(sales, created.id(), cleared.version(),
                new Quotes.Draft(validUntil, null, lines, asked));
        Quotes.QuoteView sent = quotes.send(sales, created.id(), again.version());

        // Sent: the offer on the history row keeps the date, and a sent quote can no longer be edited.
        assertThat(jdbc.queryForObject("SELECT offer ->> 'requestedDeliveryDate' FROM ordering.quote_status_history "
                + "WHERE quote_id = ? AND to_status = 'SENT'", String.class, created.id())).isEqualTo(asked.toString());
        assertThatThrownBy(() -> quotes.edit(sales, created.id(), sent.version(),
                new Quotes.Draft(validUntil, null, lines, asked.plusDays(1))))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);
        assertThat(quotes.get(portal, created.id()).requestedDeliveryDate()).isEqualTo(asked);

        // The customer asks for changes; Sales moves the date in the next revision and drops it before sending: the
        // customer still sees the quote end with the date they were sent.
        Quotes.QuoteView changed = quotes.requestChanges(portal, created.id(), sent.version(), "earlier please");
        Quotes.QuoteView reopened = quotes.reopen(sales, created.id(), changed.version());
        assertThat(reopened.requestedDeliveryDate()).as("the next revision starts from the sent offer").isEqualTo(asked);
        Quotes.QuoteView revised = quotes.edit(sales, created.id(), reopened.version(),
                new Quotes.Draft(validUntil, null, lines, asked.minusDays(10)));
        quotes.cancel(sales, created.id(), revised.version(), "Khach chon noi khac");
        assertThat(quotes.get(portal, created.id()).requestedDeliveryDate()).isEqualTo(asked);
        assertThat(quotes.get(sales, created.id()).requestedDeliveryDate()).isEqualTo(asked.minusDays(10));
    }

    @Test
    @DisplayName("A sent quote cannot be withdrawn; a revision reopened and dropped before it was sent ends the quote "
            + "for the customer with the offer they were last sent, and a stale answer is told why")
    void droppedRevision() throws Exception {
        Quotes.QuoteView sent = sentQuote();
        assertThatThrownBy(() -> quotes.cancel(sales, sent.id(), sent.version(), "Giá nhập nhầm"))
                .as("a sent offer is answered, revised or expires - Sales does not withdraw it")
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);

        // The customer sees the quote end with the offer they were last sent, not the draft's working terms.
        Quotes.QuoteView third = sentQuote();
        Quotes.QuoteView asked = quotes.requestChanges(portal, third.id(), third.version(), "giảm giá được không?");
        Quotes.QuoteView reopened = quotes.reopen(sales, third.id(), asked.version());
        Instant draftValidity = Instant.now().plus(Duration.ofDays(20)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Quotes.QuoteView edited = quotes.edit(sales, third.id(), reopened.version(),
                draft(draftValidity));
        quotes.cancel(sales, third.id(), edited.version(), "Không giảm được, khách chọn nơi khác");
        Quotes.QuoteView customerView = quotes.get(portal, third.id());
        assertThat(customerView.status()).isEqualTo(QuoteStatus.CANCELLED);
        assertThat(customerView.cancellationReason()).contains("Không giảm");
        assertThat(customerView.revision()).as("the revision they were sent").isEqualTo(third.revision());
        assertThat(customerView.validUntil()).isEqualTo(third.validUntil());
        assertThat(customerView.priced()).isEqualTo(third.priced());
        assertThat(customerView.proposedTotal()).isEqualByComparingTo(third.proposedTotal());
        assertThat(customerView.lines()).extracting(Quotes.LineView::lineNo, Quotes.LineView::cartons,
                        Quotes.LineView::proposedUnitPrice)
                .containsExactlyElementsOf(third.lines().stream().map(line -> org.assertj.core.groups.Tuple.tuple(
                        line.lineNo(), line.cartons(), line.proposedUnitPrice())).toList());
        Quotes.QuoteView staffView = quotes.get(sales, third.id());
        assertThat(staffView.revision()).as("Sales sees its draft").isEqualTo(2);
        assertThat(staffView.validUntil()).isEqualTo(draftValidity);
        assertThatThrownBy(() -> quotes.accept(portal, third.id(), new Quotes.Acceptance(third.version(), Map.of())))
                .as("the version the customer still has: told why, not that it changed")
                .hasMessageContaining("withdrawn").hasMessageContaining("Không giảm")
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);
    }

    @Test
    @DisplayName("idempotent creation; only ACTIVE customers and the customer's own SKUs")
    void creationRules() throws Exception {
        UUID request = UUID.randomUUID();
        Quotes.Draft draft = draft(Instant.now().plus(Duration.ofDays(7)));
        Quotes.QuoteView first = quotes.create(sales, new Quotes.Create(request, customerId, draft));
        assertThat(quotes.create(sales, new Quotes.Create(request, customerId, draft)).id()).isEqualTo(first.id());
        UUID other = tx.execute(s -> ReferenceRows.customer(entityManager));
        assertThatThrownBy(() -> quotes.create(sales, new Quotes.Create(request, other, draft)))
                .extracting(QuoteIntegrationTest::code).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);

        assertThatThrownBy(() -> quotes.create(sales, new Quotes.Create(UUID.randomUUID(), other, draft)))
                .as("another customer's SKU: not there for this one (13 BR-02)").extracting(QuoteIntegrationTest::code)
                .isEqualTo(ErrorCode.VARIANT_NOT_FOUND);
        assertThatThrownBy(() -> quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(com.stockflow.support.ConfirmedDesigns.BLANK, 3,
                                List.of(new Quotes.Tier(2, new BigDecimal("3000")))))))))
                .as("a stock variant, not the customer's own SKU (14 BR-04)").extracting(QuoteIntegrationTest::code)
                .isEqualTo(ErrorCode.CUSTOMER_SKU_REQUIRED);
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

    @Test
    @DisplayName("4B-2, 14 BR-08: the carton spec changed after acceptance (the unit retired, a new one declared) - the "
            + "accepted quote still converts at the cartons and pieces it fixed; a new draft takes the new spec")
    void aCartonSpecChangedAfterAcceptanceDoesNotMoveTheQuote() {
        Quotes.QuoteView sent = sentQuote();
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(
                quotes.get(portal, sent.id()).version(), Map.of()));
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE inventory.uom_conversions SET active = FALSE, default_shipping = FALSE "
                    + "WHERE unit_of_measure = 'CARTON' AND inventory_item_id = (SELECT id FROM inventory.inventory_items "
                    + "WHERE sku = ?)", sku);
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base,
                        default_purchase, active, version, created_at, default_shipping)
                    SELECT ?, i.id, 'CARTON20', 20, FALSE, FALSE, TRUE, 0, NOW(), TRUE FROM inventory.inventory_items i
                     WHERE i.sku = ?""", Identifiers.newId(), sku);
        });

        UUID orderId = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        assertThat(orders.findById(orderId).orElseThrow().lines()).singleElement().satisfies(line -> {
            assertThat(line.cartons()).isEqualTo(3);
            assertThat(line.unitsPerCarton()).as("as accepted, not the new spec").isEqualTo(PER_CARTON);
            assertThat(line.quantity()).isEqualTo(3 * PER_CARTON);
        });
        Quotes.QuoteView next = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(Instant.now().plus(Duration.ofDays(7)))));
        assertThat(next.lines().getFirst().unitsPerCarton()).as("a new line takes today's carton").isEqualTo(20);
    }

    @Test
    @DisplayName("QD-16 through the database: a quote drafted on a blank and a design reads as kept, is not sent, and is "
            + "revised onto the customer's SKU; a cancelled one shows the customer the offer it was sent, from the "
            + "history written before 4B-2")
    void aQuoteDraftedOnBlanksIsKeptToReadAndRevisedOntoSkus() {
        UUID snapshot = jdbc.queryForObject("SELECT design_snapshot_id FROM product.variants WHERE sku = ?", UUID.class,
                sku);
        UUID draftId = Identifiers.newId();
        UUID cancelledId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            for (UUID id : List.of(draftId, cancelledId)) {
                boolean cancelled = id.equals(cancelledId);
                jdbc.update("""
                        INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, revision, payment_term,
                            valid_until, sent_at, sent_by, cancellation_reason, version, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'PREPAID', NOW() + interval '7 days', ?, ?, ?, 0, NOW())""",
                        id, "QT-LEGACY-" + id.toString().substring(24), UUID.randomUUID(), customerId,
                        cancelled ? "CANCELLED" : "DRAFT", cancelled ? 2 : 1,
                        cancelled ? java.sql.Timestamp.from(Instant.now()) : null, cancelled ? sales.userId() : null,
                        cancelled ? "Khach chon noi khac" : null);
                UUID line = Identifiers.newId();
                jdbc.update("INSERT INTO ordering.quote_line (id, quote_id, line_no, blank_sku, design_snapshot_id, "
                        + "print_spec, quantity) VALUES (?, ?, 1, ?, ?, 'Logo', 600)", line, id,
                        com.stockflow.support.ConfirmedDesigns.BLANK, snapshot);
                jdbc.update("INSERT INTO ordering.quote_price_tier (quote_line_id, min_quantity, unit_price) "
                        + "VALUES (?, 500, 2000)", line);
            }
            // The cancelled one: sent at revision 1 (the offer as written before 4B-2), reopened, dropped as a draft.
            String offer = """
                    {"revision": 1, "paymentTerm": "PREPAID", "depositPercent": null, "validUntil": "%s", "terms": null,
                     "requestedDeliveryDate": null, "lines": [{"lineNo": 1, "blankSku": "%s", "designSnapshotId": "%s",
                     "printSpec": "Logo", "quantity": 600, "tiers": [{"minQuantity": 500, "unitPrice": 2100}]}]}"""
                    .formatted(Instant.now().plus(Duration.ofDays(7)), com.stockflow.support.ConfirmedDesigns.BLANK,
                            snapshot);
            int minute = 0;
            for (String[] step : List.of(new String[] {null, "DRAFT"}, new String[] {"DRAFT", "SENT"},
                    new String[] {"SENT", "CHANGES_REQUESTED"}, new String[] {"CHANGES_REQUESTED", "DRAFT"},
                    new String[] {"DRAFT", "CANCELLED"})) {
                jdbc.update("""
                        INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason,
                            offer, changed_at, changed_by)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), NOW() + make_interval(mins => ?), ?)""",
                        Identifiers.newId(), cancelledId, minute < 3 ? 1 : 2, step[0], step[1],
                        "CHANGES_REQUESTED".equals(step[1]) ? "re hon?" : null, "SENT".equals(step[1]) ? offer : null,
                        minute++, sales.userId());
            }
        });

        Quotes.QuoteView kept = quotes.get(sales, draftId);
        assertThat(kept.legacy()).isTrue();
        assertThat(kept.lines()).singleElement().satisfies(line -> {
            assertThat(line.measure()).isEqualTo("UNIT");
            assertThat(line.units()).isEqualTo(600);
            assertThat(line.cartons()).isNull();
            assertThat(line.proposedLineTotal()).isEqualByComparingTo("1200000");
        });
        assertThatThrownBy(() -> quotes.send(sales, draftId, kept.version())).extracting(QuoteIntegrationTest::code)
                .isEqualTo(ErrorCode.QUOTE_LINES_LEGACY);

        Quotes.QuoteView revised = quotes.edit(sales, draftId, kept.version(),
                draft(Instant.now().plus(Duration.ofDays(7))));
        assertThat(revised.legacy()).isFalse();
        assertThat(jdbc.queryForList("SELECT COALESCE(sku, blank_sku) FROM ordering.quote_line WHERE quote_id = ?",
                String.class, draftId)).containsExactly(sku);
        assertThat(quotes.send(sales, draftId, revised.version()).status()).isEqualTo(QuoteStatus.SENT);

        Quotes.QuoteView seen = quotes.get(portal, cancelledId);
        assertThat(seen.status()).isEqualTo(QuoteStatus.CANCELLED);
        assertThat(seen.legacy()).as("the offer the customer was sent, read from the old history").isTrue();
        assertThat(seen.lines()).singleElement().satisfies(line -> {
            assertThat(line.units()).isEqualTo(600);
            assertThat(line.tiers().getFirst().unitPrice()).isEqualByComparingTo("2100");
        });
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
