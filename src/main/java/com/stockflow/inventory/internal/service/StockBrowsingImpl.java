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
import com.stockflow.inventory.internal.repository.SlotsUnderCount;
import com.stockflow.inventory.internal.repository.StockBrowseSearch;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class StockBrowsingImpl implements StockBrowsing {

    private static final SortWhitelist STOCK_SORT =
            SortWhitelist.of("locationCode", "sku", "lotNumber", "expiryDate", "onHand", "receivedAt")
                    .withDefault("locationCode", Sort.Direction.ASC);

    private final StockBrowseSearch search;
    private final SlotsUnderCount underCount;
    private final WarehouseDirectory warehouses;
    private final Clock clock;

    StockBrowsingImpl(StockBrowseSearch search, SlotsUnderCount underCount, WarehouseDirectory warehouses,
                      Clock clock) {
        this.search = search;
        this.underCount = underCount;
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
        if (row.locationCode() != null) {
            WarehouseScope.requireLocation(row.locationCode());
        } else if (WarehouseScope.restriction().isPresent()) {
            // A SKU reservation sits in no warehouse (SCRUM-152), so it is in none of the caller's.
            throw new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE, "The reservation is not in your warehouses");
        }
        return toView(row);
    }

    @Override
    public PageResponse<StockRow> warehouseStock(UUID warehouseId, StockQuery query, BookVisibility visibility) {
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
        // Ordering by on hand would rank a hidden quantity against the visible ones: not for a blind reader.
        String sort = visibility.hidesCountedSlots() && query.sort() != null
                && query.sort().strip().toLowerCase(Locale.ROOT).startsWith("onhand") ? null : query.sort();
        var page = search.stock(criteria, Pages.of(query.page(), query.size(), STOCK_SORT.parse(sort)));
        Set<UUID> held = visibility.hidesCountedSlots()
                ? underCount.stockItemsUnderCount(page.getContent().stream().map(StockBrowseSearch.StockRow::stockItemId)
                        .toList())
                : Set.of();
        return Pages.toResponse(page.map(row -> {
            var view = new StockRow(row.stockItemId(), row.sku(), row.locationCode(), row.lotNumber(),
                    row.expiryDate(), row.onHand(), row.reserved(), available(row, today), row.status(),
                    row.receivedAt(), false);
            return held.contains(row.stockItemId()) ? view.hidden() : view;
        }));
    }

    @Override
    public Set<UUID> underCount(Collection<UUID> stockItemIds) {
        return underCount.stockItemsUnderCount(stockItemIds);
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
