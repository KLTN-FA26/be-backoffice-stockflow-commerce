package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.internal.domain.BinDefaults;
import com.stockflow.warehouse.internal.domain.BinNamingScheme;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LevelMeasures;
import com.stockflow.warehouse.internal.domain.LocationSettings;
import com.stockflow.warehouse.internal.domain.PickFaces;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.entity.BinJpaEntity;
import com.stockflow.warehouse.internal.entity.StorageLocationJpaEntity;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bin generation (BR-14) through the service, <b>committed for real</b>: {@code tg_storage_location_owned}
 * is deferred to commit, so only a commit proves every generated location is owned by its bin, and
 * {@code fk_bin_location} is not deferrable, so only a real insert proves each location went in
 * ahead of its bin while the inserts were batched and reordered.
 *
 * <p>Not {@code @Transactional}. Shelf G1 stands on free floor of the demo warehouse HCM
 * (15-35 x 36-37, clear of the office at 2-12 x 30-38) and is removed after each test, bins first,
 * then their locations - no trigger guards a delete. The cleanup runs in a transaction of its own:
 * the pool hands out connections with auto-commit off, so a bare {@code JdbcTemplate} delete would be
 * rolled back when its connection goes back to the pool.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class BinGenerationIntegrationTest {

    private static final BinDefaults DEFAULTS = new BinDefaults(BinType.SMALL_PARTS, null,
            new LocationSettings(10, new BigDecimal("20"), true, true));

    @Autowired ShelfLayoutService shelves;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private UUID shelfId;
    private UUID levelId;

    @BeforeEach
    void placeAnEmptyShelf() {
        UUID hcm = jdbc.queryForObject("select id from warehouse.warehouse where prefix = 'HCM'", UUID.class);
        shelfId = shelves.createShelf(new ShelfCommands.CreateShelf(hcm, null, "G1", "Kệ G1", null,
                new Footprint(new BigDecimal("15"), new BigDecimal("36"), new BigDecimal("20"), BigDecimal.ONE, 0),
                true, new PickFaces(true, false, false, false), StorageClass.NORMAL)).id();
        levelId = shelves.addLevel(new ShelfCommands.AddLevel(shelfId, 1, LevelMeasures.NONE)).id();
    }

    @AfterEach
    void removeTheShelf() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("delete from warehouse.bin where level_id in "
                    + "(select id from warehouse.shelf_level where shelf_id = ?)", shelfId);
            jdbc.update("delete from warehouse.storage_location where location_code like 'HCM-G1-%'");
            jdbc.update("delete from warehouse.shelf_level where shelf_id = ?", shelfId);
            jdbc.update("delete from warehouse.shelf where id = ?", shelfId);
        });
    }

    private List<ShelfSummary.Level> generate(int rows, int columns) {
        return shelves.generateBins(new ShelfCommands.GenerateBins(shelfId, List.of(levelId), rows, columns,
                BinNamingScheme.SEQUENTIAL, DEFAULTS));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class, shelfId);
    }

    private int binRows() {
        return count("select count(*) from warehouse.bin b join warehouse.shelf_level l on l.id = b.level_id "
                + "where l.shelf_id = ?");
    }

    private int ownedLocationRows() {
        return count("select count(*) from warehouse.storage_location s join warehouse.bin b on b.location_id = s.id "
                + "join warehouse.shelf_level l on l.id = b.level_id where l.shelf_id = ? and s.kind = 'BIN'");
    }

    /**
     * 400 rows in one flush. Unbatched, that is 400 prepared statements; batched by 50 with the
     * inserts ordered, it is a handful - and the commit succeeding means every location preceded its bin.
     * Inserts are counted per entity: the audit entry {@code generateBins} writes is an insert too.
     */
    @Test
    @DisplayName("1 x 200 on one level commits 200 bins and 200 owned locations, inserted in batches")
    void twoHundredBinsCommit() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        List<ShelfSummary.Level> filled;
        try {
            filled = generate(1, 200);
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        assertThat(statistics.getEntityStatistics(BinJpaEntity.class.getName()).getInsertCount()).isEqualTo(200);
        assertThat(statistics.getEntityStatistics(StorageLocationJpaEntity.class.getName()).getInsertCount())
                .isEqualTo(200);
        assertThat(statistics.getPrepareStatementCount()).isLessThan(40);
        assertThat(filled).singleElement().satisfies(level -> {
            assertThat(level.id()).isEqualTo(levelId);
            assertThat(level.bins()).hasSize(200);
            assertThat(level.bins().get(0).locationCode()).isEqualTo("HCM-G1-1-001");
            assertThat(level.bins().get(199).locationCode()).isEqualTo("HCM-G1-1-200");
        });
        assertThat(binRows()).isEqualTo(200);
        assertThat(ownedLocationRows()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(distinct location_code) from warehouse.storage_location "
                + "where location_code like 'HCM-G1-1-%' and status = 'ACTIVE' and storage_class = 'NORMAL'",
                Integer.class)).isEqualTo(200);
    }

    @Test
    @DisplayName("1 x 201 is refused and writes nothing")
    void twoHundredAndOneIsRefused() {
        assertThat(errorOf(() -> generate(1, 201))).isEqualTo(ErrorCode.SHELF_CAPACITY_EXCEEDED);
        assertThat(binRows()).isZero();
    }

    /** What a double click without an {@code Idempotency-Key} gets the second time. */
    @Test
    @DisplayName("generating again on a level just filled is refused")
    void secondRequestIsRefused() {
        generate(2, 3);

        assertThat(errorOf(() -> generate(2, 3))).isEqualTo(ErrorCode.SHELF_LEVEL_HAS_BINS);
        assertThat(binRows()).isEqualTo(6);
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
