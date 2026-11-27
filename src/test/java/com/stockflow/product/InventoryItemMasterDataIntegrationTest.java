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
import com.stockflow.support.CartonRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
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
 *
 * <p>The cartons are the catalogue variant's, which has no inventory item (V20270105000100); the item whose unit and
 * stock the checks below need is a customer's SKU drawn on the variant, made as the order naming it would make it
 * ({@link CartonRows#item}).</p>
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
    /** The variant's own code. */
    String variantSku;
    /** A customer's SKU drawn on the variant, with an item: what stock and the item's unit are about. */
    String sku;

    @BeforeEach
    void product() {
        UUID editor = tx.execute(s -> ReferenceRows.user(entityManager));
        when(users.current()).thenReturn(Optional.of(new CurrentUser(editor, "test-" + editor, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        ProductSummary created = products.create(new CreateProductCommand("CUP-" + suffix, "Ly giấy 12oz", null, null, "Ly", null, TaxClass.STANDARD, List.of()));
        productId = created.productId();
        variantId = variants.list(productId, 0, 1).items().getFirst().id();
        variantSku = "CUP-" + suffix;
        sku = variantSku + "-KH-01";
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        tx.executeWithoutResult(s -> {
            UUID draft = Identifiers.newId();
            UUID snapshot = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, customer, productId);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                        artifact_manifest)
                    VALUES (?, ?, ?, 'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb)""",
                    snapshot, draft, "c".repeat(64));
            jdbc.update("""
                    INSERT INTO product.customer_skus (id, product_id, variant_id, customer_id, design_snapshot_id, sku,
                        name)
                    VALUES (?, ?, ?, ?, ?, ?, 'Ly in logo khach')""", Identifiers.newId(), productId, variantId,
                    customer, snapshot, sku);
            CartonRows.item(jdbc, sku);
        });
    }

    @Test
    @DisplayName("a catalogue variant has no inventory item (V20270105000100), yet its cartons are declared and read on it")
    void aVariantHasNoItem() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.inventory_items WHERE sku = ?", Long.class,
                variantSku)).isZero();
        UomConversion carton = units.addConversion(productId, variantId, packing("CARTON", 1000, true));
        assertThat(units.conversions(productId, variantId, 0, 20).items()).extracting(UomConversion::id)
                .containsExactly(carton.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.inventory_items WHERE sku = ?", Long.class,
                variantSku)).as("declaring a carton makes none either").isZero();
        // The database refuses one for a catalogue variant outright.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "INSERT INTO inventory.inventory_items (id, sku, variant_id) VALUES (?, ?, ?)", Identifiers.newId(),
                variantSku, variantId)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /** Logistics are edited on the inventory item by its own id (#118). */
    private UUID itemId() {
        return jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class, sku);
    }

    // ---- rotatable -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("rotatable is the variant's (owner decision Q9): on by default, set on the variant, read by inventory")
    void rotatable() {
        assertThat(variants.get(productId, variantId).rotatable()).isTrue();
        var after = variants.update(productId, variantId,
                new ProductVariantService.NewVariant(null, null, null, null, false));
        assertThat(after.rotatable()).isFalse();
        assertThat(jdbc.queryForObject("SELECT rotatable FROM product.variants WHERE id = ?",
                Boolean.class, variantId)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'inventory' AND table_name = 'inventory_items' AND column_name = 'rotatable'""",
                Integer.class)).as("the item keeps no copy").isZero();
    }

    // ---- units of measure ------------------------------------------------------------------------------------

    @Test
    @DisplayName("an item starts with no packing unit - its own unit (EACH) names its pieces and has no row; a packing "
            + "unit has a factor")
    void unitsOfMeasure() {
        assertThat(units.conversions(productId, variantId, 0, 20).items()).isEmpty();
        assertThat(items.get(itemId()).logistics().unitOfMeasure()).isEqualTo("EACH");

        UomConversion carton = units.addConversion(productId, variantId, packing("CARTON", 1000, true));
        assertThat(units.conversions(productId, variantId, 0, 20).items())
                .extracting(UomConversion::unitOfMeasure).containsExactly("CARTON");
        assertThat(units.conversions(productId, variantId, 1, 1).items()).isEmpty();

        assertCode(() -> units.addConversion(productId, variantId, packing("CARTON", 500, true)),
                ErrorCode.UOM_CONVERSION_DUPLICATE);
        assertCode(() -> units.addConversion(productId, variantId, packing("EACH", 2, true)),
                ErrorCode.UOM_CONVERSION_DUPLICATE);
        assertField(() -> units.addConversion(productId, variantId, packing("BOX", 0, true)), "factor");
        assertField(() -> units.addConversion(productId, variantId, packing("bad unit", 2, true)),
                "unitOfMeasure");
        assertCode(() -> units.changeConversion(productId, variantId, carton.id(), carton.version() + 5,
                packing("CARTON", 1000, true)), ErrorCode.OPTIMISTIC_LOCK);

        // Retiring a unit is BR-10's way out.
        UomConversion retired = units.changeConversion(productId, variantId, carton.id(), carton.version(),
                packing("CARTON", 1000, false));
        assertThat(retired.active()).isFalse();

        // The item's unit names its pieces: a packing unit never has its code, whichever of the two is written
        // (the EACH refused above; tg_uom_conversions_unit_code and tg_inventory_items_unit_code say it again).
        var logistics = items.get(itemId());
        assertCode(() -> items.describe(itemId(), logistics.version(), withUnit(logistics.logistics(),
                "CARTON")), ErrorCode.UOM_CONVERSION_DUPLICATE);
        assertThatThrownBy(() -> tx.executeWithoutResult(t -> jdbc.update(
                "UPDATE inventory.inventory_items SET unit_of_measure = 'CARTON' WHERE id = ?", itemId())))
                .hasMessageContaining("a carton of its variant is already called CARTON");
        assertThatThrownBy(() -> tx.executeWithoutResult(t -> jdbc.update(
                "UPDATE inventory.uom_conversions SET unit_of_measure = 'EACH' WHERE id = ?", carton.id())))
                .hasMessageContaining("the unit the pieces of its variant's SKUs are counted in");
        items.describe(itemId(), logistics.version(), withUnit(logistics.logistics(), "PCS"));
        assertThat(items.get(itemId()).logistics().unitOfMeasure()).isEqualTo("PCS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.uom_conversions WHERE variant_id = "
                + "(SELECT variant_id FROM inventory.inventory_items WHERE id = ?) "
                + "AND unit_of_measure IN ('EACH', 'PCS')", Long.class, itemId())).as("and still no row").isZero();

        UomConversion reread = units.conversion(productId, variantId, carton.id());
        units.removeConversion(productId, variantId, carton.id(), reread.version());
        assertCode(() -> units.conversion(productId, variantId, carton.id()), ErrorCode.UOM_CONVERSION_NOT_FOUND);
        assertThat(history()).contains("UOM_CONVERSION");
    }

    @Test
    @DisplayName("a unit a document counts in keeps its factor and code (BR-10): it is retired, never changed or deleted")
    void unitInUse() {
        UomConversion carton = units.addConversion(productId, variantId, packing("CARTON", 1000, true));
        // A stock package is one of the documents inventory.unit_in_use asks about (V20261020000600).
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                                                     location_code)
                VALUES (?, ?, ?, ?, 1000, 'HCM-A01-1-A')""", Identifiers.newId(), "IT-" + suffix, itemId(),
                carton.id()));

        assertCode(() -> units.changeConversion(productId, variantId, carton.id(), carton.version(),
                packing("CARTON", 500, true)), ErrorCode.UOM_CONVERSION_IN_USE);
        assertCode(() -> units.changeConversion(productId, variantId, carton.id(), carton.version(),
                packing("BOX", 1000, true)), ErrorCode.UOM_CONVERSION_IN_USE);
        assertCode(() -> units.removeConversion(productId, variantId, carton.id(), carton.version()),
                ErrorCode.UOM_CONVERSION_IN_USE);

        // Its size and weight are not what a document was converted with, and retiring it is BR-10's way out.
        UomConversion resized = units.changeConversion(productId, variantId, carton.id(), carton.version(),
                new UomConversionDraft("CARTON", 1000, new BigDecimal("7"), new BigDecimal("52"),
                        new BigDecimal("42"), new BigDecimal("60"), true));
        assertThat(resized.weightKg()).isEqualByComparingTo("7");
        UomConversion retired = units.changeConversion(productId, variantId, carton.id(), resized.version(),
                packing("CARTON", 1000, false));
        assertThat(retired.active()).isFalse();
        assertThat(retired.factor()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a SKU has one active carton (01 BR-03): the one a document uses is retired first, then the new one is declared")
    void replacingTheCartonRetiresFirst() {
        UomConversion carton = units.addConversion(productId, variantId, packing("CARTON", 1000, true));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                                                     location_code)
                VALUES (?, ?, ?, ?, 1000, 'HCM-A01-1-A')""", Identifiers.newId(), "IT-" + suffix, itemId(),
                carton.id()));

        assertCode(() -> units.addConversion(productId, variantId, packing("CARTON2", 1000, true)),
                ErrorCode.UOM_CARTON_ALREADY_DECLARED);
        // A retired unit is history, declared beside the active one freely.
        assertThat(units.addConversion(productId, variantId, packing("OLDBOX", 500, false)).active()).isFalse();

        units.changeConversion(productId, variantId, carton.id(), carton.version(), packing("CARTON", 1000, false));
        UomConversion replacement = units.addConversion(productId, variantId, packing("CARTON2", 1000, true));
        assertThat(replacement.active()).isTrue();
        // ...and the retired one cannot come back beside it.
        UomConversion retired = units.conversion(productId, variantId, carton.id());
        assertCode(() -> units.changeConversion(productId, variantId, carton.id(), retired.version(),
                packing("CARTON", 1000, true)), ErrorCode.UOM_CARTON_ALREADY_DECLARED);
    }

    @Test
    @DisplayName("a unit a document is being written in right now is in use too: the document holds its lock (BR-10)")
    void unitBeingWrittenOnADocument() throws Exception {
        UomConversion carton = units.addConversion(productId, variantId, packing("CARTON", 1000, true));
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
                    packing("CARTON", 500, true)), ErrorCode.UOM_CONVERSION_IN_USE);
            assertCode(() -> units.removeConversion(productId, variantId, carton.id(), carton.version()),
                    ErrorCode.UOM_CONVERSION_IN_USE);
        } finally {
            done.countDown();
            document.get(30, TimeUnit.SECONDS);
            writer.shutdown();
        }

        // That transaction wrote no document after all: the unit is free again.
        assertThat(units.changeConversion(productId, variantId, carton.id(), carton.version(),
                packing("CARTON", 500, true)).factor()).isEqualTo(500);
    }

    // ---- sales unit -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("01 BR-11: a variant is sold by its item's active carton - read, never listed - and a carton is "
            + "retired and replaced with nothing in the way (BR-10)")
    void salesUnitIsTheCarton() {
        // No carton yet: nothing to sell in.
        assertThat(units.salesUnits(productId, variantId, 0, 20).items()).isEmpty();

        UomConversion carton = units.addConversion(productId, variantId, packing("CARTON", 1000, true));
        assertThat(units.salesUnits(productId, variantId, 0, 20).items()).singleElement().satisfies(u -> {
            assertThat(u.variantId()).isEqualTo(variantId);
            assertThat(u.uomConversionId()).isEqualTo(carton.id());
            assertThat(u.unitOfMeasure()).isEqualTo("CARTON");
            assertThat(u.factor()).isEqualTo(1000);
        });
        assertThat(units.salesUnits(productId, variantId, 1, 20).items()).as("one unit, one page").isEmpty();

        // 5-3 (kltn-docs 13 BR-04, 01 BR-11): no list of sales units and no price on a unit.
        assertThat(jdbc.queryForObject("SELECT to_regclass('product.variant_sales_units') IS NULL", Boolean.class))
                .isTrue();

        // BR-10: the carton specification is replaced by retiring the unit and declaring a new one; the sales unit
        // follows by itself.
        units.changeConversion(productId, variantId, carton.id(), carton.version(), packing("CARTON", 1000, false));
        assertThat(units.salesUnits(productId, variantId, 0, 20).items()).as("retired: nothing to sell in").isEmpty();
        UomConversion replaced = units.addConversion(productId, variantId, packing("CARTON500", 500, true));
        assertThat(units.salesUnits(productId, variantId, 0, 20).items()).singleElement().satisfies(u -> {
            assertThat(u.uomConversionId()).isEqualTo(replaced.id());
            assertThat(u.factor()).isEqualTo(500);
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static UomConversionDraft packing(String code, int factor, boolean active) {
        return new UomConversionDraft(code, factor, new BigDecimal("6.5"), new BigDecimal("52"), new BigDecimal("42"),
                new BigDecimal("60"), active);
    }

    private static ItemLogistics withUnit(ItemLogistics l, String unit) {
        return new ItemLogistics(unit, l.barcode());

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
