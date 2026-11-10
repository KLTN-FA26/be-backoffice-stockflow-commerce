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
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.LoginCommand;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.CustomerSkuRows;
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
 * SCRUM-428 end to end with security ON on real PostgreSQL, since 4B-2 (kltn-docs 13 BR-02, BR-05, BR-06, BR-10): a
 * customer's own price is set for one of its own SKUs, by tiers of cartons and a price per piece, by the Sales Manager;
 * Sales reads it and a quote starts from it; the customer never sees it, and checkout never charges it. Group and base
 * prices stay the storefront's, on the demo's published SOFA-3S-GREY (base price 12,500,000).
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

    /** A published stock variant: group and base prices, checkout. Not a SKU a customer's own price is set for. */
    private static final String BLANK = ConfirmedDesigns.BLANK;
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
    @Autowired com.stockflow.catalog.internal.service.SegmentPriceService groupPrices;
    @Autowired Quotes quotes;
    @Autowired EntityManager entityManager;

    private String admin;
    private String sales;
    private String warehouse;
    private UUID salesId;

    @BeforeAll
    void staff() {
        admin = token(account("SALES_MANAGER"));
        UUID salesUser = account("SALES_STAFF");
        sales = token(salesUser);
        salesId = salesUser;
        warehouse = token(account("WAREHOUSE_STAFF"));
    }

    @Test
    void onlyTheSalesManagerSetsAPriceOnTheCustomersOwnSkuAndInputIsChecked() throws Exception {
        Buyer buyer = buyer("ACTIVE");
        String sku = ownSku(buyer);
        String tiers = tiers(Map.of(1, 11_000L, 2, 10_000L));
        mvc.perform(post(path(buyer.customerId(), sku)).contentType("application/json").content(tiers))
                .andExpect(status().isUnauthorized());
        for (String caller : List.of(sales, warehouse, buyer.token())) {
            mvc.perform(post(path(buyer.customerId(), sku)).header("Authorization", "Bearer " + caller)
                    .contentType("application/json").content(tiers)).andExpect(status().isForbidden());
        }
        mvc.perform(get(path(buyer.customerId(), sku)).header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isForbidden());
        // 13 BR-10: never the customer's to read.
        mvc.perform(get(path(buyer.customerId(), sku)).header("Authorization", "Bearer " + buyer.token()))
                .andExpect(status().isForbidden());

        createPrice(buyer.customerId(), sku, tiers).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.measure").value("CARTON"))
                .andExpect(jsonPath("$.data.moq").value(1)).andExpect(jsonPath("$.data.version").value(0));
        mvc.perform(get(path(buyer.customerId(), sku)).header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tiers[1].unitPrice").value(10_000));
        createPrice(buyer.customerId(), sku, tiers).andExpect(status().isConflict());

        for (String invalid : List.of(
                "{\"tiers\":[]}",
                "{\"tiers\":[{\"minQuantity\":2,\"unitPrice\":5},{\"minQuantity\":2,\"unitPrice\":4}]}",
                "{\"tiers\":[{\"minQuantity\":1,\"unitPrice\":10.5}]}",
                "{\"tiers\":[{\"minQuantity\":1,\"unitPrice\":0}]}",
                "{\"tiers\":[{\"minQuantity\":0,\"unitPrice\":10}]}")) {
            Buyer fresh = buyer("ACTIVE");
            createPrice(fresh.customerId(), ownSku(fresh), invalid).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/catalog/customer-prices/" + buyer.customerId() + "/NO-SUCH-SKU")
                        .header("Authorization", "Bearer " + admin).contentType("application/json").content(tiers))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("VARIANT_NOT_FOUND"));
        mvc.perform(post(path(UUID.randomUUID(), sku)).header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(tiers))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_FOUND"));
        // Not on a stock variant (13 BR-05: the customer's own SKUs), and another customer's SKU is not there at all.
        createPrice(buyer.customerId(), BLANK, tiers).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_SKU_REQUIRED"));
        Buyer other = buyer("ACTIVE");
        createPrice(other.customerId(), sku, tiers).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("VARIANT_NOT_FOUND"));

        mvc.perform(put(path(buyer.customerId(), sku)).header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content(withVersion(tiers(Map.of(1, 9_000L)), 7)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type='customer-price' AND resource_id=?
                  AND action='CREATE' AND outcome='SUCCESS'""", Long.class, buyer.customerId().toString())).isEqualTo(1);
    }

    @Test
    void theStorefrontShowsGroupThenBasePriceAndNeverAnInternalOne() throws Exception {
        Buyer own = buyer("ACTIVE");
        String sku = ownSku(own);
        createPrice(own.customerId(), sku, tiers(Map.of(2, 11_000L, 10, 10_000L))).andExpect(status().isCreated());
        portalPrices(own.token(), sku).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        portalPrices(own.token(), BLANK).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("BASE"));

        Buyer grouped = buyer("ACTIVE");
        UUID segment = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO customer.segment(id, code, name, version, created_at) VALUES (?, ?, 'Dai ly', 0, now())",
                    segment, ("SEG-" + segment).toUpperCase());
            jdbc.update("UPDATE customer.customer SET segment_id=? WHERE id=?", segment, grouped.customerId());
            jdbc.update("""
                    INSERT INTO catalog.pricing_rule(id, name, sku, segment_id, price, currency, priority, active, version, created_at)
                    VALUES (?, ?, ?, ?, 11500000, 'VND', 0, true, 0, now())""",
                    Identifiers.newId(), "SEGMENT:" + segment, BLANK, segment);
        });
        portalPrices(grouped.token(), BLANK).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("SEGMENT"))
                .andExpect(jsonPath("$.data[0].tiers[0].unitPrice").value(11_500_000));

        Buyer plain = buyer("ACTIVE");
        portalPrices(plain.token(), BLANK).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("BASE"))
                .andExpect(jsonPath("$.data[0].moq").value(1))
                .andExpect(jsonPath("$.data[0].tiers[0].unitPrice").value(BASE));

        // Not a customer's view to give anyone else, and only an ACTIVE customer sees prices (BR-01a).
        Buyer blocked = buyer("ACTIVE");
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET status='BLOCKED' WHERE id=?",
                blocked.customerId()));
        portalPrices(blocked.token(), BLANK).andExpect(status().isForbidden());
        portalPrices(sales, BLANK).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/catalog/products/prices").param("sku", BLANK)).andExpect(status().isUnauthorized());
    }

    @Test
    void checkoutNeverChargesAPriceOfTheCustomersOwn() throws Exception {
        Buyer buyer = buyer("ACTIVE");
        String sku = ownSku(buyer);
        createPrice(buyer.customerId(), sku, tiers(Map.of(1, 9_000L))).andExpect(status().isCreated());

        // The customer's own SKU is quoted (14 BR-01, BR-08), not bought at checkout.
        order(buyer, List.of(line(sku, 10, 9_000L))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_PURCHASABLE"));
        // A stock variant is charged its base price, as the storefront shows it.
        order(buyer, List.of(line(BLANK, 1, 9_000L))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_PRICE_CHANGED"));
        String placed = order(buyer, List.of(line(BLANK, 1, BASE))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode order = mapper.readTree(placed).get("data");
        assertThat(order.at("/lines/0/unitPrice").decimalValue()).isEqualByComparingTo(String.valueOf(BASE));
    }

    @Test
    void concurrentEditorsAreSerialised() throws Exception {
        Buyer buyer = buyer("ACTIVE");
        String sku = ownSku(buyer);
        List<Object> creates = race(List.of(
                () -> prices.create(buyer.customerId(), sku, List.of(tier(1, 11_000L)), null),
                () -> prices.create(buyer.customerId(), sku, List.of(tier(1, 10_000L)), null)));
        assertThat(creates.stream().filter(CustomerPriceService.PriceView.class::isInstance)).hasSize(1);
        assertThat(creates.stream().filter(BusinessException.class::isInstance)
                .map(e -> ((BusinessException) e).errorCode())).containsExactly(ErrorCode.CONFLICT);

        List<Object> edits = race(List.of(
                () -> prices.replace(buyer.customerId(), sku, List.of(tier(1, 9_000L)), null, 0),
                () -> prices.replace(buyer.customerId(), sku, List.of(tier(1, 8_000L)), null, 0)));
        assertThat(edits.stream().filter(CustomerPriceService.PriceView.class::isInstance)).hasSize(1);
        assertThat(edits.stream().filter(BusinessException.class::isInstance)
                .map(e -> ((BusinessException) e).errorCode())).containsExactly(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM catalog.customer_price_tier t JOIN catalog.customer_price p ON p.id = t.customer_price_id
                WHERE p.customer_id = ?""", Long.class, buyer.customerId())).isEqualTo(1);
    }

    @Test
    void aQuoteStartsFromTheCustomersPriceAndKeepsIt() throws Exception {
        Buyer buyer = buyer("ACTIVE");
        String sku = ownSku(buyer);
        createPrice(buyer.customerId(), sku, tiers(Map.of(2, 11_000L, 5, 10_000L))).andExpect(status().isCreated());
        mvc.perform(get(path(buyer.customerId(), sku) + "/schedule").header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("CUSTOMER"))
                .andExpect(jsonPath("$.data.moq").value(2));

        CurrentUser salesUser = salesUser();
        Quotes.QuoteView quote = quotes.create(salesUser, new Quotes.Create(UUID.randomUUID(), buyer.customerId(),
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(sku, 5, null)))));
        assertThat(quote.lines().getFirst().moq()).as("2 cartons").isEqualTo(2);
        assertThat(quote.lines().getFirst().tiers()).extracting(t -> t.minQuantity()).containsExactly(2, 5);
        assertThat(quote.lines().getFirst().proposedUnitPrice()).isEqualByComparingTo("10000");

        prices.replace(buyer.customerId(), sku, List.of(tier(1, 5_000L)), null, 0);
        Quotes.QuoteView reread = quotes.get(salesUser, quote.id());
        assertThat(reread.lines().getFirst().tiers()).extracting(t -> t.unitPrice().longValue())
                .containsExactly(11_000L, 10_000L);

        // Tiers Sales types in win over the customer's price.
        Quotes.QuoteView typed = quotes.create(salesUser, new Quotes.Create(UUID.randomUUID(), buyer.customerId(),
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(sku, 3, List.of(new Quotes.Tier(1, new BigDecimal("12000"))))))));
        assertThat(typed.lines().getFirst().tiers()).extracting(t -> t.unitPrice().longValue()).containsExactly(12_000L);
    }

    @Test
    void aQuoteLineWithoutTiersForASkuWithoutAPriceOfTheCustomersOwnAsksSalesToEnterThem() throws Exception {
        Buyer buyer = buyer("ACTIVE");
        String sku = ownSku(buyer);
        Quotes.Create create = new Quotes.Create(UUID.randomUUID(), buyer.customerId(),
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(sku, 5, null))));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> quotes.create(salesUser(), create))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.errorCode().httpStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("enter the line's price tiers");
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.quote WHERE customer_id=?", Long.class,
                buyer.customerId())).isZero();
    }

    /**
     * User decision of 2026-10-12, overriding kltn-docs 14 BR-10: no discount limit and no internal approval -
     * Sales negotiates and sends any valid price. Server-side validation, the quote's price snapshot, audit and
     * the customer's own acceptance all stay.
     */
    @Test
    void salesSendsANegotiatedPriceWithoutApprovalAndOnlyTheCustomerAccepts() throws Exception {
        Buyer buyer = buyer("ACTIVE");
        String sku = ownSku(buyer);
        createPrice(buyer.customerId(), sku, tiers(Map.of(1, 10_000L))).andExpect(status().isCreated());
        CurrentUser salesUser = salesUser();

        // Invalid prices are refused on the server, whoever negotiated them.
        for (String invalid : List.of("0", "10.5", "-5")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> quotes.create(salesUser, quote(buyer, sku, invalid)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        // 20% below the customer's price: created and sent at once, by Sales alone.
        Quotes.QuoteView created = quotes.create(salesUser, quote(buyer, sku, "8000"));
        assertThat(created.lines().getFirst().tiers()).extracting(t -> t.unitPrice().longValue()).containsExactly(8_000L);
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
                .andExpect(jsonPath("$.data.lines[0].measure").value("CARTON"))
                .andExpect(jsonPath("$.data.lines[0].unitsPerCarton").value(10))
                .andExpect(jsonPath("$.data.lines[0].tiers[0].unitPrice").value(8_000));

        // The quote keeps its negotiated price when the customer's price changes (14 BR-06).
        prices.replace(buyer.customerId(), sku, List.of(tier(1, 9_000L)), null, 0);
        assertThat(quotes.get(salesUser, created.id()).lines().getFirst().tiers())
                .extracting(t -> t.unitPrice().longValue()).containsExactly(8_000L);
    }

    private static Quotes.Create quote(Buyer buyer, String sku, String unitPrice) {
        return new Quotes.Create(UUID.randomUUID(), buyer.customerId(),
                new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null,
                        List.of(new Quotes.Line(sku, 1, List.of(new Quotes.Tier(1, new BigDecimal(unitPrice)))))));
    }

    /**
     * P7 (user decision 2026-10-12): Sales Manager and System Admin manage customer-group prices; Sales reads
     * and negotiates per quote; the storefront resolves group price -> base price.
     */
    @Test
    void groupPricesAreTheAdminsAndResolveBeforeTheBase() throws Exception {
        UUID segment = Identifiers.newId();
        Buyer grouped = buyer("ACTIVE");
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO customer.segment(id, code, name, version, created_at) VALUES (?, ?, 'Nha phan phoi', 0, now())",
                    segment, ("SEG-" + segment).toUpperCase());
            jdbc.update("UPDATE customer.customer SET segment_id=? WHERE id=?", segment, grouped.customerId());
        });
        String path = "/api/v1/catalog/segment-prices/" + segment + "/" + BLANK;
        String systemAdmin = token(account("SYSTEM_ADMIN"));

        mvc.perform(post(path).contentType("application/json").content("{\"price\":11000000}"))
                .andExpect(status().isUnauthorized());
        for (String caller : List.of(sales, warehouse, grouped.token())) {
            mvc.perform(post(path).header("Authorization", "Bearer " + caller).contentType("application/json")
                    .content("{\"price\":11000000}")).andExpect(status().isForbidden());
        }
        for (String invalid : List.of("{\"price\":0}", "{\"price\":10.5}", "{\"price\":-1}", "{}")) {
            mvc.perform(post(path).header("Authorization", "Bearer " + admin).contentType("application/json")
                    .content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/catalog/segment-prices/" + UUID.randomUUID() + "/" + BLANK)
                        .header("Authorization", "Bearer " + admin).contentType("application/json")
                        .content("{\"price\":11000000}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("SEGMENT_NOT_FOUND"));
        mvc.perform(post("/api/v1/catalog/segment-prices/" + segment + "/NO-SUCH-SKU")
                        .header("Authorization", "Bearer " + admin).contentType("application/json")
                        .content("{\"price\":11000000}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("VARIANT_NOT_FOUND"));

        mvc.perform(post(path).header("Authorization", "Bearer " + admin).contentType("application/json")
                        .content("{\"price\":11000000}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.price").value(11000000))
                .andExpect(jsonPath("$.data.version").value(0));
        mvc.perform(post(path).header("Authorization", "Bearer " + admin).contentType("application/json")
                .content("{\"price\":10000000}")).andExpect(status().isConflict());
        portalPrices(grouped.token(), BLANK).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("SEGMENT"))
                .andExpect(jsonPath("$.data[0].tiers[0].unitPrice").value(11000000));

        // Sales reads, never edits; the coordinator reads; warehouse and the customer do not read here.
        mvc.perform(get(path).header("Authorization", "Bearer " + sales)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/catalog/segment-prices").param("segmentId", segment.toString())
                .header("Authorization", "Bearer " + token(account("ORDER_COORDINATOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(path).header("Authorization", "Bearer " + warehouse)).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", "Bearer " + grouped.token())).andExpect(status().isForbidden());
        mvc.perform(put(path).header("Authorization", "Bearer " + sales).contentType("application/json")
                .content("{\"price\":9000000,\"version\":0}")).andExpect(status().isForbidden());
        mvc.perform(delete(path).param("version", "0").header("Authorization", "Bearer " + sales))
                .andExpect(status().isForbidden());

        // Replace with the version read; a stale or missing version is refused.
        mvc.perform(put(path).header("Authorization", "Bearer " + systemAdmin).contentType("application/json")
                        .content("{\"price\":10500000,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(put(path).header("Authorization", "Bearer " + admin).contentType("application/json")
                        .content("{\"price\":10400000,\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        mvc.perform(put(path).header("Authorization", "Bearer " + admin).contentType("application/json")
                .content("{\"price\":10400000}")).andExpect(status().isBadRequest());
        portalPrices(grouped.token(), BLANK).andExpect(jsonPath("$.data[0].tiers[0].unitPrice").value(10500000));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type='segment-price' AND resource_id=?
                   AND outcome='SUCCESS'""", Long.class, segment.toString())).isEqualTo(2);

        // A price of the customer's own is not set on the stock variant: the group's keeps applying.
        createPrice(grouped.customerId(), BLANK, tiers(Map.of(1, 9_900_000L))).andExpect(status().isConflict());
        portalPrices(grouped.token(), BLANK).andExpect(jsonPath("$.data[0].source").value("SEGMENT"));

        // Removed: the group's customers fall back to the base price.
        mvc.perform(delete(path).param("version", "1").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization", "Bearer " + sales))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("SEGMENT_PRICE_NOT_FOUND"));
        portalPrices(grouped.token(), BLANK).andExpect(jsonPath("$.data[0].source").value("BASE"))
                .andExpect(jsonPath("$.data[0].tiers[0].unitPrice").value(BASE));

        // Two editors at once: one wins, the other is told.
        List<Object> creates = race(List.of(
                () -> groupPrices.create(segment, BLANK, new BigDecimal("11000000")),
                () -> groupPrices.create(segment, BLANK, new BigDecimal("10000000"))));
        assertThat(creates.stream().filter(BusinessException.class::isInstance)
                .map(e -> ((BusinessException) e).errorCode())).containsExactly(ErrorCode.CONFLICT);
        List<Object> edits = race(List.of(
                () -> groupPrices.replace(segment, BLANK, new BigDecimal("9000000"), 0),
                () -> groupPrices.replace(segment, BLANK, new BigDecimal("8000000"), 0)));
        assertThat(edits.stream().filter(BusinessException.class::isInstance)
                .map(e -> ((BusinessException) e).errorCode())).containsExactly(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.pricing_rule WHERE segment_id = ? AND sku = ?",
                Long.class, segment, BLANK)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- fixtures

    private record Buyer(UUID customerId, String token) {
    }

    private CurrentUser salesUser() {
        return new CurrentUser(salesId, "sales", Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL, Set.of(), Set.of());
    }

    /** One of the buyer's own SKUs, sold in cartons of 10 (4B-2). */
    private String ownSku(Buyer buyer) {
        return CustomerSkuRows.issue(jdbc, tx, entityManager, buyer.customerId(), 10).sku();
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

    private static String path(UUID customerId, String sku) {
        return "/api/v1/catalog/customer-prices/" + customerId + "/" + sku;
    }

    private ResultActions createPrice(UUID customerId, String sku, String body) throws Exception {
        return mvc.perform(post(path(customerId, sku)).header("Authorization", "Bearer " + admin)
                .contentType("application/json").content(body));
    }

    private ResultActions portalPrices(String bearer, String sku) throws Exception {
        return mvc.perform(get("/api/v1/catalog/products/prices").param("sku", sku)
                .header("Authorization", "Bearer " + bearer));
    }

    private ResultActions order(Buyer buyer, List<Map<String, Object>> lines) throws Exception {
        return mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + buyer.token())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of(
                        "requestId", UUID.randomUUID(), "customerId", buyer.customerId(), "lines", lines))));
    }

    private static Map<String, Object> line(String sku, int quantity, long unitPrice) {
        return Map.of("sku", sku, "quantity", quantity, "unitPrice", unitPrice);
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
