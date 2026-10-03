package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import com.stockflow.warehouse.internal.entity.BinJpaEntity;
import com.stockflow.warehouse.internal.entity.ShelfJpaEntity;
import com.stockflow.warehouse.internal.entity.ShelfLevelJpaEntity;
import com.stockflow.warehouse.internal.entity.WarehouseJpaEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Shelf -> ShelfLevel -> Bin mapping (issue #19): children are saved through the shelf and
 * come back with it.
 *
 * <p>Starting the context is already half the test: the test profile runs Hibernate with
 * {@code ddl-auto: validate}, so every warehouse entity has been checked against the migrated
 * schema before this class runs.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class ShelfJpaRepositoryIntegrationTest {

    @Autowired WarehouseJpaRepository warehouses;
    @Autowired ShelfJpaRepository shelves;
    @Autowired EntityManager entityManager;

    @Test
    @DisplayName("levels and bins are saved through the shelf and loaded back with it")
    void shelfTreeRoundTrips() {
        UUID shelfId = shelves.save(shelfWithOneBin()).getId();

        entityManager.flush();
        entityManager.clear();

        ShelfJpaEntity loaded = shelves.findByIdWithLevels(shelfId).orElseThrow();
        assertThat(loaded.getLevels()).singleElement().satisfies(l -> {
            assertThat(l.getLevelIndex()).isEqualTo(2);
            assertThat(l.getMaxWeight()).isEqualByComparingTo("500");
            assertThat(l.getBins()).singleElement().satisfies(b -> {
                assertThat(b.getLocationCode()).isEqualTo("HN-A01-2-03");
                assertThat(b.getCapacityUnits()).isEqualTo(20);
                assertThat(b.getType()).isEqualTo(BinType.STANDARD);
            });
        });
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
        UUID shelfId = shelves.save(shelfWithOneBin()).getId();
        entityManager.flush();
        entityManager.clear();

        shelves.deleteById(shelfId);

        assertThatThrownBy(shelves::flush)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_shelf_level_shelf");
    }

    private ShelfJpaEntity shelfWithOneBin() {
        WarehouseJpaEntity warehouse = warehouses.save(new WarehouseJpaEntity(
                Identifiers.newId(), "HN", "Hanoi", "1 Test street", null, MapUnit.M,
                new BigDecimal("40"), new BigDecimal("25"), WarehouseStatus.ACTIVE));

        ShelfJpaEntity shelf = new ShelfJpaEntity(Identifiers.newId(), warehouse.getId(), null,
                "A01", "Shelf A01", null, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("3"),
                BigDecimal.ONE, 0, true, true, false, false, false, StorageClass.NORMAL,
                LocationStatus.ACTIVE);
        ShelfLevelJpaEntity level = new ShelfLevelJpaEntity(Identifiers.newId(), 2, null, null,
                new BigDecimal("500"));
        level.addBin(new BinJpaEntity(Identifiers.newId(), "03", "HN-A01-2-03", null,
                new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, 0, 20, true,
                true, BinType.STANDARD, null, LocationStatus.ACTIVE));
        shelf.addLevel(level);
        return shelf;
    }
}
