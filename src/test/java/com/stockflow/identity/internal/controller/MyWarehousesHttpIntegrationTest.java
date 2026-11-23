package com.stockflow.identity.internal.controller;

import com.stockflow.common.security.Role;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.StaffFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #394: {@code /me/permissions} tells the caller which warehouses they are assigned to, through the real filter
 * chain and the same resolution the warehouse-scoped endpoints use, so the warehouse workspace offers only those.
 */
@SecuredIntegrationTest
class MyWarehousesHttpIntegrationTest {

    private static final String PERMISSIONS = "/api/v1/identity/me/permissions";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private StaffFixtures fixtures;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        fixtures = new StaffFixtures(jdbc, tx, entityManager);
    }

    private record Named(UUID id, String prefix) {
    }

    @Test
    @DisplayName("staff assigned two warehouses are told both, with their prefixes, sorted by prefix")
    void bothAssignedWarehouses() throws Exception {
        StaffFixtures.Warehouse other = fixtures.otherWarehouse();
        UUID hcm = fixtures.hcm();
        UUID clerk = fixtures.staff("WAREHOUSE_STAFF", hcm, other.id());
        List<Named> expected = List.of(new Named(hcm, "HCM"), new Named(other.id(), other.prefix())).stream()
                .sorted(Comparator.comparing(Named::prefix)).toList();

        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(PERMISSIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataScope").value("WAREHOUSE"))
                .andExpect(jsonPath("$.data.warehouses.length()").value(2))
                .andExpect(jsonPath("$.data.warehouses[0].id").value(expected.get(0).id().toString()))
                .andExpect(jsonPath("$.data.warehouses[0].prefix").value(expected.get(0).prefix()))
                .andExpect(jsonPath("$.data.warehouses[1].id").value(expected.get(1).id().toString()))
                .andExpect(jsonPath("$.data.warehouses[1].prefix").value(expected.get(1).prefix()));
    }

    @Test
    @DisplayName("staff assigned no warehouse get an empty list")
    void noAssignment() throws Exception {
        UUID clerk = fixtures.staff("WAREHOUSE_STAFF");

        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(PERMISSIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataScope").value("WAREHOUSE"))
                .andExpect(jsonPath("$.data.warehouses.length()").value(0));
    }

    @Test
    @DisplayName("a caller whose scope is not WAREHOUSE is not limited to warehouses: the list is empty, assigned or not")
    void broaderScopeIsNotLimited() throws Exception {
        UUID admin = fixtures.staff("SYSTEM_ADMIN", fixtures.hcm());

        mvc.asRole(Role.SYSTEM_ADMIN, admin, get(PERMISSIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataScope").value("ALL"))
                .andExpect(jsonPath("$.data.warehouses.length()").value(0));
    }
}
