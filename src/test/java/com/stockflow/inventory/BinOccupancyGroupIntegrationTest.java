package com.stockflow.inventory;

import com.stockflow.support.StockedSkus;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery.BinContents;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery.SkuLot;
import com.stockflow.support.CartonRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #412: what a bin holds of a GROUP of cartons moved together, read in one statement - the SKUs and the (SKU,
 * lot) pairs of the group the bin already holds - so a bin is "same SKU" / "same lot" for the group only when it holds
 * all of it. The single-carton read is the group of one and answers as before.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class BinOccupancyGroupIntegrationTest {

    /** A bin of the demo map of HCM; the test reads only what its own fresh SKUs add. */
    private static final String BIN = "HCM-B01-2-A";

    @Autowired BinOccupancyQuery query;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private String sku1;
    private String sku2;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        sku1 = "BOG-1-" + suffix;
        sku2 = "BOG-2-" + suffix;
        tx.executeWithoutResult(s -> {
            item(sku1);
            item(sku2);
        });
    }

    /**
     * The cartons are committed into a demo bin other tests resize and count on
     * ({@code BinResizeStrandingIntegrationTest}), so they leave it as they found it.
     */
    @AfterEach
    void removeCartons() {
        tx.executeWithoutResult(s -> {
            jdbc.update("delete from inventory.stock_package where inventory_item_id in"
                    + " (select id from inventory.inventory_items where sku in (?, ?))", sku1, sku2);
        });
    }

    private void item(String sku) {
        UUID product = Identifiers.newId();
        jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                "Group occupancy fixture", sku.toLowerCase(Locale.ROOT));
        StockedSkus.customerSkuOn(jdbc, product, sku, "Group occupancy fixture");
    }

    /** One carton of {@code units} units, its unit 50 x 40 x 30 cm (0.06 m³). */
    private UUID carton(String sku, int units, String lot) {
        return tx.execute(s -> {
            CartonRows.Carton made = CartonRows.carton(jdbc, sku, BIN, units, lot,
                    Instant.now().minus(Duration.ofDays(1)), "AVAILABLE");
            jdbc.update("update inventory.uom_conversions set length_cm = 50, width_cm = 40, height_cm = 30"
                    + " where id = (select uom_conversion_id from inventory.stock_package where id = ?)", made.id());
            return made.id();
        });
    }

    @Test
    @DisplayName("a bin holding both cartons' SKUs and lots holds the whole group")
    void holdsEveryPairOfTheGroup() {
        carton(sku1, 10, "L1");
        carton(sku2, 6, "L2");
        Set<SkuLot> group = Set.of(new SkuLot(sku1, "L1"), new SkuLot(sku2, "L2"));

        BinContents bin = query.contentsOf(List.of(BIN), group, List.of()).get(BIN);

        assertThat(bin.skus()).containsExactlyInAnyOrder(sku1, sku2);
        assertThat(bin.lots()).containsExactlyInAnyOrderElementsOf(group);
    }

    @Test
    @DisplayName("only the pairs of the group the bin holds are named: another lot of the SKU is not")
    void namesOnlyThePairsItHolds() {
        carton(sku1, 10, "L1");

        BinContents bin = query.contentsOf(List.of(BIN),
                Set.of(new SkuLot(sku1, "L1"), new SkuLot(sku1, "L9")), List.of()).get(BIN);

        assertThat(bin.skus()).containsExactly(sku1);
        assertThat(bin.lots()).containsExactly(new SkuLot(sku1, "L1"));
    }

    @Test
    @DisplayName("a carton left out counts for nothing: not its units, not its volume, not its SKU")
    void leavesOutACarton() {
        Set<SkuLot> group = Set.of(new SkuLot(sku1, "L1"), new SkuLot(sku2, "L2"));
        BinContents before = query.contentsOf(List.of(BIN), group, List.of()).get(BIN);
        UUID first = carton(sku1, 10, "L1");
        carton(sku2, 6, "L2");

        BinContents both = query.contentsOf(List.of(BIN), group, List.of()).get(BIN);
        BinContents without = query.contentsOf(List.of(BIN), group, List.of(first)).get(BIN);

        int unitsBefore = before == null ? 0 : before.units();
        BigDecimal volumeBefore = before == null ? BigDecimal.ZERO : before.volumeM3();
        assertThat(both.units() - unitsBefore).isEqualTo(16);
        assertThat(both.volumeM3().subtract(volumeBefore)).isEqualByComparingTo("0.12");
        assertThat(without.units() - unitsBefore).isEqualTo(6);
        assertThat(without.volumeM3().subtract(volumeBefore)).isEqualByComparingTo("0.06");
        assertThat(without.skus()).containsExactly(sku2);
        assertThat(without.lots()).containsExactly(new SkuLot(sku2, "L2"));
    }

    @Test
    @DisplayName("the single-carton read is the group of one, reduced: it answers as before")
    void singleCartonReadIsTheGroupOfOne() {
        carton(sku1, 10, "L1");
        carton(sku2, 6, "L2");
        Set<SkuLot> one = Set.of(new SkuLot(sku1, "L1"));

        Occupancy old = query.occupancyOf(List.of(BIN), sku1, "L1").get(BIN);
        BinContents contents = query.contentsOf(List.of(BIN), one, List.of()).get(BIN);

        assertThat(contents.toOccupancy(one)).isEqualTo(old);
        assertThat(old.holdsSku()).isTrue();
        assertThat(old.holdsLot()).isTrue();
        assertThat(query.occupancyOf(List.of(BIN), sku1, null).get(BIN).holdsLot())
                .as("a carton without a lot never holds the lot").isFalse();
        Map<String, BinContents> nullLot = query.contentsOf(List.of(BIN), Set.of(new SkuLot(sku1, null)), List.of());
        assertThat(nullLot.get(BIN).lots()).as("a pair with a null lot never matches").isEmpty();
        assertThat(nullLot.get(BIN).skus()).containsExactly(sku1);
    }
}
