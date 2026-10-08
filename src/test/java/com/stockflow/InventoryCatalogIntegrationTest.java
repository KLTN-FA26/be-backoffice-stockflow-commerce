package com.stockflow;

import static org.assertj.core.api.Assertions.*;

import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.internal.domain.SellingPrice;
import com.stockflow.catalog.internal.service.CatalogCommerceService;
import com.stockflow.catalog.internal.service.CatalogProjectionService;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.*;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.PlaceGuestOrderCommand;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.product.internal.service.SkuInventoryControlService;
import com.stockflow.support.DemoData;
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
import java.time.LocalDate;
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
    @Autowired SkuInventoryControlService controls;
    @Autowired InventoryControlService inventoryControl;
    @Autowired InventoryService inventory;
    @Autowired CatalogCommerceService catalog;
    @Autowired CatalogProjectionService projection;
    @Autowired com.stockflow.catalog.internal.service.CatalogProjectionListener projectionListener;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mvc;
    @Autowired OrderService orders;
    @Autowired CatalogService checkoutCatalog;

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

    @Test
    void stockMoveKeepsCanonicalSerialAndReceiptIdentity() {
        var f = fixture("APPROVED");
        controls.update(
                f.product(),
                f.skuId(),
                0,
                policy(null, RemovalStrategy.FIFO, TrackingMode.SERIAL, false));
        var received = Instant.parse("2026-09-01T00:00:00Z");
        var source = stock(f, 1, received, null, "MOVE-SERIAL");
        write("update inventory.stock_item set location_code='HCM-A01-1-B' where id=?", source);
        var command =
                new MoveStockCommand(
                        UUID.randomUUID(),
                        new Sku(f.sku()),
                        null,
                        "HCM-A01-1-B",
                        "HCM-PACK01",
                        1,
                        MoveReference.MOVE_TASK,
                        null,
                        jdbc.queryForObject(
                                "select id from identity.app_user where"
                                    + " id=md5('demo:user:editor')::uuid",
                                UUID.class));
        var moved = inventory.move(command);
        assertThat(
                        jdbc.queryForMap(
                                """
                                select on_hand,serial_number,received_at from inventory.stock_item
                                where sku=? and location_code='HCM-PACK01'
                                """,
                                f.sku()))
                .containsEntry("on_hand", 1)
                .containsEntry("serial_number", "MOVE-SERIAL")
                .containsEntry("received_at", Timestamp.from(received));
        assertThat(inventory.move(command).movementId()).isEqualTo(moved.movementId());
    }

    @Test
    void stockMoveDoesNotChooseAnArbitraryReceiptLayer() {
        var f = fixture("APPROVED");
        controls.update(
                f.product(),
                f.skuId(),
                0,
                policy(null, RemovalStrategy.FIFO, TrackingMode.NONE, false));
        stock(f, 3, Instant.parse("2026-09-01T00:00:00Z"), null, null);
        stock(f, 4, Instant.parse("2026-09-02T00:00:00Z"), null, null);
        var command =
                new MoveStockCommand(
                        UUID.randomUUID(),
                        new Sku(f.sku()),
                        null,
                        "HCM-A-01",
                        "HCM-PACK01",
                        1,
                        MoveReference.MOVE_TASK,
                        null,
                        jdbc.queryForObject(
                                "select id from identity.app_user where"
                                    + " id=md5('demo:user:editor')::uuid",
                                UUID.class));
        assertThatThrownBy(() -> inventory.move(command))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.errorCode())
                                        .isEqualTo(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT));
        assertThat(
                        jdbc.queryForObject(
                                "select sum(on_hand) from inventory.stock_item where sku=?",
                                Integer.class,
                                f.sku()))
                .isEqualTo(7);
    }

    record Fixture(UUID product, UUID skuId, String sku) {}

    int write(String sql, Object... args) {
        return transactions.execute(status -> jdbc.update(sql, args));
    }

    Fixture fixture(String status) {
        UUID product = UUID.randomUUID(),
                variant = UUID.randomUUID(),
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
insert into product.products(id,code,name,name_en,tax_class,slug,status,submitted_by,submitted_at,approved_by,approved_at,discontinued_at)
values (?,?,?,'Furniture','STANDARD',?,?,md5('demo:user:editor')::uuid,now(),
        md5('demo:user:approver')::uuid,now(),case when ?='DISCONTINUED' then now() end)
""",
                product,
                code,
                "Test furniture",
                code.toLowerCase(),
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
        return new Fixture(product, sku, code);
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

    UUID stock(Fixture f, int quantity, Instant receipt, LocalDate expiry, String serial) {
        UUID id = UUID.randomUUID();
        write(
                """
insert into inventory.stock_item(id,sku,location_code,on_hand,reserved,status,received_at,expiry_date,serial_number,lot_number,created_at)
values (?,?,'HCM-A-01',?,0,'AVAILABLE',?,?,?,?,now())
""",
                id,
                f.sku(),
                quantity,
                receipt == null ? null : Timestamp.from(receipt),
                expiry,
                serial,
                expiry == null ? null : "TEST-LOT");
        return id;
    }

    InventoryPolicy policy(
            Integer threshold, RemovalStrategy strategy, TrackingMode tracking, boolean expiry) {
        return new InventoryPolicy(
                threshold, null, strategy, tracking, expiry, expiry ? 365 : null);
    }

    @Test
    void batchAvailabilityExcludesHoldsBlockedAndExpiredStock() {
        var a = fixture("DRAFT");
        var b = fixture("DRAFT");
        UUID held = stock(a, 6, null, null, null);
        write("update inventory.stock_item set reserved=2 where id=?", held);
        UUID blocked = stock(b, 10, null, null, null);
        write("update inventory.stock_item set status='QUARANTINE' where id=?", blocked);
        stock(a, 20, Instant.parse("2026-01-01T00:00:00Z"), LocalDate.of(2000, 1, 1), null);
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
    void guestCheckoutUsesServerPriceAndReplayKeepsTheAgreedSnapshot() {
        var f = fixture("APPROVED");
        gallery(f);
        stock(f, 10, null, null, null);
        var listing = catalog.edit(f.product(), 0, "checkout-" + f.sku(), null, null);
        catalog.price(
                f.product(),
                f.sku(),
                listing.revision(),
                new SellingPrice(new BigDecimal("100"), "VND"),
                "test");
        catalog.publish(f.product());
        projection.project(f.product());
        var address =
                new PlaceGuestOrderCommand.Address(
                        "Minh",
                        "0901234567",
                        "12 Nguyen Hue",
                        null,
                        "26734",
                        "Ben Nghe",
                        "79",
                        "Ho Chi Minh City",
                        "VN",
                        null);
        var tampered =
                new PlaceGuestOrderCommand(
                        UUID.randomUUID(),
                        "minh@example.com",
                        address,
                        address,
                        List.of(
                                new PlaceGuestOrderCommand.Line(
                                        new Sku(f.sku()), 1, Money.vnd(1))));
        assertThatThrownBy(() -> orders.placeGuestOrder(tampered))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CHECKOUT_PRICE_CHANGED));
        assertThat(inventory.availableToPromise(new Sku(f.sku()))).isEqualTo(10);
        var accepted =
                new PlaceGuestOrderCommand(
                        UUID.randomUUID(),
                        "minh@example.com",
                        address,
                        address,
                        List.of(
                                new PlaceGuestOrderCommand.Line(
                                        new Sku(f.sku()), 1, Money.vnd(100))));
        var order = orders.placeGuestOrder(accepted);
        catalog.price(
                f.product(),
                f.sku(),
                catalog.get(f.product()).revision(),
                new SellingPrice(new BigDecimal("200"), "VND"),
                "test");
        assertThat(orders.placeGuestOrder(accepted).orderId()).isEqualTo(order.orderId());
        assertThat(orders.placeGuestOrder(accepted).total()).isEqualTo(Money.vnd(100));
        assertThat(inventory.availableToPromise(new Sku(f.sku()))).isEqualTo(9);
        catalog.unpublish(f.product());
        assertThatThrownBy(() -> checkoutCatalog.checkoutPrices(Set.of(f.sku())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(ErrorCode.PRODUCT_NOT_PURCHASABLE));
    }

    @Test
    void thresholdsAreNullableZeroIsMeaningfulAndHigherThanStockIsAllowed() {
        var f = fixture("DRAFT");
        assertThat(controls.get(f.product(), f.skuId()).policy().reorderPoint()).isNull();
        var saved =
                controls.update(
                        f.product(),
                        f.skuId(),
                        0,
                        policy(10, RemovalStrategy.FEFO, TrackingMode.NONE, false));
        assertThat(saved.evaluation().reorderRequired()).isTrue();
        assertThat(inventoryControl.policy(new Sku(f.sku())).reorderPoint()).isEqualTo(10);
        var cleared =
                controls.update(
                        f.product(),
                        f.skuId(),
                        saved.version(),
                        policy(null, RemovalStrategy.FEFO, TrackingMode.NONE, false));
        assertThat(cleared.evaluation().reorderRequired()).isNull();
        var zero =
                controls.update(
                        f.product(),
                        f.skuId(),
                        cleared.version(),
                        policy(0, RemovalStrategy.FEFO, TrackingMode.NONE, false));
        assertThat(zero.evaluation().reorderRequired()).isTrue();
    }

    @Test
    void skuMustBelongToProductAndStaleVersionCannotOverwrite() {
        var a = fixture("DRAFT");
        var b = fixture("DRAFT");
        assertThatThrownBy(() -> controls.get(a.product(), b.skuId()))
                .isInstanceOf(BusinessException.class);
        controls.update(
                a.product(),
                a.skuId(),
                0,
                policy(5, RemovalStrategy.FEFO, TrackingMode.NONE, false));
        assertThatThrownBy(
                        () ->
                                controls.update(
                                        a.product(),
                                        a.skuId(),
                                        0,
                                        policy(6, RemovalStrategy.FEFO, TrackingMode.NONE, false)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void legacyUnknownReceiptBlocksFifoWithoutChangingTheSku() {
        var f = fixture("DRAFT");
        stock(f, 10, null, null, null);
        assertThatThrownBy(
                        () ->
                                controls.update(
                                        f.product(),
                                        f.skuId(),
                                        0,
                                        policy(3, RemovalStrategy.FIFO, TrackingMode.NONE, false)))
                .isInstanceOf(BusinessException.class);
        assertThat(controls.get(f.product(), f.skuId()).policy().reorderPoint()).isNull();
    }

    @Test
    void fifoActuallyReservesTheOlderReceiptLayer() {
        var f = fixture("APPROVED");
        controls.update(
                f.product(),
                f.skuId(),
                0,
                policy(2, RemovalStrategy.FIFO, TrackingMode.NONE, false));
        var newer = stock(f, 5, Instant.parse("2026-09-02T00:00:00Z"), null, null);
        var older = stock(f, 5, Instant.parse("2026-09-01T00:00:00Z"), null, null);
        orders.placeOrder(
                new PlaceOrderCommand(
                        UUID.randomUUID(),
                        DemoData.CUSTOMER_ID,
                        List.of(
                                new PlaceOrderCommand.Line(
                                        new Sku(f.sku()), 2, Money.vnd(100), null))));
        assertThat(
                        jdbc.queryForObject(
                                "select reserved from inventory.stock_item where id=?",
                                Integer.class,
                                older))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select reserved from inventory.stock_item where id=?",
                                Integer.class,
                                newer))
                .isZero();
    }

    @Test
    void serialFlagIsEnforcedAtStockIngressAndCannotBeDisabledWithStock() {
        var f = fixture("DRAFT");
        var saved =
                controls.update(
                        f.product(),
                        f.skuId(),
                        0,
                        policy(null, RemovalStrategy.FIFO, TrackingMode.SERIAL, false));
        assertThatThrownBy(() -> stock(f, 2, Instant.parse("2026-09-01T00:00:00Z"), null, "SN-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        stock(f, 1, Instant.parse("2026-09-01T00:00:00Z"), null, "SN-1");
        assertThatThrownBy(
                        () ->
                                controls.update(
                                        f.product(),
                                        f.skuId(),
                                        saved.version(),
                                        policy(
                                                null,
                                                RemovalStrategy.FIFO,
                                                TrackingMode.NONE,
                                                false)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void expiredAvailableRowsDoNotInflateAtpOrThresholdStock() {
        var f = fixture("DRAFT");
        stock(f, 9, null, LocalDate.of(2000, 1, 1), null);
        assertThat(inventory.availableToPromise(new Sku(f.sku()))).isZero();
        assertThat(inventory.availableToPromise(List.of(new Sku(f.sku()))).get(new Sku(f.sku())))
                .isZero();
        assertThat(inventory.availableToPromise(new Sku(f.sku()), "HCM")).isZero();
        assertThat(inventoryControl.evaluate(new Sku(f.sku())).usableOnHand()).isZero();
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
    void negativeThresholdIsHttp400AndPublicCatalogReturnsAPage() throws Exception {
        var f = fixture("DRAFT");
        mvc.perform(
                        MockMvcRequestBuilders.put(
                                        "/api/v1/products/"
                                                + f.product()
                                                + "/skus/"
                                                + f.skuId()
                                                + "/inventory-control")
                                .contentType("application/json")
                                .content(
                                        """
{"version":0,"reorderPoint":-1,"removalStrategy":"FIFO","trackingMode":"NONE","expiryTracked":false}
"""))
                .andExpect(MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/catalog/products"))
                .andExpect(MockMvcResultMatchers.status().isOk());
    }

    @Test
    void databaseConstraintsRejectNegativeThresholdsEvenWithoutTheService() {
        var f = fixture("DRAFT");
        assertThatThrownBy(
                        () ->
                                write(
                                        "update inventory.inventory_items set reorder_point=-1"
                                                + " where sku=?",
                                        f.sku()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inventoryControlCannotChangeDuringApprovalOrAfterDiscontinuation() {
        for (String status : List.of("PENDING_APPROVAL", "DISCONTINUED")) {
            var f = fixture(status);
            assertThatThrownBy(
                            () ->
                                    controls.update(
                                            f.product(),
                                            f.skuId(),
                                            0,
                                            policy(
                                                    5,
                                                    RemovalStrategy.FEFO,
                                                    TrackingMode.NONE,
                                                    false)))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void expiryCannotBeDisabledAndIngressRejectsMissingOrExpiredDates() {
        var f = fixture("DRAFT");
        var saved =
                controls.update(
                        f.product(),
                        f.skuId(),
                        0,
                        policy(5, RemovalStrategy.FEFO, TrackingMode.LOT_SERIAL, true));
        Instant receipt = Instant.now().minusSeconds(60);
        LocalDate today = BusinessCalendar.date(receipt);
        assertThatThrownBy(() -> stock(f, 1, receipt, null, "EXP-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> stock(f, 1, receipt, today.minusDays(1), "EXP-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        stock(f, 1, receipt, today.plusDays(10), "EXP-1");
        assertThatThrownBy(
                        () ->
                                controls.update(
                                        f.product(),
                                        f.skuId(),
                                        saved.version(),
                                        policy(
                                                5,
                                                RemovalStrategy.FEFO,
                                                TrackingMode.SERIAL,
                                                false)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void shorterShelfLifeCannotInvalidateExistingStock() {
        var f = fixture("DRAFT");
        var saved =
                controls.update(
                        f.product(),
                        f.skuId(),
                        0,
                        policy(5, RemovalStrategy.FEFO, TrackingMode.LOT_SERIAL, true));
        Instant receipt = Instant.now().minusSeconds(60);
        LocalDate receiptDay = BusinessCalendar.date(receipt);
        stock(f, 1, receipt, receiptDay.plusDays(10), "SHELF-1");

        assertThatThrownBy(
                        () ->
                                controls.update(
                                        f.product(),
                                        f.skuId(),
                                        saved.version(),
                                        new InventoryPolicy(
                                                5,
                                                null,
                                                RemovalStrategy.FEFO,
                                                TrackingMode.LOT_SERIAL,
                                                true,
                                                5)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT));
        assertThat(controls.get(f.product(), f.skuId()).policy().maxShelfLifeDays()).isEqualTo(365);
        assertThat(inventoryControl.policy(new Sku(f.sku())).maxShelfLifeDays()).isEqualTo(365);

        var allowed =
                controls.update(
                        f.product(),
                        f.skuId(),
                        saved.version(),
                        new InventoryPolicy(
                                5, null, RemovalStrategy.FEFO, TrackingMode.LOT_SERIAL, true, 10));
        assertThat(allowed.policy().maxShelfLifeDays()).isEqualTo(10);
    }

    @Test
    void shelfLifeLimitRequiresKnownReceiptAgeForExistingStock() {
        var f = fixture("DRAFT");
        Instant now = Instant.now();
        LocalDate today = BusinessCalendar.date(now);
        stock(f, 1, null, today.plusDays(10), "UNKNOWN-AGE");
        assertThatThrownBy(
                        () ->
                                controls.update(
                                        f.product(),
                                        f.skuId(),
                                        0,
                                        policy(
                                                5,
                                                RemovalStrategy.FEFO,
                                                TrackingMode.LOT_SERIAL,
                                                true)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT));
        assertThat(controls.get(f.product(), f.skuId()).version()).isZero();
    }

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
                            controls.update(
                                    f.product(),
                                    f.skuId(),
                                    0,
                                    policy(5, RemovalStrategy.FEFO, TrackingMode.NONE, false));
                            return true;
                        } catch (BusinessException e) {
                            assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT);
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
    void concurrentIngressIsSeenBeforePolicyChangeCanCommit() throws Exception {
        var f = fixture("DRAFT");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var inserted = new CountDownLatch(1);
            var commit = new CountDownLatch(1);
            var ingress =
                    executor.submit(
                            () ->
                                    transactions.execute(
                                            status -> {
                                                stock(
                                                        f,
                                                        2,
                                                        Instant.parse("2026-09-01T00:00:00Z"),
                                                        null,
                                                        null);
                                                inserted.countDown();
                                                try {
                                                    if (!commit.await(10, TimeUnit.SECONDS))
                                                        throw new IllegalStateException(
                                                                "Timed out");
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(e);
                                                }
                                                return true;
                                            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var changed =
                    executor.submit(
                            () -> {
                                try {
                                    controls.update(
                                            f.product(),
                                            f.skuId(),
                                            0,
                                            policy(
                                                    null,
                                                    RemovalStrategy.FIFO,
                                                    TrackingMode.SERIAL,
                                                    false));
                                    return true;
                                } catch (BusinessException e) {
                                    assertThat(e.errorCode())
                                            .isEqualTo(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
                                    return false;
                                }
                            });
            commit.countDown();
            assertThat(ingress.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(changed.get(15, TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test
    void canonicalVariantCreatesInventoryItemAndPolicyHasItsOwnVersion() {
        var f = fixture("DRAFT");
        write("update product.variants set status='DRAFT' where id=?", f.skuId());
        var initial = controls.get(f.product(), f.skuId());
        assertThat(initial.policy().trackingMode()).isEqualTo(TrackingMode.NONE);
        assertThat(initial.evaluation().reorderRequired()).isNull();
        var saved =
                controls.update(
                        f.product(),
                        f.skuId(),
                        initial.version(),
                        new InventoryPolicy(
                                100, 5, RemovalStrategy.FEFO, TrackingMode.LOT_SERIAL, true, 30));
        assertThat(saved.version()).isEqualTo(initial.version() + 1);
        assertThat(saved.evaluation().reorderRequired()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select safety_stock from inventory.inventory_items where sku=?",
                                Integer.class,
                                f.sku()))
                .isEqualTo(5);
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
        assertThatThrownBy(() -> checkoutCatalog.checkoutPrices(Set.of(f.sku())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(ErrorCode.PRODUCT_NOT_PURCHASABLE));
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
