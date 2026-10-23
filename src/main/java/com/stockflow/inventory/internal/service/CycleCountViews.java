package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.internal.domain.CycleCount;
import com.stockflow.inventory.internal.domain.CycleCountLine;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.StockPackageId;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns counts into {@link CycleCountView}s, applying the blind rule. One place for it, because every answer of
 * {@link CycleCounts} - the list, one count, and what each counting step returns - must hide the same three things,
 * and a second copy of the rule is the one that would forget one of them.
 */
@Component
class CycleCountViews {

    private final CycleCountRepository counts;

    CycleCountViews(CycleCountRepository counts) {
        this.counts = counts;
    }

    CycleCountView of(CycleCount count, boolean showExpected) {
        return of(List.of(count), showExpected).get(0);
    }

    /**
     * The views of these counts, the LPNs and adjustments of all their lines read in one query each. A blind count
     * hides what the books say from a counter (V20260928005000: is_blind): the expected quantity, the note (a short
     * pick's note carries what the picker found, which would anchor the count just as the book quantity does) and
     * the adjustments, which state the variance. The adjustments of a hidden count are not even read.
     */
    List<CycleCountView> of(List<CycleCount> found, boolean showExpected) {
        if (found.isEmpty()) {
            return List.of();
        }
        List<CycleCountLine> lines = found.stream().flatMap(count -> count.lines().stream()).toList();
        Map<UUID, String> lpns = counts.lpnsOf(lines.stream().map(line -> line.slot().stockPackageId())
                .filter(Objects::nonNull).distinct().toList());
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
