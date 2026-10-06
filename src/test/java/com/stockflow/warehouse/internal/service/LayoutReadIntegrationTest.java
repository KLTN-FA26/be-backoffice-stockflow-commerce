package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.BinDefaults;
import com.stockflow.warehouse.internal.domain.BinNamingScheme;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LevelMeasures;
import com.stockflow.warehouse.internal.domain.LocationSettings;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.PickFaces;
import com.stockflow.warehouse.internal.domain.StorageClass;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the map (issue #25) on Postgres with the demo seed: warehouse {@code HCM}, map 60 x 40,
 * zones "Khu A - Sofa" and "Khu B - Bàn", shelves A01, A02 (OVERSIZE) and B01 (NORMAL) with levels
 * 1-2 and bins A and B on each, storage areas RCV01, QC01 (QUARANTINE), PACK01 and DSP01, the
 * {@code NON_STORAGE} area OFFICE, a wall and a door. Every test rolls back.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class LayoutReadIntegrationTest {

    @Autowired WarehouseLayoutService warehouses;
    @Autowired ShelfLayoutService shelves;
    @Autowired WarehouseService locations;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;

    private UUID hcm() {
        return jdbc.queryForObject("select id from warehouse.warehouse where prefix = 'HCM'", UUID.class);
    }

    private UUID shelfId(String code) {
        return jdbc.queryForObject("select s.id from warehouse.shelf s join warehouse.warehouse w "
                + "on w.id = s.warehouse_id where w.prefix = 'HCM' and s.code = ?", UUID.class, code);
    }

    private StorageLocationView location(String code) {
        return locations.findLocation(code).orElseThrow();
    }

    /** Writes go to the database and the persistence context is emptied, so every read hits Postgres. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static WarehouseLayout.Shelf shelf(WarehouseLayout layout, String code) {
        return layout.shelves().stream().filter(s -> s.code().equals(code)).findFirst().orElseThrow();
    }

    private static WarehouseLayout.Area area(WarehouseLayout layout, String code) {
        return layout.areas().stream().filter(a -> a.code().equals(code)).findFirst().orElseThrow();
    }

    private static List<WarehouseLayout.Bin> bins(WarehouseLayout.Shelf shelf) {
        return shelf.levels().stream().flatMap(level -> level.bins().stream()).toList();
    }

    @Nested
    @DisplayName("the layout of a warehouse")
    class Layout {

        @Test
        @DisplayName("HCM comes whole: frame, zones, shelves with levels and bins, areas, boundaries")
        void demoWarehouse() {
            WarehouseLayout layout = warehouses.layoutOf(hcm());

            assertThat(layout.warehouse().prefix()).isEqualTo("HCM");
            assertThat(layout.warehouse().mapWidth()).isEqualByComparingTo("60");
            assertThat(layout.zones()).extracting(ZoneSummary::name).containsExactly("Khu A - Sofa", "Khu B - Bàn");
            assertThat(layout.shelves()).extracting(WarehouseLayout.Shelf::code).containsExactly("A01", "A02", "B01");
            WarehouseLayout.Shelf a01 = shelf(layout, "A01");
            assertThat(a01.levels()).extracting(WarehouseLayout.Level::levelIndex).containsExactly(1, 2);
            assertThat(a01.levels().get(1).bins()).extracting(WarehouseLayout.Bin::locationCode)
                    .containsExactly("HCM-A01-2-A", "HCM-A01-2-B");
            WarehouseLayout.Bin bin = a01.levels().get(1).bins().get(1);
            assertThat(bin.storageClass()).isEqualTo(StorageClass.OVERSIZE);
            assertThat(bin.capacityUnits()).isEqualTo(40);
            assertThat(bin.effectiveStatus()).isEqualTo(LocationStatus.ACTIVE);
            assertThat(bin.usable()).isTrue();
            assertThat(a01.pickFaces()).isEqualTo(new PickFaces(false, false, true, false));
            assertThat(layout.areas()).extracting(WarehouseLayout.Area::code)
                    .containsExactly("DSP01", "OFFICE", "PACK01", "QC01", "RCV01");
            assertThat(layout.boundaries()).hasSize(2);
        }

        @Test
        @DisplayName("a NON_STORAGE area has no location and no usable flag; a storage area has both")
        void areas() {
            WarehouseLayout layout = warehouses.layoutOf(hcm());

            WarehouseLayout.Area office = area(layout, "OFFICE");
            assertThat(office.type()).isEqualTo(AreaType.NON_STORAGE);
            assertThat(office.locationId()).isNull();
            assertThat(office.usable()).isNull();
            assertThat(office.effectiveStatus()).isEqualTo(LocationStatus.ACTIVE);
            WarehouseLayout.Area qc = area(layout, "QC01");
            assertThat(qc.locationCode()).isEqualTo("HCM-QC01");
            assertThat(qc.usable()).isTrue();
        }

        @Test
        @DisplayName("an unknown warehouse is WAREHOUSE_NOT_FOUND")
        void unknownWarehouse() {
            assertThat(errorOf(() -> warehouses.layoutOf(UUID.randomUUID()))).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        }
    }

    /**
     * Reading the map costs the same number of statements whatever is on it: one per table, each
     * filtered by the warehouse. Five shelves or twenty-five, two levels of ten bins each - the
     * counts must be equal. A query per shelf (or per level) would make the second five times the first.
     */
    @Test
    @DisplayName("5 and 25 shelves of 2 x 10 bins read in the same number of statements")
    void constantQueryCount() {
        UUID small = warehouseWithShelves("QA5", 5);
        UUID large = warehouseWithShelves("QA25", 25);
        flushAndClear();

        long smallStatements = statementsToRead(small, 100);
        long largeStatements = statementsToRead(large, 500);

        assertThat(smallStatements).isEqualTo(largeStatements).isEqualTo(7);
    }

    private long statementsToRead(UUID warehouseId, int expectedBins) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            WarehouseLayout layout = warehouses.layoutOf(warehouseId);
            assertThat(layout.shelves().stream().mapToLong(s -> bins(s).size()).sum()).isEqualTo(expectedBins);
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private UUID warehouseWithShelves(String prefix, int shelfCount) {
        UUID warehouseId = warehouses.register(new RegisterWarehouseCommand(prefix, "Kho " + prefix,
                "1 Đường Thử", null, MapUnit.M, new BigDecimal("60"), new BigDecimal("60"))).id();
        BinDefaults defaults = new BinDefaults(BinType.SMALL_PARTS, null,
                new LocationSettings(10, null, true, true));
        IntStream.range(0, shelfCount).forEach(i -> {
            UUID shelfId = shelves.createShelf(new ShelfCommands.CreateShelf(warehouseId, null,
                    "S%02d".formatted(i), "Kệ " + i, null,
                    new Footprint(BigDecimal.ONE, BigDecimal.valueOf(1 + 2L * i), BigDecimal.TEN,
                            new BigDecimal("1.2"), 0),
                    true, new PickFaces(true, false, false, false), StorageClass.NORMAL)).id();
            UUID first = shelves.addLevel(new ShelfCommands.AddLevel(shelfId, 1, LevelMeasures.NONE)).id();
            UUID second = shelves.addLevel(new ShelfCommands.AddLevel(shelfId, 2, LevelMeasures.NONE)).id();
            shelves.generateBins(new ShelfCommands.GenerateBins(shelfId, List.of(first, second), 1, 10,
                    BinNamingScheme.SEQUENTIAL, defaults));
        });
        return warehouseId;
    }

    @Nested
    @DisplayName("effective status (issue #18 D2)")
    class Effective {

        @Test
        @DisplayName("an ACTIVE bin on a shelf in MAINTENANCE is MAINTENANCE and unusable, in the layout and by lookup")
        void shelfNarrowsItsBins() {
            shelves.changeShelfStatus(shelfId("A01"), LocationStatus.MAINTENANCE);
            flushAndClear();

            WarehouseLayout layout = warehouses.layoutOf(hcm());
            WarehouseLayout.Shelf a01 = shelf(layout, "A01");
            assertThat(a01.status()).isEqualTo(LocationStatus.MAINTENANCE);
            assertThat(bins(a01)).allSatisfy(bin -> {
                assertThat(bin.status()).isEqualTo(LocationStatus.ACTIVE);
                assertThat(bin.effectiveStatus()).isEqualTo(LocationStatus.MAINTENANCE);
                assertThat(bin.usable()).isFalse();
            });
            assertThat(bins(shelf(layout, "A02"))).allSatisfy(bin -> assertThat(bin.usable()).isTrue());

            StorageLocationView view = location("HCM-A01-1-A");
            assertThat(view.status()).isEqualTo(StorageLocationView.Status.ACTIVE);
            assertThat(view.effectiveStatus()).isEqualTo(StorageLocationView.Status.MAINTENANCE);
            assertThat(view.usable()).isFalse();
        }

        @Test
        @DisplayName("an INACTIVE warehouse makes every location INACTIVE and unusable")
        void inactiveWarehouse() {
            warehouses.deactivate(hcm());
            flushAndClear();

            WarehouseLayout layout = warehouses.layoutOf(hcm());
            assertThat(layout.shelves()).flatExtracting(LayoutReadIntegrationTest::bins).hasSize(12)
                    .allSatisfy(bin -> {
                        assertThat(bin.effectiveStatus()).isEqualTo(LocationStatus.INACTIVE);
                        assertThat(bin.usable()).isFalse();
                    });
            assertThat(layout.areas()).filteredOn(a -> a.locationId() != null).hasSize(4)
                    .allSatisfy(a -> assertThat(a.usable()).isFalse());
            assertThat(location("HCM-B01-1-A").usable()).isFalse();
            assertThat(location("HCM-RCV01").effectiveStatus()).isEqualTo(StorageLocationView.Status.INACTIVE);
        }

        @Test
        @DisplayName("an INACTIVE shelf is still on the layout, marked so")
        void inactiveShelvesAreIncluded() {
            shelves.changeShelfStatus(shelfId("B01"), LocationStatus.INACTIVE);
            flushAndClear();

            WarehouseLayout.Shelf b01 = shelf(warehouses.layoutOf(hcm()), "B01");
            assertThat(b01.effectiveStatus()).isEqualTo(LocationStatus.INACTIVE);
            assertThat(bins(b01)).hasSize(4);
        }
    }

    @Nested
    @DisplayName("looking a location up (warehouse.api)")
    class Lookup {

        @Test
        @DisplayName("by code, in any case: hcm-a01-2-b is bin HCM-A01-2-B")
        void byCodeAnyCase() {
            StorageLocationView view = location("hcm-a01-2-b");

            assertThat(view.kind()).isEqualTo(StorageLocationView.Kind.BIN);
            assertThat(view.locationCode()).isEqualTo("HCM-A01-2-B");
            assertThat(view.warehouseId()).isEqualTo(hcm());
            assertThat(view.storageClass()).isEqualTo(StorageLocationView.StorageClass.OVERSIZE);
            assertThat(view.usable()).isTrue();
        }

        @Test
        @DisplayName("HCM-QC01 is the location of the QUARANTINE area")
        void areaByCode() {
            StorageLocationView view = location("HCM-QC01");

            assertThat(view.kind()).isEqualTo(StorageLocationView.Kind.AREA);
            assertThat(area(warehouses.layoutOf(hcm()), "QC01"))
                    .satisfies(qc -> {
                        assertThat(qc.type()).isEqualTo(AreaType.QUARANTINE);
                        assertThat(qc.locationId()).isEqualTo(view.id());
                    });
        }

        @Test
        @DisplayName("the NON_STORAGE OFFICE, a blank code and an unknown code find nothing")
        void notFound() {
            assertThat(locations.findLocation("HCM-OFFICE")).isEmpty();
            assertThat(locations.findLocation(" ")).isEmpty();
            assertThat(locations.findLocation("HCM-Z99-1-A")).isEmpty();
            assertThat(locations.findLocation(UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("by id: the id of HCM-RCV01 finds that row")
        void byId() {
            UUID id = jdbc.queryForObject("select id from warehouse.storage_location where location_code = 'HCM-RCV01'",
                    UUID.class);

            StorageLocationView view = locations.findLocation(id).orElseThrow();

            assertThat(view.locationCode()).isEqualTo("HCM-RCV01");
            assertThat(view.kind()).isEqualTo(StorageLocationView.Kind.AREA);
            assertThat(view.storageClass()).isEqualTo(StorageLocationView.StorageClass.OVERSIZE);
        }
    }

    private static ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }
}
