package com.stockflow.order;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.support.Await;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ProductFixtures;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.ReferenceRows;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Review of PR #109, item 6 (BR-PRD-005 for sales, decision of 2026-10-16): a SKU that is INACTIVE or
 * DISCONTINUED goes on no new quote, no sending or revision of one, no acceptance - an old quote included - no
 * conversion into a new order. The refusal names the SKU and the line; nothing is substituted.
 * Work already running carries on, and a product resumed is accepted again: the status is asked at every step.
 *
 * <p>The product is this test's own, made by the product module as it makes every product, so suspending it
 * touches no other test. The race - the status changes while a step is under way - is run for real: the step
 * holds the product row FOR SHARE until it commits, and the suspension waits for it.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
// Mail goes to the mocked JavaMailSender (SMTP), as before the transport existed; customer order mail is off.
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false", "stockflow.notification.email.provider=smtp",
        "stockflow.notification.email.customer.enabled=false"})
class InactiveSkuQuoteIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired Quotes quotes;
    @Autowired CustomerService customers;
    @Autowired ProductService products;
    @Autowired ProductTaxonomyService taxonomy;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    /** Sales notices are captured, never sent. */
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private CurrentUser portal;
    private UUID customerId;
    private UUID productId;
    private String sku;

    @BeforeEach
    void setUp() throws Exception {
        sales = staff(Role.SALES_STAFF);
        UUID portalUser = tx.execute(s -> ReferenceRows.user(entityManager));
        portal = new CurrentUser(portalUser, "portal-" + portalUser, Set.of(Role.CUSTOMER), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        customerId = customerWithPortal(portalUser);
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        UUID editor = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID approver = tx.execute(s -> ReferenceRows.user(entityManager));
        productId = ProductFixtures.approved(products, ProductFixtures.taxonomy(taxonomy, suffix), suffix, editor,
                approver).productId();
        UUID variant = jdbc.queryForObject("SELECT id FROM product.variants WHERE sku = ?", UUID.class, "FX-" + suffix);
        // 4B-2: what is quoted is the customer's own SKU, drawn on this product - suspending the product stops it too.
        sku = CustomerSkuRows.issueOn(jdbc, tx, customerId, new ReferenceRows.ProductRow(productId, variant,
                "FX-" + suffix), 10).sku();
        CustomerSkuRows.stock(jdbc, tx, sku, 100);
    }

    // ------------------------------------------------------------------------------------- fixtures

    private CurrentUser staff(Role role) {
        UUID id = tx.execute(s -> ReferenceRows.user(entityManager));
        return new CurrentUser(id, role.name().toLowerCase(Locale.ROOT) + "-" + id, Set.of(role), Set.of(),
                DataScope.ALL, Set.of(), Set.of());
    }

    private UUID customerWithPortal(UUID portalUser) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, phone, status, version, created_at)
                VALUES (?, ?, 'Cong ty In Ly B', ?, '0901234568', 'ACTIVE', 0, NOW())""",
                id, portalUser, "buyer-" + id + "@example.com"));
        customers.addAddress(id, sales.userId(), true, new SaveAddressCommand("SHIPPING", "Nguyen Van B",
                "0901234568", "2 Le Loi", null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null, true, null));
        return id;
    }

    private Quotes.Draft draft() {
        return new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), "Prices exclude VAT",
                List.of(new Quotes.Line(sku, 3, List.of(new Quotes.Tier(2, new BigDecimal("3000"))))));
    }

    private Quotes.QuoteView drafted() {
        return quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId, draft()));
    }

    private Quotes.QuoteView sent() {
        Quotes.QuoteView created = drafted();
        return quotes.send(sales, created.id(), created.version());
    }

    private Quotes.QuoteView accepted() {
        Quotes.QuoteView sent = sent();
        return quotes.accept(portal, sent.id(), acceptance(sent));
    }

    private static Quotes.Acceptance acceptance(Quotes.QuoteView quote) {
        return new Quotes.Acceptance(quote.version(), Map.of(quote.lines().getFirst().id(), 3));
    }

    private long productVersion() {
        return products.findById(productId).orElseThrow().version();
    }

    private void suspend() {
        products.suspend(productId, "Supplier recall", productVersion());
    }

    private QuoteStatus status(UUID quoteId) {
        return quotes.get(sales, quoteId).status();
    }

    private int ordersOfCustomer() {
        return jdbc.queryForObject("SELECT count(*) FROM ordering.customer_order WHERE customer_id = ?", Integer.class,
                customerId);
    }

    /** SKU_NOT_SELLABLE, naming the SKU and the line, on the line's field. */
    private void assertRefusedFor(Throwable thrown, String why) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.SKU_NOT_SELLABLE);
            assertThat(e.getMessage()).contains(sku);
            assertThat(e.fieldErrors()).extracting(f -> f.field() + ":" + f.code())
                    .anyMatch(f -> f.endsWith(":" + why));
        });
    }

    // ------------------------------------------------------------------------------------- tests

    @Test
    @DisplayName("a suspended SKU goes on no new quote, no sending and no revision; resumed, the quote is sent")
    void draftingSendingAndRevisingAskTheStatusEveryTime() {
        Quotes.QuoteView created = drafted();
        suspend();

        Throwable send = catchThrowable(() -> quotes.send(sales, created.id(), created.version()));
        assertRefusedFor(send, "PRODUCT_INACTIVE");
        assertThat(((BusinessException) send).getMessage()).contains("line 1");
        assertThat(((BusinessException) send).fieldErrors()).extracting(f -> f.field())
                .containsExactly("lines[0].sku");
        assertRefusedFor(catchThrowable(() -> quotes.edit(sales, created.id(), created.version(), draft())),
                "PRODUCT_INACTIVE");
        assertRefusedFor(catchThrowable(this::drafted), "PRODUCT_INACTIVE");
        assertThat(status(created.id())).isEqualTo(QuoteStatus.DRAFT);

        products.resume(productId, productVersion());
        assertThat(quotes.send(sales, created.id(), created.version()).status()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    @DisplayName("a quote sent before the product was discontinued cannot be accepted after it")
    void anOldQuoteIsNotAcceptedOnADiscontinuedSku() {
        Quotes.QuoteView sent = sent();
        products.discontinue(productId, "End of line", productVersion());

        assertRefusedFor(catchThrowable(() -> quotes.accept(portal, sent.id(), acceptance(sent))),
                "PRODUCT_DISCONTINUED");
        assertThat(status(sent.id())).isEqualTo(QuoteStatus.SENT);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ordering.quote_status_history WHERE quote_id = ? AND to_status = 'ACCEPTED'""",
                Integer.class, sent.id())).isZero();
    }

    @Test
    @DisplayName("an accepted quote is not converted while its SKU is suspended; nothing is placed; resumed, it converts")
    void conversionAsksTheStatusAgain() {
        Quotes.QuoteView accepted = accepted();
        int before = ordersOfCustomer();
        suspend();

        assertRefusedFor(catchThrowable(() -> quotes.convert(sales, accepted.id(),
                new Quotes.Conversion(accepted.version(), null, null))), "PRODUCT_INACTIVE");
        assertThat(status(accepted.id())).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(ordersOfCustomer()).as("no order placed").isEqualTo(before);

        products.resume(productId, productVersion());
        Quotes.QuoteView converted = quotes.convert(sales, accepted.id(),
                new Quotes.Conversion(accepted.version(), null, null));
        assertThat(converted.status()).isEqualTo(QuoteStatus.CONVERTED);
        assertThat(ordersOfCustomer()).isEqualTo(before + 1);

        // The order placed before the next suspension carries on: nothing about it changes.
        String orderStatus = jdbc.queryForObject("SELECT status FROM ordering.customer_order WHERE id = ?",
                String.class, converted.convertedOrder().orderId());
        suspend();
        assertThat(jdbc.queryForObject("SELECT status FROM ordering.customer_order WHERE id = ?", String.class,
                converted.convertedOrder().orderId())).isEqualTo(orderStatus);
    }

    @Test
    @DisplayName("decision 2026-10-17: an accepted quote whose SKU stopped is cancelled by Sales with a reason, kept in "
            + "its history and still shown to the customer; with every SKU active it is converted, not cancelled")
    void anAcceptedQuoteWithAStoppedSkuIsCancelledWithAReason() {
        Quotes.QuoteView accepted = accepted();
        assertThat(code(catchThrowable(() -> quotes.cancel(sales, accepted.id(), accepted.version(), "Changed my mind"))))
                .as("every SKU active: convert it").isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);
        suspend();
        Throwable convert = catchThrowable(() -> quotes.convert(sales, accepted.id(),
                new Quotes.Conversion(accepted.version(), null, null)));
        assertRefusedFor(convert, "PRODUCT_INACTIVE");
        assertThat(convert.getMessage()).contains("cancel the quote");
        assertThat(code(catchThrowable(() -> quotes.cancel(sales, accepted.id(), accepted.version(), " "))))
                .as("a reason is required").isEqualTo(ErrorCode.VALIDATION_FAILED);

        Quotes.QuoteView cancelled = quotes.cancel(sales, accepted.id(), accepted.version(),
                "Blank discontinued by the supplier; a new quote follows on the 16oz cup");

        assertThat(cancelled.status()).isEqualTo(QuoteStatus.CANCELLED);
        assertThat(cancelled.history()).last().satisfies(t -> {
            assertThat(t.fromStatus()).isEqualTo(QuoteStatus.ACCEPTED);
            assertThat(t.toStatus()).isEqualTo(QuoteStatus.CANCELLED);
            assertThat(t.reason()).startsWith("Blank discontinued");
        });
        assertThat(quotes.get(portal, accepted.id()).status()).as("the customer sees what became of it")
                .isEqualTo(QuoteStatus.CANCELLED);
        assertThat(quotes.list(portal, new Quotes.Criteria(null, null, null, 0, 20, null)).items())
                .extracting(Quotes.Row::id).contains(accepted.id());
        assertThat(code(catchThrowable(() -> quotes.convert(sales, accepted.id(),
                new Quotes.Conversion(cancelled.version(), null, null))))).isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("race: a suspension during an acceptance waits for it; the conversion after it is refused")
    void aSuspensionDuringAnAcceptanceWaitsForIt() throws Exception {
        Quotes.QuoteView sent = sent();
        CountDownLatch acceptedNotCommitted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CompletableFuture<Quotes.QuoteView> acceptance = CompletableFuture.supplyAsync(() -> tx.execute(s -> {
            Quotes.QuoteView view = quotes.accept(portal, sent.id(), acceptance(sent));
            acceptedNotCommitted.countDown();
            try {
                commit.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return view;
        }));
        assertThat(acceptedNotCommitted.await(30, TimeUnit.SECONDS)).isTrue();
        long version = productVersion();
        CompletableFuture<Void> suspension = CompletableFuture.runAsync(
                () -> products.suspend(productId, "Supplier recall", version));
        try {
            Await.until("the suspension waits on the product row the acceptance holds", ASYNC, () -> jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE datname = current_database() AND wait_event_type = 'Lock'""", Integer.class) > 0);
            assertThat(suspension).as("not before the acceptance commits").isNotDone();
        } finally {
            commit.countDown();
        }
        Quotes.QuoteView accepted = acceptance.get(30, TimeUnit.SECONDS);
        suspension.get(30, TimeUnit.SECONDS);

        assertThat(status(sent.id())).as("the acceptance came first, and stands").isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(products.findById(productId).orElseThrow().status()).isEqualTo(ProductStatus.INACTIVE);
        assertRefusedFor(catchThrowable(() -> quotes.convert(sales, sent.id(),
                new Quotes.Conversion(quotes.get(sales, sent.id()).version(), null, null))), "PRODUCT_INACTIVE");
        assertThat(accepted.status()).isEqualTo(QuoteStatus.ACCEPTED);
    }
}
