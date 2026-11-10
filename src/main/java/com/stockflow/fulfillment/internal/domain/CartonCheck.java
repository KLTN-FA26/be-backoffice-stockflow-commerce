package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The check session of one task at the carton check and labelling station (team plan 4C-6; kltn-docs 08 §4-§5). The
 * LPN carton the producer packed is the shipping carton (08 BR-02): nothing is opened, packed again, weighed or sealed
 * here. Each carton the task picked is scanned by its LPN, gets its SKU's carton-spec size and weight and a shipping
 * label, or is removed when damaged; completing makes the labelled cartons one shipment.
 *
 * <p>The rules, each said again in the database (V20261113000700):</p>
 * <ul>
 *   <li>The session is PENDING until a worker starts it, then IN_PROGRESS; only that worker, or a warehouse manager,
 *       works it (08 BR-06). Completing in full makes it COMPLETED; in part, it stays IN_PROGRESS for the cartons
 *       picked later (08 §4.4 step 8). Its order cancelled, it is CANCELLED.</li>
 *   <li>A carton is one picked pick line of the task - its LPN, order line, SKU, lot and count - and starts TO_CHECK
 *       (08 §5.2). Only a carton of the task is accepted (BR-05); scanning it again changes nothing.</li>
 *   <li>A label prints only for a CHECKED carton (BR-08) whose SKU's carton spec declares its size and full weight
 *       (BR-07); the label carries them and the volumetric weight D × W × H / divisor (BR-09). A reprint replaces the
 *       label: its version goes up and the old one is void (BR-08).</li>
 *   <li>A damaged carton is REMOVED - from TO_CHECK, CHECKED, or LABELED while in no shipment (08 §5.2) - and goes to
 *       the put-back queue for quarantine (08 §4.2 step 4).</li>
 *   <li>Completing checks, in this order (08 §4.4 step 8): every carton LABELED or REMOVED; at least one LABELED and not
 *       shipped yet; the labelled units cover what the order still wants, or a partial completion with a note that the
 *       order allows (BR-01).</li>
 * </ul>
 *
 * <p>Pure domain: no Spring, no JPA. Locks, the order's lock on the first label and the shipment are the service's.</p>
 */
public final class CartonCheck {

    /** Why a carton left the order (08 §5.2 "Removed"). */
    public enum RemovalReason {
        /** Damaged outside (dented, torn, wet), removed by a worker: it goes whole to quarantine (08 §4.2 step 4). */
        DAMAGED,
        /** Its order was cancelled before any carton was labelled: it goes back onto a bin (08 BR-04). */
        ORDER_CANCELLED
    }

    /**
     * A carton the task picked, as the session learns it: the pick line, the LPN it took and what the carton holds.
     *
     * @param quantity the carton's units, its SKU's standard count (08 §4.2 step 3: never opened to count)
     */
    public record PickedCarton(UUID pickLineId, String lpn, UUID orderLineId, String sku, String lotNumber,
                               int quantity) {
        public PickedCarton {
            Objects.requireNonNull(pickLineId, "pickLineId");
            Objects.requireNonNull(lpn, "lpn");
            Objects.requireNonNull(orderLineId, "orderLineId");
            Objects.requireNonNull(sku, "sku");
            if (quantity <= 0) {
                throw new IllegalArgumentException("A carton holds at least one unit");
            }
        }
    }

    /**
     * A SKU's carton spec as the label needs it (kltn-docs 01 BR-03): size in cm, full weight in kg, may it be turned.
     * {@link #measured()} false: no label prints (08 BR-07).
     */
    public record Spec(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightKg,
                       boolean rotatable) {

        public boolean measured() {
            return positive(lengthCm) && positive(widthCm) && positive(heightCm) && positive(weightKg);
        }
    }

    /** What a completion ships: the labelled cartons not shipped yet, and whether it is part of the order. */
    public record Completion(List<Carton> cartons, boolean partial, List<Missing> missing) { }

    /** An order line the labelled cartons do not cover: {@code wanted} units, {@code labeled} of them labelled. */
    public record Missing(UUID orderLineId, String sku, int wanted, int labeled) { }

    /** A scan's outcome: the carton, and whether it was checked already (nothing changed). */
    public record Scan(Carton carton, boolean repeated) { }

    private static final BigDecimal SMALLEST_WEIGHT = new BigDecimal("0.001");

    private final UUID sessionId;
    private final String orderNumber;
    private PackStatus status;
    private Instant startedAt;
    private UUID startedBy;
    private Instant completedAt;
    private UUID completedBy;
    private final List<Carton> cartons;
    private int lastPackageNo;
    private final Set<UUID> changed = new LinkedHashSet<>();
    private boolean sessionChanged;

    /**
     * @param lastPackageNo the highest package number the order's parcels and cartons use so far: a new carton takes
     *                      the next one, and its label code with it
     */
    public CartonCheck(UUID sessionId, String orderNumber, PackStatus status, Instant startedAt, UUID startedBy,
                       Instant completedAt, UUID completedBy, List<Carton> cartons, int lastPackageNo) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.orderNumber = Objects.requireNonNull(orderNumber, "orderNumber");
        this.status = Objects.requireNonNull(status, "status");
        this.startedAt = startedAt;
        this.startedBy = startedBy;
        this.completedAt = completedAt;
        this.completedBy = completedBy;
        this.cartons = new ArrayList<>(cartons);
        this.lastPackageNo = lastPackageNo;
    }

    // ------------------------------------------------------------------ the session

    /**
     * Cartons the task picked that the session does not know yet become TO_CHECK (08 §5.2 "[*] → To Check"): at the
     * start, and again for cartons a top-up picked after a partial completion (07 §4.3). A cancelled or completed
     * session takes none.
     *
     * @return the cartons added
     */
    public List<Carton> receive(Collection<PickedCarton> picked, Supplier<UUID> ids) {
        if (status == PackStatus.CANCELLED || status == PackStatus.COMPLETED) {
            return List.of();
        }
        Set<UUID> known = cartons.stream().map(Carton::pickLineId).collect(Collectors.toSet());
        List<Carton> added = new ArrayList<>();
        for (PickedCarton carton : picked) {
            if (known.add(carton.pickLineId())) {
                int number = ++lastPackageNo;
                Carton made = new Carton(ids.get(), number, orderNumber + "-C" + number, carton.pickLineId(),
                        carton.lpn().toUpperCase(Locale.ROOT), carton.orderLineId(), carton.sku(), carton.lotNumber(),
                        carton.quantity());
                cartons.add(made);
                changed.add(made.id);
                added.add(made);
            }
        }
        return added;
    }

    /**
     * A worker starts the session (08 §4.1 step 2): PENDING becomes IN_PROGRESS, with who started it. Started already,
     * it is answered as it is for that worker or a warehouse manager.
     *
     * @return whether this started it
     * @throws BusinessException {@code PACKING_TAKEN} for anyone else; {@code PACK_STATE_CONFLICT} once cancelled
     */
    public boolean start(UUID actor, boolean manager, Instant now) {
        switch (status) {
            case PENDING -> {
                status = PackStatus.IN_PROGRESS;
                startedAt = Objects.requireNonNull(now, "now");
                startedBy = Objects.requireNonNull(actor, "actor");
                sessionChanged = true;
                return true;
            }
            case IN_PROGRESS -> {
                requireOwner(actor, manager);
                return false;
            }
            case COMPLETED -> {
                return false;
            }
            default -> throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT,
                    "The check of order %s was cancelled with the order".formatted(orderNumber));
        }
    }

    /**
     * Only while IN_PROGRESS, and only by the worker who started it or a warehouse manager (08 BR-06).
     *
     * @throws BusinessException {@code PACK_STATE_CONFLICT}; {@code PACKING_TAKEN}
     */
    public void requireWorkable(UUID actor, boolean manager) {
        if (status != PackStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT, status == PackStatus.PENDING
                    ? "Start the check of order %s first".formatted(orderNumber)
                    : "The check of order %s is %s".formatted(orderNumber, status));
        }
        requireOwner(actor, manager);
    }

    // ------------------------------------------------------------------ cartons

    /**
     * The LPN of a carton is scanned (08 §4.2 step 3): a carton of this task TO_CHECK becomes CHECKED. One CHECKED or
     * LABELED already is answered as it is (BR-05).
     *
     * @throws BusinessException {@code CARTON_NOT_IN_TASK} for any other LPN; {@code PACK_STATE_CONFLICT} for a carton
     *         removed
     */
    public Scan check(String lpn, UUID actor, Instant now) {
        Carton carton = requireCarton(lpn);
        if (carton.status == PackageStatus.CHECKED || carton.status == PackageStatus.LABELED) {
            return new Scan(carton, true);
        }
        if (carton.status == PackageStatus.REMOVED) {
            throw removed(carton);
        }
        carton.status = PackageStatus.CHECKED;
        carton.checkedAt = now;
        carton.checkedBy = Objects.requireNonNull(actor, "actor");
        changed.add(carton.id);
        return new Scan(carton, false);
    }

    /**
     * Shipping labels for the cartons named, or every CHECKED carton when none is (08 §4.3 steps 5-6): each gets its
     * SKU's carton-spec size, full weight, may-rotate flag and volumetric weight, and label version 1; it is LABELED. A
     * carton labelled already is left as it is. All or nothing: any SKU without a measured spec stops every label.
     *
     * @param specs   the carton spec of each SKU named, by SKU; a SKU missing or not measured has no spec
     * @param divisor the volumetric divisor (08 BR-09, 6000 by default)
     * @return the cartons labelled now
     * @throws BusinessException {@code CARTON_NOT_IN_TASK}; {@code CARTON_NOT_CHECKED} for a carton not scanned yet, or
     *         nothing to label; {@code PACK_STATE_CONFLICT} for one removed; {@code CARTON_SPEC_UNMEASURED} naming the SKUs
     */
    public List<Carton> label(Collection<String> lpns, Map<String, Spec> specs, int divisor, UUID actor,
                              Instant now) {
        List<Carton> chosen;
        if (lpns == null || lpns.isEmpty()) {
            chosen = cartons.stream().filter(c -> c.status == PackageStatus.CHECKED).toList();
            if (chosen.isEmpty()) {
                throw new BusinessException(ErrorCode.CARTON_NOT_CHECKED,
                        "No checked carton of order %s is waiting for a label".formatted(orderNumber));
            }
        } else {
            Map<UUID, Carton> named = new LinkedHashMap<>();
            for (String lpn : lpns) {
                Carton carton = requireCarton(lpn);
                if (carton.status == PackageStatus.TO_CHECK) {
                    throw new BusinessException(ErrorCode.CARTON_NOT_CHECKED,
                            "Scan carton %s first: only a checked carton is labelled".formatted(carton.lpn));
                }
                if (carton.status == PackageStatus.REMOVED) {
                    throw removed(carton);
                }
                named.put(carton.id, carton);
            }
            chosen = named.values().stream().filter(c -> c.status == PackageStatus.CHECKED).toList();
        }
        Set<String> unmeasured = new TreeSet<>();
        chosen.forEach(c -> {
            Spec spec = specs.get(c.sku);
            if (spec == null || !spec.measured()) {
                unmeasured.add(c.sku);
            }
        });
        if (!unmeasured.isEmpty()) {
            throw new BusinessException(ErrorCode.CARTON_SPEC_UNMEASURED, ("The carton spec of %s declares no size or "
                    + "full weight: a warehouse manager completes it on the item, then the labels print")
                    .formatted(String.join(", ", unmeasured)), unmeasured.stream()
                    .map(sku -> new FieldError("sku", sku, "CartonSpecUnmeasured")).toList());
        }
        for (Carton carton : chosen) {
            carton.measure(specs.get(carton.sku), divisor);
            carton.status = PackageStatus.LABELED;
            carton.labelVersion = 1;
            carton.labeledAt = now;
            carton.labeledBy = Objects.requireNonNull(actor, "actor");
            changed.add(carton.id);
        }
        return chosen;
    }

    /**
     * A label torn or lost is printed again (08 §4.3 step 7, BR-08): the carton's label version goes up, so the old
     * label is void. Its size and weight follow the carton spec as it is now while it is in no shipment (08 edge: a spec
     * corrected after the check applies when the label is printed again); once shipped they stay what load planning
     * took.
     *
     * @param spec its SKU's carton spec now; null or not measured keeps the measures it has
     * @throws BusinessException {@code CARTON_NOT_IN_TASK}; {@code CARTON_NOT_CHECKED} unless it is LABELED
     */
    public Carton reprint(String lpn, Spec spec, int divisor) {
        Carton carton = requireCarton(lpn);
        if (carton.status != PackageStatus.LABELED) {
            throw new BusinessException(ErrorCode.CARTON_NOT_CHECKED, ("Carton %s is %s: only a labelled carton's "
                    + "label is printed again").formatted(carton.lpn, carton.status));
        }
        if (carton.shipmentId == null && spec != null && spec.measured()) {
            carton.measure(spec, divisor);
        }
        carton.labelVersion++;
        changed.add(carton.id);
        return carton;
    }

    /**
     * A carton damaged outside is taken out of the order (08 §4.2 step 4): REMOVED, its label void if it had one; it
     * goes to the put-back queue and from there whole into quarantine. Removed already, it is answered as it is.
     *
     * @throws BusinessException {@code CARTON_NOT_IN_TASK}; {@code PACKAGE_SHIPPED} for one in a shipment already
     */
    public Carton remove(String lpn, String note, UUID actor, Instant now) {
        Carton carton = requireCarton(lpn);
        if (carton.status == PackageStatus.REMOVED) {
            return carton;
        }
        if (carton.shipmentId != null) {
            throw new BusinessException(ErrorCode.PACKAGE_SHIPPED, ("Carton %s is in a shipment already; it leaves "
                    + "the order only through the shipment").formatted(carton.lpn));
        }
        carton.removeFor(RemovalReason.DAMAGED, note, Objects.requireNonNull(actor, "actor"), now);
        changed.add(carton.id);
        return carton;
    }

    // ------------------------------------------------------------------ completing

    /**
     * What completing would ship, checked in the order of 08 §4.4 step 8.
     *
     * @param wanted         per order line, the units the order still wants of this task: its quantity less what was
     *                       closed (17 §4.6); every line of the order, even one with no carton
     * @param skus           the SKU of each order line, for the messages
     * @param partialAllowed Sales allowed partial delivery on the order (17 BR-15)
     * @throws BusinessException {@code PACK_STATE_CONFLICT}; {@code PACKING_INCOMPLETE} while a carton is TO_CHECK or
     *         CHECKED, or when the labelled cartons fall short and no partial completion is asked;
     *         {@code PACK_NOTHING_TO_SHIP}; {@code PACK_PARTIAL_NOT_ALLOWED}; {@code VALIDATION_FAILED} for a partial
     *         completion without a note
     */
    public Completion complete(boolean partialRequested, String note, boolean partialAllowed,
                               Map<UUID, Integer> wanted, Map<UUID, String> skus) {
        if (status != PackStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT, "The check of order %s is %s"
                    .formatted(orderNumber, status));
        }
        List<Carton> open = cartons.stream()
                .filter(c -> c.status == PackageStatus.TO_CHECK || c.status == PackageStatus.CHECKED).toList();
        if (!open.isEmpty()) {
            throw new BusinessException(ErrorCode.PACKING_INCOMPLETE, ("%d carton(s) still to check or label, or to "
                    + "remove: %s").formatted(open.size(), open.stream().map(Carton::lpn)
                    .collect(Collectors.joining(", "))), open.stream()
                    .map(c -> new FieldError("cartons[" + c.lpn + "]", c.status.name(), "NotLabeled")).toList());
        }
        List<Carton> toShip = cartons.stream()
                .filter(c -> c.status == PackageStatus.LABELED && c.shipmentId == null).toList();
        if (toShip.isEmpty()) {
            throw new BusinessException(ErrorCode.PACK_NOTHING_TO_SHIP,
                    "No labelled carton of order %s is waiting to ship".formatted(orderNumber));
        }
        List<Missing> missing = new ArrayList<>();
        wanted.forEach((line, units) -> {
            int labeled = labeledUnitsOf(line);
            if (labeled < units) {
                missing.add(new Missing(line, skus.get(line), units, labeled));
            }
        });
        if (!missing.isEmpty()) {
            if (!partialRequested) {
                throw new BusinessException(ErrorCode.PACKING_INCOMPLETE, "Cartons are missing: " + missing.stream()
                        .map(m -> "%s %d of %d labelled".formatted(m.sku(), m.labeled(), m.wanted()))
                        .collect(Collectors.joining(", ")) + "; complete in part if the order allows it",
                        missing.stream().map(m -> new FieldError("lines[" + m.orderLineId() + "]",
                                "%d of %d labelled".formatted(m.labeled(), m.wanted()), "Missing")).toList());
            }
            if (!partialAllowed) {
                throw new BusinessException(ErrorCode.PACK_PARTIAL_NOT_ALLOWED, ("Order %s does not allow partial "
                        + "delivery: label every carton, or ask Sales to allow partial delivery on the order")
                        .formatted(orderNumber));
            }
            if (note == null || note.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A partial completion says why",
                        List.of(FieldError.of("note", "required for a partial completion")));
            }
        }
        return new Completion(toShip, !missing.isEmpty(), List.copyOf(missing));
    }

    /**
     * The completion's cartons are in {@code shipmentId} now. With nothing the order wants left, the session is
     * COMPLETED (08 §5.1); otherwise it stays IN_PROGRESS for the rest.
     */
    public void ship(Completion completion, UUID shipmentId, UUID actor, Instant now) {
        Objects.requireNonNull(shipmentId, "shipmentId");
        completion.cartons().forEach(carton -> {
            carton.shipmentId = shipmentId;
            changed.add(carton.id);
        });
        if (!completion.partial()) {
            status = PackStatus.COMPLETED;
            completedAt = now;
            completedBy = actor;
            sessionChanged = true;
        }
    }

    /**
     * The order was cancelled (08 BR-04): every carton not in a shipment, and every carton of a shipment the
     * cancellation cancelled, is REMOVED for that reason - they go back onto a bin through the put-back queue - and the
     * session is CANCELLED. A carton taken out of a cancelled shipment leaves it ({@code shipment_id} cleared): a REMOVED
     * carton is in no shipment ({@code ck_package_shipped}). A shipment that did not cancel - part of it already left on
     * a vehicle - keeps its cartons. The order refuses a cancellation once a carton is labelled, so normally no label is
     * voided here.
     *
     * @param cancelledShipments the session's shipments the cancellation cancelled
     * @return the cartons removed
     */
    public List<Carton> cancel(Instant now, Set<UUID> cancelledShipments) {
        List<Carton> removed = new ArrayList<>();
        for (Carton carton : cartons) {
            boolean free = carton.shipmentId == null || cancelledShipments.contains(carton.shipmentId);
            if (carton.status != PackageStatus.REMOVED && free) {
                carton.shipmentId = null;
                carton.removeFor(RemovalReason.ORDER_CANCELLED, null, null, now);
                changed.add(carton.id);
                removed.add(carton);
            }
        }
        if (status != PackStatus.CANCELLED) {
            status = PackStatus.CANCELLED;
            sessionChanged = true;
        }
        return removed;
    }

    // ------------------------------------------------------------------ reading

    /** Units of the order line in LABELED cartons, shipped or not. */
    public int labeledUnitsOf(UUID orderLineId) {
        return cartons.stream().filter(c -> c.status == PackageStatus.LABELED && c.orderLineId.equals(orderLineId))
                .mapToInt(Carton::quantity).sum();
    }

    public Optional<Carton> carton(String lpn) {
        String wanted = lpn == null ? "" : lpn.trim().toUpperCase(Locale.ROOT);
        return cartons.stream().filter(c -> c.lpn.equals(wanted)).findFirst();
    }

    /** Unmodifiable: cartons change through this aggregate's methods only. */
    public List<Carton> cartons() {
        return Collections.unmodifiableList(cartons);
    }

    /** The cartons changed or added since the session was loaded, for the store to write. */
    public List<Carton> changedCartons() {
        return cartons.stream().filter(c -> changed.contains(c.id)).toList();
    }

    /** Whether the session's own state (status, who started or completed it) changed since it was loaded. */
    public boolean sessionChanged() { return sessionChanged; }

    /** The store wrote every change: the cartons are stored now, and nothing is pending. */
    public void saved() {
        cartons.forEach(carton -> carton.stored = true);
        changed.clear();
        sessionChanged = false;
    }

    public UUID sessionId() { return sessionId; }
    public String orderNumber() { return orderNumber; }
    public PackStatus status() { return status; }
    public Instant startedAt() { return startedAt; }
    public UUID startedBy() { return startedBy; }
    public Instant completedAt() { return completedAt; }
    public UUID completedBy() { return completedBy; }

    private Carton requireCarton(String lpn) {
        return carton(lpn).orElseThrow(() -> new BusinessException(ErrorCode.CARTON_NOT_IN_TASK, ("Carton %s was not "
                + "picked for order %s: ask the warehouse manager to check the picking and the stock")
                .formatted(lpn == null ? "" : lpn.trim(), orderNumber)));
    }

    private void requireOwner(UUID actor, boolean manager) {
        if (!manager && !Objects.equals(startedBy, actor)) {
            throw new BusinessException(ErrorCode.PACKING_TAKEN,
                    "Another worker is checking order %s; a warehouse manager may step in".formatted(orderNumber));
        }
    }

    private static BusinessException removed(Carton carton) {
        return new BusinessException(ErrorCode.PACK_STATE_CONFLICT, ("Carton %s was removed from the order (%s); it "
                + "waits in the put-back queue").formatted(carton.lpn, carton.removalReason));
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    // ------------------------------------------------------------------ the carton

    /** One shipping carton of the session (08 §5.2): a picked carton, from its scan to its shipment. */
    public static final class Carton {

        private final UUID id;
        private final int packageNo;
        private final String labelCode;
        private final UUID pickLineId;
        private final String lpn;
        private final UUID orderLineId;
        private final String sku;
        private final String lotNumber;
        private final int quantity;
        private boolean stored;
        private PackageStatus status;
        private Instant checkedAt;
        private UUID checkedBy;
        private Instant labeledAt;
        private UUID labeledBy;
        private int labelVersion;
        private BigDecimal lengthCm;
        private BigDecimal widthCm;
        private BigDecimal heightCm;
        private BigDecimal weightKg;
        private BigDecimal dimWeightKg;
        private boolean rotatable = true;
        private UUID shipmentId;
        private Instant removedAt;
        private UUID removedBy;
        private RemovalReason removalReason;
        private String removalNote;

        /** A carton new to the session, TO_CHECK. */
        Carton(UUID id, int packageNo, String labelCode, UUID pickLineId, String lpn, UUID orderLineId, String sku,
               String lotNumber, int quantity) {
            this.id = Objects.requireNonNull(id, "id");
            this.packageNo = packageNo;
            this.labelCode = Objects.requireNonNull(labelCode, "labelCode");
            this.pickLineId = Objects.requireNonNull(pickLineId, "pickLineId");
            this.lpn = Objects.requireNonNull(lpn, "lpn");
            this.orderLineId = Objects.requireNonNull(orderLineId, "orderLineId");
            this.sku = Objects.requireNonNull(sku, "sku");
            this.lotNumber = lotNumber;
            this.quantity = quantity;
            this.status = PackageStatus.TO_CHECK;
            this.stored = false;
        }

        /** Rehydration of a stored carton. */
        @SuppressWarnings("java:S107")
        public Carton(UUID id, int packageNo, String labelCode, PickedCarton picked, PackageStatus status,
                      Instant checkedAt, UUID checkedBy, Instant labeledAt, UUID labeledBy, int labelVersion,
                      BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightKg,
                      BigDecimal dimWeightKg, boolean rotatable, UUID shipmentId, Instant removedAt, UUID removedBy,
                      RemovalReason removalReason, String removalNote) {
            this.id = Objects.requireNonNull(id, "id");
            this.packageNo = packageNo;
            this.labelCode = Objects.requireNonNull(labelCode, "labelCode");
            this.pickLineId = picked.pickLineId();
            this.lpn = picked.lpn().toUpperCase(Locale.ROOT);
            this.orderLineId = picked.orderLineId();
            this.sku = picked.sku();
            this.lotNumber = picked.lotNumber();
            this.quantity = picked.quantity();
            this.status = Objects.requireNonNull(status, "status");
            if (status != PackageStatus.TO_CHECK && status != PackageStatus.CHECKED && status != PackageStatus.LABELED
                    && status != PackageStatus.REMOVED) {
                throw new IllegalArgumentException("A shipping carton is not " + status);
            }
            this.checkedAt = checkedAt;
            this.checkedBy = checkedBy;
            this.labeledAt = labeledAt;
            this.labeledBy = labeledBy;
            this.labelVersion = labelVersion;
            this.lengthCm = lengthCm;
            this.widthCm = widthCm;
            this.heightCm = heightCm;
            this.weightKg = weightKg;
            this.dimWeightKg = dimWeightKg;
            this.rotatable = rotatable;
            this.shipmentId = shipmentId;
            this.removedAt = removedAt;
            this.removedBy = removedBy;
            this.removalReason = removalReason;
            this.removalNote = removalNote;
            this.stored = true;
        }

        /** The carton spec's size and full weight, and the volumetric weight D × W × H / divisor (08 BR-07, BR-09). */
        private void measure(Spec spec, int divisor) {
            if (divisor <= 0) {
                throw new IllegalArgumentException("The volumetric divisor is positive");
            }
            lengthCm = spec.lengthCm();
            widthCm = spec.widthCm();
            heightCm = spec.heightCm();
            weightKg = spec.weightKg();
            rotatable = spec.rotatable();
            // Rounded to the gram, never to nothing: the column says a known weight is positive.
            dimWeightKg = lengthCm.multiply(widthCm).multiply(heightCm)
                    .divide(BigDecimal.valueOf(divisor), 3, RoundingMode.HALF_UP).max(SMALLEST_WEIGHT);
        }

        private void removeFor(RemovalReason reason, String note, UUID actor, Instant now) {
            status = PackageStatus.REMOVED;
            removalReason = reason;
            removalNote = note == null || note.isBlank() ? null : note.trim();
            removedBy = actor;
            removedAt = Objects.requireNonNull(now, "now");
        }

        public UUID id() { return id; }
        public int packageNo() { return packageNo; }
        public String labelCode() { return labelCode; }
        public UUID pickLineId() { return pickLineId; }
        public String lpn() { return lpn; }
        public UUID orderLineId() { return orderLineId; }
        public String sku() { return sku; }
        public String lotNumber() { return lotNumber; }
        public int quantity() { return quantity; }
        /** Whether it is in the store (false: received in this session, not written yet). */
        public boolean stored() { return stored; }
        public PackageStatus status() { return status; }
        public Instant checkedAt() { return checkedAt; }
        public UUID checkedBy() { return checkedBy; }
        public Instant labeledAt() { return labeledAt; }
        public UUID labeledBy() { return labeledBy; }
        public int labelVersion() { return labelVersion; }
        public BigDecimal lengthCm() { return lengthCm; }
        public BigDecimal widthCm() { return widthCm; }
        public BigDecimal heightCm() { return heightCm; }
        public BigDecimal weightKg() { return weightKg; }
        public BigDecimal dimWeightKg() { return dimWeightKg; }
        public boolean rotatable() { return rotatable; }
        public UUID shipmentId() { return shipmentId; }
        public Instant removedAt() { return removedAt; }
        public UUID removedBy() { return removedBy; }
        public RemovalReason removalReason() { return removalReason; }
        public String removalNote() { return removalNote; }
    }
}
