package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Port of the uniform-grid spatial index of {@code solver_3d.py} ({@code _cells_for}, {@code _grid_register},
 * {@code _grid_support}).
 *
 * <p>A placed carton is registered in every 600 mm cell its box touches, so a support query reads only the cells
 * under the candidate's footprint instead of every carton in the vehicle - exactly the same answer, far cheaper.</p>
 */
final class SupportGrid {

    static final double CELL = 600.0;
    static final double EPS = 1e-9;

    /** Query ids, unique across grids: a placement may sit in several copies of a grid. */
    private static final AtomicLong QUERIES = new AtomicLong();

    private final Map<Long, List<BoxPlacement>> cells = new HashMap<>();

    private static long key(int ci, int cj, int ck) {
        return ((long) (ci & 0x1FFFFF) << 42) | ((long) (cj & 0x1FFFFF) << 21) | (ck & 0x1FFFFF);
    }

    private static int cell(double v) {
        return (int) Math.floor(v / CELL);
    }

    /** An independent copy, so a restart can start from the same pinned cargo without re-registering it. */
    SupportGrid copy() {
        SupportGrid out = new SupportGrid();
        cells.forEach((k, v) -> out.cells.put(k, new ArrayList<>(v)));
        return out;
    }

    void register(BoxPlacement p) {
        for (int ci = cell(p.x); ci <= cell(p.x + p.w - EPS); ci++) {
            for (int cj = cell(p.y); cj <= cell(p.y + p.h - EPS); cj++) {
                for (int ck = cell(p.z); ck <= cell(p.z + p.d - EPS); ck++) {
                    cells.computeIfAbsent(key(ci, cj, ck), k -> new ArrayList<>()).add(p);
                }
            }
        }
    }

    /** {@code _grid_support}: the share of a {@code w x d} base at height {@code y} resting on the floor or on tops. */
    double support(double x, double y, double z, double w, double d) {
        if (y <= EPS) {
            return 1.0;
        }
        double base = w * d;
        if (base <= 0) {
            return 1.0;
        }
        int ky = cell(y - EPS);
        long query = QUERIES.incrementAndGet();
        double covered = 0.0;
        for (int ci = cell(x); ci <= cell(x + w - EPS); ci++) {
            for (int ck = cell(z); ck <= cell(z + d - EPS); ck++) {
                List<BoxPlacement> in = cells.get(key(ci, ky, ck));
                if (in == null) {
                    continue;
                }
                for (BoxPlacement p : in) {
                    if (p.seenBy == query) {
                        continue;
                    }
                    p.seenBy = query;
                    if (Math.abs((p.y + p.h) - y) > EPS) {
                        continue;
                    }
                    double ox = Math.min(x + w, p.x + p.w) - Math.max(x, p.x);
                    double oz = Math.min(z + d, p.z + p.d) - Math.max(z, p.z);
                    if (ox > EPS && oz > EPS) {
                        covered += ox * oz;
                    }
                }
            }
        }
        return covered / base;
    }
}
