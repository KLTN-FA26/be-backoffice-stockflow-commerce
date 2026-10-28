package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** SCRUM-277 as plain rules: a line found partly or wholly damaged gives way to the lines that pick what is left. */
class PickListDamageTest {

    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();
    private static final UUID REPORT = UUID.randomUUID();

    private static ErrorCode refusal(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static PickLine open(String location, int quantity) {
        return new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP", location, "L1", 1, quantity,
                0, PickLineStatus.OPEN, null, null, null, null, null, 0, null, null, null);
    }

    private static PickList list(PickStatus status, PickLine... lines) {
        return new PickList(UUID.randomUUID(), HCM, status, false, List.of(lines));
    }

    @Test
    @DisplayName("a damage report needs a picking task, an open line, its bin and 1 to the allocated units")
    void refusals() {
        PickLine line = open("HCM-A01-1-A", 5);
        PickList picking = list(PickStatus.PICKING, line);

        assertThat(refusal(() -> picking.requireDamageable(line.id(), "HCM-A01-1-B", 1)))
                .isEqualTo(ErrorCode.PICK_LOCATION_MISMATCH);
        assertThat(refusal(() -> picking.requireDamageable(line.id(), "HCM-A01-1-A", 0)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> picking.requireDamageable(line.id(), "HCM-A01-1-A", 6)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> picking.requireDamageable(UUID.randomUUID(), "HCM-A01-1-A", 1)))
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(refusal(() -> list(PickStatus.PENDING, line).requireDamageable(line.id(), "HCM-A01-1-A", 1)))
                .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        PickLine picked = new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP", "HCM-A01-1-A",
                "L1", 1, 2, 2, PickLineStatus.PICKED, UUID.randomUUID(), java.time.Instant.now(),
                java.time.Instant.now(), "HCM-A01-1-A", "CUP", 0, null, null, null);
        assertThat(refusal(() -> list(PickStatus.PICKING, picked).requireDamageable(picked.id(), "HCM-A01-1-A", 1)))
                .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
        assertThat(picking.requireDamageable(line.id(), "hcm-a01-1-a", 5)).as("not reported yet").isFalse();
    }

    @Test
    @DisplayName("partly damaged and replaced: good units at the bin, replacements after the last stop")
    void partlyDamagedReplaced() {
        PickLine line = open("HCM-A01-1-A", 5);
        PickLine other = open("HCM-B01-1-A", 1);
        PickList list = list(PickStatus.PICKING, line, other);
        UUID replacement = UUID.randomUUID();

        List<PickLine> added = list.replaceDamaged(line.id(), 2, REPORT, 3, List.of(new PickList.Hold(replacement,
                "CUP", "HCM-A02-1-A", HCM, "L2", 2, true)), null, UUID::randomUUID);

        assertThat(line.status()).isEqualTo(PickLineStatus.CANCELLED);
        assertThat(line.damagedQty()).isEqualTo(2);
        assertThat(line.damageReportId()).isEqualTo(REPORT);
        assertThat(added).hasSize(2);
        assertThat(added.get(0)).satisfies(good -> {
            assertThat(good.reservationId()).isEqualTo(line.reservationId());
            assertThat(good.locationCode()).isEqualTo("HCM-A01-1-A");
            assertThat(good.allocatedQty()).isEqualTo(3);
            assertThat(good.status()).isEqualTo(PickLineStatus.OPEN);
        });
        assertThat(added.get(1)).satisfies(moved -> {
            assertThat(moved.reservationId()).isEqualTo(replacement);
            assertThat(moved.locationCode()).isEqualTo("HCM-A02-1-A");
            assertThat(moved.lotNumber()).isEqualTo("L2");
            assertThat(moved.status()).isEqualTo(PickLineStatus.OPEN);
        });
        assertThat(added).allSatisfy(made -> {
            assertThat(made.damageReportId()).isEqualTo(REPORT);
            assertThat(made.orderLineId()).isEqualTo(line.orderLineId());
            assertThat(made.sequenceNo()).isGreaterThan(other.sequenceNo());
        });
        assertThat(list.hasShortLines()).isFalse();
        assertThat(list.madeByDamage(REPORT)).containsExactlyElementsOf(added);
        // The same report again is recognised from the line itself.
        assertThat(list.requireDamageable(line.id(), "HCM-A01-1-A", 2)).isTrue();
        assertThat(refusal(() -> list.requireDamageable(line.id(), "HCM-A01-1-A", 3)))
                .as("another quantity on a superseded line").isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
    }

    @Test
    @DisplayName("nothing to replace them: a SHORT line for the damaged units waits for the coordinator")
    void damagedWithoutReplacement() {
        PickLine line = open("HCM-A01-1-A", 4);
        PickList list = list(PickStatus.PICKING, line);
        UUID impaired = UUID.randomUUID();

        List<PickLine> added = list.replaceDamaged(line.id(), 1, REPORT, 3, List.of(), impaired, UUID::randomUUID);

        assertThat(added).hasSize(2);
        PickLine waiting = added.get(1);
        assertThat(waiting.status()).isEqualTo(PickLineStatus.SHORT);
        assertThat(waiting.reservationId()).isEqualTo(impaired);
        assertThat(waiting.shortQty()).isEqualTo(1);
        assertThat(waiting.allocatedQty()).isEqualTo(1);
        assertThat(waiting.locationCode()).isEqualTo("HCM-A01-1-A");
        assertThat(list.hasShortLines()).isTrue();
        assertThat(refusal(list::requireAllPicked)).isEqualTo(ErrorCode.PICK_LINES_INCOMPLETE);
        // The coordinator's reallocation supersedes it like any short line (X-SHORT).
        List<PickLine> reallocated = list.replaceShort(waiting.id(), List.of(new PickList.Hold(UUID.randomUUID(), "CUP",
                "HCM-A02-1-A", HCM, "L2", 1, true)), UUID::randomUUID);
        assertThat(reallocated).hasSize(1);
        assertThat(list.hasShortLines()).isFalse();
    }

    @Test
    @DisplayName("all of it damaged: no line at the bin, the replacement elsewhere picks everything")
    void allDamaged() {
        PickLine line = open("HCM-A01-1-A", 2);
        PickList list = list(PickStatus.PICKING, line);

        List<PickLine> added = list.replaceDamaged(line.id(), 2, REPORT, 0, List.of(new PickList.Hold(UUID.randomUUID(),
                "CUP", "HCM-A02-1-A", HCM, "L2", 2, true)), null, UUID::randomUUID);

        assertThat(added).singleElement().satisfies(moved -> assertThat(moved.locationCode()).isEqualTo("HCM-A02-1-A"));
        assertThat(line.damagedQty()).isEqualTo(2);
    }

    @Test
    @DisplayName("a replacement outside the task's warehouse, or both a replacement and an impaired share, is refused")
    void inconsistentOutcomes() {
        PickLine line = open("HCM-A01-1-A", 2);
        PickList list = list(PickStatus.PICKING, line);

        assertThat(refusal(() -> list.replaceDamaged(line.id(), 1, REPORT, 1, List.of(new PickList.Hold(
                UUID.randomUUID(), "CUP", "HN-A01-1-A", HN, null, 1, true)), null, UUID::randomUUID)))
                .isEqualTo(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE);
        assertThatThrownBy(() -> list.replaceDamaged(line.id(), 1, REPORT, 1, List.of(), null, UUID::randomUUID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(line.status()).as("nothing changed").isEqualTo(PickLineStatus.OPEN);
    }

    @Test
    @DisplayName("a line drawn from a carton: the good units stay in that carton, no longer whole; a replacement names "
            + "its own carton; the impaired share is loose in quarantine (SCRUM-473)")
    void damageKeepsTheCartons() {
        PickLine carton = new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP", "HCM-A01-1-A",
                "L1", 1, 5, 0, PickLineStatus.OPEN, null, null, null, null, null, 0, null, null, null, 0, "LPN-1",
                true);
        PickList replaced = list(PickStatus.PICKING, carton);
        List<PickLine> added = replaced.replaceDamaged(carton.id(), 2, REPORT, 3, List.of(new PickList.Hold(
                UUID.randomUUID(), "CUP", "HCM-A02-1-A", HCM, "L2", 2, true, "LPN-2", true)), null, UUID::randomUUID);
        assertThat(added).extracting(PickLine::lpn, PickLine::wholeCarton)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("LPN-1", false),
                        org.assertj.core.groups.Tuple.tuple("LPN-2", true));

        PickLine other = new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP", "HCM-A01-1-A",
                "L1", 1, 2, 0, PickLineStatus.OPEN, null, null, null, null, null, 0, null, null, null, 0, "LPN-3",
                false);
        List<PickLine> impaired = list(PickStatus.PICKING, other).replaceDamaged(other.id(), 2, REPORT, 0, List.of(),
                UUID.randomUUID(), UUID::randomUUID);
        assertThat(impaired).singleElement().satisfies(line -> {
            assertThat(line.status()).isEqualTo(PickLineStatus.SHORT);
            assertThat(line.lpn()).isNull();
            assertThat(line.wholeCarton()).isFalse();
        });
    }

    @Test
    @DisplayName("only a superseded line carries damaged units, and never more than it was allocated")
    void rehydrationRule() {
        assertThatThrownBy(() -> new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP",
                "HCM-A01-1-A", null, 1, 2, 0, PickLineStatus.OPEN, null, null, null, null, null, 0, null, null, null, 0,
                null, false, 1, REPORT, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP",
                "HCM-A01-1-A", null, 1, 2, 0, PickLineStatus.CANCELLED, null, null, null, null, null, 0, null, null,
                null, 0, null, false, 3, REPORT, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- damage found at packing (R8) ---------------------------------------------------------------------------

    private static PickLine picked(int quantity) {
        return new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP", "HCM-A01-1-A", "L1", 1,
                quantity, quantity, PickLineStatus.PICKED, UUID.randomUUID(), java.time.Instant.now(),
                java.time.Instant.now(), "HCM-A01-1-A", "CUP", 0, null, null, null);
    }

    @Test
    @DisplayName("damage at packing: a picked task, a picked line, 1 to what it still has; never twice the same units")
    void afterPickRefusals() {
        PickLine line = picked(3);
        PickList packing = list(PickStatus.PICKED, line);

        assertThat(refusal(() -> list(PickStatus.CANCELLED, line).requireDamageableAfterPick(line.id(), 1)))
                .as("a cancelled order's goods are put back, then reported on the shelf")
                .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        list(PickStatus.PICKING, line).requireDamageableAfterPick(line.id(), 1);   // in the tote, still picking
        assertThat(refusal(() -> packing.requireDamageableAfterPick(line.id(), 0)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> packing.requireDamageableAfterPick(line.id(), 4)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        PickLine notPicked = open("HCM-A01-1-A", 2);
        assertThat(refusal(() -> list(PickStatus.PICKED, notPicked).requireDamageableAfterPick(notPicked.id(), 1)))
                .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);

        packing.replaceDamagedAfterPick(line.id(), 2, REPORT, List.of(new PickList.Hold(UUID.randomUUID(), "CUP",
                "HCM-A02-1-A", HCM, "L2", 2, true)), null, UUID::randomUUID);
        assertThat(line.packingDamagedQty()).isEqualTo(2);
        assertThat(line.goodQty()).isEqualTo(1);
        assertThat(refusal(() -> packing.requireDamageableAfterPick(line.id(), 2)))
                .as("two of the three are in quarantine already").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("damage at packing replaced: the picked line keeps them on record, new OPEN lines after the last stop")
    void afterPickReplaced() {
        PickLine line = picked(3);
        PickLine other = picked(1);
        PickList packing = list(PickStatus.PICKED, line, other);
        UUID replacement = UUID.randomUUID();

        List<PickLine> added = packing.replaceDamagedAfterPick(line.id(), 1, REPORT, List.of(new PickList.Hold(
                replacement, "CUP", "HCM-A02-1-A", HCM, "L2", 1, true)), null, UUID::randomUUID);

        assertThat(line.status()).isEqualTo(PickLineStatus.PICKED);
        assertThat(line.pickedQty()).isEqualTo(3);
        assertThat(line.damageReportId()).as("several reports may fall on one picked line").isNull();
        assertThat(added).singleElement().satisfies(next -> {
            assertThat(next.reservationId()).isEqualTo(replacement);
            assertThat(next.status()).isEqualTo(PickLineStatus.OPEN);
            assertThat(next.damageReportId()).isEqualTo(REPORT);
            assertThat(next.orderLineId()).isEqualTo(line.orderLineId());
        });
        assertThat(packing.madeAfterPick(REPORT)).isTrue();
        assertThat(refusal(() -> list(PickStatus.PICKED, picked(2)).replaceDamagedAfterPick(
                packing.lines().get(0).id(), 1, REPORT, List.of(), null, UUID::randomUUID)))
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(refusal(() -> list(PickStatus.PICKED, picked(2)).requireDamageableAfterPick(UUID.randomUUID(), 1)))
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("damage at packing with nothing to replace it: one SHORT line on the impaired share")
    void afterPickImpaired() {
        PickLine line = picked(2);
        PickList packing = list(PickStatus.PICKED, line);
        UUID impaired = UUID.randomUUID();

        List<PickLine> added = packing.replaceDamagedAfterPick(line.id(), 2, REPORT, List.of(), impaired,
                UUID::randomUUID);

        assertThat(added).singleElement().satisfies(next -> {
            assertThat(next.status()).isEqualTo(PickLineStatus.SHORT);
            assertThat(next.shortQty()).isEqualTo(2);
            assertThat(next.reservationId()).isEqualTo(impaired);
        });
        assertThat(line.goodQty()).isZero();
        assertThat(packing.hasShortLines()).isTrue();
    }

    @Test
    @DisplayName("a cancelled order puts back what a picked line still has; a line all damaged has nothing to put back")
    void putBackLeavesTheDamagedUnits() {
        PickLine some = new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP", "HCM-A01-1-A",
                "L1", 1, 3, 3, PickLineStatus.PICKED, UUID.randomUUID(), java.time.Instant.now(),
                java.time.Instant.now(), "HCM-A01-1-A", "CUP", 0, null, null, null, 0, null, false, 0, null, 1);
        PickLine all = new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP", "HCM-A01-1-A",
                "L1", 1, 2, 2, PickLineStatus.PICKED, UUID.randomUUID(), java.time.Instant.now(),
                java.time.Instant.now(), "HCM-A01-1-A", "CUP", 0, null, null, null, 0, null, false, 0, null, 2);
        PickList cancelled = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(some, all));
        PickList.PutBackTarget bin = new PickList.PutBackTarget("HCM-A01-1-B", HCM, true, true);

        assertThat(cancelled.putBack(some.id(), bin, UUID.randomUUID(), java.time.Instant.now()).line().putBackQty())
                .isEqualTo(2);
        assertThat(refusal(() -> cancelled.putBack(all.id(), bin, UUID.randomUUID(), java.time.Instant.now())))
                .isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
        assertThatThrownBy(() -> new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP",
                "HCM-A01-1-A", null, 1, 2, 0, PickLineStatus.OPEN, null, null, null, null, null, 0, null, null,
                null, 0, null, false, 0, null, 1)).as("only units a line picked").isInstanceOf(IllegalArgumentException.class);
    }
}
