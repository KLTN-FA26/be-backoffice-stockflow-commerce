package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.OpenInventoryItemsCommand;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.support.CustomerSkuRows;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Owner decisions Q1, Q5 of 2026-11-26 (V20270105000100): only a customer's SKU that an order named has an inventory
 * item, made by {@link InventoryService#openItems} in the order's transaction and packed as the SKU's catalogue variant;
 * a catalogue variant never has one. The cartons of a SKU are its variant's whether or not the SKU has an item: a
 * catalogue variant's code and a customer's SKU still being quoted read them through the catalogue
 * ({@code inventory :: spi} {@code SkuVariants}), and a carton is declared on a catalogue variant that no item names.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class InventoryItemAtOrderIntegrationTest {

    @Autowired InventoryService inventory;
    @Autowired UomConversionService units;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    /** A customer's SKU as the design approval leaves it: no order yet, so no item. */
    private CustomerSkuRows.Issued quoted;
    /** The code of the catalogue variant it was drawn on. */
    private String variantSku;

    @BeforeEach
    void setUp() {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        quoted = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 12, "EXEMPT", false);
        variantSku = jdbc.queryForObject("SELECT sku FROM product.variants WHERE id = ?", String.class,
                quoted.sourceVariantId());
    }

    private long itemsOf(String sku) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory.inventory_items WHERE sku = ?", Long.class, sku);
    }

    private static ErrorCode codeOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }

    private Map<String, UUID> open(String sku, UUID variant) {
        return tx.execute(s -> inventory.openItems(new OpenInventoryItemsCommand(
                List.of(new OpenInventoryItemsCommand.Item(new Sku(sku), variant)))));
    }

    private static UomConversionDraft pack(String code, int factor) {
        return new UomConversionDraft(code, factor, new BigDecimal("5"), new BigDecimal("50"), new BigDecimal("40"),
                new BigDecimal("30"), true);
    }

    // ---- the item, made at the order ----------------------------------------------------------------------------

    @Test
    @DisplayName("Q5: an approved design's SKU has no item until an order names it, nor does its catalogue variant")
    void noItemBeforeTheOrder() {
        assertThat(itemsOf(quoted.sku())).isZero();
        assertThat(itemsOf(variantSku)).isZero();
        assertThat(inventory.itemPolicy(new Sku(quoted.sku()))).isEmpty();
    }

    @Test
    @DisplayName("openItems makes the item packed as the SKU's variant, and a second call keeps the one item")
    void openItemsMakesTheItemOnce() {
        UUID first = open(quoted.sku(), quoted.sourceVariantId()).get(quoted.sku());
        assertThat(first).isNotNull();
        assertThat(jdbc.queryForObject("SELECT variant_id FROM inventory.inventory_items WHERE id = ?", UUID.class,
                first)).isEqualTo(quoted.sourceVariantId());

        assertThat(open(quoted.sku(), quoted.sourceVariantId())).containsEntry(quoted.sku(), first);
        assertThat(itemsOf(quoted.sku())).isOne();
    }

    @Test
    @DisplayName("openItems refuses with CONFLICT a SKU whose item records another variant, or one named twice apart")
    void anotherVariantIsAConflict() {
        open(quoted.sku(), quoted.sourceVariantId());
        UUID otherVariant = tx.execute(s -> ReferenceRows.product(entityManager)).variantId();

        assertThat(codeOf(() -> open(quoted.sku(), otherVariant))).isEqualTo(ErrorCode.CONFLICT);
        assertThat(codeOf(() -> tx.execute(s -> inventory.openItems(new OpenInventoryItemsCommand(List.of(
                new OpenInventoryItemsCommand.Item(new Sku(quoted.sku()), quoted.sourceVariantId()),
                new OpenInventoryItemsCommand.Item(new Sku(quoted.sku()), otherVariant)))))))
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT variant_id FROM inventory.inventory_items WHERE sku = ?", UUID.class,
                quoted.sku())).isEqualTo(quoted.sourceVariantId());
    }

    @Test
    @DisplayName("tg_inventory_items_customer_sku: no item for a catalogue variant, nor for a SKU packed as another variant")
    void theDatabaseRefusesAnItemThatIsNotACustomerSkus() {
        assertThatThrownBy(() -> open(variantSku, quoted.sourceVariantId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("it is packed as its SKU's catalogue variant");
        UUID otherVariant = tx.execute(s -> ReferenceRows.product(entityManager)).variantId();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "INSERT INTO inventory.inventory_items (id, sku, variant_id) VALUES (?, ?, ?)",
                Identifiers.newId(), quoted.sku(), otherVariant)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("it is packed as its SKU's catalogue variant");
        assertThat(itemsOf(variantSku)).isZero();
        assertThat(itemsOf(quoted.sku())).isZero();
    }

    @Test
    @DisplayName("openItems joins the caller's transaction: rolled back with it, nothing is left")
    void rolledBackWithTheOrder() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            inventory.openItems(new OpenInventoryItemsCommand(List.of(
                    new OpenInventoryItemsCommand.Item(new Sku(quoted.sku()), quoted.sourceVariantId()))));
            throw new IllegalStateException("the order failed to persist");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(itemsOf(quoted.sku())).isZero();
    }

    // ---- the cartons, read without an item ------------------------------------------------------------------------

    @Test
    @DisplayName("Q1: a catalogue variant's code and its customer's SKU with no item read the variant's carton")
    void cartonsReadWithoutAnItem() {
        Map<String, List<UomConversion>> read = units.conversionsOf(List.of(new Sku(variantSku),
                new Sku(quoted.sku()), new Sku("NO-SUCH-SKU-X")));

        assertThat(read).containsOnlyKeys(variantSku, quoted.sku());
        assertThat(read.get(variantSku)).extracting(UomConversion::id).containsExactly(quoted.uomConversionId());
        assertThat(read.get(quoted.sku())).extracting(UomConversion::id).containsExactly(quoted.uomConversionId());
        assertThat(read.get(quoted.sku())).extracting(UomConversion::sku).containsOnly(quoted.sku());
        assertThat(units.conversion(new Sku(variantSku), quoted.uomConversionId()).factor()).isEqualTo(12);
        assertThat(units.conversions(new Sku(quoted.sku()), 0, 20).items()).extracting(UomConversion::id)
                .containsExactly(quoted.uomConversionId());
        assertThat(codeOf(() -> units.conversions(new Sku("NO-SUCH-SKU-X"), 0, 20)))
                .isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("a carton is declared on a catalogue variant no item names, and its customer's SKU reads it at once")
    void aCartonIsDeclaredOnACatalogueVariant() {
        UomConversion old = units.conversion(new Sku(variantSku), quoted.uomConversionId());
        units.change(new Sku(variantSku), old.id(), old.version(), new UomConversionDraft(old.unitOfMeasure(),
                old.factor(), old.weightKg(), old.lengthCm(), old.widthCm(), old.heightCm(), false));

        UomConversion added = units.add(new Sku(variantSku), pack("CTN6", 6));

        assertThat(added.active()).isTrue();
        assertThat(jdbc.queryForObject("SELECT variant_id FROM inventory.uom_conversions WHERE id = ?", UUID.class,
                added.id())).isEqualTo(quoted.sourceVariantId());
        assertThat(UomConversion.cartonOf(units.conversionsOf(List.of(new Sku(quoted.sku()))).get(quoted.sku())))
                .map(UomConversion::id).contains(added.id());
    }

    @Test
    @DisplayName("two cartons declared at once on a catalogue variant with no item: one wins, the other is told to retire")
    void twoDeclarationsOnAVariantWithoutItemsAreSerialised() throws Exception {
        UomConversion old = units.conversion(new Sku(variantSku), quoted.uomConversionId());
        units.change(new Sku(variantSku), old.id(), old.version(), new UomConversionDraft(old.unitOfMeasure(),
                old.factor(), old.weightKg(), old.lengthCm(), old.widthCm(), old.heightCm(), false));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> outcomes = new ArrayList<>();
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<UomConversion>> calls = List.of(
                    () -> { go.await(); return units.add(new Sku(variantSku), pack("BOX", 10)); },
                    () -> { go.await(); return units.add(new Sku(variantSku), pack("CTN50", 50)); });
            List<Future<UomConversion>> running = calls.stream().map(pool::submit).toList();
            go.countDown();
            for (Future<UomConversion> f : running) {
                try {
                    outcomes.add(f.get());
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).filteredOn(UomConversion.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(BusinessException.class::isInstance).extracting(o -> ((BusinessException) o)
                .errorCode()).containsExactly(ErrorCode.UOM_CARTON_ALREADY_DECLARED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.uom_conversions WHERE variant_id = ? AND active",
                Long.class, quoted.sourceVariantId())).isOne();
    }
}
