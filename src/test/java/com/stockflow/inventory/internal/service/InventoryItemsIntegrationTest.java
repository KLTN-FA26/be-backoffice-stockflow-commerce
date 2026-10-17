package com.stockflow.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.inventory.api.TrackingMode;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.product.internal.service.ProductVariantService.NewVariant;
import com.stockflow.support.Await;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ProductFixtures;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An inventory item read and written by its own id, with no product in the path: the three groups it answers with,
 * the audit row naming the item, the absence of product history, and the refusals the inventory rules own.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class InventoryItemsIntegrationTest {

    @Autowired InventoryItems items;
    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired ProductTaxonomyService taxonomy;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean CurrentUserProvider users;

    private UUID editor;
    private String suffix;
    private ProductFixtures.Taxonomy tax;

    @BeforeEach
    void setUp() {
        editor = tx.execute(s -> ReferenceRows.user(entityManager));
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        tax = ProductFixtures.taxonomy(taxonomy, suffix);
        when(users.current())
                .thenReturn(Optional.of(new CurrentUser(
                        editor, "test-" + editor, Set.of(), Set.of(), DataScope.ALL, Set.of())));
    }

    private record Fixture(UUID productId, String sku, UUID itemId) {}

    private Fixture fixture() {
        var created = ProductFixtures.draft(products, tax, suffix);
        String sku = "FX-" + suffix;
        return new Fixture(created.productId(), sku, itemId(sku));
    }

    private UUID itemId(String sku) {
        return jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
    }

    private ItemLogistics logistics(String unit, String barcode, String weightKg) {
        return new ItemLogistics(unit, barcode, new BigDecimal(weightKg), new BigDecimal("8"), new BigDecimal("8"),
                new BigDecimal("10"), new BigDecimal("6.2"), new BigDecimal("40"), new BigDecimal("30"),
                new BigDecimal("50"), 1, 50, StorageClass.FRAGILE, false, null, true);
    }

    private static ErrorCode codeOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }

    @Test
    void getReturnsTheThreeGroups() {
        var f = fixture();
        var d = items.get(f.itemId());
        assertThat(d.id()).isEqualTo(f.itemId());
        assertThat(d.sku()).isEqualTo(f.sku());
        assertThat(d.logistics().unitOfMeasure()).isEqualTo("EACH");
        assertThat(d.policy().removalStrategy()).isEqualTo(RemovalStrategy.FEFO);
        assertThat(d.evaluation().usableOnHand()).isZero();
    }

    @Test
    void unknownIdIsInventoryItemNotFound() {
        assertThat(codeOf(() -> items.get(UUID.randomUUID()))).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
    }

    @Test
    void describeBumpsTheVersion() {
        var f = fixture();
        var before = items.get(f.itemId());
        var after = items.describe(f.itemId(), before.version(), logistics("EACH", null, "0.120"));
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.logistics().weightKg()).isEqualByComparingTo("0.120");
    }

    @Test
    void configureSavesThePolicy() {
        var f = fixture();
        var before = items.get(f.itemId());
        var after = items.configure(f.itemId(), before.version(),
                new InventoryPolicy(500, null, null, RemovalStrategy.FEFO, TrackingMode.NONE, false, null));
        assertThat(after.policy().reorderPoint()).isEqualTo(500);
    }

    @Test
    void writesAuditTheItemIdNotTheVariant() {
        var f = fixture();
        var before = items.get(f.itemId());
        items.describe(f.itemId(), before.version(), logistics("EACH", null, "0.120"));
        Await.until("the audit row of the item", Duration.ofSeconds(10), () -> jdbc.queryForObject(
                "select count(*) from platform.audit_log where resource_type = 'inventory-item' and resource_id = ?",
                Long.class, f.itemId().toString()) > 0);
    }

    @Test
    void writesNoProductHistory() {
        var f = fixture();
        var before = items.get(f.itemId());
        var described = items.describe(f.itemId(), before.version(), logistics("EACH", null, "0.120"));
        items.configure(f.itemId(), described.version(),
                new InventoryPolicy(10, 2, 20, RemovalStrategy.FIFO, TrackingMode.NONE, false, null));
        assertThat(jdbc.queryForObject("""
                select count(*) from product.product_change_log
                where change_type in ('LOGISTICS','INVENTORY_CONTROL') and product_id = ?""",
                Long.class, f.productId())).isZero();
    }

    @Test
    void editsAreNotRefusedForAProductUnderReview() {
        var f = fixture();
        products.submit(f.productId(), editor);
        var before = items.get(f.itemId());
        var after = items.describe(f.itemId(), before.version(), logistics("EACH", null, "0.200"));
        assertThat(after.version()).isEqualTo(before.version() + 1);
    }

    @Test
    void unitChangeWhileStockIsHeldIs409() {
        var f = fixture();
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_item(id,sku,location_code,on_hand,reserved,status,received_at,created_at)
                values (?,?,'HCM-A01-1-A',5,0,'AVAILABLE',now(),now())""", UUID.randomUUID(), f.sku()));
        var before = items.get(f.itemId());
        try {
            assertThat(codeOf(() -> items.describe(f.itemId(), before.version(), logistics("BOX", null, "0.120"))))
                    .isEqualTo(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        } finally {
            tx.executeWithoutResult(s -> jdbc.update("delete from inventory.stock_item where sku = ?", f.sku()));
        }
    }

    @Test
    void duplicateBarcodeIs409() {
        var f = fixture();
        var extra = variants.add(f.productId(), new NewVariant("FX-" + suffix + "-16", "16oz", null, null));
        UUID secondId = itemId(extra.sku());
        String barcode = "893" + suffix.replaceAll("[^0-9]", "1");
        var first = items.get(f.itemId());
        items.describe(f.itemId(), first.version(), logistics("EACH", barcode, "0.120"));
        var second = items.get(secondId);
        assertThat(codeOf(() -> items.describe(secondId, second.version(), logistics("EACH", barcode, "0.120"))))
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void staleVersionOnPolicyIsOptimisticLock() {
        var f = fixture();
        var before = items.get(f.itemId());
        items.describe(f.itemId(), before.version(), logistics("EACH", null, "0.120"));
        assertThat(codeOf(() -> items.configure(f.itemId(), before.version(),
                new InventoryPolicy(500, null, null, RemovalStrategy.FEFO, TrackingMode.NONE, false, null))))
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }

    @Test
    void staleVersionOnLogisticsIsOptimisticLock() {
        var f = fixture();
        var before = items.get(f.itemId());
        items.configure(f.itemId(), before.version(),
                new InventoryPolicy(500, null, null, RemovalStrategy.FEFO, TrackingMode.NONE, false, null));
        assertThat(codeOf(() -> items.describe(f.itemId(), before.version(), logistics("EACH", null, "0.120"))))
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }

    @Test
    void sharedVersionRefusesTheSecondGroup() {
        var f = fixture();
        long v = items.get(f.itemId()).version();
        items.describe(f.itemId(), v, logistics("EACH", null, "0.120"));
        assertThat(codeOf(() -> items.configure(f.itemId(), v,
                new InventoryPolicy(500, null, null, RemovalStrategy.FEFO, TrackingMode.NONE, false, null))))
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }

    @Test
    void policySaveStillReevaluatesAlerts() {
        var f = fixture();
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_item(id,sku,location_code,on_hand,reserved,status,received_at,created_at)
                values (?,?,'HCM-A01-1-A',5,0,'AVAILABLE',now(),now())""", UUID.randomUUID(), f.sku()));
        try {
            var before = items.get(f.itemId());
            items.configure(f.itemId(), before.version(),
                    new InventoryPolicy(500, null, null, RemovalStrategy.FEFO, TrackingMode.NONE, false, null));
            Await.until("the alert from the policy-change listener", Duration.ofSeconds(30), () -> jdbc.queryForObject(
                    "SELECT count(*) FROM inventory.stock_alert WHERE sku = ? AND kind = 'REORDER' AND status = 'OPEN'",
                    Long.class, f.sku()) == 1);
        } finally {
            tx.executeWithoutResult(s -> jdbc.update("delete from inventory.stock_item where sku = ?", f.sku()));
        }
    }
}
