package com.stockflow.procurement;

import com.stockflow.common.security.Role;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.SecuredProductionReceiptTest;
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

import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-473 through the real filter chain, permission aspect and data scope: a warehouse clerk of HCM counts a draft
 * production receipt's line into cartons with {@code PUT /api/v1/goods-receipts/{id}/lines} and gets their LPNs back, to print
 * the labels from. The rules themselves are in {@link ProductionReceiptIntegrationTest}.
 */
@SecuredProductionReceiptTest
class GoodsReceiptPackageHttpTest {

    private static final String RECEIVING = "HCM-RCV01";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ProductionReceivables receivables;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private UUID clerk;
    private ProductionReceivables.Receivable goods;
    private UUID receiptId;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        goods = receivables.sent(WAREHOUSE_HCM, 2, 1000);
        // Started below HTTP, with no caller scope (the system): only the count goes through the endpoint.
        receiptId = receivables.draftReceipt(goods, clerk).id();
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

    @Test
    @DisplayName("PUT lines with a unit and two cartons of the standard count: 200, the line's cartons with their LPNs, none loose")
    void countIntoCartonsAnswersWithLpns() throws Exception {
        String body = """
                {"lines":[{"productionOrderLineId":"%s","locationCode":"%s","unitOfMeasure":"CARTON",
                           "packages":[{"quantity":1000},{}]}]}""".formatted(goods.productionOrderLineId(), RECEIVING);

        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, put("/api/v1/goods-receipts/" + receiptId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].unitOfMeasure").value("CARTON"))
                .andExpect(jsonPath("$.data.lines[0].looseQuantity").value(0))
                .andExpect(jsonPath("$.data.lines[0].packages.length()").value(2))
                .andExpect(jsonPath("$.data.lines[0].packages[*].lpn", everyItem(matchesPattern("^LPN-\\d{8}-\\d{6}$"))))
                .andExpect(jsonPath("$.data.lines[0].packages[*].status", everyItem(org.hamcrest.Matchers.is("RECEIVING"))))
                .andExpect(jsonPath("$.data.lines[0].packages[*].nominalQty",
                        everyItem(org.hamcrest.Matchers.is(1000))))
                .andExpect(jsonPath("$.data.lines[0].packages[*].receivedQty",
                        org.hamcrest.Matchers.containsInAnyOrder(1000, 1000)))
                .andExpect(jsonPath("$.data.lines[0].units[0].base").value(true))
                .andExpect(jsonPath("$.data.lines[0].units[?(@.unitOfMeasure == 'CARTON')].factor",
                        org.hamcrest.Matchers.contains(1000)))
                .andExpect(jsonPath("$.data.lines[0].units[?(@.unitOfMeasure == 'CARTON')].active",
                        org.hamcrest.Matchers.contains(true)));
    }
}
