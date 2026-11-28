package com.stockflow.order;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The back office customer list's salesperson column (owner decision 2026-11-27) over HTTP, through the real filter
 * chain: {@code GET /orders/sales-contacts} names who sent each customer's most recent quote or contract, derived from
 * the documents (kltn-docs 00 §2), and says whether that account is still active. Every document is written through the
 * services Sales use - quotes through {@link Quotes}, contracts through {@link ConfirmedOrders} - so the rows are ones
 * the application makes. The context the credit positions test shares.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class SalesContactApiIntegrationTest {

    private static final String CONTACTS = "/api/v1/orders/sales-contacts";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired Quotes quotes;

    private SecuredMvc http;
    private StaffFixtures people;
    private ConfirmedOrders confirmedOrders;
    /** Sends the quotes. */
    private UUID salesA;
    /** Sends the contracts: the fixture's own Sales account. */
    private UUID salesB;
    private UUID portalUser;
    /** A contract by B, then a quote by A: the quote is the latest. */
    private UUID quoteLast;
    private String quoteLastNumber;
    /** A quote by A, then a contract by B: the contract is the latest. */
    private UUID contractLast;
    private String contractLastNumber;
    /** No document at all. */
    private UUID nothing;

    @BeforeEach
    void threeCustomers() {
        http = new SecuredMvc(mvc, decoder);
        people = new StaffFixtures(jdbc, tx, entityManager);
        confirmedOrders = new ConfirmedOrders(context);
        salesA = people.staff("SALES_STAFF");
        salesB = confirmedOrders.sales().userId();
        String tag = Identifiers.newId().toString().substring(0, 8);
        name(salesA, "Sales A " + tag);
        name(salesB, "Sales B " + tag);

        portalUser = tx.execute(s -> ReferenceRows.user(entityManager));
        quoteLast = customer("Quote last", portalUser);
        contractLast = customer("Contract last", null);
        nothing = customer("Nothing yet", null);

        signedContract(quoteLast);
        quoteLastNumber = sentQuote(quoteLast).quoteNumber();

        sentQuote(contractLast);
        contractLastNumber = signedContract(contractLast);
    }

    private void name(UUID user, String fullName) {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE identity.app_user SET full_name = ? WHERE id = ?", fullName,
                user));
    }

    private String nameOf(UUID user) {
        return jdbc.queryForObject("SELECT full_name FROM identity.app_user WHERE id = ?", String.class, user);
    }

    /** A customer, signing in as {@code login} when given, else with a sign-in of its own. */
    private UUID customer(String name, UUID login) {
        UUID id = Identifiers.newId();
        UUID user = login != null ? login : tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0, NOW())""", id, user, name, "contact-" + id + "@example.com"));
        return id;
    }

    /** A quote of a SKU of the customer's own, drawn up and sent by Sales A. */
    private Quotes.QuoteView sentQuote(UUID customerId) {
        CustomerSkuRows.Issued sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 10);
        CurrentUser sales = new CurrentUser(salesA, "sales-" + salesA, Set.of(Role.SALES_STAFF), Set.of(),
                DataScope.ALL, Set.of(), Set.of());
        // Strict mode (14 BR-25): Sales A works on the customer as its owner; the fixture takes it back for Sales B
        // when it draws its contract - the customer moving between the two, as a reassignment does.
        new com.stockflow.support.SalesOwners(context).own(customerId, salesA);
        Quotes.QuoteView drafted = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null, List.of(new Quotes.Line(sku.sku(), 3,
                        List.of(new Quotes.Tier(2, new BigDecimal("3000"))))), null, List.of())));
        Quotes.QuoteView sent = quotes.send(sales, drafted.id(), drafted.version());
        assertThat(sent.status()).isEqualTo(QuoteStatus.SENT);
        return sent;
    }

    /** A contract drawn up and sent by Sales B (the fixture's Sales account) and signed; returns its number. */
    private String signedContract(UUID customerId) {
        CustomerSkuRows.Issued sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 10);
        UUID orderId = confirmedOrders.draft(customerId, List.of(ConfirmedOrders.Line.cartons(sku, 2, 3000)));
        return confirmedOrders.sign(orderId, ConfirmedOrders.paidOnSigning()).contractNumber();
    }

    @Test
    @DisplayName("each customer's latest sent document names its salesperson - a newer quote over an older contract, a "
            + "newer contract over an older quote; a customer with neither has no item; in the order asked, once each")
    void theLatestDocumentNamesTheSalesperson() throws Exception {
        http.asRole(Role.SALES_STAFF, salesA, get(CONTACTS).param("customerIds",
                        nothing + "," + contractLast + "," + quoteLast + "," + contractLast + "," + Identifiers.newId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].customerId").value(contractLast.toString()))
                .andExpect(jsonPath("$.data[0].salesUserId").value(salesB.toString()))
                .andExpect(jsonPath("$.data[0].salesName").value(nameOf(salesB)))
                .andExpect(jsonPath("$.data[0].salesActive").value(true))
                .andExpect(jsonPath("$.data[0].source").value("CONTRACT"))
                .andExpect(jsonPath("$.data[0].documentNumber").value(contractLastNumber))
                .andExpect(jsonPath("$.data[0].at").isNotEmpty())
                .andExpect(jsonPath("$.data[1].customerId").value(quoteLast.toString()))
                .andExpect(jsonPath("$.data[1].salesUserId").value(salesA.toString()))
                .andExpect(jsonPath("$.data[1].salesName").value(nameOf(salesA)))
                .andExpect(jsonPath("$.data[1].salesActive").value(true))
                .andExpect(jsonPath("$.data[1].source").value("QUOTE"))
                .andExpect(jsonPath("$.data[1].documentNumber").value(quoteLastNumber));

        // The customer list's whole audience reads it: the accountant and the Sales Manager too; the parameter may be
        // repeated.
        http.asRole(Role.ACCOUNTANT, Identifiers.newId(), get(CONTACTS).param("customerIds", quoteLast.toString())
                        .param("customerIds", nothing.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].salesUserId").value(salesA.toString()));
        http.asRole(Role.SALES_MANAGER, Identifiers.newId(), get(CONTACTS).param("customerIds",
                        contractLast.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].salesUserId").value(salesB.toString()));
    }

    @Test
    @DisplayName("a salesperson whose account was disabled or locked is still named, and reported inactive")
    void aDepartedSalespersonIsReportedInactive() throws Exception {
        people.setStatus(salesA, "DISABLED");
        people.setStatus(salesB, "LOCKED");

        http.asRole(Role.SALES_STAFF, Identifiers.newId(), get(CONTACTS).param("customerIds",
                        quoteLast + "," + contractLast))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].salesUserId").value(salesA.toString()))
                .andExpect(jsonPath("$.data[0].salesName").value(nameOf(salesA)))
                .andExpect(jsonPath("$.data[0].salesActive").value(false))
                .andExpect(jsonPath("$.data[1].salesUserId").value(salesB.toString()))
                .andExpect(jsonPath("$.data[1].salesActive").value(false));
    }

    @Test
    @DisplayName("refused: a portal customer 403 (it holds customer-customers:READ for its own profile), a role without "
            + "customer-customers:READ 403, anonymous 401; past 200 ids and a malformed id 400")
    void refused() throws Exception {
        http.asCustomer(portalUser, get(CONTACTS).param("customerIds", quoteLast.toString()))
                .andExpect(status().isForbidden());
        http.asRole(Role.WAREHOUSE_STAFF, Identifiers.newId(), get(CONTACTS).param("customerIds",
                        quoteLast.toString()))
                .andExpect(status().isForbidden());
        http.anonymous(get(CONTACTS).param("customerIds", quoteLast.toString())).andExpect(status().isUnauthorized());
        String tooMany = java.util.stream.Stream.generate(Identifiers::newId).limit(201).map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
        http.asRole(Role.SALES_STAFF, salesA, get(CONTACTS).param("customerIds", tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
        http.asRole(Role.SALES_STAFF, salesA, get(CONTACTS).param("customerIds", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
