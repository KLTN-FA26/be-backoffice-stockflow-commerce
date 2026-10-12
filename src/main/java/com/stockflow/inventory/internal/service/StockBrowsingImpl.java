package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.domain.WarehouseDirectory;
import com.stockflow.inventory.internal.repository.StockBrowseSearch;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class StockBrowsingImpl implements StockBrowsing {

    private static final SortWhitelist STOCK_SORT =
            SortWhitelist.of("locationCode", "sku", "lotNumber", "expiryDate", "onHand", "receivedAt")
                    .withDefault("locationCode", Sort.Direction.ASC);

    private final StockBrowseSearch search;
    private final WarehouseDirectory warehouses;
    private final Clock clock;

    StockBrowsingImpl(StockBrowseSearch search, WarehouseDirectory warehouses, Clock clock) {
        this.search = search;
        this.warehouses = warehouses;
        this.clock = clock;
    }

    @Override
    public PageResponse<Reservation> reservations(ReservationQuery query) {
        String warehouse = query.warehouse() == null || query.warehouse().isBlank() ? null
                : query.warehouse().trim().toUpperCase(Locale.ROOT);
        if (warehouse != null) {
            WarehouseScope.requirePrefix(warehouse);
        }
        var criteria = new StockBrowseSearch.ReservationCriteria(query.orderId(), query.sku(), query.statuses(),
                warehouse);
        return Pages.toResponse(search.reservations(criteria, Pages.of(query.page(), query.size()))
                .map(StockBrowsingImpl::toView));
    }

    @Override
    public Reservation reservation(UUID reservationId) {
        var row = search.reservation(reservationId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESERVATION_NOT_FOUND, "No stock reservation " + reservationId));
        WarehouseScope.requireLocation(row.locationCode());
        return toView(row);
    }

    @Override
    public PageResponse<StockRow> warehouseStock(UUID warehouseId, StockQuery query) {
        String prefix = warehouses.prefixOf(warehouseId).orElseThrow(() ->
                new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + warehouseId));
        WarehouseScope.requireWarehouse(warehouseId);
        String location = query.location() == null || query.location().isBlank() ? null : query.location().trim();
        if (location != null && !prefix.equals(WarehouseScope.prefixOf(location))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "location must be a location code of warehouse " + prefix);
        }
        var criteria = new StockBrowseSearch.StockCriteria(prefix, location, query.sku(), query.lotNumber(),
                query.statuses(), query.includeEmpty());
        LocalDate today = BusinessCalendar.date(clock.instant());
        return Pages.toResponse(search.stock(criteria, Pages.of(query.page(), query.size(), STOCK_SORT.parse(query.sort())))
                .map(row -> new StockRow(row.stockItemId(), row.sku(), row.locationCode(), row.lotNumber(),
                        row.expiryDate(), row.onHand(), row.reserved(), available(row, today), row.status(),
                        row.receivedAt())));
    }

    private static int available(StockBrowseSearch.StockRow row, LocalDate today) {
        boolean sellable = row.status() == StockStatus.AVAILABLE
                && (row.expiryDate() == null || !row.expiryDate().isBefore(today));
        return sellable ? Math.max(0, row.onHand() - row.reserved()) : 0;
    }

    private static Reservation toView(StockBrowseSearch.ReservationRow r) {
        return new Reservation(r.reservationId(), r.orderId(), r.stockItemId(), r.sku(), r.locationCode(),
                r.lotNumber(), r.quantity(), r.status(), r.reservedAt(), r.expiresAt(), r.releaseReason(),
                r.closedAt());
    }
}
