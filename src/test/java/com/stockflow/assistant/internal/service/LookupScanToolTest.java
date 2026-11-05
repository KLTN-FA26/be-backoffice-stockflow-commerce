package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.ScannedCodeQueries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A scanned label (design §4.2, C9): which kind of code it is taken for, in which order, and what each answers. */
class LookupScanToolTest {

    private final ScannedCodeQueries scans = mock(ScannedCodeQueries.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final LookupScanTool tool = new LookupScanTool(scans, users);

    private LookupScanTool.Answer scan(String code) {
        return tool.run(ToolArguments.parse(new ObjectMapper(), "{\"code\":\"" + code + "\"}"));
    }

    private void user(boolean mayReadItems) {
        Set<PermissionCode> permissions = mayReadItems
                ? Set.of(PermissionCode.of(ToolPermissions.INVENTORY_ITEMS, Action.READ)) : Set.of();
        when(users.current()).thenReturn(Optional.of(new CurrentUser(UUID.randomUUID(), "staff",
                Set.of(Role.WAREHOUSE_STAFF), permissions, DataScope.WAREHOUSE, Set.of("HCM"))));
    }

    private static BusinessException error(ErrorCode code) {
        return new BusinessException(code, code.name());
    }

    @Test
    @DisplayName("an LPN label is a carton, with what is left in it, whether it is still sealed, and its stock rows "
            + "with their own status: an IN_STOCK carton of goods waiting for QC is not one to pick")
    void carton() {
        when(scans.carton("lpn-20261026-000001")).thenReturn(new ScannedCodeQueries.Carton("LPN-20261026-000001",
                "EVAL-LID-90", "PCS", 50, "HCM-A01-1-B", "IN_STOCK", false, "EVAL-LOT-1", LocalDate.of(2027, 3, 1),
                26, 10, List.of(new ScannedCodeQueries.CartonContent("EVAL-LOT-1", LocalDate.of(2027, 3, 1),
                "INBOUND", 26, 10))));

        LookupScanTool.Answer answer = scan("lpn-20261026-000001");

        assertThat(answer.kind()).isEqualTo("CARTON");
        assertThat(answer.carton().sku()).isEqualTo("EVAL-LID-90");
        assertThat(answer.carton().unitsWhenFull()).isEqualTo(50);
        assertThat(answer.carton().onHand()).isEqualTo(26);
        assertThat(answer.carton().held()).isEqualTo(10);
        assertThat(answer.carton().unopened()).isFalse();
        assertThat(answer.carton().contents()).singleElement()
                .satisfies(row -> assertThat(row.stockStatus()).isEqualTo("INBOUND"));
        verify(scans, never()).atLocation(anyString());
    }

    @Test
    @DisplayName("an LPN that is not found, or stands in another warehouse, is answered by that error, never by a "
            + "barcode search")
    void lpnErrorsAreTheAnswer() {
        when(scans.carton("LPN-20261026-000009")).thenThrow(error(ErrorCode.OUT_OF_DATA_SCOPE));
        user(true);

        assertThatThrownBy(() -> scan("LPN-20261026-000009")).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        verify(scans, never()).skusWithBarcode(anyString());
    }

    @Test
    @DisplayName("a location code gives what the bin holds per SKU, lot and status; an empty bin is an empty list")
    void location() {
        when(scans.atLocation("HCM-A01-1-B")).thenReturn(List.of(new ScannedCodeQueries.LocationLine("EVAL-LID-90",
                "EVAL-LOT-1", LocalDate.of(2027, 3, 1), "AVAILABLE", 2, 100, 1, 26, 4, 130)));
        when(scans.atLocation("HCM-A01-1-C")).thenReturn(List.of());

        LookupScanTool.Answer answer = scan("HCM-A01-1-B");

        assertThat(answer.kind()).isEqualTo("LOCATION");
        assertThat(answer.location()).singleElement().satisfies(row -> {
            assertThat(row.wholeCartons()).isEqualTo(2);
            assertThat(row.openedCartonUnits()).isEqualTo(26);
            assertThat(row.totalUnits()).isEqualTo(130);
        });
        assertThat(scan("HCM-A01-1-C").location()).isEmpty();
        verify(scans, never()).carton(anyString());
    }

    @Test
    @DisplayName("a busy area is cut at the most lines one answer carries, with the total")
    void busyArea() {
        List<ScannedCodeQueries.LocationLine> lines = java.util.stream.IntStream.range(0, 130)
                .mapToObj(i -> new ScannedCodeQueries.LocationLine("EVAL-SKU-" + i, null, null, "INBOUND", 0, 0, 0,
                        0, 1, 1)).toList();
        when(scans.atLocation("HCM-QC01")).thenReturn(lines);

        LookupScanTool.Answer answer = scan("HCM-QC01");

        assertThat(answer.location()).hasSize(LookupScanTool.MAX_LOCATION_ROWS);
        assertThat(answer.locationTotal()).isEqualTo(130);
        assertThat(answer.locationTruncated()).isTrue();
    }

    @Test
    @DisplayName("a code that is no location and no carton is a barcode, for a user who may search items: exact "
            + "matches only, as the inventory api returns them")
    void barcode() {
        user(true);
        when(scans.carton("8934567890123")).thenThrow(error(ErrorCode.STOCK_PACKAGE_NOT_FOUND));
        when(scans.skusWithBarcode("8934567890123")).thenReturn(List.of("EVAL-LID-90"));

        LookupScanTool.Answer answer = scan("8934567890123");

        assertThat(answer.kind()).isEqualTo("BARCODE");
        assertThat(answer.skus()).containsExactly("EVAL-LID-90");
        verify(scans, never()).atLocation(anyString());
    }

    @Test
    @DisplayName("without the item list's permission no barcode is searched, and the NOT_FOUND says which permission "
            + "would read it")
    void barcodeNeedsTheItemList() {
        user(false);
        when(scans.carton("8934567890123")).thenThrow(error(ErrorCode.STOCK_PACKAGE_NOT_FOUND));

        assertThatThrownBy(() -> scan("8934567890123")).isInstanceOf(NotFoundException.class)
                .hasMessageContaining("inventory-inventory-items:READ");
        verify(scans, never()).skusWithBarcode(anyString());
    }

    @Test
    @DisplayName("a location of another warehouse is refused only when nothing else matches: a barcode with a dash is "
            + "still found, and an unknown code of that shape is refused as the screen refuses it")
    void anotherWarehouse() {
        user(true);
        when(scans.atLocation("HN-A01-1-B")).thenThrow(error(ErrorCode.OUT_OF_DATA_SCOPE));
        when(scans.carton("HN-A01-1-B")).thenThrow(error(ErrorCode.STOCK_PACKAGE_NOT_FOUND));
        when(scans.atLocation("BC-77")).thenThrow(error(ErrorCode.OUT_OF_DATA_SCOPE));
        when(scans.carton("BC-77")).thenThrow(error(ErrorCode.STOCK_PACKAGE_NOT_FOUND));
        when(scans.skusWithBarcode("BC-77")).thenReturn(List.of("EVAL-CUP-12"));

        assertThatThrownBy(() -> scan("HN-A01-1-B")).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        assertThat(scan("BC-77").skus()).containsExactly("EVAL-CUP-12");
    }

    @Test
    @DisplayName("a location error other than not found or out of scope is passed on, not hidden behind a barcode")
    void otherErrorsPassOn() {
        when(scans.atLocation("HCM-")).thenThrow(error(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> scan("HCM-")).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(scans, never()).carton(anyString());
    }

    @Test
    @DisplayName("a code that is nothing at all is NOT_FOUND")
    void nothing() {
        user(true);
        when(scans.carton("NOSUCHLABEL")).thenThrow(error(ErrorCode.STOCK_PACKAGE_NOT_FOUND));
        when(scans.skusWithBarcode("NOSUCHLABEL")).thenReturn(List.of());

        assertThatThrownBy(() -> scan("NOSUCHLABEL")).isInstanceOf(NotFoundException.class);
    }
}
