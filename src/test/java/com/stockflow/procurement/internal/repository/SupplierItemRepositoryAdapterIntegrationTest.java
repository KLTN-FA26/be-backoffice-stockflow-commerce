package com.stockflow.procurement.internal.repository;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.SaveSupplierCommand;
import com.stockflow.procurement.api.SupplierService;
import com.stockflow.procurement.internal.domain.CatalogSupplier;
import com.stockflow.procurement.internal.domain.SupplierItem;
import com.stockflow.procurement.internal.domain.SupplierItemRepository;
import com.stockflow.procurement.internal.domain.SupplierItemTerms;
import com.stockflow.procurement.internal.domain.SupplierStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-77 supplier catalog persistence against Postgres: the price periods the exclusion constraint
 * polices, the unique pair, the open-order probe and the preferred-first listing.
 *
 * <p>Every call runs in its own {@link TransactionTemplate} transaction, as the service will, so each
 * save really commits; Hikari runs with auto-commit off, so a bare {@code JdbcTemplate} read would
 * otherwise see nothing or hold a connection open.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class SupplierItemRepositoryAdapterIntegrationTest {

    @Autowired SupplierItemRepository items;
    @Autowired SupplierService suppliers;
    @Autowired ProcurementService procurement;
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private UUID supplierId;
    private String sku;
    private UUID inventoryItemId;

    @BeforeEach
    void setUp() {
        supplierId = supplier("Alpha supplier");
        newSku();
    }

    private void newSku() {
        sku = tx.execute(s -> ReferenceRows.approvedProduct(entityManager).sku());
        inventoryItemId = inventory.itemPolicy(new Sku(sku)).orElseThrow().inventoryItemId();
    }

    private UUID supplier(String name) {
        String code = "SI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return suppliers.create(new SaveSupplierCommand(code, name, "Contact", "supplier@example.com",
                "+84901234567", null, "ACTIVE", 30, 7, "EMAIL", null, null, false, null)).supplierId();
    }

    private static Instant now() {
        // Postgres keeps microseconds; a nanosecond Instant would not round-trip for comparison.
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static SupplierItemTerms terms(String price) {
        return SupplierItemTerms.of("SUP-" + price, new Money(new BigDecimal(price), Money.VND), 10, 5, null);
    }

    private SupplierItem create(UUID supplier, String price, boolean preferred, Instant at) {
        return tx.execute(s -> items.save(
                SupplierItem.create(supplier, inventoryItemId, terms(price), preferred), Optional.empty(), at));
    }

    private SupplierItem reprice(UUID itemId, String price, Instant at) {
        return tx.execute(s -> {
            var loaded = items.findByIdForUpdate(itemId).orElseThrow();
            var change = loaded.update(terms(price), loaded.preferred());
            return items.save(loaded, change, at);
        });
    }

    private List<Map<String, Object>> periods(UUID itemId) {
        return tx.execute(s -> jdbc.queryForList("""
                SELECT unit_price, currency, effective_from, effective_to
                  FROM procurement.supplier_item_prices
                 WHERE supplier_item_id = ? ORDER BY effective_from""", itemId));
    }

    private static Instant instant(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    @Test
    @DisplayName("an insert opens the first price period, open-ended")
    void insertOpensTheFirstPricePeriod() {
        Instant at = now();
        var saved = create(supplierId, "10", false, at);

        assertThat(saved.terms().price()).isEqualTo(new Money(new BigDecimal("10"), Money.VND));
        assertThat(periods(saved.id())).singleElement().satisfies(period -> {
            assertThat((BigDecimal) period.get("unit_price")).isEqualByComparingTo("10");
            assertThat(period.get("currency")).isEqualTo("VND");
            assertThat(instant(period.get("effective_from"))).isEqualTo(at);
            assertThat(period.get("effective_to")).isNull();
        });
        var read = tx.execute(s -> items.findById(saved.id())).orElseThrow();
        assertThat(read.terms()).isEqualTo(saved.terms());
        assertThat(read.inventoryItemId()).isEqualTo(inventoryItemId);
    }

    @Test
    @DisplayName("repricing closes the open period at the instant it opens the next")
    void repricingClosesTheOpenPeriodAndOpensOne() {
        Instant first = now();
        Instant second = first.plusSeconds(1);
        var saved = create(supplierId, "10", false, first);

        var repriced = reprice(saved.id(), "12", second);

        assertThat(repriced.terms().price().amount()).isEqualByComparingTo("12");
        var rows = periods(saved.id());
        assertThat(rows).hasSize(2);
        assertThat((BigDecimal) rows.get(0).get("unit_price")).isEqualByComparingTo("10");
        assertThat(instant(rows.get(0).get("effective_from"))).isEqualTo(first);
        assertThat(instant(rows.get(0).get("effective_to"))).isEqualTo(second);
        assertThat((BigDecimal) rows.get(1).get("unit_price")).isEqualByComparingTo("12");
        assertThat(instant(rows.get(1).get("effective_from"))).isEqualTo(second);
        assertThat(rows.get(1).get("effective_to")).isNull();
    }

    @Test
    @DisplayName("repricing at the instant the period opened overwrites it instead of making a zero-length one")
    void repricingAtTheSameInstantOverwritesTheOpenPeriod() {
        Instant at = now();
        var saved = create(supplierId, "10", false, at);

        reprice(saved.id(), "11", at);

        assertThat(periods(saved.id())).singleElement().satisfies(period -> {
            assertThat((BigDecimal) period.get("unit_price")).isEqualByComparingTo("11");
            assertThat(period.get("effective_to")).isNull();
        });
    }

    @Test
    @DisplayName("a second row for the same supplier and SKU is SUPPLIER_ITEM_DUPLICATE, not a 500")
    void aDuplicatePairIsABusinessError() {
        create(supplierId, "10", false, now());

        assertThatThrownBy(() -> create(supplierId, "11", false, now()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SUPPLIER_ITEM_DUPLICATE));
    }

    @Test
    @DisplayName("only an open purchase order for this supplier and SKU blocks removal")
    void usedByOpenPurchaseOrderSeesOnlyOpenOrders() {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID order = procurement.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, DemoData.WAREHOUSE_HCM,
                "VND", LocalDate.now().plusDays(10), null,
                List.of(new CreatePOLineCommand(sku, "Catalog line", 1, BigDecimal.TEN)))).purchaseOrderId();

        assertThat(items.usedByOpenPurchaseOrder(supplierId, inventoryItemId)).isTrue();
        assertThat(items.usedByOpenPurchaseOrder(supplier("Other supplier"), inventoryItemId)).isFalse();

        procurement.cancel(order, user, "catalog test");

        assertThat(items.usedByOpenPurchaseOrder(supplierId, inventoryItemId)).isFalse();
    }

    @Test
    @DisplayName("the SKU's suppliers come preferred first, then by name")
    void suppliersOfListsThePreferredFirst() {
        UUID beta = supplier("Beta supplier");
        create(supplierId, "10", false, now());
        create(beta, "9", true, now());

        var rows = tx.execute(s -> items.suppliersOf(inventoryItemId));

        assertThat(rows).extracting(row -> row.supplier().id()).containsExactly(beta, supplierId);
        assertThat(rows.get(0).item().preferred()).isTrue();
        assertThat(rows.get(0).supplier().currency()).isEqualTo(Money.VND);
        assertThat(rows.get(0).supplier().status()).isEqualTo(SupplierStatus.ACTIVE);
    }

    @Test
    @DisplayName("supplierForShare reads the currency the supplier entity does not map")
    void supplierForShareReadsTheCurrency() {
        var found = tx.execute(s -> items.supplierForShare(supplierId)).orElseThrow();

        assertThat(found.name()).isEqualTo("Alpha supplier");
        assertThat(found.currency()).isEqualTo(Money.VND);
        assertThat(found.leadTimeDays()).isEqualTo(7);
        Optional<CatalogSupplier> unlocked = tx.execute(s -> items.findSupplier(supplierId));
        assertThat(unlocked).contains(found);
        Optional<CatalogSupplier> unknown = tx.execute(s -> items.supplierForShare(UUID.randomUUID()));
        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName("a supplier's catalog pages by price, and a SKU with no preferred supplier has none")
    void listBySupplierPagesAndFindPreferred() {
        create(supplierId, "10", false, now());
        newSku();
        create(supplierId, "20", false, now());
        newSku();
        create(supplierId, "30", false, now());

        var firstPage = tx.execute(s -> items.listBySupplier(supplierId, 0, 2, "currentPrice,desc"));
        var secondPage = tx.execute(s -> items.listBySupplier(supplierId, 1, 2, "currentPrice,desc"));

        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.items()).extracting(i -> i.terms().price().amount().intValue()).containsExactly(30, 20);
        assertThat(secondPage.items()).extracting(i -> i.terms().price().amount().intValue()).containsExactly(10);
        Optional<SupplierItem> preferred = tx.execute(s -> {
            items.lockCatalogOf(inventoryItemId);
            return items.findPreferred(inventoryItemId);
        });
        assertThat(preferred).isEmpty();
    }

    @Test
    @DisplayName("listedForShare returns only this supplier's rows, keyed by inventory item")
    void listedForShareReturnsOnlyThisSuppliersRowsKeyedByItem() {
        UUID other = supplier("Other supplier");
        UUID first = inventoryItemId;
        create(supplierId, "10", false, now());
        newSku();
        UUID second = inventoryItemId;
        create(supplierId, "20", false, now());
        newSku();
        UUID third = inventoryItemId;
        create(other, "30", false, now());

        var listed = tx.execute(s -> items.listedForShare(supplierId, List.of(first, second, third)));

        assertThat(listed.keySet()).isEqualTo(Set.of(first, second));
        assertThat(listed.get(first).inventoryItemId()).isEqualTo(first);
        assertThat(listed.get(first).supplierId()).isEqualTo(supplierId);
        assertThat(listed.get(second).terms().price().amount()).isEqualByComparingTo("20");
        assertThat(listed.get(second).terms().moq()).isEqualTo(10);
        assertThat(listed.get(second).terms().packSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("listedForShare of no items is empty")
    void listedForShareOfNoItemsIsEmpty() {
        create(supplierId, "10", false, now());

        Map<UUID, SupplierItem> listed = tx.execute(s -> items.listedForShare(supplierId, List.of()));

        assertThat(listed).isEmpty();
    }
}
