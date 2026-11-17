package com.stockflow.warehouse.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The order a putaway trip visits its bins in, and where the clerk stands at each (kltn-docs 05 §4.5 step 14, BR-17;
 * 06 §4.4 step 6). Pure geometry on the map's own coordinates: nothing here is stored, the trip keeps only the stop
 * number of each carton.
 *
 * <p><b>Stand point.</b> A bin is reached from a pick face of its shelf: the point on the face nearest the bin, straight
 * in front of the bin's centre. A shelf with several faces offers the nearest one; a shelf that declares none (it
 * holds no pickable bin, 06 BR-08) is reached from its nearest side. An area is reached at its centre.</p>
 *
 * <p><b>Order.</b> Nearest neighbour from the start: always the unvisited stop with the smallest Manhattan distance
 * (|dx| + |dy|) from where the clerk stands, the location code deciding a tie. OVERFLOW areas come last whatever
 * their distance (BR-17), and a location the map cannot place comes after every one it can.</p>
 *
 * <p>The distance crosses shelves and walls: aisles are not on the map (open question C46). The route is advice - the
 * clerk may visit the stops in any order.</p>
 */
public final class PutawayRoute {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private PutawayRoute() {
    }

    /** A point of the warehouse map, in the map unit (metres). */
    public record Point(BigDecimal x, BigDecimal y) {
        public Point {
            Objects.requireNonNull(x, "x");
            Objects.requireNonNull(y, "y");
        }

        public BigDecimal manhattanTo(Point other) {
            return x.subtract(other.x).abs().add(y.subtract(other.y).abs());
        }
    }

    /**
     * A place the trip stops at.
     *
     * @param point    where the clerk stands; null when the map cannot say
     * @param overflow an OVERFLOW area: visited last
     */
    public record Stop(UUID locationId, String locationCode, Point point, boolean overflow) {
        public Stop {
            Objects.requireNonNull(locationId, "locationId");
            Objects.requireNonNull(locationCode, "locationCode");
        }
    }

    /**
     * Where the clerk stands for a bin of a shelf.
     *
     * @param shelf the shelf's footprint on the map: {@code (x, y)} its top-left corner after rotation
     * @param faces the sides it is reached from, in its own frame (north = its top edge before rotation)
     * @param binX  the bin's corner inside the shelf's own frame, before rotation
     */
    public static Point standPointOfBin(Footprint shelf, PickFaces faces, BigDecimal binX, BigDecimal binY,
                                        BigDecimal binWidth, BigDecimal binLength) {
        BigDecimal width = shelf.width();
        BigDecimal length = shelf.length();
        BigDecimal cx = clamp(binX.add(half(binWidth)), width);
        BigDecimal cy = clamp(binY.add(half(binLength)), length);
        PickFaces reachable = faces == null || !faces.hasAny() ? new PickFaces(true, true, true, true) : faces;

        // The nearest reachable side, north, east, south, west deciding a tie.
        BigDecimal best = null;
        Point inFrame = null;
        if (reachable.north()) {
            best = cy;
            inFrame = new Point(cx, BigDecimal.ZERO);
        }
        if (reachable.east() && (best == null || width.subtract(cx).compareTo(best) < 0)) {
            best = width.subtract(cx);
            inFrame = new Point(width, cy);
        }
        if (reachable.south() && (best == null || length.subtract(cy).compareTo(best) < 0)) {
            best = length.subtract(cy);
            inFrame = new Point(cx, length);
        }
        if (reachable.west() && (best == null || cx.compareTo(best) < 0)) {
            inFrame = new Point(BigDecimal.ZERO, cy);
        }
        return onMap(shelf, Objects.requireNonNull(inFrame));
    }

    /** Where the clerk stands for an area: its centre. */
    public static Point standPointOfArea(Footprint area) {
        return new Point(area.x().add(half(area.effectiveWidth())), area.y().add(half(area.effectiveLength())));
    }

    /**
     * Turns a point of the shelf's own frame onto the map. Quarter turns are clockwise, and {@code (x, y)} of the
     * footprint is the top-left corner of the turned shape ({@link Footprint}).
     */
    private static Point onMap(Footprint shelf, Point p) {
        BigDecimal width = shelf.width();
        BigDecimal length = shelf.length();
        Point turned = switch (shelf.rotation()) {
            case 90 -> new Point(length.subtract(p.y()), p.x());
            case 180 -> new Point(width.subtract(p.x()), length.subtract(p.y()));
            case 270 -> new Point(p.y(), width.subtract(p.x()));
            default -> p;
        };
        return new Point(shelf.x().add(turned.x()), shelf.y().add(turned.y()));
    }

    /**
     * The stops in visiting order.
     *
     * @param start where the clerk sets off from; null when the map cannot say, and then the first stop is the one
     *              first by code
     */
    public static List<Stop> order(Point start, Collection<Stop> stops) {
        Comparator<Stop> byCode = Comparator.comparing(Stop::locationCode);
        List<Stop> placed = new ArrayList<>(stops.stream()
                .filter(stop -> !stop.overflow() && stop.point() != null).sorted(byCode).toList());
        List<Stop> route = new ArrayList<>();
        Point here = start;
        while (!placed.isEmpty()) {
            Stop next = placed.get(0);
            if (here != null) {
                for (Stop candidate : placed) {
                    // placed is in code order, so a strictly smaller distance is what moves the choice.
                    if (candidate.point().manhattanTo(here).compareTo(next.point().manhattanTo(here)) < 0) {
                        next = candidate;
                    }
                }
            }
            placed.remove(next);
            route.add(next);
            here = next.point();
        }
        stops.stream().filter(stop -> !stop.overflow() && stop.point() == null).sorted(byCode).forEach(route::add);
        stops.stream().filter(Stop::overflow).sorted(byCode).forEach(route::add);
        return route;
    }

    private static BigDecimal half(BigDecimal value) {
        return value.divide(TWO, 3, RoundingMode.HALF_UP);
    }

    /** A bin drawn a hair outside its shelf still stands in front of it. */
    private static BigDecimal clamp(BigDecimal value, BigDecimal max) {
        return value.signum() < 0 ? BigDecimal.ZERO : value.compareTo(max) > 0 ? max : value;
    }
}
