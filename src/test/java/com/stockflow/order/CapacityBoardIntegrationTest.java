package com.stockflow.order;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.order.internal.domain.CapacityBoard;
import com.stockflow.order.internal.service.CapacityBoardService;
import com.stockflow.support.CartonRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The capacity board against the demo warehouse HCM (kltn-docs 06 §4.6; SCRUM-669). The expected figures are worked out
 * here with SQL straight from the tables - the test may read every schema, the application may not (ADR-0005) - so the
 * test does not depend on what other classes left in a shared database. Production orders are covered by
 * {@code ProductionOrderIntegrationTest.capacityBoardCountsWhatProductionOrdersOwe}. Every test rolls back.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class CapacityBoardIntegrationTest {

    @Autowired CapacityBoardService boards;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;

    private final UUID hcm = DemoData.WAREHOUSE_HCM;

    /** Σ width x length x clear height of HCM's bins whose bin, shelf and warehouse are all ACTIVE. */
    private BigDecimal usableBinVolume() {
        return jdbc.queryForObject("""
                select coalesce(sum(b.width * b.length * lv.usable_height), 0)
                  from warehouse.bin b
                  join warehouse.shelf_level lv on lv.id = b.level_id
                  join warehouse.shelf s on s.id = lv.shelf_id
                  join warehouse.storage_location l on l.id = b.location_id
                  join warehouse.warehouse w on w.id = s.warehouse_id
                 where w.id = ? and w.status = 'ACTIVE' and s.status = 'ACTIVE' and l.status = 'ACTIVE'""",
                BigDecimal.class, hcm);
    }

    /** Cartons with units standing in HCM, except in its staging and dispatch areas. */
    private long cartonsOccupying() {
        return jdbc.queryForObject("""
                select count(distinct s.stock_package_id)
                  from inventory.stock_item s
                 where s.location_code like 'HCM-%' and s.on_hand > 0 and s.stock_package_id is not null
                   and s.location_code not in (select l.location_code from warehouse.area a
                                                 join warehouse.storage_location l on l.id = a.location_id
                                                where a.type in ('STAGING', 'DISPATCH'))""", Long.class);
    }

    @Test
    @DisplayName("capacity is the usable bins' volume; occupied counts bins and the receiving/quarantine areas")
    void demoWarehouse() {
        CapacityBoard board = boards.boardOf(hcm);

        assertThat(board.prefix()).isEqualTo("HCM");
        assertThat(board.utilizationFactor()).isEqualByComparingTo("1");
        assertThat(board.capacityM3()).isEqualByComparingTo(usableBinVolume());
        assertThat(board.occupied().cartons()).isEqualTo(cartonsOccupying());
        assertThat(board.occupied().cartons()).as("the demo's 75 cartons, 30 of them quarantined").isGreaterThanOrEqualTo(75);
        assertThat(board.expectedFreeM3()).isEqualByComparingTo(
                board.capacityM3().subtract(board.occupied().volumeM3()).subtract(board.incoming().volumeM3()));
        assertThat(board.areas()).filteredOn(a -> a.code().equals("QC01")).singleElement()
                .satisfies(a -> assertThat(a.countsAsOccupied()).isTrue());
        assertThat(board.shelves()).extracting(CapacityBoard.Section::shelfCode).contains("A01", "A02", "B01");
        assertThat(board.zones().stream().map(CapacityBoard.Section::capacityM3).reduce(BigDecimal.ZERO,
                BigDecimal::add)).isEqualByComparingTo(board.capacityM3());
    }

    @Test
    @DisplayName("a carton in the staging area is listed there but not counted as occupied")
    void stagingIsListedNotCounted() {
        long before = boards.boardOf(hcm).occupied().cartons();
        String sku = fixtureSku();
        CartonRows.stock(jdbc, sku, "HCM-STG01", 1, 10, "LOT-1", Instant.now());

        CapacityBoard board = boards.boardOf(hcm);

        assertThat(board.occupied().cartons()).isEqualTo(before);
        assertThat(board.areas()).filteredOn(a -> a.code().equals("STG01")).singleElement().satisfies(a -> {
            assertThat(a.countsAsOccupied()).isFalse();
            assertThat(a.occupied().cartons()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("the utilisation factor scales capacity only; a blocked bin leaves capacity but its cartons stay")
    void factorAndBlockedBin() {
        CapacityBoard full = boards.boardOf(hcm);

        jdbc.update("update warehouse.warehouse set utilization_factor = 0.85 where id = ?", hcm);
        entityManager.clear();      // the warehouse row read above is still in this test's persistence context
        CapacityBoard scaled = boards.boardOf(hcm);
        assertThat(scaled.capacityM3()).isEqualByComparingTo(full.capacityM3().multiply(new BigDecimal("0.85")));
        assertThat(scaled.occupied()).isEqualTo(full.occupied());

        jdbc.update("update warehouse.storage_location set status = 'BLOCKED' where location_code = 'HCM-A01-2-B'");
        entityManager.clear();
        CapacityBoard blocked = boards.boardOf(hcm);
        assertThat(blocked.capacityM3()).isEqualByComparingTo(usableBinVolume().multiply(new BigDecimal("0.85")));
        assertThat(blocked.capacityM3()).isLessThan(scaled.capacityM3());
        assertThat(blocked.occupied().cartons()).isEqualTo(full.occupied().cartons());
    }

    @Test
    @DisplayName("an unknown warehouse is 404; a warehouse manager of another warehouse is refused")
    void unknownAndOutOfScope() {
        assertThat(assertThrows(BusinessException.class, () -> boards.boardOf(Identifiers.newId())).errorCode())
                .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);

        CurrentUser elsewhere = new CurrentUser(Identifiers.newId(), "manager-elsewhere",
                Set.of(Role.WAREHOUSE_MANAGER), Set.of(), DataScope.WAREHOUSE, Set.of("HN"), Set.of(Identifiers.newId()));
        BusinessException refused = assertThrows(BusinessException.class,
                () -> WithCurrentUser.call(elsewhere, DataScope.WAREHOUSE, () -> boards.boardOf(hcm)));
        assertThat(refused.errorCode().httpStatus()).isEqualTo(403);

        CurrentUser own = new CurrentUser(Identifiers.newId(), "manager-hcm", Set.of(Role.WAREHOUSE_MANAGER),
                Set.of(), DataScope.WAREHOUSE, Set.of("HCM"), Set.of(hcm));
        assertThat(WithCurrentUser.call(own, DataScope.WAREHOUSE, () -> boards.boardOf(hcm)).prefix())
                .isEqualTo("HCM");
    }

    /** A fixture SKU with a carton unit of 10, so cartons can be written as rows. */
    private String fixtureSku() {
        String sku = "CBI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        UUID product = Identifiers.newId();
        jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                "Capacity fixture", sku.toLowerCase(Locale.ROOT));
        jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                Identifiers.newId(), product, sku, "Capacity fixture");
        CartonRows.unit(jdbc, sku, 10);
        return sku;
    }
}
