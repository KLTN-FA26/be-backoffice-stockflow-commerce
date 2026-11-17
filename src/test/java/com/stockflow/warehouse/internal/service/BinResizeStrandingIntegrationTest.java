package com.stockflow.warehouse.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.support.CartonRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.warehouse.internal.domain.BinDetails;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LevelMeasures;
import com.stockflow.warehouse.internal.domain.LocationSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * kltn-docs 06 BR-23 (decided 2026-11-05): a bin, a level's clear height or a carton unit cannot be resized so that a
 * carton standing in a bin no longer fits it, or a bin holds more volume than it has. The map asks inventory through
 * the warehouse's SPI ({@code warehouse :: spi}); the carton unit asks inside inventory.
 *
 * <p>Demo bin {@code HCM-B01-2-A}: 5 m wide x 1.2 m long on a level 1.4 m clear. Two cartons of 50 x 40 x 30 cm
 * (0.06 m³ each, rotatable) stand in it. Every test rolls back.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class BinResizeStrandingIntegrationTest {

    private static final String BIN = "HCM-B01-2-A";

    @Autowired ShelfLayoutService shelves;
    @Autowired UomConversionService units;
    @Autowired JdbcTemplate jdbc;

    private String sku;
    private UUID unit;
    private final UUID shelf = DemoData.shelf("B01");
    private final UUID level = DemoData.level("B01", 2);
    private final UUID bin = DemoData.bin(BIN);

    @BeforeEach
    void twoCartonsInTheBin() {
        sku = "BRS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        UUID product = Identifiers.newId();
        jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                "Stranding fixture", sku.toLowerCase(Locale.ROOT));
        jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                Identifiers.newId(), product, sku, "Stranding fixture");
        unit = CartonRows.unit(jdbc, sku, 10);
        jdbc.update("update inventory.uom_conversions set length_cm = 50, width_cm = 40, height_cm = 30 where id = ?",
                unit);
        CartonRows.stock(jdbc, sku, BIN, 2, 10, "LOT-1", Instant.now());
    }

    private ErrorCode refusal(Runnable change) {
        return assertThrows(BusinessException.class, change::run).errorCode();
    }

    private void resizeBin(String width, String length) {
        shelves.updateBin(new ShelfCommands.UpdateBin(shelf, level, bin, new BinDetails(null,
                new Footprint(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(width), new BigDecimal(length), 0),
                BinType.PALLET), new LocationSettings(new BigDecimal("250"), true, true)));
    }

    private void clearHeight(String height) {
        shelves.updateLevel(new ShelfCommands.UpdateLevel(shelf, level,
                new LevelMeasures(new BigDecimal("1.5"), new BigDecimal(height), new BigDecimal("500"))));
    }

    @Test
    @DisplayName("a bin made too narrow for the cartons in it is refused, naming them; one that still takes them is not")
    void binTooNarrow() {
        assertThat(refusal(() -> resizeBin("0.35", "1.2"))).isEqualTo(ErrorCode.CARTONS_WOULD_NOT_FIT);
        assertThat(jdbc.queryForObject("select width from warehouse.bin where id = ?", BigDecimal.class, bin))
                .isEqualByComparingTo("5");

        resizeBin("0.4", "0.5");
        assertThat(jdbc.queryForObject("select width from warehouse.bin where id = ?", BigDecimal.class, bin))
                .isEqualByComparingTo("0.4");
    }

    @Test
    @DisplayName("a level's clear height lowered under a carton is refused; down to the carton's height it is not")
    void clearHeightUnderACarton() {
        assertThat(refusal(() -> clearHeight("0.29"))).isEqualTo(ErrorCode.CARTONS_WOULD_NOT_FIT);
        clearHeight("0.3");
        assertThat(jdbc.queryForObject("select usable_height from warehouse.shelf_level where id = ?",
                BigDecimal.class, level)).isEqualByComparingTo("0.3");
    }

    @Test
    @DisplayName("each carton still fits, but the bin's new volume is less than theirs: refused for volume")
    void volumeBelowWhatIsInIt() {
        resizeBin("0.4", "0.5");    // 0.28 m³ at 1.4 m: still takes both cartons (0.12 m³)

        BusinessException refused = assertThrows(BusinessException.class, () -> clearHeight("0.3"));  // 0.06 m³

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.CARTONS_WOULD_NOT_FIT);
        assertThat(refused.getMessage()).contains(BIN + " (volume)");
    }

    @Test
    @DisplayName("a carton unit made taller than the level the cartons stand on is refused; a smaller size is not")
    void cartonUnitResized() {
        long version = jdbc.queryForObject("select version from inventory.uom_conversions where id = ?", Long.class,
                unit);
        assertThat(refusal(() -> units.change(new Sku(sku), unit, version, new UomConversionDraft("CTN", 10, null,
                new BigDecimal("50"), new BigDecimal("40"), new BigDecimal("141"), false, true))))
                .isEqualTo(ErrorCode.CARTONS_WOULD_NOT_FIT);

        units.change(new Sku(sku), unit, version, new UomConversionDraft("CTN", 10, null, new BigDecimal("45"),
                new BigDecimal("40"), new BigDecimal("30"), false, true));
        assertThat(jdbc.queryForObject("select length_cm from inventory.uom_conversions where id = ?",
                BigDecimal.class, unit)).isEqualByComparingTo("45");
    }
}
