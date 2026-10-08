package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SCRUM-326/327: the transfer order state machine, without Spring. */
class TransferOrderTest {

    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();
    private static final UUID PLANNER = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");

    private static TransferLine line(int no, String sku, int qty) {
        return new TransferLine(UUID.randomUUID(), no, new Sku(sku), null, qty, 0);
    }

    private static TransferOrder draft(int... quantities) {
        List<TransferLine> lines = new java.util.ArrayList<>();
        for (int i = 0; i < quantities.length; i++) {
            lines.add(line(i + 1, "CUP-PP-50" + i, quantities[i]));
        }
        return TransferOrder.draft(UUID.randomUUID(), "TO-20261008-0001", HCM, HN, TransferReason.REBALANCING,
                null, null, lines, NOW);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("at or under the threshold the submitter approves in one step; above it someone else must")
    void threshold() {
        TransferOrder small = draft(40, 60);
        small.submit(PLANNER, 100, NOW);
        assertThat(small.status()).isEqualTo(TransferStatus.APPROVED);
        assertThat(small.approvedBy()).isEqualTo(PLANNER);
        assertThat(small.submittedBy()).isNull();

        TransferOrder big = draft(101);
        big.submit(PLANNER, 100, NOW);
        assertThat(big.status()).isEqualTo(TransferStatus.PENDING_APPROVAL);
        assertThatThrownBy(() -> big.approve(PLANNER, NOW)).extracting(TransferOrderTest::code)
                .isEqualTo(ErrorCode.TRANSFER_SELF_APPROVAL);
        big.approve(MANAGER, NOW);
        assertThat(big.status()).isEqualTo(TransferStatus.APPROVED);
    }

    @Test
    @DisplayName("rejection goes back to DRAFT and the order can be submitted again")
    void rejectAndResubmit() {
        TransferOrder order = draft(500);
        order.submit(PLANNER, 100, NOW);
        order.reject(MANAGER);
        assertThat(order.status()).isEqualTo(TransferStatus.DRAFT);
        assertThat(order.submittedBy()).isNull();
        order.submit(PLANNER, 100, NOW);
        assertThat(order.status()).isEqualTo(TransferStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("dispatch needs PICKING, ships 0..requested per line and at least one unit")
    void dispatch() {
        TransferOrder order = draft(10, 5);
        order.submit(PLANNER, 100, NOW);
        UUID first = order.lines().get(0).id();
        assertThatThrownBy(() -> order.dispatch(PLANNER, Map.of(first, 1), NOW))
                .extracting(TransferOrderTest::code).isEqualTo(ErrorCode.INVALID_TRANSFER_TRANSITION);

        order.startPicking();
        assertThatThrownBy(() -> order.dispatch(PLANNER, Map.of(first, 11), NOW))
                .extracting(TransferOrderTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> order.dispatch(PLANNER, Map.of(), NOW))
                .extracting(TransferOrderTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> order.dispatch(PLANNER, Map.of(UUID.randomUUID(), 1), NOW))
                .extracting(TransferOrderTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);

        order.dispatch(PLANNER, Map.of(first, 8), NOW);
        assertThat(order.status()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(order.lines()).extracting(TransferLine::shipped).containsExactly(8, 0);
        assertThat(order.dispatchedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("cancel only before dispatch, with a reason; picking can be stopped first")
    void cancel() {
        TransferOrder order = draft(10);
        order.submit(PLANNER, 100, NOW);
        order.startPicking();
        assertThatThrownBy(() -> order.cancel("Changed plan")).extracting(TransferOrderTest::code)
                .isEqualTo(ErrorCode.INVALID_TRANSFER_TRANSITION);
        order.cancelPicking();
        assertThatThrownBy(() -> order.cancel(" ")).extracting(TransferOrderTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        order.cancel("Changed plan");
        assertThat(order.status()).isEqualTo(TransferStatus.CANCELLED);

        TransferOrder gone = draft(10);
        gone.submit(PLANNER, 100, NOW);
        gone.startPicking();
        gone.dispatch(PLANNER, Map.of(gone.lines().get(0).id(), 10), NOW);
        assertThatThrownBy(() -> gone.cancel("too late")).extracting(TransferOrderTest::code)
                .isEqualTo(ErrorCode.INVALID_TRANSFER_TRANSITION);
    }

    @Test
    @DisplayName("same warehouse, no lines or a repeated SKU are refused")
    void shape() {
        assertThatThrownBy(() -> TransferOrder.draft(UUID.randomUUID(), "TO-1", HCM, HCM, null, null, null,
                List.of(line(1, "CUP-PP-500", 1)), NOW)).extracting(TransferOrderTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> TransferOrder.draft(UUID.randomUUID(), "TO-1", HCM, HN, null, null, null,
                List.of(), NOW)).extracting(TransferOrderTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> TransferOrder.draft(UUID.randomUUID(), "TO-1", HCM, HN, null, null, null,
                List.of(line(1, "CUP-PP-500", 1), line(2, "CUP-PP-500", 2)), NOW))
                .extracting(TransferOrderTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
