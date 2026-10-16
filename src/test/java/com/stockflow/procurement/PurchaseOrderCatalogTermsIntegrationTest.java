package com.stockflow.procurement;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.CommercialTerms;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.CreateSubcontractOrderCommand;
import com.stockflow.procurement.api.POLineSummary;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.RevisePOLineCommand;
import com.stockflow.procurement.api.RevisePurchaseOrderCommand;
import com.stockflow.procurement.api.SaveSupplierItemCommand;
import com.stockflow.procurement.api.SubcontractOrder;
import com.stockflow.procurement.api.SubcontractPurchasing;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.procurement.api.SupplierItemSummary;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SupplierItemFixtures;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-78 (BR-PO-001): a standard purchase order line takes only a SKU its supplier lists, in a quantity the
 * catalog allows, and a line without a price takes the catalog's. The order's lead time is the slowest line's,
 * and a missing delivery date follows it. Create and draft edit are held to the same rule; a subcontract order is
 * not. Runs on real Postgres because the catalog rows, their constraints and the purchase order's persistence are
 * real, which a mock cannot check.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(PostgresContainer.class)
class PurchaseOrderCatalogTermsIntegrationTest {

    @Autowired ProcurementService procurement;
    @Autowired SupplierItemService catalog;
    @Autowired SubcontractPurchasing subcontracting;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    private UUID supplier;
    private String skuA;
    private String skuB;

    @BeforeEach
    void setUp() {
        supplier = supplier(9);
        skuA = approvedSku();
        skuB = approvedSku();
    }

    /** An ACTIVE VND supplier; {@code leadTimeDays} null leaves the column NULL ("not known"). */
    private UUID supplier(Integer leadTimeDays) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO procurement.suppliers (id, code, name, status, currency, lead_time_days, version, created_at)
                VALUES (?, ?, 'Catalog terms supplier', 'ACTIVE', 'VND', ?, 0, NOW())""",
                id, "CAT-" + id.toString().substring(24).toUpperCase(Locale.ROOT), leadTimeDays));
        return id;
    }

    private String approvedSku() {
        return tx.execute(status -> ReferenceRows.approvedProduct(entityManager).sku());
    }

    private SupplierItemSummary list(String sku, String price, int moq, int packSize, Integer leadTimeDays) {
        return SupplierItemFixtures.list(catalog, supplier, sku, new BigDecimal(price), moq, packSize, leadTimeDays);
    }

    private PurchaseOrderSummary create(String currency, LocalDate expectedAt, CreatePOLineCommand... lines) {
        return create(supplier, currency, expectedAt, lines);
    }

    private PurchaseOrderSummary create(UUID supplierId, String currency, LocalDate expectedAt,
                                        CreatePOLineCommand... lines) {
        return procurement.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, DemoData.WAREHOUSE_HCM,
                currency, expectedAt, null, List.of(lines)));
    }

    private static CreatePOLineCommand line(String sku, int quantity, String unitPrice) {
        return new CreatePOLineCommand(sku, null, quantity, unitPrice == null ? null : new BigDecimal(unitPrice));
    }

    private static RevisePurchaseOrderCommand revision(PurchaseOrderSummary order, String note,
                                                       RevisePOLineCommand... lines) {
        return new RevisePurchaseOrderCommand(order.version(), order.warehouseId(), order.expectedAt(), note,
                List.of(lines));
    }

    private static RevisePOLineCommand kept(POLineSummary line, int quantity) {
        return new RevisePOLineCommand(line.lineId(), line.sku(), null, quantity, line.unitPrice(), null);
    }

    private static POLineSummary lineOf(PurchaseOrderSummary order, String sku) {
        return order.lines().stream().filter(l -> l.sku().equals(sku)).findFirst().orElseThrow();
    }

    /** The refusal, as "field:code" per field error, after checking it is the catalog-terms one. */
    private static List<String> termsRefusal(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        BusinessException refusal = (BusinessException) thrown;
        assertThat(refusal.errorCode()).isEqualTo(ErrorCode.SUPPLIER_TERMS_VIOLATED);
        assertThat(refusal.errorCode().httpStatus()).isEqualTo(409);
        return refusal.fieldErrors().stream().map(f -> f.field() + ":" + f.code()).toList();
    }

    /** One integer column of one row, read in its own transaction (Hikari runs with auto-commit off). */
    private Integer number(String sql, Object... args) {
        return tx.execute(status -> jdbc.queryForObject(sql, Integer.class, args));
    }

    private Integer ordersOf(UUID supplierId) {
        return number("SELECT count(*) FROM procurement.purchase_orders WHERE supplier_id = ?", supplierId);
    }

    private LocalDate today() {
        return BusinessCalendar.date(clock.instant());
    }

    // ------------------------------------------------------------------ listed or not

    @Test
    @DisplayName("a SKU the supplier does not list is refused on its line, and no order is written")
    void anUnlistedSkuIsRefused() {
        assertThat(termsRefusal(() -> create("VND", null, line(skuA, 1, "1000"))))
                .containsExactly("lines[0].sku:SUPPLIER_ITEM_NOT_LISTED");
        assertThat(ordersOf(supplier)).isZero();
    }

    @Test
    @DisplayName("a SKU another supplier lists is still not listed for this one")
    void aSkuListedOnlyForAnotherSupplierIsNotListed() {
        UUID other = supplier(9);
        SupplierItemFixtures.list(catalog, other, skuA);

        assertThat(termsRefusal(() -> create("VND", null, line(skuA, 1, "1000"))))
                .containsExactly("lines[0].sku:SUPPLIER_ITEM_NOT_LISTED");
        assertThat(create(other, "VND", null, line(skuA, 1, "1000")).lines()).hasSize(1);
    }

    // ------------------------------------------------------------------ quantity

    @Test
    @DisplayName("below the MOQ is refused on the quantity")
    void belowMoqIsRefused() {
        list(skuA, "1000", 10, 5, null);

        assertThat(termsRefusal(() -> create("VND", null, line(skuA, 5, "1000"))))
                .containsExactly("lines[0].quantityOrdered:PO_LINE_BELOW_MOQ");
        assertThat(ordersOf(supplier)).isZero();
    }

    @Test
    @DisplayName("a quantity that is not whole packs is refused on the quantity")
    void notAPackMultipleIsRefused() {
        list(skuA, "1000", 10, 5, null);

        assertThat(termsRefusal(() -> create("VND", null, line(skuA, 12, "1000"))))
                .containsExactly("lines[0].quantityOrdered:PO_LINE_NOT_PACK_MULTIPLE");
        assertThat(create("VND", null, line(skuA, 15, "1000")).lines()).hasSize(1);
    }

    @Test
    @DisplayName("every line at fault is in one refusal, with one error per broken rule")
    void severalBadLinesGiveOneRefusalWithOneErrorPerField() {
        list(skuB, "1000", 10, 5, null);

        Throwable thrown = catchThrowable(() -> create("VND", null, line(skuA, 1, "1000"), line(skuB, 7, "1000")));

        assertThat(termsRefusal(() -> { throw thrown; })).containsExactly(
                "lines[0].sku:SUPPLIER_ITEM_NOT_LISTED",
                "lines[1].quantityOrdered:PO_LINE_BELOW_MOQ",
                "lines[1].quantityOrdered:PO_LINE_NOT_PACK_MULTIPLE");
        assertThat(thrown.getMessage()).startsWith("2 line(s)");
    }

    @Test
    @DisplayName("an unlisted line reports only that: there are no terms to hold its quantity or price to")
    void anUnlistedLineReportsOnlyNotListed() {
        assertThat(termsRefusal(() -> create("VND", null, line(skuA, 7, null))))
                .containsExactly("lines[0].sku:SUPPLIER_ITEM_NOT_LISTED");
    }

    // ------------------------------------------------------------------ price

    @Test
    @DisplayName("a line without a price takes the catalog's; a typed price is a negotiated one and is kept")
    void aNullPriceIsPrefilledAndATypedPriceKept() {
        list(skuA, "120000", 1, 1, null);
        list(skuB, "120000", 1, 1, null);

        PurchaseOrderSummary order = create("VND", null, line(skuA, 2, null), line(skuB, 1, "99000"));

        assertThat(lineOf(order, skuA).unitPrice()).isEqualByComparingTo("120000");
        assertThat(lineOf(order, skuB).unitPrice()).isEqualByComparingTo("99000");
        assertThat(order.totalAmount()).isEqualByComparingTo("339000");
    }

    @Test
    @DisplayName("an order in another currency than the catalog's needs a typed price")
    void anotherCurrencyNeedsATypedPrice() {
        list(skuA, "100000", 1, 1, null);

        assertThat(termsRefusal(() -> create("USD", null, line(skuA, 1, null))))
                .containsExactly("lines[0].unitPrice:PO_LINE_PRICE_REQUIRED");

        PurchaseOrderSummary order = create("USD", null, line(skuA, 1, "12.50"));
        assertThat(order.currency()).isEqualTo("USD");
        assertThat(lineOf(order, skuA).unitPrice()).isEqualByComparingTo("12.50");
    }

    // ------------------------------------------------------------------ lead time and delivery date

    @Test
    @DisplayName("the order's lead time is its slowest line's: an item's own, else the supplier's")
    void leadTimeIsTheLargestLineLeadTime() {
        // 12 is above the supplier's 9, so a supplier-wide rule (9) and the slowest-line rule (12) differ; skuB has
        // no lead time of its own and falls back to the supplier's 9, which is the smaller of the two
        list(skuA, "1000", 1, 1, 12);
        list(skuB, "1000", 1, 1, null);

        PurchaseOrderSummary order = create("VND", today().plusDays(30), line(skuA, 1, null), line(skuB, 1, null));

        assertThat(order.leadTimeDays()).isEqualTo(12);
        assertThat(order.expectedAt()).isEqualTo(today().plusDays(30));
    }

    @Test
    @DisplayName("no delivery date: today on the business calendar plus that lead time")
    void noExpectedAtDefaultsToTodayPlusThatLeadTime() {
        list(skuA, "1000", 1, 1, 12);
        list(skuB, "1000", 1, 1, null);

        PurchaseOrderSummary order = create("VND", null, line(skuA, 1, null), line(skuB, 1, null));

        assertThat(order.leadTimeDays()).isEqualTo(12);
        assertThat(order.expectedAt()).isEqualTo(today().plusDays(12));
    }

    @Test
    @DisplayName("no lead time on the item or the supplier: the system default")
    void noLeadTimeAnywhereFallsBackToTheDefault() {
        UUID unknownLeadTime = supplier(null);
        assertThat(number("SELECT lead_time_days FROM procurement.suppliers WHERE id = ?", unknownLeadTime)).isNull();
        SupplierItemFixtures.list(catalog, unknownLeadTime, skuA);

        PurchaseOrderSummary order = create(unknownLeadTime, "VND", null, line(skuA, 1, null));

        assertThat(order.leadTimeDays()).isEqualTo(CommercialTerms.LEAD_DAYS);
        assertThat(order.expectedAt()).isEqualTo(today().plusDays(CommercialTerms.LEAD_DAYS));
    }

    // ------------------------------------------------------------------ subcontract

    @Test
    @DisplayName("a subcontract order is not held to the catalog: its line follows a production order already running")
    void aSubcontractOrderIsUntouched() {
        Sku cup = new Sku("SOFA-3S-GREY");
        UUID hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        UUID innhanh = jdbc.queryForObject("SELECT id FROM procurement.suppliers WHERE code = 'INNHANH'", UUID.class);
        UUID manager = tx.execute(s -> ReferenceRows.user(entityManager));
        assertThat(number("""
                SELECT count(*) FROM procurement.supplier_items si
                  JOIN inventory.inventory_items ii ON ii.id = si.inventory_item_id
                 WHERE si.supplier_id = ? AND ii.sku = ?""", innhanh, cup.code()))
                .as("the subcontractor does not list the SKU it prints (the demo lists it only for GOVIET)").isZero();
        UUID child = subcontractedProductionOrder(cup, hcm, innhanh, manager);

        SubcontractOrder created = subcontracting.create(new CreateSubcontractOrderCommand(child, innhanh, hcm, cup, 400,
                new BigDecimal("800"), "VND", LocalDate.now().plusDays(7), manager));

        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.quantity()).isEqualTo(400);
    }

    /** As in {@code SubcontractPurchasingIntegrationTest}: a line printed 600 in house and 400 at INNHANH. */
    private UUID subcontractedProductionOrder(Sku cup, UUID hcm, UUID innhanh, UUID manager) {
        UUID draft = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        UUID order = Identifiers.newId();
        UUID line = Identifiers.newId();
        UUID root = Identifiers.newId();
        UUID sub = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, DemoData.CUSTOMER_ID, DemoData.PRODUCT_SOFA);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                                                        artifact_manifest)
                    VALUES (?, ?, ?, 'https://example.com/p.pdf', NOW(), NOW(), '[]'::jsonb)""", snapshot, draft, "b".repeat(64));
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                         currency, placed_at, warehouse_id, released_at, released_by,
                                                         version, created_at)
                    VALUES (?, ?, ?, ?, 'IN_PRODUCTION', 2000000, 'VND', NOW(), ?, NOW(), ?, 0, NOW())""",
                    order, "SO-20991230-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)),
                    DemoData.CUSTOMER_ID, UUID.randomUUID(), hcm, manager);
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, design_snapshot_id,
                                                     design_checksum) VALUES (?, ?, ?, 1000, 2000, 'VND', ?, ?)""",
                    line, order, cup.code(), snapshot, "b".repeat(64));
            jdbc.update("""
                    INSERT INTO production.production_order (id, order_number, type, status, warehouse_id, blank_sku,
                        design_snapshot_id, design_checksum, planned_quantity, sales_order_id, sales_order_line_id)
                    VALUES (?, ?, 'ORDER', 'PENDING_PREPRESS', ?, ?, ?, ?, 600, ?, ?)""",
                    root, "LSX-IT-" + root.toString().substring(24), hcm, cup.code(), snapshot, "b".repeat(64), order, line);
            jdbc.update("""
                    INSERT INTO production.production_order (id, order_number, type, mode, status, warehouse_id, blank_sku,
                        design_snapshot_id, design_checksum, planned_quantity, sales_order_id, sales_order_line_id, parent_id,
                        subcontract_method, subcontractor_id, subcontract_unit_price, subcontract_currency)
                    VALUES (?, ?, 'ORDER', 'SUBCONTRACTED', 'PENDING_PREPRESS', ?, ?, ?, ?, 400, ?, ?, ?,
                            'SUPPLIED_BLANKS', ?, 800, 'VND')""",
                    sub, "LSX-IT-" + sub.toString().substring(24), hcm, cup.code(), snapshot, "b".repeat(64), order, line,
                    root, innhanh);
        });
        return sub;
    }

    // ------------------------------------------------------------------ draft edit

    @Test
    @DisplayName("draft edit: a quantity taken below the MOQ is refused and the draft is unchanged")
    void draftEditToBelowMoqIsRefused() {
        list(skuA, "1000", 10, 5, null);
        PurchaseOrderSummary order = create("VND", null, line(skuA, 10, null));

        assertThat(termsRefusal(() -> procurement.reviseDraft(order.purchaseOrderId(),
                revision(order, null, kept(lineOf(order, skuA), 5)))))
                .containsExactly("lines[0].quantityOrdered:PO_LINE_BELOW_MOQ");
        assertThat(lineOf(procurement.findById(order.purchaseOrderId()).orElseThrow(), skuA).quantityOrdered())
                .isEqualTo(10);
    }

    @Test
    @DisplayName("draft edit: a line left as it was is checked against the catalog as it is now")
    void draftEditRechecksUnchangedLinesAgainstTheCurrentCatalog() {
        SupplierItemSummary listed = list(skuA, "1000", 1, 1, null);
        PurchaseOrderSummary order = create("VND", null, line(skuA, 2, null));
        catalog.update(supplier, listed.supplierItemId(), new SaveSupplierItemCommand(skuA, null,
                new BigDecimal("1000"), 10, 1, null, false, listed.version()));

        assertThat(termsRefusal(() -> procurement.reviseDraft(order.purchaseOrderId(),
                revision(order, "only the note changes", kept(lineOf(order, skuA), 2)))))
                .containsExactly("lines[0].quantityOrdered:PO_LINE_BELOW_MOQ");
    }

    @Test
    @DisplayName("draft edit: the lead time is recomputed from the lines as edited; the delivery date stays the request's")
    void draftEditRecomputesTheLeadTime() {
        list(skuA, "1000", 1, 1, null);
        list(skuB, "1000", 1, 1, 20);
        PurchaseOrderSummary order = create("VND", null, line(skuA, 1, null));
        assertThat(order.leadTimeDays()).isEqualTo(9);

        PurchaseOrderSummary edited = procurement.reviseDraft(order.purchaseOrderId(), revision(order, null,
                kept(lineOf(order, skuA), 1), new RevisePOLineCommand(null, skuB, null, 1, null, null)));

        assertThat(edited.leadTimeDays()).isEqualTo(20);
        assertThat(edited.expectedAt()).isEqualTo(order.expectedAt());
        assertThat(lineOf(edited, skuB).unitPrice()).isEqualByComparingTo("1000");
        assertThat(number("SELECT lead_time_days FROM procurement.purchase_orders WHERE id = ?",
                order.purchaseOrderId())).isEqualTo(20);
    }

    // ------------------------------------------------------------------ HTTP

    @Test
    @DisplayName("over HTTP a line may leave unitPrice out, on create and on draft edit, and takes the catalog price")
    void aLineWithoutUnitPriceIsAcceptedOverHttp() throws Exception {
        list(skuA, "120000", 1, 1, null);

        String created = mvc.perform(post("/api/v1/purchase-orders").contentType("application/json").content("""
                        {"supplierId":"%s","warehouseId":"%s","currency":"VND",
                         "lines":[{"sku":"%s","quantityOrdered":2}]}"""
                        .formatted(supplier, DemoData.WAREHOUSE_HCM, skuA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].unitPrice").value(120000))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.data.purchaseOrderId");
        String lineId = JsonPath.read(created, "$.data.lines[0].lineId");
        Number version = JsonPath.read(created, "$.data.version");

        mvc.perform(put("/api/v1/purchase-orders/" + id).contentType("application/json").content("""
                        {"version":%d,"warehouseId":"%s","expectedAt":"%s",
                         "lines":[{"lineId":"%s","sku":"%s","quantityOrdered":3}]}"""
                        .formatted(version.longValue(), DemoData.WAREHOUSE_HCM, today().plusDays(20), lineId, skuA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].quantityOrdered").value(3))
                .andExpect(jsonPath("$.data.lines[0].unitPrice").value(120000));
    }
}
