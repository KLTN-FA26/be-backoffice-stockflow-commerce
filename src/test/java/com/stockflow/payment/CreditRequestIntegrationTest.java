package com.stockflow.payment;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.CreditRequestDecided;
import com.stockflow.contracts.CreditRequestSubmitted;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Credit requests over HTTP (QĐ-A, 2026-11-26; plan §4.7): the customer asks from the portal, Sales give their
 * opinion, the accountant approves and the customer's credit is set in the same transaction; rejected, withdrawn, one
 * open per customer, four eyes, two approvals on one read, another customer's request out of reach, and the review
 * figures (plan §4.4) on contracts signed and paid through the real flow.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class, RecordedEvents.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class CreditRequestIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired CustomerService customers;
    @Autowired RecordedEvents.Recorder events;

    private SecuredMvc http;
    private UUID portalUser;
    private UUID customer;
    private UUID otherPortalUser;
    private UUID otherCustomer;
    private UUID sales;
    private UUID accountant;
    private UUID secondAccountant;

    @BeforeEach
    void people() {
        http = new SecuredMvc(mvc, decoder);
        portalUser = user();
        otherPortalUser = user();
        sales = user();
        accountant = user();
        secondAccountant = user();
        customer = customer(portalUser, "Credit");
        otherCustomer = customer(otherPortalUser, "Other");
    }

    private UUID user() {
        return tx.execute(s -> ReferenceRows.user(entityManager));
    }

    private UUID customer(UUID portalAccount, String name) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0, NOW())""", id, portalAccount, name + " " + id,
                "credit-" + id + "@example.com"));
        return id;
    }

    // ---- HTTP -----------------------------------------------------------------------------------------------------

    private String call(Role role, UUID user, MockHttpServletRequestBuilder request, ResultMatcher expected)
            throws Exception {
        return http.asRole(role, user, request).andExpect(expected).andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String fileOwn(String limit) throws Exception {
        return call(Role.CUSTOMER, portalUser, json(post("/api/v1/credit-requests"), """
                {"requestedLimit": %s, "requestedTermDays": 30, "reason": "Mở rộng đơn hàng",
                 "customerId": "%s", "salesNote": "not mine to write"}""".formatted(limit, otherCustomer)),
                status().isCreated());
    }

    private String fileBySales(UUID forCustomer, String limit) throws Exception {
        return call(Role.SALES_STAFF, sales, json(post("/api/v1/credit-requests"), """
                {"customerId": "%s", "requestedLimit": %s, "requestedTermDays": 30,
                 "salesNote": "Khách lâu năm, trả đúng hạn"}""".formatted(forCustomer, limit)), status().isCreated());
    }

    private String recommend(String id, int version) throws Exception {
        return call(Role.SALES_STAFF, sales, json(post("/api/v1/credit-requests/" + id + "/recommendation"),
                "{\"version\": %d, \"note\": \"Đồng ý, khách trả đúng hạn\"}".formatted(version)), status().isOk());
    }

    private String approve(UUID by, String id, int version, String limit, ResultMatcher expected) throws Exception {
        return call(Role.ACCOUNTANT, by, json(post("/api/v1/credit-requests/" + id + "/approval"), """
                {"version": %d, "approvedLimit": %s, "approvedTermDays": 45, "note": "Duyệt theo đề nghị",
                 "creditTermsVersion": -1}""".formatted(version, limit)), expected);
    }

    private static String id(String body) {
        return JsonPath.read(body, "$.data.id");
    }

    private static int version(String body) {
        return ((Number) JsonPath.read(body, "$.data.version")).intValue();
    }

    private static String stateOf(String body) {
        return JsonPath.read(body, "$.data.status");
    }

    /** A null field is left out of the answer altogether. */
    private static boolean absent(String body, String path) {
        try {
            return JsonPath.read(body, path) == null;
        } catch (com.jayway.jsonpath.PathNotFoundException missing) {
            return true;
        }
    }

    private static String errorCode(String body) {
        return JsonPath.read(body, "$.errorCode");
    }

    private long inbox(UUID recipient, String requestId) {
        return jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? "
                + "AND category = 'CREDIT_REQUEST' AND source_id = ?", Long.class, recipient, requestId);
    }

    // ---- the flow -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the customer asks, Sales give their opinion, the accountant approves: the customer's credit is set")
    void portalToApproved() throws Exception {
        String filed = fileOwn("500000000");
        String id = id(filed);
        assertThat(stateOf(filed)).isEqualTo("SUBMITTED");
        assertThat((String) JsonPath.read(filed, "$.data.customerId")).as("filed for the caller's own customer, "
                + "whatever the body names").isEqualTo(customer.toString());
        assertThat(absent(filed, "$.data.review")).as("no review figures for the customer").isTrue();
        assertThat(events.matching(CreditRequestSubmitted.class, e -> e.creditRequestId().toString().equals(id)))
                .singleElement().extracting(CreditRequestSubmitted::stage)
                .isEqualTo(CreditRequestSubmitted.Stage.AWAITING_SALES);

        String opinion = recommend(id, version(filed));
        assertThat(stateOf(opinion)).isEqualTo("PENDING_APPROVAL");
        assertThat((String) JsonPath.read(opinion, "$.data.salesNote")).isEqualTo("Đồng ý, khách trả đúng hạn");

        String review = call(Role.ACCOUNTANT, accountant, get("/api/v1/credit-requests/" + id), status().isOk());
        assertThat(((Number) JsonPath.read(review, "$.data.review.creditTermsVersion")).longValue()).isEqualTo(-1L);
        assertThat((List<String>) JsonPath.read(review, "$.data.review.warnings"))
                .contains("SHORT_HISTORY", "ABOVE_SUGGESTED");

        String approved = approve(accountant, id, version(opinion), "400000000", status().isOk());
        assertThat(stateOf(approved)).isEqualTo("APPROVED");
        CreditTerms terms = customers.creditTerms(customer);
        assertThat(terms.configured()).isTrue();
        assertThat(terms.creditLimit()).isEqualByComparingTo("400000000");
        assertThat(terms.creditTermDays()).isEqualTo(45);
        assertThat(terms.approvedBy()).isEqualTo(accountant);
        assertThat(customers.creditTerms(otherCustomer).configured()).as("the body's customer untouched").isFalse();
        assertThat(events.matching(CreditRequestDecided.class, e -> e.creditRequestId().toString().equals(id)))
                .singleElement().extracting(CreditRequestDecided::outcome)
                .isEqualTo(CreditRequestDecided.Outcome.APPROVED);

        Await.until("the customer and Sales are told", Duration.ofSeconds(10),
                () -> inbox(portalUser, id) == 1 && inbox(sales, id) == 1);

        String seen = call(Role.CUSTOMER, portalUser, get("/api/v1/credit-requests/" + id), status().isOk());
        assertThat(absent(seen, "$.data.salesNote")).as("Sales' opinion is not the customer's").isTrue();
        assertThat(absent(seen, "$.data.decidedBy")).isTrue();
        assertThat((String) JsonPath.read(seen, "$.data.decisionNote")).isEqualTo("Duyệt theo đề nghị");
        assertThat(((Number) JsonPath.read(seen, "$.data.approvedLimit")).longValue()).isEqualTo(400_000_000L);
    }

    @Test
    @DisplayName("audit bo-28: a back-office row carries the customer's credit limit now, read for the page; none "
            + "without credit, never for the portal")
    void rowsCarryTheCurrentLimit() throws Exception {
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, new BigDecimal("250000000"), 30,
                "set before the request", accountant, null));
        String withCredit = id(fileBySales(customer, "300000000"));
        String withoutCredit = id(fileBySales(otherCustomer, "100000000"));

        String rows = call(Role.ACCOUNTANT, accountant, get("/api/v1/credit-requests")
                .param("customerId", customer.toString()), status().isOk());
        List<Number> limits = JsonPath.read(rows, "$.data.items[?(@.id == '%s')].currentCreditLimit"
                .formatted(withCredit));
        assertThat(limits).singleElement().satisfies(limit ->
                assertThat(new BigDecimal(limit.toString())).isEqualByComparingTo("250000000"));
        String others = call(Role.ACCOUNTANT, accountant, get("/api/v1/credit-requests")
                .param("customerId", otherCustomer.toString()), status().isOk());
        assertThat((List<Object>) JsonPath.read(others, "$.data.items[?(@.id == '%s')]".formatted(withoutCredit)))
                .hasSize(1);
        assertThat(absent(others, "$.data.items[0].currentCreditLimit")).as("no credit, no limit").isTrue();

        String detail = call(Role.ACCOUNTANT, accountant, get("/api/v1/credit-requests/" + withCredit),
                status().isOk());
        assertThat(new BigDecimal(JsonPath.read(detail, "$.data.currentCreditLimit").toString()))
                .as("the detail says the same as its row").isEqualByComparingTo("250000000");

        String mine = call(Role.CUSTOMER, portalUser, get("/api/v1/credit-requests/mine"), status().isOk());
        assertThat(absent(mine, "$.data.items[0].currentCreditLimit")).as("the portal's rows leave it out").isTrue();
    }

    @Test
    @DisplayName("Sales ask for the customer; the accountant rejects with a reason and the credit is unchanged")
    void rejected() throws Exception {
        String filed = fileBySales(customer, "300000000");
        String id = id(filed);
        assertThat(stateOf(filed)).isEqualTo("PENDING_APPROVAL");
        call(Role.ACCOUNTANT, accountant, json(post("/api/v1/credit-requests/" + id + "/rejection"),
                "{\"version\": %d, \"reason\": \" \"}".formatted(version(filed))), status().isBadRequest());
        String rejected = call(Role.ACCOUNTANT, accountant, json(post("/api/v1/credit-requests/" + id + "/rejection"),
                "{\"version\": %d, \"reason\": \"Đang có nợ quá hạn\"}".formatted(version(filed))), status().isOk());
        assertThat(stateOf(rejected)).isEqualTo("REJECTED");
        assertThat((String) JsonPath.read(rejected, "$.data.decisionNote")).isEqualTo("Đang có nợ quá hạn");
        assertThat(customers.creditTerms(customer).configured()).isFalse();
        assertThat(events.matching(CreditRequestDecided.class, e -> e.creditRequestId().toString().equals(id)))
                .singleElement().extracting(CreditRequestDecided::outcome)
                .isEqualTo(CreditRequestDecided.Outcome.REJECTED);
    }

    @Test
    @DisplayName("granted at most the limit asked; never decided by whoever filed it or gave Sales' opinion")
    void limitsOfTheDecision() throws Exception {
        String filed = fileBySales(customer, "300000000");
        String id = id(filed);
        assertThat(errorCode(approve(accountant, id, version(filed), "300000001", status().isConflict())))
                .isEqualTo("CREDIT_REQUEST_ABOVE_REQUESTED");
        // The System Admin holds every permission: one who filed the request may not decide it.
        String own = call(Role.SYSTEM_ADMIN, secondAccountant, json(post("/api/v1/credit-requests"), """
                {"customerId": "%s", "requestedLimit": 100, "requestedTermDays": 0, "salesNote": "ok"}"""
                .formatted(otherCustomer)), status().isCreated());
        String self = call(Role.SYSTEM_ADMIN, secondAccountant,
                json(post("/api/v1/credit-requests/" + id(own) + "/approval"), """
                {"version": %d, "approvedLimit": 100, "approvedTermDays": 0}""".formatted(version(own))),
                status().isConflict());
        assertThat(errorCode(self)).isEqualTo("CREDIT_REQUEST_SELF_DECISION");
        assertThat(customers.creditTerms(customer).configured()).isFalse();
    }

    @Test
    @DisplayName("two accountants on one read: the second is refused OPTIMISTIC_LOCK and the first's credit stands")
    void twoApprovals() throws Exception {
        String filed = fileBySales(customer, "300000000");
        String id = id(filed);
        int read = version(filed);
        approve(accountant, id, read, "200000000", status().isOk());
        assertThat(errorCode(approve(secondAccountant, id, read, "300000000", status().isConflict())))
                .isEqualTo("OPTIMISTIC_LOCK");
        assertThat(customers.creditTerms(customer).creditLimit()).isEqualByComparingTo("200000000");
    }

    @Test
    @DisplayName("credit changed through the profile form since it was read: OPTIMISTIC_LOCK, nothing written")
    void termsChangedSinceRead() throws Exception {
        String filed = fileBySales(customer, "300000000");
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, new BigDecimal("50000000"), 15,
                "set by hand meanwhile", accountant, null));
        assertThat(errorCode(approve(secondAccountant, id(filed), version(filed), "300000000", status().isConflict())))
                .isEqualTo("OPTIMISTIC_LOCK");
        assertThat(customers.creditTerms(customer).creditLimit()).isEqualByComparingTo("50000000");
        assertThat(stateOf(call(Role.ACCOUNTANT, accountant, get("/api/v1/credit-requests/" + id(filed)),
                status().isOk()))).as("rolled back with it").isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("one open request per customer; withdrawn, the customer may ask again")
    void oneOpen() throws Exception {
        String first = fileOwn("100000000");
        assertThat(errorCode(call(Role.CUSTOMER, portalUser, json(post("/api/v1/credit-requests"),
                "{\"requestedLimit\": 1, \"requestedTermDays\": 0}"), status().isConflict())))
                .isEqualTo("CREDIT_REQUEST_ALREADY_OPEN");
        assertThat(errorCode(call(Role.SALES_STAFF, sales, json(post("/api/v1/credit-requests"), """
                {"customerId": "%s", "requestedLimit": 1, "requestedTermDays": 0, "salesNote": "ok"}"""
                .formatted(customer)), status().isConflict()))).isEqualTo("CREDIT_REQUEST_ALREADY_OPEN");
        assertThat(errorCode(call(Role.SALES_STAFF, sales, json(post("/api/v1/credit-requests/" + id(first)
                + "/withdrawal"), "{\"version\": 0}"), status().isForbidden()))).as("not Sales' to take back")
                .isEqualTo("CREDIT_REQUEST_NOT_FILER");
        String withdrawn = call(Role.CUSTOMER, portalUser, json(post("/api/v1/credit-requests/" + id(first)
                + "/withdrawal"), "{\"version\": %d}".formatted(version(first))), status().isOk());
        assertThat(stateOf(withdrawn)).isEqualTo("WITHDRAWN");
        assertThat(stateOf(fileOwn("100000000"))).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("another customer's request: 404 to read or withdraw, absent from /mine; the back-office list 403")
    void anotherCustomers() throws Exception {
        String id = id(fileOwn("100000000"));
        call(Role.CUSTOMER, otherPortalUser, get("/api/v1/credit-requests/" + id), status().isNotFound());
        call(Role.CUSTOMER, otherPortalUser, json(post("/api/v1/credit-requests/" + id + "/withdrawal"),
                "{\"version\": 0}"), status().isNotFound());
        call(Role.CUSTOMER, otherPortalUser, get("/api/v1/credit-requests/" + id + "/files/" + UUID.randomUUID()
                + "/download-url"), status().isNotFound());
        assertThat(call(Role.CUSTOMER, otherPortalUser, get("/api/v1/credit-requests/mine"), status().isOk()))
                .doesNotContain(id);
        assertThat(call(Role.CUSTOMER, portalUser, get("/api/v1/credit-requests/mine"), status().isOk()))
                .contains(id);
        call(Role.CUSTOMER, portalUser, get("/api/v1/credit-requests"), status().isForbidden());
        call(Role.CUSTOMER, portalUser, json(post("/api/v1/credit-requests/" + id + "/recommendation"),
                "{\"version\": 0, \"note\": \"mine\"}"), status().isForbidden());
    }

    @Test
    @DisplayName("review figures (plan §4.4): two contracts settled, one paid on its due day and one three days late")
    void reviewFigures() throws Exception {
        UUID payer = customer(user(), "Payer");
        ConfirmedOrders orders = new ConfirmedOrders(context);
        var sku = CustomerSkuRows.issue(jdbc, tx, entityManager, payer, 50);
        var onTime = orders.signed(payer, ConfirmedOrders.Line.cartons(sku, 2, 10_000));
        var late = orders.signed(payer, ConfirmedOrders.Line.cartons(sku, 4, 10_000));
        // Its due day three days back, written below the domain with its triggers off (as
        // OverdueInstallmentNoticeIntegrationTest moves one): the application never moves an installment on signing.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE payment.installment SET due_date = due_date - 3 WHERE id = ?", late.seq(1));
        });
        BigDecimal first = owed(onTime.seq(1));
        BigDecimal second = owed(late.seq(1));
        orders.pay(payer, onTime.seq(1), first);
        orders.pay(payer, late.seq(1), second);

        String filed = fileBySales(payer, "1");
        String review = call(Role.ACCOUNTANT, accountant, get("/api/v1/credit-requests/" + id(filed)),
                status().isOk());
        assertThat(((Number) JsonPath.read(review, "$.data.review.settledContracts")).intValue()).isEqualTo(2);
        assertThat(((Number) JsonPath.read(review, "$.data.review.paidInstallments")).intValue()).isEqualTo(2);
        assertThat(((Number) JsonPath.read(review, "$.data.review.onTimeInstallments")).intValue()).isEqualTo(1);
        assertThat(new BigDecimal(JsonPath.read(review, "$.data.review.onTimeRatio").toString()))
                .isEqualByComparingTo("0.5");
        assertThat(new BigDecimal(JsonPath.read(review, "$.data.review.averageDaysLate").toString()))
                .isEqualByComparingTo("3");
        assertThat(new BigDecimal(JsonPath.read(review, "$.data.review.paidLast12Months").toString()))
                .isEqualByComparingTo(first.add(second));
        assertThat((Boolean) JsonPath.read(review, "$.data.review.hasOverdue")).isFalse();
        assertThat((List<String>) JsonPath.read(review, "$.data.review.warnings")).as("two settled: history enough")
                .isEmpty();
    }

    private BigDecimal owed(UUID installmentId) {
        return jdbc.queryForObject("SELECT amount - paid_amount - reduced_amount FROM payment.installment WHERE id = ?",
                BigDecimal.class, installmentId);
    }
}
