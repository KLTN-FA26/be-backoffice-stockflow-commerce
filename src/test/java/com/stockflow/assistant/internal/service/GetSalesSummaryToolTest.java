package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.reporting.api.ReportingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The period of a sales summary (design §4.2): a from..to of the Vietnam calendar, checked against today, or the last
 * N days ending today - so that "yesterday" and "September" are asked as such, never as the nearest rolling window.
 */
class GetSalesSummaryToolTest {

    /** 01:30 on Wednesday 28 October 2026 in Vietnam, still the 27th in UTC. */
    private static final Instant NOW = Instant.parse("2026-10-27T18:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private final ReportingService reports = mock(ReportingService.class);
    private final GetSalesSummaryTool tool = new GetSalesSummaryTool(reports, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void reportsAnswerForThePeriodAsked() {
        when(reports.summary(any(), any())).thenAnswer(call -> summary(call.getArgument(0), call.getArgument(1)));
    }

    private GetSalesSummaryTool.Answer ask(String arguments) {
        return tool.run(ToolArguments.parse(new ObjectMapper(), arguments));
    }

    /** What the reports answer: the period asked for, and the same number of days right before it. */
    private static ReportingService.SalesSummary summary(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        ReportingService.Totals totals = new ReportingService.Totals(2, BigDecimal.valueOf(5_000_000),
                BigDecimal.valueOf(2_500_000));
        return new ReportingService.SalesSummary(from, to, from.minusDays(days), from.minusDays(1), "VND", totals,
                totals, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("yesterday is one whole day, asked by its date and not still running")
    void yesterday() {
        GetSalesSummaryTool.Answer answer = ask("{\"from\":\"2026-10-27\",\"to\":\"2026-10-27\",\"days\":0,\"top\":0}");

        verify(reports).summary(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 27));
        assertThat(answer.from()).isEqualTo(answer.to()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(answer.lastDayInProgress()).isFalse();
        assertThat(answer.companyWide()).isTrue();
    }

    @Test
    @DisplayName("a named month runs from its first to its last day, and days is ignored beside dates")
    void namedMonth() {
        GetSalesSummaryTool.Answer answer = ask("{\"from\":\"2026-09-01\",\"to\":\"2026-09-30\",\"days\":30,"
                + "\"top\":0}");

        verify(reports).summary(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertThat(answer.lastDayInProgress()).isFalse();
        assertThat(answer.previous().from()).as("the previous period is the 30 days before, not August")
                .isEqualTo(LocalDate.of(2026, 8, 2));
    }

    @Test
    @DisplayName("a period that ends today is still running, and today is the Vietnam date, not the server's")
    void endsToday() {
        GetSalesSummaryTool.Answer answer = ask("{\"from\":\"2026-10-26\",\"to\":\"2026-10-28\",\"days\":0,"
                + "\"top\":0}");

        verify(reports).summary(LocalDate.of(2026, 10, 26), TODAY);
        assertThat(answer.lastDayInProgress()).isTrue();
    }

    @Test
    @DisplayName("without dates the last N days end today, as before: older calls without from and to still work")
    void rollingWindow() {
        GetSalesSummaryTool.Answer answer = ask("{\"from\":\"\",\"to\":\"\",\"days\":7,\"top\":0}");
        verify(reports).summary(LocalDate.of(2026, 10, 22), TODAY);
        assertThat(answer.lastDayInProgress()).isTrue();

        ask("{\"days\":30,\"top\":0}");
        verify(reports).summary(LocalDate.of(2026, 9, 29), TODAY);
    }

    @Test
    @DisplayName("the best sellers are asked for the same period")
    void topSkus() {
        when(reports.topProducts(any(), any(), any())).thenReturn(List.of(
                new ReportingService.TopProduct("CUP-12OZ", "Cốc giấy 12oz", 300, BigDecimal.valueOf(9_000_000))));

        GetSalesSummaryTool.Answer answer = ask("{\"from\":\"2026-09-01\",\"to\":\"2026-09-30\",\"days\":0,"
                + "\"top\":3}");

        verify(reports).topProducts(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 3);
        assertThat(answer.topSkus()).extracting(GetSalesSummaryTool.TopSku::sku).containsExactly("CUP-12OZ");
    }

    @Test
    @DisplayName("a period after today, inverted, over 366 days, half given or malformed is refused before any report")
    void refusesBadPeriods() {
        for (String bad : List.of(
                "{\"from\":\"2026-10-26\",\"to\":\"2026-11-01\",\"days\":0,\"top\":0}",
                "{\"from\":\"2026-10-28\",\"to\":\"2026-10-27\",\"days\":0,\"top\":0}",
                "{\"from\":\"2025-10-27\",\"to\":\"2026-10-28\",\"days\":0,\"top\":0}",
                "{\"from\":\"2026-10-01\",\"to\":\"\",\"days\":0,\"top\":0}",
                "{\"from\":\"\",\"to\":\"2026-10-28\",\"days\":0,\"top\":0}",
                "{\"from\":\"01/10/2026\",\"to\":\"2026-10-28\",\"days\":0,\"top\":0}",
                "{\"from\":\"\",\"to\":\"\",\"days\":0,\"top\":0}",
                "{\"from\":\"\",\"to\":\"\",\"days\":367,\"top\":0}")) {
            assertThatThrownBy(() -> ask(bad)).as(bad)
                    .isInstanceOfSatisfying(BusinessException.class,
                            refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        verify(reports, never()).summary(any(), any());
    }

    @Test
    @DisplayName("the refusal of a future end names today, so the model can correct the call")
    void futureEndNamesToday() {
        assertThatThrownBy(() -> ask("{\"from\":\"2026-10-01\",\"to\":\"2026-10-31\",\"days\":0,\"top\":0}"))
                .hasMessageContaining("2026-10-28");
        // 366 days ending today is the longest period, and allowed.
        ask("{\"from\":\"2025-10-28\",\"to\":\"2026-10-28\",\"days\":0,\"top\":0}");
        verify(reports).summary(LocalDate.of(2025, 10, 28), TODAY);
    }
}
