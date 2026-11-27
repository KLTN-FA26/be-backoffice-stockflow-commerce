package com.stockflow.warehouse.internal.service;

import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.internal.domain.BinDetails;
import com.stockflow.warehouse.internal.domain.BoundaryType;
import com.stockflow.warehouse.internal.domain.LocationSettings;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.Segment;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
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
 * BE3-B (kltn-docs 06 §4.1 step 8: every change of the warehouse map is audited - who, when, what): an edit of a bin,
 * a shelf, an area, a boundary or a zone writes its audit entry with what it changed - the location code it is known
 * by, which only the result names, and the fields as they are now. On Postgres with the demo seed; every test rolls
 * back, and the entries are read in the same transaction, which a successful entry joins.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class LayoutAuditDetailsIntegrationTest {

    @Autowired ShelfLayoutService shelves;
    @Autowired AreaLayoutService areas;
    @Autowired BoundaryLayoutService boundaries;
    @Autowired WarehouseLayoutService warehouses;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    /** The details of the newest successful entry about this record. */
    private String details(String resourceType, UUID resourceId) {
        entityManager.flush();
        return jdbc.queryForObject("""
                SELECT details FROM platform.audit_log
                 WHERE resource_type = ? AND resource_id = ? AND outcome = 'SUCCESS'
                 ORDER BY occurred_at DESC, id DESC LIMIT 1""", String.class, resourceType, resourceId.toString());
    }

    @Test
    @DisplayName("a bin: its status change and its edit name its location code and what it is now")
    void aBin() {
        UUID a01 = DemoData.shelf("A01");
        ShelfSummary.Level level = shelves.getShelf(a01).levels().get(0);
        ShelfSummary.BinEntry bin = level.bins().get(0);

        shelves.changeBinStatus(a01, level.id(), bin.id(), LocationStatus.BLOCKED);
        assertThat(details("bin", bin.id())).isEqualTo("{\"locationCode\":\"HCM-A01-1-A\",\"status\":\"BLOCKED\"}");

        shelves.updateBin(new ShelfCommands.UpdateBin(a01, level.id(), bin.id(),
                new BinDetails("Audited bin", bin.footprint(), bin.type()),
                new LocationSettings(new BigDecimal("300"), true, true)));
        assertThat(details("bin", bin.id())).startsWith("{\"locationCode\":\"HCM-A01-1-A\",")
                .contains("description=Audited bin", "maxWeight=300");
    }

    @Test
    @DisplayName("a shelf, an area, a boundary and a zone each say what they are now")
    void theRestOfTheMap() {
        ShelfSummary a01 = shelves.getShelf(DemoData.shelf("A01"));
        shelves.updateShelf(new ShelfCommands.UpdateShelf(a01.id(), a01.zoneId(), "Shelf A01 audited",
                a01.description(), a01.footprint(), a01.obstacle(), a01.pickFaces(), a01.version()));
        assertThat(details("shelf", a01.id())).startsWith("{\"code\":\"A01\",")
                .contains("\"name\":\"Shelf A01 audited\"", "Footprint[");

        UUID rcv01 = DemoData.area("RCV01");
        areas.changeAreaStatus(rcv01, LocationStatus.BLOCKED);
        assertThat(details("area", rcv01)).isEqualTo("{\"locationCode\":\"HCM-RCV01\",\"status\":\"BLOCKED\"}");

        BoundarySummary wall = boundaries.createBoundary(new BoundaryCommands.CreateBoundary(WAREHOUSE_HCM,
                BoundaryType.WALL, new Segment(new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("15"),
                new BigDecimal("5")), false, null));
        assertThat(details("boundary", WAREHOUSE_HCM)).startsWith("{\"boundaryId\":\"" + wall.id() + "\",")
                .contains("\"type\":\"WALL\"", "\"passable\":false", "Segment[");

        ZoneSummary zone = warehouses.createZone(new CreateZoneCommand(WAREHOUSE_HCM, "Khu kiểm toán", "#336699"));
        assertThat(details("zone", WAREHOUSE_HCM)).isEqualTo("{\"zoneId\":\"" + zone.id()
                + "\",\"name\":\"Khu kiểm toán\",\"color\":\"#336699\"}");
    }

    @Test
    @DisplayName("a warehouse edit names the utilization factor as it stands, also when the request keeps it")
    void aWarehouse() {
        WarehouseSummary hcm = warehouses.get(WAREHOUSE_HCM);
        warehouses.update(new UpdateWarehouseCommand(WAREHOUSE_HCM, "Kho HCM audited",
                hcm.address(), hcm.returnAddress(), hcm.mapWidth(), hcm.mapHeight(), hcm.version(),
                new BigDecimal("0.85")));
        assertThat(details("warehouse", WAREHOUSE_HCM)).startsWith("{\"prefix\":\"HCM\",")
                .contains("\"name\":\"Kho HCM audited\"", "\"utilizationFactor\":0.85");

        long flushed = warehouses.get(WAREHOUSE_HCM).version();
        warehouses.update(new UpdateWarehouseCommand(WAREHOUSE_HCM, "Kho HCM audited again", hcm.address(),
                hcm.returnAddress(), hcm.mapWidth(), hcm.mapHeight(), flushed, null));
        assertThat(details("warehouse", WAREHOUSE_HCM))
                .contains("\"name\":\"Kho HCM audited again\"", "\"utilizationFactor\":0.85");
    }
}
