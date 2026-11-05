package com.stockflow.fulfillment.internal.domain.loadplanning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.assertSound;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.dc20;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.dc40;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.hc40;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SOLVER-CAP (owner, 02/11): the budget is work (deterministic, the same on any machine) and the real time limit is a
 * separate cap that returns the best plan so far. Time is injected, so no test waits.
 */
class LoadPlannerWallLimitTest {

    /** A work rate low enough for a test: the same search as in production, a small slice of it. */
    private static final double RATE = 50_000.0;
    private static final Duration BUDGET = Duration.ofSeconds(5);

    private static final List<CargoPackage> CARTONS =
            LoadPlannerPropertyTest.randomCartons(new Random(11), 120, false);
    private static final List<VehicleSpec> TYPES = List.of(dc20(20, 150), dc40(10, 150), hc40(10, 150));

    private static LoadPlanRequest request(Duration budget, Duration wallLimit) {
        return new LoadPlanRequest(CARTONS, TYPES, SelectionMode.FEWEST_VEHICLES, List.of(), KitDistribution.AUTO,
                false, null, 0L, budget, wallLimit);
    }

    private static LoadPlanner planner(LongSupplier time) {
        return new LoadPlanner(RATE, SpaceChoice.IMPROVED, null, time);
    }

    /** Time that never moves: only the work budget can stop a search. */
    private static LongSupplier frozen() {
        return () -> 0L;
    }

    /** Time that moves {@code stepNanos} on every read: a machine so slow that every check costs that much. */
    private static LongSupplier slow(long stepNanos) {
        AtomicLong now = new AtomicLong();
        return () -> now.addAndGet(stepNanos);
    }

    @Test
    @DisplayName("the budget is work: on a frozen clock the search ends by its budget, not cut off, and the same twice")
    void budgetIsWork() {
        LoadPlanRequest req = request(BUDGET, Duration.ofSeconds(12));
        LoadPlanResult first = planner(frozen()).plan(req);
        LoadPlanResult second = planner(frozen()).plan(req);

        assertSound(req, first);
        assertThat(first.meta().budgetExhausted()).isFalse();
        assertThat(first.meta().workTicks()).isPositive().isEqualTo(second.meta().workTicks());
        assertThat(first.vehicles()).hasSameSizeAs(second.vehicles());
        assertThat(first.unplaced()).isEmpty();
    }

    @Test
    @DisplayName("a bigger budget does more work on the same clock")
    void budgetSizesTheWork() {
        long small = planner(frozen()).plan(request(Duration.ofSeconds(1), Duration.ofSeconds(12))).meta().workTicks();
        long big = planner(frozen()).plan(request(Duration.ofSeconds(30), Duration.ofSeconds(12))).meta().workTicks();
        assertThat(big).isGreaterThan(small);
    }

    @Test
    @DisplayName("the wall limit stops a search the budget would let run on, and the best plan so far is returned")
    void wallLimitCutsTheSearch() {
        LoadPlanRequest req = request(Duration.ofSeconds(30), Duration.ofSeconds(12));
        long uncut = planner(frozen()).plan(req).meta().workTicks();

        // Every read of the clock costs 20 ms: the 12 s limit passes after 600 reads, long before the budget's work.
        LoadPlanResult cut = planner(slow(20_000_000L)).plan(req);

        assertSound(req, cut);
        assertThat(cut.meta().budgetExhausted()).isTrue();
        assertThat(cut.meta().workTicks()).isLessThan(uncut);
        assertThat(cut.vehicles()).isNotEmpty();
        // Cartons the search had no time for are reported as such, never dropped (the piece check in assertSound).
        assertThat(cut.unplaced()).allSatisfy(u -> assertThat(u.reason()).isEqualTo(UnplacedReason.SEARCH_CUT_OFF));
    }

    @Test
    @DisplayName("the limit is the request's wall limit, not its budget: a slow clock under a long limit is not cut off")
    void budgetDoesNotCap() {
        // 1 ms a read would pass a 5 s cap after 5 000 reads; the limit is 1 hour, so it never passes, whatever the
        // budget says.
        LoadPlanResult r = planner(slow(1_000L)).plan(request(Duration.ofSeconds(1), Duration.ofHours(1)));
        assertThat(r.meta().budgetExhausted()).isFalse();
        assertThat(r.unplaced()).isEmpty();
    }

    @Test
    @DisplayName("a smaller wall limit cuts earlier on the same clock")
    void smallerLimitCutsEarlier() {
        long at12 = planner(slow(20_000_000L)).plan(request(Duration.ofSeconds(30), Duration.ofSeconds(12)))
                .meta().workTicks();
        long at2 = planner(slow(20_000_000L)).plan(request(Duration.ofSeconds(30), Duration.ofSeconds(2)))
                .meta().workTicks();
        assertThat(at2).isLessThanOrEqualTo(at12);
    }

    @Test
    @DisplayName("the request defaults to a 12 s wall limit and refuses one that is not positive")
    void requestDefaultsAndValidation() {
        assertThat(LoadPlanRequest.of(CARTONS, TYPES, SelectionMode.FEWEST_VEHICLES).wallLimit())
                .isEqualTo(Duration.ofSeconds(12));
        assertThat(request(BUDGET, null).wallLimit()).isEqualTo(LoadPlanRequest.DEFAULT_WALL_LIMIT);
        assertThatThrownBy(() -> request(BUDGET, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(BUDGET, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
