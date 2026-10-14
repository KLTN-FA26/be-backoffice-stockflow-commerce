package com.stockflow.procurement;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.POLineSummary;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.RevisePOLineCommand;
import com.stockflow.procurement.api.RevisePurchaseOrderCommand;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-390 acceptance (the receiving warehouse: required, 404 unknown, 409 inactive, list filter)
 * and SCRUM-462 (a DRAFT is edited in place; after a rejection the lines a revision froze are kept as
 * WITHDRAWN; nothing past DRAFT is edited), against Postgres and its triggers.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(PostgresContainer.class)
class PurchaseOrderDraftEditIntegrationTest {

    @Autowired ProcurementService procurement;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private UUID supplier;
    private UUID otherWarehouse;
    private String skuA;
    private String skuB;
    private String skuC;

    @BeforeEach
    void setUp() {
        supplier = Identifiers.newId();
        otherWarehouse = Identifiers.newId();
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO procurement.suppliers (id, code, name, status, version, created_at)
                    VALUES (?, ?, 'Draft edit supplier', 'ACTIVE', 0, NOW())""",
                    supplier, "EDIT-" + supplier.toString().substring(24).toUpperCase());
            jdbc.update("""
                    INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                     version, created_at)
                    VALUES (?, 'Second warehouse', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""",
                    otherWarehouse, "E" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
            // A line takes an active SKU only (SCRUM-461): approved products, not drafts.
            skuA = ReferenceRows.approvedProduct(entityManager).sku();
            skuB = ReferenceRows.approvedProduct(entityManager).sku();
            skuC = ReferenceRows.approvedProduct(entityManager).sku();
        });
    }

    private PurchaseOrderSummary draft(UUID warehouseId) {
        return procurement.createPurchaseOrder(new CreatePurchaseOrderCommand(supplier, warehouseId, "VND",
                LocalDate.now().plusDays(10), null,
                List.of(new CreatePOLineCommand(skuA, null, 2, new BigDecimal("1000")),
                        new CreatePOLineCommand(skuB, null, 3, new BigDecimal("2000")))));
    }

    private static POLineSummary line(PurchaseOrderSummary order, String sku) {
        return order.lines().stream().filter(l -> l.sku().equals(sku)).findFirst().orElseThrow();
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    private String createBody(String warehouse) {
        return """
                {"supplierId":"%s",%s"currency":"VND","lines":[{"sku":"%s","quantityOrdered":1,"unitPrice":1000}]}"""
                .formatted(supplier, warehouse == null ? "" : "\"warehouseId\":\"" + warehouse + "\",", skuA);
    }

    @Test
    @DisplayName("SCRUM-390: the receiving warehouse is required, must exist, must be ACTIVE; the list filters by it")
    void receivingWarehouseAcceptance() throws Exception {
        mvc.perform(post("/api/v1/purchase-orders").contentType("application/json").content(createBody(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/purchase-orders").contentType("application/json")
                        .content(createBody(UUID.randomUUID().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("WAREHOUSE_NOT_FOUND"));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE warehouse.warehouse SET status = 'INACTIVE' WHERE id = ?",
                otherWarehouse));
        mvc.perform(post("/api/v1/purchase-orders").contentType("application/json")
                        .content(createBody(otherWarehouse.toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WAREHOUSE_INACTIVE"));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE warehouse.warehouse SET status = 'ACTIVE' WHERE id = ?",
                otherWarehouse));

        PurchaseOrderSummary atHcm = draft(DemoData.WAREHOUSE_HCM);
        PurchaseOrderSummary atOther = draft(otherWarehouse);
        mvc.perform(get("/api/v1/purchase-orders").param("supplierId", supplier.toString())
                        .param("warehouseId", otherWarehouse.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].purchaseOrderId").value(atOther.purchaseOrderId().toString()));
        assertThat(atHcm.warehouseCode()).isEqualTo("HCM");
    }

    @Test
    @DisplayName("SCRUM-462: a DRAFT changes warehouse, date, note and lines in place; a stale version is refused")
    void editADraft() throws Exception {
        PurchaseOrderSummary order = draft(DemoData.WAREHOUSE_HCM);
        POLineSummary a = line(order, skuA);
        UUID b = line(order, skuB).lineId();
        LocalDate later = LocalDate.now().plusDays(30);

        String body = """
                {"version":%d,"warehouseId":"%s","expectedAt":"%s","note":"Deliver to dock 2",
                 "lines":[{"lineId":"%s","sku":"%s","quantityOrdered":5,"unitPrice":1500,"taxRate":10},
                          {"sku":"%s","quantityOrdered":1,"unitPrice":9000}]}"""
                .formatted(order.version(), otherWarehouse, later, a.lineId(), skuA, skuC);
        mvc.perform(put("/api/v1/purchase-orders/" + order.purchaseOrderId()).contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warehouseId").value(otherWarehouse.toString()))
                .andExpect(jsonPath("$.data.expectedAt").value(later.toString()))
                .andExpect(jsonPath("$.data.note").value("Deliver to dock 2"))
                .andExpect(jsonPath("$.data.lines.length()").value(2))
                .andExpect(jsonPath("$.data.lines[0].lineNo").value(1))
                .andExpect(jsonPath("$.data.lines[0].quantityOrdered").value(5))
                .andExpect(jsonPath("$.data.lines[1].lineNo").value(3))
                .andExpect(jsonPath("$.data.lines[1].sku").value(skuC))
                // 5 x 1500 = 7500 + 10 % = 8250; 1 x 9000
                .andExpect(jsonPath("$.data.totalAmount").value(17250.00));

        assertThat(jdbc.queryForObject("SELECT warehouse_id FROM procurement.purchase_orders WHERE id = ?", UUID.class,
                order.purchaseOrderId())).isEqualTo(otherWarehouse);
        // Never frozen by a revision: the dropped line is gone, not withdrawn.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement.purchase_order_lines WHERE id = ?",
                Integer.class, b)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT payload ->> 'warehouseIdAfter' FROM procurement.purchase_order_events
                 WHERE po_id = ? AND action = 'REVISED'""", String.class, order.purchaseOrderId()))
                .isEqualTo(otherWarehouse.toString());

        // The version read before the edit is stale now, even for an edit of the lines alone.
        mvc.perform(put("/api/v1/purchase-orders/" + order.purchaseOrderId()).contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        PurchaseOrderSummary edited = procurement.findById(order.purchaseOrderId()).orElseThrow();
        var descriptionOnly = new RevisePurchaseOrderCommand(edited.version(), otherWarehouse, later, "Deliver to dock 2",
                edited.lines().stream().map(l -> new RevisePOLineCommand(l.lineId(), l.sku(), "renamed " + l.lineNo(),
                        l.quantityOrdered(), l.unitPrice(), l.taxRate())).toList());
        assertThat(procurement.reviseDraft(order.purchaseOrderId(), descriptionOnly).version())
                .isGreaterThan(edited.version());
        assertThat(codeOf(catchThrowable(() -> procurement.reviseDraft(order.purchaseOrderId(), descriptionOnly))))
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }

    @Test
    @DisplayName("SCRUM-462: after a rejection a frozen line taken off the draft is WITHDRAWN, so the rejected revision keeps it")
    void editAfterRejection() {
        UUID submitter = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID approver = tx.execute(s -> ReferenceRows.user(entityManager));
        PurchaseOrderSummary order = draft(DemoData.WAREHOUSE_HCM);
        procurement.submit(order.purchaseOrderId(), submitter);
        assertThat(codeOf(catchThrowable(() -> procurement.reviseDraft(order.purchaseOrderId(),
                new RevisePurchaseOrderCommand(order.version() + 1, DemoData.WAREHOUSE_HCM, order.expectedAt(), null,
                        List.of(new RevisePOLineCommand(null, skuC, null, 1, BigDecimal.TEN, null)))))))
                .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_EDITABLE);
        PurchaseOrderSummary rejected = procurement.reject(order.purchaseOrderId(), approver, "Too many cups");
        POLineSummary kept = line(rejected, skuA);
        UUID dropped = line(rejected, skuB).lineId();

        PurchaseOrderSummary edited = procurement.reviseDraft(order.purchaseOrderId(),
                new RevisePurchaseOrderCommand(rejected.version(), DemoData.WAREHOUSE_HCM, rejected.expectedAt(), null,
                        List.of(new RevisePOLineCommand(kept.lineId(), skuA, null, 1, new BigDecimal("1000"), null))));

        assertThat(edited.lines()).extracting(POLineSummary::lineId).containsExactly(kept.lineId());
        assertThat(edited.totalAmount()).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForMap("SELECT status, po_revision_id IS NOT NULL AS frozen FROM procurement.purchase_order_lines WHERE id = ?",
                dropped)).isEqualTo(Map.of("status", "WITHDRAWN", "frozen", true));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM procurement.purchase_order_line_revisions WHERE po_line_id = ?""",
                Integer.class, dropped)).isEqualTo(1);

        PurchaseOrderSummary resubmitted = procurement.submit(order.purchaseOrderId(), submitter);
        assertThat(resubmitted.revisionNo()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM procurement.purchase_order_line_revisions lr
                  JOIN procurement.purchase_order_revisions r ON r.id = lr.po_revision_id
                 WHERE r.po_id = ? AND r.revision_no = 1""", Integer.class, order.purchaseOrderId()))
                .as("the new revision freezes the order as edited, without the withdrawn line").isEqualTo(1);
        procurement.approve(order.purchaseOrderId(), approver);

        // Past DRAFT the database refuses a new warehouse even when the application is bypassed.
        assertThatThrownBy(() -> jdbc.update("UPDATE procurement.purchase_orders SET warehouse_id = ? WHERE id = ?",
                otherWarehouse, order.purchaseOrderId()))
                .hasMessageContaining("changes only while a standard order is DRAFT");
    }

    @Test
    @DisplayName("SCRUM-462: a warehouse-bound clerk edits only within their warehouses, old and new")
    void clerkIsLimitedToTheirWarehouses() {
        UUID clerkId = tx.execute(s -> ReferenceRows.user(entityManager));
        CurrentUser hcmClerk = new CurrentUser(clerkId, "kho", Set.of(Role.WAREHOUSE_STAFF), Set.of(),
                DataScope.WAREHOUSE, Set.of(), Set.of(DemoData.WAREHOUSE_HCM));
        PurchaseOrderSummary order = draft(DemoData.WAREHOUSE_HCM);
        POLineSummary a = line(order, skuA);
        var move = new RevisePurchaseOrderCommand(order.version(), otherWarehouse, order.expectedAt(), null,
                List.of(new RevisePOLineCommand(a.lineId(), skuA, null, 4, new BigDecimal("1000"), null)));
        assertThat(codeOf(catchThrowable(() -> WithCurrentUser.call(hcmClerk, hcmClerk.scope(),
                () -> procurement.reviseDraft(order.purchaseOrderId(), move)))))
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        var stay = new RevisePurchaseOrderCommand(order.version(), DemoData.WAREHOUSE_HCM, order.expectedAt(), null,
                move.lines());
        assertThat(WithCurrentUser.call(hcmClerk, hcmClerk.scope(),
                () -> procurement.reviseDraft(order.purchaseOrderId(), stay)).lines())
                .extracting(POLineSummary::quantityOrdered).containsExactly(4);
    }
}
