package com.stockflow.order.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Review of G3-2: the nightly settlement drains batch after batch, and stops when a batch moves nothing. */
class OrderSettlementJobTest {

    private static List<UUID> ids(int n) {
        return IntStream.range(0, n).mapToObj(i -> UUID.randomUUID()).toList();
    }

    @Test
    @DisplayName("200 + 200 + 50 candidates: every one is completed in one run")
    void drainsEveryBatch() {
        OrderSettlementService settlements = mock(OrderSettlementService.class);
        when(settlements.completionCandidates(OrderSettlementJob.BATCH_SIZE))
                .thenReturn(ids(200), ids(200), ids(50));
        when(settlements.closingCandidates(OrderSettlementJob.BATCH_SIZE)).thenReturn(List.of());
        when(settlements.complete(any())).thenReturn(true);

        new OrderSettlementJob(settlements).settle();

        verify(settlements, times(450)).complete(any());
        verify(settlements, times(3)).completionCandidates(OrderSettlementJob.BATCH_SIZE);
    }

    @Test
    @DisplayName("a full batch in which nothing moves ends the run instead of looping on the same orders")
    void stopsWhenNothingMoves() {
        OrderSettlementService settlements = mock(OrderSettlementService.class);
        List<UUID> stuck = ids(200);
        when(settlements.completionCandidates(OrderSettlementJob.BATCH_SIZE)).thenReturn(stuck);
        when(settlements.closingCandidates(OrderSettlementJob.BATCH_SIZE)).thenReturn(List.of());
        when(settlements.complete(any())).thenThrow(new IllegalStateException("broken row"));

        new OrderSettlementJob(settlements).settle();

        verify(settlements, times(1)).completionCandidates(OrderSettlementJob.BATCH_SIZE);
        verify(settlements, times(200)).complete(any());
    }
}
