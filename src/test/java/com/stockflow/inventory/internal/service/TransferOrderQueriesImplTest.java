package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.TransferDeliveryMode;
import com.stockflow.inventory.api.TransferOrderQueries;
import com.stockflow.inventory.internal.domain.TransferStatus;
import com.stockflow.inventory.internal.domain.WarehouseDirectory;
import com.stockflow.inventory.internal.repository.TransferOrderSearch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link TransferOrderQueries} reads the transfer screen's own list and names the warehouses by their prefix. */
class TransferOrderQueriesImplTest {

    private final TransferOrders transfers = mock(TransferOrders.class);
    private final WarehouseDirectory warehouses = mock(WarehouseDirectory.class);
    private final TransferOrderSearch search = mock(TransferOrderSearch.class);
    private final TransferOrderQueriesImpl queries = new TransferOrderQueriesImpl(transfers, search, warehouses);

    @Test
    @DisplayName("the api's statuses are the domain's, and the open ones leave out only the finished")
    void vocabulary() {
        assertThat(TransferOrderQueries.STATUSES).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(TransferStatus.values()).map(Enum::name).toList());
        assertThat(TransferOrderQueries.STATUSES).containsAll(TransferOrderQueries.OPEN_STATUSES);
        assertThat(TransferOrderQueries.STATUSES.stream()
                .filter(status -> !TransferOrderQueries.OPEN_STATUSES.contains(status)))
                .containsExactly("COMPLETED", "CLOSED", "CANCELLED");
    }

    @Test
    @DisplayName("rows name both warehouses by prefix, each warehouse looked up once a page")
    void rows() {
        UUID hcm = UUID.randomUUID();
        UUID hn = UUID.randomUUID();
        Instant created = Instant.parse("2026-11-01T02:00:00Z");
        when(transfers.list(List.of(TransferStatus.IN_TRANSIT), null, null, "TO-2026", 0, 5)).thenReturn(
                PageResponse.of(List.of(
                        new TransferOrders.Row(UUID.randomUUID(), "TO-20261101-0001", hcm, hn,
                                TransferStatus.IN_TRANSIT, LocalDate.of(2026, 11, 3), created, created,
                                TransferDeliveryMode.PARCEL, null),
                        new TransferOrders.Row(UUID.randomUUID(), "TO-20261101-0002", hn, hcm,
                                TransferStatus.IN_TRANSIT, null, created, null, null, null)), 0, 5, 2));
        when(warehouses.prefixOf(hcm)).thenReturn(Optional.of("HCM"));
        when(warehouses.prefixOf(hn)).thenReturn(Optional.of("HN"));

        var page = queries.transfers(List.of("IN_TRANSIT"), " TO-2026 ", false, 5);

        assertThat(page.items()).extracting(TransferOrderQueries.TransferOrderSummary::fromWarehouseCode)
                .containsExactly("HCM", "HN");
        assertThat(page.items().getFirst().deliveryMode()).isEqualTo("PARCEL");
        assertThat(page.items().get(1).deliveryMode()).isNull();
        verify(warehouses, times(1)).prefixOf(hcm);
        verify(warehouses, times(1)).prefixOf(hn);
    }

    @Test
    @DisplayName("expected-first reads the screen's search, the earliest expected date first")
    void expectedFirst() {
        UUID hcm = UUID.randomUUID();
        UUID hn = UUID.randomUUID();
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        when(search.search(any(), pageable.capture())).thenReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(new TransferOrderSearch.Row(UUID.randomUUID(), "TO-1", hcm, hn, TransferStatus.IN_TRANSIT,
                        LocalDate.of(2026, 11, 1), Instant.now(), Instant.now(), null, null))));
        when(warehouses.prefixOf(hcm)).thenReturn(Optional.of("HCM"));
        when(warehouses.prefixOf(hn)).thenReturn(Optional.of("HN"));

        var page = queries.transfers(TransferOrderQueries.OPEN_STATUSES, null, true, 50);

        assertThat(page.items()).singleElement().satisfies(row -> assertThat(row.toWarehouseCode()).isEqualTo("HN"));
        var order = pageable.getValue().getSort().getOrderFor("expectedDate");
        assertThat(order).isNotNull();
        assertThat(order.isAscending()).isTrue();
        assertThat(order.getNullHandling()).as("refused on a criteria query; PostgreSQL puts NULL last anyway")
                .isEqualTo(org.springframework.data.domain.Sort.NullHandling.NATIVE);
        verify(transfers, never()).list(anyList(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a status outside the list or a limit out of range is VALIDATION_FAILED, before anything is read")
    void refusals() {
        assertThatThrownBy(() -> queries.transfers(List.of("SHIPPED"), null, false, 5))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> queries.transfers(List.of(), null, true, TransferOrderQueries.MAX_TRANSFERS + 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(transfers, never()).list(anyList(), any(), any(), any(), anyInt(), anyInt());
    }
}
