package com.stockflow.warehouse.internal.domain;

import com.stockflow.warehouse.internal.domain.PutawayRoute.Point;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.IntConsumer;

/**
 * The line a putaway trip draws between two of its points on the warehouse map: round shelves, through open doors,
 * never through a wall or a closed door (issue #410; kltn-docs 05 §4.5 step 14, 06 §4.4 step 6). Pure geometry on one
 * warehouse's map, built once and asked for every leg of the route.
 *
 * <p><b>An estimate.</b> Aisles are not on the map (open question C46), so free space is whatever no obstacle covers:
 * a shelf or area flagged {@code is_obstacle} - whatever its status, an inactive shelf still stands there - a wall, and
 * a door that is closed or not passable. An open door drawn over a wall cuts its span out of the wall. The map's frame
 * is the outer limit. Only the line drawn changes: the stop order is still {@link PutawayRoute#order}'s.</p>
 *
 * <p><b>How.</b> A grid finds the way round (cells of 0.5 m, wider on a map that would need more than
 * {@value #MAX_CELLS} of them), A* with 8 moves walks it, and string pulling straightens the result. The grid only
 * decides the topology: a cell is blocked when its centre is strictly inside an obstacle, and every move, like every
 * straightened segment, is checked against the exact shapes - so a narrow aisle the grid does not line up with is
 * still used, and walls do not eat aisle width. A segment may slide along an obstacle's edge but never enters it, and
 * may not touch a wall or a closed door at all.</p>
 *
 * <p><b>Endpoints</b> are kept exactly as given, never snapped: a stand point lies on a shelf's pick face and an area
 * stop at the area's centre, so each end is joined to the nearest free cell it can see within {@value #SNAP_RADIUS} m.
 * An obstacle strictly containing an end (an area stop whose area is flagged an obstacle) is ignored for segments
 * starting or ending there, and the {@value #SNAP_RADIUS} m are then counted from that obstacle's nearest edge.</p>
 *
 * <p>An instance is not thread-safe: it caches the moves it has checked.</p>
 */
public final class WalkingPath {

    /** The grid step on an ordinary map, in metres. */
    static final double STEP = 0.5;
    /** The most cells a grid gets; a larger map widens the step instead. */
    static final int MAX_CELLS = 200_000;
    /** How far an end looks for the grid. */
    static final double SNAP_RADIUS = 3.0;

    /** "On the edge" and "touching" tolerance: the map is in millimetres, so this is far below anything drawn. */
    private static final double EPS = 1e-9;
    /** Collinearity tolerance for a door lying on a wall. */
    private static final double ON_LINE = 1e-6;
    private static final double SQRT2 = Math.sqrt(2);
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final byte UNKNOWN = 0;
    private static final byte ALLOWED = 1;
    private static final byte REFUSED = 2;

    /**
     * A door on the map.
     *
     * @param opening whether people walk through it: passable and OPEN. Any other door blocks like a wall
     */
    public record Door(Segment line, boolean opening) {
        public Door {
            Objects.requireNonNull(line, "line");
        }
    }

    /** An axis-aligned obstacle on the map. */
    record Box(double minX, double minY, double maxX, double maxY) {

        static Box of(Footprint footprint) {
            double x = footprint.x().doubleValue();
            double y = footprint.y().doubleValue();
            return new Box(x, y, x + footprint.effectiveWidth().doubleValue(),
                    y + footprint.effectiveLength().doubleValue());
        }

        boolean strictlyContains(double x, double y) {
            return x > minX + EPS && x < maxX - EPS && y > minY + EPS && y < maxY - EPS;
        }

        /** Distance from an inside point to the nearest edge. */
        double depthOf(double x, double y) {
            return Math.min(Math.min(x - minX, maxX - x), Math.min(y - minY, maxY - y));
        }

        /**
         * Whether the segment passes through the interior (Liang-Barsky against the closed box, then the middle of
         * the clipped piece tested strictly). Sliding along an edge or touching a corner is not entering.
         */
        boolean entered(double ax, double ay, double bx, double by) {
            double dx = bx - ax;
            double dy = by - ay;
            double[] p = {-dx, dx, -dy, dy};
            double[] q = {ax - minX, maxX - ax, ay - minY, maxY - ay};
            double t0 = 0;
            double t1 = 1;
            for (int k = 0; k < 4; k++) {
                if (Math.abs(p[k]) < EPS) {
                    if (q[k] < -EPS) {
                        return false;
                    }
                } else {
                    double r = q[k] / p[k];
                    if (p[k] < 0) {
                        t0 = Math.max(t0, r);
                    } else {
                        t1 = Math.min(t1, r);
                    }
                    if (t0 > t1) {
                        return false;
                    }
                }
            }
            if ((t1 - t0) * Math.hypot(dx, dy) <= EPS) {
                return false;
            }
            double mid = (t0 + t1) / 2;
            return strictlyContains(ax + mid * dx, ay + mid * dy);
        }
    }

    /** A straight line nothing crosses or touches: a piece of wall, or a closed door. */
    record Line(double ax, double ay, double bx, double by) {

        boolean touches(double px, double py, double qx, double qy) {
            double d1 = orient(ax, ay, bx, by, px, py);
            double d2 = orient(ax, ay, bx, by, qx, qy);
            double d3 = orient(px, py, qx, qy, ax, ay);
            double d4 = orient(px, py, qx, qy, bx, by);
            if (((d1 > EPS && d2 < -EPS) || (d1 < -EPS && d2 > EPS))
                    && ((d3 > EPS && d4 < -EPS) || (d3 < -EPS && d4 > EPS))) {
                return true;
            }
            return (Math.abs(d1) <= EPS && within(ax, ay, bx, by, px, py))
                    || (Math.abs(d2) <= EPS && within(ax, ay, bx, by, qx, qy))
                    || (Math.abs(d3) <= EPS && within(px, py, qx, qy, ax, ay))
                    || (Math.abs(d4) <= EPS && within(px, py, qx, qy, bx, by));
        }

        private static double orient(double ax, double ay, double bx, double by, double cx, double cy) {
            return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        }

        private static boolean within(double ax, double ay, double bx, double by, double px, double py) {
            return px >= Math.min(ax, bx) - EPS && px <= Math.max(ax, bx) + EPS
                    && py >= Math.min(ay, by) - EPS && py <= Math.max(ay, by) + EPS;
        }
    }

    private final double width;
    private final double height;
    private final double step;
    private final int cols;
    private final int rows;
    private final List<Box> boxes;
    private final List<Line> lines;
    private final boolean[] free;
    private final byte[] moves;
    private final ShapeIndex index;

    private WalkingPath(double width, double height, List<Box> boxes, List<Line> lines) {
        this.width = width;
        this.height = height;
        this.boxes = boxes;
        this.lines = lines;
        double area = width * height;
        this.step = area / (STEP * STEP) > MAX_CELLS ? Math.sqrt(area / MAX_CELLS) : STEP;
        this.cols = Math.max(1, (int) Math.ceil(width / step - EPS));
        this.rows = Math.max(1, (int) Math.ceil(height / step - EPS));
        this.index = new ShapeIndex(width, height, boxes, lines);
        this.free = new boolean[cols * rows];
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                free[j * cols + i] = centreX(i) <= width + EPS && centreY(j) <= height + EPS;
            }
        }
        for (Box box : boxes) {
            int i0 = Math.max(0, (int) Math.floor(box.minX() / step - 0.5));
            int i1 = Math.min(cols - 1, (int) Math.ceil(box.maxX() / step - 0.5));
            int j0 = Math.max(0, (int) Math.floor(box.minY() / step - 0.5));
            int j1 = Math.min(rows - 1, (int) Math.ceil(box.maxY() / step - 0.5));
            for (int j = j0; j <= j1; j++) {
                for (int i = i0; i <= i1; i++) {
                    if (box.strictlyContains(centreX(i), centreY(j))) {
                        free[j * cols + i] = false;
                    }
                }
            }
        }
        this.moves = new byte[cols * rows * DX.length];
    }

    /**
     * The walkable map of one warehouse.
     *
     * @param mapWidth  the map's extent along x; {@code mapHeight} along y
     * @param obstacles the footprints of every shelf and area flagged an obstacle, whatever their status
     * @param walls     every wall
     * @param doors     every door
     */
    public static WalkingPath of(BigDecimal mapWidth, BigDecimal mapHeight, Collection<Footprint> obstacles,
                                 Collection<Segment> walls, Collection<Door> doors) {
        List<Box> boxes = obstacles.stream().map(Box::of).toList();
        List<Line> lines = new ArrayList<>();
        List<Line> openings = doors.stream().filter(Door::opening).map(door -> line(door.line())).toList();
        for (Segment wall : walls) {
            lines.addAll(cut(line(wall), openings));
        }
        doors.stream().filter(door -> !door.opening()).map(door -> line(door.line())).forEach(lines::add);
        return new WalkingPath(mapWidth.doubleValue(), mapHeight.doubleValue(), boxes, List.copyOf(lines));
    }

    /** The grid step this map uses, in metres. */
    public double step() {
        return step;
    }

    /**
     * The walking line from {@code from} to {@code to}, both ends included and kept exactly; the corners between them
     * at three decimals. Empty when either end lies outside the map or no way leads from one to the other. Two equal
     * points give {@code [p, p]}.
     */
    public Optional<List<Point>> between(Point from, Point to) {
        double fx = from.x().doubleValue();
        double fy = from.y().doubleValue();
        double tx = to.x().doubleValue();
        double ty = to.y().doubleValue();
        if (!onMap(fx, fy) || !onMap(tx, ty)) {
            return Optional.empty();
        }
        if (from.x().compareTo(to.x()) == 0 && from.y().compareTo(to.y()) == 0) {
            return Optional.of(List.of(from, to));
        }
        if (clear(fx, fy, true, tx, ty, true)) {
            return Optional.of(List.of(from, to));
        }
        int start = snap(fx, fy);
        int goal = snap(tx, ty);
        if (start < 0 || goal < 0) {
            return Optional.empty();
        }
        int[] cells = search(start, goal);
        if (cells == null) {
            return Optional.empty();
        }
        double[][] points = new double[cells.length + 2][];
        points[0] = new double[]{fx, fy};
        for (int k = 0; k < cells.length; k++) {
            points[k + 1] = new double[]{centreX(cells[k] % cols), centreY(cells[k] / cols)};
        }
        points[points.length - 1] = new double[]{tx, ty};
        List<Point> path = new ArrayList<>();
        path.add(from);
        int last = points.length - 1;
        int i = 0;
        while (i < last) {
            int j = last;
            while (j > i + 1 && !clear(points[i][0], points[i][1], i == 0, points[j][0], points[j][1], j == last)) {
                j--;
            }
            path.add(j == last ? to : point(points[j][0], points[j][1]));
            i = j;
        }
        return Optional.of(List.copyOf(path));
    }

    /**
     * The line of every leg of a route, one per stop: from where the clerk is before it - {@code origin} for the
     * first, else the last stop before it that has a point - to the stop. Empty for a stop with no point, for a stop
     * with no point before it to start from, and where {@link #between} finds no way.
     *
     * @param origin where the route sets off; null when the map cannot say
     * @param stops  each stop's point in visiting order; null where the map cannot say
     */
    public List<Optional<List<Point>>> legs(Point origin, List<Point> stops) {
        List<Optional<List<Point>>> legs = new ArrayList<>();
        Point here = origin;
        for (Point stop : stops) {
            legs.add(here == null || stop == null ? Optional.empty() : between(here, stop));
            if (stop != null) {
                here = stop;
            }
        }
        return legs;
    }

    /**
     * Whether the straight segment is walkable: it enters no obstacle and touches no wall or closed door. An end
     * marked {@code exempt} is a route endpoint, and an obstacle strictly containing it does not count.
     */
    boolean clear(double ax, double ay, boolean exemptA, double bx, double by, boolean exemptB) {
        for (Box box : index.boxesNear(ax, ay, bx, by)) {
            if ((exemptA && box.strictlyContains(ax, ay)) || (exemptB && box.strictlyContains(bx, by))) {
                continue;
            }
            if (box.entered(ax, ay, bx, by)) {
                return false;
            }
        }
        for (Line line : index.linesNear(ax, ay, bx, by)) {
            if (line.touches(ax, ay, bx, by)) {
                return false;
            }
        }
        return true;
    }

    /** The obstacles and blocking lines of this map, for checking a path from outside. */
    List<Box> boxes() {
        return boxes;
    }

    List<Line> lines() {
        return lines;
    }

    private boolean onMap(double x, double y) {
        return x >= -EPS && y >= -EPS && x <= width + EPS && y <= height + EPS;
    }

    /** The nearest free cell an end sees in a straight line, or -1. */
    private int snap(double x, double y) {
        double radius = SNAP_RADIUS;
        for (Box box : boxes) {
            if (box.strictlyContains(x, y)) {
                radius = Math.max(radius, SNAP_RADIUS + box.depthOf(x, y));
            }
        }
        int i0 = Math.max(0, (int) Math.floor((x - radius) / step));
        int i1 = Math.min(cols - 1, (int) Math.ceil((x + radius) / step));
        int j0 = Math.max(0, (int) Math.floor((y - radius) / step));
        int j1 = Math.min(rows - 1, (int) Math.ceil((y + radius) / step));
        List<double[]> candidates = new ArrayList<>();
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                int cell = j * cols + i;
                double distance = Math.hypot(centreX(i) - x, centreY(j) - y);
                if (free[cell] && distance <= radius + EPS) {
                    candidates.add(new double[]{distance, cell});
                }
            }
        }
        candidates.sort(Comparator.<double[]>comparingDouble(c -> c[0]).thenComparingDouble(c -> c[1]));
        for (double[] candidate : candidates) {
            int cell = (int) candidate[1];
            if (clear(x, y, true, centreX(cell % cols), centreY(cell / cols), false)) {
                return cell;
            }
        }
        return -1;
    }

    /** A* on the grid, octile heuristic; the cells from start to goal, or null. */
    private int[] search(int start, int goal) {
        double[] cost = new double[free.length];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        int[] parent = new int[free.length];
        boolean[] done = new boolean[free.length];
        int gx = goal % cols;
        int gy = goal / cols;
        PriorityQueue<double[]> open = new PriorityQueue<>(
                Comparator.<double[]>comparingDouble(n -> n[0]).thenComparingDouble(n -> n[1]));
        cost[start] = 0;
        parent[start] = -1;
        open.add(new double[]{octile(start % cols, start / cols, gx, gy), start});
        while (!open.isEmpty()) {
            int cell = (int) open.poll()[1];
            if (done[cell]) {
                continue;
            }
            if (cell == goal) {
                return trace(parent, goal);
            }
            done[cell] = true;
            int cx = cell % cols;
            int cy = cell / cols;
            for (int d = 0; d < DX.length; d++) {
                if (!movable(cell, cx, cy, d)) {
                    continue;
                }
                int next = (cy + DY[d]) * cols + cx + DX[d];
                double through = cost[cell] + (d < 4 ? 1 : SQRT2);
                if (!done[next] && through < cost[next]) {
                    cost[next] = through;
                    parent[next] = cell;
                    open.add(new double[]{through + octile(cx + DX[d], cy + DY[d], gx, gy), next});
                }
            }
        }
        return null;
    }

    private static int[] trace(int[] parent, int goal) {
        List<Integer> reversed = new ArrayList<>();
        for (int cell = goal; cell >= 0; cell = parent[cell]) {
            reversed.add(cell);
        }
        int[] cells = new int[reversed.size()];
        for (int k = 0; k < cells.length; k++) {
            cells[k] = reversed.get(cells.length - 1 - k);
        }
        return cells;
    }

    /** A move to a free neighbour, no corner cut, checked against the exact shapes once and remembered. */
    private boolean movable(int cell, int cx, int cy, int d) {
        int known = moves[cell * DX.length + d];
        if (known != UNKNOWN) {
            return known == ALLOWED;
        }
        int nx = cx + DX[d];
        int ny = cy + DY[d];
        boolean allowed = nx >= 0 && ny >= 0 && nx < cols && ny < rows && free[ny * cols + nx]
                && (d < 4 || (free[cy * cols + nx] && free[ny * cols + cx]))
                && clear(centreX(cx), centreY(cy), false, centreX(nx), centreY(ny), false);
        moves[cell * DX.length + d] = allowed ? ALLOWED : REFUSED;
        return allowed;
    }

    private static double octile(int ax, int ay, int bx, int by) {
        int dx = Math.abs(ax - bx);
        int dy = Math.abs(ay - by);
        return dx + dy + (SQRT2 - 2) * Math.min(dx, dy);
    }

    private double centreX(int i) {
        return (i + 0.5) * step;
    }

    private double centreY(int j) {
        return (j + 0.5) * step;
    }

    private static Point point(double x, double y) {
        return new Point(BigDecimal.valueOf(x).setScale(3, RoundingMode.HALF_UP),
                BigDecimal.valueOf(y).setScale(3, RoundingMode.HALF_UP));
    }

    private static Line line(Segment segment) {
        return new Line(segment.startX().doubleValue(), segment.startY().doubleValue(), segment.endX().doubleValue(),
                segment.endY().doubleValue());
    }

    /** What is left of a wall once every open door lying on it is cut out. */
    static List<Line> cut(Line wall, List<Line> openings) {
        double dx = wall.bx() - wall.ax();
        double dy = wall.by() - wall.ay();
        double length = Math.hypot(dx, dy);
        List<double[]> gaps = new ArrayList<>();
        for (Line door : openings) {
            if (distanceToLine(wall, door.ax(), door.ay(), length) > ON_LINE
                    || distanceToLine(wall, door.bx(), door.by(), length) > ON_LINE) {
                continue;
            }
            double ta = ((door.ax() - wall.ax()) * dx + (door.ay() - wall.ay()) * dy) / (length * length);
            double tb = ((door.bx() - wall.ax()) * dx + (door.by() - wall.ay()) * dy) / (length * length);
            double lo = Math.max(0, Math.min(ta, tb));
            double hi = Math.min(1, Math.max(ta, tb));
            if ((hi - lo) * length > EPS) {
                gaps.add(new double[]{lo, hi});
            }
        }
        if (gaps.isEmpty()) {
            return List.of(wall);
        }
        gaps.sort(Comparator.comparingDouble(g -> g[0]));
        List<Line> pieces = new ArrayList<>();
        double from = 0;
        for (double[] gap : gaps) {
            if ((gap[0] - from) * length > EPS) {
                pieces.add(new Line(wall.ax() + from * dx, wall.ay() + from * dy, wall.ax() + gap[0] * dx,
                        wall.ay() + gap[0] * dy));
            }
            from = Math.max(from, gap[1]);
        }
        if ((1 - from) * length > EPS) {
            pieces.add(new Line(wall.ax() + from * dx, wall.ay() + from * dy, wall.bx(), wall.by()));
        }
        return pieces;
    }

    private static double distanceToLine(Line line, double px, double py, double length) {
        return Math.abs((line.bx() - line.ax()) * (py - line.ay()) - (line.by() - line.ay()) * (px - line.ax()))
                / length;
    }

    /**
     * Shapes bucketed by area, so checking a short move looks at the few shapes near it rather than every one on the
     * map. A shape is listed in every bucket its bounding box touches; a query may see one twice, which costs a
     * check and changes nothing.
     */
    private static final class ShapeIndex {

        private static final double BUCKET = 4.0;

        private final int bucketCols;
        private final int bucketRows;
        private final List<List<Box>> boxBuckets;
        private final List<List<Line>> lineBuckets;

        ShapeIndex(double width, double height, List<Box> boxes, List<Line> lines) {
            this.bucketCols = Math.max(1, (int) Math.ceil(width / BUCKET) + 1);
            this.bucketRows = Math.max(1, (int) Math.ceil(height / BUCKET) + 1);
            this.boxBuckets = new ArrayList<>();
            this.lineBuckets = new ArrayList<>();
            for (int k = 0; k < bucketCols * bucketRows; k++) {
                boxBuckets.add(new ArrayList<>());
                lineBuckets.add(new ArrayList<>());
            }
            for (Box box : boxes) {
                forEachBucket(box.minX(), box.minY(), box.maxX(), box.maxY(), k -> boxBuckets.get(k).add(box));
            }
            for (Line line : lines) {
                forEachBucket(Math.min(line.ax(), line.bx()), Math.min(line.ay(), line.by()),
                        Math.max(line.ax(), line.bx()), Math.max(line.ay(), line.by()),
                        k -> lineBuckets.get(k).add(line));
            }
        }

        List<Box> boxesNear(double ax, double ay, double bx, double by) {
            List<Box> near = new ArrayList<>();
            forEachBucket(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by),
                    k -> near.addAll(boxBuckets.get(k)));
            return near;
        }

        List<Line> linesNear(double ax, double ay, double bx, double by) {
            List<Line> near = new ArrayList<>();
            forEachBucket(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by),
                    k -> near.addAll(lineBuckets.get(k)));
            return near;
        }

        private void forEachBucket(double minX, double minY, double maxX, double maxY,
                                   IntConsumer action) {
            int i0 = clamp((int) Math.floor((minX - EPS) / BUCKET), bucketCols);
            int i1 = clamp((int) Math.floor((maxX + EPS) / BUCKET), bucketCols);
            int j0 = clamp((int) Math.floor((minY - EPS) / BUCKET), bucketRows);
            int j1 = clamp((int) Math.floor((maxY + EPS) / BUCKET), bucketRows);
            for (int j = j0; j <= j1; j++) {
                for (int i = i0; i <= i1; i++) {
                    action.accept(j * bucketCols + i);
                }
            }
        }

        private static int clamp(int value, int size) {
            return Math.max(0, Math.min(size - 1, value));
        }
    }
}
