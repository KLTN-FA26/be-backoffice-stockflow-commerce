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
 * <p>The rules it keeps:</p>
 * <ul>
 *   <li>one line per live hold of the order's <b>stock</b> lines, in the task's warehouse; print lines
 *       get none — their finished goods come from production, not from a shelf (BR-06);</li>
 *   <li>a line is confirmed by scanning its location, then its SKU or barcode, then the quantity, and
 *       only the exact allocation is accepted: more is BR-04, fewer is a short pick (SCRUM-268);</li>
 *   <li>the task completes only when every line is picked (step 9);</li>
 *   <li>goods of a cancelled task go back onto an active bin of the same warehouse, all of a line at
 *       once (BR-05).</li>
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

    /** An order line as the pick list needs it: whose holds to pick, and whether it is printed. */
    public record OrderLine(UUID lineId, String sku, boolean printLine, List<UUID> reservationIds) {
        public OrderLine {
            reservationIds = reservationIds == null ? List.of() : List.copyOf(reservationIds);
        }
    }

    /** One hold of the order, with the warehouse its location belongs to. */
    public record Hold(UUID reservationId, String sku, String locationCode, UUID warehouseId, String lotNumber,
                       int quantity, boolean live) { }

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
     *         in another warehouse than the task's; the message names the SKUs
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
            if (line.printLine()) {
                continue;
            }
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
        stops.sort(Comparator.comparing((Stop stop) -> stop.hold().locationCode())
                .thenComparing(stop -> stop.hold().sku())
                .thenComparing(stop -> stop.hold().reservationId()));
        List<PickLine> added = new ArrayList<>();
        int sequence = 1;
        for (Stop stop : stops) {
            Hold hold = stop.hold();
            added.add(PickLine.open(ids.get(), stop.orderLineId(), hold.reservationId(), hold.sku(),
                    hold.locationCode(), hold.lotNumber(), sequence++, hold.quantity()));
        }
        lines.addAll(added);
        return added;
    }

    // ------------------------------------------------------------------ confirming

    /**
     * The picker scanned a stop: location, then item, then quantity (docs 07 step 6). On success the line
     * is PICKED in full; the caller deducts its hold in the same transaction and records it with
     * {@link PickLine#recordConsumed}.
     *
     * @param barcode the SKU's barcode, accepted as well as the SKU itself; null when it has none
     * @throws BusinessException {@code NOT_FOUND} for a line of another task; {@code PICK_TASK_STATE_CONFLICT}
     *         unless the task is PICKING; {@code PICK_LINE_NOT_OPEN}; {@code PICK_LOCATION_MISMATCH};
     *         {@code PICK_ITEM_MISMATCH}; {@code PICK_QUANTITY_EXCEEDS_ALLOCATION}; {@code PICK_QUANTITY_SHORT}
     */
    public Confirmation confirm(UUID lineId, String scannedLocation, String scannedCode, int quantity,
                                String barcode, UUID actor, Instant now) {
        PickLine line = requireLine(lineId);
        if (line.status() == PickLineStatus.PICKED && matches(line, scannedLocation, scannedCode, barcode)
                && quantity == line.pickedQty()) {
            return new Confirmation(line, true);
        }
        if (status != PickStatus.PICKING) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Lines are confirmed while the task is PICKING; it is " + status);
        }
        if (line.status() != PickLineStatus.OPEN) {
            throw new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN, "The line is " + line.status());
        }
        if (!sameCode(scannedLocation, line.locationCode())) {
            throw new BusinessException(ErrorCode.PICK_LOCATION_MISMATCH,
                    "This line is picked from " + line.locationCode());
        }
        if (!itemMatches(line, scannedCode, barcode)) {
            throw new BusinessException(ErrorCode.PICK_ITEM_MISMATCH, "This line picks " + line.sku());
        }
        if (quantity > line.allocatedQty()) {
            throw new BusinessException(ErrorCode.PICK_QUANTITY_EXCEEDS_ALLOCATION,
                    "%d allocated to this line; %d scanned".formatted(line.allocatedQty(), quantity));
        }
        if (quantity < line.allocatedQty()) {
            throw new BusinessException(ErrorCode.PICK_QUANTITY_SHORT,
                    "%d allocated to this line; %d scanned".formatted(line.allocatedQty(), quantity));
        }
        line.pick(scannedLocation.trim(), scannedCode.trim(), actor, now);
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
     * The picker is at the line's bin and finds fewer units than were allocated there (docs 04c X-SHORT): the line
     * turns SHORT, recording the shortfall; nothing is picked and no stock is deducted. The order stops (the
     * caller puts it ON_HOLD) until the coordinator reallocates. Reporting the same shortfall again answers with
     * the line as it is.
     *
     * @param found the units that are there, 0 up to one fewer than allocated
     * @throws BusinessException {@code NOT_FOUND}; {@code PICK_TASK_STATE_CONFLICT} unless the task is PICKING;
     *         {@code PICK_LINE_NOT_OPEN}; {@code PICK_LOCATION_MISMATCH}; {@code VALIDATION_FAILED} for a quantity
     *         that is not short
     */
    public Confirmation reportShort(UUID lineId, String scannedLocation, int found) {
        PickLine line = requireLine(lineId);
        if (line.status() == PickLineStatus.SHORT && line.shortQty() == line.allocatedQty() - found
                && sameCode(scannedLocation, line.locationCode())) {
            return new Confirmation(line, true);
        }
        if (status != PickStatus.PICKING) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Short picks are reported while the task is PICKING; it is " + status);
        }
        if (line.status() != PickLineStatus.OPEN) {
            throw new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN, "The line is " + line.status());
        }
        if (!sameCode(scannedLocation, line.locationCode())) {
            throw new BusinessException(ErrorCode.PICK_LOCATION_MISMATCH,
                    "This line is picked from " + line.locationCode());
        }
        if (found < 0 || found >= line.allocatedQty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "%d allocated to this line; a short pick finds 0 to %d".formatted(line.allocatedQty(),
                            line.allocatedQty() - 1));
        }
        line.reportShort(found, scannedLocation.trim());
        return new Confirmation(line, false);
    }

    /**
     * The coordinator moved a SHORT line's allocation to other bins of the warehouse: the short line is
     * superseded (CANCELLED, its shortfall kept) and one new OPEN line per new allocation follows the last stop.
     *
     * @throws BusinessException {@code PICK_LINE_NOT_OPEN} unless the line is SHORT;
     *         {@code FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE} for an allocation outside the task's warehouse
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
        line.supersede();
        int sequence = lines.stream().mapToInt(PickLine::sequenceNo).max().orElse(0);
        List<PickLine> added = new ArrayList<>();
        for (Hold hold : allocations) {
            added.add(PickLine.open(ids.get(), line.orderLineId(), hold.reservationId(), hold.sku(),
                    hold.locationCode(), hold.lotNumber(), ++sequence, hold.quantity()));
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
     * @return true when the line was reported damaged already, exactly so
     * @throws BusinessException {@code NOT_FOUND}; {@code PICK_TASK_STATE_CONFLICT} unless the task is PICKING;
     *         {@code PICK_LINE_NOT_OPEN}; {@code PICK_LOCATION_MISMATCH}; {@code VALIDATION_FAILED} unless 1 to the
     *         allocated quantity
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
        if (damaged < 1 || damaged > line.allocatedQty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "%d allocated to this line; damage is reported for 1 to %d".formatted(line.allocatedQty(),
                            line.allocatedQty()));
        }
        return false;
    }

    /**
     * Inventory moved the damaged units to quarantine and made the order whole (SCRUM-277). The line gives way -
     * CANCELLED, its damaged units and the report kept - to the lines that pick what is left of it:
     * <ul>
     *   <li>the good units still at the bin, against the same allocation ({@code retained} of them);</li>
     *   <li>one OPEN line per allocation that replaced the damaged units elsewhere, after the last stop; or</li>
     *   <li>when nothing could replace them, one SHORT line for them, against the impaired allocation: the
     *       coordinator reallocates it like any short line (X-SHORT).</li>
     * </ul>
     *
     * @return the new lines
     * @throws BusinessException {@code PICK_LINE_NOT_OPEN} unless the line is OPEN;
     *         {@code FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE} for a replacement outside the task's warehouse
     */
    public List<PickLine> replaceDamaged(UUID lineId, int damaged, UUID reportId, int retained, List<Hold> replacements,
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
        if (retained < 0 || retained + damaged != line.allocatedQty()
                || (impairedReservationId == null) == replacements.isEmpty()) {
            throw new IllegalArgumentException("The damaged units are either replaced or impaired, never both or neither");
        }
        line.supersedeForDamage(damaged, reportId);
        int sequence = lines.stream().mapToInt(PickLine::sequenceNo).max().orElse(0);
        List<PickLine> added = new ArrayList<>();
        if (retained > 0) {
            added.add(PickLine.fromDamage(ids.get(), line.orderLineId(), line.reservationId(), line.sku(),
                    line.locationCode(), line.lotNumber(), ++sequence, retained, false, reportId));
        }
        for (Hold hold : replacements) {
            added.add(PickLine.fromDamage(ids.get(), line.orderLineId(), hold.reservationId(), hold.sku(),
                    hold.locationCode(), hold.lotNumber(), ++sequence, hold.quantity(), false, reportId));
        }
        if (impairedReservationId != null) {
            added.add(PickLine.fromDamage(ids.get(), line.orderLineId(), impairedReservationId, line.sku(),
                    line.locationCode(), line.lotNumber(), ++sequence, damaged, true, reportId));
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
     * Puts everything a line picked back onto a bin (docs 07 BR-05, 17 §4.5 step 15). Only for a task
     * cancelled after picking started, only for a PICKED line, only onto an active bin of the task's own
     * warehouse — stock changes warehouse through a transfer order, never through a put-back.
     *
     * @throws BusinessException {@code NOT_FOUND}; {@code PUT_BACK_NOT_REQUIRED}; {@code PUT_BACK_LOCATION_INVALID}
     */
    public PutBack putBack(UUID lineId, PutBackTarget target, UUID actor, Instant now) {
        PickLine line = requireLine(lineId);
        if (line.status() == PickLineStatus.PUT_BACK) {
            return new PutBack(line, true);
        }
        if (status != PickStatus.CANCELLED || !needsPutBack || line.status() != PickLineStatus.PICKED) {
            throw new BusinessException(ErrorCode.PUT_BACK_NOT_REQUIRED,
                    "Task %s, line %s: nothing to put back".formatted(status, line.status()));
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

    private static boolean matches(PickLine line, String scannedLocation, String scannedCode, String barcode) {
        return sameCode(scannedLocation, line.locationCode()) && itemMatches(line, scannedCode, barcode);
    }

    /** The SKU (any case, as {@code Sku} normalises it) or the item's barcode, exactly. */
    private static boolean itemMatches(PickLine line, String scannedCode, String barcode) {
        if (scannedCode == null || scannedCode.isBlank()) {
            return false;
        }
        String code = scannedCode.trim();
        return sameCode(code, line.sku()) || barcode != null && code.equals(barcode.trim());
    }

    /** Location codes and SKUs are stored upper case; a scanner or a keyboard may send either. */
    private static boolean sameCode(String scanned, String expected) {
        return scanned != null && scanned.trim().toUpperCase(Locale.ROOT).equals(expected.toUpperCase(Locale.ROOT));
    }
}
