package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The putaway trip's own rules (kltn-docs 05 §4.5, §5, BR-15, BR-16; Jira SCRUM-531, SCRUM-581): the five moves of
 * its status, when space is held and when it is given back.
 */
class PutawayTripTest {

    private static final Instant T0 = Instant.parse("2026-11-13T02:00:00Z");

    private final UUID warehouse = Identifiers.newId();
    private final UUID clerk = Identifiers.newId();
    private final UUID manager = Identifiers.newId();
    private final UUID receiving = Identifiers.newId();
    private final UUID binA = Identifiers.newId();
    private final UUID binB = Identifiers.newId();
    private final UUID task1 = Identifiers.newId();
    private final UUID task2 = Identifiers.newId();

    private PutawayTrip loading(UUID... tasks) {
        PutawayTrip trip = PutawayTrip.open(Identifiers.newId(), warehouse, clerk, T0);
        for (UUID task : tasks) {
            trip.load(Identifiers.newId(), task, T0.plusSeconds(1));
        }
        return trip;
    }

    private PutawayTrip onItsWay() {
        PutawayTrip trip = loading(task1, task2);
        trip.start(Map.of(task1, binA, task2, binB), Map.of(task1, 1, task2, 2), receiving, T0.plusSeconds(60));
        return trip;
    }

    private static PutawayTrip.Item item(PutawayTrip trip, UUID task) {
        return trip.items().stream().filter(i -> i.taskId().equals(task)).findFirst().orElseThrow();
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("LOADING -> IN_PROGRESS: setting off assigns the bins, numbers the stops and holds the space")
    void settingOff() {
        PutawayTrip trip = loading(task1, task2);
        assertThat(trip.status()).isEqualTo(PutawayTripStatus.LOADING);
        assertThat(item(trip, task1).holding()).as("nothing is held while loading").isFalse();

        trip.start(Map.of(task1, binA, task2, binB), Map.of(task1, 1, task2, 2), receiving, T0.plusSeconds(60));

        assertThat(trip.status()).isEqualTo(PutawayTripStatus.IN_PROGRESS);
        assertThat(trip.startedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(trip.startLocationId()).isEqualTo(receiving);
        assertThat(item(trip, task1).assignedLocationId()).isEqualTo(binA);
        assertThat(item(trip, task2).stopSequence()).isEqualTo(2);
        assertThat(item(trip, task1).holding()).isTrue();
    }

    @Test
    @DisplayName("LOADING -> IN_PROGRESS needs a carton; and a cart that left takes no more cartons")
    void anEmptyCartDoesNotLeave() {
        assertThat(code(catchThrowable(() -> loading().start(Map.of(), Map.of(), receiving, T0))))
                .isEqualTo(ErrorCode.PUTAWAY_TRIP_EMPTY);

        PutawayTrip trip = onItsWay();
        assertThat(code(catchThrowable(() -> trip.load(Identifiers.newId(), Identifiers.newId(), T0))))
                .isEqualTo(ErrorCode.PUTAWAY_TRIP_INVALID_STATE);
        assertThat(code(catchThrowable(() -> trip.unload(task1, T0))))
                .isEqualTo(ErrorCode.PUTAWAY_TRIP_INVALID_STATE);
    }

    @Test
    @DisplayName("LOADING -> CLOSED: a trip dropped before it left returns its cartons and holds nothing")
    void droppedWhileLoading() {
        PutawayTrip trip = loading(task1);

        trip.close(clerk, T0.plusSeconds(30));

        assertThat(trip.status()).isEqualTo(PutawayTripStatus.CLOSED);
        assertThat(trip.closedBy()).isEqualTo(clerk);
        assertThat(item(trip, task1).status()).isEqualTo(PutawayTripItemStatus.RETURNED);
        assertThat(trip.carries(task1)).isFalse();
    }

    @Test
    @DisplayName("IN_PROGRESS -> COMPLETED: the last carton put away completes the trip, each one ending its hold")
    void completedWithTheLastCarton() {
        PutawayTrip trip = onItsWay();

        trip.recordPlaced(task1, T0.plusSeconds(120));
        assertThat(trip.status()).isEqualTo(PutawayTripStatus.IN_PROGRESS);
        assertThat(item(trip, task1).status()).isEqualTo(PutawayTripItemStatus.PLACED);
        assertThat(item(trip, task1).holding()).isFalse();
        assertThat(item(trip, task1).holdReleasedAt()).isEqualTo(T0.plusSeconds(120));
        assertThat(item(trip, task2).holding()).as("the other carton's space is still held").isTrue();

        trip.recordPlaced(task2, T0.plusSeconds(180));
        assertThat(trip.status()).isEqualTo(PutawayTripStatus.COMPLETED);
        assertThat(trip.closedAt()).isEqualTo(T0.plusSeconds(180));
        assertThat(trip.closedBy()).as("nobody closed it: it completed").isNull();
    }

    @Test
    @DisplayName("IN_PROGRESS -> CLOSED: a manager closes a trip left behind; what is on the cart goes back (X07)")
    void closedWithCartonsOnTheCart() {
        PutawayTrip trip = onItsWay();
        trip.recordPlaced(task1, T0.plusSeconds(120));

        trip.close(manager, T0.plusSeconds(3600));

        assertThat(trip.status()).isEqualTo(PutawayTripStatus.CLOSED);
        assertThat(trip.closedBy()).isEqualTo(manager);
        assertThat(item(trip, task1).status()).as("what was put away stays put away")
                .isEqualTo(PutawayTripItemStatus.PLACED);
        assertThat(item(trip, task2).status()).isEqualTo(PutawayTripItemStatus.RETURNED);
        assertThat(item(trip, task2).holding()).isFalse();
    }

    @Test
    @DisplayName("COMPLETED and CLOSED are final: nothing is placed, reassigned or closed afterwards")
    void finishedTripsStayFinished() {
        PutawayTrip closed = onItsWay();
        closed.close(clerk, T0.plusSeconds(90));
        assertThat(code(catchThrowable(() -> closed.close(clerk, T0)))).isEqualTo(ErrorCode.PUTAWAY_TRIP_INVALID_STATE);
        assertThat(code(catchThrowable(() -> closed.recordPlaced(task1, T0))))
                .isEqualTo(ErrorCode.PUTAWAY_TRIP_INVALID_STATE);
        assertThat(code(catchThrowable(() -> closed.reassign(task1, binB, T0))))
                .isEqualTo(ErrorCode.PUTAWAY_TRIP_INVALID_STATE);
    }

    @Test
    @DisplayName("the hold window (BR-16): idle holds are given back, the cart and its bins stay as they are")
    void idleHoldsAreReleased() {
        PutawayTrip trip = onItsWay();

        assertThat(trip.releaseHolds(T0.plusSeconds(1900))).isTrue();

        assertThat(trip.status()).isEqualTo(PutawayTripStatus.IN_PROGRESS);
        assertThat(item(trip, task1).status()).isEqualTo(PutawayTripItemStatus.ON_CART);
        assertThat(item(trip, task1).assignedLocationId()).as("the bin is still the plan").isEqualTo(binA);
        assertThat(item(trip, task1).holding()).isFalse();
        assertThat(trip.releaseHolds(T0.plusSeconds(2000))).as("nothing left to release").isFalse();
    }

    @Test
    @DisplayName("a refused bin (step 15): another bin is a new hold, and the old one is no longer held")
    void reassigning() {
        PutawayTrip trip = onItsWay();
        trip.releaseHolds(T0.plusSeconds(1900));

        trip.reassign(task1, binB, T0.plusSeconds(2000));
        trip.renumberStops(Map.of(task1, 1, task2, 1));

        assertThat(item(trip, task1).assignedLocationId()).isEqualTo(binB);
        assertThat(item(trip, task1).holding()).as("a new bin is held afresh").isTrue();
        assertThat(item(trip, task1).stopSequence()).isEqualTo(1);
        assertThat(code(catchThrowable(() -> trip.reassign(Identifiers.newId(), binA, T0))))
                .isEqualTo(ErrorCode.PUTAWAY_CARTON_NOT_ON_TRIP);
    }

    @Test
    @DisplayName("the cart while loading: a second scan changes nothing, a carton taken off can come back")
    void loadingTheCart() {
        PutawayTrip trip = loading(task1);
        trip.load(Identifiers.newId(), task1, T0.plusSeconds(5));
        assertThat(trip.items()).hasSize(1);

        trip.unload(task1, T0.plusSeconds(6));
        assertThat(trip.carries(task1)).isFalse();
        trip.load(Identifiers.newId(), task1, T0.plusSeconds(7));
        assertThat(trip.carries(task1)).isTrue();
        assertThat(trip.items()).as("the same row comes back").hasSize(1);

        assertThat(code(catchThrowable(() -> trip.unload(task2, T0))))
                .isEqualTo(ErrorCode.PUTAWAY_CARTON_NOT_ON_TRIP);
    }

    @Test
    @DisplayName("a carton nothing takes is on the cart with no bin and holds nothing")
    void aCartonWithNoBin() {
        PutawayTrip trip = loading(task1, task2);
        trip.start(Map.of(task1, binA), Map.of(task1, 1), receiving, T0.plusSeconds(60));

        assertThat(item(trip, task2).assignedLocationId()).isNull();
        assertThat(item(trip, task2).stopSequence()).isNull();
        assertThat(item(trip, task2).holding()).isFalse();
    }
}
