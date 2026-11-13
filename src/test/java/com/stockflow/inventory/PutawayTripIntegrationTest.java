package com.stockflow.inventory;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.internal.domain.PutawayRanker;
import com.stockflow.inventory.internal.service.PutawayService;
import com.stockflow.inventory.internal.service.PutawayTripService;
import com.stockflow.inventory.internal.service.PutawayTripService.CartonView;
import com.stockflow.inventory.internal.service.PutawayTripService.StopView;
import com.stockflow.inventory.internal.service.PutawayTripService.TripView;
import com.stockflow.support.Await;
import com.stockflow.support.ProductionReceiptTest;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.ReferenceRows;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import com.stockflow.warehouse.api.PutawayTrips;
import com.stockflow.warehouse.internal.domain.PutawayTripRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SCRUM-573 / SCRUM-581: putaway trips against a real PostgreSQL (kltn-docs 05 §4.5, BR-15, BR-16, BR-17). The world
 * is {@link PutawayConfirmationIntegrationTest}'s: three cartons of 10 received into a warehouse of its own, the
 * carton cut to 50 x 40 x 30 cm, and bins the tests shape - a bin of exactly one carton makes "this space is taken"
 * something a test can see.
 *
 * <p>On the map the RECEIVING area is at the bottom left (centre 3.5, 3.5), the shelves stand in a row at y = 30 and
 * are reached from the south, and the OVERFLOW area lies right next to RECEIVING - nearer than any shelf, so that
 * "OVERFLOW is the last stop" (BR-17) is not an accident of the distances.</p>
 */
@ProductionReceiptTest
class PutawayTripIntegrationTest {

    @Autowired PutawayTripService trips;
    @Autowired PutawayService putaway;
    @Autowired PutawayTasks putawayTasks;
    @Autowired PutawayTrips tripApi;
    @Autowired PutawayTripRepository tripRepository;
    @Autowired ProductionReceivables receivables;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String prefix;
    private UUID warehouse;
    private UUID clerk;
    private UUID otherClerk;
    private UUID manager;
    private UUID receiptId;
    private String receiving;
    private String lpn1;
    private String lpn2;
    private String lpn3;
    private int shelfX;

    @BeforeEach
    void setUp() {
        prefix = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        receiving = prefix + "-RCV01";
        warehouse = Identifiers.newId();
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        otherClerk = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width,"
                    + " map_height, version, created_at) VALUES (?, 'Putaway trips', 'ACTIVE', ?, 'Test address',"
                    + " 'M', 50, 50, 0, NOW())", warehouse, prefix);
            area("RCV01", receiving, "RECEIVING", 1);
        });
        ProductionReceivables.Receivable goods = receivables.sent(warehouse, 3, 10);
        ProductionReceivables.Dock dock = receivables.onTheDock(goods, receiving, 3, clerk);
        receiptId = dock.receiptId();
        lpn1 = dock.lpns().get(0);
        lpn2 = dock.lpns().get(1);
        lpn3 = dock.lpns().get(2);
        Await.until("three putaway tasks", Duration.ofSeconds(20), () -> tasks().size() >= 3);
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE inventory.uom_conversions SET length_cm = 50, width_cm = 40, height_cm = 30, weight_kg = NULL
                 WHERE id = (SELECT uom_conversion_id FROM inventory.stock_package WHERE lpn = ?)""", lpn1));
    }

    private List<PutawayTaskView> tasks() {
        return putawayTasks.tasksOfReceipt(receiptId);
    }

    private UUID taskOf(String lpn) {
        UUID carton = jdbc.queryForObject("select id from inventory.stock_package where lpn = ?", UUID.class, lpn);
        return tasks().stream().filter(t -> carton.equals(t.stockPackageId())).findFirst().orElseThrow().id();
    }

    private String taskStatus(String lpn) {
        return jdbc.queryForObject("select status from warehouse.putaway_task where id = ?", String.class,
                taskOf(lpn));
    }

    /** An area 5 x 5 at ({@code x}, 1): its centre, where the clerk stands, is ({@code x} + 2.5, 3.5). */
    private void area(String code, String locationCode, String type, int x) {
        UUID location = Identifiers.newId();
        jdbc.update("INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code,"
                + " is_pickable, is_putaway_target, status) VALUES (?, ?, 'AREA', ?, FALSE, FALSE, 'ACTIVE')",
                location, warehouse, locationCode);
        jdbc.update("INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length,"
                + " rotation, is_obstacle, status) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 5, 5, 0, FALSE, 'ACTIVE')",
                Identifiers.newId(), warehouse, location, code, type, code, x);
    }

    /**
     * A bin {@code width} x {@code length} m on a level 0.3 m clear, at the left end of a shelf of its own. The
     * shelves stand at x = 5, 17, 29, ... and y = 30, 1.2 deep, reached from the south: the clerk stands at
     * (shelf x + half the bin's width, 31.2).
     */
    private String bin(String name, String width, String length) {
        return binAt(name, width, length, 5 + 12 * shelfX++, 30);
    }

    /** {@link #bin} with its shelf at ({@code x}, {@code y}): the clerk stands at (x + half the bin's width, y + 1.2). */
    private String binAt(String name, String width, String length, int x, int y) {
        String code = prefix + "-" + name + "-1-A";
        tx.executeWithoutResult(s -> {
            UUID zone = Identifiers.newId();
            UUID shelf = Identifiers.newId();
            UUID level = Identifiers.newId();
            UUID location = Identifiers.newId();
            jdbc.update("INSERT INTO warehouse.zone (id, warehouse_id, name, color) VALUES (?, ?, ?, '#4F81BD')",
                    zone, warehouse, "Zone " + name);
            jdbc.update("""
                    INSERT INTO warehouse.shelf (id, warehouse_id, zone_id, code, name, x, y, width, length, rotation,
                                                 is_obstacle, pick_north, pick_east, pick_south, pick_west,
                                                 status)
                    VALUES (?, ?, ?, ?, 'Shelf', ?, ?, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE,
                            'ACTIVE')""", shelf, warehouse, zone, name, x, y);
            jdbc.update("""
                    INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
                    VALUES (?, ?, 1, 0, 0.3, 500)""", level, shelf);
            jdbc.update("""
                    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, max_weight, is_pickable,
                                                            is_putaway_target, status)
                    VALUES (?, ?, 'BIN', ?, 500, TRUE, TRUE, 'ACTIVE')""", location, warehouse, code);
            jdbc.update("""
                    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
                    VALUES (?, ?, ?, 'A', 0, 0, ?::numeric, ?::numeric, 0, 'PALLET')""", Identifiers.newId(), level,
                    location, width, length);
        });
        return code;
    }

    /** Exactly one carton: 0.4 m x 0.5 m x 0.3 m. */
    private String oneCartonBin(String name) {
        return bin(name, "0.4", "0.5");
    }

    /** Three cartons side by side: 1.2 m x 0.5 m x 0.3 m. */
    private String threeCartonBin(String name) {
        return bin(name, "1.2", "0.5");
    }

    /** The warehouse's OVERFLOW area, next to RECEIVING and pickable like every real one. */
    private String overflowArea() {
        String code = prefix + "-OVF01";
        tx.executeWithoutResult(s -> {
            area("OVF01", code, "OVERFLOW", 7);
            jdbc.update("UPDATE warehouse.storage_location SET is_pickable = TRUE WHERE location_code = ?", code);
        });
        return code;
    }

    private TripView onItsWay(UUID operator, String... lpns) {
        TripView trip = trips.open(warehouse, operator);
        for (String lpn : lpns) {
            trips.load(trip.id(), lpn, operator);
        }
        return trips.start(trip.id(), operator);
    }

    private static CartonView carton(TripView trip, String lpn) {
        return trip.cartons().stream().filter(c -> lpn.equals(c.lpn())).findFirst().orElseThrow();
    }

    private static String lpnAssigned(TripView trip, String locationCode) {
        return trip.cartons().stream().filter(c -> locationCode.equals(c.assignedLocationCode()))
                .map(CartonView::lpn).findFirst().orElseThrow();
    }

    private List<String> suggestedFor(String lpn) {
        return putaway.get(taskOf(lpn)).suggestions().stream().map(PutawayRanker.Suggestion::locationCode).toList();
    }

    private int releaseIdleHolds(Instant idleSince, Instant now) {
        Integer released = tx.execute(s -> tripRepository.releaseHoldsOfTripsIdleSince(idleSince, now));
        return released == null ? 0 : released;
    }

    private ErrorCode refused(Runnable action) {
        return assertThrows(BusinessException.class, action::run).errorCode();
    }

    @Test
    @DisplayName("the whole trip: cartons onto the cart, bins assigned without sharing space, stops ordered with "
            + "OVERFLOW last, each carton put away at its stop, COMPLETED with the last one")
    void theWholeTrip() {
        String a01 = oneCartonBin("A01");
        String a02 = oneCartonBin("A02");
        String overflow = overflowArea();

        TripView loading = trips.open(warehouse, clerk);
        assertThat(loading.status()).isEqualTo("LOADING");
        trips.load(loading.id(), lpn1.toLowerCase(Locale.ROOT), clerk);
        trips.load(loading.id(), lpn2, clerk);
        TripView loaded = trips.load(loading.id(), lpn3, clerk);
        assertThat(loaded.cartons()).hasSize(3).allSatisfy(c -> {
            assertThat(c.status()).isEqualTo("ON_CART");
            assertThat(c.assignedLocationCode()).as("no bin before setting off").isNull();
            assertThat(c.holding()).isFalse();
        });
        assertThat(loaded.stops()).isEmpty();
        assertThat(trips.current(clerk)).map(TripView::id).contains(loading.id());

        TripView trip = trips.start(loading.id(), clerk);

        assertThat(trip.status()).isEqualTo("IN_PROGRESS");
        assertThat(trip.startLocationCode()).isEqualTo(receiving);
        assertThat(trip.startX()).isEqualByComparingTo("3.5");
        assertThat(trip.startY()).isEqualByComparingTo("3.5");
        // Two bins of one carton each: the three cartons are never given the same free space.
        assertThat(trip.cartons()).extracting(CartonView::assignedLocationCode)
                .containsExactlyInAnyOrder(a01, a02, overflow);
        assertThat(trip.cartons()).allSatisfy(c -> assertThat(c.holding()).isTrue());
        // Nearest first from RECEIVING; OVERFLOW last although it is the nearest of all (BR-17).
        assertThat(trip.stops()).extracting(StopView::locationCode).containsExactly(a01, a02, overflow);
        assertThat(trip.stops()).extracting(StopView::sequence).containsExactly(1, 2, 3);
        assertThat(trip.stops().get(0).x()).isEqualByComparingTo("5.2");
        assertThat(trip.stops().get(0).y()).isEqualByComparingTo("31.2");
        assertThat(trip.stops().get(2).x()).isEqualByComparingTo("9.5");
        String first = lpnAssigned(trip, a01);
        String second = lpnAssigned(trip, a02);
        String last = lpnAssigned(trip, overflow);
        assertThat(trip.stops().get(0).lpns()).containsExactly(first);
        assertThat(tripApi.holds(warehouse, null)).hasSize(3);

        TripView afterFirst = trips.place(trip.id(), a01, first, clerk);
        assertThat(afterFirst.status()).isEqualTo("IN_PROGRESS");
        assertThat(carton(afterFirst, first).status()).isEqualTo("PLACED");
        assertThat(carton(afterFirst, first).holding()).isFalse();
        assertThat(afterFirst.stops()).extracting(StopView::locationCode).containsExactly(a02, overflow);
        assertThat(taskStatus(first)).isEqualTo("DONE");

        trips.place(trip.id(), a02, second, clerk);
        TripView done = trips.place(trip.id(), overflow, last, clerk);

        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.closedAt()).isNotNull();
        assertThat(done.closedBy()).as("nobody closed it: it completed").isNull();
        assertThat(done.stops()).isEmpty();
        assertThat(done.cartons()).allSatisfy(c -> assertThat(c.status()).isEqualTo("PLACED"));
        assertThat(tripApi.holds(warehouse, null)).isEmpty();
        assertThat(trips.current(clerk)).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT p.lpn, s.status, s.location_code FROM inventory.stock_item s
                  JOIN inventory.stock_package p ON p.id = s.stock_package_id
                 WHERE p.lpn IN (?, ?, ?)""", lpn1, lpn2, lpn3)).hasSize(3).allSatisfy(row ->
                assertThat(row.get("status")).isEqualTo("AVAILABLE"));
        assertThat(jdbc.queryForObject("select location_code from inventory.stock_package where lpn = ?",
                String.class, last)).isEqualTo(overflow);
    }

    @Test
    @DisplayName("cartons of one lot that fit one bin share a stop")
    void oneStopForOneBin() {
        String a01 = threeCartonBin("A01");
        oneCartonBin("A02");

        TripView trip = onItsWay(clerk, lpn1, lpn2, lpn3);

        assertThat(trip.cartons()).allSatisfy(c -> {
            assertThat(c.assignedLocationCode()).isEqualTo(a01);
            assertThat(c.stopSequence()).isEqualTo(1);
        });
        assertThat(trip.stops()).singleElement().satisfies(stop -> {
            assertThat(stop.locationCode()).isEqualTo(a01);
            assertThat(stop.lpns()).containsExactlyInAnyOrder(lpn1, lpn2, lpn3);
        });
    }

    @Test
    @DisplayName("BR-17: after a carton is put away the next stop is the nearest to where the clerk stands, not to "
            + "the area the cart left from")
    void theRouteGoesOnFromTheLastStop() {
        // Stand points: A (5.2, 11.2), B (5.2, 21.2), C (17.2, 6.2); the cart leaves from (3.5, 3.5).
        // From the start: A 9.4, C 16.4, B 19.4. From A: B 10, C 17. So A, B, C - and after A still B before C,
        // although C is the nearer of the two to the start.
        String a = binAt("A01", "0.4", "0.5", 5, 10);
        String b = binAt("B01", "0.4", "0.5", 5, 20);
        String c = binAt("C01", "0.4", "0.5", 17, 5);

        TripView trip = onItsWay(clerk, lpn1, lpn2, lpn3);
        assertThat(trip.stops()).extracting(StopView::locationCode).containsExactly(a, b, c);

        TripView afterA = trips.place(trip.id(), a, lpnAssigned(trip, a), clerk);

        assertThat(afterA.stops()).extracting(StopView::locationCode).containsExactly(b, c);
        assertThat(carton(afterA, lpnAssigned(trip, b)).stopSequence()).isEqualTo(1);
        assertThat(carton(afterA, lpnAssigned(trip, c)).stopSequence()).isEqualTo(2);
        assertThat(afterA.startLocationCode()).as("where the trip set off from does not move").isEqualTo(receiving);
    }

    @Test
    @DisplayName("BR-15: a clerk has one open trip - the service says so, and so does uk_putaway_trip_open_operator")
    void oneOpenTripPerClerk() {
        TripView open = trips.open(warehouse, clerk);

        assertThat(refused(() -> trips.open(warehouse, clerk))).isEqualTo(ErrorCode.PUTAWAY_TRIP_ALREADY_OPEN);
        assertThat(trips.open(warehouse, otherClerk).status()).as("another clerk has a cart of their own")
                .isEqualTo("LOADING");
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.putaway_trip (id, warehouse_id, operator_id, status, last_activity_at, version,
                                                    created_at)
                VALUES (?, ?, ?, 'LOADING', NOW(), 0, NOW())""", Identifiers.newId(), warehouse, clerk)));

        trips.close(open.id(), clerk, false);
        assertThat(trips.open(warehouse, clerk).id()).as("a finished trip is no longer in the way")
                .isNotEqualTo(open.id());
    }

    @Test
    @DisplayName("an empty cart does not leave; a cart that left takes no more cartons")
    void theCartAndItsStatus() {
        oneCartonBin("A01");
        TripView trip = trips.open(warehouse, clerk);

        assertThat(refused(() -> trips.start(trip.id(), clerk))).isEqualTo(ErrorCode.PUTAWAY_TRIP_EMPTY);
        assertThat(refused(() -> trips.place(trip.id(), receiving, lpn1, clerk)))
                .as("nothing is put away from a cart that has not left")
                .isEqualTo(ErrorCode.PUTAWAY_TRIP_INVALID_STATE);

        trips.load(trip.id(), lpn1, clerk);
        assertThat(trips.load(trip.id(), lpn1, clerk).cartons()).as("the same scan again changes nothing").hasSize(1);
        trips.start(trip.id(), clerk);

        assertThat(refused(() -> trips.load(trip.id(), lpn2, clerk))).isEqualTo(ErrorCode.PUTAWAY_TRIP_INVALID_STATE);
        assertThat(refused(() -> trips.place(trip.id(), receiving, lpn2, clerk)))
                .isEqualTo(ErrorCode.PUTAWAY_CARTON_NOT_ON_TRIP);
    }

    @Test
    @DisplayName("BR-15: a carton is on one cart at a time; taken off, it may go onto another")
    void aCartonIsOnOneCart() {
        TripView mine = trips.open(warehouse, clerk);
        trips.load(mine.id(), lpn1, clerk);
        TripView theirs = trips.open(warehouse, otherClerk);

        assertThat(refused(() -> trips.load(theirs.id(), lpn1, otherClerk)))
                .isEqualTo(ErrorCode.PUTAWAY_TASK_ON_ANOTHER_TRIP);

        assertThat(carton(trips.unload(mine.id(), lpn1, clerk), lpn1).status()).isEqualTo("RETURNED");
        assertThat(carton(trips.load(theirs.id(), lpn1, otherClerk), lpn1).status()).isEqualTo("ON_CART");
    }

    @Test
    @DisplayName("only its clerk works a trip; a manager closes one left behind - the cartons go back, their tasks "
            + "PENDING, the holds released, and they can go onto a new cart")
    void closedForAnotherClerk() {
        oneCartonBin("A01");
        TripView trip = onItsWay(clerk, lpn1, lpn2);
        assertThat(tripApi.holds(warehouse, null)).isNotEmpty();

        assertThat(refused(() -> trips.place(trip.id(), receiving, lpn1, otherClerk)))
                .isEqualTo(ErrorCode.PUTAWAY_TRIP_NOT_YOURS);
        assertThat(refused(() -> trips.close(trip.id(), otherClerk, false))).isEqualTo(ErrorCode.PUTAWAY_TRIP_NOT_YOURS);

        TripView closed = trips.close(trip.id(), manager, true);

        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.closedBy()).isEqualTo(manager);
        assertThat(closed.cartons()).allSatisfy(c -> {
            assertThat(c.status()).isEqualTo("RETURNED");
            assertThat(c.holding()).isFalse();
        });
        assertThat(tripApi.holds(warehouse, null)).isEmpty();
        assertThat(taskStatus(lpn1)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select location_code from inventory.stock_package where lpn = ?",
                String.class, lpn1)).as("the carton never left its source area").isEqualTo(receiving);
        assertThat(refused(() -> trips.close(trip.id(), manager, true))).isEqualTo(ErrorCode.PUTAWAY_TRIP_INVALID_STATE);

        TripView again = trips.open(warehouse, clerk);
        assertThat(carton(trips.load(again.id(), lpn1, clerk), lpn1).status()).isEqualTo("ON_CART");
    }

    @Test
    @DisplayName("BR-16: space a trip holds is not suggested to another carton - but a hold is not a lock: the bin "
            + "taken meanwhile is refused at the scan, and the carton is given another place")
    void aHoldIsCountedButIsNotALock() {
        String a01 = oneCartonBin("A01");
        String overflow = overflowArea();
        assertThat(suggestedFor(lpn2)).containsExactly(a01);

        TripView trip = onItsWay(clerk, lpn1);
        assertThat(carton(trip, lpn1).assignedLocationCode()).isEqualTo(a01);
        assertThat(suggestedFor(lpn2)).as("the bin's only space is held for the cart").isEmpty();
        assertThat(suggestedFor(lpn1)).as("its own hold does not hide the bin from its own carton").containsExactly(a01);

        // Another clerk puts a carton there all the same: the hard rules look at what is in the bin, not at holds.
        assertThat(putaway.confirm(taskOf(lpn2), a01, lpn2, otherClerk).status()).isEqualTo("DONE");

        assertThat(refused(() -> trips.place(trip.id(), a01, lpn1, clerk)))
                .isEqualTo(ErrorCode.LOCATION_CAPACITY_EXCEEDED);
        assertThat(carton(trips.get(trip.id()), lpn1).status()).as("the refusal changed nothing").isEqualTo("ON_CART");

        TripView reassigned = trips.reassign(trip.id(), lpn1, clerk);
        assertThat(carton(reassigned, lpn1).assignedLocationCode()).isEqualTo(overflow);
        assertThat(carton(reassigned, lpn1).holding()).isTrue();
        assertThat(reassigned.stops()).extracting(StopView::locationCode).containsExactly(overflow);

        assertThat(trips.place(trip.id(), overflow, lpn1, clerk).status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("BR-16: a trip nobody scanned for the hold window gives its space back and keeps its cart and plan")
    void idleHoldsAreGivenBack() {
        String a01 = oneCartonBin("A01");
        TripView trip = onItsWay(clerk, lpn1);
        Instant now = Instant.now();
        Instant window = now.minus(Duration.ofMinutes(30));

        assertThat(releaseIdleHolds(window, now))
                .as("a trip just worked on keeps its holds").isZero();
        assertThat(suggestedFor(lpn2)).isEmpty();

        tx.executeWithoutResult(s -> jdbc.update("update warehouse.putaway_trip set last_activity_at ="
                + " last_activity_at - interval '31 minutes' where id = ?", trip.id()));
        assertThat(releaseIdleHolds(window, now)).isEqualTo(1);

        TripView idle = trips.get(trip.id());
        assertThat(idle.status()).isEqualTo("IN_PROGRESS");
        assertThat(carton(idle, lpn1).status()).isEqualTo("ON_CART");
        assertThat(carton(idle, lpn1).assignedLocationCode()).as("the bin is still the plan").isEqualTo(a01);
        assertThat(carton(idle, lpn1).holding()).isFalse();
        assertThat(suggestedFor(lpn2)).as("the space is offered again").containsExactly(a01);

        // The clerk comes back and the bin is still free: the putaway goes through as ever.
        assertThat(trips.place(trip.id(), a01, lpn1, clerk).status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a carton of a cart put away from the single-task screen leaves its cart: off it while LOADING, "
            + "PLACED once the cart left")
    void aSinglePutawayTellsTheTrip() {
        String a01 = threeCartonBin("A01");
        TripView loading = trips.open(warehouse, clerk);
        trips.load(loading.id(), lpn1, clerk);
        trips.load(loading.id(), lpn2, clerk);
        trips.load(loading.id(), lpn3, clerk);

        putaway.confirm(taskOf(lpn1), a01, lpn1, clerk);
        TripView stillLoading = trips.get(loading.id());
        assertThat(stillLoading.status()).isEqualTo("LOADING");
        assertThat(carton(stillLoading, lpn1).status()).isEqualTo("RETURNED");

        TripView trip = trips.start(loading.id(), clerk);
        assertThat(trip.cartons()).filteredOn(c -> "ON_CART".equals(c.status())).extracting(CartonView::lpn)
                .containsExactlyInAnyOrder(lpn2, lpn3);

        putaway.confirm(taskOf(lpn2), a01, lpn2, clerk);
        TripView afterSingle = trips.get(trip.id());
        assertThat(afterSingle.status()).isEqualTo("IN_PROGRESS");
        assertThat(carton(afterSingle, lpn2).status()).isEqualTo("PLACED");

        putaway.confirm(taskOf(lpn3), a01, lpn3, clerk);
        assertThat(trips.get(trip.id()).status()).as("the last carton completes the trip wherever it was scanned")
                .isEqualTo("COMPLETED");
    }

    private void databaseRefuses(String rule, String sql, Object... args) {
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(s -> jdbc.update(sql, args)), rule);
    }

    @Test
    @DisplayName("the database says it too: the status rules of a trip and of a carton on it, and one cart per carton")
    void theDatabaseSaysItToo() {
        oneCartonBin("A01");
        UUID onItsWay = onItsWay(clerk, lpn1).id();
        UUID loading = trips.open(warehouse, otherClerk).id();

        databaseRefuses("ck_putaway_trip_status",
                "update warehouse.putaway_trip set status = 'PARKED' where id = ?", onItsWay);
        databaseRefuses("ck_putaway_trip_started: a cart being loaded has not left",
                "update warehouse.putaway_trip set started_at = now() where id = ?", loading);
        databaseRefuses("ck_putaway_trip_started: a cart on its way has left",
                "update warehouse.putaway_trip set started_at = null where id = ?", onItsWay);
        databaseRefuses("ck_putaway_trip_closed: a finished trip says when",
                "update warehouse.putaway_trip set status = 'COMPLETED' where id = ?", onItsWay);
        databaseRefuses("ck_putaway_trip_closed: nobody closes a trip that completed",
                "update warehouse.putaway_trip set status = 'COMPLETED', closed_at = now(), closed_by = ? where id = ?",
                manager, onItsWay);
        databaseRefuses("uk_putaway_trip_item_on_cart: a carton is on one cart", """
                INSERT INTO warehouse.putaway_trip_item (id, trip_id, putaway_task_id, status, loaded_at, version,
                                                         created_at)
                VALUES (?, ?, ?, 'ON_CART', NOW(), 0, NOW())""", Identifiers.newId(), loading, taskOf(lpn1));
        databaseRefuses("ck_putaway_trip_item_status",
                "update warehouse.putaway_trip_item set status = 'LOST' where trip_id = ?", onItsWay);
        databaseRefuses("ck_putaway_trip_item_placed: a carton put away says when", "update warehouse.putaway_trip_item"
                + " set status = 'PLACED', hold_released_at = now() where trip_id = ?", onItsWay);
        databaseRefuses("ck_putaway_trip_item_hold: nothing is held for a carton off the cart",
                "update warehouse.putaway_trip_item set status = 'RETURNED' where trip_id = ?", onItsWay);
        databaseRefuses("ck_putaway_trip_item_stop",
                "update warehouse.putaway_trip_item set stop_sequence = 0 where trip_id = ?", onItsWay);

        // And what it accepts: the carton taken off with its hold given back.
        tx.executeWithoutResult(s -> {
            s.setRollbackOnly();
            assertThat(jdbc.update("update warehouse.putaway_trip_item set status = 'RETURNED', hold_released_at ="
                    + " now() where trip_id = ?", onItsWay)).isEqualTo(1);
        });
    }
}
