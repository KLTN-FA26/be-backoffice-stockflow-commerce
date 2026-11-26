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

    /** 20 BR-12: a count holds at most 500 cartons. */
    private static final int MAX = 500;

    /** A carton at the bin; one fixed carton per bin keeps the helper deterministic. */
    private static CountSlot slot(String location) {
        return CountSlot.carton(new LocationId(location), new Sku("CUP-250"), "L1",
                new StockPackageId(UUID.nameUUIDFromBytes(location.toUpperCase().getBytes())));
    }

    private static CycleCount planOne(String note, UUID assignee) {
        return CycleCount.plan("CC-20261023-0001", WAREHOUSE, CycleCountTrigger.MANUAL, true, assignee,
                LocalDate.of(2026, 10, 23), note, List.of(new CountLineDraft(slot("HN-A01-1-01"), true)));
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
                List.of(new CountLineDraft(slot("HN-A01-1-01"), true), new CountLineDraft(slot("hn-a01-1-01"), true))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneCartonIsOneSlotWhateverBinItIsRecordedAt() {
        StockPackageId carton = new StockPackageId(UUID.randomUUID());
        CountSlot here = CountSlot.carton(new LocationId("HN-A01-1-01"), new Sku("CUP-250"), "L1", carton);
        CountSlot moved = CountSlot.carton(new LocationId("HN-A01-1-02"), new Sku("CUP-250"), "L1", carton);

        assertThatThrownBy(() -> CycleCount.plan("CC-1", WAREHOUSE, CycleCountTrigger.MANUAL, false, null,
                LocalDate.of(2026, 10, 23), null, List.of(new CountLineDraft(here, true), new CountLineDraft(moved, true))))
                .as("the claim and the advisory lock key a carton by its package alone")
                .isInstanceOf(IllegalArgumentException.class);
        CycleCount count = CycleCount.plan("CC-1", WAREHOUSE, CycleCountTrigger.MANUAL, false, null,
                LocalDate.of(2026, 10, 23), null, List.of(new CountLineDraft(here, true)));
        assertThatThrownBy(() -> count.addLine(moved, true, counter, T1, MAX))
                .satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_SLOT_BUSY));
    }

    @Test
    void firstCountStartsTheCountAndOverwritesTheBook() {
        CycleCount count = planOne(null, null);
        CycleCountLine line = count.lines().get(0);
        assertThat(line.booked()).isTrue();
        assertThat(line.found()).isNull();

        // planned as booked, but the books no longer have it at the bin when it is scanned (BR-09): booked is re-read
        count.count(line.id(), true, false, counter, T1);

        assertThat(count.status()).isEqualTo(CycleCountStatus.IN_PROGRESS);
        assertThat(count.startedAt()).isEqualTo(T1);
        assertThat(line.booked()).isFalse();
        assertThat(line.found()).isTrue();
        assertThat(line.variance()).as("found where the books do not have it: a surplus")
                .contains(AdjustmentDirection.RESTOCK);
    }

    @Test
    void aCartonNotFoundIsAShortageOfOneWholeCarton() {
        CycleCount count = planOne(null, null);
        CycleCountLine line = count.lines().get(0);

        count.count(line.id(), false, true, counter, T1);

        assertThat(line.booked()).isTrue();
        assertThat(line.found()).isFalse();
        assertThat(line.variance()).contains(AdjustmentDirection.WRITE_OFF);
    }

    @Test
    void recountingALineOverwritesEverything() {
        CycleCount count = planOne(null, null);
        CycleCountLine line = count.lines().get(0);
        count.count(line.id(), false, true, counter, T1);

        count.count(line.id(), true, true, other, T2);

        assertThat(line.countedBy()).isEqualTo(other);
        assertThat(line.countedAt()).isEqualTo(T2);
        assertThat(line.found()).isTrue();
        assertThat(line.variance()).isEmpty();
        assertThat(count.startedAt()).isEqualTo(T1);
    }

    @Test
    void countingRefusesAnUnknownLine() {
        CycleCount count = planOne(null, null);
        assertThatThrownBy(() -> count.count(UUID.randomUUID(), true, true, counter, T1))
                .satisfies(t -> assertCode(t, ErrorCode.NOT_FOUND));
    }

    @Test
    void anUncountedLineHasNoVariance() {
        CycleCountLine line = planOne(null, null).lines().get(0);
        assertThat(line.isCounted()).isFalse();
        assertThat(line.variance()).isEmpty();
    }

    @Test
    void aCountLineNamesACartonNeverLooseStock() {
        CountSlot loose = new CountSlot(new LocationId("HN-A01-1-01"), new Sku("CUP-250"), null, null);
        assertThat(loose.isCarton()).isFalse();
        assertThatThrownBy(() -> CycleCount.plan("CC-1", WAREHOUSE, CycleCountTrigger.MANUAL, false, null,
                LocalDate.of(2026, 10, 23), null, List.of(new CountLineDraft(loose, true))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLooseLineCannotEvenBeReadBack() {
        // cycle_count_line.stock_package_id is NOT NULL since V20261231000100: no stored row is loose
        CountSlot loose = new CountSlot(new LocationId("HN-A01-1-01"), new Sku("CUP-250"), null, null);
        assertThatThrownBy(() -> new CycleCountLine(UUID.randomUUID(), loose, true, false, counter, T1, false))
                .isInstanceOf(IllegalArgumentException.class);
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
        count.count(count.lines().get(0).id(), true, true, counter, T1);

        assertThat(count.submit(T2)).isTrue();
        assertThat(count.submit(T2)).isFalse();
        assertThat(count.status()).isEqualTo(CycleCountStatus.COMPLETED);
        assertThat(count.completedAt()).isEqualTo(T2);
    }

    @Test
    void cancelledCountRefusesCounting() {
        CycleCount count = planOne(null, null);
        count.cancel("Not needed", T1);
        UUID lineId = count.lines().get(0).id();

        assertThatThrownBy(() -> count.count(lineId, true, true, counter, T2))
                .satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_NOT_OPEN));
        assertThatThrownBy(() -> count.submit(T2)).satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_NOT_OPEN));
    }

    @Test
    void cancelAppendsTheReason() {
        CycleCount withNote = planOne("found 1", null);
        assertThat(withNote.cancel("Bin checked", T1)).isTrue();
        assertThat(withNote.note()).isEqualTo("found 1 | Cancelled: Bin checked");
        assertThat(withNote.cancel("again", T2)).isFalse();

        CycleCount noNote = planOne(null, null);
        noNote.cancel("Bin checked", T1);
        assertThat(noNote.note()).isEqualTo("Cancelled: Bin checked");

        CycleCount longNote = planOne("x".repeat(1000), null);
        longNote.cancel("Bin checked", T1);
        assertThat(longNote.note()).hasSize(1000);
    }

    @Test
    void cancelRefusesACompletedCount() {
        CycleCount count = planOne(null, null);
        count.count(count.lines().get(0).id(), true, true, counter, T1);
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

        assertThatThrownBy(() -> count.addLine(slot("HN-A01-1-01"), true, counter, T1, MAX))
                .satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_SLOT_BUSY));

        // a LOST carton found again: the books do not have it, the counter does (a surplus of one carton)
        CycleCountLine added = count.addLine(slot("HN-A01-1-02"), false, counter, T1, MAX);
        assertThat(count.lines()).hasSize(2);
        assertThat(added.found()).isTrue();
        assertThat(added.booked()).isFalse();
        assertThat(added.variance()).contains(AdjustmentDirection.RESTOCK);
        // a carton the books do have, scanned although the plan missed it: present on both sides, no variance
        CycleCountLine known = count.addLine(slot("HN-A01-1-03"), true, counter, T1, MAX);
        assertThat(known.variance()).isEmpty();
    }

    @Test
    void aFullCountTakesNoMoreLines() {
        CycleCount count = planOne(null, null);

        assertThatThrownBy(() -> count.addLine(slot("HN-A01-1-02"), true, counter, T1, 1))
                .as("the plan already filled a count of one line")
                .satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_TOO_LARGE));
        assertThat(count.lines()).hasSize(1);

        count.addLine(slot("HN-A01-1-02"), true, counter, T1, 2);
        assertThat(count.lines()).hasSize(2);
        assertThatThrownBy(() -> count.addLine(slot("HN-A01-1-03"), true, counter, T1, 2))
                .satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_TOO_LARGE));
        assertThat(count.lines()).hasSize(2);
    }

    @Test
    void assignOnlyWhilePlanned() {
        CycleCount count = planOne(null, null);
        count.assign(counter);
        assertThat(count.assignedUserId()).isEqualTo(counter);

        count.count(count.lines().get(0).id(), true, true, counter, T1);
        assertThatThrownBy(() -> count.assign(other)).satisfies(t -> assertCode(t, ErrorCode.CONFLICT));
    }

    @Test
    void markRecountOnlyOnACompletedCount() {
        CycleCount count = planOne(null, null);
        UUID lineId = count.lines().get(0).id();
        assertThatThrownBy(() -> count.markRecount(List.of(lineId)))
                .satisfies(t -> assertCode(t, ErrorCode.CYCLE_COUNT_NOT_OPEN));

        count.count(lineId, true, true, counter, T1);
        count.submit(T2);
        List<CountSlot> slots = count.markRecount(List.of(lineId));

        assertThat(slots).containsExactly(slot("HN-A01-1-01"));
        assertThat(count.lines().get(0).recountRequired()).isTrue();
    }

    @Test
    void aLineIsSentForRecountOnce() {
        CycleCount count = planOne(null, null);
        UUID lineId = count.lines().get(0).id();
        count.count(lineId, true, true, counter, T1);
        count.submit(T2);
        count.markRecount(List.of(lineId));

        assertThatThrownBy(() -> count.markRecount(List.of(lineId)))
                .satisfies(t -> assertCode(t, ErrorCode.CONFLICT))
                .hasMessageContaining(lineId.toString());
    }

    @Test
    void shortPickIsBlindPlannedAndUnassigned() {
        CycleCount count = CycleCount.shortPick("CC-1", WAREHOUSE, slot("HN-A01-1-01"), true, "short",
                LocalDate.of(2026, 10, 23));
        assertThat(count.trigger()).isEqualTo(CycleCountTrigger.SHORT_PICK);
        assertThat(count.blind()).isTrue();
        assertThat(count.status()).isEqualTo(CycleCountStatus.PLANNED);
        assertThat(count.assignedUserId()).isNull();
    }
}
