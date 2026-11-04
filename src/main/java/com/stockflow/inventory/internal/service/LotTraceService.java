package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.internal.domain.LotTrace;
import com.stockflow.inventory.internal.domain.WarehouseDirectory;
import com.stockflow.inventory.internal.repository.LotTraceSearch;
import com.stockflow.inventory.internal.repository.SlotsUnderCount;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link LotTraces}. The caller's warehouses (BR-SEC-002) become the report's warehouse filter: a warehouse-bound
 * user who names no warehouse is narrowed to theirs, and one who names a warehouse outside them is refused before
 * anything is read.
 */
@Service
@Transactional(readOnly = true)
class LotTraceService implements LotTraces {

    private final LotTraceSearch search;
    private final SlotsUnderCount underCount;
    private final WarehouseDirectory warehouses;
    private final java.time.Clock clock;

    LotTraceService(LotTraceSearch search, SlotsUnderCount underCount, WarehouseDirectory warehouses,
                    java.time.Clock clock) {
        this.search = search;
        this.underCount = underCount;
        this.warehouses = warehouses;
        this.clock = clock;
    }

    @Override
    public PageResponse<LotTrace.Summary> lots(Query query) {
        Pageable page = Pages.of(query.page(), query.size());
        Set<String> scope = warehousesFor(query.warehouse());
        LotTraceSearch.Filter filter = new LotTraceSearch.Filter(
                query.sku() == null || query.sku().isBlank() ? null : new Sku(query.sku()).code(),
                query.lotNumber() == null || query.lotNumber().isBlank() ? null : query.lotNumber().strip(),
                scope, startOf(query.from(), query.to()), endOf(query.to()), today());
        long total = search.countLots(filter);
        return PageResponse.of(total == 0 ? java.util.List.of() : search.lots(filter, page.getOffset(), page.getPageSize()),
                page.getPageNumber(), page.getPageSize(), total);
    }

    @Override
    public LotTrace trace(UUID lotId, UUID warehouse, BookVisibility visibility) {
        Set<String> scope = warehousesFor(warehouse);
        LotTrace.Summary lot = search.lot(lotId, scope, today())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No lot " + lotId));
        List<LotTrace.Balance> balances = search.balances(lotId, scope);
        if (visibility.hidesCountedSlots()) {
            Set<String> held = underCount.locationsUnderCount(lotId);
            balances = balances.stream().map(b -> held.contains(b.locationCode()) ? b.hidden() : b).toList();
        }
        return new LotTrace(lot, balances, search.holds(lotId, scope), search.orders(lotId, scope));
    }

    @Override
    public PageResponse<LotTrace.Movement> movements(UUID lotId, UUID warehouse, LocalDate from, LocalDate to,
                                                     int page, int size, BookVisibility visibility) {
        Pageable pageable = Pages.of(page, size);
        Set<String> scope = warehousesFor(warehouse);
        search.lot(lotId, scope, today()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No lot " + lotId));
        Instant start = startOf(from, to);
        Instant end = endOf(to);
        boolean hide = visibility.hidesCountedSlots();
        long total = search.countMovements(lotId, scope, start, end, hide);
        return PageResponse.of(total == 0 ? java.util.List.of()
                        : search.movements(lotId, scope, start, end, pageable.getOffset(), pageable.getPageSize(), hide),
                pageable.getPageNumber(), pageable.getPageSize(), total);
    }

    /** The warehouse asked for (checked against the caller's), else the caller's own, else every warehouse. */
    private Set<String> warehousesFor(UUID warehouse) {
        if (warehouse != null) {
            WarehouseScope.requireWarehouse(warehouse);
            return Set.of(warehouses.prefixOf(warehouse).orElseThrow(() ->
                    new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + warehouse)));
        }
        return WarehouseScope.restriction().map(allowed -> allowed.prefixes().isEmpty()
                // Bound to warehouses yet assigned none: nothing to see, not everything.
                ? Set.of("")
                : Set.copyOf(allowed.prefixes())).orElse(Set.of());
    }

    private LocalDate today() {
        return BusinessCalendar.date(clock.instant());
    }

    private static Instant startOf(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "to is before from");
        }
        return from == null ? null : from.atStartOfDay(BusinessCalendar.ZONE).toInstant();
    }

    private static Instant endOf(LocalDate to) {
        return to == null ? null : to.plusDays(1).atStartOfDay(BusinessCalendar.ZONE).toInstant();
    }
}
