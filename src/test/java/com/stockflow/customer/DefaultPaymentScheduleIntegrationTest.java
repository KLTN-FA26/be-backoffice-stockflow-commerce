package com.stockflow.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4B-1 (kltn-docs 18 §3, §4 steps 2 and 5, BR-03, BR-04; 14 BR-13; 15 BR-12): a customer's default payment schedule over
 * HTTP - Sales sets it, the shares adding up to 100; an installment after delivery only on credit accounting approved,
 * proposed by Sales and approved by accounting (or set by accounting, approved as saved), audited; that credit is not
 * taken away while the schedule uses it; nothing set reads as the customer's commercial terms.
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
        return save(customer, user(), role, body);
    }

    private ResultActions save(UUID customer, UUID by, String role, String body) throws Exception {
        return mvc.perform(put("/api/v1/customers/" + customer + "/payment-schedule")
                .header("Authorization", bearer(by, role)).contentType("application/json").content(body));
    }

    private ResultActions approve(UUID customer, UUID by, String role, long version) throws Exception {
        return mvc.perform(post("/api/v1/customers/" + customer + "/payment-schedule/approval")
                .header("Authorization", bearer(by, role)).contentType("application/json")
                .content("{\"expectedVersion\":" + version + "}"));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private JsonNode read(UUID customer, String role) throws Exception {
        return data(mvc.perform(get("/api/v1/customers/" + customer + "/payment-schedule")
                .header("Authorization", bearer(user(), role))).andExpect(status().isOk()));
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static String schedule(Long version, String... lines) {
        return scheduleWithNote(version, null, lines);
    }

    private static String scheduleWithNote(Long version, String note, String... lines) {
        return "{\"installments\":[" + String.join(",", lines) + "]"
                + (note == null ? "" : ",\"note\":\"" + note + "\"")
                + (version == null ? "" : ",\"expectedVersion\":" + version) + "}";
    }

    private static String line(String name, String percent, String milestone, Integer days) {
        return "{\"name\":\"" + name + "\",\"percent\":" + percent + ",\"milestone\":\"" + milestone + "\""
                + (days == null ? "" : ",\"dueDays\":" + days) + "}";
    }

    private com.stockflow.customer.api.CreditTerms terms(UUID customer, boolean credit, String limit, Long version) {
        return customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, false, credit,
                CommercialTerm.PREPAID, null, limit == null ? null : new BigDecimal(limit), credit ? 30 : null, null,
                user(), version));
    }

    private static ErrorCode refusal(Runnable action) {
        Throwable refused = catchThrowable(action::run);
        assertThat(refused).isInstanceOf(BusinessException.class);
        return ((BusinessException) refused).errorCode();
    }

    @Test
    @DisplayName("Sales sets a schedule at signing and before delivery; nothing set reads as the commercial terms; the "
            + "shares add up to 100 with two decimals at most; a change needs the version read")
    void salesSetsTheSchedule() throws Exception {
        UUID customer = customer();
        JsonNode none = read(customer, "SALES_STAFF");
        assertThat(none.path("configured").asBoolean()).isFalse();
        assertThat(none.path("version").asLong()).isEqualTo(-1);
        assertThat(none.path("installments")).singleElement().satisfies(i -> {
            assertThat(i.path("milestone").asText()).isEqualTo("ON_SIGNING");
            assertThat(i.path("percent").decimalValue()).isEqualByComparingTo("100");
        });

        save(customer, "SALES_STAFF", schedule(null, line("Đặt cọc", "30", "ON_SIGNING", 0),
                line("Trước giao", "60", "BEFORE_DELIVERY", 0))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        save(customer, "SALES_STAFF", schedule(null, line("Đặt cọc", "33.333", "ON_SIGNING", 0),
                line("Trước giao", "66.667", "BEFORE_DELIVERY", 0))).andExpect(status().isBadRequest());
        save(customer, "SALES_STAFF", schedule(-1L, line("Đặt cọc", "33.33", "ON_SIGNING", 0),
                line("Trước giao", "66.67", "BEFORE_DELIVERY", 3))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.approvedBy").doesNotExist());
        JsonNode set = read(customer, "ACCOUNTANT");
        assertThat(set.path("installments")).hasSize(2);
        assertThat(set.path("installments").get(1).path("dueDays").asInt()).isEqualTo(3);

        // A second "first" schedule from someone who did not read this one: 409, not a silent overwrite.
        save(customer, "SALES_STAFF", schedule(null, line("Ký", "100", "ON_SIGNING", 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        save(customer, "SALES_STAFF", schedule(set.path("version").asLong() + 5, line("Ký", "100", "ON_SIGNING", 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        mvc.perform(get("/api/v1/customers/" + customer + "/payment-schedule")
                .header("Authorization", bearer(user(), "WAREHOUSE_STAFF"))).andExpect(status().isForbidden());
        save(customer, "WAREHOUSE_STAFF", schedule(set.path("version").asLong(), line("Ký", "100", "ON_SIGNING", 0)))
                .andExpect(status().isForbidden());

        // A deposit customer nobody set a schedule for: their deposit on signing, the rest before delivery.
        UUID deposit = customer();
        customers.saveCreditTerms(new SaveCreditTermsCommand(deposit, true, true, false, CommercialTerm.DEPOSIT,
                new BigDecimal("30"), null, null, null, user(), null,
                com.stockflow.customer.api.DepositBalancePolicy.BEFORE_DELIVERY));
        assertThat(schedules.defaultPaymentSchedule(deposit).installments())
                .extracting(i -> i.milestone() + " " + i.percent().toPlainString())
                .containsExactly("ON_SIGNING 30.00", "BEFORE_DELIVERY 70.00");
    }

    @Test
    @DisplayName("after delivery: only on approved credit (a limit of 0 is none); Sales proposes, accounting approves "
            + "what it read, audited; the note may change, the installments not without approval again")
    void salesProposesAccountingApproves() throws Exception {
        UUID customer = customer();
        String credit = schedule(null, line("Ký", "30", "ON_SIGNING", 0), line("Công nợ", "70", "AFTER_DELIVERY", null));
        save(customer, "SALES_STAFF", credit).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_SCHEDULE_NEEDS_CREDIT"));
        var zero = terms(customer, true, "0", null);
        save(customer, "ACCOUNTANT", credit).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_SCHEDULE_NEEDS_CREDIT"));
        terms(customer, true, "50000000", zero.version());

        UUID sales = user();
        JsonNode proposed = data(save(customer, sales, "SALES_STAFF", credit).andExpect(status().isOk()));
        assertThat(absent(proposed.path("approvedBy"))).isTrue();
        assertThat(proposed.path("installments").get(1).path("dueDays").asInt())
                .as("the credit term, by default").isEqualTo(30);
        assertThat(schedules.defaultPaymentSchedule(customer).awaitingApproval()).isTrue();
        long version = proposed.path("version").asLong();

        approve(customer, user(), "SALES_MANAGER", version).andExpect(status().isForbidden());
        UUID accountant = user();
        approve(customer, accountant, "ACCOUNTANT", version + 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        JsonNode approved = data(approve(customer, accountant, "ACCOUNTANT", version).andExpect(status().isOk()));
        assertThat(approved.path("approvedBy").asText()).isEqualTo(accountant.toString());
        assertThat(approved.path("setBy").asText()).isEqualTo(sales.toString());
        assertThat(data(approve(customer, user(), "ACCOUNTANT", approved.path("version").asLong())
                .andExpect(status().isOk())).path("approvedBy").asText()).isEqualTo(accountant.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.audit_log WHERE resource_type = "
                + "'customer-payment-schedule' AND resource_id = ? AND action = 'APPROVE'", Long.class,
                customer.toString())).isPositive();

        // The note only: still approved. A share changed by Sales: it awaits accounting again.
        JsonNode noted = data(save(customer, "SALES_STAFF", scheduleWithNote(approved.path("version").asLong(),
                "giao trong tuần", line("Ký", "30", "ON_SIGNING", 0), line("Công nợ", "70", "AFTER_DELIVERY", 30)))
                .andExpect(status().isOk()));
        assertThat(noted.path("approvedBy").asText()).isEqualTo(accountant.toString());
        JsonNode changed = data(save(customer, "SALES_STAFF", schedule(noted.path("version").asLong(),
                line("Ký", "40", "ON_SIGNING", 0), line("Công nợ", "60", "AFTER_DELIVERY", 30)))
                .andExpect(status().isOk()));
        assertThat(absent(changed.path("approvedBy"))).isTrue();

        // An accountant's own schedule is approved as they save it.
        UUID other = user();
        JsonNode own = data(save(customer, other, "ACCOUNTANT", schedule(changed.path("version").asLong(),
                line("Ký", "20", "ON_SIGNING", 0), line("Công nợ", "80", "AFTER_DELIVERY", 45)))
                .andExpect(status().isOk()));
        assertThat(own.path("approvedBy").asText()).isEqualTo(other.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.audit_log WHERE resource_type = "
                + "'customer-payment-schedule' AND resource_id = ? AND action = 'UPDATE'", Long.class,
                customer.toString())).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("the credit is not taken away - nor lowered to 0 - while the schedule pays after delivery; terms that "
            + "do not hold are refused as such first; with the schedule back to signing it may go")
    void creditStaysWhileUsed() throws Exception {
        UUID customer = customer();
        var terms = terms(customer, true, "50000000", null);
        JsonNode set = data(save(customer, "ACCOUNTANT", schedule(null, line("Ký", "30", "ON_SIGNING", 0),
                line("Công nợ", "70", "AFTER_DELIVERY", 30))).andExpect(status().isOk()));

        assertThat(refusal(() -> terms(customer, false, null, terms.version())))
                .isEqualTo(ErrorCode.PAYMENT_SCHEDULE_NEEDS_CREDIT);
        assertThat(refusal(() -> terms(customer, true, "0", terms.version())))
                .isEqualTo(ErrorCode.PAYMENT_SCHEDULE_NEEDS_CREDIT);
        assertThat(refusal(() -> terms(customer, true, null, terms.version())))
                .as("credit without a limit is wrong terms, whatever the schedule")
                .isNotEqualTo(ErrorCode.PAYMENT_SCHEDULE_NEEDS_CREDIT);
        assertThat(customers.creditTerms(customer).creditLimit()).isEqualByComparingTo("50000000");

        // Nothing to approve on a schedule paying on signing; no schedule at all is not found.
        JsonNode back = data(save(customer, "SALES_STAFF", schedule(set.path("version").asLong(),
                line("Ký", "100", "ON_SIGNING", 0))).andExpect(status().isOk()));
        assertThat(absent(back.path("approvedBy"))).isTrue();
        approve(customer, user(), "ACCOUNTANT", back.path("version").asLong()).andExpect(status().isBadRequest());
        approve(customer(), user(), "ACCOUNTANT", 0).andExpect(status().isNotFound());
        terms(customer, false, null, terms.version());
        assertThat(customers.creditTerms(customer).allowCredit()).isFalse();
        assertThat(schedules.defaultPaymentSchedule(customer).installments()).singleElement()
                .satisfies(i -> assertThat(i.milestone()).isEqualTo(PaymentMilestone.ON_SIGNING));
    }
}
