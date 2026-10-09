package com.stockflow.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DemoData} against the seeded database (issue #26). The context starting at all is the first
 * check: the test profile applies {@code db/migration} and {@code db/demo}, as {@code local} does,
 * and Hibernate validates every entity against the result ({@code ddl-auto: validate}).
 *
 * <p>Every query is by id or restricted to rows the seed wrote ({@code created_by = 'flyway'}): the
 * database is shared with every other integration test, and what they commit is not this test's
 * business.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class DemoDataIntegrationTest {

    private static final List<String> SHELVES = List.of("A01", "A02", "B01");
    private static final List<String> STORAGE_AREAS = List.of("RCV01", "QC01", "PACK01", "DSP01");

    @Autowired JdbcTemplate jdbc;

    private Map<String, Object> row(String sql, Object... args) {
        return jdbc.queryForMap(sql, args);
    }

    @Test
    @DisplayName("demoId is Postgres md5(text)::uuid, which nameUUIDFromBytes is not")
    void demoIdIsPostgresMd5() {
        assertThat(DemoData.demoId("demo:wh:HCM"))
                .isEqualTo(jdbc.queryForObject("select md5('demo:wh:HCM')::uuid", UUID.class));
        assertThat(DemoData.demoId("Khu B - Bàn"))
                .isEqualTo(jdbc.queryForObject("select md5('Khu B - Bàn')::uuid", UUID.class));
        assertThat(UUID.nameUUIDFromBytes("demo:wh:HCM".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(DemoData.WAREHOUSE_HCM);
    }

    @Test
    @DisplayName("the warehouse, its zones, wall and door are the seeded rows")
    void frameRows() {
        assertThat(row("select prefix from warehouse.warehouse where id = ?", DemoData.WAREHOUSE_HCM))
                .containsEntry("prefix", "HCM");
        assertThat(row("select warehouse_id, color from warehouse.zone where id = ?", DemoData.ZONE_HCM_A))
                .containsEntry("warehouse_id", DemoData.WAREHOUSE_HCM).containsEntry("color", "#4F81BD");
        assertThat(row("select warehouse_id, color from warehouse.zone where id = ?", DemoData.ZONE_HCM_B))
                .containsEntry("warehouse_id", DemoData.WAREHOUSE_HCM).containsEntry("color", "#9BBB59");
        assertThat(row("select warehouse_id, type from warehouse.boundary where id = ?", DemoData.WALL_HCM_NORTH))
                .containsEntry("warehouse_id", DemoData.WAREHOUSE_HCM).containsEntry("type", "WALL");
        assertThat(row("select warehouse_id, type from warehouse.boundary where id = ?", DemoData.DOOR_HCM_EAST))
                .containsEntry("warehouse_id", DemoData.WAREHOUSE_HCM).containsEntry("type", "DOOR");
    }

    /** Each of the 3 shelves, 6 levels, 12 bins and their 12 locations, by the helpers' ids. */
    @Test
    @DisplayName("every shelf, level, bin and bin location of HCM is where the helpers say")
    void everyShelfRow() {
        for (String shelf : SHELVES) {
            assertThat(row("select warehouse_id, code from warehouse.shelf where id = ?", DemoData.shelf(shelf)))
                    .containsEntry("warehouse_id", DemoData.WAREHOUSE_HCM).containsEntry("code", shelf);
            for (int index = 1; index <= 2; index++) {
                UUID level = DemoData.level(shelf, index);
                assertThat(row("select shelf_id, level_index from warehouse.shelf_level where id = ?", level))
                        .containsEntry("shelf_id", DemoData.shelf(shelf)).containsEntry("level_index", index);
                for (String bin : List.of("A", "B")) {
                    String code = "HCM-" + shelf + "-" + index + "-" + bin;
                    assertThat(row("select level_id, location_id from warehouse.bin where id = ?", DemoData.bin(code)))
                            .containsEntry("level_id", level).containsEntry("location_id", DemoData.location(code));
                    assertThat(row("select location_code from warehouse.storage_location where id = ?",
                            DemoData.location(code))).containsEntry("location_code", code);
                }
            }
        }
    }

    @Test
    @DisplayName("every area of HCM is where the helpers say; only OFFICE has no location")
    void everyArea() {
        for (String area : STORAGE_AREAS) {
            String code = "HCM-" + area;
            assertThat(row("select warehouse_id, location_id from warehouse.area where id = ?", DemoData.area(area)))
                    .containsEntry("warehouse_id", DemoData.WAREHOUSE_HCM)
                    .containsEntry("location_id", DemoData.location(code));
            assertThat(row("select location_code from warehouse.storage_location where id = ?",
                    DemoData.location(code))).containsEntry("location_code", code);
        }
        assertThat(row("select type, location_id from warehouse.area where id = ?", DemoData.area("OFFICE")))
                .containsEntry("type", "NON_STORAGE").containsEntry("location_id", null);
    }

    @Test
    @DisplayName("a name of the wrong shape is refused, not turned into the id of no row")
    void wrongShapesAreRefused() {
        assertThatThrownBy(() -> DemoData.bin("A01-2-B")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoData.location("A01-2-B")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoData.shelf("HCM-A01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoData.area("hcm-qc01")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the demo stock sits on locations of the map: no seeded stock item points at a missing code")
    void demoStockIsOnTheMap() {
        assertThat(jdbc.queryForList("""
                SELECT s.location_code FROM inventory.stock_item s
                WHERE s.created_by = 'flyway'
                  AND NOT EXISTS (SELECT 1 FROM warehouse.storage_location l WHERE l.location_code = s.location_code)
                """, String.class)).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT location_code FROM inventory.stock_item WHERE created_by = 'flyway' ORDER BY location_code
                """, String.class))
                .containsExactly("HCM-A01-2-B", "HCM-A02-1-A", "HCM-B01-1-A", "HCM-QC01");
    }
}
