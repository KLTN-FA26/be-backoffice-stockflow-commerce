package com.stockflow.procurement.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.SaveSupplierCommand;
import com.stockflow.procurement.api.SaveSupplierItemCommand;
import com.stockflow.procurement.api.SkuSupplierSummary;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.procurement.api.SupplierItemSummary;
import com.stockflow.procurement.api.SupplierService;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-77 supplier catalog service against Postgres: who may list what, the price history a reprice
 * writes, the single preferred supplier per SKU, and the guards on edit and removal.
 *
 * <p>Every service call commits on its own, as it does behind the controller; the rows are then read
 * back with {@code JdbcTemplate} inside a {@link TransactionTemplate}, because Hikari runs with
 * auto-commit off.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class SupplierItemServiceIntegrationTest {

    @Autowired SupplierItemService catalog;
    @Autowired SupplierService suppliers;
    @Autowired ProcurementService procurement;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private UUID supplierId;
    private String sku;

    @BeforeEach
    void setUp() {
        supplierId = supplier("Alpha supplier");
        sku = approvedSku();
    }

    private String approvedSku() {
        return tx.execute(s -> ReferenceRows.approvedProduct(entityManager).sku());
    }

    private UUID supplier(String name) {
        String code = "SIS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        return suppliers.create(new SaveSupplierCommand(code, name, "Contact", "supplier@example.com",
                "+84901234567", null, "ACTIVE", 30, 7, "EMAIL", null, null, false, null)).supplierId();
    }

    private static SaveSupplierItemCommand command(String sku, String price, boolean preferred, Long version) {
        return new SaveSupplierItemCommand(sku, "SUP-" + price, new BigDecimal(price), 10, 5, null, preferred, version);
    }

    private SupplierItemSummary create(UUID supplier, String price, boolean preferred) {
        return catalog.create(supplier, command(sku, price, preferred, null));
    }

    private List<Map<String, Object>> periods(UUID itemId) {
        return tx.execute(s -> jdbc.queryForList("""
                SELECT unit_price, currency, effective_from, effective_to
                  FROM procurement.supplier_item_prices
                 WHERE supplier_item_id = ? ORDER BY effective_from""", itemId));
    }

    private List<UUID> preferredRows(UUID inventoryItemId) {
        return tx.execute(s -> jdbc.queryForList("""
                SELECT id FROM procurement.supplier_items
                 WHERE inventory_item_id = ? AND is_preferred""", UUID.class, inventoryItemId));
    }

    private boolean rowExists(UUID itemId) {
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM procurement.supplier_items WHERE id = ?)", Boolean.class, itemId)));
    }

    private static void assertRefused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    private static void assertFieldRefused(ThrowingCallable call, ErrorCode code, String field, String fieldCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(code);
            assertThat(e.fieldErrors()).singleElement().satisfies(error -> {
                assertThat(error.field()).isEqualTo(field);
                assertThat(error.code()).isEqualTo(fieldCode);
            });
        });
    }

    @Test
    @DisplayName("create lists the SKU in the supplier's currency, opens one price period, and the page names every SKU")
    void createListsTheSkuInTheSuppliersCurrencyWithOnePricePeriod() {
        var created = create(supplierId, "1500", false);

        assertThat(created.supplierId()).isEqualTo(supplierId);
        assertThat(created.sku()).isEqualTo(sku);
        assertThat(created.currency()).isEqualTo("VND");
        assertThat(created.price()).isEqualByComparingTo("1500");
        assertThat(created.moq()).isEqualTo(10);
        assertThat(created.packSize()).isEqualTo(5);
        assertThat(created.leadTimeDays()).isNull();
        assertThat(created.effectiveLeadTimeDays()).isEqualTo(7);
        assertThat(created.preferred()).isFalse();
        assertThat(created.version()).isZero();
        assertThat(created.createdAt()).isNotNull();
        assertThat(periods(created.supplierItemId())).singleElement().satisfies(period -> {
            assertThat((BigDecimal) period.get("unit_price")).isEqualByComparingTo("1500");
            assertThat(period.get("currency")).isEqualTo("VND");
            assertThat(period.get("effective_to")).isNull();
        });
        Map<String, Object> row = tx.execute(s -> jdbc.queryForMap(
                "SELECT inventory_item_id, currency FROM procurement.supplier_items WHERE id = ?",
                created.supplierItemId()));
        assertThat(row.get("inventory_item_id")).isEqualTo(created.inventoryItemId());
        assertThat(row.get("currency")).isEqualTo("VND");

        String second = approvedSku();
        catalog.create(supplierId, command(second, "2000", false, null));
        var page = catalog.list(supplierId, 0, 20, null);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).extracting(SupplierItemSummary::sku).containsExactlyInAnyOrder(sku, second);
    }

    @Test
    @DisplayName("an inactive supplier can neither list a SKU nor edit one, but can still drop one")
    void anInactiveSupplierCannotListOrEdit() {
        var listed = create(supplierId, "1500", false);
        suppliers.deactivate(supplierId);

        assertRefused(() -> catalog.create(supplierId, command(approvedSku(), "1500", false, null)),
                ErrorCode.SUPPLIER_INACTIVE);
        assertRefused(() -> catalog.update(supplierId, listed.supplierItemId(), command(sku, "1600", false, 0L)),
                ErrorCode.SUPPLIER_INACTIVE);

        catalog.delete(supplierId, listed.supplierItemId(), listed.version());
        assertThat(rowExists(listed.supplierItemId())).isFalse();
    }

    @Test
    @DisplayName("a SKU with no inventory item is INVENTORY_ITEM_NOT_FOUND, on create and on lookup")
    void anUnknownSkuIsRefused() {
        assertRefused(() -> catalog.create(supplierId, command("NO-SUCH-SKU-77", "1500", false, null)),
                ErrorCode.INVENTORY_ITEM_NOT_FOUND);
        assertRefused(() -> catalog.suppliersOf("NO-SUCH-SKU-77"), ErrorCode.INVENTORY_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("a malformed SKU is VALIDATION_FAILED with SUPPLIER_ITEM_SKU_INVALID on the sku field, not a 500")
    void aMalformedSkuIsAValidationError() {
        assertFieldRefused(() -> catalog.create(supplierId, command("not a sku!", "1500", false, null)),
                ErrorCode.VALIDATION_FAILED, "sku", "SUPPLIER_ITEM_SKU_INVALID");
        assertFieldRefused(() -> catalog.suppliersOf("not a sku!"),
                ErrorCode.VALIDATION_FAILED, "sku", "SUPPLIER_ITEM_SKU_INVALID");
    }

    @Test
    @DisplayName("a draft product's SKU is SKU_NOT_PURCHASABLE, with the reason on the sku field")
    void aDraftSkuIsNotPurchasable() {
        String draft = tx.execute(s -> ReferenceRows.product(entityManager).sku());

        assertFieldRefused(() -> catalog.create(supplierId, command(draft, "1500", false, null)),
                ErrorCode.SKU_NOT_PURCHASABLE, "sku", "PRODUCT_NOT_APPROVED");
    }

    @Test
    @DisplayName("listing the same SKU twice for one supplier is SUPPLIER_ITEM_DUPLICATE")
    void aDuplicatePairIsAConflict() {
        create(supplierId, "1500", false);

        assertRefused(() -> create(supplierId, "1600", false), ErrorCode.SUPPLIER_ITEM_DUPLICATE);
    }

    @Test
    @DisplayName("a MOQ that is not a whole number of packs is VALIDATION_FAILED with its own code on moq")
    void moqNotAMultipleOfPackSizeIsRefused() {
        var oddMoq = new SaveSupplierItemCommand(sku, null, new BigDecimal("1500"), 5, 2, null, false, null);

        assertFieldRefused(() -> catalog.create(supplierId, oddMoq), ErrorCode.VALIDATION_FAILED, "moq",
                "SUPPLIER_ITEM_MOQ_NOT_PACK_MULTIPLE");
    }

    @Test
    @DisplayName("a VND price with decimals is refused on price instead of being rounded")
    void aVndPriceWithDecimalsIsRefused() {
        assertFieldRefused(() -> create(supplierId, "1500.50", false), ErrorCode.VALIDATION_FAILED, "price",
                "SUPPLIER_ITEM_PRICE_INVALID");
        assertFieldRefused(() -> create(supplierId, "0", false), ErrorCode.VALIDATION_FAILED, "price",
                "SUPPLIER_ITEM_PRICE_INVALID");
        assertFieldRefused(() -> create(supplierId, "10000000000000000", false), ErrorCode.VALIDATION_FAILED,
                "price", "SUPPLIER_ITEM_PRICE_INVALID");
        assertThat(catalog.list(supplierId, 0, 20, null).totalElements()).isZero();
    }

    @Test
    @DisplayName("a new price closes the open period and opens the next; an unchanged price writes none")
    void priceChangeWritesANewPeriodAndClosesTheOld() {
        var created = create(supplierId, "1000", false);

        var repriced = catalog.update(supplierId, created.supplierItemId(), command(sku, "1200", false, 0L));

        assertThat(repriced.price()).isEqualByComparingTo("1200");
        assertThat(repriced.version()).isEqualTo(1);
        var rows = periods(created.supplierItemId());
        assertThat(rows).hasSize(2);
        assertThat((BigDecimal) rows.get(0).get("unit_price")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) rows.get(1).get("unit_price")).isEqualByComparingTo("1200");
        assertThat((Timestamp) rows.get(0).get("effective_to")).isEqualTo((Timestamp) rows.get(1).get("effective_from"));
        assertThat(rows.get(1).get("effective_to")).isNull();

        var sameTerms = new SaveSupplierItemCommand(sku, "NEW-CODE", new BigDecimal("1200.00"), 20, 5, 3, false, 1L);
        var edited = catalog.update(supplierId, created.supplierItemId(), sameTerms);

        assertThat(edited.supplierSkuCode()).isEqualTo("NEW-CODE");
        assertThat(edited.moq()).isEqualTo(20);
        assertThat(edited.effectiveLeadTimeDays()).isEqualTo(3);
        assertThat(periods(created.supplierItemId())).hasSize(2);
    }

    @Test
    @DisplayName("preferring a second supplier, on create or on update, unprefers the first")
    void preferringASecondSupplierUnprefersTheFirst() {
        var first = create(supplierId, "1500", true);
        var second = create(supplier("Beta supplier"), "1400", true);

        assertThat(preferredRows(first.inventoryItemId())).containsExactly(second.supplierItemId());

        UUID gamma = supplier("Gamma supplier");
        var third = create(gamma, "1300", false);
        catalog.update(gamma, third.supplierItemId(), command(sku, "1300", true, third.version()));

        assertThat(preferredRows(first.inventoryItemId())).containsExactly(third.supplierItemId());
    }

    @Test
    @DisplayName("saving the preferred row again keeps it preferred")
    void resavingThePreferredRowKeepsIt() {
        var preferred = create(supplierId, "1500", true);

        var resaved = catalog.update(supplierId, preferred.supplierItemId(), command(sku, "1550", true, 0L));

        assertThat(resaved.preferred()).isTrue();
        assertThat(preferredRows(preferred.inventoryItemId())).containsExactly(preferred.supplierItemId());
    }

    @Test
    @DisplayName("unpreferring the preferred row leaves the SKU with no preferred supplier")
    void unpreferringLeavesNoPreferredSupplier() {
        var preferred = create(supplierId, "1500", true);

        var unpreferred = catalog.update(supplierId, preferred.supplierItemId(), command(sku, "1500", false, 0L));

        assertThat(unpreferred.preferred()).isFalse();
        assertThat(preferredRows(preferred.inventoryItemId())).isEmpty();
    }

    @Test
    @DisplayName("two preferred creates for one SKU at once both succeed and leave exactly one preferred row")
    void concurrentPreferredCreatesSerialise() throws Exception {
        UUID beta = supplier("Beta supplier");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<SupplierItemSummary> a = pool.submit(() -> {
                start.await();
                return catalog.create(supplierId, command(sku, "1500", true, null));
            });
            Future<SupplierItemSummary> b = pool.submit(() -> {
                start.await();
                return catalog.create(beta, command(sku, "1400", true, null));
            });
            start.countDown();

            var first = a.get(30, TimeUnit.SECONDS);
            var second = b.get(30, TimeUnit.SECONDS);

            assertThat(preferredRows(first.inventoryItemId())).singleElement()
                    .isIn(first.supplierItemId(), second.supplierItemId());
            assertThat(catalog.suppliersOf(sku)).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("two switches of one SKU's preferred supplier at once both succeed and leave one preferred row")
    void concurrentSwitchesSerialise() throws Exception {
        UUID beta = supplier("Beta supplier");
        var alphaItem = create(supplierId, "1500", false);
        var betaItem = create(beta, "1400", false);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<SupplierItemSummary> a = pool.submit(() -> {
                start.await();
                return catalog.update(supplierId, alphaItem.supplierItemId(),
                        command(sku, "1500", true, alphaItem.version()));
            });
            Future<SupplierItemSummary> b = pool.submit(() -> {
                start.await();
                return catalog.update(beta, betaItem.supplierItemId(), command(sku, "1400", true, betaItem.version()));
            });
            start.countDown();

            var first = a.get(30, TimeUnit.SECONDS);
            var second = b.get(30, TimeUnit.SECONDS);

            assertThat(first.preferred()).isTrue();
            assertThat(second.preferred()).isTrue();
            assertThat(preferredRows(alphaItem.inventoryItemId())).singleElement()
                    .isIn(alphaItem.supplierItemId(), betaItem.supplierItemId());
            assertThat(catalog.suppliersOf(sku)).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a stored row outside the form's limits still lists, looks up, gets unpreferred, edits and deletes")
    void aStoredRowOutsideTheFormLimitsStillReadsAndDeletes() {
        UUID beta = supplier("Beta supplier");
        var alphaItem = create(supplierId, "1500", true);
        var betaItem = create(beta, "1400", false);
        // What the table admits and the form refuses: no CHECK holds moq to whole packs or lead time to 365.
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE procurement.supplier_items SET moq = 5, pack_size = 2, lead_time_days = 366
                 WHERE id IN (?, ?)""", alphaItem.supplierItemId(), betaItem.supplierItemId()));

        assertThat(catalog.list(supplierId, 0, 20, null).items()).singleElement().satisfies(item -> {
            assertThat(item.moq()).isEqualTo(5);
            assertThat(item.packSize()).isEqualTo(2);
            assertThat(item.leadTimeDays()).isEqualTo(366);
        });
        assertThat(catalog.suppliersOf(sku)).extracting(SkuSupplierSummary::supplierItemId)
                .containsExactly(alphaItem.supplierItemId(), betaItem.supplierItemId());

        // Preferring beta loads the stored alpha row to unprefer it; editing beta replaces its bad terms.
        var edited = catalog.update(beta, betaItem.supplierItemId(),
                new SaveSupplierItemCommand(sku, "BETA-1", new BigDecimal("1400"), 10, 5, 30, true, betaItem.version()));
        assertThat(edited.moq()).isEqualTo(10);
        assertThat(edited.leadTimeDays()).isEqualTo(30);
        assertThat(preferredRows(alphaItem.inventoryItemId())).containsExactly(betaItem.supplierItemId());

        var stored = catalog.list(supplierId, 0, 20, null).items().get(0);
        catalog.delete(supplierId, stored.supplierItemId(), stored.version());
        assertThat(rowExists(alphaItem.supplierItemId())).isFalse();
    }

    @Test
    @DisplayName("another supplier's item is SUPPLIER_ITEM_NOT_FOUND through this supplier, for edit and removal")
    void anItemIsOnlyReachableThroughItsOwnSupplier() {
        var betaItem = create(supplier("Beta supplier"), "1500", false);

        assertRefused(() -> catalog.update(supplierId, betaItem.supplierItemId(), command(sku, "1", false, 0L)),
                ErrorCode.SUPPLIER_ITEM_NOT_FOUND);
        assertRefused(() -> catalog.delete(supplierId, betaItem.supplierItemId(), 0L),
                ErrorCode.SUPPLIER_ITEM_NOT_FOUND);
        assertRefused(() -> catalog.update(supplierId, UUID.randomUUID(), command(sku, "1", false, 0L)),
                ErrorCode.SUPPLIER_ITEM_NOT_FOUND);

        assertThat(rowExists(betaItem.supplierItemId())).isTrue();
        assertThat(periods(betaItem.supplierItemId())).singleElement()
                .satisfies(p -> assertThat((BigDecimal) p.get("unit_price")).isEqualByComparingTo("1500"));
    }

    @Test
    @DisplayName("an edit or removal against an old version is OPTIMISTIC_LOCK and changes nothing")
    void aStaleVersionIsAConflict() {
        var created = create(supplierId, "1500", false);
        catalog.update(supplierId, created.supplierItemId(), command(sku, "1600", false, 0L));

        assertRefused(() -> catalog.update(supplierId, created.supplierItemId(), command(sku, "1700", false, 0L)),
                ErrorCode.OPTIMISTIC_LOCK);
        assertRefused(() -> catalog.delete(supplierId, created.supplierItemId(), 0L), ErrorCode.OPTIMISTIC_LOCK);

        assertThat(catalog.list(supplierId, 0, 20, null).items()).singleElement().satisfies(item -> {
            assertThat(item.price()).isEqualByComparingTo("1600");
            assertThat(item.version()).isEqualTo(1);
        });
        assertThat(periods(created.supplierItemId())).hasSize(2);
    }

    @Test
    @DisplayName("removal is SUPPLIER_ITEM_IN_USE while an open order buys the SKU from the supplier, allowed once it is cancelled")
    void deleteIsRefusedWhileAnOpenOrderUsesThePair() {
        var listed = create(supplierId, "1500", false);
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID order = procurement.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, DemoData.WAREHOUSE_HCM,
                "VND", LocalDate.now().plusDays(10), null,
                List.of(new CreatePOLineCommand(sku, "Catalog line", 10, new BigDecimal("1500"))))).purchaseOrderId();

        assertRefused(() -> catalog.delete(supplierId, listed.supplierItemId(), listed.version()),
                ErrorCode.SUPPLIER_ITEM_IN_USE);
        assertThat(rowExists(listed.supplierItemId())).isTrue();

        procurement.cancel(order, user, "catalog test");
        catalog.delete(supplierId, listed.supplierItemId(), listed.version());

        assertThat(rowExists(listed.supplierItemId())).isFalse();
        assertThat(periods(listed.supplierItemId())).isEmpty();
    }

    @Test
    @DisplayName("listing the catalog of a supplier that does not exist is SUPPLIER_NOT_FOUND")
    void listingAnUnknownSupplierIs404() {
        assertRefused(() -> catalog.list(UUID.randomUUID(), 0, 20, null), ErrorCode.SUPPLIER_NOT_FOUND);
        assertRefused(() -> catalog.create(UUID.randomUUID(), command(sku, "1500", false, null)),
                ErrorCode.SUPPLIER_NOT_FOUND);
    }

    @Test
    @DisplayName("a SKU's suppliers can be looked up in lower case and come preferred first")
    void suppliersOfAcceptsALowercaseSkuAndListsThePreferredFirst() {
        var alpha = create(supplierId, "1500", false);
        UUID beta = supplier("Beta supplier");
        var betaItem = catalog.create(beta,
                new SaveSupplierItemCommand(sku, "BETA-1", new BigDecimal("1400"), 10, 5, 3, true, null));

        List<SkuSupplierSummary> rows = catalog.suppliersOf(sku.toLowerCase(Locale.ROOT));

        assertThat(rows).extracting(SkuSupplierSummary::supplierItemId)
                .containsExactly(betaItem.supplierItemId(), alpha.supplierItemId());
        var top = rows.get(0);
        assertThat(top.supplierId()).isEqualTo(beta);
        assertThat(top.supplierName()).isEqualTo("Beta supplier");
        assertThat(top.supplierCode()).startsWith("SIS-");
        assertThat(top.supplierStatus()).isEqualTo("ACTIVE");
        assertThat(top.supplierSkuCode()).isEqualTo("BETA-1");
        assertThat(top.price()).isEqualByComparingTo("1400");
        assertThat(top.currency()).isEqualTo("VND");
        assertThat(top.effectiveLeadTimeDays()).isEqualTo(3);
        assertThat(top.preferred()).isTrue();
        assertThat(rows.get(1).effectiveLeadTimeDays()).isEqualTo(7);
        assertThat(rows.get(1).preferred()).isFalse();
    }
}
