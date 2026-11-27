package com.stockflow.order;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V20261209000400 (kltn-docs 17 §2-3, BR-30): the Production Coordinator lists the delay alerts; asking for the late
 * deliveries, which are not theirs, finds nothing rather than a refusal. Which types the list keeps is checked on the
 * service in {@code ProductionScheduleIntegrationTest}.
 */
@SecuredIntegrationTest
class DelayAlertAccessIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;

    private SecuredMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
    }

    @Test
    @DisplayName("the coordinator lists the delay alerts, and DELIVERY_LATE asked for alone is an empty page")
    void coordinatorListsAlerts() throws Exception {
        UUID coordinator = UUID.randomUUID();
        mvc.asRole(Role.PRODUCTION_COORDINATOR, coordinator, get("/api/v1/delay-alerts").param("open", "false"))
                .andExpect(status().isOk());
        mvc.asRole(Role.PRODUCTION_COORDINATOR, coordinator, get("/api/v1/delay-alerts")
                        .param("type", "DELIVERY_LATE").param("open", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }
}
