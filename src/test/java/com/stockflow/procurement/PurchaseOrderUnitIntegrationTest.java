package com.stockflow.procurement;

import com.stockflow.common.id.Identifiers;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.POLineSummary;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.RevisePOLineCommand;
import com.stockflow.procurement.api.RevisePurchaseOrderCommand;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SupplierItemFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-473 purchase orders by carton, against Postgres and {@code tg_purchase_order_lines_unit}: a line ordered in a
 * packing unit keeps the unit and the count, its quantity, price and terms stay in base units, and a draft revision
 * that names no unit keeps the line's.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(PostgresContainer.class)
class PurchaseOrderUnitIntegrationTest {

    @Autowired ProcurementService procurement;
    @Autowired SupplierItemService catalog;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private UUID supplier;
    private String sku;
    private String other;
    private UUID carton;
    private UUID box;
    private String base;

    @BeforeEach
    void setUp() {
        supplier = Identifiers.newId();
        carton = Identifiers.newId();
        box = Identifiers.newId();
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO procurement.suppliers (id, code, name, status, version, created_at)
                    VALUES (?, ?, 'Unit supplier', 'ACTIVE', 0, NOW())""",
                    supplier, "UNIT-" + supplier.toString().substring(24).toUpperCase());
            sku = ReferenceRows.approvedProduct(entityManager).sku();
            other = ReferenceRows.approvedProduct(entityManager).sku();
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
                    SELECT ?, id, 'CARTON', 1000 FROM inventory.inventory_items WHERE sku = ?""", carton, sku);
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
                    SELECT ?, id, 'BOX', 10 FROM inventory.inventory_items WHERE sku = ?""", box, sku);
        });
        base = jdbc.queryForObject("""
                SELECT u.unit_of_measure FROM inventory.uom_conversions u
                  JOIN inventory.inventory_items i ON i.id = u.inventory_item_id
                 WHERE i.sku = ? AND u.is_base""", String.class, sku);
        SupplierItemFixtures.list(catalog, supplier, sku, new BigDecimal("10"), 1, 1, null);
        SupplierItemFixtures.list(catalog, supplier, other, new BigDecimal("10"), 1, 1, null);
    }

    private PurchaseOrderSummary baseDraft(int quantity) {
        return procurement.createPurchaseOrder(new CreatePurchaseOrderCommand(supplier, DemoData.WAREHOUSE_HCM, "VND",
                LocalDate.now().plusDays(10), null, List.of(new CreatePOLineCommand(sku, null, quantity,
                        new BigDecimal("10")))));
    }

    private POLineSummary line(UUID order) {
        return procurement.findById(order).orElseThrow().lines().stream().filter(l -> l.sku().equals(sku))
                .findFirst().orElseThrow();
    }

    private Map<String, Object> row(UUID line) {
        return jdbc.queryForMap("""
                SELECT ordered_qty::int AS qty, uom_conversion_id AS unit, unit_qty FROM procurement.purchase_order_lines
                 WHERE id = ?""", line);
    }

    /** Puts a line in CARTON as only SQL could before this change. */
    private void inCartons(UUID line, int count) {
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE procurement.purchase_order_lines SET uom_conversion_id = ?, unit_qty = ? WHERE id = ?",
                carton, count, line));
    }

    @Test
    @DisplayName("a line stored in a packing unit reads back with its unit, factor and count")
    void storedUnitIsReadBack() {
        PurchaseOrderSummary order = baseDraft(5000);
        inCartons(line(order.purchaseOrderId()).lineId(), 5);

        POLineSummary read = line(order.purchaseOrderId());

        assertThat(read.unitOfMeasure()).isEqualTo("CARTON");
        assertThat(read.unitFactor()).isEqualTo(1000);
        assertThat(read.unitQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("a draft revision that names no unit writes the count the domain worked out")
    void draftRevisionWritesTheUnitFromTheDomain() {
        PurchaseOrderSummary order = baseDraft(5000);
        UUID lineId = line(order.purchaseOrderId()).lineId();
        inCartons(lineId, 5);
        PurchaseOrderSummary current = procurement.findById(order.purchaseOrderId()).orElseThrow();

        procurement.reviseDraft(order.purchaseOrderId(), new RevisePurchaseOrderCommand(current.version(),
                DemoData.WAREHOUSE_HCM, current.expectedAt(), null,
                List.of(new RevisePOLineCommand(lineId, sku, null, 6000, new BigDecimal("10"), null))));

        assertThat(row(lineId)).containsEntry("qty", 6000).containsEntry("unit", carton).containsEntry("unit_qty", 6);
    }

    // ---- through the API ------------------------------------------------------------------------

    private ResultActions create(String lines) throws Exception {
        return mvc.perform(post("/api/v1/purchase-orders").contentType("application/json").content("""
                {"supplierId":"%s","warehouseId":"%s","currency":"VND","lines":[%s]}"""
                .formatted(supplier, DemoData.WAREHOUSE_HCM, lines)));
    }

    private ResultActions revise(UUID order, String lines) throws Exception {
        PurchaseOrderSummary current = procurement.findById(order).orElseThrow();
        return mvc.perform(put("/api/v1/purchase-orders/" + order).contentType("application/json").content("""
                {"version":%d,"warehouseId":"%s","expectedAt":"%s","lines":[%s]}"""
                .formatted(current.version(), DemoData.WAREHOUSE_HCM, current.expectedAt(), lines)));
    }

    private UUID created(ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.data.purchaseOrderId"));
    }

    private String cartons(int count) {
        return "{\"sku\":\"%s\",\"unitOfMeasure\":\"carton\",\"unitQuantity\":%d,\"unitPrice\":10}".formatted(sku, count);
    }

    @Test
    @DisplayName("5 CARTON of 1000 is 5000 units at the unit price; the response shows the unit, its count and price")
    void createInCartons() throws Exception {
        ResultActions result = create(cartons(5))
                .andExpect(jsonPath("$.data.lines[0].quantityOrdered").value(5000))
                .andExpect(jsonPath("$.data.lines[0].unitOfMeasure").value("CARTON"))
                .andExpect(jsonPath("$.data.lines[0].unitFactor").value(1000))
                .andExpect(jsonPath("$.data.lines[0].unitQuantity").value(5))
                .andExpect(jsonPath("$.data.lines[0].openUnitQuantity").value(5))
                .andExpect(jsonPath("$.data.lines[0].packingUnitPrice").value(10000))
                .andExpect(jsonPath("$.data.lines[0].lineTotal").value(50000));
        UUID order = created(result);

        assertThat(row(line(order).lineId())).containsEntry("qty", 5000).containsEntry("unit", carton)
                .containsEntry("unit_qty", 5);
    }

    @Test
    @DisplayName("naming the base unit orders in base units: no packing unit is stored")
    void namingTheBaseUnitStoresNone() throws Exception {
        UUID order = created(create("{\"sku\":\"%s\",\"unitOfMeasure\":\"%s\",\"unitQuantity\":7,\"unitPrice\":10}"
                .formatted(sku, base)));

        assertThat(row(line(order).lineId())).containsEntry("qty", 7).containsEntry("unit", null)
                .containsEntry("unit_qty", null);
    }

    @Test
    @DisplayName("a line in base units carries no unit fields (nulls are left out of the JSON)")
    void baseUnitLineHasNoUnitFields() throws Exception {
        create("{\"sku\":\"%s\",\"quantityOrdered\":7,\"unitPrice\":10}".formatted(sku))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].unitOfMeasure").doesNotExist())
                .andExpect(jsonPath("$.data.lines[0].unitFactor").doesNotExist())
                .andExpect(jsonPath("$.data.lines[0].unitQuantity").doesNotExist())
                .andExpect(jsonPath("$.data.lines[0].openUnitQuantity").doesNotExist())
                .andExpect(jsonPath("$.data.lines[0].packingUnitPrice").doesNotExist());
    }

    @Test
    @DisplayName("a retired unit is refused on its field")
    void retiredUnitRefused() throws Exception {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.uom_conversions SET active = false WHERE id = ?", carton));

        create(cartons(5))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].unitOfMeasure"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("PO_LINE_UNIT_INVALID"));
    }

    @Test
    @DisplayName("a unit the item does not have is refused on its field")
    void unknownUnitRefused() throws Exception {
        create("{\"sku\":\"%s\",\"unitOfMeasure\":\"PALLET\",\"unitQuantity\":1,\"unitPrice\":10}".formatted(sku))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].unitOfMeasure"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("UOM_CONVERSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("a unit count without a unit is refused")
    void unitQuantityWithoutUnitRefused() throws Exception {
        create("{\"sku\":\"%s\",\"quantityOrdered\":5000,\"unitQuantity\":5,\"unitPrice\":10}".formatted(sku))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].unitQuantity"));
    }

    @Test
    @DisplayName("a quantity that is not what the units hold is refused")
    void mismatchingQuantityRefused() throws Exception {
        create("{\"sku\":\"%s\",\"quantityOrdered\":4000,\"unitOfMeasure\":\"CARTON\",\"unitQuantity\":5,\"unitPrice\":10}"
                .formatted(sku))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].quantityOrdered"));
    }

    @Test
    @DisplayName("the supplier's MOQ is checked on the base units the cartons hold")
    void moqCheckedOnTheBaseQuantity() throws Exception {
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE procurement.supplier_items SET moq = 6000
                 WHERE supplier_id = ? AND inventory_item_id = (SELECT id FROM inventory.inventory_items WHERE sku = ?)""",
                supplier, sku));

        create(cartons(5))
                .andExpect(jsonPath("$.errorCode").value("SUPPLIER_TERMS_VIOLATED"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("PO_LINE_BELOW_MOQ"));
    }

    @Test
    @DisplayName("a draft revision changes the unit, keeps it when none is named, and leaves it for the base unit")
    void revisionChangesKeepsAndDropsTheUnit() throws Exception {
        UUID order = created(create(cartons(5)));
        UUID lineId = line(order).lineId();

        revise(order, "{\"lineId\":\"%s\",\"sku\":\"%s\",\"unitOfMeasure\":\"BOX\",\"unitQuantity\":30,\"unitPrice\":10}"
                .formatted(lineId, sku)).andExpect(status().isOk());
        assertThat(row(lineId)).containsEntry("qty", 300).containsEntry("unit", box).containsEntry("unit_qty", 30);

        revise(order, "{\"lineId\":\"%s\",\"sku\":\"%s\",\"quantityOrdered\":70,\"unitPrice\":10}"
                .formatted(lineId, sku)).andExpect(status().isOk());
        assertThat(row(lineId)).containsEntry("qty", 70).containsEntry("unit", box).containsEntry("unit_qty", 7);

        revise(order, "{\"lineId\":\"%s\",\"sku\":\"%s\",\"unitOfMeasure\":\"%s\",\"unitQuantity\":71,\"unitPrice\":10}"
                .formatted(lineId, sku, base)).andExpect(status().isOk());
        assertThat(row(lineId)).containsEntry("qty", 71).containsEntry("unit", null).containsEntry("unit_qty", null);
    }

    @Test
    @DisplayName("a line keeps the retired unit it already has when a revision names none")
    void revisionKeepsARetiredUnitTheLineAlreadyHas() throws Exception {
        UUID order = created(create(cartons(5)));
        UUID lineId = line(order).lineId();
        tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.uom_conversions SET active = false WHERE id = ?", carton));

        revise(order, "{\"lineId\":\"%s\",\"sku\":\"%s\",\"quantityOrdered\":6000,\"unitPrice\":10}"
                .formatted(lineId, sku)).andExpect(status().isOk());
        revise(order, "{\"lineId\":\"%s\",\"sku\":\"%s\",\"unitOfMeasure\":\"CARTON\",\"unitQuantity\":7,\"unitPrice\":10}"
                .formatted(lineId, sku)).andExpect(status().isOk());

        assertThat(row(lineId)).containsEntry("qty", 7000).containsEntry("unit", carton).containsEntry("unit_qty", 7);
    }

    @Test
    @DisplayName("one SKU on two lines is still refused, whatever their units")
    void sameSkuTwiceStillRefusedWithUnits() throws Exception {
        create(cartons(5) + ",{\"sku\":\"%s\",\"quantityOrdered\":7,\"unitPrice\":10}".formatted(sku))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("the revision a submitted order is approved against keeps the unit")
    void submittedRevisionSnapshotKeepsTheUnit() throws Exception {
        UUID order = created(create(cartons(5)));
        procurement.submit(order, tx.execute(s -> ReferenceRows.user(entityManager)));

        assertThat(jdbc.queryForMap("""
                SELECT r.uom_conversion_id AS unit, r.unit_qty FROM procurement.purchase_order_line_revisions r
                 WHERE r.po_line_id = ?""", line(order).lineId()))
                .containsEntry("unit", carton).containsEntry("unit_qty", 5);
    }
}
