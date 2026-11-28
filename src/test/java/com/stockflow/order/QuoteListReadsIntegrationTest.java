package com.stockflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.catalog.internal.service.StandardPriceService;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.StaffFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the back office quote list and the storefront order list read beside each row (UI/UX fixes, 2026-11-27), over
 * HTTP with the real security chain: the status of the order a quote became, the first item of a customer's order card,
 * and the counts behind the quote list's status tabs - under the list's filters, permission and visibility. One fresh
 * customer with one SKU issued from a design named "Ly UX" ({@link CustomerSkuRows}); every quote is written through the
 * services the controllers call. The same context as {@code Round5ReadModelsIntegrationTest}.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class QuoteListReadsIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired Quotes quotes;
    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired StandardPriceService standardPrices;

    private SecuredMvc http;
    private ConfirmedOrders confirmedOrders;
    private UUID salesUser;
    private UUID portalUser;
    private CurrentUser sales;
    private CurrentUser portal;
    private UUID customerId;
    private CustomerSkuRows.Issued issued;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        StaffFixtures people = new StaffFixtures(jdbc, tx, entityManager);
        salesUser = people.staff("SALES_STAFF");
        UUID accountant = people.staff("ACCOUNTANT");
        portalUser = people.staff("CUSTOMER");
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        portal = new CurrentUser(portalUser, "portal-" + portalUser, Set.of(Role.CUSTOMER), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, user_id, full_name, email, phone, tax_code, status, version,
                        created_at)
                    VALUES (?, ?, ?, ?, '0901234567', ?, 'ACTIVE', 0, NOW())""",
                    customerId, portalUser, "Cong ty UX " + customerId.toString().substring(0, 8),
                    "ux-" + customerId + "@example.com",
                    "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                        approved_at)
                    VALUES (?, ?, 100000000, 30, ?, NOW())""", Identifiers.newId(), customerId, accountant);
        });
        customers.addAddress(customerId, salesUser, true, new SaveAddressCommand("SHIPPING", "Nguyen Van A",
                "0901234567", "1 Le Loi", null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null, true, null));
        confirmedOrders = new ConfirmedOrders(context);
        confirmedOrders.legalProfile(customerId);
        // A first production (a plate fee) of a STANDARD product, 10 cups a carton, drawn from a design with a name.
        issued = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 10, "STANDARD", false);
        UUID design = CustomerSkuRows.designOf(jdbc, issued);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE design.design_draft SET name = 'Ly UX' WHERE id = ?", design));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private JsonNode asSales(MockHttpServletRequestBuilder request) throws Exception {
        return data(http.asRole(Role.SALES_STAFF, salesUser, request));
    }

    private JsonNode asCustomer(MockHttpServletRequestBuilder request) throws Exception {
        return data(http.asCustomer(portalUser, request));
    }

    private Quotes.Draft draft(BigDecimal unitPrice, BigDecimal plateFee) {
        return new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null, List.of(new Quotes.Line(issued.sku(), 3,
                List.of(new Quotes.Tier(2, unitPrice)))), null,
                plateFee == null ? List.of() : List.of(new Quotes.Fee(FeeKind.PLATE_FEE, issued.sku(), plateFee)));
    }

    private Quotes.QuoteView drafted(BigDecimal unitPrice) {
        Quotes.QuoteView drafted = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(unitPrice, null)));
        return quotes.edit(sales, drafted.id(), drafted.version(), draft(unitPrice, new BigDecimal("500000")));
    }

    /** Priced like the reference and sent; the customer sees it. */
    private Quotes.QuoteView sent() {
        Quotes.QuoteView priced = drafted(new BigDecimal("3000"));
        Quotes.QuoteView sent = quotes.send(sales, priced.id(), priced.version());
        assertThat(sent.status()).isEqualTo(com.stockflow.order.internal.domain.QuoteStatus.SENT);
        return sent;
    }

    @Test
    @DisplayName("a converted quote's row carries its order's status - not to the customer while the order is a draft; "
            + "the customer's order card names its first item by the design")
    void convertedOrderStatusAndFirstItem() throws Exception {
        Quotes.QuoteView sent = sent();
        Quotes.QuoteView seen = quotes.get(portal, sent.id());
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(seen.version(),
                Map.of(seen.lines().getFirst().id(), 4)));
        UUID orderId = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();

        JsonNode staffRow = asSales(get("/api/v1/quotes").param("customerId", customerId.toString()))
                .path("items").get(0);
        assertThat(staffRow.path("convertedOrderStatus").asText()).isEqualTo("DRAFT");
        JsonNode customerRow = asCustomer(get("/api/v1/quotes")).path("items").get(0);
        assertThat(customerRow.path("id").asText()).isEqualTo(sent.id().toString());
        assertThat(customerRow.has("convertedOrderStatus"))
                .as("17 §5.2: the customer meets the order once its contract is sent, as its number").isFalse();
        assertThat(customerRow.has("convertedOrderNumber")).isFalse();

        confirmedOrders.sign(orderId, ConfirmedOrders.paidOnSigning());
        String now = orders.findById(orderId).orElseThrow().status().name();
        assertThat(asSales(get("/api/v1/quotes").param("customerId", customerId.toString())).path("items").get(0)
                .path("convertedOrderStatus").asText()).isEqualTo(now);
        assertThat(asCustomer(get("/api/v1/quotes")).path("items").get(0).path("convertedOrderStatus").asText())
                .isEqualTo(now);

        JsonNode card = asCustomer(get("/api/v1/orders/me")).path("items").get(0);
        assertThat(card.path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(card.path("lineCount").asInt()).isEqualTo(1);
        assertThat(card.path("firstItemName").asText()).as("the design of its first line, as the quote row")
                .isEqualTo("Ly UX");
    }

    @Test
    @DisplayName("status counts: each status as the list shows it, the price review queue, the list's own filters - one "
            + "count per tab equal to the list it opens")
    void statusCountsForTheBackOffice() throws Exception {
        drafted(new BigDecimal("3000"));
        Quotes.QuoteView live = sent();
        Quotes.QuoteView lapsed = sent();
        // Sent a week ago and valid until yesterday: EXPIRED as read, whether or not the expiry sweep has written it.
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE ordering.quote SET created_at = NOW() - INTERVAL '8 days', valid_until = NOW() - INTERVAL '1 day'
                 WHERE id = ?""", lapsed.id()));
        // A piece at 1 VND against a standard price raised to 1,000 (as QuotePriceApprovalIntegrationTest sets it): far
        // below its reference, so it waits for the Sales Manager (14 BR-10).
        String variant = jdbc.queryForObject("""
                SELECT s.sku FROM product.customer_skus c JOIN product.variants s ON s.id = c.variant_id
                 WHERE c.sku = ?""", String.class, issued.sku());
        standardPrices.replace(variant, List.of(new StandardPriceService.TierInput(1, new BigDecimal("1000"))), null,
                standardPrices.get(variant).version());
        Quotes.QuoteView cheap = drafted(BigDecimal.ONE);
        Quotes.QuoteView waiting = quotes.send(sales, cheap.id(), cheap.version());
        assertThat(waiting.priceReviewPending()).isTrue();

        JsonNode counts = asSales(get("/api/v1/quotes/status-counts").param("customerId", customerId.toString()));
        assertThat(counts.path("byStatus").path("DRAFT").asLong()).isEqualTo(2);
        assertThat(counts.path("byStatus").path("SENT").asLong()).isEqualTo(1);
        assertThat(counts.path("byStatus").path("EXPIRED").asLong()).isEqualTo(1);
        assertThat(counts.path("byStatus").path("CONVERTED").asLong()).as("every status, zero included").isZero();
        assertThat(counts.path("byStatus").size()).isEqualTo(7);
        assertThat(counts.path("total").asLong()).isEqualTo(4);
        assertThat(counts.path("priceReviewPending").asLong()).isEqualTo(1);

        // Each tab counts what its list holds.
        for (String tab : List.of("DRAFT", "SENT", "EXPIRED")) {
            long listed = asSales(get("/api/v1/quotes").param("customerId", customerId.toString())
                    .param("status", tab)).path("totalElements").asLong();
            assertThat(counts.path("byStatus").path(tab).asLong()).as(tab).isEqualTo(listed);
        }
        assertThat(asSales(get("/api/v1/quotes").param("customerId", customerId.toString())
                .param("priceReview", "PENDING")).path("totalElements").asLong()).isEqualTo(1);

        // The list's filters: a status, the number searched, the price review queue.
        JsonNode sentOnly = asSales(get("/api/v1/quotes/status-counts").param("customerId", customerId.toString())
                .param("status", "SENT"));
        assertThat(sentOnly.path("total").asLong()).isEqualTo(1);
        assertThat(sentOnly.path("byStatus").path("EXPIRED").asLong()).as("past its validity: not SENT").isZero();
        JsonNode searched = asSales(get("/api/v1/quotes/status-counts").param("customerId", customerId.toString())
                .param("search", live.quoteNumber()));
        assertThat(searched.path("total").asLong()).isEqualTo(1);
        assertThat(searched.path("byStatus").path("SENT").asLong()).isEqualTo(1);
        JsonNode queue = asSales(get("/api/v1/quotes/status-counts").param("customerId", customerId.toString())
                .param("priceReview", "PENDING"));
        assertThat(queue.path("total").asLong()).isEqualTo(1);
        assertThat(queue.path("byStatus").path("DRAFT").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("status counts to a portal customer: their own quotes as they were shown them, whatever customer they "
            + "name; a revision Sales are writing reads CHANGES_REQUESTED, a draft nothing")
    void statusCountsForTheCustomer() throws Exception {
        drafted(new BigDecimal("3000"));
        sent();
        Quotes.QuoteView asked = sent();
        Quotes.QuoteView seen = quotes.get(portal, asked.id());
        Quotes.QuoteView changes = quotes.requestChanges(portal, asked.id(), seen.version(), "Doi so thung");
        quotes.reopen(sales, asked.id(), changes.version());

        // Sales see the revision as their draft; the customer as the quote they asked to change.
        JsonNode staff = asSales(get("/api/v1/quotes/status-counts").param("customerId", customerId.toString()));
        assertThat(staff.path("byStatus").path("DRAFT").asLong()).isEqualTo(2);
        assertThat(staff.path("byStatus").path("CHANGES_REQUESTED").asLong()).isZero();

        UUID someoneElse = Identifiers.newId();
        JsonNode mine = asCustomer(get("/api/v1/quotes/status-counts").param("customerId", someoneElse.toString()));
        assertThat(mine.path("byStatus").path("DRAFT").asLong()).as("Sales' working copy is not shown").isZero();
        assertThat(mine.path("byStatus").path("SENT").asLong()).isEqualTo(1);
        assertThat(mine.path("byStatus").path("CHANGES_REQUESTED").asLong()).isEqualTo(1);
        assertThat(mine.path("total").asLong()).isEqualTo(2);
        assertThat(mine.path("priceReviewPending").asLong()).isZero();
        assertThat(mine.path("total").asLong()).as("the customer's own list, whatever customer they name")
                .isEqualTo(asCustomer(get("/api/v1/quotes").param("customerId", someoneElse.toString()))
                        .path("totalElements").asLong());
        assertThat(asCustomer(get("/api/v1/quotes/status-counts").param("status", "CHANGES_REQUESTED"))
                .path("total").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("status counts need sales-quotes:READ, as the list: refused to a role without it, and to no one signed in")
    void statusCountsArePermissioned() throws Exception {
        UUID warehouse = new StaffFixtures(jdbc, tx, entityManager).staff("WAREHOUSE_STAFF");
        http.asRole(Role.WAREHOUSE_STAFF, warehouse, get("/api/v1/quotes")).andExpect(status().isForbidden());
        http.asRole(Role.WAREHOUSE_STAFF, warehouse, get("/api/v1/quotes/status-counts"))
                .andExpect(status().isForbidden());
        http.anonymous(get("/api/v1/quotes/status-counts")).andExpect(status().isUnauthorized());
        http.asRole(Role.SALES_MANAGER, new StaffFixtures(jdbc, tx, entityManager).staff("SALES_MANAGER"),
                get("/api/v1/quotes/status-counts")).andExpect(status().isOk());
    }
}
