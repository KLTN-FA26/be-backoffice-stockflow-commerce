package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.internal.domain.Recounts;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** {@link CycleCounts}: the caller's warehouses (BR-SEC-002) narrow the list before anything is read. */
@Service
@Transactional(readOnly = true)
class CycleCountService implements CycleCounts {

    private static final Set<String> STATUSES = Set.of("PLANNED", "IN_PROGRESS", "COMPLETED", "CANCELLED");

    private final Recounts recounts;

    CycleCountService(Recounts recounts) {
        this.recounts = recounts;
    }

    @Override
    public PageResponse<Recounts.Recount> list(String status, UUID warehouse, boolean showExpected, int page,
                                               int size) {
        Pageable paging = Pages.of(page, size);
        String wanted = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
        if (wanted != null && !STATUSES.contains(wanted)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER,
                    "status must be one of " + String.join(", ", new java.util.TreeSet<>(STATUSES)));
        }
        Set<UUID> warehouses;
        if (warehouse != null) {
            WarehouseScope.requireWarehouse(warehouse);
            warehouses = Set.of(warehouse);
        } else {
            warehouses = WarehouseScope.restriction().map(allowed -> Set.copyOf(allowed.ids())).orElse(null);
        }
        long total = recounts.count(wanted, warehouses);
        var items = total == 0 ? java.util.List.<Recounts.Recount>of()
                : recounts.find(wanted, warehouses, paging.getOffset(), paging.getPageSize());
        if (!showExpected) {
            items = items.stream().map(CycleCountService::blind).toList();
        }
        return PageResponse.of(items, paging.getPageNumber(), paging.getPageSize(), total);
    }

    @Override
    @Transactional
    public Recounts.Recount cancel(UUID id, String reason, String actor) {
        Recounts.Recount count = recounts.findForUpdate(id).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "No cycle count with id " + id));
        WarehouseScope.requireWarehouse(count.warehouseId());
        switch (count.status()) {
            case "CANCELLED" -> {
                return count;
            }
            case "COMPLETED" -> throw new BusinessException(ErrorCode.CYCLE_COUNT_NOT_OPEN,
                    "Cycle count %s is already COMPLETED".formatted(count.number()));
            default -> { }
        }
        recounts.cancel(id, reason.strip(), actor);
        return recounts.findForUpdate(id).orElseThrow();
    }

    /**
     * A blind count's counter does not see what the books say (V20260928005000: is_blind), nor the note: a short
     * pick's note carries what the picker found, which would anchor the count just as the book quantity does.
     */
    private static Recounts.Recount blind(Recounts.Recount count) {
        if (!count.blind()) {
            return count;
        }
        return new Recounts.Recount(count.id(), count.number(), count.warehouseId(), count.triggerType(),
                count.status(), true, count.plannedDate(), null, count.createdAt(),
                count.lines().stream().map(line -> new Recounts.Line(line.id(), line.locationCode(), line.sku(),
                        line.lotNumber(), null, line.countedQty())).toList());
    }
}
