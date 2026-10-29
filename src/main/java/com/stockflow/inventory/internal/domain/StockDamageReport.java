package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.StockDamageDisposed;
import com.stockflow.contracts.StockDamageReported;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>Damaged stock, reported and decided</b> (docs 03 §5 AVAILABLE → DAMAGED → written off; BR-STK-003;
 * SCRUM-475, SCRUM-277): units of one stock row somebody found damaged, the evidence, and what a warehouse manager
 * decided for them.
 *
 * <p>The record is what the business keeps on paper as the damage record and, for what is destroyed, the
 * destruction record: what, how many, why, who saw it, photos, who decided, when, under which number. It is the
 * document behind the stock movements, not the stock itself — the units moved to their DAMAGED row in the same
 * transaction that created the report ({@link StockItem#giveUpDamaged}), and every decision moves them again in
 * the transaction that records it.</p>
 *
 * <p>The rules it keeps, each said a second time by the tables of V20261028030000:</p>
 * <ul>
 *   <li>at least one photo, at most {@value #MAX_PHOTOS} (BR-STK-003; {@code tg_stock_damage_report_photo});</li>
 *   <li>a reason, and a note when the reason is OTHER;</li>
 *   <li>one serial number is one unit;</li>
 *   <li>a picker's report names the pick line, the order and the allocation; a stock check names none;</li>
 *   <li>the person who reported is never the person who decides (four eyes), and no more units are decided than
 *       were reported; a decided unit is never decided again ({@code tg_stock_damage_disposition_check}).</li>
 * </ul>
 */
public final class StockDamageReport extends AggregateRoot {

    /** Enough to show the damage from a few angles; a request of phone photos stays well under the upload limit. */
    public static final int MAX_PHOTOS = 5;

    /** Who found the damage, and where. */
    public enum Origin {
        /** Anyone of the warehouse, on a shelf: free units only (held ones are reported where they are picked). */
        STOCK_CHECK,
        /** A picker at a pick line's bin, among the units the line's allocation holds (SCRUM-277). */
        PICKING,
        /**
         * The packing bench, among units already picked: broken in the tote or found damaged when packed. They were
         * deducted when picked and come back into stock, DAMAGED, in quarantine.
         */
        PACKING
    }

    /** The goods the report is about, as their stock row knew them when the damage was found. */
    public record DamagedGoods(StockItemId sourceStockItem, Sku sku, String lotNumber, String serialNumber,
                               Instant receivedAt, UUID lotId, LocationId sourceLocation,
                               StockPackageId sourceStockPackage, UUID warehouseId) {
        public DamagedGoods {
            Objects.requireNonNull(sourceStockItem, "sourceStockItem");
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(sourceLocation, "sourceLocation");
            Objects.requireNonNull(warehouseId, "warehouseId");
        }

        /** The goods of {@code row}, in warehouse {@code warehouseId}. */
        public static DamagedGoods of(StockItem row, UUID warehouseId) {
            return new DamagedGoods(row.id(), row.sku(), row.lotNumber(), row.serialNumber(), row.receivedAt(),
                    row.lotId(), row.location(), row.stockPackageId(), warehouseId);
        }
    }

    /**
     * What a picker's report adds: the order, its pick line, the allocation the damaged units were picked against,
     * and — when no other stock could replace them — the share of that allocation left on the damaged units.
     */
    public record PickContext(UUID orderId, UUID pickLineId, UUID reservationId, UUID impairedReservationId) {
        public PickContext {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(pickLineId, "pickLineId");
            Objects.requireNonNull(reservationId, "reservationId");
        }
    }

    /** One photograph of the damage, already in storage. */
    public record Photo(UUID id, String storageKey, String originalName, String contentType, long sizeBytes,
                        String checksum) {
        public Photo {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(storageKey, "storageKey");
            Objects.requireNonNull(originalName, "originalName");
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(checksum, "checksum");
            if (sizeBytes <= 0) {
                throw new IllegalArgumentException("A photo has content");
            }
        }
    }

    /**
     * One decision on some of the units. Immutable: a decision is corrected by another document, never edited.
     *
     * @param recordNumber      the destruction record of a {@link DamageDisposition#WRITE_OFF}; null otherwise
     * @param adjustmentId      the posted stock adjustment of a write-off; null otherwise
     * @param restoreLocation   where restored goods went back onto; {@link DamageDisposition#RESTORE} only
     * @param restoredStockItem the row that took them; {@link DamageDisposition#RESTORE} only
     * @param supplierId        the supplier the goods go back to, when known; {@link DamageDisposition#RETURN_TO_SUPPLIER} only
     */
    public record Disposition(UUID id, UUID requestId, DamageDisposition type, Quantity quantity, String note,
                              String recordNumber, UUID adjustmentId, LocationId restoreLocation,
                              StockItemId restoredStockItem, UUID supplierId, String supplierName, UUID decidedBy,
                              Instant decidedAt) {
        public Disposition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(decidedBy, "decidedBy");
            Objects.requireNonNull(decidedAt, "decidedAt");
            note = blankToNull(note);
            if (quantity.isZero()) {
                throw new IllegalArgumentException("A decision covers at least one unit");
            }
            if ((type == DamageDisposition.WRITE_OFF) != (recordNumber != null && adjustmentId != null)
                    || (type == DamageDisposition.RESTORE) != (restoreLocation != null && restoredStockItem != null)
                    || type != DamageDisposition.RETURN_TO_SUPPLIER && (supplierId != null || supplierName != null)) {
                throw new IllegalArgumentException("A " + type + " decision carries the facts of its own kind only");
            }
        }
    }

    /**
     * What became of some units of a {@link DamageDisposition#RETURN_TO_SUPPLIER} decision: handed to the supplier, or
     * the supplier's carrier, under {@code deliveryNote} - they left the books - or refused by the supplier
     * ({@code refused}, with a note saying so): those are the report's to decide again. A decision is carried out in
     * one or several of these, never for more units than it named. Immutable.
     */
    public record SupplierShipment(UUID id, UUID requestId, UUID dispositionId, Quantity quantity, String deliveryNote,
                                   String note, UUID shippedBy, Instant shippedAt, boolean refused) {
        public SupplierShipment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(dispositionId, "dispositionId");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(shippedBy, "shippedBy");
            Objects.requireNonNull(shippedAt, "shippedAt");
            deliveryNote = blankToNull(deliveryNote);
            note = blankToNull(note);
            if (quantity.isZero()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "At least one unit");
            }
            if (!refused && deliveryNote == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Goods go back to the supplier under a delivery note: give its number");
            }
            if (refused && (deliveryNote != null || note == null)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "A supplier's refusal says why in the note, and carries no delivery note");
            }
        }

        /** Handed over (not refused). */
        public SupplierShipment(UUID id, UUID requestId, UUID dispositionId, Quantity quantity, String deliveryNote,
                                String note, UUID shippedBy, Instant shippedAt) {
            this(id, requestId, dispositionId, quantity, deliveryNote, note, shippedBy, shippedAt, false);
        }
    }

    private final UUID id;
    private final String number;
    private final UUID requestId;
    private final Origin origin;
    private final DamagedGoods goods;
    private final StockItemId damagedStockItem;
    private final LocationId damageLocation;
    private final Quantity quantity;
    private final DamageReason reason;
    private final String note;
    private final List<Photo> photos;
    private final PickContext pick;
    private final UUID reportedBy;
    private final Instant reportedAt;
    private final List<Disposition> dispositions;
    private final List<SupplierShipment> shipments;
    private final long version;

    /** As stored, before anything went back to a supplier. */
    public StockDamageReport(UUID id, String number, UUID requestId, Origin origin, DamagedGoods goods,
                             StockItemId damagedStockItem, LocationId damageLocation, Quantity quantity,
                             DamageReason reason, String note, List<Photo> photos, PickContext pick, UUID reportedBy,
                             Instant reportedAt, List<Disposition> dispositions, long version) {
        this(id, number, requestId, origin, goods, damagedStockItem, damageLocation, quantity, reason, note, photos,
                pick, reportedBy, reportedAt, dispositions, List.of(), version);
    }

    /** As stored. Every rule is checked again, so a row a bad migration corrupted cannot be loaded as a report. */
    public StockDamageReport(UUID id, String number, UUID requestId, Origin origin, DamagedGoods goods,
                             StockItemId damagedStockItem, LocationId damageLocation, Quantity quantity,
                             DamageReason reason, String note, List<Photo> photos, PickContext pick, UUID reportedBy,
                             Instant reportedAt, List<Disposition> dispositions, List<SupplierShipment> shipments,
                             long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.number = Objects.requireNonNull(number, "number");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.goods = Objects.requireNonNull(goods, "goods");
        this.damagedStockItem = Objects.requireNonNull(damagedStockItem, "damagedStockItem");
        this.damageLocation = Objects.requireNonNull(damageLocation, "damageLocation");
        this.quantity = Objects.requireNonNull(quantity, "quantity");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.note = blankToNull(note);
        this.photos = List.copyOf(photos == null ? List.of() : photos);
        this.pick = pick;
        this.reportedBy = Objects.requireNonNull(reportedBy, "reportedBy");
        this.reportedAt = Objects.requireNonNull(reportedAt, "reportedAt");
        this.dispositions = new ArrayList<>(dispositions == null ? List.of() : dispositions);
        this.shipments = new ArrayList<>(shipments == null ? List.of() : shipments);
        this.version = version;
        checkInvariants();
    }

    /**
     * A new report of units already moved to their DAMAGED row ({@code damagedStockItem} at {@code damageLocation}).
     * Records {@code StockDamageReported}.
     *
     * @param skuReservationShortfall what the SKU's live checkout reservations now promise beyond its free sellable
     *                                stock, for the event: the damage cannot break those soft promises silently
     * @throws BusinessException {@code DAMAGE_PHOTO_REQUIRED} with no photo (BR-STK-003); {@code VALIDATION_FAILED}
     *         for too many photos, or OTHER without a note
     */
    public static StockDamageReport report(String number, UUID requestId, DamagedGoods goods,
                                           StockItemId damagedStockItem, LocationId damageLocation, Quantity quantity,
                                           DamageReason reason, String note, List<Photo> photos, PickContext pick,
                                           UUID reportedBy, Instant now, int skuReservationShortfall) {
        return report(number, requestId, pick == null ? Origin.STOCK_CHECK : Origin.PICKING, goods, damagedStockItem,
                damageLocation, quantity, reason, note, photos, pick, reportedBy, now, skuReservationShortfall);
    }

    /** The same, found at the packing bench among units already picked ({@link Origin#PACKING}). */
    public static StockDamageReport reportAfterPick(String number, UUID requestId, DamagedGoods goods,
                                                    StockItemId damagedStockItem, LocationId damageLocation,
                                                    Quantity quantity, DamageReason reason, String note,
                                                    List<Photo> photos, PickContext pick, UUID reportedBy, Instant now,
                                                    int skuReservationShortfall) {
        return report(number, requestId, Origin.PACKING, goods, damagedStockItem, damageLocation, quantity, reason,
                note, photos, Objects.requireNonNull(pick, "pick"), reportedBy, now, skuReservationShortfall);
    }

    private static StockDamageReport report(String number, UUID requestId, Origin origin, DamagedGoods goods,
                                            StockItemId damagedStockItem, LocationId damageLocation, Quantity quantity,
                                            DamageReason reason, String note, List<Photo> photos, PickContext pick,
                                            UUID reportedBy, Instant now, int skuReservationShortfall) {
        StockDamageReport report = new StockDamageReport(Identifiers.newId(), number, requestId, origin, goods,
                damagedStockItem, damageLocation, quantity, reason, note, photos, pick, reportedBy, now, List.of(),
                0L);
        report.registerEvent(new StockEvent.DamageReported(new StockDamageReported(report.id, number,
                report.origin.name(), goods.warehouseId(), goods.sku().code(), goods.lotNumber(),
                goods.sourceLocation().code(), damageLocation.code(), quantity.value(), reason.name(),
                pick == null ? null : pick.orderId(), pick != null && pick.impairedReservationId() != null,
                Math.max(0, skuReservationShortfall))));
        return report;
    }

    /**
     * The reason a report must give, checked before anything is stored or moved; {@link #report} checks it again.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} without a reason, or OTHER without a note
     */
    public static void requireExplained(DamageReason reason, String note) {
        if (reason == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A damage report gives a reason");
        }
        if (reason.needsNote() && blankToNull(note) == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A damage report with reason OTHER must say what in the note");
        }
    }

    /**
     * Before any stock moves for a decision: is it one this person may take, for this many units, with what it must
     * say? The same checks run again when the decision is recorded.
     *
     * @throws BusinessException {@code DAMAGE_SELF_DISPOSITION} for the reporter (four eyes);
     *         {@code DAMAGE_DISPOSITION_EXCEEDS_PENDING} beyond the units still waiting for a decision;
     *         {@code VALIDATION_FAILED} for a restore or a return without a note
     */
    public void requireDecidable(DamageDisposition type, Quantity units, String decisionNote, UUID decidedBy) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(units, "units");
        if (Objects.requireNonNull(decidedBy, "decidedBy").equals(reportedBy)) {
            throw new BusinessException(ErrorCode.DAMAGE_SELF_DISPOSITION,
                    "Damage report %s cannot be decided by the person who reported it".formatted(number));
        }
        if (units.isZero() || pending().isLessThan(units)) {
            throw new BusinessException(ErrorCode.DAMAGE_DISPOSITION_EXCEEDS_PENDING,
                    "Damage report %s has %s unit(s) waiting for a decision; %s asked".formatted(number, pending(),
                            units));
        }
        if (type.needsNote() && blankToNull(decisionNote) == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A %s decision must say why in the note".formatted(type));
        }
    }

    /** Destroyed: a DAMAGED adjustment of the units was posted ({@code adjustmentId}) under {@code recordNumber}. */
    public Disposition writeOff(UUID dispositionRequestId, Quantity units, String decisionNote, UUID decidedBy,
                                String recordNumber, UUID adjustmentId, Instant now) {
        return decide(new Disposition(Identifiers.newId(), dispositionRequestId, DamageDisposition.WRITE_OFF, units,
                decisionNote, Objects.requireNonNull(recordNumber, "recordNumber"),
                Objects.requireNonNull(adjustmentId, "adjustmentId"), null, null, null, null, decidedBy, now));
    }

    /** Going back to the supplier ({@code supplierId} null when the lot does not say who): intent only. */
    public Disposition returnToSupplier(UUID dispositionRequestId, Quantity units, String decisionNote,
                                        UUID decidedBy, UUID supplierId, String supplierName, Instant now) {
        return decide(new Disposition(Identifiers.newId(), dispositionRequestId, DamageDisposition.RETURN_TO_SUPPLIER,
                units, decisionNote, null, null, null, null, supplierId, supplierName, decidedBy, now));
    }

    /** Found fine on re-inspection: the units went back onto {@code location}, into {@code restoredStockItem}. */
    public Disposition restore(UUID dispositionRequestId, Quantity units, String decisionNote, UUID decidedBy,
                               LocationId location, StockItemId restoredStockItem, Instant now) {
        return decide(new Disposition(Identifiers.newId(), dispositionRequestId, DamageDisposition.RESTORE, units,
                decisionNote, null, null, Objects.requireNonNull(location, "location"),
                Objects.requireNonNull(restoredStockItem, "restoredStockItem"), null, null, decidedBy, now));
    }

    private Disposition decide(Disposition disposition) {
        requireDecidable(disposition.type(), disposition.quantity(), disposition.note(), disposition.decidedBy());
        if (dispositionFor(disposition.requestId()).isPresent()) {
            throw new IllegalStateException("Request %s already decided report %s".formatted(disposition.requestId(),
                    number));
        }
        dispositions.add(disposition);
        checkInvariants();
        registerEvent(new StockEvent.DamageDisposed(new StockDamageDisposed(id, number, disposition.id(),
                disposition.type().name(), disposition.quantity().value(), disposition.recordNumber(),
                goods.warehouseId(), goods.sku().code(), goods.lotNumber(), damageLocation.code(),
                disposition.supplierId(),
                disposition.restoreLocation() == null ? null : disposition.restoreLocation().code())));
        return disposition;
    }

    /**
     * Units of a return-to-supplier decision handed to the supplier under {@code deliveryNote}: {@code units} of them,
     * or all it has left when null - a supplier may take a consignment in parts. The caller takes them off the DAMAGED
     * row in the same transaction.
     *
     * @throws BusinessException {@code NOT_FOUND} for a decision not on this report;
     *         {@code DAMAGE_RETURN_NOT_SHIPPABLE} for a decision that is not a return to the supplier, or more units
     *         than it has left to hand over; {@code VALIDATION_FAILED} without a delivery note
     */
    public SupplierShipment shipToSupplier(UUID shipmentRequestId, UUID dispositionId, Quantity units,
                                           String deliveryNote, String shipmentNote, UUID shippedBy, Instant now) {
        Quantity handed = units == null ? remainingToHandOver(requireShippable(dispositionId).id()) : units;
        requireShippable(dispositionId, handed);
        SupplierShipment shipment = new SupplierShipment(Identifiers.newId(), shipmentRequestId, dispositionId, handed,
                deliveryNote, shipmentNote, shippedBy, now, false);
        shipments.add(shipment);
        checkInvariants();
        return shipment;
    }

    /**
     * The supplier refused {@code units} of a return-to-supplier decision (or took fewer than were sent for): they stay
     * DAMAGED in quarantine and wait for a decision again - written off, restored, or another return. Nothing moves.
     *
     * @throws BusinessException as {@link #shipToSupplier}; {@code VALIDATION_FAILED} without a note
     */
    public SupplierShipment refuseReturn(UUID refusalRequestId, UUID dispositionId, Quantity units, String why,
                                         UUID recordedBy, Instant now) {
        requireShippable(dispositionId, Objects.requireNonNull(units, "units"));
        SupplierShipment refusal = new SupplierShipment(Identifiers.newId(), refusalRequestId, dispositionId, units,
                null, why, recordedBy, now, true);
        shipments.add(refusal);
        checkInvariants();
        return refusal;
    }

    /**
     * The decision whose units are to go back to the supplier, before any stock moves for it.
     *
     * @throws BusinessException as {@link #shipToSupplier}
     */
    public Disposition requireShippable(UUID dispositionId) {
        Disposition decision = dispositions.stream().filter(d -> d.id().equals(dispositionId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Damage report %s has no decision %s".formatted(number, dispositionId)));
        if (decision.type() != DamageDisposition.RETURN_TO_SUPPLIER) {
            throw new BusinessException(ErrorCode.DAMAGE_RETURN_NOT_SHIPPABLE,
                    "Decision %s is %s; only goods decided for the supplier are shipped to it".formatted(dispositionId,
                            decision.type()));
        }
        if (remainingToHandOver(dispositionId).isZero()) {
            throw new BusinessException(ErrorCode.DAMAGE_RETURN_NOT_SHIPPABLE,
                    "Every unit of decision %s went back to the supplier, or was refused, already".formatted(
                            dispositionId));
        }
        return decision;
    }

    /** {@link #requireShippable(UUID)}, for {@code units} of it. */
    public Disposition requireShippable(UUID dispositionId, Quantity units) {
        Disposition decision = requireShippable(dispositionId);
        Quantity left = remainingToHandOver(dispositionId);
        if (units.isZero() || left.isLessThan(units)) {
            throw new BusinessException(ErrorCode.DAMAGE_RETURN_NOT_SHIPPABLE,
                    "Decision %s has %s unit(s) left to hand over; %s asked".formatted(dispositionId, left, units));
        }
        return decision;
    }

    // ------------------------------------------------------------------ queries

    /** The hand-over (or refusal) a retried request already recorded, if any. */
    public Optional<SupplierShipment> shipmentFor(UUID shipmentRequestId) {
        return shipments.stream().filter(s -> s.requestId().equals(shipmentRequestId)).findFirst();
    }

    /** The hand-overs and refusals of a return-to-supplier decision, oldest first. */
    public List<SupplierShipment> shipmentsOf(UUID dispositionId) {
        return shipments.stream().filter(s -> s.dispositionId().equals(dispositionId)).toList();
    }

    /** Units of a decision neither handed to the supplier nor refused by it yet. */
    public Quantity remainingToHandOver(UUID dispositionId) {
        Quantity named = dispositions.stream().filter(d -> d.id().equals(dispositionId))
                .filter(d -> d.type() == DamageDisposition.RETURN_TO_SUPPLIER)
                .map(Disposition::quantity).findFirst().orElse(Quantity.ZERO);
        return named.minus(shipmentsOf(dispositionId).stream().map(SupplierShipment::quantity)
                .reduce(Quantity.ZERO, Quantity::plus));
    }

    /** Units decided for the supplier that are still in quarantine, waiting to be handed over. */
    public Quantity awaitingSupplier() {
        return dispositions.stream().filter(d -> d.type() == DamageDisposition.RETURN_TO_SUPPLIER)
                .map(d -> remainingToHandOver(d.id())).reduce(Quantity.ZERO, Quantity::plus);
    }

    /** Units a manager has decided so far, less those a supplier refused (they wait for a decision again). */
    public Quantity decided() {
        Quantity refused = shipments.stream().filter(SupplierShipment::refused).map(SupplierShipment::quantity)
                .reduce(Quantity.ZERO, Quantity::plus);
        return dispositions.stream().map(Disposition::quantity).reduce(Quantity.ZERO, Quantity::plus).minus(refused);
    }

    /** Units still waiting for a decision. */
    public Quantity pending() {
        return quantity.minus(decided());
    }

    /** Open while some units wait for a decision. */
    public boolean isOpen() {
        return !pending().isZero();
    }

    /** The decision a retried request already made, if any. */
    public Optional<Disposition> dispositionFor(UUID dispositionRequestId) {
        return dispositions.stream().filter(d -> d.requestId().equals(dispositionRequestId)).findFirst();
    }

    // ------------------------------------------------------------------ invariants

    private void checkInvariants() {
        if (quantity.isZero()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A damage report covers at least one unit");
        }
        if (photos.isEmpty()) {
            throw new BusinessException(ErrorCode.DAMAGE_PHOTO_REQUIRED,
                    "Damaged stock is reported with at least one photo (BR-STK-003)");
        }
        if (photos.size() > MAX_PHOTOS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A damage report takes at most %d photos".formatted(MAX_PHOTOS));
        }
        requireExplained(reason, note);
        if (goods.serialNumber() != null && quantity.value() != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Serial number %s is one unit".formatted(goods.serialNumber()));
        }
        if ((origin == Origin.STOCK_CHECK) == (pick != null)) {
            throw new IllegalStateException("A picker's or a packer's report names its pick line; a stock check none");
        }
        if (goods.sourceLocation().equals(damageLocation) || goods.sourceStockItem().equals(damagedStockItem)) {
            throw new IllegalStateException("Damaged goods leave the row and the location they were found at");
        }
        if (quantity.isLessThan(decided())) {
            throw new IllegalStateException("Damage report %s decides more units than it has".formatted(number));
        }
        if (dispositions.stream().anyMatch(d -> d.decidedBy().equals(reportedBy))) {
            throw new IllegalStateException("Damage report %s was decided by its reporter".formatted(number));
        }
        for (SupplierShipment shipment : shipments) {
            Optional<Disposition> decision = dispositions.stream()
                    .filter(d -> d.id().equals(shipment.dispositionId())).findFirst();
            int handedOver = shipmentsOf(shipment.dispositionId()).stream().mapToInt(s -> s.quantity().value()).sum();
            if (decision.isEmpty() || decision.get().type() != DamageDisposition.RETURN_TO_SUPPLIER
                    || handedOver > decision.get().quantity().value()) {
                throw new IllegalStateException("Damage report %s hands over what no return decision sent, or more"
                        .formatted(number));
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID id() { return id; }
    public String number() { return number; }
    public UUID requestId() { return requestId; }
    public Origin origin() { return origin; }
    public DamagedGoods goods() { return goods; }
    public StockItemId damagedStockItem() { return damagedStockItem; }
    public LocationId damageLocation() { return damageLocation; }
    public Quantity quantity() { return quantity; }
    public DamageReason reason() { return reason; }
    public String note() { return note; }
    public List<Photo> photos() { return photos; }
    /** Null for a stock check. */
    public PickContext pick() { return pick; }
    public UUID reportedBy() { return reportedBy; }
    public Instant reportedAt() { return reportedAt; }
    /** Unmodifiable, oldest first: decisions are added through this aggregate only. */
    public List<Disposition> dispositions() { return Collections.unmodifiableList(dispositions); }
    /** Unmodifiable, oldest first. */
    public List<SupplierShipment> shipments() { return Collections.unmodifiableList(shipments); }
    public long version() { return version; }
}
