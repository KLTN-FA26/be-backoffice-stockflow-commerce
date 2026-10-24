package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.identity.api.AssignableUser;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.internal.domain.CountSlot;
import com.stockflow.inventory.internal.domain.CycleCount;
import com.stockflow.inventory.internal.domain.CycleCount.CountLineDraft;
import com.stockflow.inventory.internal.domain.CycleCountLine;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.CycleCountStatus;
import com.stockflow.inventory.internal.domain.CycleCountTrigger;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.WarehouseDirectory;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link CycleCounts}: reading counts, and the planner's and manager's side of them - planning, assigning,
 * cancelling and sending lines for a recount. The counter's side (counting a line, adding a found one, submitting)
 * is {@link CycleCountCountingService}, delegated to: it holds the stock-side lock order and the carton rules, which
 * have nothing to do with choosing what to count. The caller's warehouses (BR-SEC-002) are checked before anything
 * is read or written.
 */
@Service
@Transactional(readOnly = true)
class CycleCountService implements CycleCounts {

    private static final Logger log = LoggerFactory.getLogger(CycleCountService.class);

    /** Who a count may be handed to: the same people fulfillment hands pick tasks to. */
    private static final Set<String> ASSIGNEE_ROLES =
            Set.of(Role.WAREHOUSE_STAFF.authority(), Role.WAREHOUSE_MANAGER.authority());

    private static final String REJECTED = "REJECTED";
    private static final String PENDING = "PENDING_APPROVAL";

    private final CycleCountRepository counts;
    private final CycleCountViewMapper views;
    private final CycleCountCountingService counting;
    private final WarehouseDirectory warehouseDirectory;
    private final StockPackageRepository stockPackages;
    private final WarehouseService warehouses;
    private final IdentityService identities;
    private final Clock clock;
    /** Not final: a test lowers it to reach the cap without planning 500 lines. */
    private int maxLines;

    CycleCountService(CycleCountRepository counts, CycleCountViewMapper views, CycleCountCountingService counting,
                      WarehouseDirectory warehouseDirectory, StockPackageRepository stockPackages,
                      WarehouseService warehouses, IdentityService identities, Clock clock,
                      @Value("${stockflow.inventory.cycle-count-max-lines:500}") int maxLines) {
        this.counts = counts;
        this.views = views;
        this.counting = counting;
        this.warehouseDirectory = warehouseDirectory;
        this.stockPackages = stockPackages;
        this.warehouses = warehouses;
        this.identities = identities;
        this.clock = clock;
        this.maxLines = maxLines;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public PageResponse<CycleCountSummary> list(String status, UUID warehouse, boolean showExpected, int page,
                                                int size) {
        Pageable paging = Pages.of(page, size);
        CycleCountStatus wanted = parse(status);
        Set<UUID> scoped;
        if (warehouse != null) {
            WarehouseScope.requireWarehouse(warehouse);
            scoped = Set.of(warehouse);
        } else {
            scoped = WarehouseScope.restriction().map(allowed -> Set.copyOf(allowed.ids())).orElse(null);
        }
        long total = counts.count(wanted, scoped);
        List<CycleCountSummary> found = total == 0 ? List.of()
                : counts.findHeaders(wanted, scoped, paging.getOffset(), paging.getPageSize()).stream()
                        .map(header -> CycleCountViewMapper.summary(header, showExpected)).toList();
        return PageResponse.of(found, paging.getPageNumber(), paging.getPageSize(), total);
    }

    @Override
    public CycleCountView get(UUID id, boolean showExpected) {
        CycleCount count = counts.findById(id).orElseThrow(() -> notFound(id));
        WarehouseScope.requireWarehouse(count.warehouseId());
        return views.of(count, showExpected);
    }

    @Override
    public List<AssignableUser> assignees(UUID warehouseId) {
        WarehouseScope.requireWarehouse(warehouseId);
        prefixOf(warehouseId);
        return identities.assignableUsers(ASSIGNEE_ROLES, warehouseId);
    }

    // ------------------------------------------------------------------ planning

    /**
     * The order is the spec's (§4): resolve the scope to codes and prefixes, read the stocked slots, narrow a
     * SKU-only scope to bins, check the cap, then claim. The cap comes before the claim so an over-wide scope is
     * refused without taking a thousand advisory locks first; the claim comes last so the locks are held for as
     * short a time as the transaction allows.
     */
    @Override
    @Transactional
    public PlannedCount plan(PlanCycleCountCommand command, boolean showExpected) {
        UUID warehouseId = Objects.requireNonNull(command.warehouseId(), "warehouseId");
        WarehouseScope.requireWarehouse(warehouseId);
        String prefix = prefixOf(warehouseId);
        if (command.assignedUserId() != null) {
            requireAssignable(command.assignedUserId(), warehouseId);
        }
        List<String> codes = command.locationCodes().stream().map(CycleCountInput::upper).filter(Objects::nonNull)
                .distinct().toList();
        List<String> foreign = codes.stream().filter(code -> !prefix.equals(WarehouseScope.prefixOf(code))).toList();
        if (!foreign.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Not locations of warehouse %s: %s".formatted(prefix, String.join(", ", foreign)));
        }
        Set<String> shelves = new TreeSet<>();
        command.shelfCodes().stream().map(CycleCountInput::upper).filter(Objects::nonNull).forEach(shelves::add);
        for (UUID zone : new LinkedHashSet<>(command.zoneIds())) {
            // Empty for a zone of another warehouse as for an unknown one: its shelf codes would otherwise be read
            // against this warehouse's prefix and count whatever shelves here happen to share them.
            List<String> ofZone = warehouses.shelfCodesOf(warehouseId, zone);
            if (ofZone.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Zone %s has no shelves in warehouse %s"
                        .formatted(zone, prefix), List.of(new FieldError("scope.zoneIds",
                        "Zone %s has no shelves in warehouse %s".formatted(zone, prefix), "UnknownZone")));
            }
            shelves.addAll(ofZone);
        }
        // With the trailing dash: shelf A01 must not match the bins of a shelf A011.
        List<String> prefixes = shelves.stream().map(shelf -> prefix + "-" + shelf + "-").toList();
        List<String> skus = command.skus().stream().map(sku -> CycleCountInput.sku("scope.skus", sku).code())
                .distinct().toList();

        // From what was resolved, not what was sent: blank codes must not leave an empty location scope that
        // stockIn would read as the whole warehouse.
        boolean byLocation = !codes.isEmpty() || !shelves.isEmpty();
        if (!byLocation && skus.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name at least one zone, shelf, bin or SKU");
        }
        List<CountLineDraft> drafts = counts.stockIn(prefix, codes, prefixes, skus);
        if (!byLocation) {
            drafts = onlyBins(drafts);
        }
        if (drafts.size() > maxLines) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_TOO_LARGE,
                    "The scope holds %d slots; a count takes at most %d".formatted(drafts.size(), maxLines));
        }

        Map<CountSlot, String> busy = counts.claim(drafts.stream().map(CountLineDraft::slot).toList());
        List<CountLineDraft> free = drafts.stream().filter(draft -> !busy.containsKey(draft.slot())).toList();
        if (free.isEmpty() && !busy.isEmpty()) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_EMPTY_SCOPE, "Every slot in scope is on another count: "
                    + busy.values().stream().distinct().sorted().collect(Collectors.joining(", ")));
        }
        LocalDate day = BusinessCalendar.date(clock.instant());
        CycleCount planned = counts.save(CycleCount.plan(counts.nextNumber(day), warehouseId, CycleCountTrigger.MANUAL,
                command.blind(), command.assignedUserId(),
                command.plannedDate() == null ? day : command.plannedDate(), command.note(), free));
        log.info("Cycle count {} planned in {}: {} line(s), {} skipped", planned.number(), prefix,
                planned.lines().size(), busy.size());
        return new PlannedCount(views.of(planned, showExpected), skipped(busy));
    }

    /** SKUs alone mean bins: stock in a receiving or QC area is still being received or inspected, not stored. */
    private List<CountLineDraft> onlyBins(List<CountLineDraft> drafts) {
        if (drafts.isEmpty()) {
            return drafts;
        }
        Set<String> bins = warehouses.findLocations(drafts.stream().map(draft -> draft.slot().location().code())
                        .collect(Collectors.toSet())).stream()
                .filter(location -> location.kind() == StorageLocationView.Kind.BIN)
                .map(StorageLocationView::locationCode).collect(Collectors.toSet());
        return drafts.stream().filter(draft -> bins.contains(draft.slot().location().code())).toList();
    }

    private List<SkippedSlot> skipped(Map<CountSlot, String> busy) {
        if (busy.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> lpns = counts.lpnsOf(busy.keySet().stream().map(CountSlot::stockPackageId)
                .filter(Objects::nonNull).distinct().toList());
        return busy.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<CountSlot, String> e) -> e.getKey().lockKey()))
                .map(e -> {
                    CountSlot slot = e.getKey();
                    StockPackageId pkg = slot.stockPackageId();
                    return new SkippedSlot(slot.location().code(), slot.sku().code(), slot.lotNumber(),
                            pkg == null ? null : lpns.get(pkg.value()), e.getValue());
                }).toList();
    }

    @Override
    @Transactional
    public CycleCountView assign(UUID id, UUID userId, boolean showExpected) {
        CycleCount count = lock(id);
        requireAssignable(userId, count.warehouseId());
        count.assign(userId);
        return views.of(counts.save(count), showExpected);
    }

    // ------------------------------------------------------------------ counting, delegated

    @Override
    @Transactional
    public CycleCountView count(UUID id, UUID lineId, Integer countedQty, Boolean present, UUID actor,
                                boolean mayDecide) {
        return counting.count(id, lineId, countedQty, present, actor, mayDecide);
    }

    @Override
    @Transactional
    public CycleCountView addLine(UUID id, AddCountLineCommand command) {
        return counting.addLine(id, command);
    }

    @Override
    @Transactional
    public CycleCountView submit(UUID id, UUID actor, boolean mayDecide) {
        return counting.submit(id, actor, mayDecide);
    }

    // ------------------------------------------------------------------ deciding

    /**
     * Only a line whose variance the manager rejected, with no share of it still pending, is recounted: a pending one
     * is still to be decided, and an approved one is already in the books - recounting it would be a new count, which
     * is what planning is for. Checked before any line is flagged, so the refusal names the line and its state. The
     * slots are then claimed like a plan's; a decided adjustment no longer holds them. A carton emptied since
     * (CONSUMED) has nothing left to count: {@code CYCLE_COUNT_STALE}, naming the line. A line already sent for
     * recount is refused by the aggregate: its doubt went to that recount, and the line's own rejected adjustment
     * would otherwise let it be recounted again after that recount settled the slot.
     *
     * <p>The recount keeps a {@code SHORT_PICK} trigger: only an open SHORT_PICK count freezes its slot, and rejecting
     * the shortage already lifted the pending-shortage freeze, so a MANUAL recount would let the allocator promise
     * the units the picker could not find while they are being counted again.</p>
     */
    @Override
    @Transactional
    public CycleCountView recount(UUID id, List<UUID> lineIds, String note, UUID actor) {
        CycleCount count = lock(id);
        Set<UUID> wanted = new LinkedHashSet<>(lineIds == null ? List.of() : lineIds);
        if (wanted.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name at least one line to recount");
        }
        Set<UUID> onCount = count.lines().stream().map(CycleCountLine::id).collect(Collectors.toSet());
        List<UUID> unknown = wanted.stream().filter(line -> !onCount.contains(line)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Not lines of count %s: %s".formatted(count.number(),
                    unknown.stream().map(UUID::toString).collect(Collectors.joining(", "))));
        }
        if (count.status() != CycleCountStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_NOT_OPEN,
                    "Only a submitted count is sent for recount; %s is %s".formatted(count.number(), count.status()));
        }
        Map<UUID, List<CycleCountRepository.LineAdjustment>> adjustments = counts.adjustmentsOf(wanted);
        List<UUID> notRejected = wanted.stream().filter(line -> adjustments.getOrDefault(line, List.of()).stream()
                .noneMatch(adjustment -> REJECTED.equals(adjustment.status()))).toList();
        if (!notRejected.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only a line whose variance was rejected is recounted: "
                    + notRejected.stream().map(UUID::toString).collect(Collectors.joining(", ")));
        }
        List<UUID> stillPending = wanted.stream().filter(line -> adjustments.get(line).stream()
                .anyMatch(adjustment -> PENDING.equals(adjustment.status()))).toList();
        if (!stillPending.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Part of the variance still waits for approval: "
                    + stillPending.stream().map(UUID::toString).collect(Collectors.joining(", ")));
        }
        // An emptied carton has nothing left to recount: said here, naming the line, rather than by the count of it.
        for (CycleCountLine line : count.lines()) {
            if (wanted.contains(line.id()) && line.slot().isCarton()) {
                stockPackages.findById(line.slot().stockPackageId())
                        .ifPresent(carton -> CycleCountCountingService.requireCountable(carton, line));
            }
        }
        List<CountSlot> slots = count.markRecount(wanted);
        Map<CountSlot, String> busy = counts.claim(slots);
        if (!busy.isEmpty()) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_SLOT_BUSY, "Already on another count: "
                    + busy.entrySet().stream().map(e -> e.getKey().lockKey() + " (" + e.getValue() + ")")
                    .sorted().collect(Collectors.joining(", ")));
        }
        LocalDate day = BusinessCalendar.date(clock.instant());
        List<CountLineDraft> drafts = slots.stream()
                .map(slot -> new CountLineDraft(slot, counts.bookQuantity(slot))).toList();
        String text = "Recount of " + count.number() + (note == null || note.isBlank() ? "" : ": " + note.strip());
        counts.save(count);
        // Blind whatever the first count was: the counter must not be told what the doubted figure was.
        CycleCountTrigger trigger = count.trigger() == CycleCountTrigger.SHORT_PICK
                ? CycleCountTrigger.SHORT_PICK : CycleCountTrigger.MANUAL;
        CycleCount opened = counts.save(CycleCount.plan(counts.nextNumber(day), count.warehouseId(), trigger, true,
                null, day, text, drafts));
        log.info("Cycle count {}: {} line(s) sent for recount by {} as {}", count.number(), slots.size(), actor,
                opened.number());
        return views.of(opened, true);
    }

    @Override
    @Transactional
    public CycleCountView cancel(UUID id, String reason, String actor) {
        CycleCount count = lock(id);
        if (count.status() == CycleCountStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_NOT_OPEN,
                    "Cycle count %s is already COMPLETED".formatted(count.number()));
        }
        if (count.cancel(reason.strip(), clock.instant())) {
            counts.save(count);
            log.info("Cycle count {} cancelled by {}", count.number(), actor);
        }
        // Whoever may cancel may also decide, so the view is the full one.
        return views.of(count, true);
    }

    // ------------------------------------------------------------------ helpers

    /** The count row locked first, as every write to a count starts (lock order: count, SKU, carton, rows). */
    private CycleCount lock(UUID id) {
        CycleCount count = counts.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        WarehouseScope.requireWarehouse(count.warehouseId());
        return count;
    }

    private String prefixOf(UUID warehouseId) {
        return warehouseDirectory.prefixOf(warehouseId).orElseThrow(() -> new BusinessException(
                ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse with id " + warehouseId));
    }

    /** BR-SEC-002, as fulfillment's task assignment: an active staff member or manager of that warehouse. */
    private void requireAssignable(UUID userId, UUID warehouseId) {
        boolean eligible = userId != null && identities.assignableUsers(ASSIGNEE_ROLES, warehouseId).stream()
                .anyMatch(user -> user.userId().equals(userId));
        if (!eligible) {
            String message = "Assignee must be an active warehouse staff member or warehouse manager "
                    + "assigned to this warehouse";
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message,
                    List.of(new FieldError("assignedUserId", message, "NotAssignable")));
        }
    }

    private static BusinessException notFound(UUID id) {
        return new BusinessException(ErrorCode.NOT_FOUND, "No cycle count with id " + id);
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
}
