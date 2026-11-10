package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.internal.domain.PutawayRanker.Need;
import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.support.CartonRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-92, team plan 5-4: what a bin holds, summed over every SKU on it, and what a carton takes of it, as the putaway
 * ranker reads them. A carton takes its carton unit's length x width x height and weighs the unit's full weight
 * (kltn-docs 05 BR-03), or - its unit not weighed - the item's unit weight times its units. Loose units, history since
 * V20261111000110, no longer exist at all (V20261117000100), so every row here is a carton's.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class BinOccupancyQueryIntegrationTest {

    private static final String BIN = "HCM-A01-1-A";
    /** Not a bin of any map: nothing is ever stocked there. */
    private static final String EMPTY_BIN = "NO-SUCH-BIN";

    @Autowired BinOccupancyQuery query;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private String skuA;
    private String skuB;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        skuA = "BOQ-A-" + suffix;
        skuB = "BOQ-B-" + suffix;
        tx.executeWithoutResult(s -> {
            item(skuA, "2.000");
            item(skuB, null);
        });
    }

    private void item(String sku, String weight) {
        UUID product = Identifiers.newId();
        jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                "Occupancy fixture", sku.toLowerCase(Locale.ROOT));
        jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                Identifiers.newId(), product, sku, "Occupancy fixture");
        jdbc.update("update inventory.inventory_items set weight_kg = ?::numeric where sku = ?", weight, sku);
    }

    /** One carton of exactly {@code onHand} units, its unit neither measured nor weighed. */
    private void stock(String sku, String bin, int onHand, String lot) {
        tx.executeWithoutResult(s -> CartonRows.carton(jdbc, sku, bin, onHand, lot,
                Instant.now().minus(java.time.Duration.ofDays(1)), "AVAILABLE"));
    }

    @Test
    @DisplayName("two SKUs in one bin: units add up, an unweighed carton weighs on_hand x weight_kg with an unknown "
            + "weight as 0, and the asked SKU and lot are recognised; an empty bin is absent")
    void sumsEverySkuOnTheBin() {
        // Other data may share the bin, so the test measures what its own rows add.
        Occupancy before = query.occupancyOf(List.of(BIN), skuA, "LOT-1").getOrDefault(BIN, Occupancy.EMPTY);
        stock(skuA, BIN, 10, "LOT-1");
        stock(skuA, BIN, 5, "LOT-2");
        stock(skuB, BIN, 7, "LOT-B");

        Map<String, Occupancy> out = query.occupancyOf(List.of(BIN, EMPTY_BIN), skuA, "LOT-1");

        assertThat(out).containsOnlyKeys(BIN);
        Occupancy bin = out.get(BIN);
        assertThat(bin.units() - before.units()).isEqualTo(22);
        assertThat(bin.weightKg().subtract(before.weightKg())).isEqualByComparingTo("30");
        assertThat(bin.holdsSku()).isTrue();
        assertThat(bin.holdsLot()).isTrue();
    }

    /** A carton unit of {@code factor} units of 50 x 40 x 30 cm (0.06 m³), weighing {@code weight} kg full or unknown. */
    private UUID cartonUnit(String sku, int factor, String weight) {
        return tx.execute(s -> {
            UUID unit = CartonRows.unit(jdbc, sku, factor);
            jdbc.update("update inventory.uom_conversions set length_cm = 50, width_cm = 40, height_cm = 30,"
                    + " weight_kg = ?::numeric where id = ?", weight, unit);
            return unit;
        });
    }

    @Test
    @DisplayName("cartons take their carton unit's volume and weigh it full; a carton left out is not counted")
    void cartonsTakeTheirVolume() {
        cartonUnit(skuA, 10, "12");
        Occupancy before = query.occupancyOf(List.of(BIN), skuA, "LOT-1").getOrDefault(BIN, Occupancy.EMPTY);
        List<CartonRows.Carton> cartons = tx.execute(s ->
                CartonRows.stock(jdbc, skuA, BIN, 2, 10, "LOT-1", Instant.now()));

        Occupancy both = query.occupancyOf(List.of(BIN), skuA, "LOT-1").get(BIN);
        Occupancy one = query.occupancyOf(List.of(BIN), skuA, "LOT-1", cartons.get(0).id()).get(BIN);

        assertThat(both.volumeM3().subtract(before.volumeM3())).isEqualByComparingTo("0.12");
        assertThat(both.weightKg().subtract(before.weightKg())).isEqualByComparingTo("24");
        assertThat(both.units() - before.units()).isEqualTo(20);
        assertThat(one.volumeM3().subtract(before.volumeM3())).isEqualByComparingTo("0.06");
        assertThat(one.weightKg().subtract(before.weightKg())).isEqualByComparingTo("12");
        assertThat(both.holdsLot()).isTrue();
    }

    @Test
    @DisplayName("what a carton takes: its unit's size, the item's rotatable flag, the unit's weight or else the units'")
    void needsOfCartons() {
        cartonUnit(skuA, 10, "12");
        cartonUnit(skuB, 10, null);
        tx.executeWithoutResult(s -> jdbc.update("update inventory.inventory_items set rotatable = false, weight_kg = 0.5"
                + " where sku = ?", skuB));
        UUID a = tx.execute(s -> CartonRows.stock(jdbc, skuA, BIN, 1, 10, "LOT-1", Instant.now())).get(0).id();
        UUID b = tx.execute(s -> CartonRows.stock(jdbc, skuB, BIN, 1, 10, "LOT-1", Instant.now())).get(0).id();

        Map<UUID, Need> needs = query.needsOf(List.of(a, b, UUID.randomUUID()));

        assertThat(needs).containsOnlyKeys(a, b);
        assertThat(needs.get(a).carton().volumeM3()).isEqualByComparingTo("0.06");
        assertThat(needs.get(a).carton().rotatable()).isTrue();
        assertThat(needs.get(a).weightKg()).isEqualByComparingTo("12");
        assertThat(needs.get(b).carton().rotatable()).isFalse();
        assertThat(needs.get(b).weightKg()).as("10 units x 0.5 kg").isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("another lot or another SKU does not count as the asked one")
    void holdsOnlyTheAskedSkuAndLot() {
        stock(skuA, BIN, 10, "LOT-1");

        assertThat(query.occupancyOf(List.of(BIN), skuA, "LOT-9").get(BIN))
                .satisfies(o -> {
                    assertThat(o.holdsSku()).isTrue();
                    assertThat(o.holdsLot()).isFalse();
                });
        assertThat(query.occupancyOf(List.of(BIN), skuB, null).get(BIN))
                .satisfies(o -> {
                    assertThat(o.holdsSku()).isFalse();
                    assertThat(o.holdsLot()).isFalse();
                });
    }
}
