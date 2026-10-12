package com.stockflow.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import com.stockflow.catalog.internal.service.CustomerPriceService;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.ActiveSessionCheck;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.SessionValidator;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.LoginCommand;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-428 end to end with security ON on real PostgreSQL: a customer's own price by SKU and quantity
 * tier, managed by the e-commerce admin, shown on the portal, used at checkout and locked into the order,
 * and the starting price of a quote. The demo's published SOFA-3S-GREY has a base price of 12,500,000.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({PostgresContainer.class, RedisContainer.class, CustomerPricingIntegrationTest.LocalKeys.class})
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerPricingIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalKeys {
        @Bean
        @Primary
        JwtDecoder localTestDecoder(RSAKey key, ActiveSessionCheck sessions) throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefault(), new SessionValidator(sessions)));
            return decoder;
        }
    }

    private static final String SKU = ConfirmedDesigns.BLANK;
    private static final long BASE = 12_500_000;
    private static final String PASSWORD = "Pricing428Pass";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired IdentityService identities;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TransactionTemplate tx;
    @Autowired CustomerService customers;
    @Autowired CustomerPriceService prices;
    @Autowired ProductPublication publication;
    @Autowired Quotes quotes;
    @Autowired FileStorage storage;
    @Autowired EntityManager entityManager;

    private String admin;
    private String sales;
    private String warehouse;
    private UUID salesId;

    @BeforeAll
    void staff() {
        admin = token(account("ECOMMERCE_ADMIN"));
        UUID salesUser = account("SALES_STAFF");
        sales = token(salesUser);
        salesId = salesUser;
        warehouse = token(account("WAREHOUSE_STAFF"));
    }

    @Test
    void onlyTheEcommerceAdminEditsAndInputIsChecked() throws Exception {
        Buyer buyer = buyer("ACTIVE");
        String tiers = tiers(Map.of(1, 11_000_000L, 2, 10_000_000L));
        mvc.perform(post(path(buyer.customerId())).contentType("application/json").content(tiers))
                .andExpect(status().isUnauthorized());
        for (String caller : List.of(sales, warehouse, buyer.token())) {
            mvc.perform(post(path(buyer.customerId())).header("Authorization", "Bearer " + caller)
                    .contentType("application/json").content(tiers)).andExpect(status().isForbidden());
        }
        mvc.perform(get(path(buyer.customerId())).header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isForbidden());
        mvc.perform(get(path(buyer.customerId())).header("Authorization", "Bearer " + buyer.token()))
                .andExpect(status().isForbidden());

        createPrice(buyer.customerId(), tiers).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.moq").value(1)).andExpect(jsonPath("$.data.version").value(0));
        mvc.perform(get(path(buyer.customerId())).header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tiers[1].unitPrice").value(10_000_000));
        createPrice(buyer.customerId(), tiers).andExpect(status().isConflict());

        for (String invalid : List.of(
                "{\"tiers\":[]}",
                "{\"tiers\":[{\"minQuantity\":2,\"unitPrice\":5},{\"minQuantity\":2,\"unitPrice\":4}]}",
                "{\"tiers\":[{\"minQuantity\":1,\"unitPrice\":10.5}]}",
                "{\"tiers\":[{\"minQuantity\":1,\"unitPrice\":0}]}",
                "{\"tiers\":[{\"minQuantity\":0,\"unitPrice\":10}]}")) {
            createPrice(buyer(("ACTIVE")).customerId(), invalid).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/catalog/customer-prices/" + buyer.customerId() + "/NO-SUCH-SKU")
                        .header("Authorization", "Bearer " + admin).contentType("application/json").content(tiers))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("VARIANT_NOT_FOUND"));
        mvc.perform(post(path(UUID.randomUUID())).header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(tiers))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_FOUND"));

        mvc.perform(put(path(buyer.customerId())).header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(withVersion(tiers(Map.of(1, 9_000_000L)), 7)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type='customer-price' AND resource_id=?
                  AND action='CREATE' AND outcome='SUCCESS'""", Long.class, buyer.customerId().toString())).isEqualTo(1);
    }

    @Test
    void thePortalShowsTheCustomersOwnThenGroupThenBasePrice() throws Exception {
        Buyer own = buyer("ACTIVE");
        createPrice(own.customerId(), tiers(Map.of(2, 11_000_000L, 10, 10_000_000L))).andExpect(status().isCreated());
        portalPrices(own.token()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("CUSTOMER"))
                .andExpect(jsonPath("$.data[0].moq").value(2))
                .andExpect(jsonPath("$.data[0].tiers[1].minQuantity").value(10))
                .andExpect(jsonPath("$.data[0].tiers[1].unitPrice").value(10_000_000));

        Buyer grouped = buyer("ACTIVE");
        UUID segment = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO customer.segment(id, code, name, version, created_at) VALUES (?, ?, 'Dai ly', 0, now())",
                    segment, "SEG-" + segment.toString().substring(0, 8));
            jdbc.update("UPDATE customer.customer SET segment_id=? WHERE id=?", segment, grouped.customerId());
            jdbc.update("""
                    INSERT INTO catalog.pricing_rule(id, name, sku, segment_id, price, currency, priority, active, version, created_at)
                    VALUES (?, ?, ?, ?, 11500000, 'VND', 0, true, 0, now())""",
                    Identifiers.newId(), "SEGMENT:" + segment, SKU, segment);
        });
        portalPrices(grouped.token()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("SEGMENT"))
                .andExpect(jsonPath("$.data[0].tiers[0].unitPrice").value(11_500_000));

        Buyer plain = buyer("ACTIVE");
        portalPrices(plain.token()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("BASE"))
                .andExpect(jsonPath("$.data[0].moq").value(1))
                .andExpect(jsonPath("$.data[0].tiers[0].unitPrice").value(BASE));

        // Not a customer's view to give anyone else, and only an ACTIVE customer sees prices (BR-01a).
        Buyer blocked = buyer("ACTIVE");
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET status='BLOCKED' WHERE id=?",
                blocked.customerId()));
        portalPrices(blocked.token()).andExpect(status().isForbidden());
        portalPrices(sales).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/catalog/products/prices").param("sku", SKU)).andExpect(status().isUnauthorized());
    }

    @Test
    void checkoutChargesTheCustomersTierForTheSkusTotalAndLocksIt() throws Exception {
        Buyer buyer = buyer("ACTIVE");
        createPrice(buyer.customerId(), tiers(Map.of(2, 11_000_000L, 3, 10_000_000L))).andExpect(status().isCreated());

        order(buyer, List.of(line(1, 11_000_000L))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_PRICE_BELOW_MOQ"));
        order(buyer, List.of(line(2, BASE))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_PRICE_CHANGED"));
        String placed = order(buyer, List.of(line(2, 10_000_000L), line(1, 10_000_000L)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode order = mapper.readTree(placed).get("data");
        assertThat(order.at("/lines/0/unitPrice").decimalValue()).isEqualByComparingTo("10000000");
        assertThat(order.at("/lines/1/unitPrice").decimalValue()).isEqualByComparingTo("10000000");

        // BR-03: a later price change does not move the order.
        mvc.perform(put(path(buyer.customerId())).header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(withVersion(tiers(Map.of(1, 9_000_000L)), 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        assertThat(jdbc.queryForObject("SELECT DISTINCT unit_price FROM ordering.order_line WHERE order_id=?",
                BigDecimal.class, UUID.fromString(order.get("orderId").asText()))).isEqualByComparingTo("10000000");

        // Removing the own price sends the customer back to the base price.
        mvc.perform(delete(path(buyer.customerId())).param("version", "1").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        portalPrices(buyer.token()).andExpect(jsonPath("$.data[0].source").value("BASE"));
        order(buyer, List.of(line(1, BASE))).andExpect(status().isCreated());
    }

    @Test
    void concurrentEditorsAndCheckoutAreSerialised() throws Exception {
        Buyer buyer = buyer("ACTIVE");
        List<Object> creates = race(List.of(
                () -> prices.create(buyer.customerId(), SKU, List.of(tier(1, 11_000_000L)), null),
                () -> prices.create(buyer.customerId(), SKU, List.of(tier(1, 10_000_000L)), null)));
        assertThat(creates.stream().filter(CustomerPriceService.PriceView.class::isInstance)).hasSize(1);
        assertThat(creates.stream().filter(BusinessException.class::isInstance)
                .map(e -> ((BusinessException) e).errorCode())).containsExactly(ErrorCode.CONFLICT);

        List<Object> edits = race(List.of(
                () -> prices.replace(buyer.customerId(), SKU, List.of(tier(1, 9_000_000L)), null, 0),
                () -> prices.replace(buyer.customerId(), SKU, List.of(tier(1, 8_000_000L)), null, 0)));
        assertThat(edits.stream().filter(CustomerPriceService.PriceView.class::isInstance)).hasSize(1);
        assertThat(edits.stream().filter(BusinessException.class::isInstance)
                .map(e -> ((BusinessException) e).errorCode())).containsExactly(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM catalog.customer_price_tier t JOIN catalog.customer_price p ON p.id = t.customer_price_id
                WHERE p.customer_id = ?""", Long.class, buyer.customerId())).isEqualTo(1);

        // An edit waits for a checkout holding the product lock, so an order is priced wholly before or
        // wholly after it (BR-03), never from a half-replaced list.
        UUID product = jdbc.queryForObject("SELECT product_id FROM catalog.catalog_entry WHERE sku=? AND published",
                UUID.class, SKU);
        var pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> checkout = pool.submit(() -> tx.executeWithoutResult(s -> {
                publication.lock(product);
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long version = prices.get(buyer.customerId(), SKU).version();
            Future<CustomerPriceService.PriceView> edit = pool.submit(() ->
                    prices.replace(buyer.customerId(), SKU, List.of(tier(1, 7_000_000L)), null, version));
            Thread.sleep(1_000);
            assertThat(edit.isDone()).as("the edit waits for the product lock").isFalse();
            release.countDown();
            checkout.get(10, TimeUnit.SECONDS);
            assertThat(edit.get(10, TimeUnit.SECONDS).tiers().getFirst().unitPrice()).isEqualByComparingTo("7000000");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aQuoteStartsFromTheCustomersPriceAndKeepsIt() throws Exception {
        Buyer buyer = buyer("ACTIVE");
        createPrice(buyer.customerId(), tiers(Map.of(2, 11_000_000L, 5, 10_000_000L))).andExpect(status().isCreated());
        mvc.perform(get(path(buyer.customerId()) + "/schedule").header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("CUSTOMER"))
                .andExpect(jsonPath("$.data.moq").value(2));

        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager, buyer.customerId());
        CurrentUser salesUser = new CurrentUser(salesId, "sales", Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        Quotes.QuoteView quote = quotes.create(salesUser, new Quotes.Create(UUID.randomUUID(), buyer.customerId(),
                new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(SKU, design.snapshotId(), "Logo", 5, null)))));
        assertThat(quote.lines().getFirst().moq()).isEqualTo(2);
        assertThat(quote.lines().getFirst().tiers()).extracting(t -> t.minQuantity()).containsExactly(2, 5);

        prices.replace(buyer.customerId(), SKU, List.of(tier(1, 5_000_000L)), null, 0);
        Quotes.QuoteView reread = quotes.get(salesUser, quote.id());
        assertThat(reread.lines().getFirst().tiers()).extracting(t -> t.unitPrice().longValue())
                .containsExactly(11_000_000L, 10_000_000L);

        // Tiers Sales types in win over the list.
        Quotes.QuoteView typed = quotes.create(salesUser, new Quotes.Create(UUID.randomUUID(), buyer.customerId(),
                new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(SKU, design.snapshotId(), "Logo", 3,
                                List.of(new Quotes.Tier(1, new BigDecimal("12000000"))))))));
        assertThat(typed.lines().getFirst().tiers()).extracting(t -> t.unitPrice().longValue()).containsExactly(12_000_000L);
    }

    @Test
    void aQuoteLineWithoutTiersForAnUnpricedBlankAsksSalesToEnterThem() throws Exception {
        Buyer buyer = buyer("ACTIVE");
        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager, buyer.customerId());
        CurrentUser salesUser = new CurrentUser(salesId, "sales", Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        // No own price, no group, and - for this test only - no active base price either.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE catalog.pricing_rule SET active=false WHERE name=?", "BASE:" + SKU));
        try {
            Quotes.Create create = new Quotes.Create(UUID.randomUUID(), buyer.customerId(),
                    new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                            List.of(new Quotes.Line(SKU, design.snapshotId(), "Logo", 5, null))));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> quotes.create(salesUser, create))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(400);
                    });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.quote WHERE customer_id=?", Long.class,
                    buyer.customerId())).isZero();
        } finally {
            tx.executeWithoutResult(s -> jdbc.update("UPDATE catalog.pricing_rule SET active=true WHERE name=?", "BASE:" + SKU));
        }
    }

    /**
     * User decision of 2026-10-12, overriding kltn-docs 14 BR-10: no discount limit and no internal approval -
     * Sales negotiates and sends any valid price. Server-side validation, the quote's price snapshot, audit and
     * the customer's own acceptance all stay.
     */
    @Test
    void salesSendsANegotiatedPriceWithoutApprovalAndOnlyTheCustomerAccepts() throws Exception {
        Buyer buyer = buyer("ACTIVE");
        createPrice(buyer.customerId(), tiers(Map.of(1, 10_000_000L))).andExpect(status().isCreated());
        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager, buyer.customerId());
        CurrentUser salesUser = new CurrentUser(salesId, "sales", Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());

        // Invalid prices are refused on the server, whoever negotiated them.
        for (String invalid : List.of("0", "10.5", "-5")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> quotes.create(salesUser, quote(buyer, design, invalid)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        // 20% below the customer's list: created and sent at once, by Sales alone.
        Quotes.QuoteView created = quotes.create(salesUser, quote(buyer, design, "8000000"));
        assertThat(created.lines().getFirst().tiers()).extracting(t -> t.unitPrice().longValue()).containsExactly(8_000_000L);
        mvc.perform(post("/api/v1/quotes/" + created.id() + "/sending").header("Authorization", "Bearer " + warehouse)
                        .contentType("application/json").content("{\"version\":" + created.version() + "}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/quotes/" + created.id() + "/sending").header("Authorization", "Bearer " + sales)
                        .contentType("application/json").content("{\"version\":" + created.version() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SENT"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type='quote' AND resource_id=? AND outcome='SUCCESS'""",
                Long.class, created.id().toString())).isGreaterThanOrEqualTo(1);

        // Acceptance is still the customer's: not Sales, not another customer.
        long version = quotes.get(salesUser, created.id()).version();
        String acceptance = "{\"version\":" + version + "}";
        mvc.perform(post("/api/v1/quotes/" + created.id() + "/acceptance").header("Authorization", "Bearer " + sales)
                .contentType("application/json").content(acceptance)).andExpect(status().isForbidden());
        Buyer other = buyer("ACTIVE");
        mvc.perform(get("/api/v1/quotes/" + created.id()).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/quotes/" + created.id() + "/acceptance").header("Authorization", "Bearer " + other.token())
                .contentType("application/json").content(acceptance)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/quotes/" + created.id()).header("Authorization", "Bearer " + buyer.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.lines[0].tiers[0].unitPrice").value(8_000_000));

        // The quote keeps its negotiated price when the list changes (14 BR-03).
        prices.replace(buyer.customerId(), SKU, List.of(tier(1, 9_000_000L)), null, 0);
        assertThat(quotes.get(salesUser, created.id()).lines().getFirst().tiers())
                .extracting(t -> t.unitPrice().longValue()).containsExactly(8_000_000L);
    }

    private static Quotes.Create quote(Buyer buyer, ConfirmedDesigns.Design design, String unitPrice) {
        return new Quotes.Create(UUID.randomUUID(), buyer.customerId(),
                new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(SKU, design.snapshotId(), "Logo", 1,
                                List.of(new Quotes.Tier(1, new BigDecimal(unitPrice)))))));
    }

    // ---------------------------------------------------------------- fixtures

    private record Buyer(UUID customerId, String token) {
    }

    /** A customer with a portal account (password set), a default shipping address and this status. */
    private Buyer buyer(String status) {
        UUID user = Identifiers.newId();
        UUID customer = Identifiers.newId();
        String email = "buyer-" + user + "@example.test";
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.app_user(id, username, email, password_hash, full_name, status, version, created_at)
                    VALUES (?, ?, ?, ?, 'Buyer', 'ACTIVE', 0, now())""", user, email, email, passwordEncoder.encode(PASSWORD));
            jdbc.update("""
                    INSERT INTO identity.user_role(id, user_id, role_id, version, created_at)
                    SELECT ?, ?, id, 0, now() FROM identity.app_role WHERE code = 'CUSTOMER'""", Identifiers.newId(), user);
            jdbc.update("""
                    INSERT INTO customer.customer(id, user_id, full_name, email, phone, status, version, created_at)
                    VALUES (?, ?, 'Cong ty Mua Ly', ?, '0901234567', ?, 0, now())""", customer, user, email, status);
        });
        customers.addAddress(customer, null, true, new SaveAddressCommand("SHIPPING", "Nguyen Van A", "0901234567",
                "1 Le Loi", null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null, true, null));
        return new Buyer(customer, identities.login(new LoginCommand(email, PASSWORD, null, "scrum428")).accessToken());
    }

    private UUID account(String role) {
        UUID id = Identifiers.newId();
        String name = "staff-" + role.toLowerCase() + "-" + id;
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.app_user(id, username, email, password_hash, full_name, status, version, created_at)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, now())""",
                    id, name, name + "@example.test", passwordEncoder.encode(PASSWORD), role);
            jdbc.update("""
                    INSERT INTO identity.user_role(id, user_id, role_id, version, created_at)
                    SELECT ?, ?, id, 0, now() FROM identity.app_role WHERE code = ?""", Identifiers.newId(), id, role);
        });
        return id;
    }

    private String token(UUID userId) {
        String username = jdbc.queryForObject("SELECT username FROM identity.app_user WHERE id=?", String.class, userId);
        return identities.login(new LoginCommand(username, PASSWORD, null, "scrum428")).accessToken();
    }

    private static String path(UUID customerId) {
        return "/api/v1/catalog/customer-prices/" + customerId + "/" + SKU;
    }

    private ResultActions createPrice(UUID customerId, String body) throws Exception {
        return mvc.perform(post(path(customerId)).header("Authorization", "Bearer " + admin)
                .contentType("application/json").content(body));
    }

    private ResultActions portalPrices(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/catalog/products/prices").param("sku", SKU)
                .header("Authorization", "Bearer " + bearer));
    }

    private ResultActions order(Buyer buyer, List<Map<String, Object>> lines) throws Exception {
        return mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + buyer.token())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of(
                        "requestId", UUID.randomUUID(), "customerId", buyer.customerId(), "lines", lines))));
    }

    private static Map<String, Object> line(int quantity, long unitPrice) {
        return Map.of("sku", SKU, "quantity", quantity, "unitPrice", unitPrice);
    }

    private String tiers(Map<Integer, Long> tiers) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        tiers.forEach((min, price) -> rows.add(Map.of("minQuantity", min, "unitPrice", price)));
        return mapper.writeValueAsString(Map.of("tiers", rows));
    }

    private String withVersion(String body, long version) throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(body);
        node.put("version", version);
        return mapper.writeValueAsString(node);
    }

    private static CustomerPriceService.TierInput tier(int min, long price) {
        return new CustomerPriceService.TierInput(min, BigDecimal.valueOf(price));
    }

    private static List<Object> race(List<Callable<Object>> attempts) throws Exception {
        var pool = Executors.newFixedThreadPool(attempts.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> attempt : attempts) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return attempt.call();
                    } catch (BusinessException refused) {
                        return refused;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) outcomes.add(future.get(30, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
