package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.warehouse.internal.domain.LabelSelection;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import com.stockflow.warehouse.internal.repository.LocationLabelRepository;
import com.stockflow.warehouse.internal.repository.LocationLabelRepository.LabelRow;
import com.stockflow.warehouse.internal.repository.LocationLookupRepository;
import com.stockflow.warehouse.internal.repository.LocationLookupRepository.WarehouseHeader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-request limit (SCRUM-97), with the rows stubbed: 2,001 real bins would have to pass the map's
 * geometry triggers, and the limit is arithmetic on the row count, not something the database decides.
 */
class LocationLabelServiceTest {

    private static final UUID WAREHOUSE = UUID.randomUUID();

    private final LocationLabelRepository rows = mock(LocationLabelRepository.class);
    private final LocationLookupRepository lookup = mock(LocationLookupRepository.class);
    private final LocationLabelService service = new LocationLabelServiceImpl(rows, lookup);

    private void binRows(int count) {
        when(lookup.findWarehouseHeader(WAREHOUSE)).thenReturn(Optional.of(
                new WarehouseHeader(WAREHOUSE, "HCM", "Kho Hồ Chí Minh", "address", WarehouseStatus.ACTIVE)));
        List<LabelRow> bins = IntStream.range(0, count)
                .mapToObj(i -> new LabelRow("HCM-A01-1-B" + i, StorageLocationKind.BIN, StorageClass.NORMAL,
                        LocationStatus.ACTIVE, LocationStatus.ACTIVE, "A01", 1, "B" + i, null))
                .toList();
        when(rows.findRows(any(), any())).thenReturn(bins);
    }

    @Test
    void moreThanTheLimitIs422() {
        binRows(2001);
        assertThatThrownBy(() -> service.sheetFor(WAREHOUSE, new LabelSelection.WholeWarehouse()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.LOCATION_LABELS_TOO_MANY))
                .hasMessageContaining("2001").hasMessageContaining("2000");
    }

    @Test
    void exactlyTheLimitPasses() {
        binRows(2000);
        assertThat(service.sheetFor(WAREHOUSE, new LabelSelection.WholeWarehouse()).labels()).hasSize(2000);
    }
}
