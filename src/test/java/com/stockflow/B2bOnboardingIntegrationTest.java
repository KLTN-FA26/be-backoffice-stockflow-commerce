package com.stockflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.ActiveSessionCheck;
import com.stockflow.common.security.SessionValidator;
import com.stockflow.customer.internal.service.CustomerOnboardingService;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.LoginCommand;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-450 end to end with security ON: real migrations and constraints in PostgreSQL, real JWT
 * sessions and permission checks, real SMTP into a throwaway Mailpit container. The activation link
 * is read out of the delivered e-mail, exactly as a customer would receive it. No address used here
 * can leave the container: every recipient is {@code @example.test}.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({PostgresContainer.class, RedisContainer.class, B2bOnboardingIntegrationTest.LocalKeys.class})
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.idempotency.enabled=true",
        "stockflow.customer.portal-activation-url=https://portal.example.test/activate",
        "spring.mail.properties.mail.smtp.connectiontimeout=2000",
        "spring.mail.properties.mail.smtp.timeout=2000",
        // JavaMail otherwise resolves this machine's name for EHLO, which can take many seconds.
        "spring.mail.properties.mail.smtp.localhost=localhost"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class B2bOnboardingIntegrationTest {

    @SuppressWarnings("resource") // Ryuk removes it when the JVM exits.
    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:latest")
            .withExposedPorts(1025, 8025);

    static {
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", B2bOnboardingIntegrationTest::mailHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalKeys {
        /** Signature, expiry and session checks without a network call for the key set. */
        @Bean
        @Primary
        JwtDecoder localTestDecoder(RSAKey key, ActiveSessionCheck sessions) throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefault(), new SessionValidator(sessions)));
            return decoder;
        }
    }

    private static final String PASSWORD = "Onboard450Pass";
    private static final Pattern LINK = Pattern.compile(
            "https://portal\\.example\\.test/activate#token=([A-Za-z0-9_-]{64})");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired IdentityService identities;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CustomerOnboardingService onboarding;

    private final HttpClient http = HttpClient.newHttpClient();
    private String sales;
    private String ecommerceAdmin;
    private String warehouse;
    private String systemAdmin;

    @BeforeAll
    void staff() {
        ecommerceAdmin = staffToken("ECOMMERCE_ADMIN");
        warehouse = staffToken("WAREHOUSE_STAFF");
        systemAdmin = staffToken("SYSTEM_ADMIN");
    }

    @AfterAll
    void stopMail() {
        MAILPIT.stop();
    }

    /**
     * Creating a customer is rate-limited per user (30 a minute), and this class creates more than that in under a
     * minute. Each test gets its own Sales user, so the limit stays real but the tests do not share one bucket - the
     * same reason {@link #anotherClient} gives each login its own address.
     */
    @BeforeEach
    void salesUser() {
        sales = staffToken("SALES_STAFF");
    }

    @BeforeEach
    void emptyInbox() throws Exception {
        http.send(HttpRequest.newBuilder(mailpit("/api/v1/messages")).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void salesCreatesTheCustomerAndTheContactActivatesOnceFromTheEmailedLink() throws Exception {
        String email = unique("buyer");
        String mst = mst();
        long users = count("identity.app_user");
        String body = json(Map.of("companyName", "Cong ty TNHH Ly Giay Sai Gon", "taxCode", mst + "001",
                "contactName", "Nguyen Van An", "email", email.toUpperCase(), "phone", "0901234567"));
        String key = "create-" + UUID.randomUUID();

        String created = create(sales, body, key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.customer.fullName").value("Cong ty TNHH Ly Giay Sai Gon"))
                .andExpect(jsonPath("$.data.customer.email").value(email))
                // Review of #109, item 9: masked in the invitation response; the staff detail shows it whole.
                .andExpect(jsonPath("$.data.customer.taxCode").value(masked(mst + "-001")))
                .andExpect(jsonPath("$.data.customer.status").value("PENDING_ACTIVATION"))
                .andExpect(jsonPath("$.data.invitation.delivery").value("SENT"))
                .andExpect(jsonPath("$.data.invitation.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        UUID customerId = UUID.fromString(mapper.readTree(created).at("/data/customer/customerId").asText());
        UUID userId = UUID.fromString(mapper.readTree(created).at("/data/customer/userId").asText());

        // The same request retried with its key is answered from the stored outcome: no second
        // customer, account or e-mail.
        create(sales, body, key)
                .andExpect(status().isCreated()).andExpect(header().exists("Idempotency-Replayed"));
        assertThat(count("identity.app_user")).isEqualTo(users + 1);

        String token = onlyLinkSentTo(email);
        assertThat(created).doesNotContain(token);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM identity.app_user WHERE id=?",
                String.class, userId)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT token_digest FROM identity.portal_invitation
                WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL AND delivery_status='SENT'
                  AND issued_by IS NOT NULL""", String.class, userId))
                .isEqualTo(sha256(token)).isNotEqualTo(token);
        assertThat(jdbc.queryForObject("""
                SELECT r.code FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                WHERE ur.user_id = ?""", String.class, userId)).isEqualTo("CUSTOMER");
        invitationState(customerId).andExpect(jsonPath("$.data.state").value("PENDING"))
                .andExpect(jsonPath("$.data.delivery").value("SENT"));

        // No password exists before activation, so nothing - not even a guessed one - signs in.
        login(email, PASSWORD).andExpect(status().isUnauthorized());

        activate(token, "short").andExpect(status().isBadRequest());
        activate(token, "weakpassword1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        assertThat(customerStatus(customerId)).isEqualTo("PENDING_ACTIVATION");

        activate(token, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(email));
        assertThat(customerStatus(customerId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM identity.app_user WHERE id=?",
                String.class, userId)).startsWith("{bcrypt}");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM identity.portal_invitation WHERE user_id=? AND consumed_at IS NOT NULL""",
                Long.class, userId)).isEqualTo(1);

        activate(token, PASSWORD + "2").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PORTAL_INVITATION_INVALID"));

        String customerToken = mapper.readTree(login(email, PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).at("/data/accessToken").asText();
        mvc.perform(get("/api/v1/customers/me").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mvc.perform(get("/api/v1/catalog/products/availability").param("sku", "TABLE-OAK")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log
                WHERE action='CREATE' AND resource_type='customer' AND resource_id=? AND outcome='SUCCESS'""",
                Long.class, customerId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.audit_log WHERE details LIKE ? OR resource_id LIKE ?",
                Long.class, "%" + token + "%", "%" + token + "%")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.idempotency_record WHERE response_body LIKE ?",
                Long.class, "%" + token + "%")).isZero();

        invitationState(customerId).andExpect(jsonPath("$.data.state").value("ACTIVATED"));
        resend(sales, customerId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PORTAL_ACCOUNT_ALREADY_ACTIVE"));
        // The activated customer is now a CUSTOMER caller: it cannot onboard anyone.
        create(customerToken, json(Map.of("companyName", "X", "taxCode", mst(), "contactName", "Y",
                "email", unique("by-customer"))), null).andExpect(status().isForbidden());
    }

    @Test
    void onlySalesAndTheEcommerceAdminOnboardAndInputIsValidated() throws Exception {
        String body = json(Map.of("companyName", "Cong ty A", "taxCode", mst(), "contactName", "B",
                "email", unique("perm")));
        long customers = count("customer.customer");
        mvc.perform(post("/api/v1/customers").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        create(warehouse, body, null).andExpect(status().isForbidden());
        assertThat(count("customer.customer")).isEqualTo(customers);

        create(ecommerceAdmin, body, null).andExpect(status().isCreated());
        // SYSTEM_ADMIN passes every role check (CurrentUser.hasAnyRole), here as everywhere else.
        // customer-customers:CREATE is seeded for SYSTEM_ADMIN too.
        create(systemAdmin, json(Map.of("companyName", "Cong ty B", "taxCode", mst(), "contactName", "C",
                "email", unique("perm-admin"))), null).andExpect(status().isCreated());
        for (String invalid : List.of(
                json(Map.of("taxCode", mst(), "contactName", "B", "email", unique("v1"))),
                json(Map.of("companyName", "A", "taxCode", "12345", "contactName", "B", "email", unique("v-mst"))),
                json(Map.of("companyName", "A", "taxCode", mst(), "contactName", "B", "email", "not-an-email")),
                json(Map.of("companyName", "A", "taxCode", mst(), "contactName", "B", "email", unique("v2"),
                        "phone", "12")))) {
            create(sales, invalid, null).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM identity.role_permission rp
                JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
                WHERE p.code = 'customer-customers:CREATE'
                  AND r.code IN ('SALES_STAFF','ECOMMERCE_ADMIN','SYSTEM_ADMIN')""", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM identity.role_permission rp
                JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
                WHERE p.code = 'customer-customers:CREATE' AND r.code = 'CUSTOMER'""", Long.class)).isZero();
        mvc.perform(get("/api/v1/customers/portal-activation")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/customers/" + UUID.randomUUID() + "/portal-invitation")
                        .header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isForbidden());
        resend(sales, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void anExistingCustomerOrStaffAddressIsNeverReusedAndNothingIsLeftBehind() throws Exception {
        String email = unique("dup");
        create(sales, json(Map.of("companyName", "A", "taxCode", mst(), "contactName", "B", "email", email)), null)
                .andExpect(status().isCreated());
        long users = count("identity.app_user");
        long customers = count("customer.customer");

        create(sales, json(Map.of("companyName", "Other", "taxCode", mst(), "contactName", "C",
                "email", email.toUpperCase())), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_EMAIL_ALREADY_EXISTS"));
        String staffEmail = jdbc.queryForObject(
                "SELECT email FROM identity.app_user WHERE username LIKE 'staff-sales_staff-%' LIMIT 1", String.class);
        create(sales, json(Map.of("companyName", "A", "taxCode", mst(), "contactName", "B", "email", staffEmail)), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("USER_EMAIL_ALREADY_EXISTS"));

        // A failure after the account is written rolls the account back with the customer.
        String rolledBack = unique("rollback");
        assertThatCode(() -> onboarding.create(new CustomerOnboardingService.CreateBusinessCustomer(
                "A", mst(), "B", rolledBack, "12", null)), ErrorCode.VALIDATION_FAILED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.app_user WHERE email=?",
                Long.class, rolledBack)).isZero();
        assertThat(count("identity.app_user")).isEqualTo(users);
        assertThat(count("customer.customer")).isEqualTo(customers);
        assertThat(inbox().size()).isEqualTo(1);
    }

    @Test
    void concurrentCreatesOfOneAddressMakeExactlyOneCustomer() throws Exception {
        String email = unique("race-create");
        List<Callable<Object>> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String spelling = i % 2 == 0 ? email : email.toUpperCase();
            String code = mst();
            attempts.add(() -> onboarding.create(new CustomerOnboardingService.CreateBusinessCustomer(
                    "Race Co", code, "Racer", spelling, null, null)));
        }
        List<Object> outcomes = race(attempts);
        assertThat(outcomes.stream().filter(CustomerOnboardingService.Invited.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance).map(e -> ((BusinessException) e).errorCode()))
                .hasSize(3).allMatch(code -> code == ErrorCode.CUSTOMER_EMAIL_ALREADY_EXISTS
                        || code == ErrorCode.USER_EMAIL_ALREADY_EXISTS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.app_user WHERE lower(email)=?", Long.class, email))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer.customer WHERE lower(email)=?", Long.class, email))
                .isEqualTo(1);
    }

    @Test
    void concurrentActivationsOfOneLinkSucceedExactlyOnce() throws Exception {
        String email = unique("race-activate");
        UUID customerId = createdCustomer(email);
        String token = onlyLinkSentTo(email);
        List<Callable<Object>> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String password = PASSWORD + i;
            attempts.add(() -> onboarding.activate(token, password));
        }
        List<Object> outcomes = race(attempts);
        assertThat(outcomes.stream().filter(String.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance)
                .map(e -> ((BusinessException) e).errorCode())).hasSize(3)
                .allMatch(code -> code == ErrorCode.PORTAL_INVITATION_INVALID);
        assertThat(customerStatus(customerId)).isEqualTo("ACTIVE");
        int winners = 0;
        for (int i = 0; i < 4; i++) {
            if (login(email, PASSWORD + i).andReturn().getResponse().getStatus() == 200) winners++;
        }
        assertThat(winners).isEqualTo(1);
    }

    @Test
    void resendWaitsForTheCooldownThenOnlyTheNewestLinkWorks() throws Exception {
        String email = unique("resend");
        UUID customerId = createdCustomer(email);
        String first = onlyLinkSentTo(email);

        resend(sales, customerId).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
        backdateInvitation(email, 120, 3600);
        resend(sales, customerId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitation.delivery").value("SENT"));
        List<String> links = linksSentTo(email);
        assertThat(links).hasSize(2);
        String second = links.stream().filter(link -> !link.equals(first)).findFirst().orElseThrow();

        activate(first, PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PORTAL_INVITATION_INVALID"));
        activate(second, PASSWORD).andExpect(status().isOk());
        assertThat(customerStatus(customerId)).isEqualTo("ACTIVE");
    }

    @Test
    void anExpiredLinkFailsUntilSalesSendsANewOne() throws Exception {
        String email = unique("expired");
        UUID customerId = createdCustomer(email);
        String expired = onlyLinkSentTo(email);
        backdateInvitation(email, 400_000, -1);

        activate(expired, PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PORTAL_INVITATION_INVALID"));
        invitationState(customerId).andExpect(jsonPath("$.data.state").value("EXPIRED"));
        resend(sales, customerId).andExpect(status().isOk());
        String fresh = linksSentTo(email).stream().filter(link -> !link.equals(expired)).findFirst().orElseThrow();
        activate(fresh, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aResendRacingAnActivationLeavesExactlyOneUsableOutcome() throws Exception {
        String email = unique("resend-race");
        UUID customerId = createdCustomer(email);
        String token = onlyLinkSentTo(email);
        backdateInvitation(email, 120, 3600);

        List<Object> outcomes = race(List.of(
                () -> onboarding.activate(token, PASSWORD),
                () -> onboarding.resendInvitation(customerId, null)));
        boolean activated = outcomes.get(0) instanceof String;
        if (activated) {
            assertThat(outcomes.get(1)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PORTAL_ACCOUNT_ALREADY_ACTIVE));
            assertThat(customerStatus(customerId)).isEqualTo("ACTIVE");
        } else {
            assertThat(outcomes.get(0)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PORTAL_INVITATION_INVALID));
            assertThat(outcomes.get(1)).isInstanceOf(CustomerOnboardingService.Invited.class);
            assertThat(customerStatus(customerId)).isEqualTo("PENDING_ACTIVATION");
            activate(token, PASSWORD).andExpect(status().isBadRequest());
        }
    }

    @Test
    void aBlockedCustomerCannotActivateOrBeInvitedAgain() throws Exception {
        String email = unique("blocked");
        UUID customerId = createdCustomer(email);
        String token = onlyLinkSentTo(email);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                jdbc.update("UPDATE customer.customer SET status='BLOCKED' WHERE id=?", customerId));

        activate(token, PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PORTAL_INVITATION_INVALID"));
        assertThat(jdbc.queryForObject("SELECT password_hash FROM identity.app_user WHERE lower(email)=?",
                String.class, email)).isNull();
        backdateInvitation(email, 120, 3600);
        resend(sales, customerId).andExpect(status().isConflict());
        assertThat(customerStatus(customerId)).isEqualTo("BLOCKED");
    }

    @Test
    void aMailFailureKeepsTheCustomerReportsFailedAndIsRecoveredByResend() throws Exception {
        String email = unique("mail-down");
        var docker = MAILPIT.getDockerClient();
        docker.pauseContainerCmd(MAILPIT.getContainerId()).exec();
        String created;
        try {
            created = create(sales, json(Map.of("companyName", "Mail Down Co", "taxCode", mst(), "contactName", "M",
                    "email", email)), null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.invitation.delivery").value("FAILED"))
                    .andReturn().getResponse().getContentAsString();
        } finally {
            docker.unpauseContainerCmd(MAILPIT.getContainerId()).exec();
        }
        UUID customerId = UUID.fromString(mapper.readTree(created).at("/data/customer/customerId").asText());
        assertThat(customerStatus(customerId)).isEqualTo("PENDING_ACTIVATION");
        assertThat(linksSentTo(email)).isEmpty();
        invitationState(customerId).andExpect(jsonPath("$.data.delivery").value("FAILED"));

        backdateInvitation(email, 120, 3600);
        resend(sales, customerId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitation.delivery").value("SENT"));
        invitationState(customerId).andExpect(jsonPath("$.data.delivery").value("SENT"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM identity.portal_invitation i JOIN identity.app_user u ON u.id = i.user_id
                WHERE lower(u.email) = ? AND i.revoked_at IS NOT NULL AND i.delivery_status = 'FAILED'""",
                Long.class, email)).isEqualTo(1);
        activate(onlyLinkSentTo(email), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void theTaxCodeIsWholeOnlyOnTheStaffDetailAndIsFoundOnlyWhole() throws Exception {
        // Review of #109, item 9: a company's tax code or a household's personal id is PII.
        String mst = mst();
        String created = create(sales, json(Map.of("companyName", "Masked Co", "taxCode", mst, "contactName", "A",
                "email", unique("mask"))), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.customer.taxCode").value(masked(mst)))
                .andReturn().getResponse().getContentAsString();
        UUID customerId = UUID.fromString(mapper.readTree(created).at("/data/customer/customerId").asText());

        mvc.perform(get("/api/v1/customers").param("q", mst).header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.data.items[0].taxCode").value(masked(mst)));
        // A part of the code finds nothing: nobody walks the codes digit by digit through the search box.
        mvc.perform(get("/api/v1/customers").param("q", mst.substring(2, 8)).header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.customerId == '" + customerId + "')]").isEmpty());
        mvc.perform(get("/api/v1/customers/" + customerId).header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.taxCode").value(mst));
    }

    @Test
    void aTaxCodeBelongsToOneLiveCustomerInAnySpellingAndRacesEndInOneWinner() throws Exception {
        String mst = mst();
        String created = create(sales, json(Map.of("companyName", "First", "taxCode", mst, "contactName", "A",
                "email", unique("mst-1"))), null).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        UUID first = UUID.fromString(mapper.readTree(created).at("/data/customer/customerId").asText());
        for (String spelling : List.of(mst, " " + mst.substring(0, 4) + " " + mst.substring(4))) {
            create(sales, json(Map.of("companyName", "Copy", "taxCode", spelling, "contactName", "B",
                    "email", unique("mst-dup"))), null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("CUSTOMER_TAX_CODE_ALREADY_EXISTS"));
        }
        // A dependent unit is another customer: 10-3 differs from the parent's 10 digits.
        create(sales, json(Map.of("companyName", "Branch", "taxCode", mst + "-002", "contactName", "C",
                "email", unique("mst-branch"))), null).andExpect(status().isCreated());

        // BR-01, decided 16/10/2026: one record per tax code in every status. A closed customer is not created
        // again under its code - it is reopened, with a reason and its history; a blocked one cannot come back
        // under a new record at all.
        changeStatus(first, "INACTIVE").andExpect(status().isOk());
        create(sales, json(Map.of("companyName", "Again", "taxCode", mst, "contactName", "D",
                "email", unique("mst-again"))), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_TAX_CODE_ALREADY_EXISTS"));
        reopen(first, "Back with the same tax code").andExpect(status().isOk());
        assertThat(customerStatus(first)).isIn("PENDING_ACTIVATION", "ACTIVE");
        // An existing BLOCKED customer (only an activated one can be blocked; set here as one would find it).
        new TransactionTemplate(transactions).executeWithoutResult(tx -> jdbc.update(
                "UPDATE customer.customer SET status = 'BLOCKED' WHERE id = ?", first));
        create(sales, json(Map.of("companyName", "Around the block", "taxCode", mst, "contactName", "E",
                "email", unique("mst-blocked"))), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_TAX_CODE_ALREADY_EXISTS"));
        // Without a tax code (a household business, a small shop) nothing is constrained.
        for (String who : List.of("mst-none-1", "mst-none-2")) {
            create(sales, json(Map.of("companyName", "Quan nho", "contactName", "F", "email", unique(who))), null)
                    .andExpect(status().isCreated());
        }

        String raced = mst();
        List<Callable<Object>> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String email = unique("mst-race-" + i);
            attempts.add(() -> onboarding.create(new CustomerOnboardingService.CreateBusinessCustomer(
                    "Race Co", raced, "Racer", email, null, null)));
        }
        List<Object> outcomes = race(attempts);
        assertThat(outcomes.stream().filter(CustomerOnboardingService.Invited.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance)
                .map(e -> ((BusinessException) e).errorCode()))
                .hasSize(3).allMatch(code -> code == ErrorCode.CUSTOMER_TAX_CODE_ALREADY_EXISTS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer.customer WHERE tax_code=?", Long.class, raced))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM identity.app_user u WHERE lower(u.email) LIKE 'mst-race-%'
                  AND NOT EXISTS (SELECT 1 FROM customer.customer c WHERE c.user_id = u.id)""",
                Long.class)).as("no orphan account from a losing writer").isZero();
    }

    @Test
    void onlyActivationLeavesPendingForActiveAndStaffManageTheTaxCode() throws Exception {
        String email = unique("lifecycle");
        UUID customerId = createdCustomer(email);
        String token = onlyLinkSentTo(email);
        changeStatus(customerId, "ACTIVE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CUSTOMER_STATUS_TRANSITION"));
        changeStatus(customerId, "BLOCKED").andExpect(status().isConflict());
        assertThat(customerStatus(customerId)).isEqualTo("PENDING_ACTIVATION");

        // Sales corrects the tax code of a pending customer; a malformed one is refused.
        String corrected = mst();
        staffUpdate(customerId, Map.of("fullName", "Renamed Co", "version", version(customerId), "taxCode", corrected))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.taxCode").value(corrected));
        staffUpdate(customerId, Map.of("fullName", "Renamed Co", "version", version(customerId), "taxCode", "12"))
                .andExpect(status().isBadRequest());

        activate(token, PASSWORD).andExpect(status().isOk());
        String customerToken = mapper.readTree(login(email, PASSWORD).andReturn().getResponse().getContentAsString())
                .at("/data/accessToken").asText();
        // The customer edits its own profile, but its business identity stays with Sales.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/customers/me")
                        .header("Authorization", "Bearer " + customerToken).contentType("application/json")
                        .content(json(Map.of("fullName", "Self Renamed", "version", version(customerId),
                                "taxCode", mst()))))
                // Unchanged by the customer, and shown to them masked: they cannot change it (review of #109, item 9).
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.taxCode").value(masked(corrected)));
        mvc.perform(get("/api/v1/customers/" + customerId).header("Authorization", "Bearer " + sales))
                .andExpect(jsonPath("$.data.taxCode").value(corrected));
        changeStatus(customerId, "PENDING_ACTIVATION").andExpect(status().isConflict());

        // A pending customer that Sales closes cannot be activated from its old link any more.
        String closedEmail = unique("closed");
        UUID closed = createdCustomer(closedEmail);
        String closedLink = onlyLinkSentTo(closedEmail);
        changeStatus(closed, "INACTIVE").andExpect(status().isOk());
        activate(closedLink, PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PORTAL_INVITATION_INVALID"));
        resend(sales, closed).andExpect(status().isConflict());
        login(closedEmail, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void blockingEndsTheSessionAndSignInAndLiftingTheBlockRestoresSignIn() throws Exception {
        String email = unique("suspend");
        UUID customerId = createdCustomer(email);
        activate(onlyLinkSentTo(email), PASSWORD).andExpect(status().isOk());
        String before = loginToken(email, PASSWORD);
        me(before).andExpect(status().isOk());

        // kltn-docs 18 §5: Suspended = no sign-in. The token issued before the block stops at once.
        changeStatus(customerId, "BLOCKED").andExpect(status().isOk());
        me(before).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/catalog/products/availability").param("sku", "TABLE-OAK")
                .header("Authorization", "Bearer " + before)).andExpect(status().isUnauthorized());
        login(email, PASSWORD).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_ACTIVE"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM identity.user_session s JOIN identity.app_user u ON u.id = s.user_id
                WHERE lower(u.email) = ? AND s.revoked_at IS NULL""", Long.class, email)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT revoked_reason FROM identity.user_session s JOIN identity.app_user u ON u.id = s.user_id
                WHERE lower(u.email) = ? ORDER BY s.issued_at DESC LIMIT 1""", String.class, email))
                .isEqualTo("ACCOUNT_DISABLED");

        // Suspended -> Active is allowed (§5). The same password works again; the old session does not.
        changeStatus(customerId, "ACTIVE").andExpect(status().isOk());
        me(before).andExpect(status().isUnauthorized());
        String after = loginToken(email, PASSWORD);
        me(after).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // Closed (INACTIVE) is a closed account: sign-in ends the same way.
        changeStatus(customerId, "INACTIVE").andExpect(status().isOk());
        me(after).andExpect(status().isUnauthorized());
        login(email, PASSWORD).andExpect(status().isForbidden());
        // Reopening a closed customer is a manager decision with a reason (2026-10-12; §5 ends Closed at
        // Anonymized); the account follows it, old sessions stay ended.
        reopen(customerId, "Customer asked to resume").andExpect(status().isOk());
        me(loginToken(email, PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void aStaffLoginIsNeverTakenAwayThroughACustomerProfile() throws Exception {
        String staffName = "staff-linked-" + UUID.randomUUID();
        UUID staffId = Identifiers.newId();
        UUID customerId = Identifiers.newId();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("""
                    INSERT INTO identity.app_user(id, username, email, password_hash, full_name, status, version, created_at)
                    VALUES (?, ?, ?, ?, 'Linked staff', 'ACTIVE', 0, now())""",
                    staffId, staffName, staffName + "@example.test", passwordEncoder.encode(PASSWORD));
            jdbc.update("""
                    INSERT INTO identity.user_role(id, user_id, role_id, version, created_at)
                    SELECT ?, ?, id, 0, now() FROM identity.app_role WHERE code = 'SALES_STAFF'""",
                    Identifiers.newId(), staffId);
            jdbc.update("""
                    INSERT INTO customer.customer(id, user_id, full_name, email, status, tax_code, version, created_at)
                    VALUES (?, ?, 'Linked Co', ?, 'ACTIVE', ?, 0, now())""",
                    customerId, staffId, staffName + "@example.test", mst());
        });
        String staff = identities.login(new LoginCommand(staffName, PASSWORD, null, "scrum450-test")).accessToken();

        changeStatus(customerId, "BLOCKED").andExpect(status().isOk());
        assertThat(customerStatus(customerId)).isEqualTo("BLOCKED");
        mvc.perform(get("/api/v1/customers").header("Authorization", "Bearer " + staff)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM identity.app_user WHERE id=?", String.class, staffId))
                .isEqualTo("ACTIVE");
        login(staffName, PASSWORD).andExpect(status().isOk());
        // And the unlinked staff used throughout this class still works.
        mvc.perform(get("/api/v1/customers").header("Authorization", "Bearer " + sales)).andExpect(status().isOk());
    }

    @Test
    void aShopWithoutATaxCodeIsOnboardedActivatesAndOrdersOnItsOwn() throws Exception {
        String email = unique("shop");
        String created = create(sales, json(Map.of("companyName", "Quan ca phe Nho", "contactName", "Chu quan",
                "email", email)), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.customer.taxCode").doesNotExist())
                .andExpect(jsonPath("$.data.customer.status").value("PENDING_ACTIVATION"))
                .andReturn().getResponse().getContentAsString();
        UUID customerId = UUID.fromString(mapper.readTree(created).at("/data/customer/customerId").asText());
        activate(onlyLinkSentTo(email), PASSWORD).andExpect(status().isOk());
        String shop = loginToken(email, PASSWORD);

        String address = mvc.perform(post("/api/v1/customers/" + customerId + "/addresses")
                        .header("Authorization", "Bearer " + shop).contentType("application/json").content("""
                                {"type":"SHIPPING","recipientName":"Chu quan","phone":"0901234567",
                                 "line1":"12 Nguyen Hue","wardCode":"26734","wardName":"Ben Nghe",
                                 "provinceCode":"79","provinceName":"Ho Chi Minh","countryCode":"VN"}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(address).contains("SHIPPING");
        long price = jdbc.queryForObject(
                "SELECT price FROM catalog.pricing_rule WHERE name='BASE:SOFA-3S-GREY' AND active", Long.class);
        mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + shop)
                        .contentType("application/json").content(json(Map.of(
                                "requestId", UUID.randomUUID(), "customerId", customerId,
                                "lines", List.of(Map.of("sku", "SOFA-3S-GREY", "quantity", 1, "unitPrice", price))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.customerId").value(customerId.toString()));

        // Supplied later by Sales, with the usual rules.
        String code = mst();
        staffUpdate(customerId, Map.of("fullName", "Quan ca phe Nho", "version", version(customerId), "taxCode", code))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.taxCode").value(code));
    }

    @Test
    void aManagerReopensAClosedCustomerOnlyWithAReasonAndOldSessionsStayDead() throws Exception {
        String email = unique("reopen");
        UUID customerId = createdCustomer(email);
        activate(onlyLinkSentTo(email), PASSWORD).andExpect(status().isOk());
        String before = loginToken(email, PASSWORD);

        changeStatus(customerId, "INACTIVE").andExpect(status().isOk());
        changeStatus(customerId, "ACTIVE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        reopen(customerId, "   ").andExpect(status().isBadRequest());
        changeStatus(customerId, "BLOCKED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CUSTOMER_STATUS_TRANSITION"));
        // Sales is not the manager (kltn-docs 18 §2: E-commerce Admin).
        mvc.perform(post("/api/v1/customers/" + customerId + "/status").header("Authorization", "Bearer " + sales)
                        .contentType("application/json").content(json(Map.of("status", "ACTIVE", "reason", "x"))))
                .andExpect(status().isForbidden());
        assertThat(customerStatus(customerId)).isEqualTo("INACTIVE");

        reopen(customerId, "Khach xac nhan tiep tuc hop tac").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        me(before).andExpect(status().isUnauthorized());
        me(loginToken(email, PASSWORD)).andExpect(status().isOk());

        String history = mvc.perform(get("/api/v1/customers/" + customerId + "/status-history")
                        .header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var entries = mapper.readTree(history).get("data");
        assertThat(entries).hasSize(4);
        assertThat(entries.get(0).path("fromStatus").isMissingNode()).isTrue();
        assertThat(entries.get(0).get("toStatus").asText()).isEqualTo("PENDING_ACTIVATION");
        assertThat(entries.get(1).get("toStatus").asText()).isEqualTo("ACTIVE");
        assertThat(entries.get(2).get("toStatus").asText()).isEqualTo("INACTIVE");
        assertThat(entries.get(3).get("fromStatus").asText()).isEqualTo("INACTIVE");
        assertThat(entries.get(3).get("reason").asText()).isEqualTo("Khach xac nhan tiep tuc hop tac");
        assertThat(entries.get(3).get("changedBy").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type='customer' AND resource_id=?
                  AND action='TRANSITION'""", Long.class, customerId.toString())).isGreaterThanOrEqualTo(2);
        mvc.perform(get("/api/v1/customers/" + customerId + "/status-history")
                .header("Authorization", "Bearer " + warehouse)).andExpect(status().isForbidden());
    }

    @Test
    void aClosedCustomerThatNeverActivatedReopensPendingAndCanBeInvitedAgain() throws Exception {
        String email = unique("reopen-pending");
        UUID customerId = createdCustomer(email);
        String firstLink = onlyLinkSentTo(email);
        changeStatus(customerId, "INACTIVE").andExpect(status().isOk());
        reopen(customerId, "Khach doi y").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_ACTIVATION"));
        backdateInvitation(email, 120, 3600);
        resend(sales, customerId).andExpect(status().isOk());
        activate(firstLink, PASSWORD).andExpect(status().isBadRequest());
        String fresh = linksSentTo(email).stream().filter(link -> !link.equals(firstLink)).findFirst().orElseThrow();
        activate(fresh, PASSWORD).andExpect(status().isOk());
        assertThat(customerStatus(customerId)).isEqualTo("ACTIVE");
    }

    private ResultActions reopen(UUID customerId, String reason) throws Exception {
        return mvc.perform(post("/api/v1/customers/" + customerId + "/status")
                .header("Authorization", "Bearer " + ecommerceAdmin).contentType("application/json")
                .content(json(Map.of("status", "ACTIVE", "reason", reason))));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions me(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/customers/me").header("Authorization", "Bearer " + bearer));
    }

    private String loginToken(String username, String password) throws Exception {
        return mapper.readTree(login(username, password).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).at("/data/accessToken").asText();
    }

    private ResultActions changeStatus(UUID customerId, String target) throws Exception {
        return mvc.perform(post("/api/v1/customers/" + customerId + "/status")
                .header("Authorization", "Bearer " + ecommerceAdmin).contentType("application/json")
                .content(json(Map.of("status", target))));
    }

    private ResultActions staffUpdate(UUID customerId, Map<String, ?> body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/customers/" + customerId).header("Authorization", "Bearer " + sales)
                .contentType("application/json").content(json(body)));
    }

    private long version(UUID customerId) {
        return jdbc.queryForObject("SELECT version FROM customer.customer WHERE id=?", Long.class, customerId);
    }


    private UUID createdCustomer(String email) throws Exception {
        String created = create(sales, json(Map.of("companyName", "Co " + email, "taxCode", mst(),
                "contactName", "Contact", "email", email)), null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(created).at("/data/customer/customerId").asText());
    }

    /** Decision B1 (2026-10-13): Sales sets PREPAID or DEPOSIT at onboarding; CREDIT stays with the approver. */
    @Test
    void salesChoosesPrepaidOrDepositAtOnboardingAndCreditStaysWithTheApprover() throws Exception {
        String refused = unique("credit");
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("companyName", "Cong ty Cong No", "contactName", "A", "email", refused, "paymentTerm", "CREDIT"),
                Map.of("companyName", "Cong ty Coc", "contactName", "A", "email", refused, "paymentTerm", "DEPOSIT"),
                Map.of("companyName", "Cong ty Tra Truoc", "contactName", "A", "email", refused,
                        "paymentTerm", "PREPAID", "depositPercent", 30),
                Map.of("companyName", "Cong ty Coc", "contactName", "A", "email", refused,
                        "paymentTerm", "DEPOSIT", "depositPercent", 100),
                Map.of("companyName", "Cong ty Coc", "contactName", "A", "email", refused,
                        "paymentTerm", "DEPOSIT", "depositPercent", 12.345))) {
            create(sales, json(bad), null).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer.customer WHERE email = ?", Integer.class,
                refused)).as("nothing written").isZero();

        String email = unique("deposit");
        String created = create(sales, json(Map.of("companyName", "Cong ty Dat Coc", "contactName", "Chi B",
                "email", email, "paymentTerm", "DEPOSIT", "depositPercent", 30)), null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID customerId = UUID.fromString(mapper.readTree(created).at("/data/customer/customerId").asText());
        String profile = "/api/v1/customers/" + customerId + "/credit-profile";
        String terms = mvc.perform(get(profile).header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.allowPrepaid").value(true))
                .andExpect(jsonPath("$.data.allowDeposit").value(true))
                .andExpect(jsonPath("$.data.allowCredit").value(false))
                .andExpect(jsonPath("$.data.defaultPaymentTerm").value("DEPOSIT"))
                .andExpect(jsonPath("$.data.depositPercent").value(30))
                .andExpect(jsonPath("$.data.approvedBy").exists())
                .andReturn().getResponse().getContentAsString();
        long version = mapper.readTree(terms).at("/data/version").asLong();

        // Sales cannot grant credit afterwards either; the approver can.
        Map<String, Object> credit = new java.util.HashMap<>(Map.of("allowPrepaid", true, "allowDeposit", true,
                "allowCredit", true, "defaultPaymentTerm", "CREDIT", "depositPercent", 30, "creditLimit", 50_000_000,
                "creditTermDays", 30, "expectedVersion", version));
        mvc.perform(put(profile).header("Authorization", "Bearer " + sales).contentType("application/json")
                .content(json(credit))).andExpect(status().isForbidden());
        mvc.perform(put(profile).header("Authorization", "Bearer " + systemAdmin).contentType("application/json")
                        .content(json(credit)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.allowCredit").value(true));

        // Without a choice nothing is recorded, as before: the customer prepays until the approver acts.
        String plain = unique("plain");
        String createdPlain = create(sales, json(Map.of("companyName", "Cong ty Thuong", "contactName", "C",
                "email", plain)), null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID plainId = UUID.fromString(mapper.readTree(createdPlain).at("/data/customer/customerId").asText());
        mvc.perform(get("/api/v1/customers/" + plainId + "/credit-profile").header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.defaultPaymentTerm").value("PREPAID"));
    }

    private ResultActions create(String bearer, String body, String idempotencyKey) throws Exception {
        var request = post("/api/v1/customers").header("Authorization", "Bearer " + bearer)
                .contentType("application/json").content(body);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        return mvc.perform(request);
    }

    private ResultActions resend(String bearer, UUID customerId) throws Exception {
        return mvc.perform(post("/api/v1/customers/" + customerId + "/portal-invitation")
                .header("Authorization", "Bearer " + bearer));
    }

    private ResultActions invitationState(UUID customerId) throws Exception {
        return mvc.perform(get("/api/v1/customers/" + customerId + "/portal-invitation")
                .header("Authorization", "Bearer " + sales)).andExpect(status().isOk());
    }

    private ResultActions activate(String token, String password) throws Exception {
        return mvc.perform(post("/api/v1/customers/portal-activation").contentType("application/json")
                .header("X-Forwarded-For", anotherClient())
                .content(json(Map.of("token", token, "password", password))));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/identity/auth/login").contentType("application/json")
                .header("X-Forwarded-For", anotherClient())
                .content(json(Map.of("username", username, "password", password))));
    }

    /**
     * Login and activation are rate-limited per client address, and every MockMvc request comes from
     * 127.0.0.1. Each call here is a different client behind the proxy, so the limit stays real but
     * the tests in this class do not share one bucket.
     */
    private static String anotherClient() {
        var random = java.util.concurrent.ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    /**
     * Moves the open invitation of an account back in time, as the clock would: issued
     * {@code issuedSecondsAgo} ago and expiring {@code expiresInSeconds} from now (negative = past).
     * Only the two instants change; the digest - and so the link - stays the same.
     */
    private void backdateInvitation(String email, long issuedSecondsAgo, long expiresInSeconds) {
        Instant now = Instant.now();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertThat(jdbc.update("""
                UPDATE identity.portal_invitation SET issued_at = ?, expires_at = ?
                WHERE consumed_at IS NULL AND revoked_at IS NULL
                  AND user_id = (SELECT id FROM identity.app_user WHERE lower(email) = ?)
                """, java.sql.Timestamp.from(now.minusSeconds(issuedSecondsAgo)),
                java.sql.Timestamp.from(now.plusSeconds(expiresInSeconds)), email)).isEqualTo(1));
    }

    private String onlyLinkSentTo(String email) throws Exception {
        List<String> links = linksSentTo(email);
        assertThat(links).hasSize(1);
        return links.get(0);
    }

    private List<String> linksSentTo(String email) throws Exception {
        List<String> links = new ArrayList<>();
        for (JsonNode message : inbox()) {
            if (!email.equalsIgnoreCase(message.at("/To/0/Address").asText())) continue;
            JsonNode full = mapper.readTree(http.send(HttpRequest.newBuilder(
                    mailpit("/api/v1/message/" + message.get("ID").asText())).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            assertThat(full.get("Subject").asText()).contains("Kích hoạt tài khoản cổng khách hàng");
            Matcher link = LINK.matcher(full.get("Text").asText());
            assertThat(link.find()).as("activation link in the e-mail").isTrue();
            links.add(link.group(1));
        }
        return links;
    }

    private List<JsonNode> inbox() throws Exception {
        JsonNode list = mapper.readTree(http.send(HttpRequest.newBuilder(mailpit("/api/v1/messages")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        List<JsonNode> messages = new ArrayList<>();
        list.get("messages").forEach(messages::add);
        return messages;
    }

    private static URI mailpit(String path) {
        return URI.create("http://" + mailHost() + ":" + MAILPIT.getMappedPort(8025) + path);
    }

    /** "localhost" can cost seconds per lookup where IPv6 is tried first; the mapped port is on IPv4. */
    private static String mailHost() {
        return "localhost".equals(MAILPIT.getHost()) ? "127.0.0.1" : MAILPIT.getHost();
    }

    /** Starts every attempt at the same instant on its own thread; returns results or exceptions in order. */
    private static List<Object> race(List<Callable<Object>> attempts) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(attempts.size());
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
            for (Future<Object> future : futures) outcomes.add(future.get());
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertThatCode(Runnable action, ErrorCode expected) {
        org.assertj.core.api.Assertions.assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
    }

    private String staffToken(String role) {
        String username = "staff-" + role.toLowerCase() + "-" + UUID.randomUUID();
        UUID userId = Identifiers.newId();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("""
                    INSERT INTO identity.app_user(id, username, email, password_hash, full_name, status, version, created_at)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, now())""",
                    userId, username, username + "@example.test", passwordEncoder.encode(PASSWORD), role);
            jdbc.update("""
                    INSERT INTO identity.user_role(id, user_id, role_id, version, created_at)
                    SELECT ?, ?, id, 0, now() FROM identity.app_role WHERE code = ?""",
                    Identifiers.newId(), userId, role);
        });
        return identities.login(new LoginCommand(username, PASSWORD, null, "scrum450-test")).accessToken();
    }

    /** What a list or an invitation response shows: the first and last three kept (review of #109, item 9). */
    private static String masked(String code) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            out.append(i < 3 || i >= code.length() - 3 || !Character.isLetterOrDigit(c) ? c : '*');
        }
        return out.toString();
    }

    private String customerStatus(UUID customerId) {
        return jdbc.queryForObject("SELECT status FROM customer.customer WHERE id=?", String.class, customerId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private String json(Map<String, ?> body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    /** A fresh 10-digit tax code, so tests never collide on uk_customer_tax_code. */
    private static String mst() {
        return String.format("%010d",
                java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    private static String unique(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }
}
