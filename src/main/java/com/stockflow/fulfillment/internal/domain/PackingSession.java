package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <b>The packing session of one task</b> (kltn-docs 08 §4-6, SCRUM-278/281/282): the order's lines as the packing
 * table sees them, the parcels being filled, and the rules that keep them right.
 *
 * <ul>
 *   <li><b>Scan to verify</b> (08 step 2, BR-01): a scan names the item by SKU or barcode, with its lot or serial when
 *       the goods have one. An item the order does not have, more than the line wants or than was picked for it, a lot
 *       or a serial that was not picked for it - each is refused with its own code and writes nothing. The same scan
 *       again (its {@code scanId}) changes nothing.</li>
 *   <li><b>One parcel, one order</b> (08 BR-04): a session only holds its own order's lines; the database checks it
 *       too.</li>
 *   <li><b>Measured parcels</b> (08 BR-03, step 5): a parcel is sealed with its real weight and size; its rotatable
 *       flag comes from its SKUs - the strictest wins (01, E13) - its fragile flag from a FRAGILE SKU, and the packer
 *       may override either.</li>
 *   <li><b>Shipments</b> (08 step 9, 09 §4.1 step 1): completing ships every sealed parcel not yet shipped as one
 *       shipment. Everything the order still wants must be in it (BR-01) unless the packer completes a partial
 *       shipment, with a reason, where the policy allows split shipments (G4-Q3; a mixed order's stock lines first,
 *       decision 2026-10-21); the rest is packed later into another shipment.</li>
 * </ul>
 *
 * <p>Pure Java: the service supplies the facts it needs from the order, the pick and inventory, and persists what
 * changed ({@link #changedParcels}, {@link #pullScans}).</p>
 */
public final class PackingSession {

    /** The key of {@link Line#lots} for units picked without a lot, beside others of the same line that have one. */
    public static final String NO_LOT = "";

    /** More units than one scan or one removal ever carries: refused before any arithmetic on it. */
    public static final int MAX_UNITS_PER_SCAN = 1_000_000;

    /**
     * One order line as packing sees it.
     *
     * @param wanted    what the order still wants of it: its quantity less units closed out of it
     * @param available what reached the packing area for it: picked (a stock line) or produced (a print line)
     * @param lots      lot number to the units of it picked or produced; empty when the goods have no lot. When some
     *                  of them have a lot and some do not, the units without one are under {@link #NO_LOT}
     * @param serials   the serial numbers of the goods; empty when they have none
     * @param fragile   its SKU is stored FRAGILE (docs 01 storage class): a parcel holding it is marked fragile
     */
    public record Line(UUID orderLineId, String sku, String barcode, int wanted, int available, boolean printLine,
                       UUID designSnapshotId, Map<String, Integer> lots, Set<String> serials, boolean rotatable,
                       boolean fragile) {
        public Line {
            Objects.requireNonNull(orderLineId, "orderLineId");
            Objects.requireNonNull(sku, "sku");
            lots = lots == null ? Map.of() : Map.copyOf(lots);
            serials = serials == null ? Set.of() : Set.copyOf(serials);
        }
    }

    /** What one parcel holds of one line: per lot and serial, as scanned. */
    public record ContentKey(UUID orderLineId, String lotNumber, String serialNumber) { }

    /** One scan to write: positive put into the parcel, negative taken out. */
    public record Scan(UUID id, UUID packageId, UUID orderLineId, String scannedCode, String lotNumber,
                       String serialNumber, int quantity, UUID by, Instant at) { }

    /** Measures a parcel is sealed with (08 step 5); centimetres and kilograms. */
    public record Measures(BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) { }

    /** Units of one line still missing from what would ship. */
    public record Missing(UUID orderLineId, String sku, int wanted, int packed) { }

    /**
     * What completing ships.
     *
     * @param lines      units per order line in the shipment
     * @param partial    something the order still wants stays behind
     * @param missing    what stays behind, per line
     */
    public record Completion(List<Parcel> parcels, Map<UUID, Integer> lines, boolean partial, List<Missing> missing) { }

    /** A parcel (table {@code fulfillment.package}). */
    public static final class Parcel {
        private final UUID id;
        private final int packageNo;
        private final String labelCode;
        private PackageType type;
        private PackageStatus status;
        private UUID shipmentId;
        private Measures measures;
        private BigDecimal dimWeightKg;
        private boolean rotatable;
        private boolean rotatableOverridden;
        private boolean fragile;
        private boolean fragileOverridden;
        private Instant sealedAt;
        private UUID sealedBy;
        private Instant voidedAt;
        private UUID voidedBy;
        private final Map<ContentKey, Integer> contents = new LinkedHashMap<>();
        private final Instant createdAt;
        /** Its shipment came back (RETURNED): its units are the order's to ship again, so it holds nothing now. */
        private boolean returned;

        @SuppressWarnings("java:S107")
        public Parcel(UUID id, int packageNo, String labelCode, PackageType type, PackageStatus status, UUID shipmentId,
                      Measures measures, BigDecimal dimWeightKg, boolean rotatable, boolean rotatableOverridden,
                      boolean fragile, boolean fragileOverridden, Instant sealedAt, UUID sealedBy, Instant voidedAt,
                      UUID voidedBy, Map<ContentKey, Integer> contents, Instant createdAt) {
            this.id = Objects.requireNonNull(id, "id");
            this.packageNo = packageNo;
            this.labelCode = Objects.requireNonNull(labelCode, "labelCode");
            this.type = type == null ? PackageType.CARTON : type;
            this.status = Objects.requireNonNull(status, "status");
            this.shipmentId = shipmentId;
            this.measures = measures;
            this.dimWeightKg = dimWeightKg;
            this.rotatable = rotatable;
            this.rotatableOverridden = rotatableOverridden;
            this.fragile = fragile;
            this.fragileOverridden = fragileOverridden;
            this.sealedAt = sealedAt;
            this.sealedBy = sealedBy;
            this.voidedAt = voidedAt;
            this.voidedBy = voidedBy;
            if (contents != null) {
                contents.forEach((key, quantity) -> {
                    if (quantity != 0) {
                        this.contents.put(key, quantity);
                    }
                });
            }
            this.createdAt = createdAt;
        }

        /** Units per order line, as {@code package_line} keeps them. */
        public Map<UUID, Integer> lineTotals() {
            Map<UUID, Integer> totals = new LinkedHashMap<>();
            contents.forEach((key, quantity) -> totals.merge(key.orderLineId(), quantity, Integer::sum));
            totals.values().removeIf(quantity -> quantity == 0);
            return totals;
        }

        int quantityOf(UUID orderLineId) {
            return contents.entrySet().stream().filter(e -> e.getKey().orderLineId().equals(orderLineId))
                    .mapToInt(Map.Entry::getValue).sum();
        }

        /** It holds goods of the order: not voided, and not in a shipment that came back. */
        boolean live() { return status != PackageStatus.VOIDED && !returned; }

        /** Read back from a shipment that came back to the warehouse (09 §4.3 step 3). */
        public Parcel markReturned() {
            this.returned = true;
            return this;
        }

        public boolean returned() { return returned; }
        boolean shipped() { return shipmentId != null; }
        boolean isEmpty() { return contents.isEmpty(); }

        public UUID id() { return id; }
        public int packageNo() { return packageNo; }
        public String labelCode() { return labelCode; }
        public PackageType type() { return type; }
        public PackageStatus status() { return status; }
        public UUID shipmentId() { return shipmentId; }
        public Measures measures() { return measures; }
        public BigDecimal dimWeightKg() { return dimWeightKg; }
        public boolean rotatable() { return rotatable; }
        public boolean rotatableOverridden() { return rotatableOverridden; }
        public boolean fragile() { return fragile; }
        public boolean fragileOverridden() { return fragileOverridden; }
        public Instant sealedAt() { return sealedAt; }
        public UUID sealedBy() { return sealedBy; }
        public Instant voidedAt() { return voidedAt; }
        public UUID voidedBy() { return voidedBy; }
        public Map<ContentKey, Integer> contents() { return java.util.Collections.unmodifiableMap(contents); }
        public Instant createdAt() { return createdAt; }
    }

    private final UUID packId;
    private final String orderNumber;
    private PackStatus status;
    private Instant startedAt;
    private UUID startedBy;
    private Instant packedAt;
    private UUID packedBy;
    private final Map<UUID, Line> lines = new LinkedHashMap<>();
    private final List<Parcel> parcels = new ArrayList<>();
    private final Set<UUID> knownScans = new HashSet<>();
    private final List<Scan> newScans = new ArrayList<>();
    private final Set<UUID> changed = new HashSet<>();

    @SuppressWarnings("java:S107")
    public PackingSession(UUID packId, String orderNumber, PackStatus status, Instant startedAt, UUID startedBy,
                          Instant packedAt, UUID packedBy, Collection<Line> lines, Collection<Parcel> parcels,
                          Collection<UUID> knownScans) {
        this.packId = Objects.requireNonNull(packId, "packId");
        this.orderNumber = Objects.requireNonNull(orderNumber, "orderNumber");
        this.status = Objects.requireNonNull(status, "status");
        this.startedAt = startedAt;
        this.startedBy = startedBy;
        this.packedAt = packedAt;
        this.packedBy = packedBy;
        lines.forEach(line -> this.lines.put(line.orderLineId(), line));
        this.parcels.addAll(parcels);
        this.parcels.sort(java.util.Comparator.comparingInt(Parcel::packageNo));
        this.knownScans.addAll(knownScans);
    }

    // ------------------------------------------------------------------ the session

    /**
     * A packer takes the order at the packing table (08 §5 Pending → In Progress). Taking it again is answered as it
     * is; another packer is refused while it is in progress ({@code PACKING_TAKEN}) unless a manager.
     *
     * @return whether this changed the session
     */
    public boolean start(UUID actor, boolean manager, Instant now) {
        switch (status) {
            case PENDING -> {
                status = PackStatus.PACKING;
                startedAt = now;
                startedBy = Objects.requireNonNull(actor, "actor");
                return true;
            }
            case PACKING -> {
                requireOwner(actor, manager);
                return false;
            }
            case PACKED -> {
                return false;
            }
            case ON_HOLD -> throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT,
                    "Packing is on hold for a design check; QC or a warehouse manager re-verifies it first");
            default -> throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT, "Packing is " + status);
        }
    }

    /** Only while PACKING, and only the packer who took it or a warehouse manager. */
    public void requireWorkable(UUID actor, boolean manager) {
        if (status != PackStatus.PACKING) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT, status == PackStatus.PENDING
                    ? "Start packing first" : "Packing is " + status + "; parcels cannot change now");
        }
        requireOwner(actor, manager);
    }

    private void requireOwner(UUID actor, boolean manager) {
        if (!manager && !Objects.equals(actor, startedBy)) {
            throw new BusinessException(ErrorCode.PACKING_TAKEN, "Another packer is packing order " + orderNumber);
        }
    }

    // ------------------------------------------------------------------ parcels

    /** A new, empty parcel; its number is the next of the session, its label code {@code PKG-<order>-<n>}. */
    public Parcel openPackage(UUID id, PackageType type, Instant now) {
        int next = parcels.stream().mapToInt(Parcel::packageNo).max().orElse(0) + 1;
        Parcel parcel = new Parcel(id, next, "PKG-" + orderNumber + "-" + next, type, PackageStatus.OPEN, null, null,
                null, true, false, false, false, null, null, null, null, Map.of(), now);
        parcels.add(parcel);
        changed.add(id);
        return parcel;
    }

    /**
     * Docs 08 step 2: one scan of {@code quantity} units into an open parcel. See the class comment for what is
     * refused. {@code orderLineId} is needed only when the code is on two lines (a plain and a printed cup of the
     * same SKU); otherwise the line is found by the code.
     *
     * @return false when the scan was recorded before (same {@code scanId}): nothing changes
     */
    @SuppressWarnings("java:S107")
    public boolean scan(UUID scanId, UUID packageId, String code, String lotNumber, String serialNumber, int quantity,
                        UUID orderLineId, UUID actor, Instant now) {
        Objects.requireNonNull(scanId, "scanId");
        if (knownScans.contains(scanId)) {
            return false;
        }
        requireUnits(quantity);
        Parcel parcel = requireOpen(packageId);
        Line line = lineFor(code, orderLineId);
        int packed = packedOf(line.orderLineId());
        if ((long) packed + quantity > line.wanted()) {
            throw new BusinessException(ErrorCode.PACK_QUANTITY_EXCEEDS_ORDER,
                    "Line %s wants %d; %d packed already, %d scanned".formatted(line.sku(), line.wanted(), packed,
                            quantity), List.of(new FieldError("quantity", "at most " + (line.wanted() - packed),
                    "ExceedsOrder")));
        }
        if ((long) packed + quantity > line.available()) {
            throw new BusinessException(ErrorCode.PACK_QUANTITY_EXCEEDS_PICKED,
                    "%d of %s reached packing; %d packed already, %d scanned".formatted(line.available(), line.sku(),
                            packed, quantity), List.of(new FieldError("quantity",
                    "at most " + Math.max(0, line.available() - packed), "ExceedsPicked")));
        }
        String lot = checkLot(line, lotNumber, quantity);
        String serial = checkSerial(line, serialNumber, quantity);
        ContentKey key = new ContentKey(line.orderLineId(), lot, serial);
        parcel.contents.merge(key, quantity, Integer::sum);
        record(new Scan(scanId, parcel.id, line.orderLineId(), code.trim(), lot, serial, quantity, actor, now));
        return true;
    }

    /**
     * Takes units of a line out of an open parcel again (a negative scan). The lot or serial is needed only when the
     * parcel holds the line under more than one. Again with the same {@code scanId}, nothing changes.
     *
     * @return false for a retry: the removal was recorded before
     */
    @SuppressWarnings("java:S107")
    public boolean remove(UUID scanId, UUID packageId, UUID orderLineId, String lotNumber, String serialNumber,
                          int quantity, UUID actor, Instant now) {
        Objects.requireNonNull(scanId, "scanId");
        if (knownScans.contains(scanId)) {
            return false;
        }
        requireUnits(quantity);
        Parcel parcel = requireOpen(packageId);
        List<ContentKey> keys = parcel.contents.keySet().stream()
                .filter(key -> key.orderLineId().equals(orderLineId))
                .filter(key -> blank(lotNumber) || same(lotNumber, key.lotNumber()))
                .filter(key -> blank(serialNumber) || same(serialNumber, key.serialNumber()))
                .toList();
        if (keys.isEmpty()) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT,
                    "Package %d holds none of that line".formatted(parcel.packageNo));
        }
        if (keys.size() > 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The package holds that line under several lots "
                    + "or serial numbers: name the one to take out", List.of(FieldError.of("lotNumber",
                    "required: " + keys.stream().map(k -> k.lotNumber() + "/" + k.serialNumber())
                            .collect(Collectors.joining(", ")))));
        }
        ContentKey key = keys.get(0);
        int held = parcel.contents.get(key);
        if (quantity > held) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT,
                    "Package %d holds %d of that line, not %d".formatted(parcel.packageNo, held, quantity));
        }
        if (held == quantity) {
            parcel.contents.remove(key);
        } else {
            parcel.contents.put(key, held - quantity);
        }
        record(new Scan(scanId, parcel.id, orderLineId, lines.get(orderLineId) == null ? "-"
                : lines.get(orderLineId).sku(), key.lotNumber(), key.serialNumber(), -quantity, actor, now));
        return true;
    }

    /** Whether {@code scanId} was recorded in this session: a retry is answered before anything else is checked. */
    public boolean knows(UUID scanId) {
        return knownScans.contains(scanId);
    }

    private static void requireUnits(int quantity) {
        if (quantity < 1 || quantity > MAX_UNITS_PER_SCAN) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A scan moves between 1 and %d units"
                    .formatted(MAX_UNITS_PER_SCAN), List.of(FieldError.of("quantity",
                    "must be between 1 and " + MAX_UNITS_PER_SCAN)));
        }
    }

    /**
     * Docs 08 step 5-7: weighed and measured, the parcel is sealed. Its rotatable flag defaults to the strictest of its
     * SKUs (one that may not be turned keeps the parcel upright), its fragile flag to any FRAGILE SKU; the packer may
     * set either (E13). The dimensional weight is {@code L x W x H / divisor}.
     */
    @SuppressWarnings("java:S107")
    public Parcel seal(UUID packageId, Measures measures, Boolean rotatable, Boolean fragile, PackageType type,
                       int dimDivisor, UUID actor, Instant now) {
        Parcel parcel = requireParcel(packageId);
        if (parcel.status == PackageStatus.SEALED) {
            throw new BusinessException(ErrorCode.PACKAGE_SEALED, "Package %d is sealed".formatted(parcel.packageNo));
        }
        requireOpen(packageId);
        if (parcel.isEmpty()) {
            throw new BusinessException(ErrorCode.PACKAGE_EMPTY, "Package %d has nothing in it".formatted(parcel.packageNo));
        }
        requireMeasures(measures);
        boolean defaultRotatable = parcel.lineTotals().keySet().stream().map(lines::get).filter(Objects::nonNull)
                .allMatch(Line::rotatable);
        boolean defaultFragile = parcel.lineTotals().keySet().stream().map(lines::get).filter(Objects::nonNull)
                .anyMatch(Line::fragile);
        parcel.rotatable = rotatable == null ? defaultRotatable : rotatable;
        parcel.rotatableOverridden = parcel.rotatable != defaultRotatable;
        parcel.fragile = fragile == null ? defaultFragile : fragile;
        parcel.fragileOverridden = parcel.fragile != defaultFragile;
        if (type != null) {
            parcel.type = type;
        }
        parcel.measures = measures;
        parcel.dimWeightKg = measures.lengthCm().multiply(measures.widthCm()).multiply(measures.heightCm())
                .divide(BigDecimal.valueOf(dimDivisor), 3, RoundingMode.HALF_UP).max(new BigDecimal("0.001"));
        parcel.status = PackageStatus.SEALED;
        parcel.sealedAt = now;
        parcel.sealedBy = actor;
        changed.add(parcel.id);
        return parcel;
    }

    /** Opened again before it shipped (08 BR-06 before the handover): goods can be changed, it is sealed again. */
    public Parcel reopen(UUID packageId) {
        Parcel parcel = requireParcel(packageId);
        requireNotShipped(parcel);
        if (parcel.status == PackageStatus.OPEN) {
            return parcel;
        }
        if (parcel.status == PackageStatus.VOIDED) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT, "Package %d was voided".formatted(parcel.packageNo));
        }
        parcel.status = PackageStatus.OPEN;
        parcel.sealedAt = null;
        parcel.sealedBy = null;
        changed.add(parcel.id);
        return parcel;
    }

    /** Taken apart before it shipped: its goods count as unpacked again; its label is void (08 BR-05). */
    public Parcel voidPackage(UUID packageId, UUID actor, Instant now) {
        Parcel parcel = requireParcel(packageId);
        if (parcel.status == PackageStatus.VOIDED) {
            return parcel;
        }
        requireNotShipped(parcel);
        parcel.status = PackageStatus.VOIDED;
        parcel.voidedAt = now;
        parcel.voidedBy = actor;
        changed.add(parcel.id);
        return parcel;
    }

    // ------------------------------------------------------------------ completing

    /**
     * What completing would ship: every sealed parcel not shipped yet. Open parcels with goods in them stop it
     * ({@code PACKAGE_NOT_SEALED}, 09 BR-01: only measured, labelled parcels leave); empty open ones are voided.
     * Everything the order still wants must be in it ({@code PACKING_INCOMPLETE}, 08 BR-01) unless a partial shipment
     * is asked for, with a reason, and allowed - by Sales, on the order ({@code partialAllowed}, PACK-PARTIAL).
     */
    public Completion complete(boolean partialRequested, String reason, boolean partialAllowed, UUID actor,
                               Instant now) {
        if (status != PackStatus.PACKING) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT, "Packing is " + status);
        }
        List<Parcel> open = parcels.stream().filter(p -> p.status == PackageStatus.OPEN && !p.isEmpty()).toList();
        if (!open.isEmpty()) {
            throw new BusinessException(ErrorCode.PACKAGE_NOT_SEALED, "Package(s) %s still open: weigh, measure and "
                    + "seal them, or take them apart".formatted(open.stream().map(p -> String.valueOf(p.packageNo))
                    .collect(Collectors.joining(", "))));
        }
        List<Parcel> toShip = parcels.stream().filter(p -> p.status == PackageStatus.SEALED && !p.shipped()).toList();
        if (toShip.isEmpty()) {
            throw new BusinessException(ErrorCode.PACK_NOTHING_TO_SHIP, "No sealed package is waiting to ship");
        }
        Map<UUID, Integer> content = new LinkedHashMap<>();
        toShip.forEach(parcel -> parcel.lineTotals().forEach((line, quantity) -> content.merge(line, quantity,
                Integer::sum)));
        List<Missing> missing = lines.values().stream()
                .filter(line -> packedOf(line.orderLineId()) < line.wanted())
                .map(line -> new Missing(line.orderLineId(), line.sku(), line.wanted(), packedOf(line.orderLineId())))
                .toList();
        if (!missing.isEmpty()) {
            if (!partialRequested) {
                throw new BusinessException(ErrorCode.PACKING_INCOMPLETE, "Not everything is packed: " + missing.stream()
                        .map(m -> "%s %d of %d".formatted(m.sku(), m.packed(), m.wanted()))
                        .collect(Collectors.joining(", ")), missing.stream().map(m -> new FieldError(
                        "lines[" + m.orderLineId() + "]", "%d of %d packed".formatted(m.packed(), m.wanted()),
                        "Missing")).toList());
            }
            if (!partialAllowed) {
                throw new BusinessException(ErrorCode.PACK_PARTIAL_NOT_ALLOWED, ("Order %s does not allow partial "
                        + "shipments: pack the whole order, or ask Sales to allow partial shipment on the order")
                        .formatted(orderNumber));
            }
            if (blank(reason)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A partial shipment says why",
                        List.of(FieldError.of("note", "required for a partial shipment")));
            }
        }
        parcels.stream().filter(p -> p.status == PackageStatus.OPEN && p.isEmpty())
                .forEach(p -> voidPackage(p.id, actor, now));
        return new Completion(toShip, content, !missing.isEmpty(), missing);
    }

    /**
     * The completion's parcels are now in {@code shipmentId}. When nothing the order wants is left, the session is
     * PACKED (08 §5); otherwise it stays PACKING for the rest.
     */
    public void ship(Completion completion, UUID shipmentId, UUID actor, Instant now) {
        completion.parcels().forEach(parcel -> {
            parcel.shipmentId = shipmentId;
            changed.add(parcel.id);
        });
        if (!completion.partial()) {
            status = PackStatus.PACKED;
            packedAt = now;
            packedBy = actor;
        }
    }

    /** A printed design did not match its snapshot (BR-DSG-003, 08 BR-02): QC or a manager decides. */
    public void hold() {
        if (status != PackStatus.PACKING && status != PackStatus.ON_HOLD) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT, "Packing is " + status);
        }
        status = PackStatus.ON_HOLD;
    }

    /** Re-verified (08 §5 Verification Failed → In Progress): the packer completes it again. */
    public void resume() {
        if (status != PackStatus.ON_HOLD) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT, "The package has no design-integrity hold");
        }
        status = PackStatus.PACKING;
    }

    /** The order was cancelled: parcels that did not ship are voided with their labels (08 BR-05). */
    public List<Parcel> cancel(UUID actor, Instant now) {
        List<Parcel> voided = new ArrayList<>();
        parcels.stream().filter(p -> p.status != PackageStatus.VOIDED && !p.shipped()).forEach(p -> {
            voidPackage(p.id, actor, now);
            voided.add(p);
        });
        status = PackStatus.CANCELLED;
        return voided;
    }

    // ------------------------------------------------------------------ reading

    /** Units of the line in parcels that are not voided, shipped or not. */
    public int packedOf(UUID orderLineId) {
        return parcels.stream().filter(Parcel::live).mapToInt(p -> p.quantityOf(orderLineId)).sum();
    }

    /** Units of the line in parcels already in a shipment. */
    public int shippedOf(UUID orderLineId) {
        return parcels.stream().filter(p -> p.live() && p.shipped()).mapToInt(p -> p.quantityOf(orderLineId)).sum();
    }

    /** The print lines among {@code orderLineIds}: what a design check covers for a shipment. */
    public List<Line> printLinesIn(Collection<UUID> orderLineIds) {
        return orderLineIds.stream().map(lines::get).filter(Objects::nonNull).filter(Line::printLine).toList();
    }

    public Optional<Parcel> parcel(UUID packageId) {
        return parcels.stream().filter(p -> p.id.equals(packageId)).findFirst();
    }

    public List<Parcel> parcels() { return List.copyOf(parcels); }
    public List<Line> lines() { return List.copyOf(lines.values()); }
    public List<Parcel> changedParcels() { return parcels.stream().filter(p -> changed.contains(p.id)).toList(); }

    /** The scans recorded since it was read; cleared by the call. */
    public List<Scan> pullScans() {
        List<Scan> copy = List.copyOf(newScans);
        newScans.clear();
        return copy;
    }

    public UUID packId() { return packId; }
    public PackStatus status() { return status; }
    public Instant startedAt() { return startedAt; }
    public UUID startedBy() { return startedBy; }
    public Instant packedAt() { return packedAt; }
    public UUID packedBy() { return packedBy; }

    // ------------------------------------------------------------------ internals

    private Line lineFor(String code, UUID orderLineId) {
        if (blank(code)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Scan the item's SKU or barcode",
                    List.of(FieldError.of("scannedCode", "must not be blank")));
        }
        List<Line> matching = lines.values().stream().filter(line -> same(code, line.sku()) || same(code, line.barcode()))
                .toList();
        if (orderLineId != null) {
            Line named = lines.get(orderLineId);
            if (named == null || !matching.contains(named)) {
                throw new BusinessException(ErrorCode.PACK_ITEM_NOT_IN_ORDER, named == null
                        ? "That line is not on order " + orderNumber
                        : "Scanned %s; this line is %s".formatted(code.trim(), named.sku()));
            }
            return named;
        }
        if (matching.isEmpty()) {
            throw new BusinessException(ErrorCode.PACK_ITEM_NOT_IN_ORDER,
                    "%s is not on order %s".formatted(code.trim(), orderNumber));
        }
        if (matching.size() == 1) {
            return matching.get(0);
        }
        List<Line> open = matching.stream().filter(line -> packedOf(line.orderLineId()) < line.wanted()).toList();
        if (open.size() == 1) {
            return open.get(0);
        }
        throw new BusinessException(ErrorCode.PACK_SCAN_AMBIGUOUS,
                "%s is on %d lines of order %s: name the line".formatted(code.trim(), matching.size(), orderNumber),
                List.of(new FieldError("orderLineId", "one of " + matching.stream()
                        .map(l -> l.orderLineId().toString()).collect(Collectors.joining(", ")), "Ambiguous")));
    }

    private String checkLot(Line line, String lotNumber, int quantity) {
        if (line.lots().isEmpty()) {
            if (!blank(lotNumber)) {
                throw new BusinessException(ErrorCode.PACK_LOT_MISMATCH,
                        "%s has no lot; scanned lot %s".formatted(line.sku(), lotNumber.trim()));
            }
            return null;
        }
        String lot;
        if (blank(lotNumber)) {
            // Some of the line's goods were picked without a lot: those are scanned without one.
            if (!line.lots().containsKey(NO_LOT)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, line.sku() + " is tracked by lot: scan the lot",
                        List.of(FieldError.of("lotNumber", "required for " + line.sku())));
            }
            lot = NO_LOT;
        } else {
            lot = line.lots().keySet().stream().filter(known -> !NO_LOT.equals(known) && same(lotNumber, known))
                    .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.PACK_LOT_MISMATCH,
                            "Lot %s was not picked for %s (picked: %s)".formatted(lotNumber.trim(), line.sku(),
                                    line.lots().keySet().stream().filter(k -> !NO_LOT.equals(k))
                                            .collect(Collectors.joining(", ")))));
        }
        String stored = NO_LOT.equals(lot) ? null : lot;
        int packedOfLot = parcels.stream().filter(Parcel::live).flatMap(p -> p.contents.entrySet().stream())
                .filter(e -> e.getKey().orderLineId().equals(line.orderLineId())
                        && Objects.equals(stored, e.getKey().lotNumber()))
                .mapToInt(Map.Entry::getValue).sum();
        if ((long) packedOfLot + quantity > line.lots().get(lot)) {
            throw new BusinessException(ErrorCode.PACK_LOT_MISMATCH, "%d of %s were picked for %s; %d packed already"
                    .formatted(line.lots().get(lot), stored == null ? "the units without a lot" : "lot " + lot,
                            line.sku(), packedOfLot));
        }
        return stored;
    }

    private String checkSerial(Line line, String serialNumber, int quantity) {
        if (line.serials().isEmpty()) {
            if (!blank(serialNumber)) {
                throw new BusinessException(ErrorCode.PACK_SERIAL_MISMATCH,
                        "%s has no serial numbers; scanned %s".formatted(line.sku(), serialNumber.trim()));
            }
            return null;
        }
        if (blank(serialNumber) || quantity != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, line.sku() + " is tracked by serial: scan each unit",
                    List.of(FieldError.of("serialNumber", "required, one unit per scan, for " + line.sku())));
        }
        String serial = line.serials().stream().filter(known -> same(serialNumber, known)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PACK_SERIAL_MISMATCH,
                        "Serial %s was not picked for %s".formatted(serialNumber.trim(), line.sku())));
        boolean packedAlready = parcels.stream().filter(Parcel::live).flatMap(p -> p.contents.keySet().stream())
                .anyMatch(key -> serial.equals(key.serialNumber()));
        if (packedAlready) {
            throw new BusinessException(ErrorCode.PACK_SERIAL_MISMATCH, "Serial %s is packed already".formatted(serial));
        }
        return serial;
    }

    private void record(Scan scan) {
        newScans.add(scan);
        knownScans.add(scan.id());
        changed.add(scan.packageId());
    }

    private Parcel requireParcel(UUID packageId) {
        return parcel(packageId).orElseThrow(() -> new BusinessException(ErrorCode.PACKAGE_NOT_FOUND));
    }

    private Parcel requireOpen(UUID packageId) {
        Parcel parcel = requireParcel(packageId);
        switch (parcel.status) {
            case OPEN -> {
                return parcel;
            }
            case SEALED -> throw new BusinessException(ErrorCode.PACKAGE_SEALED,
                    "Package %d is sealed: re-open it first".formatted(parcel.packageNo));
            default -> throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT,
                    "Package %d was voided".formatted(parcel.packageNo));
        }
    }

    private static void requireNotShipped(Parcel parcel) {
        if (parcel.shipped()) {
            throw new BusinessException(ErrorCode.PACKAGE_SHIPPED,
                    "Package %d is in a shipment already".formatted(parcel.packageNo));
        }
    }

    private static void requireMeasures(Measures measures) {
        List<FieldError> errors = new ArrayList<>();
        if (measures == null) {
            measures = new Measures(null, null, null, null);
        }
        positive(errors, "weightKg", measures.weightKg());
        positive(errors, "lengthCm", measures.lengthCm());
        positive(errors, "widthCm", measures.widthCm());
        positive(errors, "heightCm", measures.heightCm());
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A package is sealed with its real weight and size (08 BR-03)", errors);
        }
    }

    private static void positive(List<FieldError> errors, String field, BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            errors.add(FieldError.of(field, "must be greater than 0"));
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean same(String scanned, String known) {
        return scanned != null && known != null
                && scanned.trim().toUpperCase(Locale.ROOT).equals(known.trim().toUpperCase(Locale.ROOT));
    }
}
