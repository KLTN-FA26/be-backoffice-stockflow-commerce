package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.internal.domain.LabelSelection;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Choosing which locations get a label (SCRUM-97) on Postgres with the demo seed: warehouse {@code HCM},
 * shelves A01 and A02 in zone A and B01 in zone B, levels 1-2 with bins A and B, storage areas RCV01,
 * QC01, PACK01, DSP01 and the {@code NON_STORAGE} OFFICE. Every test rolls back.
 *
 * <p>Other test classes may commit extra shelves into {@code HCM}, so whole-warehouse assertions check
 * an ordered subsequence, never the exact list.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class LocationLabelServiceIntegrationTest {

    private static final UUID HCM = DemoData.WAREHOUSE_HCM;

    @Autowired LocationLabelService labels;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;

    private List<String> codes(LabelSelection selection) {
        return labels.sheetFor(HCM, selection).labels().stream().map(LocationLabel::code).toList();
    }

    private static LabelSelection.OfShelf shelf(String code, Integer... levels) {
        return new LabelSelection.OfShelf(DemoData.shelf(code), Set.of(levels));
    }

    private static LabelSelection.OfCodes codesOf(String... codes) {
        return new LabelSelection.OfCodes(Set.of(codes));
    }

    private void locationStatus(String code, String status) {
        jdbc.update("update warehouse.storage_location set status = ? where location_code = ?", status, code);
        flushAndClear();
    }

    private void shelfStatus(String code, String status) {
        jdbc.update("update warehouse.shelf set status = ? where id = ?", status, DemoData.shelf(code));
        flushAndClear();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static void assertRefused(ThrowingCallable call, ErrorCode code, String... inMessage) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(code))
                .satisfies(e -> List.of(inMessage).forEach(text -> assertThat(e.getMessage()).contains(text)));
    }

    /** A second warehouse with a zone, a shelf with one bin, and a storage area - all rolled back. */
    private record OtherWarehouse(UUID id, UUID zone, UUID shelf, String binCode, String areaCode) {
    }

    private OtherWarehouse otherWarehouse() {
        UUID warehouse = Identifiers.newId();
        String prefix = "L" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT);
        UUID zone = Identifiers.newId();
        UUID shelf = Identifiers.newId();
        UUID level = Identifiers.newId();
        UUID binLocation = Identifiers.newId();
        UUID areaLocation = Identifiers.newId();
        jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Second warehouse', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", warehouse, prefix);
        jdbc.update("INSERT INTO warehouse.zone (id, warehouse_id, name, color) VALUES (?, ?, 'Zone', '#4F81BD')",
                zone, warehouse);
        jdbc.update("""
                INSERT INTO warehouse.shelf (id, warehouse_id, zone_id, code, name, x, y, width, length, rotation,
                                             is_obstacle, pick_north, pick_east, pick_south, pick_west,
                                             default_storage_class, status)
                VALUES (?, ?, ?, 'A01', 'Shelf', 5, 5, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'OVERSIZE',
                        'ACTIVE')""", shelf, warehouse, zone);
        jdbc.update("""
                INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
                VALUES (?, ?, 1, 0, 1.4, 500)""", level, shelf);
        jdbc.update("""
                INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,
                                                        capacity_units, max_weight, is_pickable,
                                                        is_putaway_target, status)
                VALUES (?, ?, 'BIN', ?, 'OVERSIZE', 40, 250, TRUE, TRUE, 'ACTIVE')""",
                binLocation, warehouse, prefix + "-A01-1-A");
        jdbc.update("""
                INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
                VALUES (?, ?, ?, 'A', 0, 0, 5, 1.2, 0, 'PALLET')""", Identifiers.newId(), level, binLocation);
        jdbc.update("""
                INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,
                                                        capacity_units, max_weight, is_pickable,
                                                        is_putaway_target, status)
                VALUES (?, ?, 'AREA', ?, 'NORMAL', NULL, NULL, FALSE, FALSE, 'ACTIVE')""",
                areaLocation, warehouse, prefix + "-RCV01");
        jdbc.update("""
                INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length,
                                            rotation, is_obstacle, status)
                VALUES (?, ?, ?, 'RCV01', 'RECEIVING', 'Receiving', 30, 30, 10, 8, 0, FALSE, 'ACTIVE')""",
                Identifiers.newId(), warehouse, areaLocation);
        flushAndClear();
        return new OtherWarehouse(warehouse, zone, shelf, prefix + "-A01-1-A", prefix + "-RCV01");
    }

    // ------------------------------------------------------------------ what gets a label

    @Test
    void aShelfGivesItsBinsInWalkingOrder() {
        LocationLabelSheet sheet = labels.sheetFor(HCM, shelf("A01"));
        assertThat(sheet.warehousePrefix()).isEqualTo("HCM");
        assertThat(sheet.warehouseName()).isEqualTo("Kho Hồ Chí Minh");
        assertThat(sheet.labels()).extracting(LocationLabel::code)
                .containsExactly("HCM-A01-1-A", "HCM-A01-1-B", "HCM-A01-2-A", "HCM-A01-2-B");
    }

    @Test
    void levelsNarrowAShelf() {
        assertThat(codes(shelf("A01", 2))).containsExactly("HCM-A01-2-A", "HCM-A01-2-B");
    }

    @Test
    void aZoneGivesTheBinsOfItsShelvesAndNoArea() {
        List<String> codes = codes(new LabelSelection.OfZone(DemoData.ZONE_HCM_A));
        assertThat(codes).containsExactly("HCM-A01-1-A", "HCM-A01-1-B", "HCM-A01-2-A", "HCM-A01-2-B",
                "HCM-A02-1-A", "HCM-A02-1-B", "HCM-A02-2-A", "HCM-A02-2-B");
    }

    @Test
    void theWholeWarehouseGivesBinsThenAreas() {
        List<String> codes = codes(new LabelSelection.WholeWarehouse());
        assertThat(codes).containsSubsequence("HCM-A01-1-A", "HCM-A01-2-B", "HCM-A02-1-A", "HCM-B01-2-B",
                "HCM-DSP01", "HCM-PACK01", "HCM-QC01", "HCM-RCV01");
        assertThat(codes).noneMatch(code -> code.contains("OFFICE"));
    }

    @Test
    void codesAreMatchedIgnoringCaseAndPrintedOnce() {
        assertThat(codes(new LabelSelection.OfCodes(Set.of("hcm-a01-1-a", "HCM-A01-1-A"))))
                .containsExactly("HCM-A01-1-A");
    }

    @Test
    void codesComeOutInWalkingOrderNotRequestOrder() {
        assertThat(codes(codesOf("HCM-RCV01", "HCM-B01-1-A", "HCM-A01-1-A")))
                .containsExactly("HCM-A01-1-A", "HCM-B01-1-A", "HCM-RCV01");
    }

    @Test
    void aBinLabelCarriesItsPath() {
        LocationLabel label = labels.sheetFor(HCM, codesOf("HCM-A01-2-B")).labels().getFirst();
        assertThat(label).isEqualTo(new LocationLabel("HCM-A01-2-B", StorageLocationKind.BIN, "A01", 2, "B", null,
                StorageClass.OVERSIZE));
    }

    @Test
    void anAreaLabelCarriesItsName() {
        LocationLabel label = labels.sheetFor(HCM, codesOf("HCM-RCV01")).labels().getFirst();
        assertThat(label).isEqualTo(new LocationLabel("HCM-RCV01", StorageLocationKind.AREA, null, null, null,
                "Khu nhận hàng", StorageClass.OVERSIZE));
    }

    // ------------------------------------------------------------------ the layout rule (D4)

    @Test
    void anInactiveBinIsSkippedInABroadSelection() {
        locationStatus("HCM-A01-1-A", "INACTIVE");
        assertThat(codes(shelf("A01"))).containsExactly("HCM-A01-1-B", "HCM-A01-2-A", "HCM-A01-2-B");
    }

    @Test
    void binsOnAnInactiveShelfAreSkipped() {
        shelfStatus("B01", "INACTIVE");
        assertThat(codes(new LabelSelection.WholeWarehouse())).noneMatch(code -> code.startsWith("HCM-B01-"))
                .contains("HCM-A01-1-A", "HCM-RCV01");
    }

    @Test
    void blockedAndMaintenanceArePrinted() {
        locationStatus("HCM-A01-1-A", "BLOCKED");
        shelfStatus("A01", "MAINTENANCE");
        assertThat(codes(shelf("A01"))).hasSize(4);
    }

    @Test
    void anInactiveWarehouseStillPrints() {
        jdbc.update("update warehouse.warehouse set status = 'INACTIVE' where id = ?", HCM);
        flushAndClear();
        assertThat(codes(shelf("A01"))).hasSize(4);
    }

    @Test
    void aNamedInactiveCodeIsRefused() {
        locationStatus("HCM-A01-1-A", "INACTIVE");
        assertRefused(() -> labels.sheetFor(HCM, codesOf("HCM-A01-1-A", "HCM-A01-1-B")),
                ErrorCode.LOCATION_INACTIVE, "HCM-A01-1-A");
    }

    @Test
    void aNamedBinOnAnInactiveShelfIsRefused() {
        shelfStatus("B01", "INACTIVE");
        assertRefused(() -> labels.sheetFor(HCM, codesOf("HCM-B01-1-A")), ErrorCode.LOCATION_INACTIVE,
                "HCM-B01-1-A");
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void unknownCodesAre404WithTheirNames() {
        assertRefused(() -> labels.sheetFor(HCM, codesOf("HCM-A01-1-A", "HCM-ZZ9-1-A")),
                ErrorCode.LOCATION_NOT_FOUND, "HCM-ZZ9-1-A");
    }

    @Test
    void aCodeOfAnotherWarehouseIs404() {
        OtherWarehouse other = otherWarehouse();
        assertRefused(() -> labels.sheetFor(HCM, codesOf(other.binCode(), other.areaCode())),
                ErrorCode.LOCATION_NOT_FOUND, other.binCode(), other.areaCode());
    }

    @Test
    void aShelfOfAnotherWarehouseIs404() {
        OtherWarehouse other = otherWarehouse();
        assertRefused(() -> labels.sheetFor(HCM, new LabelSelection.OfShelf(other.shelf(), Set.of())),
                ErrorCode.SHELF_NOT_FOUND);
    }

    @Test
    void aZoneOfAnotherWarehouseIs404() {
        OtherWarehouse other = otherWarehouse();
        assertRefused(() -> labels.sheetFor(HCM, new LabelSelection.OfZone(other.zone())), ErrorCode.ZONE_NOT_FOUND);
    }

    @Test
    void anotherWarehousePrintsItsOwnLabels() {
        OtherWarehouse other = otherWarehouse();
        assertThat(labels.sheetFor(other.id(), new LabelSelection.WholeWarehouse()).labels())
                .extracting(LocationLabel::code).containsExactly(other.binCode(), other.areaCode());
    }

    @Test
    void anUnknownLevelIs404() {
        assertRefused(() -> labels.sheetFor(HCM, shelf("A01", 1, 3)), ErrorCode.SHELF_LEVEL_NOT_FOUND);
    }

    @Test
    void anUnknownWarehouseIs404() {
        assertRefused(() -> labels.sheetFor(UUID.randomUUID(), new LabelSelection.WholeWarehouse()),
                ErrorCode.WAREHOUSE_NOT_FOUND);
    }

    @Test
    void anEmptySelectionIs422() {
        for (String bin : List.of("HCM-B01-1-A", "HCM-B01-1-B", "HCM-B01-2-A", "HCM-B01-2-B")) {
            locationStatus(bin, "INACTIVE");
        }
        assertRefused(() -> labels.sheetFor(HCM, shelf("B01")), ErrorCode.LOCATION_LABELS_EMPTY);
    }

    // ------------------------------------------------------------------ query count

    @Test
    void theQueryCountDoesNotGrowWithTheLabels() {
        long one = statements(() -> labels.sheetFor(HCM, codesOf("HCM-A01-1-A")));
        long all = statements(() -> labels.sheetFor(HCM, new LabelSelection.WholeWarehouse()));
        long aShelf = statements(() -> labels.sheetFor(HCM, shelf("A01", 1, 2)));
        assertThat(all).isEqualTo(one).isLessThanOrEqualTo(5);
        assertThat(aShelf).isLessThanOrEqualTo(5);
    }

    private long statements(Supplier<LocationLabelSheet> call) {
        flushAndClear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            call.get();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }
}
