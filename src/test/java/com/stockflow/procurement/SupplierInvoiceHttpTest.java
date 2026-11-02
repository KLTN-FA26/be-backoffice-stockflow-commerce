package com.stockflow.procurement;

import com.stockflow.common.security.Role;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Supplier invoices (SCRUM-308/309) through the real filter chain and permission aspect: the accountant enters and
 * matches them (decision 2026-11-02, kltn-docs 04 §2), procurement staff only read, the warehouse and customers do
 * not hold {@code procurement-supplier-invoices} at all.
 */
@SecuredIntegrationTest
class SupplierInvoiceHttpTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc http;
    private UUID staff;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
    }

    @Test
    @DisplayName("the accountant and procurement staff list invoices; the accountant reads an order's match basis; the "
            + "warehouse and customers cannot")
    void readers() throws Exception {
        http.asRole(Role.ACCOUNTANT, staff, get("/api/v1/supplier-invoices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
        http.asRole(Role.ACCOUNTANT, staff, get("/api/v1/supplier-invoices/match-basis/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PURCHASE_ORDER_NOT_FOUND"));
        http.asRole(Role.PROCUREMENT_STAFF, staff, get("/api/v1/supplier-invoices")).andExpect(status().isOk());
        http.asRole(Role.WAREHOUSE_STAFF, staff, get("/api/v1/supplier-invoices")).andExpect(status().isForbidden());
        http.asCustomer(staff, get("/api/v1/supplier-invoices")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("only the accountant enters and matches (the body is checked); procurement staff and a warehouse "
            + "clerk may not")
    void writers() throws Exception {
        http.asRole(Role.ACCOUNTANT, staff, post("/api/v1/supplier-invoices")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        // The permission aspect runs after the body is bound and validated, so a refusal is only seen with a valid
        // body: an invalid one answers 400 whoever sends it.
        for (Role role : new Role[] {Role.PROCUREMENT_STAFF, Role.WAREHOUSE_STAFF}) {
            http.asRole(role, staff, post("/api/v1/supplier-invoices")
                            .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                    .andExpect(status().isForbidden());
            http.asRole(role, staff, post("/api/v1/supplier-invoices/" + UUID.randomUUID() + "/match"))
                    .andExpect(status().isForbidden());
            http.asRole(role, staff, post("/api/v1/supplier-invoices/" + UUID.randomUUID() + "/cancel")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"not mine\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    private static String validBody() {
        return """
                {"supplierId":"%s","invoiceNumber":"HD-1","invoiceDate":"2026-01-02",
                 "lines":[{"purchaseOrderLineId":"%s","quantity":1,"unitPrice":1000}]}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID());
    }
}
