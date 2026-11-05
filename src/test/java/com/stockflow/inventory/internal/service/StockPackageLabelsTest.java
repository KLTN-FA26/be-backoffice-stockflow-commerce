package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.repository.UomConversionRepository;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static com.stockflow.inventory.internal.service.InventoryFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SCRUM-473: which cartons a label print chooses, and what each label says. */
class StockPackageLabelsTest {

    private static final UUID RECEIPT_LINE = UUID.randomUUID();
    private static final UUID CARTON_UNIT = UUID.randomUUID();

    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final UomConversionRepository units = mock(UomConversionRepository.class);
    private final StockPackageLabels labels = new StockPackageLabels(stockPackages, units);

    private static StockPackage carton(String lpn, String location, StockPackageStatus status) {
        Instant closedAt = status.isClosed() ? NOW : null;
        Integer counted = status == StockPackageStatus.RECEIVING ? null : 24;
        return new StockPackage(new StockPackageId(UUID.randomUUID()), lpn, UUID.randomUUID(), CARTON_UNIT, 24, counted,
                new LocationId(location), status, status == StockPackageStatus.IN_STOCK, RECEIPT_LINE, closedAt, 0L);
    }

    private void unitOfCartons() {
        when(units.findAll(anyCollection())).thenReturn(List.of(new UomConversion(CARTON_UNIT, "CUP-12OZ", "CARTON", 24,
                false, null, null, null, null, false, true, 0)));
    }

    private static CurrentUser clerkOf(String prefix) {
        return new CurrentUser(UUID.randomUUID(), "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of(prefix), Set.of(UUID.randomUUID()));
    }

    @Test
    @DisplayName("choosing nothing is STOCK_PACKAGE_LABELS_EMPTY")
    void nothingChosen() {
        assertThat(codeOf(() -> labels.sheetFor(null, null))).isEqualTo(ErrorCode.STOCK_PACKAGE_LABELS_EMPTY);
        assertThat(codeOf(() -> labels.sheetFor(null, List.of()))).isEqualTo(ErrorCode.STOCK_PACKAGE_LABELS_EMPTY);
    }

    @Test
    @DisplayName("a receipt line prints its live cartons only, in LPN order, with counted and nominal quantity and unit")
    void receiptLineSheet() {
        unitOfCartons();
        when(stockPackages.findByReceiptLines(List.of(RECEIPT_LINE))).thenReturn(List.of(
                carton("LPN-0002", "HCM-RCV01", StockPackageStatus.IN_STOCK),
                carton("LPN-0003", "HCM-RCV01", StockPackageStatus.VOID),
                carton("LPN-0001", "HCM-RCV01", StockPackageStatus.RECEIVING)));

        List<StockPackageLabel> sheet = labels.sheetFor(RECEIPT_LINE, null);

        assertThat(sheet).extracting(StockPackageLabel::lpn).containsExactly("LPN-0001", "LPN-0002");
        assertThat(sheet.get(0).receivedQty()).isNull();
        assertThat(sheet.get(1).receivedQty()).isEqualTo(24);
        assertThat(sheet.get(1).nominalQty()).isEqualTo(24);
        assertThat(sheet.get(1).unit()).isEqualTo("CARTON");
        assertThat(sheet.get(1).sku()).isEqualTo("CUP-12OZ");
    }

    @Test
    @DisplayName("a receipt line with no live carton is STOCK_PACKAGE_LABELS_EMPTY")
    void lineWithoutLiveCartons() {
        when(stockPackages.findByReceiptLines(List.of(RECEIPT_LINE))).thenReturn(List.of(
                carton("LPN-0003", "HCM-RCV01", StockPackageStatus.VOID)));

        assertThat(codeOf(() -> labels.sheetFor(RECEIPT_LINE, null))).isEqualTo(ErrorCode.STOCK_PACKAGE_LABELS_EMPTY);
    }

    @Test
    @DisplayName("more than 500 cartons on a line is STOCK_PACKAGE_LABELS_TOO_MANY")
    void tooManyOnALine() {
        List<StockPackage> many = new ArrayList<>();
        for (int i = 0; i < StockPackageLabels.MAX_LABELS + 1; i++) {
            many.add(carton("LPN-%05d".formatted(i), "HCM-RCV01", StockPackageStatus.IN_STOCK));
        }
        when(stockPackages.findByReceiptLines(List.of(RECEIPT_LINE))).thenReturn(many);

        assertThat(codeOf(() -> labels.sheetFor(RECEIPT_LINE, null))).isEqualTo(ErrorCode.STOCK_PACKAGE_LABELS_TOO_MANY);
    }

    @Test
    @DisplayName("exactly 500 is allowed")
    void fiveHundredIsAllowed() {
        unitOfCartons();
        List<StockPackage> many = new ArrayList<>();
        for (int i = 0; i < StockPackageLabels.MAX_LABELS; i++) {
            many.add(carton("LPN-%05d".formatted(i), "HCM-RCV01", StockPackageStatus.IN_STOCK));
        }
        when(stockPackages.findByReceiptLines(List.of(RECEIPT_LINE))).thenReturn(many);

        assertThat(labels.sheetFor(RECEIPT_LINE, null)).hasSize(StockPackageLabels.MAX_LABELS);
    }

    @Test
    @DisplayName("501 LPNs named are refused before anything is read")
    void tooManyNamed() {
        List<String> lpns = new ArrayList<>();
        for (int i = 0; i < StockPackageLabels.MAX_LABELS + 1; i++) {
            lpns.add("LPN-%05d".formatted(i));
        }

        assertThat(codeOf(() -> labels.sheetFor(null, lpns))).isEqualTo(ErrorCode.STOCK_PACKAGE_LABELS_TOO_MANY);
        verify(stockPackages, never()).findByLpns(anyCollection());
    }

    @Test
    @DisplayName("a reprint accepts LPNs in any case and with spaces, and counts a repeated LPN once")
    void reprintNormalisesLpns() {
        unitOfCartons();
        when(stockPackages.findByLpns(List.of("LPN-0001"))).thenReturn(List.of(
                carton("LPN-0001", "HCM-A01-1-A", StockPackageStatus.IN_STOCK)));

        List<StockPackageLabel> sheet = labels.sheetFor(null, List.of(" lpn-0001 ", "LPN-0001"));

        assertThat(sheet).extracting(StockPackageLabel::lpn).containsExactly("LPN-0001");
    }

    @Test
    @DisplayName("an unknown LPN is STOCK_PACKAGE_NOT_FOUND")
    void unknownLpn() {
        when(stockPackages.findByLpns(List.of("LPN-0009"))).thenReturn(List.of());

        assertThat(codeOf(() -> labels.sheetFor(null, List.of("LPN-0009")))).isEqualTo(ErrorCode.STOCK_PACKAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("a consumed, lost or void carton has no label to print")
    void closedCartonRefused() {
        when(stockPackages.findByLpns(List.of("LPN-0004"))).thenReturn(List.of(
                carton("LPN-0004", "HCM-A01-1-A", StockPackageStatus.CONSUMED)));

        assertThat(codeOf(() -> labels.sheetFor(null, List.of("LPN-0004"))))
                .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
    }

    @Test
    @DisplayName("a carton in another warehouse is OUT_OF_DATA_SCOPE, said before its status, which would leak what became of it")
    void otherWarehouseIsOutOfScopeBeforeStatus() {
        when(stockPackages.findByLpns(List.of("LPN-0005"))).thenReturn(List.of(
                carton("LPN-0005", "HN-A01-1-A", StockPackageStatus.CONSUMED)));

        WithCurrentUser.run(clerkOf("HCM"), () ->
                assertThat(codeOf(() -> labels.sheetFor(null, List.of("LPN-0005"))))
                        .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
    }

    @Test
    @DisplayName("a receipt line's cartons in another warehouse are OUT_OF_DATA_SCOPE for a clerk")
    void receiptLineOfAnotherWarehouse() {
        when(stockPackages.findByReceiptLines(List.of(RECEIPT_LINE))).thenReturn(List.of(
                carton("LPN-0006", "HN-RCV01", StockPackageStatus.IN_STOCK)));

        WithCurrentUser.run(clerkOf("HCM"), () ->
                assertThat(codeOf(() -> labels.sheetFor(RECEIPT_LINE, null))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
    }

    @Test
    @DisplayName("a clerk may print the cartons of their own warehouse")
    void ownWarehouse() {
        unitOfCartons();
        when(stockPackages.findByLpns(List.of("LPN-0007"))).thenReturn(List.of(
                carton("LPN-0007", "HCM-A01-1-A", StockPackageStatus.IN_STOCK)));

        WithCurrentUser.run(clerkOf("HCM"), () ->
                assertThat(labels.sheetFor(null, List.of("LPN-0007"))).hasSize(1));
    }
}
