package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import com.stockflow.warehouse.internal.entity.AreaJpaEntity;
import com.stockflow.warehouse.internal.entity.BinJpaEntity;
import com.stockflow.warehouse.internal.entity.ShelfJpaEntity;
import com.stockflow.warehouse.internal.entity.ShelfLevelJpaEntity;
import com.stockflow.warehouse.internal.entity.StorageLocationJpaEntity;
import com.stockflow.warehouse.internal.entity.WarehouseJpaEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The warehouse map entities against the migrated schema ({@code V20260928000100/0200}, issue #19).
 *
 * <p>Starting the context is already half the test: the test profile runs Hibernate with
 * {@code ddl-auto: validate}, so every warehouse entity has been checked against the schema before
 * this class runs. What is left is what validation cannot see - insert order, the triggers, and the
 * demo seed reading back through the mapping. The schema's own CHECKs and triggers are tested in
 * {@code tools/db/qa}, not here.</p>
 *
 * <p>{@code tg_storage_location_owned} is deferred to commit, and these tests roll back, so it
 * would never fire. {@link #checkDeferredConstraints()} runs it on demand instead.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class ShelfJpaRepositoryIntegrationTest {

    @Autowired WarehouseJpaRepository warehouses;
    @Autowired ShelfJpaRepository shelves;
    @Autowired AreaJpaRepository areas;
    @Autowired StorageLocationJpaRepository locations;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("levels, bins and their locations are saved through the shelf and loaded back with it")
    void shelfTreeRoundTrips() {
        UUID shelfId = shelves.save(shelfWithOneBin(warehouse("HN"))).getId();

        // fk_bin_location is not deferrable: this flush fails unless the location goes in first.
        entityManager.flush();
        checkDeferredConstraints();
        entityManager.clear();

        ShelfJpaEntity loaded = shelves.findByIdWithLevels(shelfId).orElseThrow();
        assertThat(loaded.getLevels()).singleElement().satisfies(l -> {
            assertThat(l.getLevelIndex()).isEqualTo(2);
            assertThat(l.getMaxWeight()).isEqualByComparingTo("500");
            assertThat(l.getBins()).singleElement().satisfies(b -> {
                assertThat(b.getCode()).isEqualTo("03");
                assertThat(b.getType()).isEqualTo(BinType.STANDARD);
                assertThat(b.getLocation().getKind()).isEqualTo(StorageLocationKind.BIN);
                assertThat(b.getLocation().getLocationCode()).isEqualTo("HN-A01-2-03");
                assertThat(b.getLocation().getCapacityUnits()).isEqualTo(20);
                assertThat(b.getLocation().isPutawayTarget()).isTrue();
            });
        });
    }

    @Test
    @DisplayName("a storage area's location is saved with it; a NON_STORAGE area has none")
    void areaLocationRoundTrips() {
        WarehouseJpaEntity hanoi = warehouse("HN");
        StorageLocationJpaEntity location = location(hanoi, StorageLocationKind.AREA, "HN-RCV01");
        areas.save(area(hanoi, location, "RCV01", AreaType.RECEIVING));
        UUID officeId = areas.save(area(hanoi, null, "OFFICE", AreaType.NON_STORAGE)).getId();

        entityManager.flush();
        checkDeferredConstraints();
        entityManager.clear();

        assertThat(locations.findByLocationCode("HN-RCV01")).hasValueSatisfying(l ->
                assertThat(l.getKind()).isEqualTo(StorageLocationKind.AREA));
        assertThat(areas.findById(officeId).orElseThrow().getLocation()).isNull();
    }

    /**
     * Until contract C2 the flat model's {@code warehouse.code} is still {@code NOT NULL}; the
     * entity fills it with the prefix, or no warehouse could be created at all.
     */
    @Test
    @DisplayName("a new warehouse fills the legacy code column with its prefix")
    void newWarehouseWritesTheLegacyCode() {
        UUID id = warehouse("HN").getId();
        entityManager.flush();

        assertThat(jdbc.queryForObject("select code from warehouse.warehouse where id = ?",
                String.class, id)).isEqualTo("HN");
    }

    @Test
    @DisplayName("a location owned by no bin and no area is refused at commit")
    void anOrphanLocationIsRefused() {
        entityManager.persist(location(warehouse("HN"), StorageLocationKind.AREA, "HN-LOST01"));
        entityManager.flush();

        assertThatThrownBy(this::checkDeferredConstraints)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("owned by no bin and no area");
    }

    /**
     * Nothing in this aggregate is ever hard-deleted (a location leaves the layout as
     * {@code INACTIVE}). A cascaded {@code REMOVE} would delete the levels and bins first and so get
     * past the foreign key; without it, the database refuses.
     *
     * <p>The session is cleared first so the shelf is loaded fresh, levels not yet initialised -
     * the realistic case. With the levels still in the session, Hibernate itself would refuse the
     * flush before the DELETE reached the database, and the foreign key would go untested.</p>
     */
    @Test
    @DisplayName("deleting a shelf that still has levels is refused by the database")
    void deletingAShelfWithLevelsIsRefused() {
        UUID shelfId = shelves.save(shelfWithOneBin(warehouse("HN"))).getId();
        entityManager.flush();
        entityManager.clear();

        shelves.deleteById(shelfId);

        assertThatThrownBy(shelves::flush)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_shelf_level_shelf");
    }

    /** The demo seed ({@code db/demo/V20260928009000}) is what the frontend draws first. */
    @Test
    @DisplayName("the demo warehouse HCM reads back through the mapping")
    void demoWarehouseReadsBack() {
        ShelfJpaEntity a01 = shelves.findByIdWithLevels(DemoData.shelf("A01")).orElseThrow();
        assertThat(a01.getWarehouseId()).isEqualTo(DemoData.WAREHOUSE_HCM);
        assertThat(a01.getCode()).isEqualTo("A01");
        assertThat(a01.getZoneId()).isEqualTo(DemoData.ZONE_HCM_A);
        assertThat(a01.getLevels()).hasSize(2);
        assertThat(a01.getLevels().get(0).getBins())
                .extracting(b -> b.getLocation().getLocationCode())
                .containsExactly("HCM-A01-1-A", "HCM-A01-1-B");
        assertThat(locations.findByLocationCode("HCM-QC01")).hasValueSatisfying(l ->
                assertThat(l.getKind()).isEqualTo(StorageLocationKind.AREA));
    }

    // ---- fixtures -------------------------------------------------------------------------

    /** Runs the deferred triggers now instead of at a commit these tests never reach. */
    private void checkDeferredConstraints() {
        jdbc.execute("set constraints all immediate");
    }

    private WarehouseJpaEntity warehouse(String prefix) {
        return warehouses.save(new WarehouseJpaEntity(Identifiers.newId(), prefix, "Test " + prefix,
                "1 Test street", null, MapUnit.M, new BigDecimal("40"), new BigDecimal("25"),
                WarehouseStatus.ACTIVE));
    }

    private StorageLocationJpaEntity location(WarehouseJpaEntity warehouse, StorageLocationKind kind,
                                              String code) {
        return new StorageLocationJpaEntity(Identifiers.newId(), warehouse.getId(), kind, code,
                StorageClass.NORMAL, 20, null, true, true, LocationStatus.ACTIVE);
    }

    private ShelfJpaEntity shelfWithOneBin(WarehouseJpaEntity warehouse) {
        ShelfJpaEntity shelf = new ShelfJpaEntity(Identifiers.newId(), warehouse.getId(), null,
                "A01", "Shelf A01", null, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("3"),
                BigDecimal.ONE, 0, true, true, false, false, false, StorageClass.NORMAL,
                LocationStatus.ACTIVE);
        ShelfLevelJpaEntity level = new ShelfLevelJpaEntity(Identifiers.newId(), 2, null, null,
                new BigDecimal("500"));
        level.addBin(new BinJpaEntity(Identifiers.newId(),
                location(warehouse, StorageLocationKind.BIN, "HN-A01-2-03"), "03", null,
                new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, 0,
                BinType.STANDARD, null));
        shelf.addLevel(level);
        return shelf;
    }

    private AreaJpaEntity area(WarehouseJpaEntity warehouse, StorageLocationJpaEntity location,
                               String code, AreaType type) {
        return new AreaJpaEntity(Identifiers.newId(), warehouse.getId(), location, code, type,
                "Area " + code, new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("3"),
                new BigDecimal("3"), 0, false, LocationStatus.ACTIVE);
    }
}
