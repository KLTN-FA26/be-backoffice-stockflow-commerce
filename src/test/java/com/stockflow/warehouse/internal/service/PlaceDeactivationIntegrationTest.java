package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.support.CartonRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * kltn-docs 06 BR-04 (decided 2026-11-05): a bin, a shelf, an area or a warehouse goes {@code INACTIVE} only once no
 * carton stands there; {@code BLOCKED} stays allowed with cartons in place.
 *
 * <p>Demo {@code HCM}: 25 cartons in bin {@code HCM-A01-2-B} (level 2 of shelf A01, level 1 empty), 30 in area
 * {@code QC01}, 75 in all; bin {@code HCM-A01-1-A} is empty. Every test rolls back.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class PlaceDeactivationIntegrationTest {

    private static final String FULL_BIN = "HCM-A01-2-B";
    private static final String EMPTY_BIN = "HCM-A01-1-A";

    @Autowired ShelfLayoutService shelves;
    @Autowired AreaLayoutService areas;
    @Autowired WarehouseLayoutService warehouses;
    @Autowired JdbcTemplate jdbc;

    private final UUID a01 = DemoData.shelf("A01");

    private BusinessException refusal(Runnable change) {
        return assertThrows(BusinessException.class, change::run);
    }

    private ShelfSummary.BinEntry binStatus(String code, int level, LocationStatus status) {
        return shelves.changeBinStatus(a01, DemoData.level("A01", level), DemoData.bin(code), status);
    }

    private String locationStatus(String code) {
        return jdbc.queryForObject("select status from warehouse.storage_location where location_code = ?",
                String.class, code);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listed(BusinessException refused) {
        return (List<Map<String, Object>>) refused.details().get("cartons");
    }

    /** A fixture SKU with a carton unit of 10, so cartons can be written as rows. */
    private String fixtureSku() {
        String sku = "PDI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        UUID product = Identifiers.newId();
        jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                "Deactivation fixture", sku.toLowerCase(Locale.ROOT));
        jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                Identifiers.newId(), product, sku, "Deactivation fixture");
        CartonRows.unit(jdbc, sku, 10);
        return sku;
    }

    @Test
    @DisplayName("a bin with cartons in it is refused, naming them, and stays ACTIVE; BLOCKED is allowed")
    void binWithCartons() {
        BusinessException refused = refusal(() -> binStatus(FULL_BIN, 2, LocationStatus.INACTIVE));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.LOCATION_HOLDS_CARTONS);
        assertThat(refused.details()).containsEntry("total", 25L);
        assertThat(listed(refused)).hasSize(25).allSatisfy(c -> assertThat(c).containsEntry("locationCode", FULL_BIN));
        assertThat(refused.getMessage()).contains("bin " + FULL_BIN);
        assertThat(locationStatus(FULL_BIN)).isEqualTo("ACTIVE");

        assertThat(binStatus(FULL_BIN, 2, LocationStatus.BLOCKED).status()).isEqualTo(LocationStatus.BLOCKED);
        assertThat(refusal(() -> binStatus(FULL_BIN, 2, LocationStatus.INACTIVE)).errorCode())
                .isEqualTo(ErrorCode.LOCATION_HOLDS_CARTONS);
    }

    @Test
    @DisplayName("an empty bin goes INACTIVE")
    void emptyBin() {
        binStatus(EMPTY_BIN, 1, LocationStatus.INACTIVE);

        assertThat(locationStatus(EMPTY_BIN)).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("a carton labelled on a draft receipt counts; a picked (CONSUMED) one does not")
    void whichCartonsCount() {
        String sku = fixtureSku();
        UUID unit = CartonRows.unit(jdbc, sku, 10);
        jdbc.update("""
                insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                                                     location_code, status)
                select ?, ?, inventory_item_id, id, factor, ?, 'RECEIVING' from inventory.uom_conversions where id = ?""",
                Identifiers.newId(), "PDI-RCV-" + sku, EMPTY_BIN, unit);

        assertThat(refusal(() -> binStatus(EMPTY_BIN, 1, LocationStatus.INACTIVE)).errorCode())
                .isEqualTo(ErrorCode.LOCATION_HOLDS_CARTONS);

        String other = "HCM-A01-1-B";
        CartonRows.Carton picked = CartonRows.stock(jdbc, sku, other, 1, 10, "LOT-1", Instant.now()).getFirst();
        jdbc.update("update inventory.stock_item set on_hand = 0 where id = ?", picked.stockItemId());
        jdbc.update("update inventory.stock_package set status = 'CONSUMED', closed_at = now() where id = ?",
                picked.id());

        binStatus(other, 1, LocationStatus.INACTIVE);
        assertThat(locationStatus(other)).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("a shelf is refused for cartons on any of its levels; emptied, it goes INACTIVE")
    void shelf() {
        BusinessException refused = refusal(() -> shelves.changeShelfStatus(a01, LocationStatus.INACTIVE));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.LOCATION_HOLDS_CARTONS);
        assertThat(refused.details()).containsEntry("total", 25L);

        CartonRows.moveOut(jdbc, "HCM-A01-", "HCM-OVF01");
        assertThat(shelves.changeShelfStatus(a01, LocationStatus.INACTIVE).status()).isEqualTo(LocationStatus.INACTIVE);
    }

    @Test
    @DisplayName("a storage area with cartons is refused; a NON_STORAGE area never is")
    void areas() {
        BusinessException refused = refusal(() -> areas.changeAreaStatus(DemoData.area("QC01"), LocationStatus.INACTIVE));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.LOCATION_HOLDS_CARTONS);
        assertThat(refused.details()).containsEntry("total", 30L);

        assertThat(areas.changeAreaStatus(DemoData.area("OFFICE"), LocationStatus.INACTIVE).status())
                .isEqualTo(LocationStatus.INACTIVE);
    }

    @Test
    @DisplayName("a warehouse with cartons anywhere is refused: the real total, the first 50 listed")
    void warehouse() {
        long inStock = jdbc.queryForObject("""
                select count(*) from inventory.stock_package
                where location_code like 'HCM-%' and status in ('RECEIVING', 'IN_STOCK')""", Long.class);

        BusinessException refused = refusal(() -> warehouses.deactivate(DemoData.WAREHOUSE_HCM));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.LOCATION_HOLDS_CARTONS);
        assertThat(inStock).isGreaterThan(50);
        assertThat(refused.details()).containsEntry("total", inStock);
        assertThat(listed(refused)).hasSize(50);
        assertThat(refused.getMessage()).contains("warehouse HCM").contains("and " + (inStock - 50) + " more");
        assertThat(jdbc.queryForObject("select status from warehouse.warehouse where id = ?", String.class,
                DemoData.WAREHOUSE_HCM)).isEqualTo("ACTIVE");
    }
}
