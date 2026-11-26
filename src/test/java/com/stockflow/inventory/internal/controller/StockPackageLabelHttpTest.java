package com.stockflow.inventory.internal.controller;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.SecuredProductionReceiptTest;
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
@SecuredProductionReceiptTest
class StockPackageLabelHttpTest {

    private static final String RECEIVING = "HCM-RCV01";
    private static final String LABELS = "/api/v1/inventory/stock-packages/labels";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ProductionReceivables receivables;
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
        ProductionReceivables.Receivable goods = receivables.sent(WAREHOUSE_HCM, 2, 1000);
        UUID receiptId = receivables.draftReceipt(goods, clerk).id();
        String body = """
                {"lines":[{"productionOrderLineId":"%s","locationCode":"%s",
                           "packages":[{},{}]}]}""".formatted(
                goods.productionOrderLineId(), RECEIVING);
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
    @DisplayName("a received carton reprinted by LPN prints the lot the carton is of")
    void reprintPrintsTheStockRowsLot() throws Exception {
        ProductionReceivables.Receivable goods = receivables.sent(WAREHOUSE_HCM, 1, 1000);
        UUID receiptId = receivables.received(goods, RECEIVING, 1, clerk).id();
        String lpn = jdbc.queryForObject("""
                SELECT p.lpn FROM inventory.stock_package p
                  JOIN procurement.goods_receipt_lines l ON l.id = p.goods_receipt_line_id
                 WHERE l.receipt_id = ?""", String.class, receiptId);
        String rowLot = jdbc.queryForObject("""
                SELECT l.lot_number FROM inventory.stock_package p
                  JOIN inventory.stock_lot l ON l.id = p.lot_id
                 WHERE p.lpn = ?""", String.class, lpn);
        assertThat(rowLot).isEqualTo(goods.productionCode());

        byte[] pdf = print(Role.WAREHOUSE_STAFF, clerk,
                "{\"format\":\"THERMAL_ROLL\",\"lpns\":" + quoted(List.of(lpn)) + "}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(doc)).contains(rowLot);
        }
    }

    @Test
    @DisplayName("a carton still being counted prints its receipt line's lot and its SKU's name, asked of procurement "
            + "and product")
    void printsTheLotAndTheName() throws Exception {
        String lot = jdbc.queryForObject("SELECT lot_number FROM procurement.goods_receipt_lines WHERE id = ?",
                String.class, receiptLineId);
        String name = jdbc.queryForObject("""
                SELECT v.name FROM product.variants v
                  JOIN inventory.inventory_items i ON i.sku = v.sku
                  JOIN inventory.stock_package p ON p.inventory_item_id = i.id
                 WHERE p.lpn = ?""", String.class, lpns.get(0));
        byte[] pdf = print(Role.WAREHOUSE_STAFF, clerk,
                "{\"format\":\"A4_SHEET\",\"receiptLineId\":\"" + receiptLineId + "\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertThat(text).contains(lot);
            // A long name wraps and may be cut; its start is on the label either way.
            assertThat(text).contains(name.length() > 20 ? name.substring(0, 20) : name);
        }
    }

    @Test
    @DisplayName("a receipt line with an empty lpns list beside it prints the line: [] is no second selector")
    void emptyLpnsBesideTheLine() throws Exception {
        byte[] pdf = print(Role.WAREHOUSE_STAFF, clerk,
                "{\"format\":\"THERMAL_ROLL\",\"receiptLineId\":\"" + receiptLineId + "\",\"lpns\":[]}")
                .andExpect(status().isOk())
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
    @DisplayName("an accountant holds nothing on cartons: 403")
    void anAccountantCannotPrint() throws Exception {
        UUID accountant = staff("ACCOUNTANT", WAREHOUSE_HCM);
        print(Role.ACCOUNTANT, accountant,
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
