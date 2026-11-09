package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.CartonRows;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Team plan 4D-1 over HTTP, with the real security chain: quarantining a carton and releasing it are the warehouse
 * manager's alone (kltn-docs 20 BR-21, {@code inventory-stock-packages:UPDATE}); warehouse staff, who look cartons up
 * and print their labels, are refused both. The manager quarantines a demo carton into {@code HCM-QC01} and releases
 * it to a putaway task of source QUARANTINE_RELEASE.
 */
@SecuredIntegrationTest
class CartonQuarantineHttpTest {

    private static final String SHELF = "HCM-A01-1-B";
    private static final String QUARANTINE_AREA = "HCM-QC01";
    private static final String CARTONS = "/api/v1/inventory/stock-packages/";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private UUID clerk;
    private UUID manager;
    private String lpn;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        clerk = staff("WAREHOUSE_STAFF");
        manager = staff("WAREHOUSE_MANAGER");
        String sku = "QRH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Quarantine HTTP fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Quarantine HTTP fixture");
        });
        lpn = tx.execute(s -> CartonRows.stock(jdbc, sku, SHELF, 1, 10, null,
                Instant.parse("2026-01-02T00:00:00Z"))).getFirst().lpn();
    }

    private UUID staff(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role);
            jdbc.update("""
                    INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                    VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, WAREHOUSE_HCM);
        });
        return user;
    }

    private static MockHttpServletRequestBuilder quarantine(String lpn) {
        return post(CARTONS + lpn + "/quarantine").contentType(MediaType.APPLICATION_JSON)
                .content("{\"locationCode\":\"" + QUARANTINE_AREA + "\",\"reason\":\"Wet carton\"}");
    }

    private static MockHttpServletRequestBuilder release(String lpn) {
        return post(CARTONS + lpn + "/quarantine-release");
    }

    private String stockStatus() {
        return jdbc.queryForObject("""
                SELECT i.status FROM inventory.stock_item i JOIN inventory.stock_package p ON p.id = i.stock_package_id
                 WHERE p.lpn = ?""", String.class, lpn);
    }

    @Test
    @DisplayName("only the warehouse manager holds inventory-stock-packages:UPDATE (with the system admin)")
    void onlyTheManagerMayQuarantine() {
        assertThat(jdbc.queryForList("""
                SELECT r.code FROM identity.role_permission rp
                  JOIN identity.app_role r ON r.id = rp.role_id
                  JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE p.code = 'inventory-stock-packages:UPDATE' ORDER BY r.code""", String.class))
                .containsExactly("SYSTEM_ADMIN", "WAREHOUSE_MANAGER");
    }

    @Test
    @DisplayName("warehouse staff are refused both quarantine and release: 403, nothing changes")
    void staffAreRefused() throws Exception {
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, quarantine(lpn)).andExpect(status().isForbidden());
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, release(lpn)).andExpect(status().isForbidden());

        assertThat(stockStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("the manager quarantines a carton and releases it to a QUARANTINE_RELEASE putaway task")
    void managerQuarantinesAndReleases() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, quarantine(lpn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lpn").value(lpn))
                .andExpect(jsonPath("$.data.locationCode").value(QUARANTINE_AREA));
        assertThat(stockStatus()).isEqualTo("QUARANTINE");

        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, quarantine(lpn))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STOCK_PACKAGE_QUARANTINED"));

        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, release(lpn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("QUARANTINE_RELEASE"))
                .andExpect(jsonPath("$.data.lpn").value(lpn))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.sourceLocationCode").value(QUARANTINE_AREA))
                .andExpect(jsonPath("$.data.goodsReceiptId").doesNotExist());
        assertThat(stockStatus()).isEqualTo("QUARANTINE");
    }

    @Test
    @DisplayName("a quarantine names a reason and a location: 400 without them")
    void quarantineNeedsAReason() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post(CARTONS + lpn + "/quarantine")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locationCode\":\"" + QUARANTINE_AREA + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(stockStatus()).isEqualTo("AVAILABLE");
    }
}
