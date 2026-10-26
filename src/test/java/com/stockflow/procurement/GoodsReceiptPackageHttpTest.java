package com.stockflow.procurement;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-473 through the real filter chain, permission aspect and data scope: a warehouse clerk of HCM counts a draft
 * receipt's line into cartons with {@code PUT /api/v1/goods-receipts/{id}/lines} and gets their LPNs back, to print
 * the labels from. The rules themselves are in {@link GoodsReceiptPackageIntegrationTest}.
 */
@SecuredIntegrationTest
class GoodsReceiptPackageHttpTest {

    private static final String RECEIVING = "HCM-RCV01";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired GoodsReceipts receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private UUID clerk;
    private UUID poLine;
    private UUID receiptId;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        String sku = "GRH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        poLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Goods receipt carton HTTP fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Goods receipt carton HTTP fixture");
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
                    po, "PO-GH-" + po.toString().substring(24), WAREHOUSE_HCM, submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at) VALUES (?, ?, ?, 1, ?, 2000, 1000, 0, NOW())""",
                    poLine, po, revision, item);
        });
        // Started below HTTP, with no caller scope (the system): only the count goes through the endpoint.
        receiptId = receipts.create(new GoodsReceipts.Create(po, "DN-GH", null), clerk).id();
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
    @DisplayName("PUT lines with a unit and two cartons: 200, the line's cartons with their LPNs, the rest loose")
    void countIntoCartonsAnswersWithLpns() throws Exception {
        String body = """
                {"lines":[{"purchaseOrderLineId":"%s","quantity":2000,"locationCode":"%s","unitOfMeasure":"CARTON",
                           "packages":[{"quantity":1000},{"quantity":980}]}]}""".formatted(poLine, RECEIVING);

        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, put("/api/v1/goods-receipts/" + receiptId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].unitOfMeasure").value("CARTON"))
                .andExpect(jsonPath("$.data.lines[0].looseQuantity").value(20))
                .andExpect(jsonPath("$.data.lines[0].packages.length()").value(2))
                .andExpect(jsonPath("$.data.lines[0].packages[*].lpn", everyItem(matchesPattern("^LPN-\\d{8}-\\d{6}$"))))
                .andExpect(jsonPath("$.data.lines[0].packages[*].status", everyItem(org.hamcrest.Matchers.is("RECEIVING"))))
                .andExpect(jsonPath("$.data.lines[0].packages[*].nominalQty",
                        everyItem(org.hamcrest.Matchers.is(1000))))
                .andExpect(jsonPath("$.data.lines[0].packages[*].receivedQty",
                        org.hamcrest.Matchers.containsInAnyOrder(1000, 980)));
    }
}
