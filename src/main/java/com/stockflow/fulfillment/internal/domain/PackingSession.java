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
 *   <li><b>Kits</b> (SCRUM-287 LP2-5/LP2-6): a unit whose item ships as N &gt; 1 boxes may be packed as a kit ("đóng
 *       bộ") of N parcels opened together ({@link #openKit}). Part 1 holds the one unit; parts 2..N hold no goods and
 *       carry only the kit code. Every part is weighed and measured on its own; the kit is voided whole, and completing
 *       is refused while a kit has a part not sealed ({@code KIT_INCOMPLETE}), so a kit always ships whole - a partial
 *       shipment never splits one. Units are counted once, from part 1.</li>
 *   <li><b>KIT-FORCE</b> (lead's decision 2026-10-22): a kit is the default for an item that ships as several boxes. Its
 *       units go in an ordinary parcel only with the packer's reason ({@code KIT_OVERRIDE_REASON_REQUIRED}), kept on
 *       the parcel; the kit path needs none.</li>
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
     * @param packageCount how many boxes one unit of its item ships as (inventory {@code package_count}); more than one
     *                  makes a unit of it a kit (LP2-5)
     */
    public record Line(UUID orderLineId, String sku, String barcode, int wanted, int available, boolean printLine,
                       UUID designSnapshotId, Map<String, Integer> lots, Set<String> serials, boolean rotatable,
                       boolean fragile, int packageCount) {
        public Line {
            Objects.requireNonNull(orderLineId, "orderLineId");
            Objects.requireNonNull(sku, "sku");
            lots = lots == null ? Map.of() : Map.copyOf(lots);
            serials = serials == null ? Set.of() : Set.copyOf(serials);
            packageCount = Math.max(1, packageCount);
        }

        /** A line whose item ships as one box. */
        @SuppressWarnings("java:S107")
        public Line(UUID orderLineId, String sku, String barcode, int wanted, int available, boolean printLine,
                    UUID designSnapshotId, Map<String, Integer> lots, Set<String> serials, boolean rotatable,
                    boolean fragile) {
            this(orderLineId, sku, barcode, wanted, available, printLine, designSnapshotId, lots, serials, rotatable,
                    fragile, 1);
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
        /** The kit it is a part of (LP2-6); null for an ordinary parcel. */
        private final KitPart kit;
        /** KIT-FORCE: why this ordinary parcel holds units of an item that ships as several boxes; null otherwise. */
        private String kitOverrideReason;
        /** Its shipment came back (RETURNED): its units are the order's to ship again, so it holds nothing now. */
        private boolean returned;

        @SuppressWarnings("java:S107")
        public Parcel(UUID id, int packageNo, String labelCode, PackageType type, PackageStatus status, UUID shipmentId,
                      Measures measures, BigDecimal dimWeightKg, boolean rotatable, boolean rotatableOverridden,
                      boolean fragile, boolean fragileOverridden, Instant sealedAt, UUID sealedBy, Instant voidedAt,
                      UUID voidedBy, Map<ContentKey, Integer> contents, Instant createdAt) {
            this(id, packageNo, labelCode, type, status, shipmentId, measures, dimWeightKg, rotatable,
                    rotatableOverridden, fragile, fragileOverridden, sealedAt, sealedBy, voidedAt, voidedBy, contents,
                    createdAt, null);
        }

        @SuppressWarnings("java:S107")
        public Parcel(UUID id, int packageNo, String labelCode, PackageType type, PackageStatus status, UUID shipmentId,
                      Measures measures, BigDecimal dimWeightKg, boolean rotatable, boolean rotatableOverridden,
                      boolean fragile, boolean fragileOverridden, Instant sealedAt, UUID sealedBy, Instant voidedAt,
                      UUID voidedBy, Map<ContentKey, Integer> contents, Instant createdAt, KitPart kit) {
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
            this.kit = kit;
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
        int units() { return contents.values().stream().mapToInt(Integer::intValue).sum(); }
        boolean inKit(UUID groupId) { return kit != null && kit.groupId().equals(groupId); }

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
        public KitPart kit() { return kit; }
        public String kitOverrideReason() { return kitOverrideReason; }

        /** Read back from the table: the reason recorded for this ordinary parcel (KIT-FORCE). */
        public Parcel withKitOverrideReason(String reason) {
            this.kitOverrideReason = reason;
            return this;
        }
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
        return openPackage(id, type, null, now);
    }

    /**
     * A new, empty ordinary parcel. {@code kitOverrideReason} (KIT-FORCE): why units of an item that ships as several
     * boxes will go in it rather than in a kit; it may also be given with the scan that puts them in.
     */
    public Parcel openPackage(UUID id, PackageType type, String kitOverrideReason, Instant now) {
        int next = parcels.stream().mapToInt(Parcel::packageNo).max().orElse(0) + 1;
        Parcel parcel = new Parcel(id, next, "PKG-" + orderNumber + "-" + next, type, PackageStatus.OPEN, null, null,
                null, true, false, false, false, null, null, null, null, Map.of(), now);
        parcel.kitOverrideReason = trimmed(kitOverrideReason);
        parcels.add(parcel);
        changed.add(id);
        return parcel;
    }

    /**
     * LP2-6 "đóng bộ": opens the N parcels of a kit for one unit of {@code orderLineId} at once - N is the item's
     * {@code package_count} (LP2-5), numbered after the session's last parcel, sharing {@code groupId}. When the client
     * says how many parts it expects ({@code partCount}), it must be that ({@code KIT_PART_COUNT_MISMATCH}: the item's
     * data changed under it). A line whose item ships as one box has no kit; a line with nothing left to pack none
     * either.
     *
     * @param ids one new parcel id per part, part 1 first
     */
    public List<Parcel> openKit(UUID groupId, List<UUID> ids, UUID orderLineId, Integer partCount, PackageType type,
                                Instant now) {
        Line line = lines.get(orderLineId);
        if (line == null) {
            throw new BusinessException(ErrorCode.PACK_ITEM_NOT_IN_ORDER, "That line is not on order " + orderNumber);
        }
        int parts = line.packageCount();
        if (parts < KitPart.MIN_PARTS || parts > KitPart.MAX_PARTS) {
            throw new BusinessException(ErrorCode.KIT_PART_COUNT_MISMATCH, parts < KitPart.MIN_PARTS
                    ? "%s ships as one box: pack it in an ordinary package".formatted(line.sku())
                    : "%s ships as %d boxes, more than a kit holds (%d)".formatted(line.sku(), parts,
                    KitPart.MAX_PARTS));
        }
        if (partCount != null && partCount != parts) {
            throw new BusinessException(ErrorCode.KIT_PART_COUNT_MISMATCH,
                    "%s ships as %d boxes, not %d".formatted(line.sku(), parts, partCount),
                    List.of(new FieldError("partCount", "must be " + parts, "PackageCount")));
        }
        // A kit is opened for a unit of the line still to pack that has reached packing (review of #155: under
        // PACK-EARLY a print line not produced yet has none). Kits already opened for the line and still empty hold a
        // unit each.
        int held = packedOf(orderLineId) + emptyKits(orderLineId).size();
        if (held >= line.wanted()) {
            throw new BusinessException(ErrorCode.PACK_QUANTITY_EXCEEDS_ORDER,
                    "Line %s wants %d; all of it is packed or has a kit opened".formatted(line.sku(), line.wanted()));
        }
        if (held >= line.available()) {
            throw new BusinessException(ErrorCode.PACK_QUANTITY_EXCEEDS_PICKED, line.available() == 0
                    ? "Nothing of %s has reached packing yet: open its kit once it has".formatted(line.sku())
                    : "%d of %s reached packing; each is packed or has a kit opened".formatted(line.available(),
                    line.sku()));
        }
        if (ids.size() != parts) {
            throw new IllegalArgumentException("one id per part: " + parts);
        }
        int next = parcels.stream().mapToInt(Parcel::packageNo).max().orElse(0) + 1;
        List<Parcel> kit = new ArrayList<>();
        for (int part = 1; part <= parts; part++) {
            UUID id = ids.get(part - 1);
            int no = next + part - 1;
            Parcel parcel = new Parcel(id, no, "PKG-" + orderNumber + "-" + no, type, PackageStatus.OPEN, null, null,
                    null, true, false, false, false, null, null, null, null, Map.of(), now,
                    new KitPart(groupId, part, parts, orderLineId));
            parcels.add(parcel);
            changed.add(id);
            kit.add(parcel);
        }
        return kit;
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
        return scan(scanId, packageId, code, lotNumber, serialNumber, quantity, orderLineId, null, actor, now);
    }

    /**
     * As {@link #scan(UUID, UUID, String, String, String, int, UUID, UUID, Instant)}, with the packer's reason for
     * putting units of an item that ships as several boxes in this ordinary parcel (KIT-FORCE): needed unless the parcel
     * has one already ({@code KIT_OVERRIDE_REASON_REQUIRED}); kept on the parcel.
     */
    @SuppressWarnings("java:S107")
    public boolean scan(UUID scanId, UUID packageId, String code, String lotNumber, String serialNumber, int quantity,
                        UUID orderLineId, String kitOverrideReason, UUID actor, Instant now) {
        Objects.requireNonNull(scanId, "scanId");
        if (knownScans.contains(scanId)) {
            return false;
        }
        requireUnits(quantity);
        Parcel parcel = requireOpen(packageId);
        Line line = lineFor(code, orderLineId == null ? kitLineScanned(parcel, code) : orderLineId);
        requireKitRoom(parcel, line, quantity);
        String override = kitOverride(parcel, List.of(line), kitOverrideReason);
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
        parcel.kitOverrideReason = override;
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
        return seal(packageId, measures, rotatable, fragile, type, null, dimDivisor, actor, now);
    }

    /**
     * As {@link #seal(UUID, Measures, Boolean, Boolean, PackageType, int, UUID, Instant)}; an ordinary parcel holding
     * units of an item that ships as several boxes is sealed only with a reason, its own or {@code kitOverrideReason}
     * (KIT-FORCE) - goods put in before the rule have none yet.
     */
    @SuppressWarnings("java:S107")
    public Parcel seal(UUID packageId, Measures measures, Boolean rotatable, Boolean fragile, PackageType type,
                       String kitOverrideReason, int dimDivisor, UUID actor, Instant now) {
        Parcel parcel = requireParcel(packageId);
        if (parcel.status == PackageStatus.SEALED) {
            throw new BusinessException(ErrorCode.PACKAGE_SEALED, "Package %d is sealed".formatted(parcel.packageNo));
        }
        requireOpen(packageId);
        // A kit's parts 2..N hold nothing of their own: their flags are those of the unit in part 1.
        Parcel holder = parcel.kit == null ? parcel : kitHolder(parcel);
        if (holder.isEmpty()) {
            throw new BusinessException(ErrorCode.PACKAGE_EMPTY, parcel == holder
                    ? "Package %d has nothing in it".formatted(parcel.packageNo)
                    : "Kit part 1 (package %d) holds nothing yet: scan the unit into it first".formatted(
                    holder.packageNo));
        }
        requireMeasures(measures);
        String override = kitOverride(parcel, holder.lineTotals().keySet().stream().map(lines::get)
                .filter(Objects::nonNull).toList(), kitOverrideReason);
        parcel.kitOverrideReason = override;
        boolean defaultRotatable = holder.lineTotals().keySet().stream().map(lines::get).filter(Objects::nonNull)
                .allMatch(Line::rotatable);
        boolean defaultFragile = holder.lineTotals().keySet().stream().map(lines::get).filter(Objects::nonNull)
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

    /**
     * Taken apart before it shipped: its goods count as unpacked again; its label is void (08 BR-05). A kit is taken
     * apart whole: voiding one of its parts voids them all (LP2-6), so no kit is left with a part missing.
     */
    public Parcel voidPackage(UUID packageId, UUID actor, Instant now) {
        Parcel parcel = requireParcel(packageId);
        if (parcel.status == PackageStatus.VOIDED) {
            return parcel;
        }
        requireNotShipped(parcel);
        if (parcel.kit != null) {
            List<Parcel> kit = parcels.stream().filter(p -> p.inKit(parcel.kit.groupId())
                    && p.status != PackageStatus.VOIDED).toList();
            kit.forEach(PackingSession::requireNotShipped);
            kit.forEach(part -> voidOne(part, actor, now));
            return parcel;
        }
        return voidOne(parcel, actor, now);
    }

    private Parcel voidOne(Parcel parcel, UUID actor, Instant now) {
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
        requireWholeKits();
        List<Parcel> open = parcels.stream().filter(p -> p.status == PackageStatus.OPEN && !p.isEmpty()).toList();
        if (!open.isEmpty()) {
            throw new BusinessException(ErrorCode.PACKAGE_NOT_SEALED, ("Package(s) %s still open: weigh, measure and "
                    + "seal them, or take them apart").formatted(open.stream().map(p -> String.valueOf(p.packageNo))
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
        parcels.stream().filter(p -> p.status == PackageStatus.OPEN && p.isEmpty() && p.kit == null)
                .forEach(p -> voidOne(p, actor, now));
        // A kit opened and never used - nothing in part 1, nothing sealed - goes like an empty box (review of #155).
        emptyKits(null).forEach(kit -> kit.forEach(p -> voidOne(p, actor, now)));
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

    /**
     * A transfer's packing completed (SCRUM-287 PR T, 10 step 5): its sealed parcels stay with the transfer - no
     * shipment is made (a parcel is of a shipment or of a transfer) - and the session is PACKED. Call after
     * {@link #complete}, which checked the kits and the parcels.
     */
    public void finishTransfer(UUID actor, Instant now) {
        status = PackStatus.PACKED;
        packedAt = now;
        packedBy = actor;
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
            voidOne(p, actor, now);
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

    /**
     * The kit's own line when the code scanned into its part 1 is that line's item: a code on two lines (a plain and a
     * printed one of one SKU) then means the line the kit was opened for. Null otherwise - the code decides.
     */
    private UUID kitLineScanned(Parcel parcel, String code) {
        if (parcel.kit == null || !parcel.kit.holdsGoods()) {
            return null;
        }
        Line own = lines.get(parcel.kit.orderLineId());
        return own != null && (same(code, own.sku()) || same(code, own.barcode())) ? own.orderLineId() : null;
    }

    /**
     * LP2-6: a kit's parts 2..N take no goods; part 1 takes one unit of the line the kit was opened for, and no other
     * item - even one that ships as as many boxes ({@code KIT_ITEM_MISMATCH}, review of #155).
     */
    private static void requireKitRoom(Parcel parcel, Line line, int quantity) {
        KitPart kit = parcel.kit;
        if (kit == null) {
            return;
        }
        if (!kit.holdsGoods()) {
            throw new BusinessException(ErrorCode.KIT_CONTENTS_INVALID, ("Package %d is part %d of %d of a kit: it "
                    + "holds no goods, part 1 holds the unit").formatted(parcel.packageNo, kit.partNo(),
                    kit.partCount()));
        }
        if (!line.orderLineId().equals(kit.orderLineId())) {
            throw new BusinessException(ErrorCode.KIT_ITEM_MISMATCH, ("Kit part 1 (package %d) was opened for another "
                    + "line than %s: scan its own item, or open a kit for %s").formatted(parcel.packageNo, line.sku(),
                    line.sku()));
        }
        if ((long) parcel.units() + quantity > 1) {
            throw new BusinessException(ErrorCode.KIT_CONTENTS_INVALID, ("Kit part 1 (package %d) holds one unit: open "
                    + "another kit for the next one").formatted(parcel.packageNo),
                    List.of(new FieldError("quantity", "at most " + (1 - parcel.units()), "KitUnit")));
        }
    }

    /**
     * KIT-FORCE: the reason an ordinary parcel holding units of {@code goods} keeps. None needed for a kit part or for
     * goods that ship as one box; otherwise the parcel's own, or {@code given}, or {@code KIT_OVERRIDE_REASON_REQUIRED}.
     */
    private static String kitOverride(Parcel parcel, List<Line> goods, String given) {
        if (parcel.kit != null) {
            return parcel.kitOverrideReason;
        }
        List<Line> kitItems = goods.stream().filter(line -> line.packageCount() >= KitPart.MIN_PARTS).toList();
        String reason = trimmed(given) != null ? trimmed(given) : parcel.kitOverrideReason;
        if (!kitItems.isEmpty() && reason == null) {
            Line first = kitItems.get(0);
            throw new BusinessException(ErrorCode.KIT_OVERRIDE_REASON_REQUIRED, ("%s ships as %d boxes: pack it as a "
                    + "kit, or say why it goes in an ordinary package").formatted(first.sku(), first.packageCount()),
                    List.of(new FieldError("kitOverrideReason", "required for " + first.sku(), "KitOverride")));
        }
        return kitItems.isEmpty() && trimmed(given) == null ? parcel.kitOverrideReason : reason;
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Part 1 of {@code part}'s kit: the parcel that holds its unit. */
    private Parcel kitHolder(Parcel part) {
        return parcels.stream().filter(p -> p.inKit(part.kit.groupId()) && p.kit.holdsGoods()).findFirst()
                .orElseThrow(() -> new IllegalStateException("kit " + part.kit.groupId() + " has no part 1"));
    }

    /** Kits not voided and not shipped, by group, parts in order. */
    private Map<UUID, List<Parcel>> openKits() {
        Map<UUID, List<Parcel>> kits = new LinkedHashMap<>();
        parcels.stream().filter(p -> p.kit != null && p.status != PackageStatus.VOIDED && !p.shipped())
                .forEach(p -> kits.computeIfAbsent(p.kit.groupId(), id -> new ArrayList<>()).add(p));
        return kits;
    }

    /**
     * Kits opened and not used yet - no unit in part 1 and no part sealed - of {@code orderLineId} (null: of any line).
     * Each holds a unit of its line when another kit is opened; completing voids them like empty boxes.
     */
    private List<List<Parcel>> emptyKits(UUID orderLineId) {
        return openKits().values().stream()
                .filter(parts -> orderLineId == null || parts.get(0).kit.orderLineId().equals(orderLineId))
                .filter(parts -> parts.stream().allMatch(p -> p.isEmpty() && p.status == PackageStatus.OPEN))
                .toList();
    }

    /**
     * Every kit not yet shipped is whole before anything ships (LP2-6): all its parts sealed, part 1 with its unit.
     * Refused with {@code KIT_INCOMPLETE}, one field error per kit naming the parts still missing. A kit not used at
     * all is not in the way: completing voids it.
     */
    private void requireWholeKits() {
        Map<UUID, List<Parcel>> kits = openKits();
        emptyKits(null).forEach(parts -> kits.remove(parts.get(0).kit.groupId()));
        List<FieldError> problems = new ArrayList<>();
        List<String> described = new ArrayList<>();
        kits.forEach((groupId, parts) -> {
            int count = parts.get(0).kit.partCount();
            List<String> missing = new ArrayList<>();
            for (int no = 1; no <= count; no++) {
                int partNo = no;
                Parcel part = parts.stream().filter(p -> p.kit.partNo() == partNo).findFirst().orElse(null);
                if (part == null) {
                    missing.add("part %d/%d (missing)".formatted(partNo, count));
                } else if (part.status != PackageStatus.SEALED) {
                    missing.add("part %d/%d %s (%s)".formatted(partNo, count, part.labelCode,
                            part.kit.holdsGoods() && part.isEmpty() ? "no unit scanned" : "not sealed"));
                }
            }
            if (!missing.isEmpty()) {
                problems.add(new FieldError("kits[" + groupId + "]", String.join(", ", missing), "KitIncomplete"));
                described.add(String.join(", ", missing));
            }
        });
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.KIT_INCOMPLETE, "A kit ships whole: seal every part first - "
                    + String.join("; ", described), problems);
        }
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
