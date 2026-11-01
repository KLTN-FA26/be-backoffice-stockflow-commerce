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
 * Supplier invoices (SCRUM-308/309) through the real filter chain and permission aspect: the accountant and
 * procurement staff hold {@code procurement-supplier-invoices}, the warehouse and customers do not.
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
    @DisplayName("the accountant lists invoices and reads an order's match basis; the warehouse and customers cannot")
    void readers() throws Exception {
        http.asRole(Role.ACCOUNTANT, staff, get("/api/v1/supplier-invoices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
        http.asRole(Role.ACCOUNTANT, staff, get("/api/v1/supplier-invoices/match-basis/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PURCHASE_ORDER_NOT_FOUND"));
        http.asRole(Role.WAREHOUSE_STAFF, staff, get("/api/v1/supplier-invoices")).andExpect(status().isForbidden());
        http.asCustomer(staff, get("/api/v1/supplier-invoices")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("procurement staff may enter one (the body is checked); a warehouse clerk may not")
    void writers() throws Exception {
        http.asRole(Role.PROCUREMENT_STAFF, staff, post("/api/v1/supplier-invoices")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        http.asRole(Role.WAREHOUSE_STAFF, staff, post("/api/v1/supplier-invoices")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[]}"))
                .andExpect(status().isForbidden());
        http.asRole(Role.WAREHOUSE_STAFF, staff, post("/api/v1/supplier-invoices/" + UUID.randomUUID() + "/match"))
                .andExpect(status().isForbidden());
    }
}
