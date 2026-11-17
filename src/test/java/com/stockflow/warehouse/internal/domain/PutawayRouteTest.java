package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.id.Identifiers;
import com.stockflow.warehouse.internal.domain.PutawayRoute.Point;
import com.stockflow.warehouse.internal.domain.PutawayRoute.Stop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The route of a putaway trip (kltn-docs 05 §4.5 step 14, BR-17; 06 §4.4 step 6): where the clerk stands for a bin,
 * and the nearest-neighbour order of the stops.
 */
class PutawayRouteTest {

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private static Point p(String x, String y) {
        return new Point(n(x), n(y));
    }

    private static Stop bin(String code, String x, String y) {
        return new Stop(Identifiers.newId(), code, p(x, y), false);
    }

    private static List<String> codes(List<Stop> route) {
        return route.stream().map(Stop::locationCode).toList();
    }

    private static void assertPoint(Point actual, String x, String y) {
        assertThat(actual.x()).isEqualByComparingTo(x);
        assertThat(actual.y()).isEqualByComparingTo(y);
    }

    @Test
    @DisplayName("the docs' example: RECEIVING at the bottom left, three bins - A01, then B02, then C04")
    void theExampleOfTheDocs() {
        Point receiving = p("0", "0");
        List<Stop> route = PutawayRoute.order(receiving, List.of(
                bin("C04-2-01", "9", "8"), bin("A01-1-02", "2", "1"), bin("B02-1-05", "5", "3")));

        assertThat(codes(route)).containsExactly("A01-1-02", "B02-1-05", "C04-2-01");
    }

    @Test
    @DisplayName("nearest neighbour is from where the clerk stands, not from the start: the route can double back")
    void nearestFromTheLastStop() {
        // From (0,0): B at 4 is nearest; then A at (5,0) is 1 away from B, C at (0,6) is 10.
        List<Stop> route = PutawayRoute.order(p("0", "0"), List.of(
                bin("A", "5", "0"), bin("B", "4", "0"), bin("C", "0", "6")));

        assertThat(codes(route)).containsExactly("B", "A", "C");
    }

    @Test
    @DisplayName("a tie is decided by the location code")
    void tiesGoByCode() {
        List<Stop> route = PutawayRoute.order(p("5", "5"), List.of(bin("B-2", "7", "5"), bin("B-1", "3", "5")));

        assertThat(codes(route)).containsExactly("B-1", "B-2");
    }

    @Test
    @DisplayName("OVERFLOW is the last stop however near it is (BR-17); a place the map cannot say comes before it")
    void overflowIsLast() {
        Stop overflow = new Stop(Identifiers.newId(), "HN-OVF01", p("1", "1"), true);
        Stop unknown = new Stop(Identifiers.newId(), "HN-X", null, false);

        List<Stop> route = PutawayRoute.order(p("0", "0"), List.of(overflow, unknown, bin("HN-A01-1-01", "9", "9")));

        assertThat(codes(route)).containsExactly("HN-A01-1-01", "HN-X", "HN-OVF01");
    }

    @Test
    @DisplayName("no start on the map: the route begins at the first stop by code")
    void withoutAStart() {
        List<Stop> route = PutawayRoute.order(null, List.of(bin("B", "0", "0"), bin("A", "9", "9")));

        assertThat(codes(route)).containsExactly("A", "B");
    }

    @Test
    @DisplayName("stand point: straight in front of the bin, on the pick face nearest to it")
    void standPointOnTheNearestFace() {
        // A shelf 4 wide and 1 deep at (10, 20), reached from north and south; a bin at its left end, 0.2 from the
        // north edge.
        Footprint shelf = new Footprint(n("10"), n("20"), n("4"), n("1"), 0);
        PickFaces northAndSouth = new PickFaces(true, false, true, false);

        Point stand = PutawayRoute.standPointOfBin(shelf, northAndSouth, n("0"), n("0"), n("1"), n("0.4"));

        assertPoint(stand, "10.5", "20");
    }

    @Test
    @DisplayName("stand point: a face the shelf does not offer is not used, even when it is nearer")
    void onlyDeclaredFaces() {
        Footprint shelf = new Footprint(n("10"), n("20"), n("4"), n("1"), 0);
        PickFaces southOnly = new PickFaces(false, false, true, false);

        Point stand = PutawayRoute.standPointOfBin(shelf, southOnly, n("0"), n("0"), n("1"), n("0.4"));

        assertPoint(stand, "10.5", "21");
    }

    @Test
    @DisplayName("stand point: a shelf turned a quarter turns its faces with it")
    void aTurnedShelf() {
        // The same shelf turned 90 degrees clockwise occupies 1 x 4 at (10, 20): its north face now looks east.
        Footprint shelf = new Footprint(n("10"), n("20"), n("4"), n("1"), 90);
        PickFaces northOnly = new PickFaces(true, false, false, false);

        Point stand = PutawayRoute.standPointOfBin(shelf, northOnly, n("0"), n("0"), n("1"), n("0.4"));

        assertPoint(stand, "11", "20.5");
    }

    @Test
    @DisplayName("stand point of an area is its centre")
    void areaCentre() {
        assertPoint(PutawayRoute.standPointOfArea(new Footprint(n("2"), n("4"), n("6"), n("2"), 0)), "5", "5");
        assertPoint(PutawayRoute.standPointOfArea(new Footprint(n("2"), n("4"), n("6"), n("2"), 90)), "3", "7");
    }
}
