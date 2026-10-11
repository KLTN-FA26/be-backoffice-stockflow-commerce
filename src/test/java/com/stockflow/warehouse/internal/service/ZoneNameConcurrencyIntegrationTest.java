package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * Zone names are unique per warehouse ignoring case, and {@code uk_zone_warehouse_name} is not: two
 * requests for "Race" and "race" at the same moment would both pass the check and both commit,
 * unless the warehouse row lock makes the second read the first's zone (issue #18 D4). The race
 * window is short, so it is run several times.
 *
 * <p>Not {@code @Transactional}, for the reason {@link ShelfPlacementConcurrencyIntegrationTest}
 * gives: both transactions must really commit, and the clean-up runs in a transaction of its own.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class ZoneNameConcurrencyIntegrationTest {

    private static final int ROUNDS = 10;

    @Autowired WarehouseLayoutService layout;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void removeTheZones() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                jdbc.update("delete from warehouse.zone where lower(name) like 'race %'"));
    }

    @Test
    @DisplayName("of two zones whose names differ only in case, created at once, exactly one is created")
    void onlyOneOfTwoSameNamedZonesWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<ErrorCode>> outcomes = new ArrayList<>();
                for (String name : List.of("Race " + round, "RACE " + round)) {
                    outcomes.add(pool.submit(() -> {
                        start.await();
                        try {
                            layout.createZone(new CreateZoneCommand(WAREHOUSE_HCM, name, null));
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
                assertThat(results).as("round %d", round)
                        .containsExactlyInAnyOrder(null, ErrorCode.ZONE_NAME_ALREADY_EXISTS);
            }
            assertThat(jdbc.queryForObject("select count(*) from warehouse.zone where lower(name) like 'race %'",
                    Integer.class)).isEqualTo(ROUNDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
