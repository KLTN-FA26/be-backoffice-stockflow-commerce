package com.stockflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.idempotency.IdempotencyKeys;
import com.stockflow.common.idempotency.IdempotencyStore;
import com.stockflow.common.security.ActiveSessionCheck;
import com.stockflow.common.security.SessionValidator;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.LoginCommand;
import com.stockflow.identity.api.RegisterAccountCommand;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real migrations, JWT signatures, sessions, role lookup and permission aspects; no schema substitutes. */
@IntegrationTest
@AutoConfigureMockMvc
@Import({PostgresContainer.class, RedisContainer.class, B2bAccessIntegrationTest.LocalKeys.class})
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.idempotency.enabled=true",
        "stockflow.checkout.guest-enabled=true"}) // A stale deployment toggle cannot restore B2C.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class B2bAccessIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityService identities;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired IdempotencyStore idempotency;
    @Autowired Clock clock;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    private static final String PASSWORD = "B2bTestPass1!";
    private static final String AVAILABILITY = "/api/v1/catalog/products/availability";
    private Account alice;
    private Account bob;
    private Account blocked;
    private Account unlinked;
    private Account sales;
    private Account warehouse;
    private Account admin;

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalKeys {
        /** Keep signature + expiry + session validation, without a network call to localhost:8080. */
        @Bean
        @Primary
        JwtDecoder localTestDecoder(RSAKey key, ActiveSessionCheck sessions) throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefault(), new SessionValidator(sessions)));
            return decoder;
        }
    }

    @BeforeAll
    void accounts() {
        alice = account("alice", "CUSTOMER", "ACTIVE");
        bob = account("bob", "CUSTOMER", "ACTIVE");
        blocked = account("blocked", "CUSTOMER", "BLOCKED");
        unlinked = account("unlinked", "CUSTOMER", null);
        sales = account("sales", "SALES_STAFF", null);
        warehouse = account("warehouse", "WAREHOUSE_STAFF", null);
        admin = account("admin", "SYSTEM_ADMIN", null);
    }

    private Account account(String name, String role, String customerStatus) {
        String email = name + ".scrum429@example.test";
        // Trusted identity API used ONLY to seed this isolated database, not an HTTP registration.
        var registered = identities.registerCustomer(new RegisterAccountCommand(email, PASSWORD, name));
        if (!"CUSTOMER".equals(role)) {
            identities.assignRole(registered.userId(), role);
            identities.revokeRole(registered.userId(), "CUSTOMER");
        }
        UUID customerId = null;
        if (customerStatus != null) {
            customerId = Identifiers.newId();
            UUID profileId = customerId;
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> jdbc.update("""
                    INSERT INTO customer.customer(id,user_id,full_name,email,status,version,created_at)
                    VALUES (?,?,?,?,?,0,now())
                    """, profileId, registered.userId(), name, email, customerStatus));
        }
        String token = identities.login(new LoginCommand(email, PASSWORD, null, "scrum429-test")).accessToken();
        return new Account(registered.userId(), customerId, email, token);
    }

    @Test
    void retiredRoutesHaveNoHandlersAndCannotCreateOrReplayEvenWithTheOldToggle() throws Exception {
        long users = count("identity.app_user");
        long customers = count("customer.customer");
        long orders = count("ordering.customer_order");
        for (String path : List.of("/api/v1/customers/registrations", "/api/v1/orders/guest-checkout")) {
            assertThat(mappings.getHandlerMethods().keySet().stream()
                    .flatMap(mapping -> mapping.getPatternValues().stream())).doesNotContain(path);
            mvc.perform(post(path).contentType("application/json").content("{}")
                            .header("Idempotency-Key", "retired-route-retry"))
                    .andExpect(status().isUnauthorized());
            for (Account caller : List.of(alice, sales, warehouse, admin)) {
                mvc.perform(post(path).header("Authorization", bearer(caller))
                                .header("Idempotency-Key", "retired-route-retry")
                                .contentType("application/json").content("{}"))
                        .andExpect(status().isForbidden());
            }
        }
        assertThat(count("identity.app_user")).isEqualTo(users);
        assertThat(count("customer.customer")).isEqualTo(customers);
        assertThat(count("ordering.customer_order")).isEqualTo(orders);
    }

    @Test
    void availabilityRequiresAnActiveLinkedCustomerAndHasNoPublicAlias() throws Exception {
        mvc.perform(get(AVAILABILITY).param("sku", "TABLE-OAK"))
                .andExpect(status().isUnauthorized());
        for (Account caller : List.of(blocked, unlinked, sales, warehouse, admin)) {
            mvc.perform(get(AVAILABILITY).param("sku", "TABLE-OAK")
                            .param("customerId", alice.customerId().toString())
                            .header("Authorization", bearer(caller)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(AVAILABILITY).param("sku", "TABLE-OAK").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=10, private"))
                .andExpect(jsonPath("$.data[0].sku").value("TABLE-OAK"))
                .andExpect(jsonPath("$.data[0].quantity").doesNotExist())
                .andExpect(jsonPath("$.data[0].price").doesNotExist());
        mvc.perform(get("/api/v1/public/products/availability").param("sku", "TABLE-OAK"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/public/products/availability").param("sku", "TABLE-OAK")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotReadOrChangeAnotherCustomersProfileOrAddresses() throws Exception {
        mvc.perform(get("/api/v1/customers/me").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(alice.customerId().toString()));
        mvc.perform(get("/api/v1/customers/" + alice.customerId() + "/addresses")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/customers/" + bob.customerId() + "/addresses")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/customers/" + bob.customerId() + "/addresses")
                        .header("Authorization", bearer(alice)).contentType("application/json").content("""
                                {"type":"SHIPPING","recipientName":"Alice","phone":"0901234567",
                                 "line1":"12 Nguyen Hue","wardCode":"26734","wardName":"Ben Nghe",
                                 "provinceCode":"79","provinceName":"HCM","countryCode":"VN"}
                                """))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/customers/" + bob.customerId()).header("Authorization", bearer(alice)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/customers/" + bob.customerId()).header("Authorization", bearer(alice))
                        .contentType("application/json").content("{\"fullName\":\"Changed\",\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/customers").header("Authorization", bearer(alice)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT full_name FROM customer.customer WHERE id=?",
                String.class, bob.customerId())).isEqualTo("bob");
    }

    @Test
    void staffBackofficeAccessAndExistingLoginRemainAvailable() throws Exception {
        for (Account caller : List.of(alice, sales)) {
            mvc.perform(post("/api/v1/identity/auth/login").contentType("application/json")
                            .content(mapper.writeValueAsString(Map.of("username", caller.email(), "password", PASSWORD))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").isNotEmpty());
        }
        mvc.perform(get("/api/v1/customers").header("Authorization", bearer(sales)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/customers").header("Authorization", bearer(warehouse)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/inventory/stock-items").param("sku", "TABLE-OAK")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void retiredRoutesCannotReplayASuccessStoredBeforeTheB2bCutover() throws Exception {
        for (String path : List.of("/api/v1/customers/registrations", "/api/v1/orders/guest-checkout")) {
            String key = "before-b2b-" + UUID.randomUUID();
            var now = clock.instant();
            String fingerprint = IdempotencyKeys.fingerprint("POST", path, "{}".getBytes(StandardCharsets.UTF_8));
            for (String caller : List.of("anonymous", alice.userId().toString())) {
                assertThat(idempotency.beginIfAbsent(key, caller, fingerprint, now, now.plusSeconds(3600))).isEmpty();
                idempotency.complete(key, caller, 201, "{\"success\":true}", now);
            }
            mvc.perform(post(path).header("Idempotency-Key", key).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Idempotency-Replayed"));
            mvc.perform(post(path).header("Idempotency-Key", key).header("Authorization", bearer(alice))
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Idempotency-Replayed"));
        }
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private static String bearer(Account account) { return "Bearer " + account.token(); }
    private record Account(UUID userId, UUID customerId, String email, String token) { }
}
