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
 *   <li>the task completes only when every line is picked (§4.2 step 6);</li>
 *   <li>the carton of a cancelled task's picked line goes back onto an active bin of the same warehouse whole,
 *       confirmed by scanning its LPN (§4.5 step 14, BR-05).</li>
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

    /** Where a put-back wants to go, as the warehouse map describes it; null fields for an unknown code. */
    public record PutBackTarget(String locationCode, UUID warehouseId, boolean bin, boolean usable) { }

    /** A confirmation's outcome: the line, and whether it had been picked by an earlier identical scan. */
    public record Confirmation(PickLine line, boolean repeated) { }

    /** A put-back's outcome: the line, and whether it had been put back already. */
    public record PutBack(PickLine line, boolean repeated) { }

    private final UUID taskId;
    private final UUID warehouseId;
    private final PickStatus status;
    private final boolean needsPutBack;
    private final List<PickLine> lines;

    public PickList(UUID taskId, UUID warehouseId, PickStatus status, boolean needsPutBack, List<PickLine> lines) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.status = Objects.requireNonNull(status, "status");
        this.needsPutBack = needsPutBack;
        this.lines = new ArrayList<>(lines == null ? List.of() : lines);
    }

    // ------------------------------------------------------------------ planning

    /**
     * Plans the lines of a task that has none yet: one per live hold of each stock line, ordered by
     * location so the picker walks the aisles once (docs 07 step 3, simplest route). A task that already
     * has lines gets nothing new, which is what makes generating them at creation and again, lazily, at
     * picking-start safe.
     *
     * @return the lines added; empty when the task had lines already, is past picking, or the order has
     *         nothing to pick
     * @throws BusinessException {@code FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE} when a hold of a stock line sits
     *         in another warehouse than the task's; the message names the SKUs. {@code PICK_HOLD_NOT_IN_CARTON} when a
     *         live hold is on no carton: allocation takes whole cartons only (4C-2) and V20261111000110 boxed every hold before, so
     *         such a hold was written around both, and a line for it could be confirmed by no LPN
     */
    public List<PickLine> plan(List<OrderLine> orderLines, Map<UUID, Hold> holds, Supplier<UUID> ids) {
        // A task already picked before lines existed (or cancelled, or short) is left as it is: packing
        // deducts what such a task never did (G4-5).
        if (!lines.isEmpty() || status != PickStatus.PENDING && status != PickStatus.PICKING) {
            return List.of();
        }
        record Stop(UUID orderLineId, Hold hold) { }
        List<Stop> stops = new ArrayList<>();
        Set<String> elsewhere = new TreeSet<>();
        for (OrderLine line : orderLines) {
            for (UUID reservationId : line.reservationIds()) {
                Hold hold = holds.get(reservationId);
                if (hold == null || !hold.live()) {
                    continue;       // consumed, released or expired: nothing left to take off a shelf
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
        int sequence = 1;
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
     * The picker scanned a stop: its location, then its carton's LPN (07 §4.2 step 5). On success the line is PICKED
     * with the whole carton - no quantity is entered (BR-04); the caller deducts its hold in the same transaction and
     * records it with {@link PickLine#recordConsumed}. Only the line's own LPN is accepted, in any case (BR-10): the
     * hold is deducted from that carton, and the SKU, the barcode or another carton of the same SKU would let the
     * picker take a carton the books do not say left.
     *
     * @throws BusinessException {@code NOT_FOUND} for a line of another task; {@code PICK_TASK_STATE_CONFLICT}
     *         unless the task is PICKING; {@code PICK_LINE_NOT_OPEN}, also for a line with no carton to scan;
     *         {@code PICK_LOCATION_MISMATCH}; {@code PICK_ITEM_MISMATCH} for anything but the line's LPN
     */
    public Confirmation confirm(UUID lineId, String scannedLocation, String scannedLpn, UUID actor, Instant now) {
        PickLine line = requireLine(lineId);
        if (line.status() == PickLineStatus.PICKED && sameCode(scannedLocation, line.locationCode())
                && lpnMatches(line, scannedLpn)) {
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
        line.pick(scannedLocation.trim(), line.lpn(), actor, now);
        return new Confirmation(line, false);
    }

    /**
     * Docs 07 step 9: a task closes when every line is picked. A task with no lines (an order of print
     * lines only, or one opened before lines existed) has nothing to wait for.
     *
     * @throws BusinessException {@code PICK_LINES_INCOMPLETE}, naming how many lines are still open
     */
    public void requireAllPicked() {
        // A CANCELLED line of a live task is a short line whose allocation was moved: its new lines count instead.
        long open = lines.stream().filter(line -> line.status() != PickLineStatus.PICKED
                && line.status() != PickLineStatus.CANCELLED).count();
        if (open > 0) {
            throw new BusinessException(ErrorCode.PICK_LINES_INCOMPLETE,
                    "%d of %d lines are not picked yet".formatted(open, lines.size()));
        }
    }

    // ------------------------------------------------------------------ short picks (X-SHORT)

    /**
     * The picker is at the line's bin and cannot take its carton - not there, or damaged (07 §4.4 step 9, X-SHORT): the
     * line turns SHORT of the whole carton, with the reason; nothing is picked and no stock is deducted. A carton is
     * taken whole or not at all (BR-04), so there is no count of units found. The order stops (the caller puts it
     * ON_HOLD) and the carton is counted blind (BR-09) until the coordinator reallocates. The same report again - same
     * bin, same reason - answers with the line as it is.
     *
     * @throws BusinessException {@code NOT_FOUND}; {@code PICK_TASK_STATE_CONFLICT} unless the task is PICKING;
     *         {@code PICK_LINE_NOT_OPEN}, also for a line with no carton; {@code PICK_LOCATION_MISMATCH}
     */
    public Confirmation reportShort(UUID lineId, String scannedLocation, ShortPickReason reason) {
        Objects.requireNonNull(reason, "reason");
        PickLine line = requireLine(lineId);
        if (line.status() == PickLineStatus.SHORT && line.shortReason() == reason
                && sameCode(scannedLocation, line.locationCode())) {
            return new Confirmation(line, true);
        }
        if (status != PickStatus.PICKING) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Short picks are reported while the task is PICKING; it is " + status);
        }
        if (line.status() != PickLineStatus.OPEN || line.lpn() == null) {
            throw new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN, line.lpn() == null
                    ? "The line names no carton to report" : "The line is " + line.status());
        }
        if (!sameCode(scannedLocation, line.locationCode())) {
            throw new BusinessException(ErrorCode.PICK_LOCATION_MISMATCH,
                    "This line is picked from " + line.locationCode());
        }
        line.reportShort(reason, scannedLocation.trim());
        return new Confirmation(line, false);
    }

    /**
     * The coordinator moved a SHORT line's allocation to other bins of the warehouse: the short line is
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

    /** Whether a line is still SHORT, waiting for the coordinator. */
    public boolean hasShortLines() {
        return lines.stream().anyMatch(line -> line.status() == PickLineStatus.SHORT);
    }

    // ------------------------------------------------------------------ damage found at the bin (SCRUM-277)

    /**
     * The picker is at the line's bin and finds {@code damaged} of the allocated units damaged (docs 03 §5,
     * SCRUM-277). Checked before anything is written; the caller then has inventory move the damaged units to
     * quarantine and makes the line give way to {@link #replaceDamaged}. The same report again - the line already
     * superseded by a damage report of that many units at that bin - is answered as done.
     *
     * <p>A line is one carton and a carton is taken whole (4C-2), so the damaged units are the whole carton: anything
     * else is refused here, before inventory refuses it too. A damaged carton is better reported as a short pick with
     * reason {@link ShortPickReason#DAMAGED} (07 §4.4 step 9); this unit-level report goes with team plan 5-6.</p>
     *
     * @return true when the line was reported damaged already, exactly so
     * @throws BusinessException {@code NOT_FOUND}; {@code PICK_TASK_STATE_CONFLICT} unless the task is PICKING;
     *         {@code PICK_LINE_NOT_OPEN}; {@code PICK_LOCATION_MISMATCH}; {@code STOCK_PACKAGE_TAKEN_WHOLE} unless the
     *         line's whole allocation
     */
    public boolean requireDamageable(UUID lineId, String scannedLocation, int damaged) {
        if (alreadyReportedDamaged(lineId, scannedLocation, damaged)) {
            return true;
        }
        PickLine line = requireLine(lineId);
        if (status != PickStatus.PICKING) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Damage is reported while the task is PICKING; it is " + status);
        }
        if (line.status() != PickLineStatus.OPEN) {
            throw new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN, "The line is " + line.status());
        }
        if (line.reservationId() == null) {
            throw new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN, "The line takes no allocated stock");
        }
        if (!sameCode(scannedLocation, line.locationCode())) {
            throw new BusinessException(ErrorCode.PICK_LOCATION_MISMATCH,
                    "This line is picked from " + line.locationCode());
        }
        if (damaged != line.allocatedQty()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("The line is carton %s of %d; a carton is "
                    + "reported damaged whole, never %d of it").formatted(line.lpn(), line.allocatedQty(), damaged));
        }
        return false;
    }

    /**
     * Inventory moved the damaged carton's units to quarantine and made the order whole (SCRUM-277). The line gives way
     * - CANCELLED, its damaged units and the report kept - to the lines that pick what replaced it:
     * <ul>
     *   <li>one OPEN line per allocation that replaced the carton elsewhere, after the last stop, in its hold's carton;
     *       or</li>
     *   <li>when nothing could replace it, one SHORT line for the units, against the impaired allocation: the
     *       coordinator reallocates it like any short line (X-SHORT). It is on the loose DAMAGED row in quarantine,
     *       never in a carton.</li>
     * </ul>
     * Nothing is retained at the bin: the line was the whole carton ({@link #requireDamageable}), so there is no
     * "rest of it" to pick there any more (the branch for good units left at the bin went with 4C-4).
     *
     * @return the new lines
     * @throws BusinessException {@code PICK_LINE_NOT_OPEN} unless the line is OPEN;
     *         {@code FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE} for a replacement outside the task's warehouse
     */
    public List<PickLine> replaceDamaged(UUID lineId, int damaged, UUID reportId, List<Hold> replacements,
                                         UUID impairedReservationId, Supplier<UUID> ids) {
        PickLine line = requireLine(lineId);
        if (line.status() != PickLineStatus.OPEN) {
            throw new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN, "Only an OPEN line is reported damaged; it is "
                    + line.status());
        }
        if (replacements.stream().anyMatch(hold -> !warehouseId.equals(hold.warehouseId()))) {
            throw new BusinessException(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE,
                    "Task %s is picked in warehouse %s; the replacement is elsewhere".formatted(taskId, warehouseId));
        }
        if (damaged != line.allocatedQty() || (impairedReservationId == null) == replacements.isEmpty()) {
            throw new IllegalArgumentException("The whole carton is damaged, and either replaced or impaired, never both "
                    + "or neither");
        }
        requireCartons(replacements);
        line.supersedeForDamage(damaged, reportId);
        int sequence = lines.stream().mapToInt(PickLine::sequenceNo).max().orElse(0);
        List<PickLine> added = new ArrayList<>();
        for (Hold hold : replacements) {
            added.add(PickLine.fromDamage(ids.get(), line.orderLineId(), hold.reservationId(), hold.sku(),
                    hold.locationCode(), hold.lotNumber(), ++sequence, hold.quantity(), false, reportId, hold.lpn(),
                    hold.wholeCarton()));
        }
        if (impairedReservationId != null) {
            added.add(PickLine.fromDamage(ids.get(), line.orderLineId(), impairedReservationId, line.sku(),
                    line.locationCode(), line.lotNumber(), ++sequence, damaged, true, reportId, null, false));
        }
        lines.addAll(added);
        return added;
    }

    /**
     * Whether the line was superseded by a damage report of exactly these units at this bin: the same report sent
     * again, answered as done whatever happened to the task and its order since.
     *
     * @throws BusinessException {@code NOT_FOUND} for a line of another task
     */
    public boolean alreadyReportedDamaged(UUID lineId, String scannedLocation, int damaged) {
        PickLine line = requireLine(lineId);
        return line.status() == PickLineStatus.CANCELLED && line.damageReportId() != null
                && line.damagedQty() == damaged && sameCode(scannedLocation, line.locationCode());
    }

    // ------------------------------------------------------------------ damage found at packing (R8)

    /**
     * {@code damaged} of the units a picked line took are found damaged off the shelf - broken in the tote while the
     * task is still picked, or seen at the bench once it is complete. Checked before anything is written; the caller
     * has inventory take them back into quarantine and make the order whole, then calls
     * {@link #replaceDamagedAfterPick}. Whether those units are out of every parcel is the packing session's to say.
     *
     * <p>Always the whole carton the line picked (team plan 4C-4 review M1; kltn-docs 08: a damaged carton is removed
     * whole). Part of a carton reported damaged would leave its good units deducted, in no carton - a CONSUMED carton
     * cannot reopen (4C-2) - and, were the order cancelled, a put-back that can never succeed (it returns whole cartons
     * only): those units would be off the books with no way back. So the line's units are damaged all at once or not
     * at all, and a line damaged at packing has nothing left to put back.</p>
     *
     * @throws BusinessException {@code NOT_FOUND}; {@code PICK_TASK_STATE_CONFLICT} unless the task is PICKING or
     *         PICKED;
     *         {@code PICK_LINE_NOT_OPEN} unless the line was picked, on a hold; {@code STOCK_PACKAGE_TAKEN_WHOLE}
     *         unless {@code damaged} is everything the line picked, none of it reported damaged before
     */
    public void requireDamageableAfterPick(UUID lineId, int damaged) {
        PickLine line = requireLine(lineId);
        if (status != PickStatus.PICKED && status != PickStatus.PICKING) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Picked units are reported damaged while the task is picked or complete; it is " + status);
        }
        if (line.status() != PickLineStatus.PICKED || line.reservationId() == null) {
            throw new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN,
                    "Only a picked line of allocated stock brought units to packing; it is " + line.status());
        }
        if (damaged != line.pickedQty() || line.packingDamagedQty() != 0) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("The line brought carton %s of %d unit(s) "
                    + "to packing, %d of them reported damaged already; a carton is reported damaged whole, never %d of "
                    + "it").formatted(line.lpn(), line.pickedQty(), line.packingDamagedQty(), damaged));
        }
    }

    /**
     * Inventory took the damaged units back into quarantine and made the order whole (R8). The picked line keeps them
     * on record ({@link PickLine#packingDamagedQty}); what replaces them is picked again, after the last stop: one OPEN
     * line per allocation that replaced them, or, when nothing could, one SHORT line for them against the impaired
     * allocation, for the coordinator to reallocate (X-SHORT). The caller puts a complete task back to PICKING.
     *
     * @return the new lines
     * @throws BusinessException {@code FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE} for a replacement outside the task's
     *         warehouse
     */
    public List<PickLine> replaceDamagedAfterPick(UUID lineId, int damaged, UUID reportId, List<Hold> replacements,
                                                  UUID impairedReservationId, Supplier<UUID> ids) {
        requireDamageableAfterPick(lineId, damaged);
        PickLine line = requireLine(lineId);
        if (replacements.stream().anyMatch(hold -> !warehouseId.equals(hold.warehouseId()))) {
            throw new BusinessException(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE,
                    "Task %s is picked in warehouse %s; the replacement is elsewhere".formatted(taskId, warehouseId));
        }
        if ((impairedReservationId == null) == replacements.isEmpty()
                || replacements.stream().mapToInt(Hold::quantity).sum() != (replacements.isEmpty() ? 0 : damaged)) {
            throw new IllegalArgumentException("The damaged units are either replaced in full or impaired, never both");
        }
        requireCartons(replacements);
        line.damagedAfterPick(damaged);
        int sequence = lines.stream().mapToInt(PickLine::sequenceNo).max().orElse(0);
        List<PickLine> added = new ArrayList<>();
        for (Hold hold : replacements) {
            added.add(PickLine.fromDamage(ids.get(), line.orderLineId(), hold.reservationId(), hold.sku(),
                    hold.locationCode(), hold.lotNumber(), ++sequence, hold.quantity(), false, reportId, hold.lpn(),
                    hold.wholeCarton()));
        }
        if (impairedReservationId != null) {
            added.add(PickLine.fromDamage(ids.get(), line.orderLineId(), impairedReservationId, line.sku(),
                    line.locationCode(), line.lotNumber(), ++sequence, damaged, true, reportId, null, false));
        }
        lines.addAll(added);
        return added;
    }

    /**
     * Whether {@code reportId} is a damage report made at packing on this task: it made lines here and superseded none
     * (a report made at picking supersedes the line it was made on).
     */
    public boolean madeAfterPick(UUID reportId) {
        return !madeByDamage(reportId).isEmpty() && lines.stream()
                .noneMatch(line -> reportId.equals(line.damageReportId()) && line.damagedQty() > 0);
    }

    /** The lines a damage report made, in walking order: how the same report again is answered. */
    public List<PickLine> madeByDamage(UUID reportId) {
        return lines.stream().filter(line -> reportId.equals(line.damageReportId()) && line.damagedQty() == 0)
                .sorted(Comparator.comparingInt(PickLine::sequenceNo)).toList();
    }

    // ------------------------------------------------------------------ cancelling and putting back

    /**
     * The order was cancelled: lines nobody picked are cancelled with it (their holds were released by
     * the order); picked lines stay PICKED and wait for a put-back.
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
     * for a PICKED line, only onto an active bin of the task's own warehouse - stock never changes warehouse, a
     * put-back least of all. A line whose units were all found damaged at packing has nothing to put back; one with
     * only some of them is not a whole carton any more and cannot go back as one (unit-level damage at packing goes
     * with team plan 5-6). Whether the carton fits the bin (BR-12, else an OVERFLOW area) is team plan 5-4's.
     *
     * @throws BusinessException {@code NOT_FOUND}; {@code PUT_BACK_NOT_REQUIRED}, also for a loose line of before
     *         4C-4 (V20261113000400 stops on any still waiting); {@code PICK_ITEM_MISMATCH} for anything but the line's
     *         LPN; {@code STOCK_PACKAGE_TAKEN_WHOLE} for a carton part of which went to quarantine at packing;
     *         {@code PUT_BACK_LOCATION_INVALID}
     */
    public PutBack putBack(UUID lineId, String scannedLpn, PutBackTarget target, UUID actor, Instant now) {
        PickLine line = requireLine(lineId);
        if (line.status() == PickLineStatus.PUT_BACK && lpnMatches(line, scannedLpn)) {
            return new PutBack(line, true);
        }
        if (status != PickStatus.CANCELLED || !needsPutBack || line.status() != PickLineStatus.PICKED
                || line.goodQty() == 0 || line.lpn() == null) {
            throw new BusinessException(ErrorCode.PUT_BACK_NOT_REQUIRED,
                    "Task %s, line %s: nothing to put back".formatted(status, line.status()));
        }
        if (!lpnMatches(line, scannedLpn)) {
            throw new BusinessException(ErrorCode.PICK_ITEM_MISMATCH, "This line puts back carton " + line.lpn());
        }
        if (line.goodQty() != line.allocatedQty()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("%d of carton %s went to quarantine "
                    + "damaged at packing; the rest is not a whole carton and does not go back onto a bin")
                    .formatted(line.packingDamagedQty(), line.lpn()));
        }
        if (target == null || !target.bin() || !target.usable() || !warehouseId.equals(target.warehouseId())) {
            throw new BusinessException(ErrorCode.PUT_BACK_LOCATION_INVALID,
                    "Goods go back onto an active bin of this task's warehouse");
        }
        line.putBack(target.locationCode(), actor, now);
        return new PutBack(line, false);
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
