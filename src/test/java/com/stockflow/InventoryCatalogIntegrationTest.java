package com.stockflow;

import static org.assertj.core.api.Assertions.*;

import com.stockflow.catalog.internal.domain.SellingPrice;
import com.stockflow.catalog.internal.service.CatalogCommerceService;
import com.stockflow.catalog.internal.service.CatalogProjectionService;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.*;
import com.stockflow.inventory.internal.service.InventoryItems;
import com.stockflow.order.api.OrderService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@IntegrationTest
@Import(PostgresContainer.class)
@AutoConfigureMockMvc
class InventoryCatalogIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryItems items;
    @Autowired InventoryControlService inventoryControl;
    @Autowired InventoryService inventory;
    @Autowired CatalogCommerceService catalog;
    @Autowired CatalogProjectionService projection;
    @Autowired com.stockflow.catalog.internal.service.CatalogProjectionListener projectionListener;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mvc;
    @Autowired OrderService orders;
    @Autowired com.stockflow.product.api.ProductService productService;

    @Test
    void missingPriceRollsBackTheWholeBackfillAndRetryDiscoversItLater() {
        var f = fixture("APPROVED");
        gallery(f);
        String second = f.sku() + "-2";
        write("insert into product.variants(id,product_id,sku,name,status,attribute_signature)"
                + " values (?,?,?,'Second','ACTIVE','SECOND')", UUID.randomUUID(), f.product(), second);
        write("update product.products set status='PUBLISHED',published_at=now() where id=?", f.product());
        testPrice(f.sku());
        var source = jdbc.queryForMap("select * from product.products where id=?", f.product());
        assertThatThrownBy(() -> projection.project(f.product()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRICE_NOT_AVAILABLE));
        assertThat(jdbc.queryForObject("select count(*) from catalog.product_listing where product_id=?",
                Long.class, f.product())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from catalog.catalog_entry where product_id=?",
                Long.class, f.product())).isZero();
        testPrice(second);
        projectionListener.retry();
        assertThat(catalog.detail(catalog.get(f.product()).slug()).variants()).hasSize(2);
        assertThat(jdbc.queryForMap("select * from product.products where id=?", f.product())).isEqualTo(source);
    }

    @Test
    void backfillDoesNotExposeDraftApprovedOrUnapprovedMedia() {
        for (String status : List.of("DRAFT", "APPROVED")) {
            var f = fixture(status);
            gallery(f);
            testPrice(f.sku());
            projection.project(f.product());
            assertThat(jdbc.queryForObject("select count(*) from catalog.product_listing where product_id=?",
                    Long.class, f.product())).isZero();
        }
        var f = fixture("APPROVED");
        gallery(f);
        testPrice(f.sku());
        write("update product.products set status='PUBLISHED',published_at=now() where id=?", f.product());
        write("update product.media set is_published=false where variant_id=?", f.skuId());
        assertThatThrownBy(() -> projection.project(f.product()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRODUCT_GALLERY_REQUIRED));
        assertThat(jdbc.queryForObject("select count(*) from catalog.product_listing where product_id=?",
                Long.class, f.product())).isZero();
    }

    @Test
    void rebuildCannotOverwriteANewerEntryAndRollsBackPartialWrites() {
        var f = fixture("APPROVED");
        gallery(f);
        testPrice(f.sku());
        write("update product.products set status='PUBLISHED',published_at=now() where id=?", f.product());
        projection.project(f.product());
        write("update catalog.catalog_entry set source_revision=100,price=777,title='Newer snapshot' where sku=?", f.sku());
        var entry = jdbc.queryForMap("select * from catalog.catalog_entry where sku=?", f.sku());
        assertThatThrownBy(() -> projection.project(f.product()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(jdbc.queryForMap("select * from catalog.catalog_entry where sku=?", f.sku())).isEqualTo(entry);
    }

    private void testPrice(String sku) {
        write("insert into catalog.pricing_rule(id,name,sku,price,currency,active,created_at)"
                + " values (?,?,?,12500000,'VND',true,now())", UUID.randomUUID(), "BASE:" + sku, sku);
    }

    record Fixture(UUID product, UUID skuId, String sku, UUID itemId) {}

    int write(String sql, Object... args) {
        return transactions.execute(status -> jdbc.update(sql, args));
    }

    Fixture fixture(String status) {
        UUID product = UUID.randomUUID(),
                sku = UUID.randomUUID(),
                category = UUID.randomUUID();
        String code = ("TEST-" + sku.toString().substring(0, 12)).toUpperCase(Locale.ROOT);
        write(
                "insert into product.categories(id,code,name,slug,path) values (?,?,?,?,?)",
                category,
                code,
                code,
                code.toLowerCase(),
                "/" + code);
        write(
                """
insert into product.products(id,code,name,name_en,tax_class,slug,status,submitted_by,submitted_at,approved_by,approved_at,discontinued_at,
                             brand_id,discontinue_reason)
values (?,?,?,'Furniture','STANDARD',?,?,md5('demo:user:editor')::uuid,now(),
        md5('demo:user:approver')::uuid,now(),case when ?='DISCONTINUED' then now() end,
        md5('demo:brand:NHAXINH')::uuid,case when ?='DISCONTINUED' then 'test fixture' end)
""",
                product,
                code,
                "Test furniture",
                code.toLowerCase(),
                status,
                status,
                status);
        write(
                "insert into product.product_categories(id,product_id,category_id,is_primary)"
                        + " values (?,?,?,true)",
                UUID.randomUUID(),
                product,
                category);
        write(
                "insert into product.variants(id,product_id,sku,name,status,attribute_signature)"
                        + " values (?,?,?,'Standard','ACTIVE','')",
                sku,
                product,
                code);
        UUID itemId = inventory.itemIdsOf(List.of(code)).get(code);
        return new Fixture(product, sku, code, itemId);
    }

    void gallery(Fixture f) {
        write(
                """
insert into product.media(id,variant_id,url,is_published,published_at,published_by,created_by)
values (?,?,'https://example.test/cover.jpg',true,now(),md5('demo:user:approver')::uuid,
        md5('demo:user:editor')::uuid::text)
""",
                UUID.randomUUID(),
                f.skuId());
    }

    UUID stock(Fixture f, int quantity, Instant receipt) {
        UUID id = UUID.randomUUID();
        write(
                """
insert into inventory.stock_item(id,sku,location_code,lot_number,on_hand,reserved,status,received_at,created_at)
values (?,?,'HCM-A01-1-A','UNTRACED',?,0,'AVAILABLE',?,now())
""",
                id,
                f.sku(),
                quantity,
                receipt == null ? null : Timestamp.from(receipt));
        return id;
    }

    /** One sealed carton of 2 units at HCM-A01-1-A; its stock row's id. */
    UUID carton(Fixture f, Instant receipt) {
        // In a transaction: the pool does not auto-commit, and the carton and its row are checked together at commit.
        return transactions.execute(status -> com.stockflow.support.CartonRows.stock(jdbc, f.sku(), "HCM-A01-1-A", 1,
                2, null, receipt)).getFirst().stockItemId();
    }

    /** Describes the item with the logistics it already has: a write that advances the version and nothing else. */
    InventoryItems.Detail touch(Fixture f, long expectedVersion) {
        return items.describe(f.itemId(), expectedVersion, items.get(f.itemId()).logistics());
    }

    @Test
    void batchAvailabilityExcludesHoldsAndBlockedStock() {
        var a = fixture("DRAFT");
        var b = fixture("DRAFT");
        Instant received = Instant.parse("2026-01-02T00:00:00Z");
        // Since team plan 4C-2 only whole sealed cartons nothing holds are promised: two free cartons of 2 count; a
        // held one, cartons not AVAILABLE and loose units do not.
        transactions.executeWithoutResult(s -> {
            com.stockflow.support.CartonRows.stock(jdbc, a.sku(), "HCM-A01-1-A", 2, 2, null, received);
            UUID held = com.stockflow.support.CartonRows.stock(jdbc, a.sku(), "HCM-A01-1-A", 1, 2, null, received)
                    .getFirst().stockItemId();
            jdbc.update("update inventory.stock_item set reserved=2 where id=?", held);
            for (String status : List.of("INBOUND", "BLOCKED")) {
                com.stockflow.support.CartonRows.stock(jdbc, a.sku(), "HCM-A01-1-A", 1, 2, null, received, status);
            }
            com.stockflow.support.CartonRows.stock(jdbc, b.sku(), "HCM-A01-1-A", 1, 10, null, received, "QUARANTINE");
        });
        stock(a, 7, Instant.parse("2026-01-01T00:00:00Z"));
        var available = inventory.availableQuantities(Set.of(a.sku(), b.sku(), "MISSING-SKU"));
        assertThat(available.get(a.sku())).isEqualTo(4L);
        assertThat(available.getOrDefault(b.sku(), 0L)).isZero();
        assertThat(available.getOrDefault("MISSING-SKU", 0L)).isZero();
    }

    @Test
    void basePriceDatabaseRejectsForeignCurrencyAndFractionalVnd() {
        var f = fixture("DRAFT");
        var listing = catalog.edit(f.product(), 0, "money-" + f.sku(), null, null);
        catalog.price(
                f.product(),
                f.sku(),
                listing.revision(),
                new SellingPrice(new BigDecimal("100"), "VND"),
                "test");
        assertThatThrownBy(
                        () ->
                                write(
                                        "update catalog.pricing_rule set currency='USD' where"
                                                + " name=?",
                                        "BASE:" + f.sku()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                write(
                                        "update catalog.pricing_rule set price=100.49 where name=?",
                                        "BASE:" + f.sku()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void staleVersionCannotOverwrite() {
        var a = fixture("DRAFT");
        // "the SKU must belong to the product" is gone: the item is addressed by its own id (spec D1)
        touch(a, 0);
        assertThatThrownBy(() -> touch(a, 0)).isInstanceOf(BusinessException.class);
    }

    @Test
    void draftSeoIsPrivateAndPublicationRequiresPriceAndApprovedGallery() {
        var f = fixture("DRAFT");
        catalog.edit(f.product(), 0, "draft-" + f.sku(), "Private SEO", null);
        projection.project(f.product());
        assertThatThrownBy(() -> catalog.detail("draft-" + f.sku().toLowerCase()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> catalog.publish(f.product()))
                .isInstanceOf(BusinessException.class);
        write("update product.products set status='APPROVED' where id=?", f.product());
        assertThatThrownBy(() -> catalog.publish(f.product()))
                .isInstanceOf(BusinessException.class);
        var listing = catalog.get(f.product());
        catalog.price(
                f.product(),
                f.sku(),
                listing.revision(),
                new SellingPrice(new BigDecimal("100.00"), "VND"),
                "test");
        assertThat(catalog.price(f.product(), f.sku()).basePrice().amount())
                .isEqualByComparingTo("100");
        assertThatThrownBy(() -> catalog.publish(f.product()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void publishProjectsAllSkusAndUnpublishHidesImmediatelyEvenBeforeProjection() {
        var f = fixture("APPROVED");
        gallery(f);
        var listing = catalog.edit(f.product(), 0, "public-" + f.sku(), "SEO", null);
        catalog.price(
                f.product(),
                f.sku(),
                listing.revision(),
                new SellingPrice(new BigDecimal("100.00"), "VND"),
                "test");
        catalog.publish(f.product());
        projection.project(f.product());
        String slug = catalog.get(f.product()).slug();
        assertThat(catalog.detail(slug).variants()).hasSize(1);
        catalog.unpublish(f.product());
        assertThatThrownBy(() -> catalog.detail(slug)).isInstanceOf(BusinessException.class);
        projection.project(f.product()); // A late event must not resurrect the product.
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from catalog.catalog_entry where product_id=? and"
                                        + " published",
                                Integer.class,
                                f.product()))
                .isZero();
    }

    @Test
    void duplicateNormalizedSlugIsConflictAndPublishedSlugCannotChange() {
        var a = fixture("DRAFT");
        var b = fixture("DRAFT");
        String slug = "unique-" + a.sku();
        catalog.edit(a.product(), 0, slug, null, null);
        assertThatThrownBy(() -> catalog.edit(b.product(), 0, slug.toLowerCase(), null, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CATALOG_SLUG_EXISTS));
    }

    @Test
    void negativeVersionIsHttp400AndPublicCatalogReturnsAPage() throws Exception {
        var f = fixture("DRAFT");
        mvc.perform(
                        MockMvcRequestBuilders.put("/api/v1/inventory/items/" + f.itemId() + "/logistics")
                                .contentType("application/json")
                                .content(
                                        """
{"version":-1,"unitOfMeasure":"EACH","storageClass":"NORMAL","requiresAdultSignature":false}
"""))
                .andExpect(MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/catalog/products"))
                .andExpect(MockMvcResultMatchers.status().isOk());
    }

    // inventoryControlCannotChangeDuringApprovalOrAfterDiscontinuation removed: an inventory item is
    // independent of its product's status (spec D1); the policy is editable in every product state.

    @Test
    void duplicatePublicationDoesNotIncrementRevisionAndOlderEventUsesLatestSeo() {
        var f = fixture("APPROVED");
        gallery(f);
        var listing = catalog.edit(f.product(), 0, "repeat-" + f.sku(), "Old SEO", null);
        catalog.price(
                f.product(),
                f.sku(),
                listing.revision(),
                new SellingPrice(new BigDecimal("100"), "VND"),
                "test");
        catalog.publish(f.product());
        long revision = catalog.get(f.product()).revision();
        catalog.publish(f.product());
        assertThat(catalog.get(f.product()).revision()).isEqualTo(revision);
        catalog.edit(f.product(), revision, catalog.get(f.product()).slug(), "New SEO", null);
        projection.project(f.product());
        projection.project(f.product());
        assertThat(catalog.detail(catalog.get(f.product()).slug()).seoTitle()).isEqualTo("New SEO");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from catalog.catalog_entry where product_id=?",
                                Integer.class,
                                f.product()))
                .isEqualTo(1);
    }

    @Test
    void equalPriorityPricesConflictAndASecondPublishCannotBypassIt() {
        var f = fixture("APPROVED");
        gallery(f);
        var listing = catalog.edit(f.product(), 0, "price-" + f.sku(), null, null);
        catalog.price(
                f.product(),
                f.sku(),
                listing.revision(),
                new SellingPrice(new BigDecimal("100"), "VND"),
                "test");
        write(
                """
insert into catalog.pricing_rule(id,name,sku,price,currency,priority,active,created_at)
values (?,'Conflicting test rule',?,200,'VND',0,true,now())
""",
                UUID.randomUUID(),
                f.sku());
        assertThatThrownBy(() -> catalog.publish(f.product()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(ErrorCode.CATALOG_PRICE_AMBIGUOUS));
        assertThat(
                        jdbc.queryForObject(
                                "select status from product.products where id=?",
                                String.class,
                                f.product()))
                .isEqualTo("APPROVED");
    }

    @Test
    void twoWritersWithTheSameSkuVersionCannotBothWin() throws Exception {
        var f = fixture("DRAFT");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> action =
                    () -> {
                        start.await();
                        try {
                            touch(f, 0);
                            return true;
                        } catch (BusinessException e) {
                            assertThat(e.errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
                            return false;
                        }
                    };
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void simultaneousSlugClaimHasOneWinnerAndOneBusinessConflict() throws Exception {
        var a = fixture("DRAFT");
        var b = fixture("DRAFT");
        String slug = "race-" + a.sku();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var futures =
                    List.of(a, b).stream()
                            .map(
                                    f ->
                                            executor.submit(
                                                    () -> {
                                                        start.await();
                                                        try {
                                                            catalog.edit(
                                                                    f.product(),
                                                                    0,
                                                                    slug,
                                                                    null,
                                                                    null);
                                                            return true;
                                                        } catch (BusinessException e) {
                                                            assertThat(e.errorCode())
                                                                    .isEqualTo(
                                                                            ErrorCode
                                                                                    .CATALOG_SLUG_EXISTS);
                                                            return false;
                                                        }
                                                    }))
                            .toList();
            start.countDown();
            assertThat(
                            List.of(
                                    futures.get(0).get(15, TimeUnit.SECONDS),
                                    futures.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void canonicalVariantCreatesInventoryItemWithItsOwnVersion() {
        var f = fixture("DRAFT");
        write("update product.variants set status='DRAFT' where id=?", f.skuId());
        var initial = items.get(f.itemId());
        var saved = touch(f, initial.version());
        assertThat(saved.version()).isEqualTo(initial.version() + 1);
        assertThat(
                        jdbc.queryForObject(
                                "select version from product.variants where id=?",
                                Long.class,
                                f.skuId()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select to_regclass('inventory.sku_policy')::text", String.class))
                .isNull();
    }

    @Test
    void seoReadsCanonicalSourceAndProjectionRefreshesAfterDirectSourceEdit() {
        var f = fixture("APPROVED");
        gallery(f);
        var listing = catalog.edit(f.product(), 0, "source-" + f.sku(), "First", "d".repeat(600));
        assertThat(
                        jdbc.queryForObject(
                                "select seo_title from product.products where id=?",
                                String.class,
                                f.product()))
                .isEqualTo("First");
        catalog.price(
                f.product(),
                f.sku(),
                listing.revision(),
                new SellingPrice(new BigDecimal("100"), "VND"),
                "test");
        catalog.publish(f.product());
        projection.project(f.product());
        long staleRevision = catalog.get(f.product()).revision();
        write(
                "update product.products set seo_title='Canonical edit',version=version+1 where"
                        + " id=?",
                f.product());
        assertThat(catalog.get(f.product()).seoTitle()).isEqualTo("Canonical edit");
        assertThatThrownBy(
                        () ->
                                catalog.edit(
                                        f.product(),
                                        staleRevision,
                                        listing.slug(),
                                        "Stale edit",
                                        null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        projection.project(f.product());
        assertThat(catalog.detail(listing.slug()).seoTitle()).isEqualTo("Canonical edit");
        assertThat(catalog.detail(listing.slug()).seoDescription()).hasSize(600);
        catalog.unpublish(f.product());
        assertThatThrownBy(
                        () ->
                                catalog.edit(
                                        f.product(),
                                        catalog.get(f.product()).revision(),
                                        "changed-" + f.sku(),
                                        null,
                                        null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                write(
                                        "update product.products set slug=? where id=?",
                                        "changed-" + f.sku().toLowerCase(),
                                        f.product()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void publicationRejectsSelfApprovedMediaAndBlockedVariantsCannotBePurchased() {
        var f = fixture("APPROVED");
        gallery(f);
        write(
                "update product.media set created_by=published_by::text where variant_id=?",
                f.skuId());
        var listing = catalog.edit(f.product(), 0, "media-" + f.sku(), null, null);
        catalog.price(
                f.product(),
                f.sku(),
                listing.revision(),
                new SellingPrice(new BigDecimal("100"), "VND"),
                "test");
        assertThatThrownBy(() -> catalog.publish(f.product()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(ErrorCode.PRODUCT_GALLERY_REQUIRED));
        write("update product.media set created_by='demo.approver' where variant_id=?", f.skuId());
        assertThatThrownBy(() -> catalog.publish(f.product()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(ErrorCode.PRODUCT_GALLERY_REQUIRED));
        write(
                "update product.media set created_by=md5('demo:user:editor')::uuid::text where"
                        + " variant_id=?",
                f.skuId());
        catalog.publish(f.product());
        projection.project(f.product());
        write("update product.variants set status='BLOCKED' where id=?", f.skuId());
        assertThat(productService.purchasability(Set.of(f.sku())))
                .containsEntry(f.sku().toUpperCase(), com.stockflow.product.api.Purchasability.VARIANT_NOT_ACTIVE);
    }

    @Test
    void canonicalGalleryUrlReadsOnlyApprovedImagesAndHidesImmediately() throws Exception {
        var f = fixture("APPROVED");
        gallery(f);
        write(
                "insert into product.media(id,variant_id,url) values"
                        + " (?,?,'https://example.test/private.jpg')",
                UUID.randomUUID(),
                f.skuId());
        var listing = catalog.edit(f.product(), 0, "gallery-" + f.sku(), null, null);
        catalog.price(
                f.product(),
                f.sku(),
                listing.revision(),
                new SellingPrice(new BigDecimal("100"), "VND"),
                "test");
        catalog.publish(f.product());
        projection.project(f.product());
        String path = "/api/v1/catalog/products/" + listing.slug();
        mvc.perform(MockMvcRequestBuilders.get(path))
                .andExpect(
                        MockMvcResultMatchers.jsonPath("$.data.galleryUrl")
                                .value(path + "/gallery"));
        mvc.perform(MockMvcRequestBuilders.get(path + "/gallery"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.images.length()").value(1))
                .andExpect(
                        MockMvcResultMatchers.jsonPath("$.data.images[0].url")
                                .value("https://example.test/cover.jpg"));
        catalog.unpublish(f.product());
        mvc.perform(MockMvcRequestBuilders.get(path + "/gallery"))
                .andExpect(MockMvcResultMatchers.status().isNotFound());
    }
}
