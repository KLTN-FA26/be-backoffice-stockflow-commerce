package com.stockflow.inventory;

import com.stockflow.common.security.Role;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V20261014001200: the warehouse staff, who may request an adjustment, may also read the adjustments,
 * but deciding one stays with the holder of {@code inventory-stock-adjustments:APPROVE}.
 */
@SecuredIntegrationTest
class StaffAdjustmentAccessIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private SecuredMvc mvc;
    private final UUID clerk = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
    }

    @Test
    @DisplayName("the clerk lists stock adjustments")
    void clerkListsAdjustments() throws Exception {
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get("/api/v1/inventory/adjustments"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the clerk opens one adjustment (an unknown id is a 404, not a 403)")
    void clerkReadsOneAdjustment() throws Exception {
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get("/api/v1/inventory/adjustments/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the clerk still cannot approve or reject an adjustment")
    void clerkCannotDecide() throws Exception {
        UUID adjustment = UUID.randomUUID();
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, post("/api/v1/inventory/adjustments/" + adjustment + "/approval"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, post("/api/v1/inventory/adjustments/" + adjustment + "/rejection")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"not mine to decide\"}"))
                .andExpect(status().isForbidden());
    }
    @Test
    @DisplayName("4A-8: an adjustment for SUBCONTRACT_LOSS is refused, 400 VALIDATION_FAILED: subcontracting is gone")
    void subcontractLossIsRefused() throws Exception {
        var stock = jdbc.queryForMap("SELECT sku, location_code FROM inventory.stock_item WHERE on_hand > 0 "
                + "AND status = 'AVAILABLE' ORDER BY sku LIMIT 1");
        String body = "{\"locationCode\":\"%s\",\"sku\":\"%s\",\"quantityDelta\":-1,"
                .formatted(stock.get("location_code"), stock.get("sku")) + "\"reason\":\"SUBCONTRACT_LOSS\"}";

        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, post("/api/v1/inventory/adjustments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }
}
