package com.stockflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.PackagingRequest;
import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.PackagingSpecResolver;
import com.stockflow.inventory.api.RuleLevel;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The default shipping flag and the packaging-spec resolver on a real database (SCRUM-287 PR P): at most one flag per
 * item, atomically moved by the service and guarded by the partial unique index; the resolver reading real rows.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class PackagingSpecResolverIntegrationTest {

    @Autowired UomConversionService units;
    @Autowired PackagingSpecResolver resolver;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private Sku sku;
    private UUID itemId;

    @BeforeEach
    void item() {
        String code = "PK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        sku = new Sku(code);
        itemId = tx.execute(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, code,
                    "Packaging fixture", code.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature)"
                    + " values (?,?,?,'Packaging fixture','')", Identifiers.newId(), product, code);
            return jdbc.queryForObject("select id from inventory.inventory_items where sku=?", UUID.class, code);
        });
    }

    private static UomConversionDraft pack(String code, int factor, String weight) {
        return new UomConversionDraft(code, factor, new BigDecimal(weight), new BigDecimal("50"),
                new BigDecimal("40"), new BigDecimal("30"), true);
    }

    private List<String> active() {
        return jdbc.queryForList("select unit_of_measure from inventory.uom_conversions"
                + " where inventory_item_id = ? and active order by unit_of_measure", String.class, itemId);
    }

    // ---- the one active carton (01 BR-03) ------------------------------------------------------------------------

    @Test
    void newItemsHaveNoUnit() {
        assertThat(active()).isEmpty();
        assertThat(units.conversions(sku, 0, 20).items()).as("no packing unit until a carton is declared").isEmpty();
    }

    @Test
    void theOneCartonIsTheItemsOnlyUnit() {
        // No base unit row (V20261226000200), and no shipping mark to choose a unit by (V20261229000100).
        units.add(sku, pack("BOX", 10, "5"));
        assertThat(active()).containsExactly("BOX");
        assertThat(units.conversions(sku, 0, 20).items()).extracting(UomConversion::unitOfMeasure)
                .as("the item's own unit is not listed").containsExactly("BOX");
    }

    @Test
    void anotherItemHasItsOwnCarton() {
        units.add(sku, pack("BOX", 10, "5"));
        String other = "PK-O" + UUID.randomUUID().toString().substring(0, 7).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, other,
                    "Other", other.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature)"
                    + " values (?,?,?,'Other','')", Identifiers.newId(), product, other);
        });
        assertThat(units.add(new Sku(other), pack("BOX", 10, "5")).active()).isTrue();
        assertThat(active()).containsExactly("BOX");
    }

    @Test
    void theDatabaseRefusesASecondActiveCarton() {
        units.add(sku, pack("BOX", 10, "5"));
        assertThatThrownBy(() -> jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, "
                + "unit_of_measure, factor, active) values (?, ?, 'CARTON', 50, true)", Identifiers.newId(), itemId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_uom_conversions_one_carton");
        assertThat(active()).containsExactly("BOX");
    }

    @Test
    void twoPeopleDeclaringACartonAtOnceLeaveExactlyOneActive() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> outcomes = new java.util.ArrayList<>();
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<UomConversion>> calls = List.of(
                    () -> { go.await(); return units.add(sku, pack("BOX", 10, "5")); },
                    () -> { go.await(); return units.add(sku, pack("CARTON", 50, "26")); });
            List<Future<UomConversion>> running = calls.stream().map(pool::submit).toList();
            go.countDown();
            for (Future<UomConversion> f : running) {
                try {
                    outcomes.add(f.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        // 01 BR-03 under the SKU lock: the second is told to retire the first, never the unique index's bare 409.
        assertThat(outcomes).filteredOn(o -> o instanceof UomConversion).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof BusinessException).singleElement()
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.UOM_CARTON_ALREADY_DECLARED));
        assertThat(active()).hasSize(1);
    }

    // ---- the resolver on real rows ---------------------------------------------------------------------------------

    @Test
    void anItemWithNoCartonIsMissingNotDefaulted() {
        PackagingSpec spec = resolver.resolve(sku, 5);
        assertThat(spec.status()).isEqualTo(PackagingSpec.Status.MISSING);
        assertThat(spec.missing().fields()).isEmpty();
        assertThat(spec.inventoryItemId()).isEqualTo(itemId);
        assertThat(spec.sku()).isEqualTo(sku.code());
    }

    @Test
    void theChainFollowsTheCartonOfTheItem() {
        // The item's one active carton, with its size: level 1.
        UomConversion pallet = units.add(sku, pack("PALLET", 100, "80"));
        PackagingSpec spec = resolver.resolve(sku, 250);
        assertThat(spec.level()).isEqualTo(RuleLevel.DEFAULT_SHIPPING_UNIT);
        assertThat(spec.unitOfMeasure()).isEqualTo("PALLET");
        assertThat(spec.fullCartons()).isEqualTo(2);
        assertThat(spec.partialCarton().units()).isEqualTo(50);
        assertThat(spec.partialCarton().weightKg()).isEqualByComparingTo("40.000");   // 80 * 50 / 100

        // Retired, it is no carton: the item has none.
        units.change(sku, pallet.id(), units.conversion(sku, pallet.id()).version(), new UomConversionDraft("PALLET",
                100, new BigDecimal("80"), new BigDecimal("50"), new BigDecimal("40"), new BigDecimal("30"), false));
        assertThat(resolver.resolve(sku, 250).status()).isEqualTo(PackagingSpec.Status.MISSING);
    }

    @Test
    void sameInputSameAnswerAndByItemIdAgrees() {
        units.add(sku, pack("BOX", 10, "5.5"));
        PackagingSpec first = resolver.resolve(sku, 37);
        assertThat(resolver.resolve(sku, 37)).isEqualTo(first);
        assertThat(resolver.resolve(itemId, 37)).isEqualTo(first);
    }

    @Test
    void theBatchAnswersInRequestOrderAndRepeatsASku() {
        units.add(sku, pack("BOX", 10, "5.5"));
        List<PackagingSpec> specs = resolver.resolveAll(List.of(new PackagingRequest(sku, 25),
                new PackagingRequest(sku, 4)));
        assertThat(specs).extracting(PackagingSpec::quantity).containsExactly(25, 4);
        assertThat(specs.get(0).fullCartons()).isEqualTo(2);
        assertThat(specs.get(0).partialCarton().units()).isEqualTo(5);
        assertThat(specs.get(1).fullCartons()).isZero();
        assertThat(resolver.resolveAll(List.of())).isEmpty();
    }

    @Test
    void anUnknownSkuAndANonPositiveQuantityAreRefused() {
        assertThatThrownBy(() -> resolver.resolve(new Sku("NO-SUCH-SKU"), 1)).isInstanceOfSatisfying(
                BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND));
        assertThatThrownBy(() -> resolver.resolve(UUID.randomUUID(), 1)).isInstanceOfSatisfying(
                BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND));
        assertThatThrownBy(() -> resolver.resolve(sku, 0)).isInstanceOfSatisfying(
                BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> resolver.resolveAll(List.of(new PackagingRequest(sku, 1),
                new PackagingRequest(new Sku("NO-SUCH-SKU"), 1)))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND));
    }
}
