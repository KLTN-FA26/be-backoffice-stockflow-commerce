package com.stockflow.procurement.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.SettleSubcontractCommand;
import com.stockflow.procurement.api.SubcontractSettlement;
import com.stockflow.procurement.internal.repository.SubcontractOrderStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BR-PRD-17 and decision QA1 with the store mocked: the subcontractor's tolerance is a share of the blanks sent,
 * rounded down; units failing QC are not paid; a loss above tolerance waits for an approval that names the agreed
 * deduction per blank AND its reason, and is then deducted once. The CHECK constraints that say the same are tested
 * against PostgreSQL in {@code SubcontractPurchasingIntegrationTest}.
 */
class SubcontractSettlementTest {

    private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");

    private final SubcontractOrderStore store = mock(SubcontractOrderStore.class);
    private final SubcontractPurchasingImpl purchasing = new SubcontractPurchasingImpl(store, mock(InventoryService.class),
            mock(InventoryControlService.class), mock(ProcurementService.class), Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID po = UUID.randomUUID();
    private final UUID productionOrder = UUID.randomUUID();
    private final UUID supplier = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();
    private final AtomicReference<SubcontractSettlement> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(store.findById(eq(po), anyBoolean())).thenReturn(Optional.of(new SubcontractOrderStore.Row(po, "SPO-1",
                productionOrder, supplier, UUID.randomUUID(), "RECEIVED", UUID.randomUUID(), new BigDecimal("1000.000"),
                new BigDecimal("1500.00"), "VND", new BigDecimal("1500000"), LocalDate.of(2026, 10, 20))));
        when(store.supplier(supplier)).thenReturn(Optional.of(new SubcontractOrderStore.Supplier(supplier, "INNHANH",
                "In Nhanh", true, true, new BigDecimal("2.00"), 30, 7)));
        when(store.settlement(eq(po), anyBoolean())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        doAnswer(call -> {
            stored.set(call.getArgument(0));
            return null;
        }).when(store).insertSettlement(any());
        doAnswer(call -> {
            SubcontractSettlement s = stored.get();
            stored.set(new SubcontractSettlement(s.purchaseOrderId(), s.productionOrderId(), "FINAL", s.currency(),
                    s.unitPrice(), s.receivedQuantity(), s.passedQuantity(), s.failedQuantity(), s.payableAmount(),
                    s.blanksSent(), s.blanksUsed(), s.blanksReturned(), s.blanksLost(), s.tolerancePercent(),
                    s.toleranceBlanks(), s.excessLoss(), call.getArgument(1), call.getArgument(2), s.cancellationFee(),
                    s.cancellationFeeReason(), call.getArgument(3), s.settledAt(), s.settledBy(), call.getArgument(6),
                    call.getArgument(5), call.getArgument(4)));
            return null;
        }).when(store).approveSettlement(eq(po), any(), any(), any(), any(), any(), any());
    }

    /** The Q9 example: 1,000 blanks sent, 950 good printed, 30 spoilt, 20 returned; QC fails 10 of the 950. */
    private SubcontractSettlement settleExample() {
        return purchasing.settle(new SettleSubcontractCommand(po, productionOrder, 950, 940, 10, 1000, 950, 20, 30, null,
                null, approver));
    }

    @Test
    void qcFailedUnitsAreNotPaidAndALossAboveToleranceWaitsForApproval() {
        SubcontractSettlement settled = settleExample();

        assertThat(settled.toleranceBlanks()).as("2 % of 1,000 sent").isEqualTo(20);
        assertThat(settled.excessLoss()).as("30 lost, 20 tolerated").isEqualTo(10);
        assertThat(settled.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(settled.payableAmount()).as("940 passed x 1,500; the 10 failed are not paid")
                .isEqualByComparingTo("1410000");
        assertThat(settled.deductionAmount()).isZero();
        assertThat(settled.netPayable()).isEqualByComparingTo("1410000");
        assertThat(settleExample()).as("settled once").isSameAs(settled);
        verify(store).insertSettlement(any());
    }

    @Test
    void theApproverEntersTheAgreedDeductionAndWhy() {
        settleExample();
        assertThatThrownBy(() -> purchasing.approveLoss(po, new BigDecimal("800"), " ", approver))
                .as("QA1: a reason is required").extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> purchasing.approveLoss(po, new BigDecimal("800.001"), "agreed", approver))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(store, never()).approveSettlement(any(), any(), any(), any(), any(), any(), any());

        SubcontractSettlement approved = purchasing.approveLoss(po, new BigDecimal("800"), "agreed by phone", approver);

        ArgumentCaptor<BigDecimal> deduction = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> net = ArgumentCaptor.forClass(BigDecimal.class);
        verify(store).approveSettlement(eq(po), eq(new BigDecimal("800.00")), deduction.capture(), net.capture(),
                eq("agreed by phone"), eq(approver), any());
        assertThat(deduction.getValue()).as("10 x 800").isEqualByComparingTo("8000");
        assertThat(net.getValue()).as("1,410,000 - 8,000").isEqualByComparingTo("1402000");
        assertThat(approved.status()).isEqualTo("FINAL");
        assertThat(purchasing.approveLoss(po, new BigDecimal("800.00"), "again", approver)).as("a replay")
                .isEqualTo(approved);
        assertThatThrownBy(() -> purchasing.approveLoss(po, new BigDecimal("900"), "more", approver))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void aLossWithinToleranceIsFinalAtOnceAndAFeeNeedsItsReason() {
        SubcontractSettlement settled = purchasing.settle(new SettleSubcontractCommand(po, productionOrder, 400, 400, 0,
                1000, 400, 580, 20, new BigDecimal("50000"), "setup paid", approver));
        assertThat(settled.excessLoss()).isZero();
        assertThat(settled.status()).isEqualTo("FINAL");
        assertThat(settled.netPayable()).as("400 x 1,500 + the agreed fee").isEqualByComparingTo("650000");
        assertThatThrownBy(() -> purchasing.approveLoss(po, BigDecimal.ONE, "nothing to approve", approver))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);

        stored.set(null);
        assertThatThrownBy(() -> purchasing.settle(new SettleSubcontractCommand(po, productionOrder, 1, 1, 0, 0, 0, 0, 0,
                new BigDecimal("10"), " ", approver))).extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
