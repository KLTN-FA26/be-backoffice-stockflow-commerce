package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.PickFaces;
import com.stockflow.warehouse.internal.domain.StorageClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * BR-07 under concurrency: two people placing overlapping shelves at the same moment. Each check
 * alone passes - neither sees the other's shelf - unless the warehouse row lock makes the second
 * wait for the first to commit and then read it (issue #18 D4). No database constraint backs this
 * rule, so the lock is the whole defence and this test is what proves it.
 *
 * <p>Not {@code @Transactional}: both transactions must really commit. The shelves are deleted
 * afterwards (they have no levels, so the delete is allowed), in a transaction of their own: the pool
 * hands out connections with auto-commit off, so a bare {@code JdbcTemplate} delete is rolled back
 * when its connection goes back to the pool. It was, until the layout read of #25 found shelf T1
 * still standing in HCM whenever this test happened to run first.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class ShelfPlacementConcurrencyIntegrationTest {

    @Autowired ShelfLayoutService shelves;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void removeTheShelves() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                jdbc.update("delete from warehouse.shelf where code in ('T1', 'T2')"));
    }

    @Test
    @DisplayName("of two overlapping shelves placed at once, exactly one is created")
    void onlyOneOfTwoOverlappingShelvesWins() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ErrorCode>> outcomes = new ArrayList<>();
            for (String code : List.of("T1", "T2")) {
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        shelves.createShelf(new ShelfCommands.CreateShelf(WAREHOUSE_HCM, null, code,
                                "Kệ " + code, null,
                                new Footprint(new BigDecimal("20"), new BigDecimal("20"), new BigDecimal("5"),
                                        BigDecimal.ONE, 0),
                                true, PickFaces.NONE, StorageClass.NORMAL));
                        return null;
                    } catch (BusinessException e) {
                        return e.errorCode();
                    }
                }));
            }
            start.countDown();

            List<ErrorCode> results = new ArrayList<>();
            for (Future<ErrorCode> outcome : outcomes) {
                results.add(outcome.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).containsExactlyInAnyOrder(null, ErrorCode.LAYOUT_OVERLAP);
            assertThat(jdbc.queryForObject("select count(*) from warehouse.shelf where code in ('T1', 'T2')",
                    Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
