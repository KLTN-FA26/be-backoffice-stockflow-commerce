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
 * V20261014001200: the inventory planner, who may request an adjustment, may also read the adjustments,
 * but deciding one stays with the holder of {@code inventory-stock-adjustments:APPROVE}.
 */
@SecuredIntegrationTest
class PlannerAdjustmentAccessIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;

    private SecuredMvc mvc;
    private final UUID planner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
    }

    @Test
    @DisplayName("the planner lists stock adjustments")
    void plannerListsAdjustments() throws Exception {
        mvc.asRole(Role.INVENTORY_PLANNER, planner, get("/api/v1/inventory/adjustments"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the planner opens one adjustment (an unknown id is a 404, not a 403)")
    void plannerReadsOneAdjustment() throws Exception {
        mvc.asRole(Role.INVENTORY_PLANNER, planner, get("/api/v1/inventory/adjustments/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the planner still cannot approve or reject an adjustment")
    void plannerCannotDecide() throws Exception {
        UUID adjustment = UUID.randomUUID();
        mvc.asRole(Role.INVENTORY_PLANNER, planner, post("/api/v1/inventory/adjustments/" + adjustment + "/approval"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.asRole(Role.INVENTORY_PLANNER, planner, post("/api/v1/inventory/adjustments/" + adjustment + "/rejection")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"not mine to decide\"}"))
                .andExpect(status().isForbidden());
    }
}
