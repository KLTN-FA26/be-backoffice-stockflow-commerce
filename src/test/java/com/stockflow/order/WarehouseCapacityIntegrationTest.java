package com.stockflow.order;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.WarehouseCapacityTurnedNegative;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.service.WarehouseCapacityService;
import com.stockflow.order.internal.service.WarehouseCapacityService.Board;
import com.stockflow.order.internal.service.WarehouseCapacityService.Group;
import com.stockflow.support.Await;
import com.stockflow.support.CartonRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.warehouse.api.CapacityGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE1 against the real database (kltn-docs 06 §4.6, BR-20, BR-21; 17 BR-25; C40-C43): the capacity board of a warehouse
 * built for the test - its bins, a blocked one, a legacy level without a clear height, a receiving area - with cartons
 * standing in it, a hold, and production orders in every state that counts; the warehouse manager's view narrowed to
 * their warehouses; and the WARN told once, when sending a production order takes the warehouse below zero.
 *
 * <p>The fixtures are {@link ProductionOrderDispatchIntegrationTest}'s - the same property source and mocks, so the
 * context is shared. Every test builds warehouses of its own, so every figure below is exact whatever other tests left
 * in the shared database. A carton of the test's SKU is 60 x 40 x 35 cm: 0.084 m³.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class WarehouseCapacityIntegrationTest extends ProductionOrderDispatchFixtures {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired WarehouseCapacityService capacity;

    /** The test's warehouse: its prefix, its bins and its receiving area. */
    private record Layout(Warehouse warehouse, UUID zoneId, UUID zonedShelfId, UUID looseShelfId, UUID areaId,
                          String binA, String binD, String receiving) {
    }

    /**
     * A warehouse of 10.4 m³: zone Z holds shelf A01 - bin A 5 x 1.2 m under a 1.4 m clear height (8.4 m³) and bin B
     * the same but BLOCKED (no room offered) - and shelf B01, of no zone, has a level of 1 m whose bin D (2 x 1 m:
     * 2 m³) is no putaway target but still the warehouse's. And a receiving area, which has no capacity (SCRUM-666).
     * A level without a clear height (C40) cannot be written any more ({@code ck_shelf_level_usable_height_required},
     * NOT VALID for the legacy rows): {@code WarehouseCapacitiesImplTest} covers those.
     */
    private Layout layout() {
        UUID id = Identifiers.newId();
        String code = "K" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase(Locale.ROOT);
        UUID zone = Identifiers.newId();
        UUID shelfA = Identifiers.newId();
        UUID shelfB = Identifiers.newId();
        UUID area = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                     version, created_at)
                    VALUES (?, ?, 'ACTIVE', ?, '9 Kho Street', 'M', 50, 50, 0, NOW())""", id, "Warehouse " + code, code);
            jdbc.update("INSERT INTO warehouse.zone (id, warehouse_id, name, color) VALUES (?, ?, 'Zone Z', '#4F81BD')",
                    zone, id);
            shelf(shelfA, id, zone, "A01", 5, 5, 10, 1.2);
            shelf(shelfB, id, null, "B01", 5, 10, 4, 1);
            UUID levelA = level(shelfA, 1, new BigDecimal("1.4"));
            UUID levelB = level(shelfB, 1, BigDecimal.ONE);
            bin(levelA, id, code + "-A01-1-A", "A", 0, 5, 1.2, "ACTIVE", true);
            bin(levelA, id, code + "-A01-1-B", "B", 5, 5, 1.2, "BLOCKED", true);
            bin(levelB, id, code + "-B01-1-D", "D", 0, 2, 1, "ACTIVE", false);
            UUID location = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, is_pickable,
                                                            is_putaway_target, status)
                    VALUES (?, ?, 'AREA', ?, FALSE, FALSE, 'ACTIVE')""", location, id, code + "-RCV01");
            jdbc.update("""
                    INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length,
                                                rotation, is_obstacle, status)
                    VALUES (?, ?, ?, 'RCV01', 'RECEIVING', 'Receiving', 20, 20, 5, 5, 0, FALSE, 'ACTIVE')""",
                    area, id, location);
        });
        return new Layout(new Warehouse(id, code, code + "-A01-1-A"), zone, shelfA, shelfB, area, code + "-A01-1-A",
                code + "-B01-1-D", code + "-RCV01");
    }

    private void shelf(UUID id, UUID warehouse, UUID zone, String code, double x, double y, double width,
                       double length) {
        jdbc.update("""
                INSERT INTO warehouse.shelf (id, warehouse_id, zone_id, code, name, x, y, width, length, rotation,
                                             is_obstacle, pick_north, pick_east, pick_south, pick_west, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'ACTIVE')""",
                id, warehouse, zone, code, "Shelf " + code, x, y, width, length);
    }

    private UUID level(UUID shelf, int index, BigDecimal clearHeight) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
                VALUES (?, ?, ?, 0, ?, 500)""", id, shelf, index, clearHeight);
        return id;
    }

    private void bin(UUID level, UUID warehouse, String locationCode, String code, double x, double width,
                     double length, String status, boolean putawayTarget) {
        UUID location = Identifiers.newId();
        jdbc.update("""
                INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, max_weight, is_pickable,
                                                        is_putaway_target, status)
                VALUES (?, ?, 'BIN', ?, 250, TRUE, ?, ?)""", location, warehouse, locationCode, putawayTarget, status);
        jdbc.update("""
                INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, 0, 'PALLET')""", Identifiers.newId(), level, location, code, x, width,
                length);
    }

    /** {@code count} cartons of the test's SKU (its 60 x 40 x 35 cm carton unit) standing IN_STOCK at {@code at}. */
    private void cartons(String at, int count) {
        tx.executeWithoutResult(s -> CartonRows.stockOfUnit(jdbc, sku.sku(), sku.uomConversionId(), at, count, null,
                Instant.now().minusSeconds(3_600), "AVAILABLE"));
    }

    private void factor(Warehouse warehouse, String value) {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE warehouse.warehouse SET utilization_factor = ?::numeric "
                + "WHERE id = ?", value, warehouse.id()));
    }

    private ProductionOrder drawn(UUID orderId, int batch) {
        return productionOrders.drawUp(orderId, batch, producer.id(), coordinator);
    }

    private ProductionOrder sent(ProductionOrder drawn) {
        return productionOrders.send(drawn.id(), drawn.version(), coordinator);
    }

    private Board board(Warehouse warehouse) {
        return capacity.board(warehouse.id());
    }

    private static Map<String, Group> byKey(Board board) {
        return board.groups().stream().collect(Collectors.toMap(Group::key, Function.identity()));
    }

    private static void m3(BigDecimal actual, String expected, String what) {
        assertThat(actual).as(what).isEqualByComparingTo(expected);
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("06 §4.6 step 3: every column from the map, the cartons, a hold and the production orders")
    void everyColumn() {
        Layout layout = layout();
        Warehouse w = layout.warehouse();
        // Two orders shipping from it; their production orders: one sent (incoming), two drafts (pending), one
        // cancelled (nothing).
        UUID first = confirmedOrder(w);
        UUID second = confirmedOrder(w);
        ProductionOrder sentOne = sent(drawn(first, 1));                      // 12 cartons on their way
        ProductionOrder draft = drawn(first, 2);                              // 8 cartons, not sent
        ProductionOrder awaiting = drawn(second, 1);                          // 12 cartons, not sent
        ProductionOrder dropped = drawn(second, 2);
        productionOrders.cancel(dropped.id(), dropped.version(), "Not needed after all", coordinator);
        assertThat(sentOne.status()).isEqualTo(ProductionOrderStatus.SENT);
        assertThat(awaiting.status()).isEqualTo(ProductionOrderStatus.DRAFT);
        // Cartons standing: 10 in bin A, 1 in bin D, 2 in the receiving area; 2 cartons of them held for an order.
        cartons(layout.binA(), 10);
        cartons(layout.binD(), 1);
        cartons(layout.receiving(), 2);
        tx.executeWithoutResult(s -> {
            UUID request = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, quantity,
                        reserved_at, status, sku, warehouse_code)
                    VALUES (?, ?, ?, ?, ?, NOW(), 'HELD', ?, ?)""", Identifiers.newId(), first, request, request,
                    2 * PER_CARTON, sku.sku(), w.code());
        });

        Board board = board(w);

        assertThat(board.warehouseCode()).isEqualTo(w.code());
        assertThat(board.active()).isTrue();
        m3(board.utilizationFactor(), "1", "100% until the manager sets it (C43)");
        m3(board.binVolumeM3(), "10.4", "bin A 8.4 + bin D 2 (no putaway target, still room) - not the blocked bin B");
        m3(board.capacityM3(), "10.4", "x 100%");
        assertThat(board.binsWithoutVolume()).as("every level has its clear height").isZero();
        m3(board.occupiedM3(), "1.092", "13 cartons x 0.084, the receiving area's included (C40)");
        m3(board.occupiedKg(), "54.6", "13 cartons x 4.2 kg, for reference");
        m3(board.incomingM3(), "1.008", "the sent production order's 12 cartons");
        m3(board.projectedFreeM3(), "8.3", "10.4 - 1.092 - 1.008");
        m3(board.pendingM3(), "1.68", "the two drafts' 8 and 12; not the cancelled one's");
        m3(board.freeIfAllApprovedM3(), "6.62", "8.3 - 1.68");
        m3(board.outgoingM3(), "0.168", "2 cartons held, for reference only");
        m3(board.occupiedPercent(), "10.50", "1.092 / 10.4");
        m3(board.projectedFreePercent(), "79.81", "8.3 / 10.4");
        assertThat(board.cartonsWithoutSize()).isZero();
        assertThat(board.productionCartonsWithoutSize()).isZero();

        // 06 §4.6 step 4: zone, the shelves of no zone, shelves, the area.
        assertThat(board.groups()).extracting(Group::kind).containsExactly(CapacityGroup.Kind.ZONE,
                CapacityGroup.Kind.NO_ZONE, CapacityGroup.Kind.SHELF, CapacityGroup.Kind.SHELF,
                CapacityGroup.Kind.AREA);
        Map<String, Group> groups = byKey(board);
        Group zone = groups.get("ZONE:" + layout.zoneId());
        assertThat(zone.name()).isEqualTo("Zone Z");
        m3(zone.capacityM3(), "8.4", "zone Z: bin A");
        m3(zone.occupiedM3(), "0.84", "its 10 cartons");
        m3(zone.freeM3(), "7.56", "free now: 8.4 - 0.84");
        m3(zone.occupiedPercent(), "10.00", "0.84 / 8.4");
        Group noZone = groups.get("NO_ZONE");
        m3(noZone.capacityM3(), "2", "the shelves of no zone: bin D");
        m3(noZone.occupiedM3(), "0.084", "bin D's carton");
        assertThat(groups.get("SHELF:" + layout.zonedShelfId()).code()).isEqualTo("A01");
        m3(groups.get("SHELF:" + layout.zonedShelfId()).capacityM3(), "8.4", "shelf A01");
        m3(groups.get("SHELF:" + layout.looseShelfId()).capacityM3(), "2", "shelf B01");
        Group area = groups.get("AREA:" + layout.areaId());
        assertThat(area.code()).isEqualTo("RCV01");
        assertThat(area.capacityM3()).as("an area has no capacity (SCRUM-666)").isNull();
        assertThat(area.freeM3()).isNull();
        assertThat(area.occupiedPercent()).isNull();
        m3(area.occupiedM3(), "0.168", "but what stands in it is occupied");

        // C43: 85% scales capacity at every level, and nothing else.
        factor(w, "0.85");
        Board scaled = board(w);
        m3(scaled.capacityM3(), "8.84", "10.4 x 85%");
        m3(scaled.projectedFreeM3(), "6.74", "8.84 - 1.092 - 1.008");
        m3(scaled.occupiedM3(), "1.092", "occupied does not move");
        m3(byKey(scaled).get("ZONE:" + layout.zoneId()).capacityM3(), "7.14", "zone Z: 8.4 x 85%");

        // BR-25 on the production orders: judged without their own cartons; a warning, never a refusal.
        WarehouseCapacityService.ProductionOrderCapacity ofDraft = capacity.ofProductionOrder(
                productionOrders.detail(draft.id()).order());
        m3(ofDraft.neededM3(), "0.672", "its 8 cartons");
        assertThat(ofDraft.exceedsProjected()).isFalse();
        assertThat(ofDraft.exceedsIfAllApproved()).isFalse();
        factor(w, "0.1");
        WarehouseCapacityService.ProductionOrderCapacity tight = capacity.ofProductionOrder(
                productionOrders.detail(draft.id()).order());
        m3(tight.board().projectedFreeM3(), "-1.06", "1.04 - 1.092 - 1.008: negative, shown as it is");
        assertThat(tight.exceedsProjected()).isTrue();
        assertThat(tight.exceedsIfAllApproved()).isTrue();
        m3(tight.shortfallM3(), "1.732", "0.672 beyond -1.06");
        WarehouseCapacityService.ProductionOrderCapacity ofSent = capacity.ofProductionOrder(
                productionOrders.detail(sentOne.id()).order());
        m3(ofSent.neededM3(), "1.008", "what it still brings");
        assertThat(ofSent.exceedsProjected()).as("judged out of the incoming it is part of: -1.06 + 1.008 < 1.008")
                .isTrue();
        WarehouseCapacityService.ProductionOrderCapacity ofCancelled = capacity.ofProductionOrder(
                productionOrders.detail(dropped.id()).order());
        m3(ofCancelled.neededM3(), "0", "a cancelled one brings nothing");
        assertThat(ofCancelled.exceedsProjected()).isFalse();
    }

    @Test
    @DisplayName("the demo warehouse: the board agrees with the rows it is read from")
    void theDemoWarehouse() {
        Board board = capacity.board(DemoData.WAREHOUSE_HCM);

        assertThat(board.warehouseCode()).isEqualTo("HCM");
        BigDecimal bins = jdbc.queryForObject("""
                SELECT coalesce(sum(b.width * b.length * l.usable_height), 0)
                  FROM warehouse.bin b
                  JOIN warehouse.shelf_level l ON l.id = b.level_id
                  JOIN warehouse.shelf s ON s.id = l.shelf_id
                  JOIN warehouse.storage_location loc ON loc.id = b.location_id
                  JOIN warehouse.warehouse w ON w.id = s.warehouse_id
                 WHERE w.id = ? AND w.status = 'ACTIVE' AND s.status = 'ACTIVE' AND loc.status = 'ACTIVE'
                   AND l.usable_height IS NOT NULL""", BigDecimal.class, DemoData.WAREHOUSE_HCM);
        m3(board.binVolumeM3(), bins.setScale(3, RoundingMode.HALF_UP).toPlainString(),
                "every usable bin with a clear height, putaway target or not");
        m3(board.capacityM3(), bins.multiply(board.utilizationFactor()).setScale(3, RoundingMode.HALF_UP)
                .toPlainString(), "x the factor");
        Map<String, Object> standing = jdbc.queryForMap("""
                SELECT coalesce(sum(u.length_cm * u.width_cm * u.height_cm / 1000000), 0) AS volume,
                       count(*) FILTER (WHERE u.length_cm IS NULL OR u.width_cm IS NULL OR u.height_cm IS NULL)
                           AS unsized
                  FROM inventory.stock_package p
                  JOIN inventory.uom_conversions u ON u.id = p.uom_conversion_id
                 WHERE p.status = 'IN_STOCK' AND p.location_code LIKE 'HCM-%'""");
        m3(board.occupiedM3(), new BigDecimal(standing.get("volume").toString()).setScale(3, RoundingMode.HALF_UP)
                .toPlainString(), "every carton IN_STOCK at an HCM location");
        assertThat(board.cartonsWithoutSize()).as("cartons whose unit declares no size are counted apart")
                .isEqualTo(((Number) standing.get("unsized")).intValue());
        m3(board.projectedFreeM3(), board.capacityM3().subtract(board.occupiedM3()).subtract(board.incomingM3())
                .toPlainString(), "capacity - occupied - incoming");
        m3(board.freeIfAllApprovedM3(), board.projectedFreeM3().subtract(board.pendingM3()).toPlainString(),
                "projected free - pending");
        BigDecimal zones = board.groups().stream()
                .filter(g -> g.kind() == CapacityGroup.Kind.ZONE || g.kind() == CapacityGroup.Kind.NO_ZONE)
                .map(Group::capacityM3).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(zones).as("the zones and the shelves of no zone share the warehouse's capacity out")
                .isEqualByComparingTo(board.capacityM3());
    }

    @Test
    @DisplayName("06 §2: a warehouse manager sees the boards of their warehouses only; another one is 403")
    void scope() {
        Warehouse mine = layout().warehouse();
        Warehouse other = layout().warehouse();
        UUID managerId = managerOf(mine);
        CurrentUser manager = new CurrentUser(managerId, "wm-" + managerId, Set.of(Role.WAREHOUSE_MANAGER), Set.of(),
                DataScope.WAREHOUSE, Set.of(mine.code()), Set.of(mine.id()));

        List<Board> seen = WithCurrentUser.call(manager, DataScope.ALL, () -> capacity.boards());
        assertThat(seen).extracting(Board::warehouseId).containsExactly(mine.id());
        assertThat(WithCurrentUser.call(manager, DataScope.ALL, () -> capacity.board(mine.id())).warehouseId())
                .isEqualTo(mine.id());
        assertThatThrownBy(() -> WithCurrentUser.call(manager, DataScope.ALL, () -> capacity.board(other.id())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);

        List<Board> everyone = WithCurrentUser.call(sales, DataScope.ALL, () -> capacity.boards());
        assertThat(everyone).extracting(Board::warehouseId).as("Sales: every active warehouse")
                .contains(mine.id(), other.id());
        assertThatThrownBy(() -> capacity.board(UUID.randomUUID()))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
    }

    @Test
    @DisplayName("C42: the send that takes the warehouse below zero tells its manager, WARN, once - the next does not")
    void warnOnceWhenItTurnsNegative() {
        Warehouse w = warehouse();          // one bin of 8.4 m³
        UUID manager = managerOf(w);
        factor(w, "0.15");                  // capacity 1.26 m³
        UUID first = confirmedOrder(w);
        UUID second = confirmedOrder(w);
        ProductionOrder batchOne = drawn(first, 1);     // 12 cartons: 1.008 m³
        ProductionOrder batchTwo = drawn(first, 2);     // 8 cartons: 0.672 m³
        ProductionOrder another = drawn(second, 1);     // 12 cartons: 1.008 m³

        ProductionOrder fits = sent(batchOne);
        Await.until("the first send's listeners are done", WAIT, () -> pendingPublications(fits.id()) == 0);
        assertThat(publications(WarehouseCapacityTurnedNegative.class, w.id())).as("1.26 - 1.008 is still 0.252 free")
                .isZero();

        ProductionOrder crosses = sent(batchTwo);
        Await.until("the warehouse manager is warned", WAIT, () -> jdbc.queryForObject("""
                SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? AND dedupe_key = ?""",
                Long.class, manager, "capacity-negative:" + w.id() + ":" + crosses.id()) == 1);
        Map<String, Object> notice = jdbc.queryForMap("""
                SELECT severity, category, title, body, link FROM notification.user_notification
                 WHERE recipient_id = ? AND dedupe_key = ?""", manager,
                "capacity-negative:" + w.id() + ":" + crosses.id());
        assertThat(notice).containsEntry("severity", "WARN").containsEntry("category", "WAREHOUSE_CAPACITY")
                .containsEntry("link", "/warehouse/capacity/" + w.id());
        assertThat((String) notice.get("body")).as("0.252 - 0.672: 0.42 below zero")
                .contains(crosses.code(), "0.42 m³");

        ProductionOrder stillShort = sent(another);
        Await.until("the third send's listeners are done", WAIT,
                () -> pendingPublications(stillShort.id()) == 0);
        assertThat(publications(WarehouseCapacityTurnedNegative.class, w.id()))
                .as("told once, at the crossing; not again while the warehouse stays short").isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? AND dedupe_key LIKE ?""",
                Long.class, manager, "capacity-negative:" + w.id() + ":%")).isEqualTo(1);
        m3(board(w).projectedFreeM3(), "-1.428", "1.26 - 2.688: the three on their way, shown negative");
    }

    @Test
    @DisplayName("#456: one row per bin, empty and blocked ones included, no area; a shelf's bins add up to its row")
    void binOccupancy() {
        Layout layout = layout();
        Warehouse w = layout.warehouse();
        factor(w, "0.5");
        cartons(layout.binA(), 10);
        cartons(layout.binD(), 1);
        cartons(layout.receiving(), 2);

        List<WarehouseCapacityService.BinOccupancy> bins = capacity.binOccupancy(w.id());

        assertThat(bins).extracting(WarehouseCapacityService.BinOccupancy::locationCode)
                .as("bins only, by code: the receiving area is not one")
                .containsExactly(w.code() + "-A01-1-A", w.code() + "-A01-1-B", w.code() + "-B01-1-D");
        WarehouseCapacityService.BinOccupancy a = bins.get(0);
        assertThat(a.cartons()).isEqualTo(10);
        assertThat(a.skus()).isEqualTo(1);
        assertThat(a.units()).isEqualTo(10 * PER_CARTON);
        assertThat(a.usable()).isTrue();
        m3(a.capacityM3(), "4.2", "8.4 m3 x 0.5");
        m3(a.occupiedM3(), "0.84", "10 cartons of 0.084 m3");
        m3(a.freeM3(), "3.36", "capacity - occupied");
        m3(a.occupiedPercent(), "20", "0.84 / 4.2");
        m3(a.maxWeightKg(), "250", "the bin's limit");
        assertThat(a.freeKg()).isNotNull();
        m3(a.heldByTripsM3(), "0", "no trip on its way");
        assertThat(a.underOpenCount()).isFalse();

        WarehouseCapacityService.BinOccupancy blocked = bins.get(1);
        assertThat(blocked.usable()).isFalse();
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.cartons()).isZero();
        assertThat(blocked.capacityM3()).as("a blocked bin offers no room, as on the board").isNull();
        assertThat(blocked.freeM3()).isNull();
        assertThat(blocked.occupiedPercent()).isNull();
        m3(blocked.occupiedM3(), "0", "empty");

        Map<String, Group> rows = byKey(board(w));
        Group shelfA = rows.get("SHELF:" + layout.zonedShelfId());
        Group shelfB = rows.get("SHELF:" + layout.looseShelfId());
        m3(a.occupiedM3().add(blocked.occupiedM3()), shelfA.occupiedM3().toPlainString(), "shelf A01 occupied");
        m3(a.capacityM3(), shelfA.capacityM3().toPlainString(), "shelf A01 capacity: its usable bin");
        m3(bins.get(2).occupiedM3(), shelfB.occupiedM3().toPlainString(), "shelf B01 occupied");
        m3(bins.get(2).capacityM3(), shelfB.capacityM3().toPlainString(), "shelf B01 capacity");
    }

    @Test
    @DisplayName("#456: a warehouse manager reads the bins of their warehouse only; another is 403, an unknown 404")
    void binOccupancyScope() {
        Warehouse mine = layout().warehouse();
        Warehouse other = layout().warehouse();
        UUID managerId = managerOf(mine);
        CurrentUser manager = new CurrentUser(managerId, "wm-" + managerId, Set.of(Role.WAREHOUSE_MANAGER), Set.of(),
                DataScope.WAREHOUSE, Set.of(mine.code()), Set.of(mine.id()));

        assertThat(WithCurrentUser.call(manager, DataScope.ALL, () -> capacity.binOccupancy(mine.id()))).hasSize(3);
        assertThatThrownBy(() -> WithCurrentUser.call(manager, DataScope.ALL, () -> capacity.binOccupancy(other.id())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        assertThatThrownBy(() -> capacity.binOccupancy(UUID.randomUUID()))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
    }
}
