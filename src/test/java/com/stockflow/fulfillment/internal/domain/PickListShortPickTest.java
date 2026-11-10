package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.LPN;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.NOW;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.OTHER_LPN;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.PICKER;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.hold;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.open;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * X-SHORT (kltn-docs 07 section 4.4 step 9, BR-09) as plain rules: a carton that cannot be taken is reported short whole,
 * with a reason; the coordinator's reallocation supersedes the line; the task completes once the new lines are picked.
 */
class PickListShortPickTest {

    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();

    private static ErrorCode refusal(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static PickList picking(PickLine... lines) {
        return new PickList(UUID.randomUUID(), HCM, PickStatus.PICKING, false, List.of(lines));
    }

    @Test
    @DisplayName("a short report takes the whole carton off the line, records the reason, picks nothing")
    void reportShort() {
        PickLine line = open("SOFA", "HCM-A01-1-A", 5, LPN);
        PickList list = picking(line);

        PickList.Confirmation outcome = list.reportShort(line.id(), "hcm-a01-1-a", ShortPickReason.NOT_FOUND);

        assertThat(outcome.repeated()).isFalse();
        assertThat(line.status()).isEqualTo(PickLineStatus.SHORT);
        assertThat(line.shortQty()).as("a carton is short whole, never in part (BR-04)").isEqualTo(5);
        assertThat(line.shortReason()).isEqualTo(ShortPickReason.NOT_FOUND);
        assertThat(line.pickedQty()).isZero();
        assertThat(line.pickedBy()).isNull();
        assertThat(line.lpn()).as("the carton stays on the line").isEqualTo(LPN);
        assertThat(list.hasShortLines()).isTrue();
        assertThat(refusal(list::requireAllPicked)).isEqualTo(ErrorCode.PICK_LINES_INCOMPLETE);
    }

    @Test
    @DisplayName("both reasons are accepted, and a reason is not optional")
    void reasons() {
        for (ShortPickReason reason : ShortPickReason.values()) {
            PickLine line = open("SOFA", "HCM-A01-1-A", 5, LPN);
            picking(line).reportShort(line.id(), "HCM-A01-1-A", reason);
            assertThat(line.shortReason()).isEqualTo(reason);
        }
        assertThat(ShortPickReason.values()).containsExactlyInAnyOrder(ShortPickReason.NOT_FOUND, ShortPickReason.DAMAGED);

        PickLine line = open("SOFA", "HCM-A01-1-A", 5, LPN);
        PickList list = picking(line);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> list.reportShort(line.id(), "HCM-A01-1-A", null))
                .isInstanceOf(NullPointerException.class);
        assertThat(line.status()).isEqualTo(PickLineStatus.OPEN);
    }

    @Test
    @DisplayName("the same report again - same bin, same reason - is answered with the line as it is")
    void repeatIsAnAnswer() {
        PickLine line = open("SOFA", "HCM-A01-1-A", 5, LPN);
        PickList list = picking(line);
        list.reportShort(line.id(), "HCM-A01-1-A", ShortPickReason.DAMAGED);

        assertThat(list.reportShort(line.id(), "hcm-a01-1-a", ShortPickReason.DAMAGED).repeated()).isTrue();
        assertThat(line.shortReason()).isEqualTo(ShortPickReason.DAMAGED);
    }

    @Test
    @DisplayName("another reason, or another bin, on a SHORT line is PICK_LINE_NOT_OPEN and changes nothing")
    void anotherReasonIsRefused() {
        PickLine line = open("SOFA", "HCM-A01-1-A", 5, LPN);
        PickList list = picking(line);
        list.reportShort(line.id(), "HCM-A01-1-A", ShortPickReason.NOT_FOUND);

        assertThat(refusal(() -> list.reportShort(line.id(), "HCM-A01-1-A", ShortPickReason.DAMAGED)))
                .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
        assertThat(refusal(() -> list.reportShort(line.id(), "HCM-A01-1-B", ShortPickReason.NOT_FOUND)))
                .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
        assertThat(line.shortReason()).isEqualTo(ShortPickReason.NOT_FOUND);
    }

    @Test
    @DisplayName("a short report needs the line's bin, an open line with a carton, a picking task and a known line")
    void reportShortRefusals() {
        PickLine line = open("SOFA", "HCM-A01-1-A", 5, LPN);
        PickList list = picking(line);
        assertThat(refusal(() -> list.reportShort(line.id(), "HCM-A01-1-B", ShortPickReason.NOT_FOUND)))
                .isEqualTo(ErrorCode.PICK_LOCATION_MISMATCH);
        assertThat(refusal(() -> list.reportShort(line.id(), null, ShortPickReason.NOT_FOUND)))
                .isEqualTo(ErrorCode.PICK_LOCATION_MISMATCH);
        assertThat(refusal(() -> list.reportShort(UUID.randomUUID(), "HCM-A01-1-A", ShortPickReason.NOT_FOUND)))
                .isEqualTo(ErrorCode.NOT_FOUND);
        PickList pending = new PickList(UUID.randomUUID(), HCM, PickStatus.PENDING, false,
                List.of(open("SOFA", "X-1", 1, LPN)));
        assertThat(refusal(() -> pending.reportShort(pending.lines().get(0).id(), "X-1", ShortPickReason.NOT_FOUND)))
                .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        PickLine picked = PickLineFixtures.picked("SOFA", "HCM-A01-1-A", 2, OTHER_LPN);
        assertThat(refusal(() -> picking(picked).reportShort(picked.id(), "HCM-A01-1-A", ShortPickReason.NOT_FOUND)))
                .as("a picked carton is not short").isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
        PickLine history = PickLineFixtures.line("SOFA", "HCM-A01-1-A", 2, 0, PickLineStatus.OPEN, 0, null, null, 0);
        assertThat(refusal(() -> picking(history).reportShort(history.id(), "HCM-A01-1-A", ShortPickReason.NOT_FOUND)))
                .as("a line with no carton to report").isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
        assertThat(line.status()).isEqualTo(PickLineStatus.OPEN);
    }

    @Test
    @DisplayName("reallocated: the short line is superseded and new lines follow it; the task completes once they are picked")
    void replaceShort() {
        PickLine shortLine = open("SOFA", "HCM-A01-1-A", 4, LPN);
        PickLine other = open("SOFA", "HCM-A02-1-A", 1, OTHER_LPN);
        PickList list = picking(shortLine, other);
        assertThat(refusal(() -> list.replaceShort(shortLine.id(), List.of(), UUID::randomUUID)))
                .as("only a SHORT line is reallocated").isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
        list.reportShort(shortLine.id(), "HCM-A01-1-A", ShortPickReason.NOT_FOUND);

        assertThat(refusal(() -> list.replaceShort(shortLine.id(), List.of(hold(UUID.randomUUID(), "SOFA",
                "HN-A01-1-A", HN, 4, "LPN-HN")), UUID::randomUUID)))
                .isEqualTo(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE);
        List<PickLine> added = list.replaceShort(shortLine.id(), List.of(
                hold(UUID.randomUUID(), "SOFA", "HCM-B01-1-A", HCM, 3, "LPN-B1"),
                hold(UUID.randomUUID(), "SOFA", "HCM-B01-1-B", HCM, 1, "LPN-B2")), UUID::randomUUID);

        assertThat(shortLine.status()).isEqualTo(PickLineStatus.CANCELLED);
        assertThat(shortLine.shortQty()).as("the shortfall stays on record").isEqualTo(4);
        assertThat(shortLine.shortReason()).as("so does the reason").isEqualTo(ShortPickReason.NOT_FOUND);
        assertThat(added).extracting(PickLine::locationCode, PickLine::allocatedQty, PickLine::orderLineId,
                        PickLine::lpn)
                .containsExactly(tuple("HCM-B01-1-A", 3, shortLine.orderLineId(), "LPN-B1"),
                        tuple("HCM-B01-1-B", 1, shortLine.orderLineId(), "LPN-B2"));
        assertThat(added).extracting(PickLine::sequenceNo).containsExactly(2, 3);
        assertThat(added).allSatisfy(line -> assertThat(line.shortReason()).isNull());
        assertThat(list.hasShortLines()).isFalse();

        for (PickLine line : list.lines()) {
            if (line.status() == PickLineStatus.OPEN) {
                list.confirm(line.id(), line.locationCode(), line.lpn(), PICKER, NOW);
            }
        }
        list.requireAllPicked();   // the superseded line no longer blocks the task
    }

    @Test
    @DisplayName("a reallocation to a loose hold is refused: a line is a carton")
    void reallocationToALooseHoldIsRefused() {
        PickLine shortLine = open("SOFA", "HCM-A01-1-A", 4, LPN);
        PickList list = picking(shortLine);
        list.reportShort(shortLine.id(), "HCM-A01-1-A", ShortPickReason.DAMAGED);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> list.replaceShort(shortLine.id(),
                        List.of(hold(UUID.randomUUID(), "SOFA", "HCM-B01-1-A", HCM, 4, null)), UUID::randomUUID))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rehydration: a short reason belongs only to a line short of its whole carton, SHORT or reallocated")
    void rehydrationRule() {
        // short_qty = allocated_qty and status SHORT/CANCELLED, as ck_pick_line_short_reason says
        PickLineFixtures.line("SOFA", "HCM-A01-1-A", 5, 0, PickLineStatus.SHORT, 5, ShortPickReason.NOT_FOUND, LPN, 0);
        PickLineFixtures.line("SOFA", "HCM-A01-1-A", 5, 0, PickLineStatus.CANCELLED, 5, ShortPickReason.DAMAGED, LPN, 0);
        for (Object[] bad : new Object[][] {
                {PickLineStatus.SHORT, 3},          // a part of the carton
                {PickLineStatus.OPEN, 0},           // not short at all
                {PickLineStatus.PICKED, 0}}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> PickLineFixtures.line("SOFA", "HCM-A01-1-A", 5,
                            (PickLineStatus) bad[0] == PickLineStatus.PICKED ? 5 : 0, (PickLineStatus) bad[0],
                            (Integer) bad[1], ShortPickReason.NOT_FOUND, LPN, 0))
                    .as(String.valueOf(bad[0]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
