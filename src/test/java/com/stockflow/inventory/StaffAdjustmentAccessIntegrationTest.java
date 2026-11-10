package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
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
    @Autowired InventoryService inventory;
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
        // A carton is adjusted by its LPN (team plan 4C-3); the reason is judged before the carton is looked up.
        String body = "{\"lpn\":\"LPN-20991231-000001\",\"direction\":\"WRITE_OFF\","
                + "\"reason\":\"SUBCONTRACT_LOSS\"}";

        mvc.asRole(Role.SYSTEM_ADMIN, clerk, post("/api/v1/inventory/adjustments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("an adjustment of a loose layer without a lot is STOCK_ADJUSTMENT_LOT_REQUIRED on lotNumber, not a 404 "
            + "(5-5 review)")
    void adjustmentWithoutALotIsRefused() {
        // Since 4C-3 the HTTP request names a carton by LPN and carries no lot; the loose-layer path is the inventory
        // API's (InventoryService.requestAdjustment), where the rule still stands.
        for (String lot : new String[] {null, "  "}) {
            BusinessException refused = org.assertj.core.api.Assertions.catchThrowableOfType(BusinessException.class,
                    () -> inventory.requestAdjustment(new RequestAdjustmentCommand("HCM-A01-1-B", new Sku("SOFA-3S-GREY"),
                            lot, -1, StockAdjustmentReason.DAMAGED, null, clerk)));
            org.assertj.core.api.Assertions.assertThat(refused).as("lot %s", lot).isNotNull();
            org.assertj.core.api.Assertions.assertThat(refused.errorCode())
                    .isEqualTo(ErrorCode.STOCK_ADJUSTMENT_LOT_REQUIRED);
            org.assertj.core.api.Assertions.assertThat(refused.fieldErrors())
                    .extracting(com.stockflow.common.api.FieldError::field).containsExactly("lotNumber");
        }
    }
}
