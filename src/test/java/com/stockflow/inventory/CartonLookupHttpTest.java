package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.support.CartonRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.Holds;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The carton list as the LPN lookup screen reads it (#454; kltn-docs 20 §4.1), through the real filter chain,
 * permission aspect and data scope: filtered by warehouse, lot, part of the LPN and stock status, sorted FIFO by
 * received date, and every row readable on its own - its warehouse, product and variant, stock statuses, source, the
 * order holding it. Three cartons of a SKU of its own stand on an HCM shelf, received on three days, in two lots.
 */
@SecuredIntegrationTest
class CartonLookupHttpTest {

    private static final String SHELF = "HCM-A01-1-B";
    private static final String QUARANTINE_AREA = "HCM-QC01";
    private static final String CARTONS = "/api/v1/inventory/stock-packages";
    private static final int CARTON = 10;

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired InventoryService inventory;

    private SecuredMvc mvc;
    private UUID clerk;
    private UUID manager;
    private String sku;
    /** Received on the 3rd, lot A. */
    private String newest;
    /** Received on the 1st, lot A. */
    private String oldest;
    /** Received on the 2nd, lot B. */
    private String middle;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        manager = staff("WAREHOUSE_MANAGER", WAREHOUSE_HCM);
        sku = "CLK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Carton lookup cup", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Carton lookup cup 12oz");
        });
        newest = carton("LOT-A", "2026-01-03T00:00:00Z");
        oldest = carton("LOT-A", "2026-01-01T00:00:00Z");
        middle = carton("LOT-B", "2026-01-02T00:00:00Z");
    }

    private String carton(String lot, String receivedAt) {
        return tx.execute(s -> CartonRows.stock(jdbc, sku, SHELF, 1, CARTON, lot, Instant.parse(receivedAt)))
                .getFirst().lpn();
    }

    /** A real account with this role, assigned to these warehouses. */
    private UUID staff(String role, UUID... warehouses) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role);
            for (UUID w : warehouses) {
                jdbc.update("""
                        INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                        VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, w);
            }
        });
        return user;
    }

    private ResultActions list(String query) throws Exception {
        return mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(CARTONS + "?sku=" + sku + query));
    }

    @Test
    @DisplayName("sort=receivedAt is FIFO; each row names its warehouse, product, variant, stock status and source")
    void fifoAndReadableRows() throws Exception {
        list("&sort=receivedAt")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items[0].lpn").value(oldest))
                .andExpect(jsonPath("$.data.items[1].lpn").value(middle))
                .andExpect(jsonPath("$.data.items[2].lpn").value(newest))
                .andExpect(jsonPath("$.data.items[0].receivedAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].warehouseId").value(WAREHOUSE_HCM.toString()))
                .andExpect(jsonPath("$.data.items[0].warehouseCode").value("HCM"))
                .andExpect(jsonPath("$.data.items[0].productName").value("Carton lookup cup"))
                .andExpect(jsonPath("$.data.items[0].variantName").value("Carton lookup cup 12oz"))
                .andExpect(jsonPath("$.data.items[0].stockStatuses[0]").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].stockStatuses.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].source").value("IN_WAREHOUSE"))
                .andExpect(jsonPath("$.data.items[0].heldFor.length()").value(0))
                .andExpect(jsonPath("$.data.items[0].openTask").doesNotExist());
        list("&sort=receivedAt,desc")
                .andExpect(jsonPath("$.data.items[0].lpn").value(newest));
    }

    @Test
    @DisplayName("lotNumber is one exact lot; q is part of the LPN in any case, warehouseId one warehouse")
    void filters() throws Exception {
        list("&lotNumber=LOT-B")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].lpn").value(middle));
        String part = newest.substring(newest.length() - 6).toLowerCase(Locale.ROOT);
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(CARTONS + "?q=" + part))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.lpn == '%s')]", newest).exists());
        list("&q=" + part)
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].lpn").value(newest));
        list("&warehouseId=" + WAREHOUSE_HCM)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    @DisplayName("q of 2 characters: 400 VALIDATION_FAILED")
    void aShortQIsRefused() throws Exception {
        list("&q=12")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("warehouseId of a warehouse the caller is not assigned to: 403 OUT_OF_DATA_SCOPE")
    void anotherWarehouseIsOutOfScope() throws Exception {
        UUID elsewhere = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", elsewhere,
                "L" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT)));

        list("&warehouseId=" + elsewhere)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
    }

    @Test
    @DisplayName("a quarantined carton is IN_STOCK as a carton and QUARANTINE in stockStatuses; stockStatus filters it")
    void aQuarantinedCartonSaysSo() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, post(CARTONS + "/" + middle + "/quarantine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationCode\":\"" + QUARANTINE_AREA + "\",\"reason\":\"Wet carton\"}"))
                .andExpect(status().isOk());

        list("&stockStatus=QUARANTINE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].lpn").value(middle))
                .andExpect(jsonPath("$.data.items[0].status").value("IN_STOCK"))
                .andExpect(jsonPath("$.data.items[0].stockStatuses[0]").value("QUARANTINE"));
        list("&stockStatus=AVAILABLE")
                .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, get(CARTONS + "/" + middle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockStatuses[0]").value("QUARANTINE"))
                .andExpect(jsonPath("$.data.warehouseCode").value("HCM"));
    }

    @Test
    @DisplayName("a carton allocated to an order names the order holding it")
    void aHeldCartonNamesItsOrder() throws Exception {
        UUID order = Identifiers.newId();
        String orderNumber = "SO-20991227-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, contract_signed_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), NOW())""", order, orderNumber,
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        tx.executeWithoutResult(s -> {
            Holds.units(inventory, order, sku, "HCM", CARTON);
            inventory.allocate(new AllocateStockCommand(order, "HCM"));
        });

        // FIFO: the allocation takes the oldest carton.
        list("&sort=receivedAt")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].lpn").value(oldest))
                .andExpect(jsonPath("$.data.items[0].reserved").value(CARTON))
                .andExpect(jsonPath("$.data.items[0].heldFor.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].heldFor[0].orderId").value(order.toString()))
                .andExpect(jsonPath("$.data.items[0].heldFor[0].orderNumber").value(orderNumber))
                .andExpect(jsonPath("$.data.items[0].heldFor[0].quantity").value(CARTON))
                .andExpect(jsonPath("$.data.items[1].heldFor.length()").value(0));
    }

    @Test
    @DisplayName("the statements the list sends through JPA do not grow with the page")
    void queryCountIsConstantInPageSize() throws Exception {
        statements("&size=1"); // warm: the caller's grants are read once, then cached
        long one = statements("&size=1");
        long three = statements("&size=3");

        assertThat(three).isEqualTo(one);
    }

    /**
     * The JDBC statements Hibernate prepared for one request: the lookups the list asks other modules (putaway tasks,
     * SKU names, carton work). The carton search itself is plain SQL - a count, a page and the holds, three
     * statements whatever the page - and is not seen here.
     */
    private long statements(String query) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            list(query).andExpect(status().isOk());
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }
}
