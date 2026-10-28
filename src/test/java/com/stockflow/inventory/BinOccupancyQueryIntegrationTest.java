package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SCRUM-92: what a bin holds, summed over every SKU on it, as the putaway ranker reads it. */
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
            item(skuA, "2.000", true);
            item(skuB, null, false);
        });
    }

    private void item(String sku, String weight, boolean lotTracked) {
        UUID product = Identifiers.newId();
        jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                "Occupancy fixture", sku.toLowerCase(Locale.ROOT));
        jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                Identifiers.newId(), product, sku, "Occupancy fixture");
        jdbc.update("update inventory.inventory_items set weight_kg = ?::numeric, lot_tracked = ? where sku = ?",
                weight, lotTracked, sku);
    }

    private void stock(String sku, String bin, int onHand, String lot) {
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_item (id, sku, location_code, lot_number, received_at, on_hand, reserved,
                    status, version, created_at)
                values (?, ?, ?, ?, now() - interval '1 day', ?, 0, 'AVAILABLE', 0, now())""",
                Identifiers.newId(), sku, bin, lot, onHand));
    }

    @Test
    @DisplayName("two SKUs in one bin: units add up, weight is on_hand x weight_kg with an unknown weight as 0, "
            + "and the asked SKU and lot are recognised; an empty bin is absent")
    void sumsEverySkuOnTheBin() {
        // Other data may share the bin, so the test measures what its own rows add.
        Occupancy before = query.occupancyOf(List.of(BIN), skuA, "LOT-1").getOrDefault(BIN, Occupancy.EMPTY);
        stock(skuA, BIN, 10, "LOT-1");
        stock(skuA, BIN, 5, "LOT-2");
        stock(skuB, BIN, 7, null);

        Map<String, Occupancy> out = query.occupancyOf(List.of(BIN, EMPTY_BIN), skuA, "LOT-1");

        assertThat(out).containsOnlyKeys(BIN);
        Occupancy bin = out.get(BIN);
        assertThat(bin.units() - before.units()).isEqualTo(22);
        assertThat(bin.weightKg().subtract(before.weightKg())).isEqualByComparingTo("30");
        assertThat(bin.holdsSku()).isTrue();
        assertThat(bin.holdsLot()).isTrue();
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
