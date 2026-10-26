package com.stockflow.warehouse;

import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.api.PutawayCandidate;
import com.stockflow.warehouse.api.StorageLocationView.StorageClass;
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
 * SCRUM-92: the bins a putaway may choose from, on the demo map of {@code HCM} (shelves {@code A01} and {@code A02}
 * {@code OVERSIZE}, {@code B01} {@code NORMAL}, every bin 40 units and 250 kg). Every test rolls back.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class PutawayCandidatesIntegrationTest {

    @Autowired WarehouseService warehouses;
    @Autowired JdbcTemplate jdbc;

    private List<String> codes(StorageClass storageClass) {
        return warehouses.putawayCandidates(DemoData.WAREHOUSE_HCM, storageClass).stream()
                .map(PutawayCandidate::locationCode).toList();
    }

    @Test
    @DisplayName("only usable putaway-target bins of the class: no area, no bin on a shelf in maintenance, no blocked bin, no other class")
    void onlyUsableBinsOfTheClass() {
        jdbc.update("UPDATE warehouse.shelf SET status = 'MAINTENANCE' WHERE code = 'A02' AND warehouse_id = ?",
                DemoData.WAREHOUSE_HCM);
        jdbc.update("UPDATE warehouse.storage_location SET status = 'BLOCKED' WHERE location_code = 'HCM-A01-1-A'");
        jdbc.update("UPDATE warehouse.storage_location SET storage_class = 'COLD' WHERE location_code = 'HCM-B01-1-A'");
        jdbc.update("UPDATE warehouse.storage_location SET is_putaway_target = FALSE"
                + " WHERE location_code = 'HCM-B01-1-B'");

        List<String> oversize = codes(StorageClass.OVERSIZE);
        List<String> normal = codes(StorageClass.NORMAL);

        assertThat(oversize).isNotEmpty().isSorted().doesNotContain("HCM-A01-1-A")
                .noneMatch(code -> code.startsWith("HCM-A02-"))
                .allMatch(code -> code.startsWith("HCM-A01-"))
                .as("an area is never a candidate, though HCM-RCV01 is OVERSIZE").doesNotContain("HCM-RCV01");
        assertThat(normal).isNotEmpty().isSorted().doesNotContain("HCM-B01-1-A")
                .as("a bin not flagged as a putaway target").doesNotContain("HCM-B01-1-B")
                .allMatch(code -> code.startsWith("HCM-B01-"));
        assertThat(codes(StorageClass.COLD)).containsExactly("HCM-B01-1-A");
        assertThat(codes(StorageClass.HAZMAT)).isEmpty();
        assertThat(warehouses.putawayCandidates(UUID.randomUUID(), StorageClass.NORMAL)).isEmpty();
    }

    @Test
    @DisplayName("capacity and weight come back as stored, and a bin without them keeps nulls")
    void capacityAndWeightAsStored() {
        jdbc.update("UPDATE warehouse.storage_location SET capacity_units = 500, max_weight = 200"
                + " WHERE location_code = 'HCM-B01-1-A'");
        jdbc.update("UPDATE warehouse.storage_location SET capacity_units = NULL, max_weight = NULL"
                + " WHERE location_code = 'HCM-B01-1-B'");

        List<PutawayCandidate> normal = warehouses.putawayCandidates(DemoData.WAREHOUSE_HCM, StorageClass.NORMAL);

        assertThat(normal).filteredOn(c -> c.locationCode().equals("HCM-B01-1-A")).singleElement().satisfies(c -> {
            assertThat(c.locationId()).isEqualTo(DemoData.location("HCM-B01-1-A"));
            assertThat(c.capacityUnits()).isEqualTo(500);
            assertThat(c.maxWeightKg()).isEqualByComparingTo(new BigDecimal("200"));
        });
        assertThat(normal).filteredOn(c -> c.locationCode().equals("HCM-B01-1-B")).singleElement().satisfies(c -> {
            assertThat(c.capacityUnits()).isNull();
            assertThat(c.maxWeightKg()).isNull();
        });
        assertThat(normal).filteredOn(c -> c.locationCode().equals("HCM-B01-2-A")).singleElement()
                .satisfies(c -> assertThat(c.capacityUnits()).as("the demo bin as seeded").isEqualTo(40));
    }
}
