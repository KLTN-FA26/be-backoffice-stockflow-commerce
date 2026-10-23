package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.CycleCount.CountLineDraft;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CycleCountTest {

    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final Instant T1 = Instant.parse("2026-10-23T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-23T09:00:00Z");

    private final UUID counter = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private static CountSlot slot(String location) {
        return CountSlot.loose(new LocationId(location), new Sku("CUP-250"), null);
    }

    private static CycleCount planOne(String note, UUID assignee) {
        return CycleCount.plan("CC-20261023-0001", WAREHOUSE, CycleCountTrigger.MANUAL, true, assignee,
                LocalDate.of(2026, 10, 23), note, List.of(new CountLineDraft(slot("HN-A01-1-01"), 10)));
    }

    private static void assertCode(Throwable t, ErrorCode code) {
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).errorCode()).isEqualTo(code);
    }

    @Test
    void planRefusesAnEmptyScope() {
        assertThatThrownBy(() -> CycleCount.plan("CC-1", WAREHOUSE, CycleCountTrigger.MANUAL, false, null,
                LocalDate.of(2026, 10, 23), null, List.of()))
                .satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_EMPTY_SCOPE));
    }

    @Test
    void planRefusesTheSameSlotTwice() {
        assertThatThrownBy(() -> CycleCount.plan("CC-1", WAREHOUSE, CycleCountTrigger.MANUAL, false, null,
                LocalDate.of(2026, 10, 23), null,
                List.of(new CountLineDraft(slot("HN-A01-1-01"), 1), new CountLineDraft(slot("hn-a01-1-01"), 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firstCountStartsTheCountAndOverwritesTheBook() {
        CycleCount count = planOne(null, null);
        CycleCountLine line = count.getLines().get(0);

        count.count(line.id(), 7, 9, counter, T1);

        assertThat(count.getStatus()).isEqualTo(CycleCountStatus.IN_PROGRESS);
        assertThat(count.getStartedAt()).isEqualTo(T1);
        assertThat(line.expectedQty()).isEqualTo(9);
        assertThat(line.delta()).isEqualTo(-2);
    }

    @Test
    void recountingALineOverwritesEverything() {
        CycleCount count = planOne(null, null);
        CycleCountLine line = count.getLines().get(0);
        count.count(line.id(), 7, 9, counter, T1);

        count.count(line.id(), 9, 9, other, T2);

        assertThat(line.countedBy()).isEqualTo(other);
        assertThat(line.countedAt()).isEqualTo(T2);
        assertThat(line.delta()).isZero();
        assertThat(count.getStartedAt()).isEqualTo(T1);
    }

    @Test
    void countingRefusesAnUnknownLineAndANegativeQuantity() {
        CycleCount count = planOne(null, null);
        assertThatThrownBy(() -> count.count(UUID.randomUUID(), 1, 1, counter, T1))
                .satisfies(t -> assertCode(t, ErrorCode.NOT_FOUND));
        UUID lineId = count.getLines().get(0).id();
        assertThatThrownBy(() -> count.count(lineId, -1, 1, counter, T1))
                .satisfies(t -> assertCode(t, ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void submitNeedsEveryLineCounted() {
        CycleCount count = planOne(null, null);
        assertThatThrownBy(() -> count.submit(T2))
                .satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_LINE_NOT_COUNTED));
    }

    @Test
    void submitIsIdempotentOnTheAggregate() {
        CycleCount count = planOne(null, null);
        count.count(count.getLines().get(0).id(), 10, 10, counter, T1);

        assertThat(count.submit(T2)).isTrue();
        assertThat(count.submit(T2)).isFalse();
        assertThat(count.getStatus()).isEqualTo(CycleCountStatus.COMPLETED);
        assertThat(count.getCompletedAt()).isEqualTo(T2);
    }

    @Test
    void cancelledCountRefusesCounting() {
        CycleCount count = planOne(null, null);
        count.cancel("Not needed", T1);
        UUID lineId = count.getLines().get(0).id();

        assertThatThrownBy(() -> count.count(lineId, 1, 1, counter, T2))
                .satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_NOT_OPEN));
        assertThatThrownBy(() -> count.submit(T2)).satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_NOT_OPEN));
    }

    @Test
    void cancelAppendsTheReason() {
        CycleCount withNote = planOne("found 1", null);
        assertThat(withNote.cancel("Bin checked", T1)).isTrue();
        assertThat(withNote.getNote()).isEqualTo("found 1 | Cancelled: Bin checked");
        assertThat(withNote.cancel("again", T2)).isFalse();

        CycleCount noNote = planOne(null, null);
        noNote.cancel("Bin checked", T1);
        assertThat(noNote.getNote()).isEqualTo("Cancelled: Bin checked");

        CycleCount longNote = planOne("x".repeat(1000), null);
        longNote.cancel("Bin checked", T1);
        assertThat(longNote.getNote()).hasSize(1000);
    }

    @Test
    void cancelRefusesACompletedCount() {
        CycleCount count = planOne(null, null);
        count.count(count.getLines().get(0).id(), 10, 10, counter, T1);
        count.submit(T2);
        assertThatThrownBy(() -> count.cancel("late", T2))
                .satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_NOT_OPEN));
    }

    @Test
    void onlyTheAssigneeCountsUnlessTheyMayDecide() {
        CycleCount count = planOne(null, counter);

        assertThatThrownBy(() -> count.requireCounter(other, false))
                .satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_NOT_ASSIGNEE));
        count.requireCounter(counter, false);
        count.requireCounter(other, true);
    }

    @Test
    void addLineRefusesASlotAlreadyOnTheCount() {
        CycleCount count = planOne(null, null);

        assertThatThrownBy(() -> count.addLine(slot("HN-A01-1-01"), 3, 3, counter, T1))
                .satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_SLOT_BUSY));

        CycleCountLine added = count.addLine(slot("HN-A01-1-02"), 3, 0, counter, T1);
        assertThat(count.getLines()).hasSize(2);
        assertThat(added.delta()).isEqualTo(3);
    }

    @Test
    void assignOnlyWhilePlanned() {
        CycleCount count = planOne(null, null);
        count.assign(counter);
        assertThat(count.getAssignedUserId()).isEqualTo(counter);

        count.count(count.getLines().get(0).id(), 10, 10, counter, T1);
        assertThatThrownBy(() -> count.assign(other)).satisfies(t -> assertCode(t, ErrorCode.CONFLICT));
    }

    @Test
    void markRecountOnlyOnACompletedCount() {
        CycleCount count = planOne(null, null);
        UUID lineId = count.getLines().get(0).id();
        assertThatThrownBy(() -> count.markRecount(List.of(lineId)))
                .satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_NOT_OPEN));

        count.count(lineId, 10, 10, counter, T1);
        count.submit(T2);
        List<CountSlot> slots = count.markRecount(List.of(lineId));

        assertThat(slots).containsExactly(slot("HN-A01-1-01"));
        assertThat(count.getLines().get(0).recountRequired()).isTrue();
    }

    @Test
    void shortPickIsBlindPlannedAndUnassigned() {
        CycleCount count = CycleCount.shortPick("CC-1", WAREHOUSE, slot("HN-A01-1-01"), 4, "short",
                LocalDate.of(2026, 10, 23));
        assertThat(count.getTrigger()).isEqualTo(CycleCountTrigger.SHORT_PICK);
        assertThat(count.isBlind()).isTrue();
        assertThat(count.getStatus()).isEqualTo(CycleCountStatus.PLANNED);
        assertThat(count.getAssignedUserId()).isNull();
    }
}
