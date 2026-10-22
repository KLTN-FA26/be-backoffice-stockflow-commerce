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

    /** The item's own unit: 10 x 8 x 6 cm, 0.5 kg; box 12 x 9 x 7 cm, 0.7 kg. */
    private void describeItem() {
        tx.executeWithoutResult(s -> jdbc.update("""
                update inventory.inventory_items set weight_kg = 0.5, length_cm = 10, width_cm = 8, height_cm = 6,
                    package_weight_kg = 0.7, package_length_cm = 12, package_width_cm = 9, package_height_cm = 7
                where id = ?""", itemId));
    }

    private static UomConversionDraft pack(String code, int factor, String weight, boolean shipping) {
        return new UomConversionDraft(code, factor, new BigDecimal(weight), new BigDecimal("50"),
                new BigDecimal("40"), new BigDecimal("30"), false, true, shipping);
    }

    private List<String> flagged() {
        return jdbc.queryForList("select unit_of_measure from inventory.uom_conversions"
                + " where inventory_item_id = ? and default_shipping order by unit_of_measure", String.class, itemId);
    }

    private UomConversion base() {
        return units.baseUnit(sku);
    }

    // ---- the flag ----------------------------------------------------------------------------------------------

    @Test
    void newItemsHaveNoDefaultShippingUnit() {
        assertThat(flagged()).isEmpty();
        assertThat(units.conversions(sku, 0, 20).items()).extracting(UomConversion::defaultShipping)
                .containsOnly(false);
    }

    @Test
    void markingAUnitUnmarksThePreviousOneInTheSameCall() {
        UomConversion box = units.add(sku, pack("BOX", 10, "5", true));
        assertThat(box.defaultShipping()).isTrue();
        UomConversion carton = units.add(sku, pack("CARTON", 50, "26", true));
        assertThat(carton.defaultShipping()).isTrue();
        assertThat(flagged()).containsExactly("CARTON");
        assertThat(units.conversion(sku, box.id()).defaultShipping()).isFalse();
        assertThat(units.conversion(sku, box.id()).version()).isGreaterThan(box.version());

        UomConversion boxAgain = units.conversion(sku, box.id());
        UomConversion moved = units.change(sku, box.id(), boxAgain.version(), pack("BOX", 10, "5", true));
        assertThat(moved.defaultShipping()).isTrue();
        assertThat(flagged()).containsExactly("BOX");
    }

    @Test
    void unmarkingLeavesTheItemWithoutOneAndKeepsThePurchaseMark() {
        UomConversion box = units.add(sku, pack("BOX", 10, "5", true));
        units.change(sku, box.id(), box.version(), pack("BOX", 10, "5", false));
        assertThat(flagged()).isEmpty();
        assertThat(base().defaultPurchase()).isTrue();
    }

    @Test
    void theBaseUnitCanBeTheDefaultShippingUnit() {
        UomConversion box = units.add(sku, pack("BOX", 10, "5", true));
        UomConversion base = base();
        UomConversion marked = units.change(sku, base.id(), base.version(),
                new UomConversionDraft("IGNORED", 1, null, null, null, null, true, true, true));
        assertThat(marked.defaultShipping()).isTrue();
        assertThat(flagged()).containsExactly("EACH");
        assertThat(units.conversion(sku, box.id()).defaultShipping()).isFalse();
    }

    @Test
    void aRetiredUnitCannotCarryTheMark() {
        UomConversion box = units.add(sku, pack("BOX", 10, "5", false));
        UomConversionDraft retiredAndMarked = new UomConversionDraft("BOX", 10, new BigDecimal("5"),
                new BigDecimal("50"), new BigDecimal("40"), new BigDecimal("30"), false, false, true);
        assertThatThrownBy(() -> units.change(sku, box.id(), box.version(), retiredAndMarked))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).extracting(f -> f.field()).containsExactly("defaultShipping");
                });
        assertThat(flagged()).isEmpty();
    }

    @Test
    void theDatabaseRefusesASecondMarkAndAMarkedRetiredUnit() {
        UomConversion box = units.add(sku, pack("BOX", 10, "5", true));
        UomConversion carton = units.add(sku, pack("CARTON", 50, "26", false));
        assertThatThrownBy(() -> jdbc.update(
                "update inventory.uom_conversions set default_shipping = true where id = ?", carton.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_uom_conversions_default_shipping");
        assertThatThrownBy(() -> jdbc.update(
                "update inventory.uom_conversions set active = false where id = ?", box.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_uom_conversions_default_shipping");
        assertThat(flagged()).containsExactly("BOX");
    }

    @Test
    void anotherItemMayHaveItsOwnMark() {
        units.add(sku, pack("BOX", 10, "5", true));
        String other = "PK-O" + UUID.randomUUID().toString().substring(0, 7).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, other,
                    "Other", other.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature)"
                    + " values (?,?,?,'Other','')", Identifiers.newId(), product, other);
        });
        assertThat(units.add(new Sku(other), pack("BOX", 10, "5", true)).defaultShipping()).isTrue();
        assertThat(flagged()).containsExactly("BOX");
    }

    @Test
    void twoPeopleMarkingAtOnceLeaveExactlyOneMark() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<UomConversion>> calls = List.of(
                    () -> { go.await(); return units.add(sku, pack("BOX", 10, "5", true)); },
                    () -> { go.await(); return units.add(sku, pack("CARTON", 50, "26", true)); });
            List<Future<UomConversion>> running = calls.stream().map(pool::submit).toList();
            go.countDown();
            for (Future<UomConversion> f : running) {
                f.get();     // neither call may surface the unique index as an error
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(flagged()).hasSize(1);
    }

    // ---- the resolver on real rows ---------------------------------------------------------------------------------

    @Test
    void anItemWithNoDataIsMissingNotDefaulted() {
        PackagingSpec spec = resolver.resolve(sku, 5);
        assertThat(spec.status()).isEqualTo(PackagingSpec.Status.MISSING);
        assertThat(spec.missing().fields()).containsExactly("lengthCm", "widthCm", "heightCm", "weightKg");
        assertThat(spec.inventoryItemId()).isEqualTo(itemId);
        assertThat(spec.sku()).isEqualTo(sku.code());
    }

    @Test
    void theChainFollowsTheDataOfTheItem() {
        // Only the item: its box.
        describeItem();
        PackagingSpec box = resolver.resolve(sku, 3);
        assertThat(box.level()).isEqualTo(RuleLevel.ITEM_BOX);
        assertThat(box.carton().lengthMm()).isEqualTo(120);
        assertThat(box.fullCartons()).isEqualTo(3);

        // A packing unit with its size: the largest one.
        units.add(sku, pack("BOX", 10, "5.5", false));
        UomConversion pallet = units.add(sku, pack("PALLET", 100, "55", false));
        PackagingSpec largest = resolver.resolve(sku, 250);
        assertThat(largest.level()).isEqualTo(RuleLevel.LARGEST_PACKING_UNIT);
        assertThat(largest.unitOfMeasure()).isEqualTo("PALLET");
        assertThat(largest.fullCartons()).isEqualTo(2);
        assertThat(largest.partialCarton().units()).isEqualTo(50);
        assertThat(largest.partialCarton().weightKg()).isEqualByComparingTo("30.000");   // 50 * 0.5 + (55 - 100 * 0.5)

        // The mark beats the largest unit.
        units.change(sku, pallet.id(), units.conversion(sku, pallet.id()).version(), pack("PALLET", 100, "55", false));
        UomConversion box10 = units.conversions(sku, 0, 20).items().stream()
                .filter(u -> u.unitOfMeasure().equals("BOX")).findFirst().orElseThrow();
        units.change(sku, box10.id(), box10.version(), pack("BOX", 10, "5.5", true));
        PackagingSpec marked = resolver.resolve(sku, 250);
        assertThat(marked.level()).isEqualTo(RuleLevel.DEFAULT_SHIPPING_UNIT);
        assertThat(marked.unitOfMeasure()).isEqualTo("BOX");
        assertThat(marked.fullCartons()).isEqualTo(25);
    }

    @Test
    void sameInputSameAnswerAndByItemIdAgrees() {
        describeItem();
        units.add(sku, pack("BOX", 10, "5.5", true));
        PackagingSpec first = resolver.resolve(sku, 37);
        assertThat(resolver.resolve(sku, 37)).isEqualTo(first);
        assertThat(resolver.resolve(itemId, 37)).isEqualTo(first);
    }

    @Test
    void theBatchAnswersInRequestOrderAndRepeatsASku() {
        describeItem();
        units.add(sku, pack("BOX", 10, "5.5", true));
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
