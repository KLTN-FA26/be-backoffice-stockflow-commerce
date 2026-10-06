package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionCatalog;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.Warehouse;
import com.stockflow.warehouse.internal.domain.WarehouseRepository;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Warehouses and zones through the application service, on a real Postgres with the demo seed
 * ({@code db/demo/V20260928009000}): warehouse {@code HCM}, a 60 x 40 map whose layout reaches
 * 60 x 38 - the north wall and east door at x = 60, the office area down to y = 38 - and zones
 * "Khu A - Sofa" and "Khu B - Bàn". Every test rolls back.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class WarehouseLayoutServiceIntegrationTest {

    @Autowired WarehouseLayoutService layout;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired PermissionCatalog permissionCatalog;
    @Autowired JdbcTemplate jdbc;

    private UUID hcm() {
        return jdbc.queryForObject("select id from warehouse.warehouse where prefix = 'HCM'", UUID.class);
    }

    private WarehouseSummary registerHanoi() {
        return layout.register(new RegisterWarehouseCommand("hn", "Kho Hà Nội", "1 Phố Huế", null,
                MapUnit.M, new BigDecimal("40"), new BigDecimal("25")));
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
    @DisplayName("warehouses")
    class Warehouses {

        @Test
        @DisplayName("registers a warehouse with an upper-cased prefix, also written to the legacy code column")
        void registers() {
            WarehouseSummary hanoi = registerHanoi();

            assertThat(hanoi.prefix()).isEqualTo("HN");
            assertThat(hanoi.status()).isEqualTo(WarehouseStatus.ACTIVE);
            assertThat(hanoi.createdAt()).isNotNull();
            assertThat(jdbc.queryForObject("select code from warehouse.warehouse where id = ?",
                    String.class, hanoi.id())).isEqualTo("HN");
        }

        @Test
        @DisplayName("refuses a prefix already taken, in any case")
        void refusesDuplicatePrefix() {
            assertThat(errorOf(() -> layout.register(new RegisterWarehouseCommand("hcm", "Kho 2",
                    "Địa chỉ", null, MapUnit.M, BigDecimal.TEN, BigDecimal.TEN))))
                    .isEqualTo(ErrorCode.WAREHOUSE_PREFIX_ALREADY_EXISTS);
        }

        /** The service checks first; the unique constraint is the net for two requests racing. */
        @Test
        @DisplayName("a duplicate that gets past the check is still reported as a duplicate prefix")
        void duplicateCaughtByTheConstraint() {
            Warehouse twin = Warehouse.register(Identifiers.newId(), "HCM", "Kho trùng", "Địa chỉ",
                    null, MapUnit.M, BigDecimal.TEN, BigDecimal.TEN);

            assertThat(errorOf(() -> warehouseRepository.save(twin)))
                    .isEqualTo(ErrorCode.WAREHOUSE_PREFIX_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("updates the details and keeps the prefix")
        void updates() {
            WarehouseSummary hanoi = registerHanoi();

            WarehouseSummary updated = layout.update(new UpdateWarehouseCommand(hanoi.id(),
                    "Kho Hà Nội mới", "2 Phố Huế", "Kho trả hàng", new BigDecimal("45"),
                    new BigDecimal("25"), hanoi.version()));

            assertThat(updated.prefix()).isEqualTo("HN");
            assertThat(updated.name()).isEqualTo("Kho Hà Nội mới");
            assertThat(updated.mapWidth()).isEqualByComparingTo("45");
            assertThat(updated.version()).isEqualTo(hanoi.version() + 1);
        }

        @Test
        @DisplayName("refuses an edit based on an older version")
        void refusesStaleEdit() {
            WarehouseSummary hanoi = registerHanoi();

            assertThat(errorOf(() -> layout.update(new UpdateWarehouseCommand(hanoi.id(), "X", "Y",
                    null, hanoi.mapWidth(), hanoi.mapHeight(), hanoi.version() + 1))))
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        }

        @Test
        @DisplayName("shrinks the map exactly to what is on it, not a millimetre further (BR-06)")
        void shrinksOnlyToTheLayout() {
            WarehouseSummary hcm = layout.get(hcm());

            assertThat(errorOf(() -> resize(hcm, "59.999", "40"))).isEqualTo(ErrorCode.LAYOUT_OUT_OF_BOUNDS);
            assertThat(errorOf(() -> resize(hcm, "60", "37.999"))).isEqualTo(ErrorCode.LAYOUT_OUT_OF_BOUNDS);
            assertThat(resize(hcm, "60", "38").mapHeight()).isEqualByComparingTo("38");
        }

        @Test
        @DisplayName("an INACTIVE area no longer holds the map open (issue #18 D3)")
        void inactiveAreasDoNotCount() {
            jdbc.update("update warehouse.area set status = 'INACTIVE' where code = 'OFFICE'");
            WarehouseSummary hcm = layout.get(hcm());

            assertThat(resize(hcm, "60", "34").mapHeight()).isEqualByComparingTo("34");
        }

        @Test
        @DisplayName("deactivates and reactivates")
        void togglesStatus() {
            UUID id = registerHanoi().id();

            assertThat(layout.deactivate(id).status()).isEqualTo(WarehouseStatus.INACTIVE);
            assertThat(layout.activate(id).status()).isEqualTo(WarehouseStatus.ACTIVE);
        }

        @Test
        @DisplayName("lists by prefix or name, filtered by status")
        void lists() {
            registerHanoi();

            assertThat(layout.list(new ListWarehousesQuery(0, 20, "hồ chí", null, null)).items())
                    .extracting(WarehouseSummary::prefix).containsExactly("HCM");
            assertThat(layout.list(new ListWarehousesQuery(0, 20, "hn", null, null)).items())
                    .extracting(WarehouseSummary::prefix).containsExactly("HN");
            assertThat(layout.list(new ListWarehousesQuery(0, 20, null, WarehouseStatus.INACTIVE, null))
                    .items()).isEmpty();
            assertThat(layout.list(new ListWarehousesQuery(0, 20, null, null, "prefix,desc")).items())
                    .extracting(WarehouseSummary::prefix).containsExactly("HN", "HCM");
        }

        @Test
        @DisplayName("an unknown id is WAREHOUSE_NOT_FOUND")
        void unknownWarehouse() {
            assertThat(errorOf(() -> layout.get(Identifiers.newId()))).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
            assertThat(errorOf(() -> layout.deactivate(Identifiers.newId())))
                    .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        }

        private WarehouseSummary resize(WarehouseSummary current, String width, String height) {
            return layout.update(new UpdateWarehouseCommand(current.id(), current.name(),
                    current.address(), current.returnAddress(), new BigDecimal(width),
                    new BigDecimal(height), current.version()));
        }
    }

    @Nested
    @DisplayName("zones")
    class Zones {

        @Test
        @DisplayName("creates a zone with an upper-cased colour")
        void creates() {
            ZoneSummary zone = layout.createZone(new CreateZoneCommand(hcm(), "Khu C - Ghế", "#ff8800"));

            assertThat(zone.color()).isEqualTo("#FF8800");
            assertThat(layout.listZones(hcm())).extracting(ZoneSummary::name)
                    .containsExactly("Khu A - Sofa", "Khu B - Bàn", "Khu C - Ghế");
        }

        @Test
        @DisplayName("refuses a second zone with the same name in one warehouse, allows it in another")
        void namesAreUniquePerWarehouse() {
            assertThat(errorOf(() -> layout.createZone(new CreateZoneCommand(hcm(), "Khu A - Sofa", null))))
                    .isEqualTo(ErrorCode.ZONE_NAME_ALREADY_EXISTS);

            UUID hanoi = registerHanoi().id();
            assertThat(layout.createZone(new CreateZoneCommand(hanoi, "Khu A - Sofa", null)).warehouseId())
                    .isEqualTo(hanoi);
        }

        @Test
        @DisplayName("renames a zone, but not onto another zone's name; keeping its own name is fine")
        void renames() {
            ZoneSummary a = zoneNamed("Khu A - Sofa");

            assertThat(errorOf(() -> layout.updateZone(new UpdateZoneCommand(a.id(), "Khu B - Bàn",
                    null, a.version())))).isEqualTo(ErrorCode.ZONE_NAME_ALREADY_EXISTS);
            assertThat(layout.updateZone(new UpdateZoneCommand(a.id(), "Khu A - Sofa", "#000000",
                    a.version())).color()).isEqualTo("#000000");
        }

        @Test
        @DisplayName("a zone of an unknown warehouse, or an unknown zone, is not found")
        void unknownTargets() {
            assertThat(errorOf(() -> layout.createZone(new CreateZoneCommand(Identifiers.newId(), "X", null))))
                    .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
            assertThat(errorOf(() -> layout.updateZone(new UpdateZoneCommand(Identifiers.newId(), "X",
                    null, 0L)))).isEqualTo(ErrorCode.ZONE_NOT_FOUND);
        }

        private ZoneSummary zoneNamed(String name) {
            return layout.listZones(hcm()).stream().filter(z -> z.name().equals(name)).findFirst()
                    .orElseThrow();
        }
    }

    /**
     * The resource codes are a contract with the seed in {@code V20260928006000}: a code or action
     * declared here that no permission row matches could never be granted to anyone.
     */
    @Test
    @DisplayName("every declared warehouse permission exists in the seeded permission table")
    void permissionsMatchTheSeed() {
        List<PermissionCode> declared = List.of(
                PermissionCode.of("warehouse-warehouses", Action.VIEW_PAGE),
                PermissionCode.of("warehouse-warehouses", Action.READ),
                PermissionCode.of("warehouse-warehouses", Action.CREATE),
                PermissionCode.of("warehouse-warehouses", Action.UPDATE),
                PermissionCode.of("warehouse-locations", Action.VIEW_PAGE),
                PermissionCode.of("warehouse-locations", Action.READ),
                PermissionCode.of("warehouse-locations", Action.CREATE),
                PermissionCode.of("warehouse-locations", Action.UPDATE),
                PermissionCode.of("warehouse-locations", Action.DELETE));

        assertThat(declared).allSatisfy(code -> {
            assertThat(permissionCatalog.isDeclared(code)).as("declared in code: %s", code).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from identity.permission where resource = ? and action = ?",
                    Integer.class, code.resource(), code.action().name()))
                    .as("seeded: %s", code).isEqualTo(1);
        });
    }
}
