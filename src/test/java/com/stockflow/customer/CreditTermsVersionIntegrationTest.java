package com.stockflow.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-484: terms that already exist are changed only against the version the caller read. A save without it
 * would overwrite a concurrent approver's change silently; the first set of terms may still omit it.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false"})
class CreditTermsVersionIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired CustomerService customers;

    private UUID approver() {
        return tx.execute(s -> ReferenceRows.user(entityManager));
    }

    private UUID customer() {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
                VALUES (?, 'Cong ty Han Muc', ?, 'ACTIVE', 0, NOW())""", id, "terms-" + id + "@example.com"));
        return id;
    }

    private SaveCreditTermsCommand command(UUID customerId, UUID by, BigDecimal limit, Long expectedVersion) {
        return new SaveCreditTermsCommand(customerId, true, false, true, CommercialTerm.PREPAID, null, limit, 30,
                null, by, expectedVersion);
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    void existingTermsNeedTheVersionTheCallerRead() {
        UUID by = approver();
        UUID customerId = customer();

        // The first set of terms may omit it (and may send the -1 a read of unset terms returns).
        var first = customers.saveCreditTerms(command(customerId, by, new BigDecimal("10000000"), null));
        assertThat(first.configured()).isTrue();

        // From now on a save without the version is refused, and nothing is written.
        assertThatThrownBy(() -> customers.saveCreditTerms(command(customerId, by, new BigDecimal("99000000"), null)))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(refused.fieldErrors()).singleElement().satisfies(error -> {
                        assertThat(error.field()).isEqualTo("expectedVersion");
                        assertThat(error.code()).isEqualTo("NotNull");
                    });
                });
        assertThat(customers.creditTerms(customerId).creditLimit()).isEqualByComparingTo("10000000");
        assertThat(customers.creditTerms(customerId).version()).isEqualTo(first.version());

        // With the version read it goes through and moves the version; a stale one is the 409 as before.
        var second = customers.saveCreditTerms(command(customerId, by, new BigDecimal("20000000"), first.version()));
        assertThat(second.creditLimit()).isEqualByComparingTo("20000000");
        assertThat(second.version()).isGreaterThan(first.version());
        assertThatThrownBy(() -> customers.saveCreditTerms(
                command(customerId, by, new BigDecimal("30000000"), first.version())))
                .satisfies(refused -> assertThat(codeOf(refused)).isEqualTo(ErrorCode.OPTIMISTIC_LOCK));
        assertThat(customers.creditTerms(customerId).creditLimit()).isEqualByComparingTo("20000000");
    }

    @Test
    void firstTermsMayCarryTheUnsetVersionAWireClientReads() {
        UUID customerId = customer();
        // GET answers version -1 while no terms are set; sending it back on the first save is accepted.
        var saved = customers.saveCreditTerms(command(customerId, approver(), new BigDecimal("5000000"), -1L));
        assertThat(saved.configured()).isTrue();
    }

    @Test
    void twoApproversAtOnceOneWinsTheOtherIsTold() throws Exception {
        UUID by = approver();
        UUID customerId = customer();
        long version = customers.saveCreditTerms(command(customerId, by, new BigDecimal("1000000"), null)).version();

        List<Object> outcomes = race(List.of(
                () -> customers.saveCreditTerms(command(customerId, by, new BigDecimal("2000000"), version)),
                () -> customers.saveCreditTerms(command(customerId, by, new BigDecimal("3000000"), version))));

        assertThat(outcomes.stream().filter(BusinessException.class::isInstance).map(e -> ((BusinessException) e).errorCode()))
                .containsExactly(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(outcomes.stream().filter(o -> !(o instanceof BusinessException))).hasSize(1);
    }

    @Test
    void overHttpAMissingVersionIs400WithTheFieldAndAStaleOneIs409() throws Exception {
        UUID customerId = customer();
        String profile = "/api/v1/customers/" + customerId + "/credit-profile";
        String approver = bearer("ACCOUNTANT");

        Map<String, Object> body = new HashMap<>(Map.of("allowPrepaid", true, "allowDeposit", false,
                "allowCredit", true, "defaultPaymentTerm", "PREPAID", "creditLimit", 10_000_000, "creditTermDays", 30));
        // First set: no version needed.
        mvc.perform(put(profile).header("Authorization", approver).contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.configured").value(true));
        long version = json.readTree(mvc.perform(get(profile).header("Authorization", approver))
                .andReturn().getResponse().getContentAsString()).at("/data/version").asLong();

        mvc.perform(put(profile).header("Authorization", approver).contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("expectedVersion"));

        body.put("expectedVersion", version);
        body.put("creditLimit", 20_000_000);
        mvc.perform(put(profile).header("Authorization", approver).contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.creditLimit").value(20_000_000));
        // The same version again is stale now.
        mvc.perform(put(profile).header("Authorization", approver).contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
    }

    private String bearer(String role) {
        UUID userId = approver();
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(userId.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
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
