package com.stockflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-190 (kltn-docs 14 edge case "trùng đơn"): placing what the same customer placed within the window is
 * refused with 409 {@code DUPLICATE_ORDER_SUSPECTED} naming the earlier order, unless the request confirms it.
 * Through the real filter chain, as the storefront calls it.
 */
@SecuredIntegrationTest
class DuplicateOrderIntegrationTest {

    private static final String ORDERS = "/api/v1/orders";
    private static final String SOFA = """
            {"sku":"SOFA-3S-GREY","quantity":%d,"unitPrice":12500000}""";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired com.stockflow.customer.api.CustomerService customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private UUID user;
    private UUID customer;
    private UUID otherUser;
    private UUID otherCustomer;
    private final List<UUID> placed = new ArrayList<>();

    @BeforeEach
    void twoCustomersWithASignIn() {
        user = tx.execute(s -> ReferenceRows.user(entityManager));
        otherUser = tx.execute(s -> ReferenceRows.user(entityManager));
        customer = customerOf(user);
        otherCustomer = customerOf(otherUser);
    }

    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            if (orders.findById(id).orElseThrow().status() != OrderStatus.CANCELLED) {
                orders.cancel(id, "TEST_CLEANUP");
            }
        }));
    }

    private UUID customerOf(UUID account) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Duplicate check customer', ?, 'ACTIVE', 0, NOW())""",
                id, account, "dup-" + id + "@example.com"));
        customers.addAddress(id, null, true, new com.stockflow.customer.api.SaveAddressCommand("SHIPPING",
                "Nguyen Van A", "0901234567", "1 Le Loi", null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null,
                true, null));
        return id;
    }

    private SecuredMvc http() {
        return new SecuredMvc(mvc, decoder);
    }

    private static String lines(String... lines) {
        return "[" + String.join(",", lines) + "]";
    }

    private static String sofa(int quantity) {
        return SOFA.formatted(quantity);
    }

    private String body(UUID requestId, UUID forCustomer, String lines, String extra) {
        return """
                {"requestId":"%s","customerId":"%s","lines":%s%s}""".formatted(requestId, forCustomer, lines, extra);
    }

    private ResultActions place(UUID account, UUID forCustomer, String lines, String extra) throws Exception {
        return placeWith(account, UUID.randomUUID(), forCustomer, lines, extra);
    }

    private ResultActions placeWith(UUID account, UUID requestId, UUID forCustomer, String lines, String extra)
            throws Exception {
        return http().asCustomer(account, post(ORDERS).contentType(MediaType.APPLICATION_JSON)
                .content(body(requestId, forCustomer, lines, extra)));
    }

    private UUID created(ResultActions result) throws Exception {
        JsonNode data = json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()).get("data");
        UUID id = UUID.fromString(data.get("orderId").asText());
        placed.add(id);
        return id;
    }

    private String numberOf(UUID orderId) {
        return jdbc.queryForObject("SELECT order_number FROM ordering.customer_order WHERE id = ?", String.class,
                orderId);
    }

    private void age(UUID orderId, String interval) {
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET placed_at = placed_at - CAST(? AS interval) WHERE id = ?",
                interval, orderId));
    }

    private long orderCount(UUID forCustomer) {
        return jdbc.queryForObject("SELECT count(*) FROM ordering.customer_order WHERE customer_id = ?", Long.class,
                forCustomer);
    }

    @Test
    @DisplayName("the same lines again within the window: 409 DUPLICATE_ORDER_SUSPECTED naming the earlier order; "
            + "nothing is created")
    void theSameOrderAgainIsRefused() throws Exception {
        UUID first = created(place(user, customer, lines(sofa(2)), ""));

        place(user, customer, lines(sofa(2)), "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_ORDER_SUSPECTED"))
                .andExpect(jsonPath("$.details.orderId").value(first.toString()))
                .andExpect(jsonPath("$.details.orderNumber").value(numberOf(first)))
                .andExpect(jsonPath("$.details.placedAt").isString());
        assertThat(orderCount(customer)).isEqualTo(1);
        // confirmDuplicate: false is the same as absent
        place(user, customer, lines(sofa(2)), ",\"confirmDuplicate\":false").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_ORDER_SUSPECTED"));
        assertThat(orderCount(customer)).isEqualTo(1);
    }

    @Test
    @DisplayName("confirmDuplicate=true places the second order")
    void confirmingPlacesIt() throws Exception {
        UUID first = created(place(user, customer, lines(sofa(2)), ""));
        UUID second = created(place(user, customer, lines(sofa(2)), ",\"confirmDuplicate\":true"));
        assertThat(second).isNotEqualTo(first);
        assertThat(orderCount(customer)).isEqualTo(2);
    }

    @Test
    @DisplayName("a retry of the request that placed the order returns that order, it is not its own duplicate")
    void aRetryIsNotADuplicate() throws Exception {
        UUID request = UUID.randomUUID();
        UUID first = created(placeWith(user, request, customer, lines(sofa(2)), ""));
        UUID again = created(placeWith(user, request, customer, lines(sofa(2)), ""));
        assertThat(again).isEqualTo(first);
        assertThat(orderCount(customer)).isEqualTo(1);
    }

    @Test
    @DisplayName("different content is not a duplicate: another quantity")
    void anotherQuantityIsAnotherOrder() throws Exception {
        created(place(user, customer, lines(sofa(2)), ""));
        created(place(user, customer, lines(sofa(3)), ""));
        assertThat(orderCount(customer)).isEqualTo(2);
    }

    @Test
    @DisplayName("lines are compared as a set: the same SKU split over two lines equals one line of the total")
    void linesAreComparedAsASet() throws Exception {
        UUID first = created(place(user, customer, lines(sofa(2)), ""));
        place(user, customer, lines(sofa(1), sofa(1)), "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.orderId").value(first.toString()));
    }

    @Test
    @DisplayName("outside the window it is not a duplicate")
    void outsideTheWindow() throws Exception {
        UUID first = created(place(user, customer, lines(sofa(2)), ""));
        age(first, "25 hours");
        created(place(user, customer, lines(sofa(2)), ""));
        assertThat(orderCount(customer)).isEqualTo(2);
    }

    @Test
    @DisplayName("a cancelled order does not count")
    void aCancelledOrderDoesNotCount() throws Exception {
        UUID first = created(place(user, customer, lines(sofa(2)), ""));
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(first, "TEST_CANCEL"));
        created(place(user, customer, lines(sofa(2)), ""));
        assertThat(orderCount(customer)).isEqualTo(2);
    }

    @Test
    @DisplayName("only the customer's own orders count: another customer's identical order is no duplicate")
    void anotherCustomersOrderDoesNotCount() throws Exception {
        created(place(otherUser, otherCustomer, lines(sofa(2)), ""));
        created(place(user, customer, lines(sofa(2)), ""));
        assertThat(orderCount(customer)).isEqualTo(1);
    }

    @Test
    @DisplayName("OWN scope intact: a customer cannot place for another customer (404), duplicate or not")
    void ownScopeIsIntact() throws Exception {
        created(place(otherUser, otherCustomer, lines(sofa(2)), ""));
        place(user, otherCustomer, lines(sofa(2)), "").andExpect(status().isNotFound());
        place(user, otherCustomer, lines(sofa(2)), ",\"confirmDuplicate\":true").andExpect(status().isNotFound());
        assertThat(orderCount(otherCustomer)).isEqualTo(1);
    }

    @Test
    @DisplayName("staff placing for a customer are not asked to confirm")
    void staffAreNotAsked() throws Exception {
        created(place(user, customer, lines(sofa(2)), ""));
        UUID staff = tx.execute(s -> ReferenceRows.user(entityManager));
        created(http().asRole(Role.SALES_STAFF, staff, post(ORDERS).contentType(MediaType.APPLICATION_JSON)
                .content(body(UUID.randomUUID(), customer, lines(sofa(2)), ""))));
        assertThat(orderCount(customer)).isEqualTo(2);
    }
}
