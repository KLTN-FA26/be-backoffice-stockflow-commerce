package com.stockflow.warehouse;

import com.stockflow.common.id.Identifiers;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The warehouse map schema (SCRUM-89, issue #19), checked against a real Postgres.
 *
 * <p>Plain SQL on purpose: these are the database's half of "state every invariant twice", and the
 * point is that the constraint holds even when no Java code is in the way — a manual {@code UPDATE}
 * or a bad migration hits the same wall. Each test runs in a transaction that is rolled back, so
 * nothing written here is visible to other test classes.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class WarehouseSchemaIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("the flat location table is gone and the map tables exist")
    void theMapTablesReplaceTheFlatLocationTable() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'warehouse'",
                String.class);

        assertThat(tables)
                .doesNotContain("location")
                .contains("warehouse", "zone", "shelf", "shelf_level", "bin", "area", "boundary",
                        "putaway_task", "slotting_rule");
    }

    @Nested
    @DisplayName("location codes (BR-10)")
    class LocationCodes {

        @Test
        @DisplayName("a bin location code is unique across the whole system, not just its level")
        void binLocationCodeIsGloballyUnique() {
            UUID shelf = shelf(warehouse("HN"), "A01");
            UUID level1 = level(shelf, 1);
            UUID level2 = level(shelf, 2);
            bin(level1, "01", "HN-A01-1-01");

            assertThatThrownBy(() -> bin(level2, "01", "HN-A01-1-01"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_bin_location_code");
        }

        @Test
        @DisplayName("the same bin code may repeat on another level, never on the same one")
        void binCodeIsUniquePerLevel() {
            UUID shelf = shelf(warehouse("HN"), "A01");
            UUID level1 = level(shelf, 1);
            UUID level2 = level(shelf, 2);
            bin(level1, "01", "HN-A01-1-01");
            bin(level2, "01", "HN-A01-2-01");

            assertThatThrownBy(() -> bin(level1, "01", "HN-A01-1-01X"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_bin_level_code");
        }

        @Test
        @DisplayName("an area location code is unique across the whole system")
        void areaLocationCodeIsGloballyUnique() {
            UUID hanoi = warehouse("HN");
            area(hanoi, "RCV01", "HN-RCV01");

            assertThatThrownBy(() -> area(warehouse("HCM"), "RCV02", "HN-RCV01"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_area_location_code");
        }

        // One violation per test: after the first one Postgres aborts the transaction, and every
        // later statement fails with "transaction is aborted" instead of its own constraint.

        @Test
        @DisplayName("a warehouse prefix is upper case - 'hn' is refused")
        void prefixIsUpperCase() {
            assertThatThrownBy(() -> warehouse("hn"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_warehouse_prefix");
        }

        @Test
        @DisplayName("a shelf code has no hyphen - 'A-1' is refused, it would make location codes ambiguous")
        void shelfCodeHasNoHyphen() {
            UUID hanoi = warehouse("HN");

            assertThatThrownBy(() -> shelf(hanoi, "A-1"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_shelf_code");
        }
    }

    @Nested
    @DisplayName("geometry")
    class Geometry {

        @Test
        @DisplayName("rotation is a quarter turn - 45 degrees is refused")
        void rotationIsAQuarterTurn() {
            UUID hanoi = warehouse("HN");

            assertThatThrownBy(() -> jdbc.update("""
                    insert into warehouse.shelf (id, warehouse_id, code, name, x, y, width, length,
                        rotation, is_obstacle, pick_north, pick_east, pick_south, pick_west,
                        default_storage_class, status, version, created_at)
                    values (?, ?, 'A01', 'Shelf A01', 0, 0, 2, 1, 45, true, true, false, false,
                        false, 'NORMAL', 'ACTIVE', 0, now())""", Identifiers.newId(), hanoi))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_shelf_rotation");
        }

        @Test
        @DisplayName("a shelf has a positive footprint")
        void shelfHasAPositiveFootprint() {
            UUID hanoi = warehouse("HN");

            assertThatThrownBy(() -> jdbc.update("""
                    insert into warehouse.shelf (id, warehouse_id, code, name, x, y, width, length,
                        rotation, is_obstacle, pick_north, pick_east, pick_south, pick_west,
                        default_storage_class, status, version, created_at)
                    values (?, ?, 'A01', 'Shelf A01', 0, 0, 0, 1, 0, true, true, false, false,
                        false, 'NORMAL', 'ACTIVE', 0, now())""", Identifiers.newId(), hanoi))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_shelf_size");
        }
    }

    @Nested
    @DisplayName("boundaries")
    class Boundaries {

        @Test
        @DisplayName("a wall can never be passable")
        void aWallCannotBePassable() {
            UUID hanoi = warehouse("HN");

            assertThatThrownBy(() -> boundary(hanoi, "WALL", true, null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_boundary_kind");
        }

        @Test
        @DisplayName("a door must say whether it is open or closed")
        void aDoorNeedsAnOperationalStatus() {
            UUID hanoi = warehouse("HN");
            boundary(hanoi, "DOOR", true, "OPEN");

            assertThatThrownBy(() -> boundary(hanoi, "DOOR", true, null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_boundary_kind");
        }
    }

    // ---- fixtures: the smallest valid row of each table -------------------------------------

    private UUID warehouse(String prefix) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                insert into warehouse.warehouse (id, prefix, name, address, map_unit, map_width,
                    map_height, status, version, created_at)
                values (?, ?, 'Test warehouse', '1 Test street', 'M', 40, 25, 'ACTIVE', 0, now())""",
                id, prefix);
        return id;
    }

    private UUID shelf(UUID warehouseId, String code) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                insert into warehouse.shelf (id, warehouse_id, code, name, x, y, width, length,
                    rotation, is_obstacle, pick_north, pick_east, pick_south, pick_west,
                    default_storage_class, status, version, created_at)
                values (?, ?, ?, 'Test shelf', 0, 0, 2, 1, 0, true, true, false, false, false,
                    'NORMAL', 'ACTIVE', 0, now())""", id, warehouseId, code);
        return id;
    }

    private UUID level(UUID shelfId, int levelIndex) {
        UUID id = Identifiers.newId();
        jdbc.update("insert into warehouse.shelf_level (id, shelf_id, level_index) values (?, ?, ?)",
                id, shelfId, levelIndex);
        return id;
    }

    private UUID bin(UUID levelId, String code, String locationCode) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                insert into warehouse.bin (id, level_id, code, location_code, x, y, width, length,
                    rotation, is_pickable, is_putaway_bin, type, status)
                values (?, ?, ?, ?, 0, 0, 1, 1, 0, true, true, 'STANDARD', 'ACTIVE')""",
                id, levelId, code, locationCode);
        return id;
    }

    private UUID area(UUID warehouseId, String code, String locationCode) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                insert into warehouse.area (id, warehouse_id, code, location_code, type, name, x, y,
                    width, length, rotation, is_obstacle, status, version, created_at)
                values (?, ?, ?, ?, 'RECEIVING', 'Receiving', 10, 10, 3, 3, 0, false, 'ACTIVE', 0,
                    now())""", id, warehouseId, code, locationCode);
        return id;
    }

    private UUID boundary(UUID warehouseId, String type, boolean passable, String doorStatus) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                insert into warehouse.boundary (id, warehouse_id, type, start_x, start_y, end_x,
                    end_y, is_passable, operational_status, version, created_at)
                values (?, ?, ?, 0, 0, 5, 0, ?, ?, 0, now())""",
                id, warehouseId, type, passable, doorStatus);
        return id;
    }
}
