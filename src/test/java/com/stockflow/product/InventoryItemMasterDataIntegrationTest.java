package com.stockflow.product;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.ItemPurchasing;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.product.internal.service.ProductVariantService.NewVariant;
import com.stockflow.product.internal.service.SkuInventoryControlService;
import com.stockflow.product.internal.service.SkuPurchasingService;
import com.stockflow.product.internal.service.SkuUnitService;
import com.stockflow.support.DemoData;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The inventory item master data kltn-docs 01 §3 asks for and the item lacked: the blank foreign keys
 * (glossary "Phôi"), purchasing information (default supplier, reference price, lead time, MOQ; BR-06),
 * the rotatable flag (E13), units of measure (BR-09, BR-10) and the variant's sales units (BR-11).
 * Real database, real services, through the product module as the controllers call them.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class InventoryItemMasterDataIntegrationTest {

    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired SkuInventoryControlService skus;
    @Autowired SkuPurchasingService purchasing;
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
                "Paper cup 12oz", null, null, "Ly", null, null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE, List.of()));
        productId = created.productId();
        variantId = variants.list(productId, 0, 1).items().getFirst().id();
        sku = "CUP-" + suffix;
    }

    // ---- purchasing ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("purchasing info lives on the item: default supplier checked, version shared with logistics")
    void purchasingInformation() {
        var before = purchasing.purchasing(productId, variantId);
        assertThat(before.purchasing()).isEqualTo(new ItemPurchasing(null, null, null, null, null));
        UUID goviet = supplier("GOVIET");

        var after = purchasing.describe(productId, variantId, before.version(),
                new ItemPurchasing(goviet, new BigDecimal("1250.50"), "VND", 10, 500));
        assertThat(after.version()).isGreaterThan(before.version());
        assertThat(after.purchasing().defaultSupplierId()).isEqualTo(goviet);
        assertThat(after.purchasing().referencePurchasePrice()).isEqualByComparingTo("1250.50");
        assertThat(after.purchasing().moq()).isEqualTo(500);
        assertThat(skus.logistics(productId, variantId).version()).isEqualTo(after.version());
        assertThat(history()).contains("PURCHASING");

        assertCode(() -> purchasing.describe(productId, variantId, before.version(),
                new ItemPurchasing(null, null, null, null, null)), ErrorCode.OPTIMISTIC_LOCK);
        assertCode(() -> purchasing.describe(productId, variantId, after.version(),
                new ItemPurchasing(UUID.randomUUID(), null, null, null, null)), ErrorCode.SUPPLIER_NOT_FOUND);
        UUID inactive = inactiveSupplier();
        assertCode(() -> purchasing.describe(productId, variantId, after.version(),
                new ItemPurchasing(inactive, null, null, null, null)), ErrorCode.SUPPLIER_INACTIVE);
        assertField(() -> purchasing.describe(productId, variantId, after.version(),
                new ItemPurchasing(null, new BigDecimal("10"), null, null, null)), "referencePurchaseCurrency");
        assertField(() -> purchasing.describe(productId, variantId, after.version(),
                new ItemPurchasing(null, null, null, 366, null)), "leadTimeDays");
        assertField(() -> purchasing.describe(productId, variantId, after.version(),
                new ItemPurchasing(null, null, null, null, 0)), "moq");

        // An unchanged default supplier is not asked again: deactivated later, it does not block a price edit.
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE inventory.inventory_items SET default_supplier_id = ? WHERE sku = ?", inactive, sku));
        var current = purchasing.purchasing(productId, variantId);
        var repriced = purchasing.describe(productId, variantId, current.version(),
                new ItemPurchasing(inactive, new BigDecimal("99"), "USD", null, null));
        assertThat(repriced.purchasing().referencePurchaseCurrency()).isEqualTo("USD");
    }

    // ---- rotatable -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("rotatable defaults to on and is written with the logistics")
    void rotatable() {
        var before = skus.logistics(productId, variantId);
        assertThat(before.logistics().rotatable()).isTrue();
        var logistics = new ItemLogistics("EACH", null, new BigDecimal("0.01"), new BigDecimal("8"),
                new BigDecimal("8"), new BigDecimal("10"), null, null, null, null, 1, 1, StorageClass.FRAGILE, false,
                null, false, false);
        var after = skus.describe(productId, variantId, before.version(), logistics);
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
        var logistics = skus.logistics(productId, variantId);
        assertCode(() -> skus.describe(productId, variantId, logistics.version(), withUnit(logistics.logistics(),
                "CARTON")), ErrorCode.UOM_CONVERSION_DUPLICATE);
        skus.describe(productId, variantId, logistics.version(), withUnit(logistics.logistics(), "PCS"));
        assertThat(units.conversion(productId, variantId, base.id()).unitOfMeasure()).isEqualTo("PCS");

        UomConversion reread = units.conversion(productId, variantId, carton.id());
        units.removeConversion(productId, variantId, carton.id(), reread.version());
        assertCode(() -> units.conversion(productId, variantId, carton.id()), ErrorCode.UOM_CONVERSION_NOT_FOUND);
        assertThat(history()).contains("UOM_CONVERSION");
    }

    // ---- sales units -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("a variant sells in its base unit by default; other units come from its own item, each with a price")
    void salesUnits() {
        var implicit = units.salesUnits(productId, variantId, 0, 20);
        assertThat(implicit.items()).singleElement().satisfies(u -> {
            assertThat(u.implicitDefault()).isTrue();
            assertThat(u.id()).isNull();
            assertThat(u.base()).isTrue();
            assertThat(u.price()).isNull();
        });
        UUID baseId = implicit.items().getFirst().uomConversionId();
        UomConversion carton = units.addConversion(productId, variantId, packing("CARTON", 1000, false, true));

        assertField(() -> units.addSalesUnit(productId, variantId, baseId, new BigDecimal("2000")), "price");
        assertField(() -> units.addSalesUnit(productId, variantId, carton.id(), null), "price");
        assertField(() -> units.addSalesUnit(productId, variantId, carton.id(), new BigDecimal("10.50")), "price");

        var cartonUnit = units.addSalesUnit(productId, variantId, carton.id(), new BigDecimal("450000"));
        assertThat(cartonUnit.factor()).isEqualTo(1000);
        assertThat(units.salesUnits(productId, variantId, 0, 20).items())
                .singleElement().satisfies(u -> assertThat(u.implicitDefault()).isFalse());
        assertCode(() -> units.addSalesUnit(productId, variantId, carton.id(), new BigDecimal("1")),
                ErrorCode.SALES_UNIT_DUPLICATE);

        // BR-11: only a unit of the variant's own inventory item.
        UUID otherItemsBase = jdbc.queryForObject("""
                SELECT c.id FROM inventory.uom_conversions c JOIN inventory.inventory_items i ON i.id = c.inventory_item_id
                WHERE i.sku = 'SOFA-3S-GREY' AND c.is_base""", UUID.class);
        assertCode(() -> units.addSalesUnit(productId, variantId, otherItemsBase, null),
                ErrorCode.UOM_CONVERSION_NOT_FOUND);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO product.variant_sales_units (id, variant_id, uom_conversion_id) VALUES (?, ?, ?)""",
                Identifiers.newId(), variantId, otherItemsBase))).isInstanceOf(DataIntegrityViolationException.class);

        // A unit still sold in is neither retired nor deleted.
        assertCode(() -> units.changeConversion(productId, variantId, carton.id(), carton.version(),
                packing("CARTON", 1000, false, false)), ErrorCode.UOM_CONVERSION_IN_USE);
        assertCode(() -> units.removeConversion(productId, variantId, carton.id(), carton.version()),
                ErrorCode.UOM_CONVERSION_IN_USE);

        var repriced = units.changeSalesUnit(productId, variantId, cartonUnit.id(), cartonUnit.version(),
                new BigDecimal("480000"));
        assertThat(repriced.price()).isEqualByComparingTo("480000");
        assertCode(() -> units.changeSalesUnit(productId, variantId, cartonUnit.id(), cartonUnit.version(),
                new BigDecimal("1")), ErrorCode.OPTIMISTIC_LOCK);

        var each = units.addSalesUnit(productId, variantId, baseId, null);
        assertThat(units.salesUnits(productId, variantId, 0, 20).totalElements()).isEqualTo(2);
        units.removeSalesUnit(productId, variantId, repriced.id(), repriced.version());
        units.removeSalesUnit(productId, variantId, each.id(), each.version());
        assertThat(units.salesUnits(productId, variantId, 0, 20).items())
                .singleElement().satisfies(u -> assertThat(u.implicitDefault()).isTrue());
        assertCode(() -> units.removeSalesUnit(productId, variantId, each.id(), each.version()),
                ErrorCode.SALES_UNIT_NOT_FOUND);

        // A retired unit is not offered for sale.
        var cartonNow = units.conversion(productId, variantId, carton.id());
        units.changeConversion(productId, variantId, carton.id(), cartonNow.version(), packing("CARTON", 1000, false, false));
        assertField(() -> units.addSalesUnit(productId, variantId, carton.id(), new BigDecimal("450000")),
                "uomConversionId");
        assertThat(history()).contains("SALES_UNIT");
    }

    // ---- blank foreign keys ----------------------------------------------------------------------------------

    @Test
    @DisplayName("a blank is an inventory item: unknown blanks are refused, a used blank is neither renamed nor deleted")
    void blankIsAnInventoryItem() {
        UUID design = snapshot();
        assertThatThrownBy(() -> sampleRequest("NO-SUCH-BLANK-" + suffix, design))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_sample_request_blank");
        UUID sample = sampleRequest(sku, design);

        assertThatThrownBy(() -> variants.update(productId, variantId, new NewVariant(sku + "-R", "12oz", null, null)))
                .isNotNull();
        assertThat(jdbc.queryForObject("SELECT blank_sku FROM production.sample_request WHERE id = ?", String.class,
                sample)).isEqualTo(sku);
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                jdbc.update("DELETE FROM inventory.inventory_items WHERE sku = ?", sku)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static UomConversionDraft packing(String code, int factor, boolean defaultPurchase, boolean active) {
        return new UomConversionDraft(code, factor, new BigDecimal("6.5"), new BigDecimal("52"), new BigDecimal("42"),
                new BigDecimal("60"), defaultPurchase, active);
    }

    private static ItemLogistics withUnit(ItemLogistics l, String unit) {
        return new ItemLogistics(unit, l.barcode(), l.weightKg(), l.lengthCm(), l.widthCm(), l.heightCm(),
                l.packageWeightKg(), l.packageLengthCm(), l.packageWidthCm(), l.packageHeightCm(), l.packageCount(),
                l.packSize(), l.storageClass(), l.requiresAdultSignature(), l.shippingRestrictionNote(),
                l.qcRequired(), l.rotatable());
    }

    private UUID supplier(String code) {
        return jdbc.queryForObject("SELECT id FROM procurement.suppliers WHERE code = ?", UUID.class, code);
    }

    private UUID inactiveSupplier() {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update(
                "INSERT INTO procurement.suppliers (id, code, name, status) VALUES (?, ?, ?, 'INACTIVE')",
                id, "OFF" + suffix, "Inactive " + suffix));
        return id;
    }

    private List<String> history() {
        return jdbc.queryForList("SELECT change_type FROM product.product_change_log WHERE product_id = ?",
                String.class, productId);
    }

    private UUID snapshot() {
        UUID draft = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, DemoData.CUSTOMER_ID, DemoData.PRODUCT_SOFA);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                                                        artifact_manifest)
                    VALUES (?, ?, ?, 'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb)""",
                    snapshot, draft, "a".repeat(64));
        });
        return snapshot;
    }

    private UUID sampleRequest(String blank, UUID design) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO production.sample_request (id, request_number, customer_id, blank_sku, design_snapshot_id,
                    quantity, status)
                VALUES (?, ?, ?, ?, ?, 3, 'DRAFT')""", id, "SR-IT-" + id.toString().substring(24),
                DemoData.CUSTOMER_ID, blank, design));
        return id;
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
