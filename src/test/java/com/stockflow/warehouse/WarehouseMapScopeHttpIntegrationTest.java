package com.stockflow.warehouse;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * kltn-docs 06 §2: the warehouse map and its locations are NOT limited to the warehouses a manager is assigned to,
 * unlike stock and putaway work (BR-SEC-002). A manager assigned to HCM lists, reads and sets up another warehouse
 * too, and declares new ones. This pins the rule so the warehouse scope is not added to the map by mistake; change it
 * only with the business rule.
 */
@SecuredIntegrationTest
class WarehouseMapScopeHttpIntegrationTest {

    private static final String WAREHOUSES = "/api/v1/warehouses";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private UUID manager;
    private UUID other;
    private String otherPrefix;
    private UUID otherZone;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        other = Identifiers.newId();
        otherPrefix = ReferenceRows.warehousePrefix("E");
        otherZone = Identifiers.newId();
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
                    VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", other, otherPrefix);
            jdbc.update("""
                    INSERT INTO warehouse.zone (id, warehouse_id, name, version, created_at)
                    VALUES (?, ?, 'Elsewhere zone', 0, NOW())""", otherZone, other);
        });
    }

    @Test
    @DisplayName("a manager assigned to HCM lists and reads another warehouse and its map")
    void anotherWarehouseIsReadable() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(WAREHOUSES + "?size=100&q=" + otherPrefix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id").value(hasItem(other.toString())));
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(WAREHOUSES + "/" + other)).andExpect(status().isOk());
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(WAREHOUSES + "/" + other + "/layout"))
                .andExpect(status().isOk());
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(WAREHOUSES + "/" + other + "/zones"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a manager assigned to HCM sets up another warehouse's map and declares a new warehouse")
    void anotherWarehouseIsSetUp() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, put("/api/v1/zones/" + otherZone)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed\",\"version\":0}"))
                .andExpect(status().isOk());
        String prefix = "N" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT);
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post(WAREHOUSES).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prefix":"%s","name":"New warehouse","address":"1 Le Loi","mapUnit":"M",
                                 "mapWidth":40,"mapHeight":40}""".formatted(prefix)))
                .andExpect(status().isCreated());
    }
}
