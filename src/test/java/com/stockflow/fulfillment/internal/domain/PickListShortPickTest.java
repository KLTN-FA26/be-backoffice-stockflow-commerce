package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** X-SHORT (docs 04c) as plain rules: reporting a short line, superseding it with reallocated lines, completing. */
class PickListShortPickTest {

    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();

    private static ErrorCode refusal(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static PickLine open(String location, int quantity) {
        return new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SOFA", location, "L1", 1, quantity,
                0, PickLineStatus.OPEN, null, null, null, null, null, 0, null, null, null);
    }

    private static PickList picking(PickLine... lines) {
        return new PickList(UUID.randomUUID(), HCM, PickStatus.PICKING, false, List.of(lines));
    }

    @Test
    @DisplayName("a short report records the shortfall, picks nothing, and is answered again unchanged")
    void reportShort() {
        PickLine line = open("HCM-A01-1-A", 5);
        PickList list = picking(line);

        PickList.Confirmation outcome = list.reportShort(line.id(), "hcm-a01-1-a", 2);

        assertThat(outcome.repeated()).isFalse();
        assertThat(line.status()).isEqualTo(PickLineStatus.SHORT);
        assertThat(line.shortQty()).isEqualTo(3);
        assertThat(line.pickedQty()).isZero();
        assertThat(list.hasShortLines()).isTrue();
        assertThat(list.reportShort(line.id(), "HCM-A01-1-A", 2).repeated()).isTrue();
        assertThat(refusal(list::requireAllPicked)).isEqualTo(ErrorCode.PICK_LINES_INCOMPLETE);
    }

    @Test
    @DisplayName("a short report needs the line's bin, an open line, a picking task and a quantity that is short")
    void reportShortRefusals() {
        PickLine line = open("HCM-A01-1-A", 5);
        PickList list = picking(line);
        assertThat(refusal(() -> list.reportShort(line.id(), "HCM-A01-1-B", 0))).isEqualTo(ErrorCode.PICK_LOCATION_MISMATCH);
        assertThat(refusal(() -> list.reportShort(line.id(), "HCM-A01-1-A", 5))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> list.reportShort(line.id(), "HCM-A01-1-A", -1))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> list.reportShort(UUID.randomUUID(), "HCM-A01-1-A", 0))).isEqualTo(ErrorCode.NOT_FOUND);
        PickList pending = new PickList(UUID.randomUUID(), HCM, PickStatus.PENDING, false, List.of(open("X-1", 1)));
        assertThat(refusal(() -> pending.reportShort(pending.lines().get(0).id(), "X-1", 0)))
                .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
    }

    @Test
    @DisplayName("reallocated: the short line is superseded and new lines follow it; the task completes once they are picked")
    void replaceShort() {
        PickLine shortLine = open("HCM-A01-1-A", 4);
        PickLine other = open("HCM-A02-1-A", 1);
        PickList list = picking(shortLine, other);
        assertThat(refusal(() -> list.replaceShort(shortLine.id(), List.of(), UUID::randomUUID)))
                .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
        list.reportShort(shortLine.id(), "HCM-A01-1-A", 0);

        assertThat(refusal(() -> list.replaceShort(shortLine.id(), List.of(new PickList.Hold(UUID.randomUUID(), "SOFA",
                "HN-A01-1-A", HN, "L1", 4, true)), UUID::randomUUID)))
                .isEqualTo(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE);
        List<PickLine> added = list.replaceShort(shortLine.id(), List.of(
                new PickList.Hold(UUID.randomUUID(), "SOFA", "HCM-B01-1-A", HCM, "L2", 3, true),
                new PickList.Hold(UUID.randomUUID(), "SOFA", "HCM-B01-1-B", HCM, "L3", 1, true)), UUID::randomUUID);

        assertThat(shortLine.status()).isEqualTo(PickLineStatus.CANCELLED);
        assertThat(shortLine.shortQty()).as("the shortfall stays on record").isEqualTo(4);
        assertThat(added).extracting(PickLine::locationCode, PickLine::allocatedQty, PickLine::orderLineId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("HCM-B01-1-A", 3, shortLine.orderLineId()),
                        org.assertj.core.groups.Tuple.tuple("HCM-B01-1-B", 1, shortLine.orderLineId()));
        assertThat(added).extracting(PickLine::sequenceNo).containsExactly(2, 3);
        assertThat(list.hasShortLines()).isFalse();

        for (PickLine line : list.lines()) {
            if (line.status() == PickLineStatus.OPEN) {
                list.confirm(line.id(), line.locationCode(), "SOFA", line.allocatedQty(), null, UUID.randomUUID(),
                        java.time.Instant.now());
            }
        }
        list.requireAllPicked();   // the superseded line no longer blocks the task
    }
}
