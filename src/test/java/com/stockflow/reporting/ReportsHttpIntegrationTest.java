package com.stockflow.reporting;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.CartonRows;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The report endpoints of 2026-11-14 plan BE2 through the real filter chain, permission aspect and data scope, with the
 * grants V20261126000100 seeds: who reads and who exports each, the warehouse manager narrowed to their warehouses, the
 * files handed over as attachments never cached, and the filters refused when they make no sense.
 */
@SecuredIntegrationTest
class ReportsHttpIntegrationTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String BIN = "HCM-B01-1-A";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    /** Units in one carton of the fixture's SKU: 4 and 6 units are 2 and 3 cartons. */
    private static final int PER_CARTON = 2;

    private SecuredMvc mvc;
    private UUID accountant;
    private UUID salesStaff;
    private UUID manager;
    private UUID otherWarehouse;
    private String otherArea;
    private String sku;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        accountant = staff("ACCOUNTANT");
        salesStaff = staff("SALES_STAFF");
        manager = staff("WAREHOUSE_MANAGER", WAREHOUSE_HCM);
        sku = "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        otherWarehouse = Identifiers.newId();
        String prefix = "R" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT);
        otherArea = prefix + "-QC01";
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Report fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Report fixture");
            jdbc.update("""
                    INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                     version, created_at)
                    VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", otherWarehouse, prefix);
            UUID location = Identifiers.newId();
            jdbc.update("INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code,"
                    + " is_pickable, is_putaway_target, status) VALUES (?, ?, 'AREA', ?, FALSE, FALSE, 'ACTIVE')",
                    location, otherWarehouse, otherArea);
            jdbc.update("INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length,"
                    + " rotation, is_obstacle, status) VALUES (?, ?, ?, 'QC01', 'QUARANTINE', 'QC01', 1, 1, 5, 5, 0,"
                    + " FALSE, 'ACTIVE')", Identifiers.newId(), otherWarehouse, location);
        });
        // The SKU's ledger: 4 pieces into HCM, 6 into the other warehouse, both today.
        ledgerLine(BIN, 4);
        ledgerLine(otherArea, 6);
    }

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

    /**
     * {@code quantity} units into {@code location}: one ledger line per carton of {@link #PER_CARTON} standing there.
     * A line names its carton and no number of units (ADR-0011); the report under test sums the cartons' standard
     * counts.
     */
    private void ledgerLine(String location, int quantity) {
        tx.executeWithoutResult(s -> {
            for (var carton : CartonRows.stock(jdbc, sku, location, quantity / PER_CARTON, PER_CARTON, null,
                    Instant.now())) {
                jdbc.update("""
                        INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code,
                            to_location_code, to_status, reference_type, reference_id, occurred_at, version,
                            created_at, stock_package_id)
                        VALUES (?, 'ADJUSTMENT', ?, 'UNTRACED', NULL, ?, 'AVAILABLE', 'STOCK_ADJUSTMENT', ?, ?, 0, NOW(),
                                ?)""",
                        Identifiers.newId(), sku, location, Identifiers.newId(),
                        OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC), carton.id());
            }
        });
    }

    private static void isSpreadsheet(MvcResult result, String stem) throws Exception {
        assertThat(result.getResponse().getContentType()).isEqualTo(XLSX);
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .startsWith("attachment").contains("filename=\"" + stem + "-").contains(".xlsx");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(
                result.getResponse().getContentAsByteArray()))) {
            assertThat(workbook.getNumberOfSheets()).isPositive();
        }
    }

    @Test
    @DisplayName("receivables: the accountant reads and exports, Sales reads only, the warehouse manager neither")
    void receivables() throws Exception {
        mvc.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/reports/receivables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asOf").value(today.toString()))
                .andExpect(jsonPath("$.data.rows.page").value(0));
        isSpreadsheet(mvc.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/reports/receivables/export")
                .param("overdueOnly", "true")).andExpect(status().isOk()).andReturn(), "receivables");

        mvc.asRole(Role.SALES_STAFF, salesStaff, get("/api/v1/reports/receivables")).andExpect(status().isOk());
        mvc.asRole(Role.SALES_STAFF, salesStaff, get("/api/v1/reports/receivables/export"))
                .andExpect(status().isForbidden());
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get("/api/v1/reports/receivables"))
                .andExpect(status().isForbidden());

        mvc.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/reports/receivables")
                        .param("customerId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_FOUND"));
        mvc.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/reports/receivables").param("sort", "creditLimit"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("stock summary: the warehouse manager sees their warehouse only, another is a 403; the accountant all")
    void stockSummaryScope() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get("/api/v1/reports/stock-summary").param("sku", sku))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value(today.minusDays(29).toString()))
                .andExpect(jsonPath("$.data.to").value(today.toString()))
                .andExpect(jsonPath("$.data.rows.totalElements").value(1))
                .andExpect(jsonPath("$.data.rows.items[*].warehousePrefix", everyItem(is("HCM"))))
                .andExpect(jsonPath("$.data.rows.items[0].warehouseId").value(WAREHOUSE_HCM.toString()))
                .andExpect(jsonPath("$.data.rows.items[0].closing.units").value(4))
                .andExpect(jsonPath("$.data.rows.items[0].adjustedIn.units").value(4))
                // A SKU with ledger lines has a carton unit since a line names its carton (V20261231000200): the
                // NO_CARTON_UNIT note is StockSummaryReportServiceTest's.
                .andExpect(jsonPath("$.data.rows.items[0].cartonNote").value("NONE"));
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get("/api/v1/reports/stock-summary")
                        .param("warehouseId", otherWarehouse.toString()))
                .andExpect(status().isForbidden());
        isSpreadsheet(mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get("/api/v1/reports/stock-summary/export")
                .param("sku", sku)).andExpect(status().isOk()).andReturn(), "stock-summary");

        mvc.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/reports/stock-summary").param("sku", sku))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.totalElements").value(2))
                .andExpect(jsonPath("$.data.rows.items[*].warehousePrefix", hasItems("HCM",
                        otherArea.substring(0, otherArea.indexOf('-')))));
        mvc.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/reports/stock-summary").param("sku", sku)
                        .param("warehouseId", otherWarehouse.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.items[0].closing.units").value(6));

        mvc.asRole(Role.SALES_STAFF, salesStaff, get("/api/v1/reports/stock-summary"))
                .andExpect(status().isForbidden());
        mvc.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/reports/stock-summary")
                        .param("from", today.minusDays(400).toString()).param("to", today.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("the stock history drills down by warehouse and days; another warehouse is a 403, days inverted a 400")
    void movementsDrillDown() throws Exception {
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get("/api/v1/inventory/movements").param("sku", sku)
                        .param("warehouseId", WAREHOUSE_HCM.toString()).param("from", today.toString())
                        .param("to", today.toString()))
                .andExpect(status().isOk())
                // HCM's 4 pieces are two cartons of PER_CARTON, a line each (the other warehouse's three are left out);
                // a line answers its carton's standard count.
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[0].quantity").value(PER_CARTON))
                .andExpect(jsonPath("$.data.items[1].quantity").value(PER_CARTON));
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get("/api/v1/inventory/movements")
                        .param("warehouseId", otherWarehouse.toString()))
                .andExpect(status().isForbidden());
        mvc.asRole(Role.WAREHOUSE_MANAGER, manager, get("/api/v1/inventory/movements")
                        .param("from", today.toString()).param("to", today.minusDays(1).toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("payment receipts: the total reads with the list, the file is the accountant's; days inverted a 400")
    void paymentReceipts() throws Exception {
        mvc.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/payment-receipts/summary")
                        .param("receivedFrom", today.toString()).param("receivedTo", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").isNumber());
        isSpreadsheet(mvc.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/payment-receipts/export")
                .param("receivedFrom", today.minusDays(7).toString())).andExpect(status().isOk()).andReturn(),
                "payment-receipts");
        mvc.asRole(Role.SALES_STAFF, salesStaff, get("/api/v1/payment-receipts/export"))
                .andExpect(status().isForbidden());
        mvc.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/payment-receipts")
                        .param("receivedFrom", today.toString()).param("receivedTo", today.minusDays(1).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }
}
