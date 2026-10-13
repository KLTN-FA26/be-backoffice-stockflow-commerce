package com.stockflow.procurement;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.RevisePOLineCommand;
import com.stockflow.procurement.api.RevisePurchaseOrderCommand;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.product.internal.service.SkuInventoryControlService;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ProductFixtures;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * kltn-docs 01 step 12 and 02 (BR-01 and the edge cases), SCRUM-461, against Postgres: when a product is discontinued
 * or suspended, goods already on their way are received as usual, but no new purchase order line takes the SKU — in
 * every place a line enters a standard order (create, edit of a draft, submit, approve), each refusal naming the line
 * and the reason. A purchase order for a SKU that is purchasable again is accepted.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class ProductEndOfLifePurchasingIntegrationTest {

    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired SkuInventoryControlService skus;
    @Autowired ProductTaxonomyService taxonomy;
    @Autowired ProcurementService procurement;
    @Autowired GoodsReceipts receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private UUID editor;
    private UUID approver;
    private UUID supplier;
    private String suffix;
    private ProductFixtures.Taxonomy tax;

    @BeforeEach
    void setUp() {
        editor = tx.execute(s -> ReferenceRows.user(entityManager));
        approver = tx.execute(s -> ReferenceRows.user(entityManager));
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        tax = ProductFixtures.taxonomy(taxonomy, suffix);
        supplier = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.suppliers (id, code, name, status, version, created_at)
                VALUES (?, ?, 'End of life supplier', 'ACTIVE', 0, NOW())""",
                supplier, "EL-" + supplier.toString().substring(24).toUpperCase(Locale.ROOT)));
    }

    private ProductSummary approved(String tag) {
        return ProductFixtures.approved(products, tax, suffix + tag, editor, approver);
    }

    /** What a SKU needs before goods of it are received (BR-03): its weight and dimensions. */
    private ProductSummary approvedWithLogistics(String tag) {
        ProductSummary product = approved(tag);
        UUID variant = variants.list(product.productId(), 0, 5).items().getFirst().id();
        skus.describe(product.productId(), variant, skus.logistics(product.productId(), variant).version(),
                new ItemLogistics("EACH", null, new BigDecimal("0.5"), new BigDecimal("10"), new BigDecimal("10"),
                        new BigDecimal("10"), new BigDecimal("0.6"), new BigDecimal("12"), new BigDecimal("12"),
                        new BigDecimal("12"), 1, 1, StorageClass.NORMAL, false, null, false));
        return product;
    }

    private CreatePurchaseOrderCommand create(String... skus) {
        return new CreatePurchaseOrderCommand(supplier, DemoData.WAREHOUSE_HCM, "VND", LocalDate.now().plusDays(10), null,
                java.util.Arrays.stream(skus).map(sku -> new CreatePOLineCommand(sku, null, 2, new BigDecimal("1000"))).toList());
    }

    private static BusinessException refusal(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return (BusinessException) thrown;
    }

    private static List<String> fields(BusinessException e) {
        return e.fieldErrors().stream().map(f -> f.field() + ":" + f.code()).toList();
    }

    /** A CONFIRMED order on the new tables with one line of {@code sku}, as the goods receipt tests build it. */
    private UUID confirmedOrder(String sku, int quantity) {
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID confirmer = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    VALUES (?, ?, 'CONFIRMED', ?, ?, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW())""",
                    po, "PO-EL-" + po.toString().substring(24), supplier, DemoData.WAREHOUSE_HCM, submitter, confirmer,
                    confirmer, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at)
                    SELECT ?, ?, ?, 1, id, ?, 1000, 0, NOW() FROM inventory.inventory_items WHERE sku = ?""",
                    Identifiers.newId(), po, revision, quantity, sku);
        });
        return po;
    }

    /** A goods receipt for {@code quantity} of the order's only line, confirmed. */
    private void receive(UUID po, int quantity) {
        var draft = receipts.create(new GoodsReceipts.Create(po, "DN-EL", null), editor);
        receipts.replaceLines(draft.id(), List.of(new GoodsReceipts.NewLine(lineOf(po), quantity, null, null, "HCM-RCV01", null)));
        assertThat(receipts.confirm(draft.id(), editor).lines()).singleElement()
                .satisfies(l -> assertThat(l.quantity()).isEqualTo(quantity));
    }

    private UUID lineOf(UUID po) {
        return jdbc.queryForObject("SELECT id FROM procurement.purchase_order_lines WHERE po_id = ?", UUID.class, po);
    }

    private String status(UUID po) {
        return jdbc.queryForObject("SELECT status FROM procurement.purchase_orders WHERE id = ?", String.class, po);
    }

    // ------------------------------------------------------------------ goods on their way

    @Test
    @DisplayName("a line already open is received in full after the product is discontinued, then suspended; the order closes")
    void receiptsOfOpenLinesStillWork() {
        ProductSummary discontinuedSoon = approvedWithLogistics("A");
        ProductSummary suspendedSoon = approvedWithLogistics("B");
        UUID poA = confirmedOrder(discontinuedSoon.code(), 4);
        UUID poB = confirmedOrder(suspendedSoon.code(), 6);

        var result = products.discontinue(discontinuedSoon.productId(), "end of line", discontinuedSoon.version());
        products.suspend(suspendedSoon.productId(), "supplier recall", suspendedSoon.version());
        assertThat(result.warnings()).containsExactly("OPEN_PURCHASE_ORDER_LINES");
        assertThat(result.references().references()).singleElement()
                .satisfies(r -> assertThat(r.documentId()).isEqualTo(poA));

        receive(poA, 4);
        receive(poB, 6);
        assertThat(status(poA)).isEqualTo("RECEIVED");
        assertThat(status(poB)).isEqualTo("RECEIVED");
        // The stock arrived in the receiving area, for a SKU whose variants are OBSOLETE: it can still be cleared out.
        assertThat(jdbc.queryForObject("SELECT sum(on_hand) FROM inventory.stock_item WHERE sku = ?", Long.class,
                discontinuedSoon.code())).isEqualTo(4);
        assertThat(variants.list(discontinuedSoon.productId(), 0, 10).items())
                .extracting(v -> v.status().name()).containsOnly("OBSOLETE");
    }

    @Test
    @DisplayName("a partly received order keeps receiving the rest after the product is discontinued")
    void partialReceiptContinues() {
        ProductSummary product = approvedWithLogistics("C");
        UUID po = confirmedOrder(product.code(), 10);
        receive(po, 6);
        assertThat(status(po)).isEqualTo("PARTIALLY_RECEIVED");

        products.discontinue(product.productId(), "end of line", product.version());

        receive(po, 4);
        assertThat(status(po)).isEqualTo("RECEIVED");
    }

    // ------------------------------------------------------------------ new lines

    @Test
    @DisplayName("create: a discontinued, suspended, unapproved or blocked SKU is 409 SKU_NOT_PURCHASABLE, each line named with its reason")
    void newLinesAreRefusedWithTheReason() {
        ProductSummary good = approved("G");
        ProductSummary discontinued = approved("D");
        products.discontinue(discontinued.productId(), "end", discontinued.version());
        ProductSummary suspended = approved("S");
        products.suspend(suspended.productId(), "recall", suspended.version());
        ProductSummary draft = ProductFixtures.draft(products, tax, suffix + "T");
        ProductSummary blocked = approved("K");
        variants.block(blocked.productId(), variants.list(blocked.productId(), 0, 5).items().getFirst().id());

        BusinessException refused = refusal(catchThrowable(() -> procurement.createPurchaseOrder(create(good.code(),
                discontinued.code(), suspended.code(), draft.code(), blocked.code()))));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
        assertThat(fields(refused)).containsExactly("lines[1].sku:PRODUCT_DISCONTINUED", "lines[2].sku:PRODUCT_INACTIVE",
                "lines[3].sku:PRODUCT_NOT_APPROVED", "lines[4].sku:VARIANT_NOT_ACTIVE");
        // Nothing was written: the refusal came before the order.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM procurement.purchase_order_lines l JOIN inventory.inventory_items i ON i.id = l.inventory_item_id
                 WHERE i.sku IN (?, ?)""", Long.class, discontinued.code(), suspended.code())).isZero();
        assertThat(procurement.createPurchaseOrder(create(good.code())).lines()).hasSize(1);
    }

    @Test
    @DisplayName("create: the SKU is accepted again once the product is resumed")
    void resumedSkuIsPurchasableAgain() {
        ProductSummary product = approved("R");
        ProductSummary suspended = products.suspend(product.productId(), "recall", product.version());
        assertThat(refusal(catchThrowable(() -> procurement.createPurchaseOrder(create(product.code())))).errorCode())
                .isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);

        products.resume(product.productId(), suspended.version());

        assertThat(procurement.createPurchaseOrder(create(product.code())).lines()).singleElement()
                .satisfies(l -> assertThat(l.sku()).isEqualTo(product.code()));
    }

    @Test
    @DisplayName("edit of a draft: a line for a SKU that is not purchasable is refused, taking the dead line out is accepted")
    void reviseDraft() {
        ProductSummary kept = approved("K");
        ProductSummary dying = approved("Y");
        PurchaseOrderSummary draft = procurement.createPurchaseOrder(create(kept.code(), dying.code()));
        products.discontinue(dying.productId(), "end", dying.version());

        var lines = draft.lines();
        UUID keptLine = lines.stream().filter(l -> l.sku().equals(kept.code())).findFirst().orElseThrow().lineId();
        UUID dyingLine = lines.stream().filter(l -> l.sku().equals(dying.code())).findFirst().orElseThrow().lineId();
        RevisePurchaseOrderCommand unchanged = new RevisePurchaseOrderCommand(draft.version(), DemoData.WAREHOUSE_HCM,
                draft.expectedAt(), null, List.of(
                new RevisePOLineCommand(keptLine, kept.code(), null, 2, new BigDecimal("1000"), null),
                new RevisePOLineCommand(dyingLine, dying.code(), null, 2, new BigDecimal("1000"), null)));

        BusinessException refused = refusal(catchThrowable(() -> procurement.reviseDraft(draft.purchaseOrderId(), unchanged)));
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
        assertThat(fields(refused)).containsExactly("lines[1].sku:PRODUCT_DISCONTINUED");

        RevisePurchaseOrderCommand withoutIt = new RevisePurchaseOrderCommand(draft.version(), DemoData.WAREHOUSE_HCM,
                draft.expectedAt(), null, List.of(new RevisePOLineCommand(keptLine, kept.code(), null, 5, new BigDecimal("1000"), null)));
        assertThat(procurement.reviseDraft(draft.purchaseOrderId(), withoutIt).lines()).singleElement()
                .satisfies(l -> assertThat(l.quantityOrdered()).isEqualTo(5));
    }

    @Test
    @DisplayName("submit: a draft holding a SKU discontinued meanwhile is refused at the line and stays a draft; without it, it goes")
    void submitRefusesAStaleDraft() {
        ProductSummary kept = approved("K");
        ProductSummary dying = approved("Y");
        PurchaseOrderSummary draft = procurement.createPurchaseOrder(create(kept.code(), dying.code()));
        products.discontinue(dying.productId(), "end", dying.version());

        BusinessException refused = refusal(catchThrowable(() -> procurement.submit(draft.purchaseOrderId(), editor)));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
        assertThat(fields(refused)).containsExactly("lines[1].sku:PRODUCT_DISCONTINUED");
        assertThat(status(draft.purchaseOrderId())).isEqualTo("DRAFT");
        // No revision was frozen by the refused submission (a revision is append-only and must not outlive it).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement.purchase_order_revisions WHERE po_id = ?", Long.class,
                draft.purchaseOrderId())).isZero();

        PurchaseOrderSummary fine = procurement.createPurchaseOrder(create(kept.code()));
        assertThat(procurement.submit(fine.purchaseOrderId(), editor).status()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("approve: an order waiting for approval whose SKU was suspended meanwhile is refused; resumed, it is approved")
    void approveRefusesAStaleOrder() {
        ProductSummary product = approved("P");
        PurchaseOrderSummary order = procurement.createPurchaseOrder(create(product.code()));
        procurement.submit(order.purchaseOrderId(), editor);
        ProductSummary suspended = products.suspend(product.productId(), "recall", product.version());

        BusinessException refused = refusal(catchThrowable(() -> procurement.approve(order.purchaseOrderId(), approver)));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.SKU_NOT_PURCHASABLE);
        assertThat(fields(refused)).containsExactly("lines[0].sku:PRODUCT_INACTIVE");
        assertThat(status(order.purchaseOrderId())).isEqualTo("PENDING_APPROVAL");

        products.resume(product.productId(), suspended.version());
        assertThat(procurement.approve(order.purchaseOrderId(), approver).status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("an approved order is not asked again: it was approved while the SKU was active, and confirmation sends it on")
    void anApprovedOrderIsNotAskedAgain() {
        ProductSummary product = approved("Q");
        PurchaseOrderSummary order = procurement.createPurchaseOrder(create(product.code()));
        procurement.submit(order.purchaseOrderId(), editor);
        procurement.approve(order.purchaseOrderId(), approver);

        products.discontinue(product.productId(), "end", product.version());

        // Approved stays approved; it can still be cancelled, and the discontinuation did not touch it.
        assertThat(status(order.purchaseOrderId())).isEqualTo("APPROVED");
        assertThat(procurement.cancel(order.purchaseOrderId(), editor, "Product discontinued").status()).isEqualTo("CANCELLED");
    }
}
