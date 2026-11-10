package com.stockflow.warehouse;

import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.api.BinSize;
import com.stockflow.warehouse.api.PutawayCandidate;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-92, team plan 5-4: the bins a putaway may choose from, on the demo map of {@code HCM} (shelves {@code A01},
 * {@code A02}, {@code B01}, two levels 1.4 m clear, every bin 5 m x 1.2 m and 250 kg). No storage class any more
 * (kltn-docs 06, 2026-11-05): every usable putaway-target bin is a candidate, with its size, so the caller can tell
 * which ones a carton fits. Every test rolls back.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class PutawayCandidatesIntegrationTest {

    @Autowired WarehouseService warehouses;
    @Autowired JdbcTemplate jdbc;

    private List<String> codes() {
        return warehouses.putawayCandidates(DemoData.WAREHOUSE_HCM).stream()
                .map(PutawayCandidate::locationCode).toList();
    }

    private PutawayCandidate candidate(String code) {
        return warehouses.putawayCandidates(DemoData.WAREHOUSE_HCM).stream()
                .filter(c -> c.locationCode().equals(code)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("only usable putaway-target bins: no area, no bin on a shelf in maintenance, no blocked bin, no unflagged bin")
    void onlyUsablePutawayBins() {
        jdbc.update("UPDATE warehouse.shelf SET status = 'MAINTENANCE' WHERE code = 'A02' AND warehouse_id = ?",
                DemoData.WAREHOUSE_HCM);
        jdbc.update("UPDATE warehouse.storage_location SET status = 'BLOCKED' WHERE location_code = 'HCM-A01-1-A'");
        jdbc.update("UPDATE warehouse.storage_location SET is_putaway_target = FALSE"
                + " WHERE location_code = 'HCM-B01-1-B'");

        List<String> codes = codes();

        assertThat(codes).isNotEmpty().isSorted().doesNotContain("HCM-A01-1-A", "HCM-B01-1-B")
                .noneMatch(code -> code.startsWith("HCM-A02-"))
                .contains("HCM-A01-1-B", "HCM-B01-1-A", "HCM-B01-2-B")
                .as("an area is never a candidate, OVERFLOW included").doesNotContain("HCM-RCV01", "HCM-OVF01");
        assertThat(warehouses.putawayCandidates(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("each candidate carries the bin's size and its level's clear height; the volume is computed from them")
    void sizeAndComputedVolume() {
        PutawayCandidate bin = candidate("HCM-B01-2-A");

        assertThat(bin.locationId()).isEqualTo(DemoData.location("HCM-B01-2-A"));
        assertThat(bin.size()).isEqualTo(new BinSize(new BigDecimal("5.000"), new BigDecimal("1.200"),
                new BigDecimal("1.400")));
        assertThat(bin.capacityVolumeM3()).isEqualByComparingTo("8.4");
        assertThat(bin.maxWeightKg()).isEqualByComparingTo("250");
    }

    @Test
    @DisplayName("resizing a bin or its level changes its volume at once: nothing is stored to fall out of step")
    void volumeFollowsTheMap() {
        jdbc.update("UPDATE warehouse.bin SET width = 2.5 WHERE location_id = ?", DemoData.location("HCM-B01-2-A"));
        jdbc.update("UPDATE warehouse.shelf_level SET usable_height = 0.8 WHERE id = (SELECT level_id FROM"
                + " warehouse.bin WHERE location_id = ?)", DemoData.location("HCM-B01-2-A"));

        assertThat(candidate("HCM-B01-2-A").capacityVolumeM3()).isEqualByComparingTo("2.4");    // 2.5 x 1.2 x 0.8
    }

    @Test
    @DisplayName("a legacy level without a clear height gives no volume (not limited) and no height; weight as stored")
    void legacyLevelWithoutClearHeight() {
        jdbc.update("ALTER TABLE warehouse.shelf_level DROP CONSTRAINT ck_shelf_level_usable_height_required");
        jdbc.update("UPDATE warehouse.shelf_level SET usable_height = NULL WHERE id = (SELECT level_id FROM"
                + " warehouse.bin WHERE location_id = ?)", DemoData.location("HCM-B01-1-A"));
        jdbc.update("UPDATE warehouse.storage_location SET max_weight = NULL WHERE location_code = 'HCM-B01-1-A'");

        PutawayCandidate bin = candidate("HCM-B01-1-A");

        assertThat(bin.size().usableHeight()).isNull();
        assertThat(bin.capacityVolumeM3()).isNull();
        assertThat(bin.maxWeightKg()).isNull();
    }

    @Test
    @DisplayName("one bin judged by the same rule: a candidate by code, an area or an unflagged bin is not")
    void oneByCode() {
        assertThat(warehouses.putawayCandidate(DemoData.WAREHOUSE_HCM, "HCM-B01-2-A").orElseThrow()
                .capacityVolumeM3()).isEqualByComparingTo("8.4");
        assertThat(warehouses.putawayCandidate(DemoData.WAREHOUSE_HCM, "HCM-OVF01")).isEmpty();
        jdbc.update("UPDATE warehouse.storage_location SET is_putaway_target = FALSE"
                + " WHERE location_code = 'HCM-B01-1-B'");
        assertThat(warehouses.putawayCandidate(DemoData.WAREHOUSE_HCM, "HCM-B01-1-B")).isEmpty();
    }

    @Test
    @DisplayName("the lookup tells bins from areas and gives a bin its size; the OVERFLOW area is found by type")
    void lookupCarriesTheSize() {
        StorageLocationView bin = warehouses.findLocation("HCM-B01-2-A").orElseThrow();
        assertThat(bin.binSize().volumeM3()).isEqualByComparingTo("8.4");

        assertThat(warehouses.areasOf(DemoData.WAREHOUSE_HCM, StorageLocationView.AreaType.OVERFLOW))
                .singleElement().satisfies(area -> {
                    assertThat(area.locationCode()).isEqualTo("HCM-OVF01");
                    assertThat(area.binSize()).isNull();
                    assertThat(area.usable()).isTrue();
                });
    }
}
