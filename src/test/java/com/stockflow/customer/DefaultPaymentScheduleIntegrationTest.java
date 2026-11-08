package com.stockflow.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerPaymentSchedules;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.PaymentMilestone;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4B-1 (kltn-docs 18 §3, §4 steps 2 and 5, BR-03; 14 BR-13): a customer's default payment schedule over HTTP - Sales sets
 * instalments at signing and before delivery, the shares adding up to 100; one after delivery is accounting's, and only
 * for a customer whose credit accounting approved; that credit is not taken away while the schedule uses it.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false"})
class DefaultPaymentScheduleIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired CustomerService customers;
    @Autowired CustomerPaymentSchedules schedules;

    private UUID user() {
        return tx.execute(s -> ReferenceRows.user(entityManager));
    }

    private UUID customer() {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
                VALUES (?, 'Cong ty Lich Thanh Toan', ?, 'ACTIVE', 0, NOW())""", id, "schedule-" + id + "@example.com"));
        return id;
    }

    private String bearer(UUID user, String role) {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private ResultActions save(UUID customer, String role, String body) throws Exception {
        return mvc.perform(put("/api/v1/customers/" + customer + "/payment-schedule")
                .header("Authorization", bearer(user(), role)).contentType("application/json").content(body));
    }

    private JsonNode read(UUID customer, String role) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/customers/" + customer + "/payment-schedule")
                        .header("Authorization", bearer(user(), role))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    private static String schedule(Long version, String... lines) {
        return "{\"installments\":[" + String.join(",", lines) + "]"
                + (version == null ? "" : ",\"expectedVersion\":" + version) + "}";
    }

    private static String line(String name, String percent, String milestone, int days) {
        return "{\"name\":\"" + name + "\",\"percent\":" + percent + ",\"milestone\":\"" + milestone
                + "\",\"dueDays\":" + days + "}";
    }

    @Test
    @DisplayName("Sales sets a schedule at signing and before delivery; nothing set reads as everything at signing; the "
            + "shares add up to 100 with two decimals at most; a change needs the version read")
    void salesSetsTheSchedule() throws Exception {
        UUID customer = customer();
        JsonNode none = read(customer, "SALES_STAFF");
        assertThat(none.path("configured").asBoolean()).isFalse();
        assertThat(none.path("installments")).singleElement().satisfies(i -> {
            assertThat(i.path("milestone").asText()).isEqualTo("ON_SIGNING");
            assertThat(i.path("percent").decimalValue()).isEqualByComparingTo("100");
        });

        save(customer, "SALES_STAFF", schedule(null, line("Đặt cọc", "30", "ON_SIGNING", 0),
                line("Trước giao", "60", "BEFORE_DELIVERY", 0))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        save(customer, "SALES_STAFF", schedule(null, line("Đặt cọc", "33.333", "ON_SIGNING", 0),
                line("Trước giao", "66.667", "BEFORE_DELIVERY", 0))).andExpect(status().isBadRequest());
        save(customer, "SALES_STAFF", schedule(null, line("Đặt cọc", "33.33", "ON_SIGNING", 0),
                line("Trước giao", "66.67", "BEFORE_DELIVERY", 3))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.approvedBy").doesNotExist());
        JsonNode set = read(customer, "ACCOUNTANT");
        assertThat(set.path("installments")).hasSize(2);
        assertThat(set.path("installments").get(1).path("dueDays").asInt()).isEqualTo(3);

        save(customer, "SALES_STAFF", schedule(null, line("Ký", "100", "ON_SIGNING", 0)))
                .andExpect(status().isBadRequest());
        save(customer, "SALES_STAFF", schedule(set.path("version").asLong() + 5, line("Ký", "100", "ON_SIGNING", 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        mvc.perform(get("/api/v1/customers/" + customer + "/payment-schedule")
                .header("Authorization", bearer(user(), "WAREHOUSE_STAFF"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an instalment after delivery: not Sales'; accounting's only with approved credit, recorded as approved "
            + "by them; that credit is not taken away while the schedule uses it")
    void afterDeliveryIsAccountings() throws Exception {
        UUID customer = customer();
        String withCredit = schedule(null, line("Ký", "30", "ON_SIGNING", 0), line("Công nợ", "70", "AFTER_DELIVERY", 30));
        save(customer, "SALES_STAFF", withCredit).andExpect(status().isForbidden());
        save(customer, "ACCOUNTANT", withCredit).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_SCHEDULE_NEEDS_CREDIT"));

        UUID accountant = user();
        var terms = customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, false, true,
                CommercialTerm.PREPAID, null, new BigDecimal("50000000"), 30, null, accountant, null));
        JsonNode approved = json.readTree(save(customer, "ACCOUNTANT", withCredit).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(approved.path("approvedBy").asText()).isNotBlank();
        assertThat(schedules.defaultPaymentSchedule(customer).paysAfterDelivery()).isTrue();

        // Taking the credit away while the schedule pays after delivery: refused, nothing changed.
        Throwable refused = catchThrowable(() -> customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true,
                false, false, CommercialTerm.PREPAID, null, null, null, null, accountant, terms.version())));
        assertThat(refused).isInstanceOfSatisfying(com.stockflow.common.error.BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(com.stockflow.common.error.ErrorCode
                        .PAYMENT_SCHEDULE_NEEDS_CREDIT));
        assertThat(customers.creditTerms(customer).allowCredit()).isTrue();

        // Sales changes it back to at signing and before delivery: the approval goes with the instalment after delivery.
        save(customer, "SALES_STAFF", schedule(approved.path("version").asLong(), line("Ký", "100", "ON_SIGNING", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.approvedBy").doesNotExist());
        assertThat(schedules.defaultPaymentSchedule(customer).installments()).singleElement()
                .satisfies(i -> assertThat(i.milestone()).isEqualTo(PaymentMilestone.ON_SIGNING));
    }
}
