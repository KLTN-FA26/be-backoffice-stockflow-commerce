package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.internal.domain.AreaDetails;
import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.BoundaryType;
import com.stockflow.warehouse.internal.domain.DoorStatus;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LocationSettings;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.Segment;
import com.stockflow.warehouse.internal.domain.StorageClass;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Areas and boundaries through the application services, on Postgres with the demo seed: warehouse
 * {@code HCM} (map 60 x 40), shelves A01 (5-15 x 5-6.2), A02 and B01, areas RCV01 (40-55 x 2-10),
 * QC01, PACK01, DSP01 holding stock and OFFICE (2-12 x 30-38, {@code NON_STORAGE}), a north wall and
 * an east door. Every test rolls back; the deferred {@code tg_storage_location_owned} is run early
 * with {@code set constraints all immediate}, and {@link AreaCreationCommitIntegrationTest} commits
 * for real.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class AreaAndBoundaryIntegrationTest {

    private static final LocationSettings PUTAWAY_ONLY = new LocationSettings(null, null, false, true);

    @Autowired AreaLayoutService areas;
    @Autowired BoundaryLayoutService boundaries;
    @Autowired ShelfLayoutService shelves;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    private long versionOf(String table, UUID id) {
        return jdbc.queryForObject("select version from warehouse." + table + " where id = ?", Long.class, id);
    }

    private static Footprint at(String x, String y, String width, String length) {
        return new Footprint(new BigDecimal(x), new BigDecimal(y), new BigDecimal(width), new BigDecimal(length), 0);
    }

    private static Segment from(String startX, String startY, String endX, String endY) {
        return new Segment(new BigDecimal(startX), new BigDecimal(startY), new BigDecimal(endX), new BigDecimal(endY));
    }

    private AreaSummary create(String code, AreaType type, Footprint footprint) {
        return areas.createArea(new AreaCommands.CreateArea(WAREHOUSE_HCM, code,
                new AreaDetails(type, "Khu " + code, footprint, false, null, PUTAWAY_ONLY)));
    }

    /** Runs the deferred ownership trigger now, inside a test that will roll back. */
    private void checkDeferredConstraints() {
        entityManager.flush();
        jdbc.execute("set constraints all immediate");
    }

    private static ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }

    @Nested
    @DisplayName("areas")
    class Areas {

        @Test
        @DisplayName("overlapping ACTIVE shelf A01 is refused; once A01 is INACTIVE the place is free (#18 D3)")
        void overlapAgainstShelves() {
            assertThat(errorOf(() -> create("X01", AreaType.OVERFLOW, at("6", "5", "2", "1"))))
                    .isEqualTo(ErrorCode.LAYOUT_OVERLAP);

            shelves.changeShelfStatus(DemoData.shelf("A01"), LocationStatus.INACTIVE);

            assertThat(create("X01", AreaType.OVERFLOW, at("6", "5", "2", "1")).code()).isEqualTo("X01");
        }

        @Test
        @DisplayName("overlapping another area or sticking out of the map is refused")
        void overlapAgainstAreasAndTheFrame() {
            assertThat(errorOf(() -> create("X01", AreaType.OVERFLOW, at("50", "5", "2", "2"))))
                    .isEqualTo(ErrorCode.LAYOUT_OVERLAP);
            assertThat(errorOf(() -> create("X01", AreaType.OVERFLOW, at("58", "38", "3", "1"))))
                    .isEqualTo(ErrorCode.LAYOUT_OUT_OF_BOUNDS);
        }

        @Test
        @DisplayName("OFFICE (NON_STORAGE) becomes OVERFLOW and gets location HCM-OFFICE (#18 D10)")
        void officeBecomesOverflow() {
            UUID office = DemoData.area("OFFICE");
            long version = versionOf("area", office);

            AreaSummary updated = areas.updateArea(new AreaCommands.UpdateArea(office, new AreaDetails(
                    AreaType.OVERFLOW, "Khu tràn", at("2", "30", "10", "8"), true, StorageClass.OVERSIZE,
                    PUTAWAY_ONLY), version));
            checkDeferredConstraints();

            assertThat(updated.locationCode()).isEqualTo("HCM-OFFICE");
            assertThat(updated.version()).isEqualTo(version + 1);
            assertThat(jdbc.queryForObject("select l.location_code from warehouse.area a "
                    + "join warehouse.storage_location l on l.id = a.location_id where a.id = ?", String.class, office))
                    .isEqualTo("HCM-OFFICE");
        }

        /**
         * Only the location row changes here, and Hibernate versions the area by its own columns. Without
         * the forced increment the version would stay put, and the stale second edit would pass its check
         * and quietly put capacity 500 back.
         */
        @Test
        @DisplayName("changing only the location's settings still moves the area's version")
        void locationOnlyEditMovesTheVersion() {
            UUID rcv01 = DemoData.area("RCV01");
            long version = versionOf("area", rcv01);
            AreaDetails moreCapacity = new AreaDetails(AreaType.RECEIVING, "Khu nhận hàng", at("40", "2", "15", "8"),
                    false, StorageClass.OVERSIZE, new LocationSettings(800, null, false, false));

            AreaSummary updated = areas.updateArea(new AreaCommands.UpdateArea(rcv01, moreCapacity, version));
            entityManager.flush();

            assertThat(updated.capacityUnits()).isEqualTo(800);
            assertThat(updated.version()).isEqualTo(version + 1);
            assertThat(versionOf("area", rcv01)).isEqualTo(version + 1);
            assertThat(errorOf(() -> areas.updateArea(new AreaCommands.UpdateArea(rcv01, new AreaDetails(
                    AreaType.RECEIVING, "Khu nhận", at("40", "2", "15", "8"), false, StorageClass.OVERSIZE,
                    new LocationSettings(500, null, false, false)), version))))
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }

        @Test
        @DisplayName("RCV01 holds stock, so it cannot become NON_STORAGE (#18 D10)")
        void storageAreaStaysStorage() {
            UUID rcv01 = DemoData.area("RCV01");

            assertThat(errorOf(() -> areas.updateArea(new AreaCommands.UpdateArea(rcv01, new AreaDetails(
                    AreaType.NON_STORAGE, "Văn phòng", at("40", "2", "15", "8"), false, null, null),
                    versionOf("area", rcv01)))))
                    .isEqualTo(ErrorCode.AREA_TYPE_CHANGE_NOT_ALLOWED);
        }

        @Test
        @DisplayName("a status change lands on area.status and storage_location.status alike (#18 D8)")
        void statusLandsOnBothRows() {
            areas.changeAreaStatus(DemoData.area("QC01"), LocationStatus.BLOCKED);
            entityManager.flush();

            assertThat(jdbc.queryForObject("select a.status || '/' || l.status from warehouse.area a "
                    + "join warehouse.storage_location l on l.id = a.location_id where a.code = 'QC01'", String.class))
                    .isEqualTo("BLOCKED/BLOCKED");
        }

        @Test
        @DisplayName("an INACTIVE area frees its place and cannot come back while it is taken (#18 D3)")
        void inactiveAreasFreeTheirPlace() {
            UUID qc01 = DemoData.area("QC01");
            areas.changeAreaStatus(qc01, LocationStatus.INACTIVE);

            create("X01", AreaType.OVERFLOW, at("41", "13", "2", "2"));

            assertThat(errorOf(() -> areas.changeAreaStatus(qc01, LocationStatus.ACTIVE)))
                    .isEqualTo(ErrorCode.LAYOUT_OVERLAP);
        }

        @Test
        @DisplayName("an unknown area is not found")
        void unknownArea() {
            assertThat(errorOf(() -> areas.changeAreaStatus(Identifiers.newId(), LocationStatus.ACTIVE)))
                    .isEqualTo(ErrorCode.AREA_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("boundaries")
    class Boundaries {

        @Test
        @DisplayName("the seeded north wall is deleted for good (#18 D11)")
        void deletesTheWall() {
            UUID wall = DemoData.WALL_HCM_NORTH;
            long version = versionOf("boundary", wall);

            boundaries.deleteBoundary(wall, version);

            assertThat(jdbc.queryForObject("select count(*) from warehouse.boundary where id = ?", Integer.class, wall))
                    .isZero();
            assertThat(errorOf(() -> boundaries.deleteBoundary(wall, version))).isEqualTo(ErrorCode.BOUNDARY_NOT_FOUND);
        }

        @Test
        @DisplayName("a delete based on an older read is refused: the wall was redrawn as a door meanwhile")
        void staleDeleteIsRefused() {
            UUID wall = DemoData.WALL_HCM_NORTH;
            long seen = versionOf("boundary", wall);
            boundaries.updateBoundary(new BoundaryCommands.UpdateBoundary(wall, BoundaryType.DOOR,
                    from("10", "0", "14", "0"), true, DoorStatus.OPEN, seen));
            entityManager.flush();

            assertThat(errorOf(() -> boundaries.deleteBoundary(wall, seen))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
            assertThat(jdbc.queryForObject("select count(*) from warehouse.boundary where id = ?", Integer.class, wall))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a wall along a shelf is fine; an end off the map is not")
        void insideTheMapOnly() {
            BoundarySummary wall = boundaries.createBoundary(new BoundaryCommands.CreateBoundary(WAREHOUSE_HCM,
                    BoundaryType.WALL, from("5", "5", "15", "5"), false, null));

            assertThat(wall.type()).isEqualTo(BoundaryType.WALL);
            assertThat(errorOf(() -> boundaries.createBoundary(new BoundaryCommands.CreateBoundary(WAREHOUSE_HCM,
                    BoundaryType.WALL, from("0", "40", "60.5", "40"), false, null))))
                    .isEqualTo(ErrorCode.LAYOUT_OUT_OF_BOUNDS);
        }

        @Test
        @DisplayName("a wall can be redrawn as a door")
        void wallBecomesADoor() {
            UUID wall = DemoData.WALL_HCM_NORTH;
            long version = versionOf("boundary", wall);

            BoundarySummary door = boundaries.updateBoundary(new BoundaryCommands.UpdateBoundary(wall,
                    BoundaryType.DOOR, from("10", "0", "14", "0"), true, DoorStatus.CLOSED, version));
            entityManager.flush();

            assertThat(door.operationalStatus()).isEqualTo(DoorStatus.CLOSED);
            assertThat(door.version()).isEqualTo(version + 1);
            assertThat(jdbc.queryForObject("select type || '/' || operational_status from warehouse.boundary "
                    + "where id = ?", String.class, wall)).isEqualTo("DOOR/CLOSED");
        }
    }
}
