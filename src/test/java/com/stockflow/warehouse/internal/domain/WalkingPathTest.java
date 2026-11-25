package com.stockflow.warehouse.internal.domain;

import com.stockflow.warehouse.internal.domain.PutawayRoute.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line a putaway trip draws between its points (issue #410; kltn-docs 05 §4.5 step 14): round shelves, through
 * open doors, never through a wall or a closed door, and the ends exactly where they were asked for.
 *
 * <p>Every path a test gets is also checked by {@link #walkable}: no segment enters an obstacle (but the one an end
 * stands inside), checked by sampling rather than by the class's own predicate, none touches a wall or a closed door,
 * and away from its two ends the path keeps {@link WalkingPath#CLEARANCE} from every obstacle (issue #446).</p>
 */
class WalkingPathTest {

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private static Point p(String x, String y) {
        return new Point(n(x), n(y));
    }

    private static Footprint box(String x, String y, String width, String length, int rotation) {
        return new Footprint(n(x), n(y), n(width), n(length), rotation);
    }

    private static Segment line(String ax, String ay, String bx, String by) {
        return new Segment(n(ax), n(ay), n(bx), n(by));
    }

    private static WalkingPath map(String width, String height, List<Footprint> obstacles) {
        return WalkingPath.of(n(width), n(height), obstacles, List.of(), List.of());
    }

    /** The path, after checking it is walkable on {@code map} and runs from {@code from} to {@code to}. */
    private static List<Point> walk(WalkingPath map, Point from, Point to) {
        Optional<List<Point>> found = map.between(from, to);
        assertThat(found).as("a way from %s to %s", from, to).isPresent();
        List<Point> path = found.get();
        assertThat(path.get(0)).isEqualTo(from);
        assertThat(path.get(path.size() - 1)).isEqualTo(to);
        walkable(map, path);
        return path;
    }

    /**
     * No segment enters an obstacle other than one an end of the path is inside; none touches a blocking line; and
     * every point of the path more than the clearance from both ends - past the link to the stand-off - keeps the
     * clearance from those obstacles (to a millimetre, the corners are rounded to three decimals).
     */
    static void walkable(WalkingPath map, List<Point> path) {
        double[] first = xy(path.get(0));
        double[] last = xy(path.get(path.size() - 1));
        double nearEnd = WalkingPath.CLEARANCE + 0.01;
        for (int k = 0; k + 1 < path.size(); k++) {
            double[] a = xy(path.get(k));
            double[] b = xy(path.get(k + 1));
            for (WalkingPath.Box box : map.boxes()) {
                boolean ownBox = (k == 0 && box.strictlyContains(first[0], first[1]))
                        || (k + 2 == path.size() && box.strictlyContains(last[0], last[1]));
                if (ownBox) {
                    continue;
                }
                for (int s = 1; s < 400; s++) {
                    double t = s / 400.0;
                    double x = a[0] + t * (b[0] - a[0]);
                    double y = a[1] + t * (b[1] - a[1]);
                    assertThat(x > box.minX() + 1e-6 && x < box.maxX() - 1e-6
                            && y > box.minY() + 1e-6 && y < box.maxY() - 1e-6)
                            .as("segment %s -> %s enters %s", path.get(k), path.get(k + 1), box).isFalse();
                    boolean link = Math.hypot(x - first[0], y - first[1]) <= nearEnd
                            || Math.hypot(x - last[0], y - last[1]) <= nearEnd;
                    if (!link) {
                        assertThat(distance(box, x, y))
                                .as("segment %s -> %s at (%.3f, %.3f) keeps clear of %s", path.get(k),
                                        path.get(k + 1), x, y, box)
                                .isGreaterThanOrEqualTo(WalkingPath.CLEARANCE - 1e-3);
                    }
                }
            }
            for (WalkingPath.Line wall : map.lines()) {
                assertThat(wall.touches(a[0], a[1], b[0], b[1]))
                        .as("segment %s -> %s touches %s", path.get(k), path.get(k + 1), wall).isFalse();
            }
        }
    }

    private static double distance(WalkingPath.Box box, double x, double y) {
        double dx = Math.max(0, Math.max(box.minX() - x, x - box.maxX()));
        double dy = Math.max(0, Math.max(box.minY() - y, y - box.maxY()));
        return Math.hypot(dx, dy);
    }

    private static double[] xy(Point point) {
        return new double[]{point.x().doubleValue(), point.y().doubleValue()};
    }

    /** Where the path crosses the vertical line x = {@code x}: the y of the first segment that does. */
    private static double crossingAt(List<Point> path, double x) {
        for (int k = 0; k + 1 < path.size(); k++) {
            double[] a = xy(path.get(k));
            double[] b = xy(path.get(k + 1));
            if ((a[0] - x) * (b[0] - x) <= 0 && a[0] != b[0]) {
                return a[1] + (x - a[0]) / (b[0] - a[0]) * (b[1] - a[1]);
            }
        }
        throw new AssertionError("the path never crosses x = " + x);
    }

    @Test
    @DisplayName("nothing in the way: the straight segment, ends exactly as given")
    void straightWhenNothingIsInTheWay() {
        WalkingPath map = map("20", "20", List.of(box("10", "10", "2", "2", 0)));

        assertThat(walk(map, p("1.234", "1"), p("8.5", "3.25"))).containsExactly(p("1.234", "1"), p("8.5", "3.25"));
    }

    @Test
    @DisplayName("a shelf across the straight line: the path goes round its nearer end")
    void roundTheNearerEndOfAShelf() {
        // The shelf runs x 5..15 at y 9..10; the leg at x = 7 is 2 m from its west end, 8 from its east end.
        WalkingPath map = map("20", "20", List.of(box("5", "9", "10", "1", 0)));

        List<Point> path = walk(map, p("7", "5"), p("7", "15"));

        assertThat(path).hasSizeGreaterThan(2);
        assertThat(path).allSatisfy(point -> assertThat(point.x()).isLessThanOrEqualTo(n("7")));
        assertThat(path).anySatisfy(point -> assertThat(point.x()).isLessThanOrEqualTo(n("5")));
    }

    @Test
    @DisplayName("a stand point on each pick face of a shelf, turned or not, is reached without entering the shelf")
    void standPointsOnEveryFace() {
        for (int rotation : List.of(0, 90, 180, 270)) {
            Footprint shelf = box("8", "8", "4", "1.2", rotation);
            WalkingPath map = map("20", "20", List.of(shelf));
            for (PickFaces face : List.of(new PickFaces(true, false, false, false),
                    new PickFaces(false, true, false, false), new PickFaces(false, false, true, false),
                    new PickFaces(false, false, false, true))) {
                Point stand = PutawayRoute.standPointOfBin(shelf, face, n("1"), n("0.2"), n("0.8"), n("0.8"));
                for (Point from : List.of(p("1", "1"), p("19", "19"), p("10", "2"), p("10", "18"))) {
                    walk(map, from, stand);
                    walk(map, stand, from);
                }
            }
        }
    }

    @Test
    @DisplayName("an aisle of 1 m the grid does not line up with is walked down its middle; one of 0.7 m is no way")
    void aNarrowAisleOffTheGrid() {
        // Two blocks leave only x 14.8..15.8 open between y 5 and 25: the only way from bottom left to top right.
        WalkingPath map = map("30", "30", List.of(box("0", "5", "14.8", "20", 0), box("15.8", "5", "14.2", "20", 0)));
        WalkingPath tooNarrow = map("30", "30",
                List.of(box("0", "5", "14.8", "20", 0), box("15.5", "5", "14.5", "20", 0)));

        List<Point> path = walk(map, p("5", "2"), p("25", "28"));

        assertThat(path).hasSizeGreaterThan(2);
        assertThat(crossingAt(path.stream().map(q -> new Point(q.y(), q.x())).toList(), 15))
                .as("where the path crosses y = 15").isBetween(15.2 - 1e-3, 15.4 + 1e-3);
        assertThat(tooNarrow.between(p("5", "2"), p("25", "28"))).isEmpty();
    }

    @Test
    @DisplayName("the 0.2 m between two shelves of a run is no way through: the path goes round the end of the run")
    void theGapBetweenTwoShelvesOfARun() {
        // The BDG layout in small (issue #446): two 10 m shelves 0.2 m apart in each of three rows, 3.6 m aisles.
        List<Footprint> shelves = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            String y = String.valueOf(5 + row * 4.8);
            shelves.add(box("3", y, "10", "1.2", 0));
            shelves.add(box("13.2", y, "10", "1.2", 0));
        }
        WalkingPath map = map("30", "20", shelves);

        List<Point> path = walk(map, p("13.1", "2"), p("13.1", "18"));

        for (String y : List.of("5.6", "10.4", "15.2")) {
            double x = crossingAt(path.stream().map(q -> new Point(q.y(), q.x())).toList(), Double.parseDouble(y));
            assertThat(x).as("where the path crosses row y = %s", y).satisfiesAnyOf(
                    across -> assertThat(across).isLessThanOrEqualTo(3 - WalkingPath.CLEARANCE + 1e-3),
                    across -> assertThat(across).isGreaterThanOrEqualTo(23.2 + WalkingPath.CLEARANCE - 1e-3));
        }
    }

    @Test
    @DisplayName("from one stand point to the next on the same face: out to the clearance, along the aisle, back in")
    void standPointsStepBackFromTheFace() {
        // The shelf runs x 5..15 at y 9..10; both stand points are on its north face.
        WalkingPath map = map("20", "20", List.of(box("5", "9", "10", "1", 0)));

        assertThat(walk(map, p("7", "10"), p("12", "10")))
                .containsExactly(p("7", "10"), p("7.000", "10.400"), p("12.000", "10.400"), p("12", "10"));
        assertThat(walk(map, p("7", "9"), p("7", "4")))
                .as("from the south face, straight out").containsExactly(p("7", "9"), p("7.000", "8.600"), p("7", "4"));
    }

    @Test
    @DisplayName("a wall across the warehouse: an open door drawn over it lets the path through within its span")
    void anOpenDoorOverAWall() {
        Segment wall = line("10", "0", "10", "20");
        Segment door = line("10", "8", "10", "12");
        WalkingPath open = WalkingPath.of(n("20"), n("20"), List.of(), List.of(wall),
                List.of(new WalkingPath.Door(door, true)));
        WalkingPath closed = WalkingPath.of(n("20"), n("20"), List.of(), List.of(wall),
                List.of(new WalkingPath.Door(door, false)));

        List<Point> path = walk(open, p("2", "2"), p("18", "2"));
        assertThat(crossingAt(path, 10)).isStrictlyBetween(8.0, 12.0);
        assertThat(closed.between(p("2", "2"), p("18", "2"))).isEmpty();
    }

    @Test
    @DisplayName("a door in a gap of the wall: open lets the path through, closed or not passable does not")
    void aDoorInAGap() {
        List<Segment> walls = List.of(line("10", "0", "10", "8"), line("10", "12", "10", "20"));
        Segment door = line("10", "8", "10", "12");
        WalkingPath open = WalkingPath.of(n("20"), n("20"), List.of(), walls,
                List.of(new WalkingPath.Door(door, true)));
        WalkingPath closed = WalkingPath.of(n("20"), n("20"), List.of(), walls,
                List.of(new WalkingPath.Door(door, false)));

        assertThat(crossingAt(walk(open, p("2", "18"), p("18", "18")), 10)).isStrictlyBetween(8.0, 12.0);
        assertThat(closed.between(p("2", "18"), p("18", "18"))).isEmpty();
    }

    @Test
    @DisplayName("an area stop whose area is an obstacle: the path ends at its centre, even 4 m inside")
    void anObstacleAreaIsReachedAtItsCentre() {
        WalkingPath map = map("30", "30", List.of(box("10", "10", "8", "8", 0), box("4", "12", "1", "4", 0)));

        List<Point> path = walk(map, p("2", "14"), p("14", "14"));

        assertThat(path.get(path.size() - 1)).isEqualTo(p("14", "14"));
        assertThat(walk(map, p("14", "14"), p("2", "14"))).startsWith(p("14", "14"));
    }

    @Test
    @DisplayName("a point outside the map, or a way walled off on every side, has no path")
    void noPath() {
        WalkingPath map = WalkingPath.of(n("20"), n("20"), List.of(), List.of(line("0", "10", "20", "10")),
                List.of());

        assertThat(map.between(p("2", "2"), p("25", "2"))).isEmpty();
        assertThat(map.between(p("2", "2"), p("2", "18"))).isEmpty();
    }

    @Test
    @DisplayName("legs: from the origin, then from the last stop with a point; a stop the map cannot place has none, "
            + "and two stops on one stand point give [p, p]")
    void legsOfARoute() {
        WalkingPath map = map("20", "20", List.of(box("5", "9", "10", "1", 0)));
        Point origin = p("7", "5");
        Point a = p("7", "15");
        Point b = p("12", "15");

        List<Optional<List<Point>>> legs = new ArrayList<>(map.legs(origin, java.util.Arrays.asList(a, a, null, b)));

        assertThat(legs).hasSize(4);
        assertThat(legs.get(0)).hasValueSatisfying(path -> assertThat(path.get(0)).isEqualTo(origin));
        assertThat(legs.get(1)).contains(List.of(a, a));
        assertThat(legs.get(2)).isEmpty();
        assertThat(legs.get(3)).contains(List.of(a, b));
        assertThat(map.legs(null, List.of(a))).containsExactly(Optional.empty());
    }

    @Test
    @DisplayName("the BDG / HAN seed layout: every leg from NHAP01 and between the shelves' stand points is walkable, "
            + "and the legs to faces behind a shelf bend round it")
    void theSeedLayout() {
        // tools/db/seed/gen_seed_test_data.py: 60 x 40, a wall along the top, an open door in the east edge, five
        // shelves 1.2 x 5 at x = 16 + 8s, y = 4, picked east and west, an office (obstacle) at (2, 30) 8 x 6.
        List<Footprint> obstacles = new ArrayList<>();
        List<Footprint> shelves = new ArrayList<>();
        for (int s = 0; s < 5; s++) {
            Footprint shelf = box(String.valueOf(16 + 8 * s), "4", "1.2", "5.0", 0);
            shelves.add(shelf);
            obstacles.add(shelf);
        }
        obstacles.add(box("2", "30", "8", "6", 0));
        WalkingPath map = WalkingPath.of(n("60"), n("40"), obstacles, List.of(line("0", "0", "60", "0")),
                List.of(new WalkingPath.Door(line("60", "2", "60", "8"), true)));
        PickFaces eastWest = new PickFaces(false, true, false, true);
        List<Point> stands = new ArrayList<>();
        for (int s = 0; s < shelves.size(); s++) {
            for (int b = 0; b < 4; b++) {
                // The seed's bin x/y are absolute (a known quirk); the stand point clamps them onto the shelf.
                stands.add(PutawayRoute.standPointOfBin(shelves.get(s), eastWest, n(String.valueOf(16 + 8 * s)),
                        n("4").add(n("1.25").multiply(BigDecimal.valueOf(b))), n("1.2"), n("1.2")));
                stands.add(PutawayRoute.standPointOfBin(shelves.get(s), eastWest, n("0"),
                        n("1.25").multiply(BigDecimal.valueOf(b)), n("0.4"), n("1.2")));
            }
        }
        Point receiving = PutawayRoute.standPointOfArea(box("2", "2", "10", "8", 0));
        List<Point> distinct = stands.stream().distinct().toList();

        int bent = 0;
        for (Point stand : distinct) {
            if (walk(map, receiving, stand).size() > 2) {
                bent++;
            }
            for (Point other : distinct) {
                walk(map, stand, other);
            }
        }
        assertThat(bent).as("legs from NHAP01 that go round a shelf").isPositive();
    }

    @Test
    @DisplayName("a 300 x 200 m map widens the step to stay within the cell budget and still finds the way")
    void aLargeMap() {
        List<Footprint> obstacles = new ArrayList<>();
        for (int row = 0; row < 15; row++) {
            for (int col = 0; col < 20; col++) {
                obstacles.add(box(String.valueOf(10 + col * 14), String.valueOf(10 + row * 12), "10", "1.2", 0));
            }
        }
        long started = System.nanoTime();
        WalkingPath map = map("300", "200", obstacles);
        List<Point> stops = List.of(p("295", "195"), p("15", "190"), p("150", "100"), p("290", "5"), p("5", "100"),
                p("200", "11.2"));
        List<Optional<List<Point>>> legs = map.legs(p("1", "1"), stops);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(map.step()).isGreaterThan(WalkingPath.STEP);
        assertThat(legs).allSatisfy(leg -> assertThat(leg).isPresent());
        legs.forEach(leg -> walkable(map, leg.orElseThrow()));
        System.out.printf("WalkingPath: 300 x 200 m, %d shelves, step %.3f m, 6 legs in %d ms%n", obstacles.size(),
                map.step(), millis);
    }

    @Test
    @DisplayName("a wall is cut by every open door lying on it, and only by those")
    void wallsAreCutByTheirDoors() {
        WalkingPath.Line wall = new WalkingPath.Line(0, 0, 20, 0);

        assertThat(WalkingPath.cut(wall, List.of(new WalkingPath.Line(4, 0, 6, 0), new WalkingPath.Line(12, 0, 10, 0),
                new WalkingPath.Line(4, 1, 6, 1)))).containsExactly(new WalkingPath.Line(0, 0, 4, 0),
                new WalkingPath.Line(6, 0, 10, 0), new WalkingPath.Line(12, 0, 20, 0));
    }
}
