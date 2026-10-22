package com.stockflow.fulfillment.internal.domain.loadplanning;

/**
 * The clock the search reads instead of the wall clock.
 *
 * <p>The earlier solver budgeted every step in seconds ({@code time.time()}): how many restarts a container gets, when
 * the outer loops stop. Ported literally that makes the result depend on the machine and its load, and the plan must
 * be the same for the same input and seed. So the search counts work - one tick per greedy construction - and reads
 * {@link #now()} as ticks divided by a fixed rate. Every proportion of the original budget is kept, and the run is
 * reproducible.</p>
 *
 * <p>The caller's real time budget is still a hard cap: once it passes, {@link #now()} reads infinity, every loop
 * that waits on the clock stops, and the planner returns the best plan it has. Only a run that hits this cap can
 * differ from another run of the same input.</p>
 */
final class WorkClock {

    private final double ticksPerSecond;
    private final long wallDeadlineNanos;
    private long ticks;
    private boolean expired;

    WorkClock(double ticksPerSecond, long wallBudgetNanos) {
        this.ticksPerSecond = ticksPerSecond;
        this.wallDeadlineNanos = System.nanoTime() + wallBudgetNanos;
    }

    private long lastImprovementNanos;

    void tick() {
        ticks++;
    }

    /** The search found a better plan: remembered so the caller can report the time to the best result. */
    void improved() {
        lastImprovementNanos = System.nanoTime();
    }

    long lastImprovementNanos() {
        return lastImprovementNanos;
    }

    /** Seconds of work done, or infinity once the real budget is spent. */
    double now() {
        if (!expired && System.nanoTime() - wallDeadlineNanos >= 0) {
            expired = true;
        }
        return expired ? Double.POSITIVE_INFINITY : ticks / ticksPerSecond;
    }

    boolean expired() {
        now();
        return expired;
    }

    long ticks() {
        return ticks;
    }
}
