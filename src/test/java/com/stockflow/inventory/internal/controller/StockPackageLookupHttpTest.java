package com.stockflow.inventory.internal.controller;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.SecuredProductionReceiptTest;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-473 carton lookup through the real filter chain, permission aspect and data scope. A line of 2000 is counted
 * through the real endpoint as two cartons of the unit's standard 1000 (team plan 4C-2: every unit in a carton, every
 * carton full), then received: both cartons IN_STOCK at the receiving area, nothing loose.
 */
@SecuredProductionReceiptTest
class StockPackageLookupHttpTest {

    private static final String RECEIVING = "HCM-RCV01";
    private static final String CARTONS = "/api/v1/inventory/stock-packages";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ProductionReceivables receivables;
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private UUID clerk;
    private String sku;
    private List<String> lpns;

    @BeforeEach
    void setUp() throws Exception {
        mvc = new SecuredMvc(mockMvc, decoder);
        clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        // Two cartons of 1000 ordered and counted: every unit is in a carton (team plan 4C-2).
        ProductionReceivables.Receivable goods = receivables.sent(WAREHOUSE_HCM, 2, 1000);
        sku = goods.sku();
        lpns = receivables.onTheDock(goods, RECEIVING, 2, clerk).lpns();
    }

    /** A real account with this role, assigned to these warehouses. */
    private UUID staff(String role, UUID... warehouses) {
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

    /** Warehouse staff of a warehouse of their own, which holds none of the fixture's cartons. */
    private UUID outsider() {
        UUID elsewhere = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", elsewhere,
                "D" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT)));
        return staff("WAREHOUSE_STAFF", elsewhere);
    }

    private ResultActions read(Role role, UUID user, String path) throws Exception {
        return mvc.asRole(role, user, get(CARTONS + path));
    }

    @Test
    @DisplayName("warehouse staff of the warehouse read a carton, the receiving area's summary and the list")
    void staffReadAllThree() throws Exception {
        read(Role.WAREHOUSE_STAFF, clerk, "/" + lpns.getFirst().toLowerCase(Locale.ROOT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lpn").value(lpns.getFirst()))
                .andExpect(jsonPath("$.data.onHand").value(1000))
                .andExpect(jsonPath("$.data.unopened").value(true))
                .andExpect(jsonPath("$.data.status").value("IN_STOCK"))
                .andExpect(jsonPath("$.data.locationCode").value(RECEIVING))
                .andExpect(jsonPath("$.data.contents.length()").value(1))
                .andExpect(jsonPath("$.data.contents[0].status").value("INBOUND"));
        read(Role.WAREHOUSE_STAFF, clerk, "/location-summary?location=" + RECEIVING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.sku == '%s')].status", sku).value("INBOUND"))
                .andExpect(jsonPath("$.data[?(@.sku == '%s')].wholeCartons", sku).value(2))
                .andExpect(jsonPath("$.data[?(@.sku == '%s')].looseUnits", sku).value(0))
                .andExpect(jsonPath("$.data[?(@.sku == '%s')].totalUnits", sku).value(2000));
        read(Role.WAREHOUSE_STAFF, clerk, "?sku=" + sku)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[1].onHand").value(1000))
                .andExpect(jsonPath("$.data.items[1].status").value("IN_STOCK"));
    }

    @Test
    @DisplayName("an accountant holds nothing on cartons: 403 on the carton and on the summary")
    void anAccountantReadsNothing() throws Exception {
        UUID accountant = staff("ACCOUNTANT", WAREHOUSE_HCM);
        read(Role.ACCOUNTANT, accountant, "/" + lpns.getFirst()).andExpect(status().isForbidden());
        read(Role.ACCOUNTANT, accountant, "/location-summary?location=" + RECEIVING).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("staff of another warehouse: 403 on the carton (a LOST one too), on the summary, on a list of its bin")
    void aCartonInAnotherWarehouseIsOutOfScope() throws Exception {
        UUID outsider = outsider();

        read(Role.WAREHOUSE_STAFF, outsider, "/" + lpns.getFirst()).andExpect(status().isForbidden());
        read(Role.WAREHOUSE_STAFF, outsider, "/location-summary?location=" + RECEIVING)
                .andExpect(status().isForbidden());
        read(Role.WAREHOUSE_STAFF, outsider, "?location=" + RECEIVING).andExpect(status().isForbidden());

        // Lost as an approved count leaves a carton: its rows emptied, then LOST. Its status is not told either.
        // In tx: the pool runs with auto-commit off, so a bare jdbc write is rolled back when its connection returns.
        String lost = lpns.get(1);
        tx.executeWithoutResult(s -> {
            jdbc.update("update inventory.stock_item set on_hand = 0, reserved = 0 where stock_package_id ="
                    + " (select id from inventory.stock_package where lpn = ?)", lost);
            jdbc.update("update inventory.stock_package set status = 'LOST', unopened = false, closed_at = NOW()"
                    + " where lpn = ?", lost);
        });
        read(Role.WAREHOUSE_STAFF, outsider, "/" + lost).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("staff of another warehouse listing every carton see none of this warehouse's")
    void anOutsiderListsNothing() throws Exception {
        read(Role.WAREHOUSE_STAFF, outsider(), "?sku=" + sku)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("an unknown LPN is 404 STOCK_PACKAGE_NOT_FOUND, an unknown location 404 LOCATION_NOT_FOUND")
    void unknowns() throws Exception {
        read(Role.WAREHOUSE_STAFF, clerk, "/LPN-19990101-999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("STOCK_PACKAGE_NOT_FOUND"));
        read(Role.WAREHOUSE_STAFF, clerk, "/location-summary?location=HCM-NOPE99")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LOCATION_NOT_FOUND"));
    }
}
