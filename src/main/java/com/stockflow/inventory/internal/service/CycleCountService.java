package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.internal.domain.CycleCount;
import com.stockflow.inventory.internal.domain.CycleCountLine;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.CycleCountStatus;
import com.stockflow.inventory.internal.domain.StockPackageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** {@link CycleCounts}: the caller's warehouses (BR-SEC-002) narrow the list before anything is read. */
@Service
@Transactional(readOnly = true)
class CycleCountService implements CycleCounts {

    private static final Logger log = LoggerFactory.getLogger(CycleCountService.class);

    private final CycleCountRepository counts;
    private final Clock clock;

    CycleCountService(CycleCountRepository counts, Clock clock) {
        this.counts = counts;
        this.clock = clock;
    }

    @Override
    public PageResponse<CycleCountView> list(String status, UUID warehouse, boolean showExpected, int page,
                                             int size) {
        Pageable paging = Pages.of(page, size);
        CycleCountStatus wanted = parse(status);
        Set<UUID> warehouses;
        if (warehouse != null) {
            WarehouseScope.requireWarehouse(warehouse);
            warehouses = Set.of(warehouse);
        } else {
            warehouses = WarehouseScope.restriction().map(allowed -> Set.copyOf(allowed.ids())).orElse(null);
        }
        long total = counts.count(wanted, warehouses);
        List<CycleCount> found = total == 0 ? List.of()
                : counts.find(wanted, warehouses, paging.getOffset(), paging.getPageSize());
        return PageResponse.of(views(found, showExpected), paging.getPageNumber(), paging.getPageSize(), total);
    }

    @Override
    @Transactional
    public CycleCountView cancel(UUID id, String reason, String actor) {
        CycleCount count = counts.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "No cycle count with id " + id));
        WarehouseScope.requireWarehouse(count.warehouseId());
        if (count.status() == CycleCountStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_NOT_OPEN,
                    "Cycle count %s is already COMPLETED".formatted(count.number()));
        }
        if (count.cancel(reason.strip(), clock.instant())) {
            counts.save(count);
            log.info("Cycle count {} cancelled by {}", count.number(), actor);
        }
        // Whoever may cancel may also decide, so the view is the full one.
        return views(List.of(count), true).get(0);
    }

    private static CycleCountStatus parse(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return CycleCountStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "status must be one of "
                    + String.join(", ", EnumSet.allOf(CycleCountStatus.class).stream().map(Enum::name).sorted().toList()));
        }
    }

    /**
     * The views of these counts, the LPNs and adjustments of all their lines read in one query each. A blind count
     * hides what the books say from a counter (V20260928005000: is_blind): the expected quantity, the note (a short
     * pick's note carries what the picker found, which would anchor the count just as the book quantity does) and
     * the adjustments, which state the variance.
     */
    private List<CycleCountView> views(List<CycleCount> found, boolean showExpected) {
        if (found.isEmpty()) {
            return List.of();
        }
        List<CycleCountLine> lines = found.stream().flatMap(count -> count.lines().stream()).toList();
        Map<UUID, String> lpns = counts.lpnsOf(lines.stream().map(line -> line.slot().stockPackageId())
                .filter(java.util.Objects::nonNull).distinct().toList());
        Collection<UUID> shown = found.stream().filter(count -> showExpected || !count.blind())
                .flatMap(count -> count.lines().stream()).map(CycleCountLine::id).toList();
        Map<UUID, List<CycleCountRepository.LineAdjustment>> adjustments = counts.adjustmentsOf(shown);
        return found.stream().map(count -> view(count, showExpected || !count.blind(), lpns, adjustments)).toList();
    }

    private static CycleCountView view(CycleCount count, boolean reveal, Map<UUID, String> lpns,
                                       Map<UUID, List<CycleCountRepository.LineAdjustment>> adjustments) {
        return new CycleCountView(count.id(), count.number(), count.warehouseId(), count.trigger().name(),
                count.status().name(), count.blind(), count.assignedUserId(), count.plannedDate(), count.startedAt(),
                count.completedAt(), reveal ? count.note() : null, count.createdAt(),
                count.lines().stream().map(line -> {
                    StockPackageId pkg = line.slot().stockPackageId();
                    return new CycleCountView.Line(line.id(), line.slot().location().code(), line.slot().sku().code(),
                            line.slot().lotNumber(), pkg == null ? null : pkg.value(),
                            pkg == null ? null : lpns.get(pkg.value()), reveal ? line.expectedQty() : null,
                            line.countedQty(), line.countedBy(), line.countedAt(), line.recountRequired(),
                            reveal ? adjustments.getOrDefault(line.id(), List.of()) : List.of());
                }).toList());
    }
}
