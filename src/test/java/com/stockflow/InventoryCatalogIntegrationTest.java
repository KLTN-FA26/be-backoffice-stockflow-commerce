package com.stockflow;

import com.stockflow.catalog.internal.service.CatalogCommerceService;
import com.stockflow.catalog.internal.service.CatalogProjectionService;

import com.stockflow.catalog.internal.domain.SellingPrice;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.*;
import com.stockflow.product.internal.service.SkuInventoryControlService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@IntegrationTest
@Import(PostgresContainer.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class InventoryCatalogIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SkuInventoryControlService controls;
    @Autowired InventoryControlService inventoryControl;
    @Autowired InventoryService inventory;
    @Autowired CatalogCommerceService catalog;
    @Autowired CatalogProjectionService projection;
    @Autowired TransactionTemplate transactions;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.stockflow.order.api.OrderService orders;
    @Autowired com.stockflow.catalog.api.CatalogService checkoutCatalog;
    record Fixture(UUID product,UUID skuId,String sku) {}
    int write(String sql,Object... args) {
        return transactions.execute(status -> jdbc.update(sql,args));
    }
    Fixture fixture(String status) {
        UUID product=UUID.randomUUID(),variant=UUID.randomUUID(),sku=UUID.randomUUID(),category=UUID.randomUUID();
        String code=("TEST-"+sku.toString().substring(0,12)).toUpperCase(java.util.Locale.ROOT);
        write("insert into product.category(id,code,name,created_at) values (?,?,?,now())",category,code,code);
        write("""
                insert into product.product(id,code,name,name_en,brand,tax_class,category_id,status,created_at)
                values (?,?,?,'Furniture','Test','STANDARD',?,?,now())
                """,product,code,"Test furniture",category,status);
        write("insert into product.variant(id,product_id,name,created_at) values (?,?,'Standard',now())",variant,product);
        write("insert into product.sku(id,variant_id,code,unit_of_measure,created_at) values (?,?,?,'EA',now())",sku,variant,code);
        return new Fixture(product,sku,code);
    }
    void gallery(Fixture f) {
        write("""
                insert into product.product_gallery(id,published_items,approved_by,created_at)
                values (?,cast(? as jsonb),?,now())
                """,f.product(),"[{\"imageId\":\""+UUID.randomUUID()+"\",\"caption\":\"Cover\"}]",UUID.randomUUID());
    }
    UUID stock(Fixture f,int quantity,Instant receipt,LocalDate expiry,String serial) {
        UUID id=UUID.randomUUID();
        write("""
                insert into inventory.stock_item(id,sku,location_code,on_hand,reserved,status,received_at,expiry_date,serial_number,created_at)
                values (?,?,'HCM-A-01',?,0,'AVAILABLE',?,?,?,now())
                """,id,f.sku(),quantity,receipt==null?null:java.sql.Timestamp.from(receipt),expiry,serial);
        return id;
    }
    InventoryPolicy policy(Integer threshold,RemovalStrategy strategy,TrackingMode tracking,boolean expiry) {
        return new InventoryPolicy(threshold,null,strategy,tracking,expiry,expiry?365:null);
    }

    @Test void batchAvailabilityExcludesHoldsBlockedAndExpiredStock() {
        var a=fixture("DRAFT"); var b=fixture("DRAFT");
        UUID held=stock(a,6,null,null,null);
        write("update inventory.stock_item set reserved=2 where id=?",held);
        UUID blocked=stock(b,10,null,null,null);
        write("update inventory.stock_item set status='QUARANTINE' where id=?",blocked);
        stock(a,20,Instant.parse("2026-01-01T00:00:00Z"),LocalDate.of(2000,1,1),null);
        var available=inventory.availableQuantities(java.util.Set.of(a.sku(),b.sku(),"MISSING-SKU"));
        assertThat(available.get(a.sku())).isEqualTo(4L);
        assertThat(available.getOrDefault(b.sku(),0L)).isZero();
        assertThat(available.getOrDefault("MISSING-SKU",0L)).isZero();
    }

    @Test void basePriceDatabaseRejectsForeignCurrencyAndFractionalVnd() {
        var f=fixture("DRAFT");
        var listing=catalog.edit(f.product(),0,"money-"+f.sku(),null,null);
        catalog.price(f.product(),f.sku(),listing.revision(),new SellingPrice(new BigDecimal("100"),"VND"),"test");
        assertThatThrownBy(() -> write("update catalog.pricing_rule set currency='USD' where name=?","BASE:"+f.sku()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> write("update catalog.pricing_rule set price=100.49 where name=?","BASE:"+f.sku()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void guestCheckoutUsesServerPriceAndReplayKeepsTheAgreedSnapshot() {
        var f=fixture("APPROVED"); gallery(f); stock(f,10,null,null,null);
        var listing=catalog.edit(f.product(),0,"checkout-"+f.sku(),null,null);
        catalog.price(f.product(),f.sku(),listing.revision(),new SellingPrice(new BigDecimal("100"),"VND"),"test");
        catalog.publish(f.product()); projection.project(f.product());
        var address=new com.stockflow.order.api.PlaceGuestOrderCommand.Address("Minh","0901234567","12 Nguyen Hue",
                null,"26734","Ben Nghe","79","Ho Chi Minh City","VN",null);
        var tampered=new com.stockflow.order.api.PlaceGuestOrderCommand(UUID.randomUUID(),"minh@example.com",address,address,
                java.util.List.of(new com.stockflow.order.api.PlaceGuestOrderCommand.Line(new com.stockflow.common.domain.Sku(f.sku()),1,
                        com.stockflow.common.domain.Money.vnd(1))));
        assertThatThrownBy(() -> orders.placeGuestOrder(tampered)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CHECKOUT_PRICE_CHANGED));
        assertThat(inventory.availableToPromise(new Sku(f.sku()))).isEqualTo(10);
        var accepted=new com.stockflow.order.api.PlaceGuestOrderCommand(UUID.randomUUID(),"minh@example.com",address,address,
                java.util.List.of(new com.stockflow.order.api.PlaceGuestOrderCommand.Line(new Sku(f.sku()),1,
                        com.stockflow.common.domain.Money.vnd(100))));
        var order=orders.placeGuestOrder(accepted);
        catalog.price(f.product(),f.sku(),catalog.get(f.product()).revision(),new SellingPrice(new BigDecimal("200"),"VND"),"test");
        assertThat(orders.placeGuestOrder(accepted).orderId()).isEqualTo(order.orderId());
        assertThat(orders.placeGuestOrder(accepted).total()).isEqualTo(com.stockflow.common.domain.Money.vnd(100));
        assertThat(inventory.availableToPromise(new Sku(f.sku()))).isEqualTo(9);
        catalog.unpublish(f.product());
        assertThatThrownBy(() -> checkoutCatalog.checkoutPrices(java.util.Set.of(f.sku())))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CATALOG_NOT_READY));
    }
    @Test void thresholdsAreNullableZeroIsMeaningfulAndHigherThanStockIsAllowed() {
        var f=fixture("DRAFT");
        assertThat(controls.get(f.product(),f.skuId()).policy().reorderPoint()).isNull();
        var saved=controls.update(f.product(),f.skuId(),0,policy(10,RemovalStrategy.FEFO,TrackingMode.NONE,false));
        assertThat(saved.evaluation().reorderRequired()).isTrue();
        assertThat(inventoryControl.policy(new Sku(f.sku())).reorderPoint()).isEqualTo(10);
        var cleared=controls.update(f.product(),f.skuId(),saved.version(),policy(null,RemovalStrategy.FEFO,TrackingMode.NONE,false));
        assertThat(cleared.evaluation().reorderRequired()).isNull();
        var zero=controls.update(f.product(),f.skuId(),cleared.version(),policy(0,RemovalStrategy.FEFO,TrackingMode.NONE,false));
        assertThat(zero.evaluation().reorderRequired()).isTrue();
    }
    @Test void skuMustBelongToProductAndStaleVersionCannotOverwrite() {
        var a=fixture("DRAFT"); var b=fixture("DRAFT");
        assertThatThrownBy(() -> controls.get(a.product(),b.skuId())).isInstanceOf(BusinessException.class);
        controls.update(a.product(),a.skuId(),0,policy(5,RemovalStrategy.FEFO,TrackingMode.NONE,false));
        assertThatThrownBy(() -> controls.update(a.product(),a.skuId(),0,policy(6,RemovalStrategy.FEFO,TrackingMode.NONE,false)))
                .isInstanceOf(BusinessException.class);
    }
    @Test void legacyUnknownReceiptBlocksFifoWithoutChangingTheSku() {
        var f=fixture("DRAFT"); stock(f,10,null,null,null);
        assertThatThrownBy(() -> controls.update(f.product(),f.skuId(),0,policy(3,RemovalStrategy.FIFO,TrackingMode.NONE,false)))
                .isInstanceOf(BusinessException.class);
        assertThat(controls.get(f.product(),f.skuId()).policy().reorderPoint()).isNull();
    }
    @Test void fifoActuallyReservesTheOlderReceiptLayer() {
        var f=fixture("APPROVED");
        controls.update(f.product(),f.skuId(),0,policy(2,RemovalStrategy.FIFO,TrackingMode.NONE,false));
        var newer=stock(f,5,Instant.parse("2026-09-02T00:00:00Z"),null,null);
        var older=stock(f,5,Instant.parse("2026-09-01T00:00:00Z"),null,null);
        var result=inventory.reserve(new ReserveStockCommand(UUID.randomUUID(),new Sku(f.sku()),2,UUID.randomUUID()));
        assertThat(result.reservations().getFirst().stockItemId()).isEqualTo(older);
        assertThat(jdbc.queryForObject("select reserved from inventory.stock_item where id=?",Integer.class,newer)).isZero();
    }
    @Test void serialFlagIsEnforcedAtStockIngressAndCannotBeDisabledWithStock() {
        var f=fixture("DRAFT");
        var saved=controls.update(f.product(),f.skuId(),0,policy(null,RemovalStrategy.FIFO,TrackingMode.SERIAL,false));
        assertThatThrownBy(() -> stock(f,2,Instant.parse("2026-09-01T00:00:00Z"),null,"SN-1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        stock(f,1,Instant.parse("2026-09-01T00:00:00Z"),null,"SN-1");
        assertThatThrownBy(() -> controls.update(f.product(),f.skuId(),saved.version(),policy(null,RemovalStrategy.FIFO,TrackingMode.NONE,false)))
                .isInstanceOf(BusinessException.class);
    }
    @Test void expiredAvailableRowsDoNotInflateAtpOrThresholdStock() {
        var f=fixture("DRAFT"); stock(f,9,null,LocalDate.of(2000,1,1),null);
        assertThat(inventory.availableToPromise(new Sku(f.sku()))).isZero();
        assertThat(inventory.availableToPromise(new Sku(f.sku()),"HCM")).isZero();
        assertThat(inventoryControl.evaluate(new Sku(f.sku())).usableOnHand()).isZero();
    }
    @Test void draftSeoIsPrivateAndPublicationRequiresPriceAndApprovedGallery() {
        var f=fixture("DRAFT");
        catalog.edit(f.product(),0,"draft-"+f.sku(), "Private SEO",null);
        projection.project(f.product());
        assertThatThrownBy(() -> catalog.detail("draft-"+f.sku().toLowerCase())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> catalog.publish(f.product())).isInstanceOf(BusinessException.class);
        write("update product.product set status='APPROVED' where id=?",f.product());
        assertThatThrownBy(() -> catalog.publish(f.product())).isInstanceOf(BusinessException.class);
        var listing=catalog.get(f.product());
        catalog.price(f.product(),f.sku(),listing.revision(),new SellingPrice(new BigDecimal("100.00"),"VND"),"test");
        assertThat(catalog.price(f.product(),f.sku()).basePrice().amount()).isEqualByComparingTo("100");
        assertThatThrownBy(() -> catalog.publish(f.product())).isInstanceOf(BusinessException.class);
    }
    @Test void publishProjectsAllSkusAndUnpublishHidesImmediatelyEvenBeforeProjection() {
        var f=fixture("APPROVED"); gallery(f);
        var listing=catalog.edit(f.product(),0,"public-"+f.sku(),"SEO",null);
        catalog.price(f.product(),f.sku(),listing.revision(),new SellingPrice(new BigDecimal("100.00"),"VND"),"test");
        catalog.publish(f.product()); projection.project(f.product());
        String slug=catalog.get(f.product()).slug();
        assertThat(catalog.detail(slug).variants()).hasSize(1);
        catalog.unpublish(f.product());
        assertThatThrownBy(() -> catalog.detail(slug)).isInstanceOf(BusinessException.class);
        projection.project(f.product()); // A late event must not resurrect the product.
        assertThat(jdbc.queryForObject("select count(*) from catalog.catalog_entry where product_id=? and published",Integer.class,f.product())).isZero();
    }
    @Test void duplicateNormalizedSlugIsConflictAndPublishedSlugCannotChange() {
        var a=fixture("DRAFT"); var b=fixture("DRAFT");
        String slug="unique-"+a.sku();
        catalog.edit(a.product(),0,slug,null,null);
        assertThatThrownBy(() -> catalog.edit(b.product(),0,slug.toLowerCase(),null,null))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.CATALOG_SLUG_EXISTS));
    }
    @Test void negativeThresholdIsHttp400AndPublicCatalogReturnsAPage() throws Exception {
        var f=fixture("DRAFT");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/products/"+f.product()+"/skus/"+f.skuId()+"/inventory-control")
                .contentType("application/json").content("""
                    {"version":0,"reorderPoint":-1,"removalStrategy":"FIFO","trackingMode":"NONE","expiryTracked":false}
                    """)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/public/catalog/products"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
    @Test void databaseConstraintsRejectNegativeThresholdsEvenWithoutTheService() {
        var f=fixture("DRAFT");
        assertThatThrownBy(() -> write("update product.sku set reorder_point=-1 where id=?",f.skuId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void inventoryControlCannotChangeDuringApprovalOrAfterDiscontinuation() {
        for (String status:java.util.List.of("PENDING_APPROVAL","DISCONTINUED")) {
            var f=fixture(status);
            assertThatThrownBy(() -> controls.update(f.product(),f.skuId(),0,
                    policy(5,RemovalStrategy.FEFO,TrackingMode.NONE,false))).isInstanceOf(BusinessException.class);
        }
    }
    @Test void expiryCannotBeDisabledAndIngressRejectsMissingOrExpiredDates() {
        var f=fixture("DRAFT");
        var saved=controls.update(f.product(),f.skuId(),0,policy(5,RemovalStrategy.FEFO,TrackingMode.SERIAL,true));
        Instant receipt=Instant.now().minusSeconds(60);
        LocalDate today=com.stockflow.common.domain.BusinessCalendar.date(receipt);
        assertThatThrownBy(() -> stock(f,1,receipt,null,"EXP-1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> stock(f,1,receipt,today.minusDays(1),"EXP-1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        stock(f,1,receipt,today.plusDays(10),"EXP-1");
        assertThatThrownBy(() -> controls.update(f.product(),f.skuId(),saved.version(),
                policy(5,RemovalStrategy.FEFO,TrackingMode.SERIAL,false))).isInstanceOf(BusinessException.class);
    }
    @Test void duplicatePublicationDoesNotIncrementRevisionAndOlderEventUsesLatestSeo() {
        var f=fixture("APPROVED"); gallery(f);
        var listing=catalog.edit(f.product(),0,"repeat-"+f.sku(),"Old SEO",null);
        catalog.price(f.product(),f.sku(),listing.revision(),new SellingPrice(new BigDecimal("100"),"VND"),"test");
        catalog.publish(f.product());
        long revision=catalog.get(f.product()).revision();
        catalog.publish(f.product());
        assertThat(catalog.get(f.product()).revision()).isEqualTo(revision);
        catalog.edit(f.product(),revision,catalog.get(f.product()).slug(),"New SEO",null);
        projection.project(f.product()); projection.project(f.product());
        assertThat(catalog.detail(catalog.get(f.product()).slug()).seoTitle()).isEqualTo("New SEO");
        assertThat(jdbc.queryForObject("select count(*) from catalog.catalog_entry where product_id=?",Integer.class,f.product())).isEqualTo(1);
    }
    @Test void equalPriorityPricesConflictAndASecondPublishCannotBypassIt() {
        var f=fixture("APPROVED"); gallery(f);
        var listing=catalog.edit(f.product(),0,"price-"+f.sku(),null,null);
        catalog.price(f.product(),f.sku(),listing.revision(),new SellingPrice(new BigDecimal("100"),"VND"),"test");
        write("""
                insert into catalog.pricing_rule(id,name,sku,price,currency,priority,active,created_at)
                values (?,'Conflicting test rule',?,200,'VND',0,true,now())
                """,UUID.randomUUID(),f.sku());
        assertThatThrownBy(() -> catalog.publish(f.product())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CATALOG_PRICE_AMBIGUOUS));
        assertThat(jdbc.queryForObject("select status from product.product where id=?",String.class,f.product())).isEqualTo("APPROVED");
    }
    @Test void twoWritersWithTheSameSkuVersionCannotBothWin() throws Exception {
        var f=fixture("DRAFT");
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> action=() -> {
                start.await();
                try { controls.update(f.product(),f.skuId(),0,policy(5,RemovalStrategy.FEFO,TrackingMode.NONE,false)); return true; }
                catch (BusinessException e) { assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT); return false; }
            };
            var first=executor.submit(action); var second=executor.submit(action); start.countDown();
            assertThat(java.util.List.of(first.get(15,java.util.concurrent.TimeUnit.SECONDS),
                    second.get(15,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void simultaneousSlugClaimHasOneWinnerAndOneBusinessConflict() throws Exception {
        var a=fixture("DRAFT"); var b=fixture("DRAFT"); String slug="race-"+a.sku();
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var futures=java.util.List.of(a,b).stream().map(f -> executor.submit(() -> {
                start.await();
                try { catalog.edit(f.product(),0,slug,null,null); return true; }
                catch (BusinessException e) { assertThat(e.errorCode()).isEqualTo(ErrorCode.CATALOG_SLUG_EXISTS); return false; }
            })).toList();
            start.countDown();
            assertThat(java.util.List.of(futures.get(0).get(15,java.util.concurrent.TimeUnit.SECONDS),
                    futures.get(1).get(15,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void concurrentIngressIsSeenBeforePolicyChangeCanCommit() throws Exception {
        var f=fixture("DRAFT");
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var inserted=new java.util.concurrent.CountDownLatch(1);
            var commit=new java.util.concurrent.CountDownLatch(1);
            var ingress=executor.submit(() -> transactions.execute(status -> {
                stock(f,2,Instant.parse("2026-09-01T00:00:00Z"),null,null);
                inserted.countDown();
                try { if (!commit.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return true;
            }));
            assertThat(inserted.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var changed=executor.submit(() -> {
                try { controls.update(f.product(),f.skuId(),0,policy(null,RemovalStrategy.FIFO,TrackingMode.SERIAL,false)); return true; }
                catch (BusinessException e) { assertThat(e.errorCode()).isEqualTo(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT); return false; }
            });
            commit.countDown();
            assertThat(ingress.get(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(changed.get(15,java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        }
    }
}
