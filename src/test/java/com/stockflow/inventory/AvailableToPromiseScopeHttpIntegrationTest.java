package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
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

import java.util.Locale;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BR-SEC-002 on available to promise: stock is limited to the warehouses a manager is assigned to (unlike the map,
 * kltn-docs 06 §2). Another warehouse named is refused, not answered with a silent 0; their own answers.
 */
@SecuredIntegrationTest
class AvailableToPromiseScopeHttpIntegrationTest {

    private static final String ATP = "/api/v1/inventory/stock-items/atp?sku=SCOPE-NONE";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private UUID manager;
    private String otherPrefix;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        otherPrefix = "E" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = 'WAREHOUSE_MANAGER'""",
                    manager);
            jdbc.update("""
                    INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                    VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", manager, WAREHOUSE_HCM);
            jdbc.update("""
                    INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                     version, created_at)
                    VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", Identifiers.newId(),
                    otherPrefix);
        });
    }

    @Test
    @DisplayName("ATP at another warehouse is 403 to a limited manager; their own warehouse and the total answer")
    void atpOfAnotherWarehouseIsRefused() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(ATP + "&warehouse=" + otherPrefix.toLowerCase(Locale.ROOT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(ATP + "&warehouse=HCM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(ATP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
    }
}
