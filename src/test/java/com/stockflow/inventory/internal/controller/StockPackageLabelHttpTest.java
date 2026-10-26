package com.stockflow.inventory.internal.controller;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-473 carton labels through the real filter chain, permission aspect and data scope. The cartons are committed
 * (counted into a draft receipt line through the real endpoint) before the print is asked for.
 */
@SecuredIntegrationTest
class StockPackageLabelHttpTest {

    private static final String RECEIVING = "HCM-RCV01";
    private static final String LABELS = "/api/v1/inventory/stock-packages/labels";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired GoodsReceipts receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private UUID clerk;
    private UUID receiptLineId;
    private List<String> lpns;

    @BeforeEach
    void setUp() throws Exception {
        mvc = new SecuredMvc(mockMvc, decoder);
        clerk = staff("WAREHOUSE_STAFF", WAREHOUSE_HCM);
        String sku = "CL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        UUID poLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Carton label fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Carton label fixture");
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
                    po, "PO-CL-" + po.toString().substring(24), WAREHOUSE_HCM, submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at) VALUES (?, ?, ?, 1, ?, 2000, 1000, 0, NOW())""",
                    poLine, po, revision, item);
        });
        UUID receiptId = receipts.create(new GoodsReceipts.Create(po, "DN-CL", null), clerk).id();
        String body = """
                {"lines":[{"purchaseOrderLineId":"%s","quantity":2000,"locationCode":"%s","unitOfMeasure":"CARTON",
                           "packages":[{"quantity":1000},{"quantity":980}]}]}""".formatted(poLine, RECEIVING);
        mvc.asRole(Role.WAREHOUSE_STAFF, clerk, put("/api/v1/goods-receipts/" + receiptId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        receiptLineId = jdbc.queryForObject(
                "SELECT id FROM procurement.goods_receipt_lines WHERE receipt_id = ?", UUID.class, receiptId);
        lpns = jdbc.queryForList("select lpn from inventory.stock_package where goods_receipt_line_id = ? order by lpn",
                String.class, receiptLineId);
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

    private ResultActions print(Role role, UUID user, String body) throws Exception {
        return mvc.asRole(role, user, post(LABELS).accept(MediaType.APPLICATION_PDF, MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String quoted(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    @DisplayName("warehouse staff print a receipt line's cartons: 200, a PDF attachment, a page per carton")
    void printsTheLinesCartons() throws Exception {
        byte[] pdf = print(Role.WAREHOUSE_STAFF, clerk,
                "{\"format\":\"THERMAL_ROLL\",\"receiptLineId\":\"" + receiptLineId + "\"}")
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a VOID carton is not printed with the line, and a closed LPN named by hand is 409")
    void aVoidCartonHasNoLabel() throws Exception {
        String voided = lpns.get(1);
        // In tx: the pool runs with auto-commit off, so a bare jdbc write is rolled back when its connection returns.
        tx.executeWithoutResult(s -> jdbc.update(
                "update inventory.stock_package set status = 'VOID', closed_at = NOW() where lpn = ?", voided));

        byte[] pdf = print(Role.WAREHOUSE_STAFF, clerk,
                "{\"format\":\"THERMAL_ROLL\",\"receiptLineId\":\"" + receiptLineId + "\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
        print(Role.WAREHOUSE_STAFF, clerk, "{\"format\":\"THERMAL_ROLL\",\"lpns\":" + quoted(List.of(voided)) + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STOCK_PACKAGE_TRANSITION"));
    }

    @Test
    @DisplayName("an LPN is reprinted by name, whatever its case")
    void reprintsByLpn() throws Exception {
        byte[] pdf = print(Role.WAREHOUSE_STAFF, clerk, "{\"format\":\"A4_SHEET\",\"lpns\":["
                + "\"" + lpns.get(0).toLowerCase(Locale.ROOT) + "\"]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an inventory planner only reads cartons: 403")
    void aPlannerCannotPrint() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER", WAREHOUSE_HCM);
        print(Role.INVENTORY_PLANNER, planner,
                "{\"format\":\"THERMAL_ROLL\",\"receiptLineId\":\"" + receiptLineId + "\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unknown LPN is 404 STOCK_PACKAGE_NOT_FOUND")
    void anUnknownLpn() throws Exception {
        print(Role.WAREHOUSE_STAFF, clerk, "{\"format\":\"THERMAL_ROLL\",\"lpns\":[\"LPN-19990101-999999\"]}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("STOCK_PACKAGE_NOT_FOUND"));
    }

    @Test
    @DisplayName("501 LPNs: 422 STOCK_PACKAGE_LABELS_TOO_MANY")
    void tooMany() throws Exception {
        List<String> many = IntStream.rangeClosed(1, 501).mapToObj(i -> "LPN-20261026-%06d".formatted(i)).toList();
        print(Role.WAREHOUSE_STAFF, clerk, "{\"format\":\"THERMAL_ROLL\",\"lpns\":" + quoted(many) + "}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("STOCK_PACKAGE_LABELS_TOO_MANY"));
    }

    @Test
    @DisplayName("neither a line nor LPNs, or a line with no live carton: 422 STOCK_PACKAGE_LABELS_EMPTY")
    void nothingToPrint() throws Exception {
        print(Role.WAREHOUSE_STAFF, clerk, "{\"format\":\"THERMAL_ROLL\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("STOCK_PACKAGE_LABELS_EMPTY"));
        print(Role.WAREHOUSE_STAFF, clerk, "{\"format\":\"THERMAL_ROLL\",\"receiptLineId\":\""
                + UUID.randomUUID() + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("STOCK_PACKAGE_LABELS_EMPTY"));
    }

    @Test
    @DisplayName("cartons in a warehouse the caller is not assigned to: 403, a VOID one too - not 409 telling its status")
    void aCartonInAnotherWarehouseIsOutOfScope() throws Exception {
        UUID elsewhere = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", elsewhere,
                "D" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT)));
        UUID outsider = staff("WAREHOUSE_STAFF", elsewhere);

        print(Role.WAREHOUSE_STAFF, outsider,
                "{\"format\":\"THERMAL_ROLL\",\"receiptLineId\":\"" + receiptLineId + "\"}")
                .andExpect(status().isForbidden());
        print(Role.WAREHOUSE_STAFF, outsider,
                "{\"format\":\"THERMAL_ROLL\",\"lpns\":" + quoted(List.of(lpns.get(0))) + "}")
                .andExpect(status().isForbidden());

        String voided = lpns.get(1);
        // In tx: the pool runs with auto-commit off, so a bare jdbc write is rolled back when its connection returns.
        tx.executeWithoutResult(s -> jdbc.update(
                "update inventory.stock_package set status = 'VOID', closed_at = NOW() where lpn = ?", voided));
        print(Role.WAREHOUSE_STAFF, outsider, "{\"format\":\"THERMAL_ROLL\",\"lpns\":" + quoted(List.of(voided)) + "}")
                .andExpect(status().isForbidden());
    }
}
