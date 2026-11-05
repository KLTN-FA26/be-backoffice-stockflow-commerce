package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository.ThresholdRow;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static com.stockflow.inventory.internal.service.InventoryFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The nightly sweep over thresholds: only the low SKUs, plus those with an open alert so it can close. */
class AlertReconcilerTest {

    private final InventoryPolicyRepository policies = mock(InventoryPolicyRepository.class);
    private final StockAlertRepository alerts = mock(StockAlertRepository.class);
    private final StockAlertEvaluator evaluator = mock(StockAlertEvaluator.class);
    private final TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private final AlertReconciler reconciler = new AlertReconciler(policies, alerts, evaluator, tx,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static ThresholdRow row(String sku, long onHand, Integer reorder, Integer safety) {
        return new ThresholdRow(sku, onHand, reorder, safety);
    }

    @SuppressWarnings("unchecked")
    private List<List<String>> evaluatedBatches() {
        ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass(List.class);
        verify(evaluator, org.mockito.Mockito.atLeast(0)).evaluate(batches.capture());
        return batches.getAllValues().stream().map(List::copyOf).toList();
    }

    @Test
    @DisplayName("only low SKUs are evaluated: at or under the reorder point, or below the safety stock")
    void onlyLowSkusAreEvaluated() {
        when(policies.thresholdPage("", AlertReconciler.PAGE_SIZE, TODAY)).thenReturn(List.of(
                row("AT-REORDER", 20, 20, null),
                row("ABOVE", 21, 20, 5),
                row("BELOW-SAFETY", 4, null, 5),
                row("AT-SAFETY", 5, null, 5)));

        int evaluated = reconciler.reconcile();

        assertThat(evaluated).isEqualTo(2);
        assertThat(evaluatedBatches()).containsExactly(List.of("AT-REORDER", "BELOW-SAFETY"));
    }

    @Test
    @DisplayName("a SKU that is fine again but still has an open alert is evaluated too, so the alert can close")
    void openAlertsAreAddedOnce() {
        when(policies.thresholdPage("", AlertReconciler.PAGE_SIZE, TODAY)).thenReturn(List.of(
                row("LOW", 1, 20, null)));
        when(alerts.skusWithOpenStockLevelAlerts()).thenReturn(List.of("LOW", "RECOVERED"));

        assertThat(reconciler.reconcile()).isEqualTo(2);
        assertThat(evaluatedBatches()).containsExactly(List.of("LOW", "RECOVERED"));
    }

    @Test
    @DisplayName("nothing low and nothing open: nothing is evaluated")
    void nothingToDo() {
        assertThat(reconciler.reconcile()).isZero();
        verify(evaluator, never()).evaluate(anyList());
    }

    @Test
    @DisplayName("a full page continues from the last SKU (keyset paging); a short page ends the walk")
    void keysetPaging() {
        List<ThresholdRow> full = new ArrayList<>();
        for (int i = 0; i < AlertReconciler.PAGE_SIZE; i++) {
            full.add(row("S%04d".formatted(i), 100, 20, null));
        }
        when(policies.thresholdPage(eq(""), anyInt(), any())).thenReturn(full);
        when(policies.thresholdPage(eq("S0199"), anyInt(), any())).thenReturn(List.of(row("T0001", 1, 20, null)));

        int evaluated = reconciler.reconcile();

        assertThat(evaluated).isEqualTo(1);
        verify(policies, times(2)).thresholdPage(any(), anyInt(), any());
    }

    @Test
    @DisplayName("a list of SKUs longer than one batch is evaluated in batches of 200")
    void evaluatedInBatches() {
        List<ThresholdRow> page = new ArrayList<>();
        for (int i = 0; i < AlertReconciler.PAGE_SIZE; i++) {
            page.add(row("A%04d".formatted(i), 1, 20, null));
        }
        when(policies.thresholdPage(eq(""), anyInt(), any())).thenReturn(page);
        when(policies.thresholdPage(eq("A0199"), anyInt(), any())).thenReturn(List.of(row("B0000", 1, 20, null),
                row("B0001", 1, 20, null)));

        int evaluated = reconciler.reconcile();

        assertThat(evaluated).isEqualTo(202);
        assertThat(evaluatedBatches()).extracting(List::size).containsExactly(200, 2);
    }
}
