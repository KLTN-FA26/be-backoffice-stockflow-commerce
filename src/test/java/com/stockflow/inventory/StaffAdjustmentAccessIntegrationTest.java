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
    @org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.support.TransactionTemplate tx;

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
        // An adjustment names a loose layer (a carton is only ever changed by a count), and every layer has a lot.
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, "
                + "received_at, on_hand, reserved, status, version, created_at) VALUES (gen_random_uuid(), "
                + "'SOFA-3S-GREY', 'HCM-A01-1-B', 'L-ADJ-' || substr(gen_random_uuid()::text, 1, 8), now() - interval "
                + "'1 hour', 3, 0, 'AVAILABLE', 0, now())"));
        var stock = jdbc.queryForMap("SELECT sku, location_code, lot_number FROM inventory.stock_item WHERE lot_number "
                + "LIKE 'L-ADJ-%' ORDER BY created_at DESC LIMIT 1");
        String body = "{\"locationCode\":\"%s\",\"sku\":\"%s\",\"lotNumber\":\"%s\",\"quantityDelta\":-1,"
                .formatted(stock.get("location_code"), stock.get("sku"), stock.get("lot_number"))
                + "\"reason\":\"SUBCONTRACT_LOSS\"}";

        mvc.asRole(Role.SYSTEM_ADMIN, clerk, post("/api/v1/inventory/adjustments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("an adjustment without a lot is 400 STOCK_ADJUSTMENT_LOT_REQUIRED on lotNumber, not a 404 (5-5 review)")
    void adjustmentWithoutALotIsRefused() throws Exception {
        for (String lot : new String[] {"", ",\"lotNumber\":\"  \""}) {
            String body = "{\"locationCode\":\"HCM-A01-1-B\",\"sku\":\"SOFA-3S-GREY\"" + lot
                    + ",\"quantityDelta\":-1,\"reason\":\"DAMAGED\"}";
            mvc.asRole(Role.SYSTEM_ADMIN, clerk, post("/api/v1/inventory/adjustments")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("STOCK_ADJUSTMENT_LOT_REQUIRED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("lotNumber"));
        }
    }
}
