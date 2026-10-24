package com.stockflow.order;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Returns (SCRUM-243) through the real filter chain, permission aspect and data scope: the portal customer reaches
 * only their own orders and returns, the back-office list is staff's, and each step is held by the role whose step it
 * is (03 §12) - Sales review, the warehouse counts, the Accountant approves money.
 */
@SecuredIntegrationTest
class ReturnRequestHttpTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc http;
    private UUID userA;
    private UUID userB;
    private UUID staff;
    private UUID orderOfA;
    private UUID lineOfA;

    @BeforeEach
    void delivered() {
        http = new SecuredMvc(mvc, decoder);
        userA = tx.execute(s -> ReferenceRows.user(entityManager));
        userB = tx.execute(s -> ReferenceRows.user(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID customerA = customerOf(userA);
        customerOf(userB);
        orderOfA = Identifiers.newId();
        lineOfA = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                        currency, placed_at, payment_term, paid_amount, paid_in_full_at, warehouse_id, released_at,
                        released_by, packed_at, shipped_at, delivered_at, version, created_at)
                    VALUES (?, ?, ?, ?, 'DELIVERED', 3000000, 'VND', NOW() - interval '5 days', 'PREPAID', 3000000,
                        NOW() - interval '5 days', ?, NOW() - interval '4 days', ?, NOW() - interval '3 days',
                        NOW() - interval '2 days', NOW() - interval '1 day', 0, NOW())""",
                    orderOfA, "SO-20261023-%06d".formatted(ThreadLocalRandom.current().nextInt(100_000, 999_999)),
                    customerA, UUID.randomUUID(), DemoData.WAREHOUSE_HCM, staff);
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                    VALUES (?, ?, 'SOFA-3S-GREY', 3, 1000000, 'VND')""", lineOfA, orderOfA);
        });
    }

    private UUID customerOf(UUID user) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Portal customer', ?, 'ACTIVE', 0, NOW())""", id, user, "rma-" + id + "@example.com"));
        return id;
    }

    private String claim(int units) {
        return """
                {"requestId":"%s","reasonCode":"DAMAGED_IN_TRANSIT","description":"Móp hộp",
                 "requestedResolution":"REFUND","lines":[{"orderLineId":"%s","quantity":%d}]}"""
                .formatted(UUID.randomUUID(), lineOfA, units);
    }

    private String open() throws Exception {
        String body = http.asCustomer(userA, post("/api/v1/orders/" + orderOfA + "/returns")
                        .contentType(MediaType.APPLICATION_JSON).content(claim(1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.rmaNumber").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.data.id");
    }

    @Test
    @DisplayName("the customer opens a return on their own order, sees it in their list; another customer gets 404 for "
            + "the order and the return; the back-office list is not theirs (403)")
    void portalScope() throws Exception {
        String returnId = open();
        http.asCustomer(userA, get("/api/v1/returns/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(returnId));
        http.asCustomer(userA, get("/api/v1/returns/me/" + returnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].requestedQuantity").value(1))
                .andExpect(jsonPath("$.data.history[0].status").value("REQUESTED"));

        http.asCustomer(userB, post("/api/v1/orders/" + orderOfA + "/returns")
                        .contentType(MediaType.APPLICATION_JSON).content(claim(1)))
                .andExpect(status().isNotFound());
        http.asCustomer(userB, get("/api/v1/returns/me/" + returnId)).andExpect(status().isNotFound());
        http.asCustomer(userB, post("/api/v1/returns/" + returnId + "/cancellation")).andExpect(status().isNotFound());
        http.asCustomer(userA, get("/api/v1/returns")).andExpect(status().isForbidden());
        http.asCustomer(userA, post("/api/v1/returns/" + returnId + "/approval")).andExpect(status().isForbidden());

        http.asCustomer(userA, post("/api/v1/returns/" + returnId + "/cancellation")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Đã xử lý được\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("each step is its role's: Sales review (a photo first), the warehouse cannot approve, the Accountant "
            + "cannot propose, QC cannot count a parcel")
    void stepsByRole() throws Exception {
        String returnId = open();
        http.asRole(Role.WAREHOUSE_STAFF, staff, post("/api/v1/returns/" + returnId + "/approval"))
                .andExpect(status().isForbidden());
        http.asRole(Role.SALES_STAFF, staff, post("/api/v1/returns/" + returnId + "/approval"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RETURN_EVIDENCE_REQUIRED"));
        http.asRole(Role.ACCOUNTANT, staff, post("/api/v1/returns/" + returnId + "/resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"lineId\":\"" + UUID.randomUUID() + "\",\"replacementQuantity\":0,"
                                + "\"creditQuantity\":1}],\"moneyMode\":\"REFUND\"}"))
                .andExpect(status().isForbidden());
        http.asRole(Role.QC_STAFF, staff, post("/api/v1/returns/" + returnId + "/receipt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"lineId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}],"
                                + "\"version\":0}"))
                .andExpect(status().isForbidden());
        http.asRole(Role.WAREHOUSE_STAFF, staff, get("/api/v1/returns/" + returnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history[0].to").value("REQUESTED"));
        http.asRole(Role.SALES_STAFF, staff, post("/api/v1/returns/" + returnId + "/rejection")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Ngoài chính sách\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.review.rejectionReason").value("Ngoài chính sách"));
    }
}
