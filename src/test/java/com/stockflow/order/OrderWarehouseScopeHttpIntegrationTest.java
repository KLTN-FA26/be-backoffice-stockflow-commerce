package com.stockflow.order;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.StaffFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BR-SEC-002 on the order reads a warehouse manager holds the permission for: the shipping-warehouse options and stock
 * coverage, the completion hold and the release dialog's warnings. A manager reads them for an order of their
 * warehouses only (its shipping warehouse, before it is released) and gets 404 for another's, as the order itself
 * answers; of the options, they see their own warehouses' stock and room only. Sales, whose scope is ALL, see every
 * warehouse.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class OrderWarehouseScopeHttpIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private UUID hcmManager;
    private UUID otherManager;
    private UUID sales;
    private UUID other;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        StaffFixtures staff = new StaffFixtures(jdbc, tx, entityManager);
        other = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", other,
                "E" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT)));
        hcmManager = staff.staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);
        otherManager = staff.staff("WAREHOUSE_MANAGER", other);
        sales = staff.staff("SALES_STAFF");

        ConfirmedOrders orders = new ConfirmedOrders(context);
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        CustomerSkuRows.Issued sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 2);
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 4);
        orderId = orders.confirmed(customer, ConfirmedOrders.Line.cartons(sku, 2, 6_000)).orderId();
        orders.ship(orderId, DemoData.WAREHOUSE_HCM);
    }

    private String path(String tail) {
        return "/api/v1/orders/" + orderId + tail;
    }

    @Test
    @DisplayName("the manager of the shipping warehouse reads the order's release warnings, coverage and completion "
            + "hold before it is released; a manager of another warehouse gets 404")
    void readsAreLimitedToTheOrdersWarehouse() throws Exception {
        for (String tail : new String[] {"/release-warnings", "/stock-coverage", "/completion-hold"}) {
            mvc.asRole(Role.WAREHOUSE_MANAGER, hcmManager, get(path(tail)))
                    .andExpect(status().isOk());
            mvc.asRole(Role.WAREHOUSE_MANAGER, otherManager, get(path(tail)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        }
    }

    @Test
    @DisplayName("the shipping-warehouse options show a manager their own warehouses only, and Sales every one; a "
            + "manager of another warehouse gets 404")
    void optionsShowAManagerTheirWarehousesOnly() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, hcmManager, get(path("/shipping-warehouse/options")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warehouses[*].warehouseId")
                        .value(everyItem(is(DemoData.WAREHOUSE_HCM.toString()))))
                .andExpect(jsonPath("$.data.warehouses[*].warehouseId")
                        .value(hasItem(DemoData.WAREHOUSE_HCM.toString())));
        mvc.asRole(Role.SALES_STAFF, sales, get(path("/shipping-warehouse/options")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warehouses[*].warehouseId").value(hasItem(other.toString())));
        mvc.asRole(Role.WAREHOUSE_MANAGER, otherManager, get(path("/shipping-warehouse/options")))
                .andExpect(status().isNotFound());
    }
}
