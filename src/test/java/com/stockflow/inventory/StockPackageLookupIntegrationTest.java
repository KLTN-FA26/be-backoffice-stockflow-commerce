package com.stockflow.inventory;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.CountReceivingPackagesCommand.PackageCount;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.internal.domain.StockPackageBalance;
import com.stockflow.inventory.internal.domain.StockPackageBalance.LocationLine;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.service.StockPackageLookup;
import com.stockflow.inventory.internal.service.StockPackageLookup.CartonQuery;
import com.stockflow.support.DemoData;
import com.stockflow.support.ProductionReceiptTest;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * SCRUM-473 carton lookup against the real view and tables. Each test counts a line of 2000 as two cartons of the
 * standard 1000 at the receiving area; most then receive it, every unit into the cartons (team plan 4C-2). (An opened
 * carton is no longer possible: nothing takes part of a carton, V20261111000200.)
 */
@ProductionReceiptTest
class StockPackageLookupIntegrationTest {

    private static final String RECEIVING = "HCM-RCV01";
    private static final String LOT = "LOT-FX";
    private static final String BIN = "HCM-A01-1-A";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired StockPackageLookup lookup;
    @Autowired InventoryService inventory;
    @Autowired ProductionReceivables receivables;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private UUID carton;
    private UUID clerk;
    private UUID line;
    private List<String> lpns;

    @BeforeEach
    void setUp() {
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        // Two cartons of 1000 ordered; the clerk counts them below, so the line starts with none labelled.
        ProductionReceivables.Receivable goods = receivables.sent(DemoData.WAREHOUSE_HCM, 2, 1000);
        sku = goods.sku();
        carton = goods.cartonUnitId();
        line = receivables.uncountedLine(goods, receivables.draftReceipt(goods, clerk), RECEIVING, 2);
        lpns = count(PackageCount.fresh(), PackageCount.fresh()).stream()
                .map(StockPackageView::lpn).toList();
    }

    private List<StockPackageView> count(PackageCount... packages) {
        return inventory.countReceivingPackages(new CountReceivingPackagesCommand(line, new Sku(sku),
                packages.length == 0 ? null : carton, RECEIVING, List.of(packages), clerk));
    }

    /** Both cartons IN_STOCK, at the receiving area; nothing loose. */
    private void receive() {
        inventory.receive(new ReceiveStockCommand(line, new Sku(sku), LOT, RECEIVING, 2000, clerk, RECEIVED));
    }

    private PageResponse<StockPackageBalance> list(String location, String sku, List<StockPackageStatus> statuses) {
        return lookup.cartons(new CartonQuery(location, sku, statuses, 0, 20, null));
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("a carton by its LPN in lower case with spaces: what is in it, where, and the line it came on")
    void detailByLowerCaseLpn() {
        receive();

        StockPackageBalance.Detail found = lookup.byLpn(" " + lpns.getFirst().toLowerCase(Locale.ROOT) + " ");
        StockPackageBalance detail = found.carton();

        assertThat(detail.lpn()).isEqualTo(lpns.getFirst());
        assertThat(detail.sku()).isEqualTo(sku);
        assertThat(detail.unitOfMeasure()).isEqualTo("CARTON");
        assertThat(detail.nominalQty()).isEqualTo(1000);
        assertThat(detail.receivedQty()).isEqualTo(1000);
        assertThat(detail.onHand()).isEqualTo(1000);
        assertThat(detail.reserved()).isZero();
        assertThat(detail.status()).isEqualTo(StockPackageStatus.IN_STOCK);
        assertThat(detail.locationCode()).isEqualTo(RECEIVING);
        assertThat(detail.goodsReceiptLineId()).isEqualTo(line);
        assertThat(detail.closedAt()).isNull();
        assertThat(found.contents()).singleElement().satisfies(content -> {
            assertThat(content.onHand()).isEqualTo(1000);
            assertThat(content.status()).isEqualTo(StockStatus.INBOUND);
            assertThat(content.receivedAt()).isEqualTo(RECEIVED);
        });
    }

    @Test
    @DisplayName("a carton still being received has no stock row yet: on hand 0, no contents")
    void aReceivingCartonHoldsNothingYet() {
        StockPackageBalance.Detail found = lookup.byLpn(lpns.get(1));

        assertThat(found.carton().status()).isEqualTo(StockPackageStatus.RECEIVING);
        assertThat(found.carton().receivedQty()).isEqualTo(1000);
        assertThat(found.carton().onHand()).isZero();
        assertThat(found.carton().lotNumber()).isNull();
        assertThat(found.contents()).isEmpty();
    }

    @Test
    @DisplayName("an unknown or blank LPN: STOCK_PACKAGE_NOT_FOUND")
    void anUnknownLpnIsNotFound() {
        assertThatThrownBy(() -> lookup.byLpn("LPN-19990101-999999"))
                .extracting(StockPackageLookupIntegrationTest::code).isEqualTo(ErrorCode.STOCK_PACKAGE_NOT_FOUND);
        assertThatThrownBy(() -> lookup.byLpn(" "))
                .extracting(StockPackageLookupIntegrationTest::code).isEqualTo(ErrorCode.STOCK_PACKAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("the receiving area after receipt: two whole cartons of 2000 units, nothing loose, of lot UNTRACED")
    void summaryAtTheReceivingArea() {
        receive();

        assertThat(lookup.atLocation(RECEIVING.toLowerCase(Locale.ROOT))).filteredOn(l -> l.sku().equals(sku))
                .containsExactly(new LocationLine(sku, LOT, StockStatus.INBOUND, 2, 2000, 0, 2000));
    }

    @Test
    @DisplayName("an unknown location is LOCATION_NOT_FOUND, a blank one VALIDATION_FAILED")
    void anUnknownOrBlankLocation() {
        assertThatThrownBy(() -> lookup.atLocation("HCM-NOPE99"))
                .extracting(StockPackageLookupIntegrationTest::code).isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
        assertThatThrownBy(() -> lookup.atLocation(" "))
                .extracting(StockPackageLookupIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("the list filters by SKU and by location; an unknown SKU is an empty page")
    void listFiltersBySkuAndLocation() {
        receive();
        inventory.moveStockPackages(new MoveStockPackagesCommand(lpns.getFirst(), BIN, MoveReference.MOVE_TASK,
                Identifiers.newId(), clerk));

        assertThat(list(null, sku.toLowerCase(Locale.ROOT), null).items())
                .extracting(StockPackageBalance::lpn, StockPackageBalance::locationCode, StockPackageBalance::onHand)
                .containsExactly(tuple(lpns.get(0), BIN, 1000),
                        tuple(lpns.get(1), RECEIVING, 1000));
        assertThat(list(BIN, sku, null).items()).extracting(StockPackageBalance::lpn).containsExactly(lpns.getFirst());
        assertThat(list(null, "NO-SUCH-SKU", null).totalElements()).isZero();
    }

    @Test
    @DisplayName("the list sorts and pages in SQL: by location descending, a page after the first, one past the end")
    void listSortsAndPages() {
        receive();
        inventory.moveStockPackages(new MoveStockPackagesCommand(lpns.getFirst(), BIN, MoveReference.MOVE_TASK,
                Identifiers.newId(), clerk));

        assertThat(lookup.cartons(new CartonQuery(null, sku, null, 0, 20, "locationCode,desc")).items())
                .extracting(StockPackageBalance::locationCode).containsExactly(RECEIVING, BIN);
        assertThat(lookup.cartons(new CartonQuery(null, sku, null, 1, 1, "lpn,desc"))).satisfies(page -> {
            assertThat(page.items()).extracting(StockPackageBalance::lpn).containsExactly(lpns.getFirst());
            assertThat(page.totalElements()).isEqualTo(2);
        });
        assertThat(lookup.cartons(new CartonQuery(null, sku, null, 2, 1, null))).satisfies(page -> {
            assertThat(page.items()).isEmpty();
            assertThat(page.totalElements()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("a blank status in the filter is ignored, not a server error")
    void aBlankStatusIsIgnored() {
        receive();

        assertThat(list(null, sku, Arrays.asList(StockPackageStatus.IN_STOCK, null)).items()).hasSize(2);
        assertThat(list(null, sku, Collections.singletonList(null)).items()).hasSize(2);
    }

    @Test
    @DisplayName("a VOID carton is left out of the list unless its status is asked for")
    void listHidesClosedCartonsByDefault() {
        count(new PackageCount(lpns.getFirst()));   // the second carton, left out of the count, is voided

        assertThat(list(null, sku, null).items()).extracting(StockPackageBalance::lpn, StockPackageBalance::status)
                .containsExactly(tuple(lpns.getFirst(), StockPackageStatus.RECEIVING));
        assertThat(list(null, sku, List.of(StockPackageStatus.VOID)).items()).extracting(StockPackageBalance::lpn)
                .containsExactly(lpns.get(1));
    }
}
