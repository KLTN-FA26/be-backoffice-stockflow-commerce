package com.stockflow.inventory;

import com.stockflow.common.security.Role;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V20261209000300 (kltn-docs 20 §3): the lot trace report answers {@code inventory-lot-traces:READ}, held by the
 * warehouse, Sales (customer care) and the Production Coordinator; the stock ledger itself stays the warehouse's.
 */
@SecuredIntegrationTest
class LotTraceAccessIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;

    private SecuredMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
    }

    @Test
    @DisplayName("Sales and the Production Coordinator trace a lot (an unknown lot is a 404, not a 403)")
    void salesAndCoordinatorTraceALot() throws Exception {
        for (Role role : new Role[] {Role.SALES_STAFF, Role.PRODUCTION_COORDINATOR}) {
            UUID caller = UUID.randomUUID();
            mvc.asRole(role, caller, get("/api/v1/inventory/lots")).andExpect(status().isOk());
            mvc.asRole(role, caller, get("/api/v1/inventory/lots/" + UUID.randomUUID())).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("the stock ledger itself stays the warehouse's")
    void theLedgerStaysTheWarehouses() throws Exception {
        for (Role role : new Role[] {Role.SALES_STAFF, Role.PRODUCTION_COORDINATOR}) {
            mvc.asRole(role, UUID.randomUUID(), get("/api/v1/inventory/movements"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("the warehouse staff still trace a lot")
    void warehouseStaffStillTrace() throws Exception {
        mvc.asRole(Role.WAREHOUSE_STAFF, UUID.randomUUID(), get("/api/v1/inventory/lots"))
                .andExpect(status().isOk());
    }
}
