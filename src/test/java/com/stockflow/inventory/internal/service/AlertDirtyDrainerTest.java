package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.internal.repository.StockAlertDirtyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Stock changes mark SKUs dirty; the drainer evaluates them, retrying a failed batch one by one. */
class AlertDirtyDrainerTest {

    private final StockAlertDirtyRepository dirty = mock(StockAlertDirtyRepository.class);
    private final StockAlertEvaluator evaluator = mock(StockAlertEvaluator.class);
    private final TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private final AlertDirtyDrainer drainer = new AlertDirtyDrainer(dirty, evaluator, tx);

    private static List<String> skus(int count, String prefix) {
        List<String> skus = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            skus.add("%s%04d".formatted(prefix, i));
        }
        return skus;
    }

    @Test
    @DisplayName("an empty queue evaluates nothing")
    void emptyQueue() {
        when(dirty.claim(AlertDirtyDrainer.CLAIM_SIZE)).thenReturn(List.of());

        assertThat(drainer.drain()).isZero();
        verify(evaluator, never()).evaluate(anyList());
    }

    @Test
    @DisplayName("claimed SKUs are evaluated together and counted")
    void evaluatesClaimed() {
        when(dirty.claim(AlertDirtyDrainer.CLAIM_SIZE)).thenReturn(List.of("A", "B", "C"));

        assertThat(drainer.drain()).isEqualTo(3);
        verify(evaluator).evaluate(List.of("A", "B", "C"));
        verify(dirty, times(1)).claim(AlertDirtyDrainer.CLAIM_SIZE);
    }

    @Test
    @DisplayName("a claim longer than 200 is evaluated in batches of 200")
    void evaluationBatches() {
        when(dirty.claim(AlertDirtyDrainer.CLAIM_SIZE)).thenReturn(skus(450, "S"));

        assertThat(drainer.drain()).isEqualTo(450);
        verify(evaluator, times(3)).evaluate(anyList());
    }

    @Test
    @DisplayName("a full claim of 1000 is followed by another, until a short one empties the queue")
    void drainsUntilShortClaim() {
        when(dirty.claim(AlertDirtyDrainer.CLAIM_SIZE)).thenReturn(skus(AlertDirtyDrainer.CLAIM_SIZE, "S"),
                List.of("LAST"));

        assertThat(drainer.drain()).isEqualTo(AlertDirtyDrainer.CLAIM_SIZE + 1);
        verify(dirty, times(2)).claim(AlertDirtyDrainer.CLAIM_SIZE);
    }

    @Test
    @DisplayName("when a batch fails its SKUs are evaluated one by one, and only the ones that still fail go back on the queue")
    void failedBatchIsRetriedOneByOne() {
        when(dirty.claim(AlertDirtyDrainer.CLAIM_SIZE)).thenReturn(List.of("GOOD", "BAD", "ALSO-GOOD"));
        doThrow(new IllegalStateException("boom")).when(evaluator).evaluate(List.of("GOOD", "BAD", "ALSO-GOOD"));
        doThrow(new IllegalStateException("boom")).when(evaluator).evaluate(List.of("BAD"));

        int evaluated = drainer.drain();

        assertThat(evaluated).isEqualTo(2);
        verify(evaluator).evaluate(List.of("GOOD"));
        verify(evaluator).evaluate(List.of("ALSO-GOOD"));
        verify(dirty).mark(List.of("BAD"));
    }

    @Test
    @DisplayName("when every SKU of a failed batch recovers one by one, nothing is queued again")
    void recoveredBatchQueuesNothing() {
        when(dirty.claim(AlertDirtyDrainer.CLAIM_SIZE)).thenReturn(List.of("A", "B"));
        doThrow(new IllegalStateException("batch failed")).when(evaluator).evaluate(List.of("A", "B"));

        assertThat(drainer.drain()).isEqualTo(2);
        verify(dirty, never()).mark(anyList());
    }
}
