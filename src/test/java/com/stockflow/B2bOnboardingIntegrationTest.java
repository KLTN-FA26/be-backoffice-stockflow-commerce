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
        sales = staffToken("SALES_STAFF");
        ecommerceAdmin = staffToken("ECOMMERCE_ADMIN");
        warehouse = staffToken("WAREHOUSE_STAFF");
        systemAdmin = staffToken("SYSTEM_ADMIN");
    }

    @AfterAll
    void stopMail() {
        MAILPIT.stop();
    }

    @BeforeEach
    void emptyInbox() throws Exception {
        http.send(HttpRequest.newBuilder(mailpit("/api/v1/messages")).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void salesCreatesTheCustomerAndTheContactActivatesOnceFromTheEmailedLink() throws Exception {
        String email = unique("buyer");
        long users = count("identity.app_user");
        String body = json(Map.of("companyName", "Cong ty TNHH Ly Giay Sai Gon", "contactName", "Nguyen Van An",
                "email", email.toUpperCase(), "phone", "0901234567"));
        String key = "create-" + UUID.randomUUID();

        String created = create(sales, body, key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.customer.fullName").value("Cong ty TNHH Ly Giay Sai Gon"))
                .andExpect(jsonPath("$.data.customer.email").value(email))
                .andExpect(jsonPath("$.data.customer.status").value("INACTIVE"))
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
        String stored = jdbc.queryForObject("SELECT password_hash FROM identity.app_user WHERE id=?",
                String.class, userId);
        assertThat(stored).startsWith("{portal-invitation}").doesNotContain(token);
        assertThat(jdbc.queryForObject("""
                SELECT r.code FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                WHERE ur.user_id = ?""", String.class, userId)).isEqualTo("CUSTOMER");
        invitationState(customerId).andExpect(jsonPath("$.data.state").value("PENDING"));

        // No password exists before activation, so nothing - not even a guessed one - signs in.
        login(email, PASSWORD).andExpect(status().isUnauthorized());

        activate(token, "short").andExpect(status().isBadRequest());
        activate(token, "weakpassword1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        assertThat(customerStatus(customerId)).isEqualTo("INACTIVE");

        activate(token, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(email));
        assertThat(customerStatus(customerId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM identity.app_user WHERE id=?",
                String.class, userId)).startsWith("{bcrypt}");

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
        create(customerToken, json(Map.of("companyName", "X", "contactName", "Y",
                "email", unique("by-customer"))), null).andExpect(status().isForbidden());
    }

    @Test
    void onlySalesAndTheEcommerceAdminOnboardAndInputIsValidated() throws Exception {
        String body = json(Map.of("companyName", "Cong ty A", "contactName", "B", "email", unique("perm")));
        long customers = count("customer.customer");
        mvc.perform(post("/api/v1/customers").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        create(warehouse, body, null).andExpect(status().isForbidden());
        assertThat(count("customer.customer")).isEqualTo(customers);

        create(ecommerceAdmin, body, null).andExpect(status().isCreated());
        // SYSTEM_ADMIN passes every role check (CurrentUser.hasAnyRole), here as everywhere else.
        create(systemAdmin, json(Map.of("companyName", "Cong ty B", "contactName", "C",
                "email", unique("perm-admin"))), null).andExpect(status().isCreated());
        for (String invalid : List.of(
                json(Map.of("contactName", "B", "email", unique("v1"))),
                json(Map.of("companyName", "A", "contactName", "B", "email", "not-an-email")),
                json(Map.of("companyName", "A", "contactName", "B", "email", unique("v2"), "phone", "12")))) {
            create(sales, invalid, null).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/customers/portal-activation")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/customers/" + UUID.randomUUID() + "/portal-invitation")
                        .header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isForbidden());
        resend(sales, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void anExistingCustomerOrStaffAddressIsNeverReusedAndNothingIsLeftBehind() throws Exception {
        String email = unique("dup");
        create(sales, json(Map.of("companyName", "A", "contactName", "B", "email", email)), null)
                .andExpect(status().isCreated());
        long users = count("identity.app_user");
        long customers = count("customer.customer");

        create(sales, json(Map.of("companyName", "Other", "contactName", "C", "email", email.toUpperCase())), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_EMAIL_ALREADY_EXISTS"));
        String staffEmail = jdbc.queryForObject(
                "SELECT email FROM identity.app_user WHERE username LIKE 'staff-sales_staff-%' LIMIT 1", String.class);
        create(sales, json(Map.of("companyName", "A", "contactName", "B", "email", staffEmail)), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("USER_EMAIL_ALREADY_EXISTS"));

        // A failure after the account is written rolls the account back with the customer.
        String rolledBack = unique("rollback");
        assertThatCode(() -> onboarding.create(new CustomerOnboardingService.CreateBusinessCustomer(
                "A", "B", rolledBack, "12")), ErrorCode.VALIDATION_FAILED);
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
            attempts.add(() -> onboarding.create(new CustomerOnboardingService.CreateBusinessCustomer(
                    "Race Co", "Racer", spelling, null)));
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
                () -> onboarding.resendInvitation(customerId)));
        boolean activated = outcomes.get(0) instanceof String;
        if (activated) {
            assertThat(outcomes.get(1)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PORTAL_ACCOUNT_ALREADY_ACTIVE));
            assertThat(customerStatus(customerId)).isEqualTo("ACTIVE");
        } else {
            assertThat(outcomes.get(0)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PORTAL_INVITATION_INVALID));
            assertThat(outcomes.get(1)).isInstanceOf(CustomerOnboardingService.Invited.class);
            assertThat(customerStatus(customerId)).isEqualTo("INACTIVE");
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
                String.class, email)).startsWith("{portal-invitation}");
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
            created = create(sales, json(Map.of("companyName", "Mail Down Co", "contactName", "M", "email", email)), null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.invitation.delivery").value("FAILED"))
                    .andReturn().getResponse().getContentAsString();
        } finally {
            docker.unpauseContainerCmd(MAILPIT.getContainerId()).exec();
        }
        UUID customerId = UUID.fromString(mapper.readTree(created).at("/data/customer/customerId").asText());
        assertThat(customerStatus(customerId)).isEqualTo("INACTIVE");
        assertThat(linksSentTo(email)).isEmpty();

        backdateInvitation(email, 120, 3600);
        resend(sales, customerId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitation.delivery").value("SENT"));
        activate(onlyLinkSentTo(email), PASSWORD).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private UUID createdCustomer(String email) throws Exception {
        String created = create(sales, json(Map.of("companyName", "Co " + email, "contactName", "Contact",
                "email", email)), null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(created).at("/data/customer/customerId").asText());
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
                .content(json(Map.of("token", token, "password", password))));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/identity/auth/login").contentType("application/json")
                .content(json(Map.of("username", username, "password", password))));
    }

    /**
     * Moves the open invitation of an account back in time, as the clock would: issued
     * {@code issuedSecondsAgo} ago and expiring {@code expiresInSeconds} from now (negative = past).
     * Only the two instants change; the digest - and so the link - stays the same.
     */
    private void backdateInvitation(String email, long issuedSecondsAgo, long expiresInSeconds) {
        long now = Instant.now().getEpochSecond();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertThat(jdbc.update("""
                UPDATE identity.app_user
                SET password_hash = regexp_replace(password_hash, '^\\{portal-invitation\\}\\d+:\\d+:',
                                                   '{portal-invitation}' || ? || ':' || ? || ':')
                WHERE lower(email) = ? AND password_hash LIKE '{portal-invitation}%'
                """, now - issuedSecondsAgo, now + expiresInSeconds, email)).isEqualTo(1));
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

    private String customerStatus(UUID customerId) {
        return jdbc.queryForObject("SELECT status FROM customer.customer WHERE id=?", String.class, customerId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private String json(Map<String, ?> body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private static String unique(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }
}
