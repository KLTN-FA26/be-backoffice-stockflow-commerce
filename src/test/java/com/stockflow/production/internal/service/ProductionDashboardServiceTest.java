package com.stockflow.production.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.production.internal.repository.ProductionDashboardQueries;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Decision QA3 with the queries mocked: the period is an activity period by default — the business day's bounds in
 * Asia/Ho_Chi_Minh go to the activity query, never to the creation-cohort one — and the cohort stays available when
 * asked for. The counting itself is SQL, tested against PostgreSQL in {@code PrintRunIntegrationTest}.
 */
class ProductionDashboardServiceTest {

    /** 2026-10-14 01:00 in Ho Chi Minh City: still 2026-10-13 in UTC. */
    private static final Instant NOW = Instant.parse("2026-10-13T18:00:00Z");

    private final ProductionDashboardQueries queries = mock(ProductionDashboardQueries.class);
    private final ProductionDashboardService service = new ProductionDashboardService(queries,
            mock(ProductionOrders.class), mock(SampleRequests.class), Clock.fixed(NOW, ZoneOffset.UTC));
    private final CurrentUser manager = new CurrentUser(UUID.randomUUID(), "manager", Set.of(Role.WAREHOUSE_MANAGER),
            Set.of(), DataScope.ALL, Set.of());

    @Test
    void theDefaultPeriodIsActivityOverTheBusinessDays() {
        when(queries.printedAndScrapInPeriod(isNull(), any(), any())).thenReturn(new long[] {8, 2});
        when(queries.openByStatus(isNull())).thenReturn(Map.of("QC", 1L));
        LocalDate day = LocalDate.of(2026, 10, 14);

        ProductionDashboard.Summary summary = service.summary(manager, day, day);

        assertThat(summary.periodBasis()).isEqualTo(ProductionDashboard.ACTIVITY_IN_PERIOD);
        assertThat(summary.today()).as("the business date").isEqualTo(day);
        assertThat(summary.printed()).isEqualTo(8);
        assertThat(summary.scrap()).isEqualTo(2);
        assertThat(summary.scrapRate()).isEqualByComparingTo("0.2500");
        verify(queries).printedAndScrapInPeriod(null, Instant.parse("2026-10-13T17:00:00Z"),
                Instant.parse("2026-10-14T17:00:00Z"));
        verify(queries, never()).printedAndScrap(any(), any(), any());
    }

    @Test
    void theCreationCohortIsStillThereWhenAskedFor() {
        when(queries.printedAndScrap(isNull(), isNull(), isNull())).thenReturn(new long[] {0, 0});

        ProductionDashboard.Summary summary = service.summary(manager, null, null, ProductionDashboard.Basis.CREATED);

        assertThat(summary.periodBasis()).isEqualTo(ProductionDashboard.CREATED_IN_PERIOD);
        assertThat(summary.scrapRate()).as("nothing printed: no rate").isNull();
        verify(queries, never()).printedAndScrapInPeriod(any(), any(), any());
    }

    @Test
    void aPeriodEndingBeforeItStartsIsRefused() {
        assertThatThrownBy(() -> service.summary(manager, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 13)))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
