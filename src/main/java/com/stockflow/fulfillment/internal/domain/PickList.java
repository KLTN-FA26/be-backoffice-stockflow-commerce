package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>A pick task and its lines</b> — the aggregate behind scan-to-confirm picking (SCRUM-267/274) and
 * the put-back of a cancelled order (docs 07 BR-05).
 *
 * <p>The rules it keeps (kltn-docs 07, whole cartons since team plan 4C-2):</p>
 * <ul>
 *   <li>one line per live hold of the order's lines, in the task's warehouse - and a hold is one whole carton, so a
 *       line is one carton, named by its LPN (BR-04) - a print line too: its goods were printed outside the system
 *       and received into stock like any other (BR-06);</li>
 *   <li>a line is confirmed by scanning its location, then its carton's own LPN, in any case (§4.2 step 5, BR-10): a
 *       SKU, a barcode or another carton's LPN is refused, and no quantity is entered - the carton is taken whole
 *       (BR-04);</li>
 *   <li>a carton that cannot be taken is reported short, whole, with a reason (§4.4 step 9, BR-09);</li>
 *   <li>a STAGING task stages its lines instead of picking them (team plan 4C-5, §4.2 step 5): the carton goes to the
 *       staging area, still held for the order, and is deducted only when the whole task moves to dispatch (§4.3 step
 *       8, BR-11);</li>
 *   <li>the task completes only when every line is picked - or staged (§4.2 step 6);</li>
 *   <li>holds added to the order after its lines were planned - a top-up (§4.3 step 7, BR-07) - get lines in the same
 *       task, after the last stop;</li>
 *   <li>the carton of a cancelled task's picked or staged line goes back onto an active bin of the same warehouse
 *       whole, confirmed by scanning its LPN (§4.5 step 14, BR-05).</li>
 * </ul>
 *
 * <p>Repeats are answers, not errors: a line confirmed again with the same scan, or put back again,
 * returns what was done — a scanner retrying after a dropped connection must not be told it failed.</p>
 *
 * <p>The task's own status moves on the JPA entity ({@code PickJpaEntity.start/complete/cancel}); this
 * class is handed that status and decides about the lines. Plain Java: the stock deduction a
 * confirmation causes is the service's job, in the same transaction.</p>
 */
public final class PickList {

    /** An order line as the pick list needs it: whose holds to pick. */
    public record OrderLine(UUID lineId, String sku, List<UUID> reservationIds) {
        public OrderLine {
            reservationIds = reservationIds == null ? List.of() : List.copyOf(reservationIds);
        }
    }

    /**
     * One hold of the order, with the warehouse its location belongs to, and the carton it is in (SCRUM-473). A null
     * {@code lpn} is a hold on loose stock, which no line is planned for any more.
     */
    public record Hold(UUID reservationId, String sku, String locationCode, UUID warehouseId, String lotNumber,
                       int quantity, boolean live, String lpn, boolean wholeCarton) { }

    /**
     * Where a put-back wants to go, as the warehouse map describes it; null fields for an unknown code.
     *
     * @param quarantine a QUARANTINE area: where a carton the check station removed as damaged goes (4C-6)
     */
    public record PutBackTarget(String locationCode, UUID warehouseId, boolean bin, boolean usable,
                                boolean quarantine) {
        public PutBackTarget(String locationCode, UUID warehouseId, boolean bin, boolean usable) {
            this(locationCode, warehouseId, bin, usable, false);
        }
    }

    /** A confirmation's outcome: the line, and whether it had been picked by an earlier identical scan. */
    public record Confirmation(PickLine line, boolean repeated) { }

    /**
     * A put-back's outcome: the line, whether it had been put back already, whether it came from the staging area -
     * never deducted, so the carton is moved back rather than booked back (07 §4.5 step 14) - and whether it goes into
     * quarantine, a carton the check station removed as damaged (08 §4.2 step 4, 4C-6).
     */
    public record PutBack(PickLine line, boolean repeated, boolean fromStaging, boolean toQuarantine) {
        public PutBack(PickLine line, boolean repeated) {
            this(line, repeated, false, false);
        }

        public PutBack(PickLine line, boolean repeated, boolean fromStaging) {
            this(line, repeated, fromStaging, false);
        }
    }

    private final UUID taskId;
    private final UUID warehouseId;
    private final PickStatus status;
    private final boolean needsPutBack;
    /** The staging area a confirmed line's carton goes to (team plan 4C-5); null when lines are picked to dispatch. */
    private final String stagingLocationCode;
    private final List<PickLine> lines;

    /** A task that picks to dispatch. */
    public PickList(UUID taskId, UUID warehouseId, PickStatus status, boolean needsPutBack, List<PickLine> lines) {
        this(taskId, warehouseId, status, needsPutBack, null, lines);
    }

    /**
     * @param stagingLocationCode where a confirmed line's carton goes, for a STAGING task not moved to dispatch yet; null
     *                            when a confirmation deducts the carton (a DISPATCH task, or one moved to dispatch since,
     *                            whose top-up lines go straight to dispatch, 07 §4.3 step 7)
     */
    public PickList(UUID taskId, UUID warehouseId, PickStatus status, boolean needsPutBack, String stagingLocationCode,
                    List<PickLine> lines) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.status = Objects.requireNonNull(status, "status");
        this.needsPutBack = needsPutBack;
        this.stagingLocationCode = stagingLocationCode;
        this.lines = new ArrayList<>(lines == null ? List.of() : lines);
    }

    // ------------------------------------------------------------------ planning

    /**
     * Plans a line for every live hold of the order that has none yet: at creation, lazily at picking-start for a
     * task opened before lines existed, and for a top-up (07 §4.3 step 7, BR-07) - holds the order gained since, whose
     * lines join the same task after its last stop, keeping its destination. New stops are ordered by location so the
     * picker walks the aisles once (07 step 3, simplest route). A hold that had a line once, in any status, never gets
     * another: a superseded or reallocated line's hold is the business of whoever replaced it, and planning twice
     * is harmless.
     *
     * <p>A STAGED or PICKED task with lines takes a top-up, and the caller reopens it to PICKING; one with no lines
     * (picked before lines existed) is left as it is - packing deducts what such a task never did (G4-5). A
     * cancelled task takes nothing.</p>
     *
     * @return the lines added; empty when every live hold has a line already, or the task takes none
     * @throws BusinessException {@code FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE} when a new hold of a stock line sits
     *         in another warehouse than the task's; the message names the SKUs. {@code PICK_HOLD_NOT_IN_CARTON} when a
     *         live hold is on no carton: allocation takes whole cartons only (4C-2) and V20261111000110 boxed every hold
     *         before, so such a hold was written around both, and a line for it could be confirmed by no LPN
     */
    public List<PickLine> plan(List<OrderLine> orderLines, Map<UUID, Hold> holds, Supplier<UUID> ids) {
        boolean opening = status == PickStatus.PENDING || status == PickStatus.PICKING;
        boolean topUp = (status == PickStatus.STAGED || status == PickStatus.PICKED) && !lines.isEmpty();
        if (!opening && !topUp) {
            return List.of();
        }
        Set<UUID> planned = new java.util.HashSet<>();
        lines.stream().map(PickLine::reservationId).filter(Objects::nonNull).forEach(planned::add);
        record Stop(UUID orderLineId, Hold hold) { }
        List<Stop> stops = new ArrayList<>();
        Set<String> elsewhere = new TreeSet<>();
        for (OrderLine line : orderLines) {
            for (UUID reservationId : line.reservationIds()) {
                Hold hold = holds.get(reservationId);
                if (hold == null || !hold.live() || planned.contains(reservationId)) {
                    continue;       // consumed, released or expired, or a line has it: nothing new to take off a shelf
                }
                if (!warehouseId.equals(hold.warehouseId())) {
                    elsewhere.add(hold.sku());
                    continue;
                }
                stops.add(new Stop(line.lineId(), hold));
            }
        }
        if (!elsewhere.isEmpty()) {
            throw new BusinessException(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE,
                    "Task %s is picked in warehouse %s but stock for %s is held in another warehouse"
                            .formatted(taskId, warehouseId, String.join(", ", elsewhere)));
        }
        requireCartons(stops.stream().map(Stop::hold).toList());
        stops.sort(Comparator.comparing((Stop stop) -> stop.hold().locationCode())
                .thenComparing(stop -> stop.hold().sku())
                .thenComparing(stop -> stop.hold().reservationId()));
        List<PickLine> added = new ArrayList<>();
        int sequence = lines.stream().mapToInt(PickLine::sequenceNo).max().orElse(0) + 1;
        for (Stop stop : stops) {
            Hold hold = stop.hold();
            added.add(PickLine.open(ids.get(), stop.orderLineId(), hold.reservationId(), hold.sku(),
                    hold.locationCode(), hold.lotNumber(), sequence++, hold.quantity(), hold.lpn(), hold.wholeCarton()));
        }
        lines.addAll(added);
        return added;
    }

    // ------------------------------------------------------------------ confirming

    /**
     * The picker scanned a stop: its location, then its carton's LPN (07 §4.2 step 5). On success the line takes the
     * whole carton - no quantity is entered (BR-04) - and, by the task's destination:
     * <ul>
     *   <li>DISPATCH: PICKED; the caller deducts its hold in the same transaction and records it with
     *       {@link PickLine#recordConsumed};</li>
     *   <li>STAGING: STAGED; the caller moves the carton to the staging area in the same transaction, still held for
     *       the order, nothing deducted (team plan 4C-5).</li>
     * </ul>
     * Only the line's own LPN is accepted, in any case (BR-10): the hold is deducted from (or moves with) that carton,
     * and the SKU, the barcode or another carton of the same SKU would let the picker take a carton the books do not
     * say left.
     *
     * @throws BusinessException {@code NOT_FOUND} for a line of another task; {@code PICK_TASK_STATE_CONFLICT}
     *         unless the task is PICKING; {@code PICK_LINE_NOT_OPEN}, also for a line with no carton to scan;
     *         {@code PICK_LOCATION_MISMATCH}; {@code PICK_ITEM_MISMATCH} for anything but the line's LPN
     */
    public Confirmation confirm(UUID lineId, String scannedLocation, String scannedLpn, UUID actor, Instant now) {
        PickLine line = requireLine(lineId);
        if ((line.status() == PickLineStatus.PICKED || line.status() == PickLineStatus.STAGED)
                && sameCode(scannedLocation, line.locationCode()) && lpnMatches(line, scannedLpn)) {
            return new Confirmation(line, true);
        }
        if (status != PickStatus.PICKING) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Lines are confirmed while the task is PICKING; it is " + status);
        }
        if (line.status() != PickLineStatus.OPEN || line.lpn() == null) {
            throw new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN, line.lpn() == null
                    ? "The line names no carton to pick" : "The line is " + line.status());
        }
        if (!sameCode(scannedLocation, line.locationCode())) {
            throw new BusinessException(ErrorCode.PICK_LOCATION_MISMATCH,
                    "This line is picked from " + line.locationCode());
        }
        if (!lpnMatches(line, scannedLpn)) {
            throw new BusinessException(ErrorCode.PICK_ITEM_MISMATCH, "This line picks carton " + line.lpn());
        }
        if (stagingLocationCode != null) {
            line.stage(scannedLocation.trim(), line.lpn(), stagingLocationCode, actor, now);
        } else {
            line.pick(scannedLocation.trim(), line.lpn(), actor, now);
        }
        return new Confirmation(line, false);
    }

    /**
     * Docs 07 step 6: a task closes when every line is picked - or staged, for a STAGING task. A task with no lines (an
     * order of print lines only, or one opened before lines existed) has nothing to wait for.
     *
     * @throws BusinessException {@code PICK_LINES_INCOMPLETE}, naming how many lines are still open
     */
    public void requireAllPicked() {
        // A CANCELLED line of a live task is a short line whose allocation was moved: its new lines count instead.
        long open = lines.stream().filter(line -> line.status() != PickLineStatus.PICKED
                && line.status() != PickLineStatus.STAGED && line.status() != PickLineStatus.CANCELLED).count();
        if (open > 0) {
            throw new BusinessException(ErrorCode.PICK_LINES_INCOMPLETE,
                    "%d of %d lines are not picked yet".formatted(open, lines.size()));
        }
    }

    // ------------------------------------------------------------------ the staging area (team plan 4C-5)

    /**
     * The warehouse manager moves the whole STAGED task to dispatch (07 §4.3 step 8, BR-11): every STAGED line turns
     * PICKED where its carton stands; the caller deducts each in the same transaction and records it with
     * {@link PickLine#recordConsumed}, then moves the task itself. Done once, for the whole task.
     *
     * @return the lines moved, in walking order
     * @throws BusinessException {@code PICK_TASK_STATE_CONFLICT} unless the task is STAGED
     */
    public List<PickLine> moveToDispatch() {
        if (status != PickStatus.STAGED) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "A task moves to dispatch from STAGED; it is " + status);
        }
        requireAllPicked();
        List<PickLine> moved = lines.stream().filter(line -> line.status() == PickLineStatus.STAGED)
                .sorted(Comparator.comparingInt(PickLine::sequenceNo)).toList();
        moved.forEach(PickLine::dispatchStaged);
        return moved;
    }

    // ------------------------------------------------------------------ short picks (X-SHORT)

    /**
     * The picker is at the line's bin and cannot take its carton - not there, or damaged (07 §4.4 step 9, X-SHORT): the
     * line turns SHORT of the whole carton, with the reason; nothing is picked and no stock is deducted. A carton is
     * taken whole or not at all (BR-04), so there is no count of units found. The order stops (the caller puts it
     * ON_HOLD) and the carton is counted blind (BR-09) until the warehouse manager reallocates. The same report again - same
     * bin, same reason - answers with the line as it is.
     *
     * <p>A STAGED line too (team plan 4C-5, review M2; kltn-docs 20 §4.9 step 4): its carton was moved to the staging
     * area and is found missing or damaged there - a shortage of the goods held for the order, handled as a short pick.
     * The scan is of the staging area, the task may be PICKING or STAGED, and the line turns SHORT the same way, nothing
     * deducted; a STAGED task with a SHORT line cannot move to dispatch ({@link #requireAllPicked}), and the
     * reallocation's new lines are picked to the staging area again. Without this a missing staged carton had no way
     * out but cancelling the order, or being deducted by the move to dispatch as if it had shipped.</p>
     *
     * @throws BusinessException {@code NOT_FOUND}; {@code PICK_TASK_STATE_CONFLICT} unless the task is PICKING (or
     *         STAGED, for a staged line); {@code PICK_LINE_NOT_OPEN} unless OPEN or STAGED, also for a line with no
     *         carton; {@code PICK_LOCATION_MISMATCH} unless the scan is where the carton should be
     */
    public Confirmation reportShort(UUID lineId, String scannedLocation, ShortPickReason reason) {
        Objects.requireNonNull(reason, "reason");
        PickLine line = requireLine(lineId);
        if (line.status() == PickLineStatus.SHORT && line.shortReason() == reason
                && sameCode(scannedLocation, expectedAt(line))) {
            return new Confirmation(line, true);
        }
        boolean staged = line.status() == PickLineStatus.STAGED;
        if (status != PickStatus.PICKING && !(staged && status == PickStatus.STAGED)) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Short picks are reported while the task is PICKING (or STAGED, for a staged carton); it is "
                            + status);
        }
        if (line.status() != PickLineStatus.OPEN && !staged || line.lpn() == null) {
            throw new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN, line.lpn() == null
                    ? "The line names no carton to report" : "The line is " + line.status());
        }
        if (!sameCode(scannedLocation, expectedAt(line))) {
            throw new BusinessException(ErrorCode.PICK_LOCATION_MISMATCH,
                    "This line's carton is at " + expectedAt(line));
        }
        line.reportShort(reason, scannedLocation.trim());
        return new Confirmation(line, false);
    }

    /**
     * Where a line's carton should be: the staging area for a line staged (or reported short there), else its bin.
     */
    public static String expectedAt(PickLine line) {
        return line.stagedLocationCode() != null ? line.stagedLocationCode() : line.locationCode();
    }

    /** Whether the line's carton was moved to the staging area by this task (still STAGED, or reported short there). */
    public static boolean stagedCarton(PickLine line) {
        return line.stagedLocationCode() != null
                && (line.status() == PickLineStatus.STAGED || line.status() == PickLineStatus.SHORT);
    }

    /**
     * The warehouse manager moved a SHORT line's allocation to other bins of the warehouse: the short line is
     * superseded (CANCELLED, its shortfall kept) and one new OPEN line per new allocation follows the last stop.
     *
     * @throws BusinessException {@code PICK_LINE_NOT_OPEN} unless the line is SHORT;
     *         {@code FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE} for an allocation outside the task's warehouse;
     *         {@code PICK_HOLD_NOT_IN_CARTON} for an allocation on no carton - nothing is changed, and the caller's
     *         transaction, which made it, rolls back (4C-4 B1)
     */
    public List<PickLine> replaceShort(UUID lineId, List<Hold> allocations, Supplier<UUID> ids) {
        PickLine line = requireLine(lineId);
        if (line.status() != PickLineStatus.SHORT) {
            throw new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN, "Only a SHORT line is reallocated; it is "
                    + line.status());
        }
        if (allocations.stream().anyMatch(hold -> !warehouseId.equals(hold.warehouseId()))) {
            throw new BusinessException(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE,
                    "Task %s is picked in warehouse %s; the new allocation is elsewhere".formatted(taskId, warehouseId));
        }
        requireCartons(allocations);
        line.supersede();
        int sequence = lines.stream().mapToInt(PickLine::sequenceNo).max().orElse(0);
        List<PickLine> added = new ArrayList<>();
        for (Hold hold : allocations) {
            added.add(PickLine.open(ids.get(), line.orderLineId(), hold.reservationId(), hold.sku(),
                    hold.locationCode(), hold.lotNumber(), ++sequence, hold.quantity(), hold.lpn(), hold.wholeCarton()));
        }
        lines.addAll(added);
        return added;
    }

    /** Whether a line is still SHORT, waiting for the warehouse manager. */
    public boolean hasShortLines() {
        return lines.stream().anyMatch(line -> line.status() == PickLineStatus.SHORT);
    }

    // ------------------------------------------------------------------ cancelling and putting back

    /**
     * The order was cancelled: lines nobody picked are cancelled with it (their holds were released by
     * the order); picked and staged lines stay as they are and wait for a put-back (07 §4.5 step 13).
     *
     * @return the lines this call cancelled
     */
    public List<PickLine> cancelOpenLines() {
        List<PickLine> cancelled = new ArrayList<>();
        for (PickLine line : lines) {
            if (line.status() == PickLineStatus.OPEN) {
                line.cancel();
                cancelled.add(line);
            }
        }
        return cancelled;
    }

    /**
     * Puts the carton a line picked back onto a bin, whole (07 BR-05, §4.5 step 14, 17 §4.5 step 15), confirmed by
     * scanning its LPN: the carton keeps its LPN and its count. Only for a task cancelled after picking started, only
     * for a PICKED or STAGED line, only onto an active bin of the task's own warehouse - stock never changes warehouse,
     * a put-back least of all. A STAGED line's carton was never deducted: it is moved back from the staging area
     * ({@link PutBack#fromStaging}). Only a carton the line took whole goes back (a line picked loose before 4C-4 took
     * part of one). Whether the carton fits the bin (BR-12, else an OVERFLOW area) is team plan 5-4's.
     *
     * @throws BusinessException {@code NOT_FOUND}; {@code PUT_BACK_NOT_REQUIRED}, also for a loose line of before
     *         4C-4 (V20261113000400 stops on any still waiting); {@code PICK_ITEM_MISMATCH} for anything but the line's
     *         LPN; {@code STOCK_PACKAGE_TAKEN_WHOLE} for a line that did not take its whole carton;
     *         {@code PUT_BACK_LOCATION_INVALID}
     */
    public PutBack putBack(UUID lineId, String scannedLpn, PutBackTarget target, UUID actor, Instant now) {
        return putBack(lineId, scannedLpn, target, false, actor, now);
    }

    /**
     * As {@link #putBack(UUID, String, PutBackTarget, UUID, Instant)}; with {@code removedDamaged}, the line's carton
     * was removed from the order at the check station as damaged (kltn-docs 08 §4.2 step 4, team plan 4C-6): it goes
     * whole into a usable QUARANTINE area of the task's warehouse, whether or not the task was cancelled since, and is
     * kept apart there from good stock.
     *
     * @throws BusinessException as the other; {@code PUT_BACK_LOCATION_INVALID} for a damaged carton's target that is
     *         not a quarantine area of the warehouse
     */
    public PutBack putBack(UUID lineId, String scannedLpn, PutBackTarget target, boolean removedDamaged, UUID actor,
                           Instant now) {
        return putBack(lineId, scannedLpn, target, removedDamaged, false, actor, now);
    }

    /**
     * As {@link #putBack(UUID, String, PutBackTarget, boolean, UUID, Instant)}; with {@code removedClosedRest}, the line's
     * carton was removed at the check station because the rest of its order was closed (17 §4.8 step 33, WF4-CLOSE-REST
     * 2026-11-13): it goes back onto an active bin of the task's warehouse although the task itself goes on - its
     * labelled cartons are still delivered.
     */
    public PutBack putBack(UUID lineId, String scannedLpn, PutBackTarget target, boolean removedDamaged,
                           boolean removedClosedRest, UUID actor, Instant now) {
        PickLine line = requireLine(lineId);
        if (line.status() == PickLineStatus.PUT_BACK && lpnMatches(line, scannedLpn)) {
            return new PutBack(line, true);
        }
        boolean staged = line.status() == PickLineStatus.STAGED;
        boolean queued = removedDamaged || removedClosedRest ? line.status() == PickLineStatus.PICKED
                : status == PickStatus.CANCELLED && needsPutBack && (line.status() == PickLineStatus.PICKED || staged);
        if (!queued || line.pickedQty() == 0 || line.lpn() == null) {
            throw new BusinessException(ErrorCode.PUT_BACK_NOT_REQUIRED,
                    "Task %s, line %s: nothing to put back".formatted(status, line.status()));
        }
        if (!lpnMatches(line, scannedLpn)) {
            throw new BusinessException(ErrorCode.PICK_ITEM_MISMATCH, "This line puts back carton " + line.lpn());
        }
        if (line.pickedQty() != line.allocatedQty()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("The line took %d of carton %s's %d; only a "
                    + "whole carton goes back onto a bin").formatted(line.pickedQty(), line.lpn(), line.allocatedQty()));
        }
        if (removedDamaged) {
            if (target == null || !target.quarantine() || !target.usable()
                    || !warehouseId.equals(target.warehouseId())) {
                throw new BusinessException(ErrorCode.PUT_BACK_LOCATION_INVALID,
                        "A damaged carton goes into an active quarantine area of this task's warehouse");
            }
        } else if (target == null || !target.bin() || !target.usable() || !warehouseId.equals(target.warehouseId())) {
            throw new BusinessException(ErrorCode.PUT_BACK_LOCATION_INVALID,
                    "Goods go back onto an active bin of this task's warehouse");
        }
        line.putBack(target.locationCode(), actor, now);
        return new PutBack(line, false, staged, removedDamaged);
    }

    // ------------------------------------------------------------------ queries

    public Optional<PickLine> line(UUID lineId) {
        return lines.stream().filter(line -> line.id().equals(lineId)).findFirst();
    }

    /** Unmodifiable: lines change through this aggregate's methods, never from outside. */
    public List<PickLine> lines() {
        return Collections.unmodifiableList(lines);
    }

    public UUID taskId() { return taskId; }
    public UUID warehouseId() { return warehouseId; }
    public PickStatus status() { return status; }
    public String stagingLocationCode() { return stagingLocationCode; }

    private PickLine requireLine(UUID lineId) {
        return line(lineId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Task %s has no pick line %s".formatted(taskId, lineId)));
    }

    /**
     * Every hold a line is planned for is a carton (07 BR-04, BR-10): a line on loose stock could be confirmed by no LPN.
     * Checked before any line changes, so a refusal leaves the list as it was.
     *
     * @throws BusinessException {@code PICK_HOLD_NOT_IN_CARTON}, naming the SKUs
     */
    private void requireCartons(List<Hold> holds) {
        Set<String> loose = new TreeSet<>();
        holds.stream().filter(hold -> hold.lpn() == null).forEach(hold -> loose.add(hold.sku()));
        if (!loose.isEmpty()) {
            throw new BusinessException(ErrorCode.PICK_HOLD_NOT_IN_CARTON,
                    "Task %s: stock for %s is held loose, in no carton; a line is picked by its carton's LPN"
                            .formatted(taskId, String.join(", ", loose)));
        }
    }

    /** The line's own LPN, in any case (07 BR-10). A line with no carton matches nothing. */
    private static boolean lpnMatches(PickLine line, String scannedLpn) {
        return line.lpn() != null && sameCode(scannedLpn, line.lpn());
    }

    /** Location codes and LPNs are stored upper case; a scanner or a keyboard may send either. */
    private static boolean sameCode(String scanned, String expected) {
        return scanned != null && scanned.trim().toUpperCase(Locale.ROOT).equals(expected.toUpperCase(Locale.ROOT));
    }
}
