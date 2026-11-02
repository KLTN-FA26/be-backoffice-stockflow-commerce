package com.stockflow.order.internal.domain;

import com.stockflow.order.api.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.stockflow.order.api.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3-2 (SCRUM-246): the order lifecycle, every pair of statuses. The expected table is written here a second time, by
 * hand, from kltn-docs 17 §5 and the G3-2 decisions, so a change to {@link OrderStatus#canTransitionTo} is a change
 * someone made on purpose in two places. 19 x 19 = 361 pairs, each allowed or refused.
 */
class OrderStatusTest {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.ofEntries(
            Map.entry(DRAFT, EnumSet.of(PENDING_PAYMENT, CONFIRMED, ON_HOLD, CANCELLED)),
            Map.entry(PENDING_PAYMENT, EnumSet.of(CONFIRMED, CANCELLED)),
            Map.entry(CONFIRMED, EnumSet.of(IN_PRODUCTION, READY_TO_FULFILL, ON_HOLD, CANCELLED)),
            // PARTIALLY_FULFILLED: PACK-EARLY (lead's decision 2026-10-21), the stock shipped ahead of the prints.
            Map.entry(IN_PRODUCTION, EnumSet.of(READY_TO_FULFILL, PARTIALLY_FULFILLED, ON_HOLD, CANCELLED)),
            Map.entry(READY_TO_FULFILL, EnumSet.of(PICKING, PACKED, PARTIALLY_FULFILLED, ON_HOLD, CANCELLED)),
            Map.entry(PICKING, EnumSet.of(PACKED, PARTIALLY_FULFILLED, ON_HOLD, CANCELLED)),
            Map.entry(PARTIALLY_FULFILLED, EnumSet.of(PACKED, SHIPPED, DELIVERY_FAILED, DELIVERED, RETURNED, ON_HOLD)),
            Map.entry(PACKED, EnumSet.of(SHIPPED, PARTIALLY_FULFILLED, ON_HOLD)),
            Map.entry(ON_HOLD, EnumSet.of(CONFIRMED, PENDING_PAYMENT, IN_PRODUCTION, READY_TO_FULFILL, PICKING,
                    PARTIALLY_FULFILLED, PACKED, CANCELLED)),
            Map.entry(SHIPPED, EnumSet.of(DELIVERED, DELIVERY_FAILED, PARTIALLY_FULFILLED)),
            Map.entry(DELIVERY_FAILED, EnumSet.of(DELIVERED, RETURNED, PARTIALLY_FULFILLED)),
            Map.entry(DELIVERED, EnumSet.of(COMPLETED, RETURN_REQUESTED)),
            Map.entry(RETURN_REQUESTED, EnumSet.of(RETURNED, DELIVERED)),
            Map.entry(RETURNED, EnumSet.of(REFUNDED, PARTIALLY_REFUNDED)),
            Map.entry(REFUNDED, EnumSet.of(CLOSED, RETURN_REQUESTED)),
            Map.entry(PARTIALLY_REFUNDED, EnumSet.of(CLOSED, RETURN_REQUESTED)),
            Map.entry(COMPLETED, EnumSet.of(CLOSED, RETURN_REQUESTED)),
            Map.entry(CANCELLED, EnumSet.noneOf(OrderStatus.class)),
            Map.entry(CLOSED, EnumSet.noneOf(OrderStatus.class)));

    static Stream<Arguments> everyPair() {
        return Arrays.stream(values()).flatMap(from -> Arrays.stream(values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    @DisplayName("every pair of statuses is allowed exactly when the table says so")
    void everyTransition(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(ALLOWED.get(from).contains(to));
    }

    @Test
    @DisplayName("the expected table names every status, and the retired IN_FULFILMENT / PAID are gone")
    void everyStatusIsInTheTable() {
        assertThat(ALLOWED.keySet()).containsExactlyInAnyOrder(values());
        assertThat(Arrays.stream(values()).map(Enum::name)).doesNotContain("IN_FULFILMENT", "PAID", "PAYMENT_FAILED");
        assertThat(OrderStatus.edges()).hasSize(ALLOWED.values().stream().mapToInt(Set::size).sum());
    }

    @Test
    @DisplayName("only CANCELLED and CLOSED are terminal; no status moves to itself")
    void terminalAndNoSelfLoops() {
        for (OrderStatus status : values()) {
            assertThat(status.isTerminal()).as(status.name()).isEqualTo(status == CANCELLED || status == CLOSED);
            assertThat(status.canTransitionTo(status)).as(status.name()).isFalse();
        }
    }

    @Test
    @DisplayName("nothing is cancelled from PACKED on (decision 2026-10-17) nor after SHIPPED (BR-031)")
    void cancellationStopsAtTheFirstPackage() {
        assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(s -> s.canTransitionTo(CANCELLED)))
                .containsExactlyInAnyOrder(DRAFT, PENDING_PAYMENT, CONFIRMED, IN_PRODUCTION, READY_TO_FULFILL, PICKING,
                        ON_HOLD);
    }

    @Test
    @DisplayName("a hold is entered from up to PACKED and the partly shipped state, and left back to each of them")
    void holdGoesBackWhereItCameFrom() {
        for (OrderStatus status : values()) {
            assertThat(status.mayBePutOnHold()).as(status.name()).isEqualTo(status.canTransitionTo(ON_HOLD)
                    && status != DRAFT);
            if (status.mayBePutOnHold()) {
                assertThat(ON_HOLD.canTransitionTo(status)).as("ON_HOLD -> " + status).isTrue();
            }
        }
    }

    @Test
    @DisplayName("credit exposure: every live status before the outcome of delivery is counted, nothing after")
    void creditExposureComesFromTheStateMachine() {
        assertThat(OrderStatus.creditExposureStatuses()).containsExactlyInAnyOrder(CONFIRMED, IN_PRODUCTION,
                READY_TO_FULFILL, PICKING, PARTIALLY_FULFILLED, PACKED, ON_HOLD, SHIPPED, DELIVERY_FAILED);
        // A status that can still be delivered (and so billed) must be counted. RETURN_REQUESTED → DELIVERED is an
        // RMA refused (decision 2026-10-21): the goods were delivered and billed already.
        for (OrderStatus status : values()) {
            if (status != RETURN_REQUESTED && (status.canTransitionTo(DELIVERED) || status.canTransitionTo(SHIPPED))) {
                assertThat(status.countsTowardCreditExposure()).as(status.name()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("stock is held from submission until the goods are handed over")
    void holdsStock() {
        assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(OrderStatus::holdsStock))
                .containsExactlyInAnyOrder(PENDING_PAYMENT, CONFIRMED, IN_PRODUCTION, READY_TO_FULFILL, PICKING,
                        PARTIALLY_FULFILLED, PACKED, ON_HOLD);
    }
}
