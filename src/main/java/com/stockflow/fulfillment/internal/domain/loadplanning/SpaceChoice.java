package com.stockflow.fulfillment.internal.domain.loadplanning;

/**
 * Where the block filler puts its next block (SCRUM-496) - a deliberate deviation from the original, kept switchable so
 * the parity test can still run the original's rule.
 *
 * <p><b>Why.</b> The original offers every block for every free space and draws among the largest. With cartons that
 * are all different, a block is one carton, every space that takes the largest carton offers the same volume, and the
 * draw puts it in any of them: cartons scatter, the gaps between them are too small or too badly supported (BR-10's
 * 98 %) for anything else, the first vehicle reaches 65-78 % and the later ones 20-55 %. The original says as much
 * ({@code _consolidate_tail}: "hundreds of qty-1 lines starve GRASP").</p>
 *
 * <p><b>The rule.</b> {@link #IMPROVED} first picks the space whose corner is deepest (nearest the front wall,
 * smallest {@code z}), then lowest (smallest {@code y}), then leftmost (smallest {@code x}); the restricted draw by
 * volume ({@code alpha}) then runs among that space's blocks only. The load grows as walls from the front wall to the
 * door, which is docs 09 §4.2's "đặt từng kiện vào góc trống thấp nhất và sâu nhất". Measured on the PR #226 datasets
 * against the other orders tried (lowest before deepest, or the original's draw followed by moving the drawn block to
 * the lowest-deepest space that takes it, or no draw at all): the only one never worse than the original on any of
 * them, and the best (PR for SCRUM-496 has the table).</p>
 */
enum SpaceChoice {

    /** The original's rule: draw among every space's blocks. Only the parity test uses it. */
    PYTHON_PARITY,
    /** Deepest, then lowest, then leftmost space first; the draw among its blocks. What the planner uses. */
    IMPROVED;

    /** Whether the space {@code a} comes before {@code b}: deeper, then lower, then further left. */
    static boolean before(double[] a, double[] b) {
        int c = Double.compare(a[2], b[2]);
        if (c == 0) {
            c = Double.compare(a[1], b[1]);
        }
        if (c == 0) {
            c = Double.compare(a[0], b[0]);
        }
        return c < 0;
    }
}
