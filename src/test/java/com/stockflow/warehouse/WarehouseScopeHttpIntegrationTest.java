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
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BR-SEC-002 on the warehouse map, through the real filter chain, permission aspect and data scope: a warehouse
 * manager assigned to HCM sets up and reads HCM only. Before, the module checked no warehouse at all, so a manager
 * could rename, deactivate or redraw the map of a warehouse they were never assigned to. A caller whose scope is ALL
 * is not limited.
 */
@SecuredIntegrationTest
class WarehouseScopeHttpIntegrationTest {

    private static final String WAREHOUSES = "/api/v1/warehouses";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private UUID manager;
    private UUID admin;
    private UUID other;
    private String otherPrefix;
    private UUID otherZone;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        manager = user("WAREHOUSE_MANAGER", WAREHOUSE_HCM);
        admin = user("SYSTEM_ADMIN");
        other = Identifiers.newId();
        otherPrefix = "E" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT);
        otherZone = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                     version, created_at)
                    VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", other, otherPrefix);
            jdbc.update("""
                    INSERT INTO warehouse.zone (id, warehouse_id, name, version, created_at)
                    VALUES (?, ?, 'Elsewhere zone', 0, NOW())""", otherZone, other);
        });
    }

    private UUID user(String role, UUID... warehouses) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role);
            for (UUID warehouse : warehouses) {
                jdbc.update("""
                        INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                        VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, warehouse);
            }
        });
        return user;
    }

    private long version(UUID warehouse) {
        return jdbc.queryForObject("select version from warehouse.warehouse where id = ?", Long.class, warehouse);
    }

    @Test
    @DisplayName("the warehouse list shows a limited manager their warehouses only; an admin sees every one")
    void listIsLimitedToAssignedWarehouses() throws Exception {
        String list = WAREHOUSES + "?size=100";
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(list))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id").value(hasItem(WAREHOUSE_HCM.toString())))
                .andExpect(jsonPath("$.data.items[*].id").value(not(hasItem(other.toString()))));
        mvc.asRole(Role.SYSTEM_ADMIN, admin, get(list + "&q=" + otherPrefix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id").value(hasItem(other.toString())));
    }

    @Test
    @DisplayName("another warehouse, its map, zones and locations are 403 OUT_OF_DATA_SCOPE to a limited manager")
    void readsOfAnotherWarehouseAreRefused() throws Exception {
        for (String path : new String[] {WAREHOUSES + "/" + other, WAREHOUSES + "/" + other + "/layout",
                WAREHOUSES + "/" + other + "/zones", "/api/v1/locations/" + otherPrefix + "-A01-1-B"}) {
            mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(path))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        }
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(WAREHOUSES + "/" + WAREHOUSE_HCM))
                .andExpect(status().isOk());
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(WAREHOUSES + "/" + WAREHOUSE_HCM + "/layout"))
                .andExpect(status().isOk());
        mvc.asRole(Role.SYSTEM_ADMIN, admin, get(WAREHOUSES + "/" + other))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a limited manager can neither change, deactivate nor redraw another warehouse; nothing is written")
    void writesToAnotherWarehouseAreRefused() throws Exception {
        long before = version(other);
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post(WAREHOUSES + "/" + other + "/deactivation"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post(WAREHOUSES + "/" + other + "/activation"))
                .andExpect(status().isForbidden());
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post(WAREHOUSES + "/" + other + "/zones")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Mine now\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, put("/api/v1/zones/" + otherZone)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"version\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));

        org.assertj.core.api.Assertions.assertThat(version(other)).isEqualTo(before);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from warehouse.zone where warehouse_id = ?", Integer.class, other)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select name from warehouse.zone where id = ?", String.class, otherZone)).isEqualTo("Elsewhere zone");
    }

    @Test
    @DisplayName("available to promise at another warehouse is 403 to a limited manager, not a silent 0")
    void atpOfAnotherWarehouseIsRefused() throws Exception {
        String atp = "/api/v1/inventory/stock-items/atp?sku=SCOPE-NONE&warehouse=";
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(atp + otherPrefix.toLowerCase(Locale.ROOT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get(atp + "HCM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get("/api/v1/inventory/stock-items/atp?sku=SCOPE-NONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
    }

    @Test
    @DisplayName("the limited manager still sets up their own warehouse")
    void ownWarehouseStillWorks() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post(WAREHOUSES + "/" + WAREHOUSE_HCM + "/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Scope test " + UUID.randomUUID().toString().substring(0, 8) + "\"}"))
                .andExpect(status().isCreated());
    }
}
