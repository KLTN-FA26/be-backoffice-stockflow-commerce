package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.internal.domain.BinDetails;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LevelMeasures;
import com.stockflow.warehouse.internal.domain.LocationSettings;
import com.stockflow.warehouse.internal.domain.Bin;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.PickFaces;
import com.stockflow.warehouse.internal.domain.Shelf;
import com.stockflow.warehouse.internal.domain.ShelfLevel;
import com.stockflow.warehouse.internal.domain.ShelfRepository;
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
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shelves through the application service, on Postgres with the demo seed: warehouse {@code HCM}
 * (map 60 x 40) with shelves A01 (5, 5), A02 (5, 9) and B01 (5, 14), each 10 x 1.2 with levels 1-2
 * and bins A (x 0-5) and B (x 5-10) on every level, and areas RCV01 (40-55, 2-10), QC01, PACK01,
 * DSP01 and OFFICE. Every test rolls back.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class ShelfLayoutServiceIntegrationTest {

    @Autowired ShelfLayoutService shelves;
    @Autowired WarehouseLayoutService warehouses;
    @Autowired ShelfRepository shelfRepository;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;

    private static Footprint at(String x, String y, String width, String length) {
        return at(x, y, width, length, 0);
    }

    private static Footprint at(String x, String y, String width, String length, int rotation) {
        return new Footprint(new BigDecimal(x), new BigDecimal(y), new BigDecimal(width), new BigDecimal(length),
                rotation);
    }

    private ShelfSummary create(String code, Footprint footprint) {
        return create(WAREHOUSE_HCM, null, code, footprint);
    }

    private ShelfSummary create(UUID warehouseId, UUID zoneId, String code, Footprint footprint) {
        return shelves.createShelf(new ShelfCommands.CreateShelf(warehouseId, zoneId, code, "Kệ " + code, null,
                footprint, true, new PickFaces(false, false, true, false), StorageClass.NORMAL));
    }

    private static BinDetails binAt(Footprint footprint) {
        return new BinDetails(null, footprint, BinType.PALLET, null);
    }

    private static final LocationSettings PICKABLE = new LocationSettings(40, new BigDecimal("250"), true, true);

    private static ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }

    @Nested
    @DisplayName("placing a shelf on the map (BR-06, BR-07)")
    class Placing {

        @Test
        @DisplayName("a shelf on free floor is created ACTIVE with an upper-cased code")
        void createsOnFreeFloor() {
            ShelfSummary shelf = create("c01", at("20", "5", "10", "1.2"));

            assertThat(shelf.code()).isEqualTo("C01");
            assertThat(shelf.status()).isEqualTo(LocationStatus.ACTIVE);
            assertThat(shelf.levels()).isEmpty();
        }

        @Test
        @DisplayName("a code already used in the warehouse is refused")
        void refusesDuplicateCode() {
            assertThat(errorOf(() -> create("a01", at("20", "5", "10", "1.2"))))
                    .isEqualTo(ErrorCode.SHELF_CODE_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("overlapping a shelf is refused; standing back to back with it is not")
        void overlapAgainstShelves() {
            assertThat(errorOf(() -> create("X01", at("14", "5", "4", "1"))))
                    .isEqualTo(ErrorCode.LAYOUT_OVERLAP);
            assertThat(create("X02", at("5", "6.2", "10", "1.2")).code()).isEqualTo("X02");
        }

        @Test
        @DisplayName("overlapping an area is refused")
        void overlapAgainstAreas() {
            assertThat(errorOf(() -> create("X01", at("45", "3", "2", "2")))).isEqualTo(ErrorCode.LAYOUT_OVERLAP);
        }

        @Test
        @DisplayName("a quarter-turned shelf occupies its turned footprint")
        void rotationIsRespected() {
            create("R01", at("20", "0", "10", "1.2", 90));

            assertThat(errorOf(() -> create("X01", at("20.5", "9", "0.5", "0.5"))))
                    .isEqualTo(ErrorCode.LAYOUT_OVERLAP);
        }

        @Test
        @DisplayName("sticking out of the map is refused")
        void mustStayOnTheMap() {
            assertThat(errorOf(() -> create("X01", at("55", "39", "10", "1.2"))))
                    .isEqualTo(ErrorCode.LAYOUT_OUT_OF_BOUNDS);
        }

        @Test
        @DisplayName("a zone of another warehouse is not found")
        void zoneMustBeOfTheSameWarehouse() {
            UUID hanoi = warehouses.register(new RegisterWarehouseCommand("HN", "Kho Hà Nội", "1 Phố Huế", null,
                    MapUnit.M, new BigDecimal("40"), new BigDecimal("25"))).id();
            UUID hanoiZone = warehouses.createZone(new CreateZoneCommand(hanoi, "Khu A", null)).id();

            assertThat(errorOf(() -> create(WAREHOUSE_HCM, hanoiZone, "X01", at("20", "5", "10", "1.2"))))
                    .isEqualTo(ErrorCode.ZONE_NOT_FOUND);
        }

        @Test
        @DisplayName("an INACTIVE shelf frees its place, and cannot come back while it is taken (#18 D3)")
        void inactiveShelvesFreeTheirPlace() {
            UUID a02 = DemoData.shelf("A02");
            shelves.changeShelfStatus(a02, LocationStatus.INACTIVE);

            create("X01", at("5", "9", "10", "1.2"));

            assertThat(errorOf(() -> shelves.changeShelfStatus(a02, LocationStatus.ACTIVE)))
                    .isEqualTo(ErrorCode.LAYOUT_OVERLAP);
        }
    }

    @Nested
    @DisplayName("levels and bins")
    class LevelsAndBins {

        /**
         * The seeded A01 already has four bins. Adding a level and a bin, then saving, must update the
         * existing rows in place and insert the new ones location-first - and the bin's location must
         * be owned by the time the transaction would commit.
         */
        @Test
        @DisplayName("a new level and bin on a shelf that already has bins")
        void growsAnExistingTree() {
            UUID a01 = DemoData.shelf("A01");

            ShelfSummary.Level level = shelves.addLevel(new ShelfCommands.AddLevel(a01, 3,
                    new LevelMeasures(new BigDecimal("3"), new BigDecimal("1.4"), new BigDecimal("500"))));
            ShelfSummary.BinEntry bin = shelves.addBin(new ShelfCommands.AddBin(a01, level.id(), "01",
                    binAt(at("0", "0", "5", "1.2")), PICKABLE));
            entityManager.flush();
            jdbc.execute("set constraints all immediate");

            assertThat(bin.locationCode()).isEqualTo("HCM-A01-3-01");
            assertThat(bin.storageClass()).isEqualTo(StorageClass.OVERSIZE);
            entityManager.clear();
            ShelfSummary reloaded = shelves.getShelf(a01);
            assertThat(reloaded.levels()).extracting(ShelfSummary.Level::levelIndex).containsExactly(1, 2, 3);
            assertThat(reloaded.levels().get(0).bins()).extracting(ShelfSummary.BinEntry::code)
                    .containsExactly("A", "B");
            assertThat(reloaded.levels().get(2).bins()).singleElement()
                    .extracting(ShelfSummary.BinEntry::locationCode).isEqualTo("HCM-A01-3-01");
        }

        @Test
        @DisplayName("a bin code is unique on its level and a bin may not overlap its neighbours")
        void binRules() {
            UUID a01 = DemoData.shelf("A01");
            UUID level1 = shelves.getShelf(a01).levels().get(0).id();

            assertThat(errorOf(() -> shelves.addBin(new ShelfCommands.AddBin(a01, level1, "a",
                    binAt(at("0", "0", "1", "1")), PICKABLE)))).isEqualTo(ErrorCode.BIN_CODE_ALREADY_EXISTS);
            assertThat(errorOf(() -> shelves.addBin(new ShelfCommands.AddBin(a01, level1, "C",
                    binAt(at("2", "0", "1", "1.2")), PICKABLE)))).isEqualTo(ErrorCode.LAYOUT_OVERLAP);
            assertThat(errorOf(() -> shelves.addBin(new ShelfCommands.AddBin(a01, Identifiers.newId(), "C",
                    binAt(at("0", "0", "1", "1")), PICKABLE)))).isEqualTo(ErrorCode.SHELF_LEVEL_NOT_FOUND);
        }

        @Test
        @DisplayName("a bin's status is its location's status")
        void binStatusIsTheLocationStatus() {
            UUID a01 = DemoData.shelf("A01");
            ShelfSummary.Level level1 = shelves.getShelf(a01).levels().get(0);

            shelves.changeBinStatus(a01, level1.id(), level1.bins().get(0).id(), LocationStatus.BLOCKED);
            entityManager.flush();

            assertThat(jdbc.queryForObject("select status from warehouse.storage_location where location_code = ?",
                    String.class, "HCM-A01-1-A")).isEqualTo("BLOCKED");
        }

        @Test
        @DisplayName("a new default class reaches every bin of the shelf in the database (#18 D9)")
        void defaultClassReachesTheLocations() {
            ShelfSummary a01 = shelves.getShelf(DemoData.shelf("A01"));

            ShelfSummary updated = shelves.updateShelf(new ShelfCommands.UpdateShelf(a01.id(), a01.zoneId(),
                    a01.name(), a01.description(), a01.footprint(), a01.obstacle(), a01.pickFaces(),
                    StorageClass.FRAGILE, a01.version()));
            entityManager.flush();

            assertThat(updated.version()).isEqualTo(a01.version() + 1);
            assertThat(jdbc.queryForObject("select count(*) from warehouse.storage_location "
                    + "where location_code like 'HCM-A01-%' and storage_class = 'FRAGILE'", Integer.class))
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("an unknown shelf is not found")
        void unknownShelf() {
            assertThat(errorOf(() -> shelves.getShelf(Identifiers.newId()))).isEqualTo(ErrorCode.SHELF_NOT_FOUND);
            assertThat(errorOf(() -> shelves.addLevel(new ShelfCommands.AddLevel(Identifiers.newId(), 1,
                    LevelMeasures.NONE)))).isEqualTo(ErrorCode.SHELF_NOT_FOUND);
        }
    }

    /**
     * Loading a shelf must cost the same number of queries for 30 bins as for 150: the tree is read
     * with one fetch join, not one query per level or per bin. Also saves both trees fresh, which
     * puts 180 bins and 180 locations through batched inserts in one flush.
     */
    @Test
    @DisplayName("loading a shelf costs the same number of queries whatever its number of bins")
    void loadingCostsAFixedNumberOfQueries() {
        UUID small = shelfRepository.save(treeOf("Q10", 10)).id();
        UUID large = shelfRepository.save(treeOf("Q50", 50)).id();
        entityManager.flush();
        jdbc.execute("set constraints all immediate");

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        entityManager.clear();
        statistics.clear();
        assertThat(shelfRepository.findById(small).orElseThrow().levels().get(2).bins()).hasSize(10);
        long smallQueries = statistics.getPrepareStatementCount();

        entityManager.clear();
        statistics.clear();
        assertThat(shelfRepository.findById(large).orElseThrow().levels().get(2).bins()).hasSize(50);
        long largeQueries = statistics.getPrepareStatementCount();

        assertThat(largeQueries).isEqualTo(smallQueries).isEqualTo(2);
    }

    /**
     * Saving an edited shelf reads its tree once, to merge onto: the shelf it returns is built from
     * the rows it just wrote, not read back with a second fetch join over every bin.
     */
    @Test
    @DisplayName("saving an edited shelf runs the tree query once")
    void savingReadsTheTreeOnce() {
        UUID id = shelfRepository.save(treeOf("S50", 50)).id();
        entityManager.flush();
        jdbc.execute("set constraints all immediate");
        entityManager.clear();

        Shelf shelf = shelfRepository.findById(id).orElseThrow();
        ShelfLevel level = shelf.levels().get(1);
        Bin bin = level.bins().get(7);
        shelf.changeBinStatus(level.id(), bin.id(), LocationStatus.BLOCKED);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        Shelf saved = shelfRepository.save(shelf);
        long treeQueries = statistics.getQueryExecutionCount();
        long lazyLoads = statistics.getCollectionFetchCount();
        statistics.setStatisticsEnabled(false);

        assertThat(treeQueries).isEqualTo(1);
        // The shelf's own level list, so new levels can be appended to it; no level's bins.
        assertThat(lazyLoads).isEqualTo(1);
        assertThat(saved.levels()).extracting(ShelfLevel::levelIndex).containsExactly(1, 2, 3);
        assertThat(saved.level(level.id()).bin(bin.id()).location().status()).isEqualTo(LocationStatus.BLOCKED);
        assertThat(saved.level(level.id()).bins()).hasSize(50);
    }

    /** Three levels of {@code binsPerLevel} 1 x 1 bins in a row, built through the aggregate. */
    private Shelf treeOf(String code, int binsPerLevel) {
        Shelf shelf = Shelf.create(Identifiers.newId(), WAREHOUSE_HCM, null, code, "Kệ " + code, null,
                at("0", "0", String.valueOf(binsPerLevel), "1"), true, new PickFaces(true, false, false, false),
                StorageClass.NORMAL);
        for (int index = 1; index <= 3; index++) {
            ShelfLevel level = shelf.addLevel(Identifiers.newId(), index, LevelMeasures.NONE);
            for (int i = 0; i < binsPerLevel; i++) {
                shelf.addBin(level.id(), Identifiers.newId(), Identifiers.newId(), "B" + i,
                        binAt(at(String.valueOf(i), "0", "1", "1")), PICKABLE, "HCM");
            }
        }
        return shelf;
    }
}
