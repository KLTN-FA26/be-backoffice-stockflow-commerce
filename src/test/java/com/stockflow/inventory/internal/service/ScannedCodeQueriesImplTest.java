package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.repository.InventoryItemSearch;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What a scanned code is read as, before the screen's own reads and their warehouse checks. */
class ScannedCodeQueriesImplTest {

    private final StockPackageLookup lookup = mock(StockPackageLookup.class);
    private final InventoryItemSearch items = mock(InventoryItemSearch.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final ScannedCodeQueriesImpl queries = new ScannedCodeQueriesImpl(lookup, items, warehouses);

    @Test
    @DisplayName("a code whose prefix is no warehouse's is no location: not found, never refused as another "
            + "warehouse's, and the screen's read is not asked")
    void notALocation() {
        when(warehouses.isWarehousePrefix("EVAL")).thenReturn(false);

        assertThatThrownBy(() -> queries.atLocation("EVAL-CUP-12OZ")).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.LOCATION_NOT_FOUND));
        verify(lookup, never()).atLocation(anyString());
    }

    @Test
    @DisplayName("a code of a real warehouse goes to the screen's read, which checks the warehouse")
    void aLocation() {
        when(warehouses.isWarehousePrefix("HN")).thenReturn(true);
        when(lookup.atLocation("hn-a01-2-03")).thenThrow(new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE, "no"));

        assertThatThrownBy(() -> queries.atLocation("hn-a01-2-03")).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
    }

    @Test
    @DisplayName("a barcode is matched exactly, never by the item list's substring search; blank is none")
    void barcode() {
        when(items.skusWithBarcode("1001")).thenReturn(List.of("EVAL-CUP-12OZ"));

        assertThat(queries.skusWithBarcode(" 1001 ")).containsExactly("EVAL-CUP-12OZ");
        assertThat(queries.skusWithBarcode("  ")).isEmpty();
    }
}
