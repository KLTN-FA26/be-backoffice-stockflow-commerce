package com.stockflow.product;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.inventory.internal.service.InventoryItems;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.product.internal.service.SkuUnitService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The inventory item master data kltn-docs 01 §3 asks for and the item lacked: the blank foreign keys
 * (glossary "Phôi"),
 * the rotatable flag (E13), units of measure (BR-09, BR-10) and the variant's sales units (BR-11).
 * Real database, real services, through the product module as the controllers call them.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class InventoryItemMasterDataIntegrationTest {

    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired InventoryItems items;
    @Autowired SkuUnitService units;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean UploadInspection inspection;
    @MockitoBean CurrentUserProvider users;

    String suffix;
    UUID productId;
    UUID variantId;
    String sku;

    @BeforeEach
    void product() {
        UUID editor = tx.execute(s -> ReferenceRows.user(entityManager));
        when(users.current()).thenReturn(Optional.of(new CurrentUser(editor, "test-" + editor, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        ProductSummary created = products.create(new CreateProductCommand("CUP-" + suffix, "Ly giấy 12oz",
                "Paper cup 12oz", null, null, "Ly", null, null, TaxClass.STANDARD, List.of()));
        productId = created.productId();
        variantId = variants.list(productId, 0, 1).items().getFirst().id();
        sku = "CUP-" + suffix;
    }

    /** Logistics are edited on the inventory item by its own id (#118). */
    private UUID itemId() {
        return jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class, sku);
    }

    // ---- rotatable -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("rotatable defaults to on and is written with the logistics")
    void rotatable() {
        var before = items.get(itemId());
        assertThat(before.logistics().rotatable()).isTrue();
        var logistics = new ItemLogistics("EACH", null, new BigDecimal("0.01"), new BigDecimal("8"),
                new BigDecimal("8"), new BigDecimal("10"), null, null, null, null, 1, 1, false);
        var after = items.describe(itemId(), before.version(), logistics);
        assertThat(after.logistics().rotatable()).isFalse();
        assertThat(jdbc.queryForObject("SELECT rotatable FROM inventory.inventory_items WHERE sku = ?",
                Boolean.class, sku)).isFalse();
    }

    // ---- units of measure ------------------------------------------------------------------------------------

    @Test
    @DisplayName("an item starts with its base unit only; packing units have a factor and one default purchase unit")
    void unitsOfMeasure() {
        var start = units.conversions(productId, variantId, 0, 20);
        assertThat(start.items()).singleElement().satisfies(base -> {
            assertThat(base.base()).isTrue();
            assertThat(base.unitOfMeasure()).isEqualTo("EACH");
            assertThat(base.factor()).isEqualTo(1);
            assertThat(base.defaultPurchase()).isTrue();
        });
        UomConversion base = start.items().getFirst();

        UomConversion carton = units.addConversion(productId, variantId, packing("CARTON", 1000, true, true));
        assertThat(carton.defaultPurchase()).isTrue();
        assertThat(units.conversion(productId, variantId, base.id()).defaultPurchase()).isFalse();
        assertThat(units.conversions(productId, variantId, 0, 20).items())
                .extracting(UomConversion::unitOfMeasure).containsExactly("EACH", "CARTON");
        assertThat(units.conversions(productId, variantId, 1, 1).items())
                .extracting(UomConversion::unitOfMeasure).containsExactly("CARTON");

        assertCode(() -> units.addConversion(productId, variantId, packing("CARTON", 500, false, true)),
                ErrorCode.UOM_CONVERSION_DUPLICATE);
        assertCode(() -> units.addConversion(productId, variantId, packing("EACH", 2, false, true)),
                ErrorCode.UOM_CONVERSION_DUPLICATE);
        assertField(() -> units.addConversion(productId, variantId, packing("BOX", 0, false, true)), "factor");
        assertField(() -> units.addConversion(productId, variantId, packing("bad unit", 2, false, true)),
                "unitOfMeasure");
        assertField(() -> units.changeConversion(productId, variantId, carton.id(), carton.version(),
                packing("CARTON", 1000, true, false)), "defaultPurchase");
        assertCode(() -> units.changeConversion(productId, variantId, carton.id(), carton.version() + 5,
                packing("CARTON", 1000, true, true)), ErrorCode.OPTIMISTIC_LOCK);
        assertCode(() -> units.removeConversion(productId, variantId, base.id(), 0), ErrorCode.UOM_CONVERSION_IN_USE);

        // Retiring the default purchase unit (BR-10) hands the mark back to the base unit.
        UomConversion retired = units.changeConversion(productId, variantId, carton.id(), carton.version(),
                packing("CARTON", 1000, false, false));
        assertThat(retired.active()).isFalse();
        assertThat(units.conversion(productId, variantId, base.id()).defaultPurchase()).isTrue();

        // The base unit follows the item's unit code; a packing unit's code cannot be taken by it.
        var logistics = items.get(itemId());
        assertCode(() -> items.describe(itemId(), logistics.version(), withUnit(logistics.logistics(),
                "CARTON")), ErrorCode.UOM_CONVERSION_DUPLICATE);
        items.describe(itemId(), logistics.version(), withUnit(logistics.logistics(), "PCS"));
        assertThat(units.conversion(productId, variantId, base.id()).unitOfMeasure()).isEqualTo("PCS");

        UomConversion reread = units.conversion(productId, variantId, carton.id());
        units.removeConversion(productId, variantId, carton.id(), reread.version());
        assertCode(() -> units.conversion(productId, variantId, carton.id()), ErrorCode.UOM_CONVERSION_NOT_FOUND);
        assertThat(history()).contains("UOM_CONVERSION");
    }

    @Test
    @DisplayName("a unit a document counts in keeps its factor and code (BR-10): it is retired, never changed or deleted")
    void unitInUse() {
        UomConversion carton = units.addConversion(productId, variantId, packing("CARTON", 1000, true, true));
        // A stock package is one of the documents inventory.unit_in_use asks about (V20261020000600).
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                                                     location_code)
                VALUES (?, ?, ?, ?, 1000, 'HCM-A01-1-A')""", Identifiers.newId(), "IT-" + suffix, itemId(),
                carton.id()));

        assertCode(() -> units.changeConversion(productId, variantId, carton.id(), carton.version(),
                packing("CARTON", 500, true, true)), ErrorCode.UOM_CONVERSION_IN_USE);
        assertCode(() -> units.changeConversion(productId, variantId, carton.id(), carton.version(),
                packing("BOX", 1000, true, true)), ErrorCode.UOM_CONVERSION_IN_USE);
        assertCode(() -> units.removeConversion(productId, variantId, carton.id(), carton.version()),
                ErrorCode.UOM_CONVERSION_IN_USE);

        // Its size and weight are not what a document was converted with, and retiring it is BR-10's way out.
        UomConversion resized = units.changeConversion(productId, variantId, carton.id(), carton.version(),
                new UomConversionDraft("CARTON", 1000, new BigDecimal("7"), new BigDecimal("52"),
                        new BigDecimal("42"), new BigDecimal("60"), true, true));
        assertThat(resized.weightKg()).isEqualByComparingTo("7");
        UomConversion retired = units.changeConversion(productId, variantId, carton.id(), resized.version(),
                packing("CARTON", 1000, false, false));
        assertThat(retired.active()).isFalse();
        assertThat(retired.factor()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a unit a document is being written in right now is in use too: the document holds its lock (BR-10)")
    void unitBeingWrittenOnADocument() throws Exception {
        UomConversion carton = units.addConversion(productId, variantId, packing("CARTON", 1000, true, true));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        // What check_stock_package_created and check_po_line_unit do before they read the unit, in a
        // transaction that has not committed: unit_in_use cannot see its document yet.
        Future<?> document = writer.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("select true from (select pg_advisory_xact_lock_shared(inventory.unit_lock_key(?))) l",
                    Boolean.class, carton.id());
            held.countDown();
            try {
                done.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            assertCode(() -> units.changeConversion(productId, variantId, carton.id(), carton.version(),
                    packing("CARTON", 500, true, true)), ErrorCode.UOM_CONVERSION_IN_USE);
            assertCode(() -> units.removeConversion(productId, variantId, carton.id(), carton.version()),
                    ErrorCode.UOM_CONVERSION_IN_USE);
        } finally {
            done.countDown();
            document.get(30, TimeUnit.SECONDS);
            writer.shutdown();
        }

        // That transaction wrote no document after all: the unit is free again.
        assertThat(units.changeConversion(productId, variantId, carton.id(), carton.version(),
                packing("CARTON", 500, true, true)).factor()).isEqualTo(500);
    }

    // ---- sales units -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("a variant sells in its base unit by default; other units come from its own item, with no price")
    void salesUnits() {
        var implicit = units.salesUnits(productId, variantId, 0, 20);
        assertThat(implicit.items()).singleElement().satisfies(u -> {
            assertThat(u.implicitDefault()).isTrue();
            assertThat(u.id()).isNull();
            assertThat(u.base()).isTrue();
        });
        UUID baseId = implicit.items().getFirst().uomConversionId();
        UomConversion carton = units.addConversion(productId, variantId, packing("CARTON", 1000, false, true));

        // 5-3 (kltn-docs 13 BR-04, 01 BR-11): a unit names how the variant is sold, never a price.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema = 'product'
                  AND table_name = 'variant_sales_units' AND column_name = 'price'""", Long.class)).isZero();

        var cartonUnit = units.addSalesUnit(productId, variantId, carton.id());
        assertThat(cartonUnit.factor()).isEqualTo(1000);
        assertThat(units.salesUnits(productId, variantId, 0, 20).items())
                .singleElement().satisfies(u -> assertThat(u.implicitDefault()).isFalse());
        assertCode(() -> units.addSalesUnit(productId, variantId, carton.id()), ErrorCode.SALES_UNIT_DUPLICATE);

        // BR-11: only a unit of the variant's own inventory item.
        UUID otherItemsBase = jdbc.queryForObject("""
                SELECT c.id FROM inventory.uom_conversions c JOIN inventory.inventory_items i ON i.id = c.inventory_item_id
                WHERE i.sku = 'SOFA-3S-GREY' AND c.is_base""", UUID.class);
        assertCode(() -> units.addSalesUnit(productId, variantId, otherItemsBase), ErrorCode.UOM_CONVERSION_NOT_FOUND);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO product.variant_sales_units (id, variant_id, uom_conversion_id) VALUES (?, ?, ?)""",
                Identifiers.newId(), variantId, otherItemsBase))).isInstanceOf(DataIntegrityViolationException.class);

        // A unit still sold in is neither retired nor deleted.
        assertCode(() -> units.changeConversion(productId, variantId, carton.id(), carton.version(),
                packing("CARTON", 1000, false, false)), ErrorCode.UOM_CONVERSION_IN_USE);
        assertCode(() -> units.removeConversion(productId, variantId, carton.id(), carton.version()),
                ErrorCode.UOM_CONVERSION_IN_USE);

        var each = units.addSalesUnit(productId, variantId, baseId);
        assertThat(units.salesUnits(productId, variantId, 0, 20).totalElements()).isEqualTo(2);
        assertCode(() -> units.removeSalesUnit(productId, variantId, cartonUnit.id(), cartonUnit.version() + 1),
                ErrorCode.OPTIMISTIC_LOCK);
        units.removeSalesUnit(productId, variantId, cartonUnit.id(), cartonUnit.version());
        units.removeSalesUnit(productId, variantId, each.id(), each.version());
        assertThat(units.salesUnits(productId, variantId, 0, 20).items())
                .singleElement().satisfies(u -> assertThat(u.implicitDefault()).isTrue());
        assertCode(() -> units.removeSalesUnit(productId, variantId, each.id(), each.version()),
                ErrorCode.SALES_UNIT_NOT_FOUND);

        // A retired unit is not offered for sale.
        var cartonNow = units.conversion(productId, variantId, carton.id());
        units.changeConversion(productId, variantId, carton.id(), cartonNow.version(), packing("CARTON", 1000, false, false));
        assertField(() -> units.addSalesUnit(productId, variantId, carton.id()), "uomConversionId");
        assertThat(history()).contains("SALES_UNIT");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static UomConversionDraft packing(String code, int factor, boolean defaultPurchase, boolean active) {
        return new UomConversionDraft(code, factor, new BigDecimal("6.5"), new BigDecimal("52"), new BigDecimal("42"),
                new BigDecimal("60"), defaultPurchase, active);
    }

    private static ItemLogistics withUnit(ItemLogistics l, String unit) {
        return new ItemLogistics(unit, l.barcode(), l.weightKg(), l.lengthCm(), l.widthCm(), l.heightCm(),
                l.packageWeightKg(), l.packageLengthCm(), l.packageWidthCm(), l.packageHeightCm(), l.packageCount(),
                l.packSize(), l.rotatable());
    }

    private List<String> history() {
        return jdbc.queryForList("SELECT change_type FROM product.product_change_log WHERE product_id = ?",
                String.class, productId);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    private static void assertField(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.fieldErrors()).extracting(com.stockflow.common.api.FieldError::field).contains(field);
        });
    }
}
