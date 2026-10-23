package com.stockflow.warehouse.internal;

import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two lookups a cycle count plans with (SCRUM-162), on the demo map of {@code HCM}: zone A holds shelves
 * {@code A01} and {@code A02}, and is no other warehouse's; bins and areas by code, whatever their status - a
 * count must still count a blocked bin, which {@code pickableLocations} would drop. Every test rolls back.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class WarehouseLocationLookupIntegrationTest {

    @Autowired WarehouseService warehouses;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("a zone's shelf codes are its shelves' own codes, sorted; none for a zone of another warehouse")
    void shelfCodesOfADemoZone() {
        assertThat(warehouses.shelfCodesOf(DemoData.WAREHOUSE_HCM, DemoData.ZONE_HCM_A)).containsExactly("A01", "A02");
        assertThat(warehouses.shelfCodesOf(DemoData.WAREHOUSE_HCM, DemoData.ZONE_HCM_B)).containsExactly("B01");
        assertThat(warehouses.shelfCodesOf(DemoData.WAREHOUSE_HCM, UUID.randomUUID())).as("an unknown zone").isEmpty();
        assertThat(warehouses.shelfCodesOf(UUID.randomUUID(), DemoData.ZONE_HCM_A)).as("asked for another warehouse")
                .isEmpty();
    }

    @Test
    @DisplayName("findLocations returns a blocked bin, with its kind and status, beside an area")
    void findLocationsKeepsBlockedBins() {
        jdbc.update("UPDATE warehouse.storage_location SET status = 'BLOCKED' WHERE location_code = 'HCM-B01-2-B'");

        List<StorageLocationView> found = warehouses.findLocations(List.of("HCM-B01-2-B", "HCM-RCV01"));

        assertThat(found).extracting(StorageLocationView::locationCode).containsExactlyInAnyOrder("HCM-B01-2-B",
                "HCM-RCV01");
        assertThat(found).filteredOn(view -> view.locationCode().equals("HCM-B01-2-B")).singleElement()
                .satisfies(bin -> {
                    assertThat(bin.kind()).isEqualTo(StorageLocationView.Kind.BIN);
                    assertThat(bin.status()).isEqualTo(StorageLocationView.Status.BLOCKED);
                    assertThat(bin.usable()).isFalse();
                    assertThat(bin.warehouseId()).isEqualTo(DemoData.WAREHOUSE_HCM);
                });
        assertThat(found).filteredOn(view -> view.locationCode().equals("HCM-RCV01")).singleElement()
                .satisfies(area -> assertThat(area.kind()).isEqualTo(StorageLocationView.Kind.AREA));
        assertThat(warehouses.pickableLocations(List.of("HCM-B01-2-B"))).as("what picking would have said").isEmpty();
    }

    @Test
    @DisplayName("an unknown code is absent, not an error; no codes ask nothing")
    void unknownCodesAreAbsent() {
        assertThat(warehouses.findLocations(List.of("HCM-A01-1-A", "HCM-Z99-9-Z", "NOPE")))
                .extracting(StorageLocationView::locationCode).containsExactly("HCM-A01-1-A");
        assertThat(warehouses.findLocations(List.of())).isEmpty();
    }
}
