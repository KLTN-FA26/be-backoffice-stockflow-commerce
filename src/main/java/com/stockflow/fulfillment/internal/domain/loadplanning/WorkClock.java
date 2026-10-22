package com.stockflow.fulfillment.internal.domain.loadplanning;

/**
 * The clock the search reads instead of the wall clock.
 *
 * <p>The earlier solver budgeted every step in seconds ({@code time.time()}): how many restarts a container gets, when
 * the outer loops stop. Ported literally that makes the result depend on the machine and its load, and the plan must
 * be the same for the same input and seed. So the search counts work and reads {@link #now()} as work divided by a
 * fixed rate. Every proportion of the original budget is kept, and the run is reproducible.</p>
 *
 * <p>Two units of work:</p>
 * <ul>
 *   <li>{@link Unit#CONSTRUCTION} - one tick per greedy construction, as the original's restart loops count. The
 *       parity test uses it, because it lets the original (its clock patched to count constructions) run the very same
 *       search.</li>
 *   <li>{@link Unit#EVALUATION} - one tick per candidate the fillers look at. A construction costs a fraction of a
 *       millisecond on a few carton sizes and tens of milliseconds on a hundred, so counting constructions made the
 *       budget mean very different times on different loads. Counting evaluations tracks CPU time across loads and is
 *       still deterministic. The planner uses it.</li>
 * </ul>
 *
 * <p>The caller's real time budget is a hard cap: the fillers check {@link #expired()} on every step, every loop that
 * waits on the clock stops, and the planner returns the best plan it has. Only a run that hits the cap can differ from
 * another run of the same input.</p>
 */
final class WorkClock {

    enum Unit { CONSTRUCTION, EVALUATION }

    private final Unit unit;
    private final double ticksPerSecond;
    private final long wallDeadlineNanos;
    private long ticks;
    private boolean expired;
    private long lastImprovementNanos;

    WorkClock(Unit unit, double ticksPerSecond, long wallDeadlineNanos) {
        this.unit = unit;
        this.ticksPerSecond = ticksPerSecond;
        this.wallDeadlineNanos = wallDeadlineNanos;
    }

    /** A clock whose real budget starts now. */
    static WorkClock starting(Unit unit, double ticksPerSecond, long wallBudgetNanos) {
        return new WorkClock(unit, ticksPerSecond, System.nanoTime() + wallBudgetNanos);
    }

    /**
     * One greedy construction started. It counts as one unit of work in either mode: a construction that finds nothing
     * to look at must still move the clock, or a loop waiting on it would spin until the real budget ran out.
     */
    void construction() {
        ticks++;
    }

    /** {@code n} candidates looked at. */
    void evaluated(long n) {
        if (unit == Unit.EVALUATION) {
            ticks += n;
        }
    }

    /** The search found a better plan: remembered so the caller can report the time to the best result. */
    void improved() {
        lastImprovementNanos = System.nanoTime();
    }

    /** Carry over the time another clock's search found the plan that is kept. */
    void improvedAt(long nanos) {
        lastImprovementNanos = nanos;
    }

    long lastImprovementNanos() {
        return lastImprovementNanos;
    }

    /** Seconds of work done, or infinity once the real budget is spent. */
    double now() {
        return expired() ? Double.POSITIVE_INFINITY : ticks / ticksPerSecond;
    }

    boolean expired() {
        if (!expired && System.nanoTime() - wallDeadlineNanos >= 0) {
            expired = true;
        }
        return expired;
    }

    long ticks() {
        return ticks;
    }

    long wallDeadlineNanos() {
        return wallDeadlineNanos;
    }
}
