package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.LPN;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.OTHER_LPN;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.hold;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.open;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.picked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-277 as plain rules, whole cartons since team plan 4C-4: a line is one carton, so damage at the bin is the whole
 * carton - a part of it is refused - and gives way to the lines that pick what replaced it.
 */
class PickListDamageTest {

    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();
    private static final UUID REPORT = UUID.randomUUID();

    private static ErrorCode refusal(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static PickList list(PickStatus status, PickLine... lines) {
        return new PickList(UUID.randomUUID(), HCM, status, false, List.of(lines));
    }

    @Test
    @DisplayName("a damage report needs a picking task, an open line, its bin and the whole carton")
    void refusals() {
        PickLine line = open("CUP", "HCM-A01-1-A", 5, LPN);
        PickList picking = list(PickStatus.PICKING, line);

        assertThat(refusal(() -> picking.requireDamageable(line.id(), "HCM-A01-1-B", 5)))
                .isEqualTo(ErrorCode.PICK_LOCATION_MISMATCH);
        assertThat(refusal(() -> picking.requireDamageable(UUID.randomUUID(), "HCM-A01-1-A", 5)))
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(refusal(() -> list(PickStatus.PENDING, line).requireDamageable(line.id(), "HCM-A01-1-A", 5)))
                .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        PickLine picked = picked("CUP", "HCM-A01-1-A", 2, OTHER_LPN);
        assertThat(refusal(() -> list(PickStatus.PICKING, picked).requireDamageable(picked.id(), "HCM-A01-1-A", 2)))
                .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
        assertThat(picking.requireDamageable(line.id(), "hcm-a01-1-a", 5)).as("not reported yet").isFalse();
    }

    @Test
    @DisplayName("a part of a carton damaged is STOCK_PACKAGE_TAKEN_WHOLE, whatever the count: a carton is taken whole")
    void partOfACartonIsRefused() {
        PickLine line = open("CUP", "HCM-A01-1-A", 5, LPN);
        PickList picking = list(PickStatus.PICKING, line);

        for (int damaged : new int[] {-1, 0, 1, 4, 6}) {
            assertThat(refusal(() -> picking.requireDamageable(line.id(), "HCM-A01-1-A", damaged)))
                    .as("%d of 5", damaged).isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
        }
        assertThat(line.status()).isEqualTo(PickLineStatus.OPEN);
    }

    @Test
    @DisplayName("the whole carton damaged and replaced: the line gives way to one line in the replacement's carton")
    void wholeCartonDamagedReplaced() {
        PickLine line = open("CUP", "HCM-A01-1-A", 5, LPN);
        PickLine other = open("CUP", "HCM-B01-1-A", 1, OTHER_LPN);
        PickList list = list(PickStatus.PICKING, line, other);
        UUID replacement = UUID.randomUUID();

        List<PickLine> added = list.replaceDamaged(line.id(), 5, REPORT, List.of(hold(replacement, "CUP",
                "HCM-A02-1-A", HCM, 5, "LPN-R")), null, UUID::randomUUID);

        assertThat(line.status()).isEqualTo(PickLineStatus.CANCELLED);
        assertThat(line.damagedQty()).isEqualTo(5);
        assertThat(line.damageReportId()).isEqualTo(REPORT);
        assertThat(added).singleElement().satisfies(moved -> {
            assertThat(moved.reservationId()).isEqualTo(replacement);
            assertThat(moved.locationCode()).isEqualTo("HCM-A02-1-A");
            assertThat(moved.lotNumber()).isNull();
            assertThat(moved.lpn()).isEqualTo("LPN-R");
            assertThat(moved.wholeCarton()).isTrue();
            assertThat(moved.status()).isEqualTo(PickLineStatus.OPEN);
            assertThat(moved.damageReportId()).isEqualTo(REPORT);
            assertThat(moved.orderLineId()).isEqualTo(line.orderLineId());
            assertThat(moved.sequenceNo()).isGreaterThan(other.sequenceNo());
        });
        assertThat(list.hasShortLines()).isFalse();
        assertThat(list.madeByDamage(REPORT)).containsExactlyElementsOf(added);
        assertThat(added).as("nothing is retained at the bin: no line for the good units left there")
                .noneMatch(made -> made.locationCode().equals("HCM-A01-1-A"));
        // The same report again is recognised from the line itself.
        assertThat(list.requireDamageable(line.id(), "HCM-A01-1-A", 5)).isTrue();
        assertThat(refusal(() -> list.requireDamageable(line.id(), "HCM-A01-1-A", 3)))
                .as("another quantity on a superseded line is refused as a part of a carton")
                .isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
    }

    @Test
    @DisplayName("nothing to replace it: a SHORT line for the carton's units, in no carton, waits for the coordinator")
    void damagedWithoutReplacement() {
        PickLine line = open("CUP", "HCM-A01-1-A", 4, LPN);
        PickList list = list(PickStatus.PICKING, line);
        UUID impaired = UUID.randomUUID();

        List<PickLine> added = list.replaceDamaged(line.id(), 4, REPORT, List.of(), impaired, UUID::randomUUID);

        assertThat(added).singleElement().satisfies(waiting -> {
            assertThat(waiting.status()).isEqualTo(PickLineStatus.SHORT);
            assertThat(waiting.reservationId()).isEqualTo(impaired);
            assertThat(waiting.shortQty()).isEqualTo(4);
            assertThat(waiting.allocatedQty()).isEqualTo(4);
            assertThat(waiting.locationCode()).isEqualTo("HCM-A01-1-A");
            assertThat(waiting.lpn()).as("it is on a loose DAMAGED row in quarantine, never in a carton").isNull();
            assertThat(waiting.wholeCarton()).isFalse();
            assertThat(waiting.shortReason()).as("a damage report, not a short report, made it").isNull();
            assertThat(waiting.damageReportId()).isEqualTo(REPORT);
        });
        assertThat(list.hasShortLines()).isTrue();
        assertThat(refusal(list::requireAllPicked)).isEqualTo(ErrorCode.PICK_LINES_INCOMPLETE);
        // The coordinator's reallocation supersedes it like any short line (X-SHORT).
        List<PickLine> reallocated = list.replaceShort(added.get(0).id(), List.of(hold(UUID.randomUUID(), "CUP",
                "HCM-A02-1-A", HCM, 4, "LPN-R")), UUID::randomUUID);
        assertThat(reallocated).singleElement().satisfies(next -> assertThat(next.lpn()).isEqualTo("LPN-R"));
        assertThat(list.hasShortLines()).isFalse();
    }

    @Test
    @DisplayName("a replacement outside the task's warehouse is refused; both or neither of replacement and impaired is a caller bug")
    void inconsistentOutcomes() {
        PickLine line = open("CUP", "HCM-A01-1-A", 2, LPN);
        PickList list = list(PickStatus.PICKING, line);

        assertThat(refusal(() -> list.replaceDamaged(line.id(), 2, REPORT, List.of(hold(UUID.randomUUID(), "CUP",
                "HN-A01-1-A", HN, 2, "LPN-HN")), null, UUID::randomUUID)))
                .isEqualTo(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE);
        assertThatThrownBy(() -> list.replaceDamaged(line.id(), 2, REPORT, List.of(), null, UUID::randomUUID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.replaceDamaged(line.id(), 2, REPORT, List.of(hold(UUID.randomUUID(), "CUP",
                "HCM-A02-1-A", HCM, 2, "LPN-R")), UUID.randomUUID(), UUID::randomUUID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(line.status()).as("nothing changed").isEqualTo(PickLineStatus.OPEN);
    }

    @Test
    @DisplayName("a part of the carton cannot be replaced: no retained units survive 4C-4")
    void retainedUnitsAreGone() {
        PickLine line = open("CUP", "HCM-A01-1-A", 5, LPN);
        PickList list = list(PickStatus.PICKING, line);

        assertThatThrownBy(() -> list.replaceDamaged(line.id(), 2, REPORT, List.of(hold(UUID.randomUUID(), "CUP",
                "HCM-A02-1-A", HCM, 2, "LPN-R")), null, UUID::randomUUID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(line.status()).isEqualTo(PickLineStatus.OPEN);
    }

    @Test
    @DisplayName("a replacement names its own carton; the impaired share is the only line with none")
    void damageKeepsTheCartons() {
        PickLine carton = open("CUP", "HCM-A01-1-A", 5, LPN);
        PickList replaced = list(PickStatus.PICKING, carton);
        List<PickLine> added = replaced.replaceDamaged(carton.id(), 5, REPORT, List.of(hold(UUID.randomUUID(), "CUP",
                "HCM-A02-1-A", HCM, 5, "LPN-2")), null, UUID::randomUUID);
        assertThat(added).extracting(PickLine::lpn, PickLine::wholeCarton)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("LPN-2", true));

        PickLine other = open("CUP", "HCM-A01-1-A", 2, "LPN-3");
        List<PickLine> impaired = list(PickStatus.PICKING, other).replaceDamaged(other.id(), 2, REPORT, List.of(),
                UUID.randomUUID(), UUID::randomUUID);
        assertThat(impaired).singleElement().satisfies(line -> {
            assertThat(line.status()).isEqualTo(PickLineStatus.SHORT);
            assertThat(line.lpn()).isNull();
            assertThat(line.wholeCarton()).isFalse();
        });

        PickLine third = open("CUP", "HCM-A01-1-A", 2, "LPN-4");
        assertThatThrownBy(() -> list(PickStatus.PICKING, third).replaceDamaged(third.id(), 2, REPORT, List.of(hold(
                UUID.randomUUID(), "CUP", "HCM-A02-1-A", HCM, 2, null)), null, UUID::randomUUID))
                .as("a replacement with no carton").isInstanceOf(IllegalArgumentException.class);
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

    @Test
    @DisplayName("damage at packing: a picked task, a picked line, 1 to what it still has; never twice the same units")
    void afterPickRefusals() {
        PickLine line = picked("CUP", "HCM-A01-1-A", 3, LPN);
        PickList packing = list(PickStatus.PICKED, line);

        assertThat(refusal(() -> list(PickStatus.CANCELLED, line).requireDamageableAfterPick(line.id(), 1)))
                .as("a cancelled order's goods are put back, then reported on the shelf")
                .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        list(PickStatus.PICKING, line).requireDamageableAfterPick(line.id(), 1);   // in the tote, still picking
        assertThat(refusal(() -> packing.requireDamageableAfterPick(line.id(), 0)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> packing.requireDamageableAfterPick(line.id(), 4)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        PickLine notPicked = open("CUP", "HCM-A01-1-A", 2, OTHER_LPN);
        assertThat(refusal(() -> list(PickStatus.PICKED, notPicked).requireDamageableAfterPick(notPicked.id(), 1)))
                .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);

        packing.replaceDamagedAfterPick(line.id(), 2, REPORT, List.of(hold(UUID.randomUUID(), "CUP",
                "HCM-A02-1-A", HCM, 2, "LPN-R")), null, UUID::randomUUID);
        assertThat(line.packingDamagedQty()).isEqualTo(2);
        assertThat(line.goodQty()).isEqualTo(1);
        assertThat(refusal(() -> packing.requireDamageableAfterPick(line.id(), 2)))
                .as("two of the three are in quarantine already").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("damage at packing replaced: the picked line keeps them on record, new OPEN lines after the last stop")
    void afterPickReplaced() {
        PickLine line = picked("CUP", "HCM-A01-1-A", 3, LPN);
        PickLine other = picked("CUP", "HCM-B01-1-A", 1, OTHER_LPN);
        PickList packing = list(PickStatus.PICKED, line, other);
        UUID replacement = UUID.randomUUID();

        List<PickLine> added = packing.replaceDamagedAfterPick(line.id(), 1, REPORT, List.of(hold(replacement, "CUP",
                "HCM-A02-1-A", HCM, 1, "LPN-R")), null, UUID::randomUUID);

        assertThat(line.status()).isEqualTo(PickLineStatus.PICKED);
        assertThat(line.pickedQty()).isEqualTo(3);
        assertThat(line.damageReportId()).as("several reports may fall on one picked line").isNull();
        assertThat(added).singleElement().satisfies(next -> {
            assertThat(next.reservationId()).isEqualTo(replacement);
            assertThat(next.status()).isEqualTo(PickLineStatus.OPEN);
            assertThat(next.damageReportId()).isEqualTo(REPORT);
            assertThat(next.orderLineId()).isEqualTo(line.orderLineId());
            assertThat(next.lpn()).isEqualTo("LPN-R");
        });
        assertThat(packing.madeAfterPick(REPORT)).isTrue();
        assertThat(refusal(() -> list(PickStatus.PICKED, picked("CUP", "HCM-A01-1-A", 2, LPN)).replaceDamagedAfterPick(
                packing.lines().get(0).id(), 1, REPORT, List.of(), null, UUID::randomUUID)))
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(refusal(() -> list(PickStatus.PICKED, picked("CUP", "HCM-A01-1-A", 2, LPN))
                .requireDamageableAfterPick(UUID.randomUUID(), 1))).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("damage at packing with nothing to replace it: one SHORT line on the impaired share")
    void afterPickImpaired() {
        PickLine line = picked("CUP", "HCM-A01-1-A", 2, LPN);
        PickList packing = list(PickStatus.PICKED, line);
        UUID impaired = UUID.randomUUID();

        List<PickLine> added = packing.replaceDamagedAfterPick(line.id(), 2, REPORT, List.of(), impaired,
                UUID::randomUUID);

        assertThat(added).singleElement().satisfies(next -> {
            assertThat(next.status()).isEqualTo(PickLineStatus.SHORT);
            assertThat(next.shortQty()).isEqualTo(2);
            assertThat(next.reservationId()).isEqualTo(impaired);
            assertThat(next.lpn()).isNull();
        });
        assertThat(line.goodQty()).isZero();
        assertThat(packing.hasShortLines()).isTrue();
    }

    @Test
    @DisplayName("a cancelled order's carton, part of it found damaged at packing, does not go back; all of it has nothing to")
    void putBackRefusesTheDamagedCarton() {
        PickLine some = PickLineFixtures.pickedPartlyDamaged("CUP", "HCM-A01-1-A", 3, LPN, 1);
        PickLine all = PickLineFixtures.pickedPartlyDamaged("CUP", "HCM-A01-1-A", 2, OTHER_LPN, 2);
        PickList cancelled = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(some, all));
        PickList.PutBackTarget bin = new PickList.PutBackTarget("HCM-A01-1-B", HCM, true, true);

        assertThat(refusal(() -> cancelled.putBack(some.id(), LPN, bin, UUID.randomUUID(), Instant.now())))
                .isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
        assertThat(refusal(() -> cancelled.putBack(all.id(), OTHER_LPN, bin, UUID.randomUUID(), Instant.now())))
                .isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
        assertThat(some.status()).isEqualTo(PickLineStatus.PICKED);
        assertThatThrownBy(() -> new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP",
                "HCM-A01-1-A", null, 1, 2, 0, PickLineStatus.OPEN, null, null, null, null, null, 0, null, null,
                null, 0, null, false, 0, null, 1)).as("only units a line picked").isInstanceOf(IllegalArgumentException.class);
    }
}
