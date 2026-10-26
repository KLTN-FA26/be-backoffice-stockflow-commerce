package com.stockflow.inventory.internal.controller;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.procurement.internal.service.GoodsReceipts;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-473 carton lookup through the real filter chain, permission aspect and data scope. A line of 2000 is counted
 * through the real endpoint as cartons of 1000 and 980, then received: both cartons IN_STOCK at the receiving area,
 * 20 loose beside them.
 */
@SecuredIntegrationTest
class StockPackageLookupHttpTest {

    private static final String RECEIVING = "HCM-RCV01";
    private static final String CARTONS = "/api/v1/inventory/stock-packages";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired GoodsReceipts receipts;
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
        sku = "CLK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        UUID poLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Carton lookup fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Carton lookup fixture");
            UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            // BR-03: tg_goods_receipt_lines_item refuses a receipt line for an item with no weight or dimensions.
            jdbc.update("update inventory.inventory_items set weight_kg = 0.5, length_cm = 10, width_cm = 8, height_cm = 6"
                    + " where id = ?", item);
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', 1000)", Identifiers.newId(), item);

            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);   // four eyes (ck_purchase_orders_four_eyes)
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, ?, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s WHERE s.code = 'GOVIET'""",
                    po, "PO-CLK-" + po.toString().substring(24), WAREHOUSE_HCM, submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at) VALUES (?, ?, ?, 1, ?, 2000, 1000, 0, NOW())""",
                    poLine, po, revision, item);
        });
        UUID receiptId = receipts.create(new GoodsReceipts.Create(po, "DN-CLK", null), clerk).id();
        String body = """
                {"lines":[{"purchaseOrderLineId":"%s","quantity":2000,"locationCode":"%s","unitOfMeasure":"CARTON",
                           "packages":[{"quantity":1000},{"quantity":980}]}]}""".formatted(poLine, RECEIVING);
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, put("/api/v1/goods-receipts/" + receiptId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        UUID receiptLineId = jdbc.queryForObject(
                "SELECT id FROM procurement.goods_receipt_lines WHERE receipt_id = ?", UUID.class, receiptId);
        lpns = jdbc.queryForList("select lpn from inventory.stock_package where goods_receipt_line_id = ? order by lpn",
                String.class, receiptLineId);
        inventory.receive(new ReceiveStockCommand(receiptLineId, new Sku(sku), null, null, RECEIVING, 2000, clerk,
                Instant.parse("2026-01-02T00:00:00Z")));
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
                .andExpect(jsonPath("$.data.contents.length()").value(1));
        read(Role.WAREHOUSE_STAFF, clerk, "/location-summary?location=" + RECEIVING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.sku == '%s')].wholeCartons", sku).value(2))
                .andExpect(jsonPath("$.data[?(@.sku == '%s')].looseUnits", sku).value(20))
                .andExpect(jsonPath("$.data[?(@.sku == '%s')].totalUnits", sku).value(2000));
        read(Role.WAREHOUSE_STAFF, clerk, "?sku=" + sku)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[1].onHand").value(980));
    }

    @Test
    @DisplayName("an inventory planner and QC staff only read cartons, and read them")
    void readOnlyRolesRead() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER", WAREHOUSE_HCM);
        UUID qc = staff("QC_STAFF", WAREHOUSE_HCM);
        read(Role.INVENTORY_PLANNER, planner, "/" + lpns.getFirst()).andExpect(status().isOk());
        read(Role.QC_STAFF, qc, "/location-summary?location=" + RECEIVING).andExpect(status().isOk());
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
