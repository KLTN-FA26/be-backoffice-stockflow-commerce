package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The packing station (G4-5, SCRUM-278/281/282; kltn-docs 08): the queue of picked orders waiting to be packed, the
 * packing session of one task - its parcels, the scans that fill them, sealing with weight and size - and the label
 * of a parcel. Completing packing is {@code FulfillmentService.completePacking}.
 *
 * <p>Inside {@code internal} on purpose, like {@link PickLists}: only this module's web layer calls it.</p>
 *
 * <p>Every write locks the task's pick first, so two packers on one order are serialised and the second sees what the
 * first did; a packer who did not start the session is refused ({@code PACKING_TAKEN}) unless a warehouse manager.
 * Every call is limited to the caller's warehouses ({@code OUT_OF_DATA_SCOPE}).</p>
 */
public interface Packing {

    /**
     * Picked tasks waiting at the packing table, oldest pick first (SCRUM-281): those whose packing is PENDING,
     * PACKING or ON_HOLD, or the one status asked for, and whose order packing may work on (not on hold, except for
     * its design check). Limited to the caller's warehouses and to {@code warehouseId}.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OUT_OF_DATA_SCOPE}; {@code UNSUPPORTED_PARAMETER}
     */
    PageResponse<QueueRow> queue(UUID warehouseId, String status, int page, int size, CurrentUser actor);

    /**
     * A packer takes the order at the table (08 §5 Pending → In Progress); again, it is answered as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PACKING_NOT_READY} until picking is complete;
     *         {@code PACKING_TAKEN}; {@code PACK_STATE_CONFLICT} on hold or cancelled
     */
    Session start(UUID taskId, CurrentUser actor);

    Session session(UUID taskId, CurrentUser actor);

    /** A new empty parcel of {@code type} (null: CARTON) in the session. */
    Parcel openPackage(UUID taskId, String type, CurrentUser actor);

    /**
     * As {@link #openPackage(UUID, String, CurrentUser)}, with the packer's reason for packing an item that ships as
     * several boxes in it rather than in a kit (KIT-FORCE); kept on the package and in the audit trail.
     */
    Parcel openPackage(UUID taskId, String type, String kitOverrideReason, CurrentUser actor);

    /**
     * "Đóng bộ" (SCRUM-287 LP2-5/LP2-6): opens the N parcels of a kit for one unit of an order line whose item ships as
     * N &gt; 1 boxes ({@code package_count}, read from inventory). Part 1 takes the unit by the usual scan; parts 2..N
     * take no goods. Each part is then sealed with its own weight and size by {@link #seal}; voiding any part voids
     * the kit; completing is refused while a kit has a part not sealed ({@code KIT_INCOMPLETE}).
     *
     * @throws com.stockflow.common.error.BusinessException {@code KIT_PART_COUNT_MISMATCH} for an item that ships as one
     *         box, or when {@code partCount} is given and is not the item's; {@code PACK_ITEM_NOT_IN_ORDER};
     *         {@code PACK_QUANTITY_EXCEEDS_ORDER} when the line is packed in full
     */
    Kit openKit(UUID taskId, OpenKit request, CurrentUser actor);

    /**
     * One scan into an open parcel (08 step 2): SKU or barcode, lot or serial when the goods have one, quantity.
     * Again with the same {@code scanId}, nothing changes.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PACK_ITEM_NOT_IN_ORDER},
     *         {@code PACK_SCAN_AMBIGUOUS}, {@code PACK_QUANTITY_EXCEEDS_ORDER}, {@code PACK_QUANTITY_EXCEEDS_PICKED},
     *         {@code PACK_LOT_MISMATCH}, {@code PACK_SERIAL_MISMATCH}, {@code PACKAGE_SEALED},
     *         {@code PACKAGE_NOT_FOUND}, {@code VALIDATION_FAILED} (a lot or serial the goods need, or more than
     *         {@code PackingSession.MAX_UNITS_PER_SCAN}); {@code PACK_SCAN_ID_CONFLICT} for a scan id recorded on another
     *         package or task. A recorded scan id is answered first, whatever the session's state now
     */
    Session scan(UUID taskId, UUID packageId, Scan scan, CurrentUser actor);

    /**
     * Takes units of a line out of an open parcel again. Again with the same {@code scanId}, nothing changes (null: a
     * new one).
     *
     * @throws com.stockflow.common.error.BusinessException {@code PACK_SCAN_ID_CONFLICT} for a scan id recorded on
     *         another package or task
     */
    Session removeUnits(UUID taskId, UUID packageId, UUID orderLineId, String lotNumber, String serialNumber,
                        int quantity, UUID scanId, CurrentUser actor);

    /**
     * Weighed, measured and sealed (08 step 5-7): the rotatable and fragile flags come from its SKUs unless given.
     *
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a missing measure;
     *         {@code PACKAGE_EMPTY}; {@code PACKAGE_SEALED}
     */
    Parcel seal(UUID taskId, UUID packageId, Seal seal, CurrentUser actor);

    /** Opened again before it shipped. @throws com.stockflow.common.error.BusinessException {@code PACKAGE_SHIPPED} */
    Parcel reopen(UUID taskId, UUID packageId, CurrentUser actor);

    /** Taken apart before it shipped: its goods count as unpacked again, its label is void (08 BR-05/06). */
    Session voidPackage(UUID taskId, UUID packageId, CurrentUser actor);

    /**
     * Units a pick line brought to the bench are found damaged at packing - broken in the tote, or seen when packed -
     * and reported with photos (BR-STK-003), by the session's packer or a manager. They must be out of every parcel.
     * In one transaction, pick first, then order, then stock: inventory takes them back into stock as DAMAGED in a
     * quarantine area and allocates the same quantity again, FEFO, or, when there is none, keeps the share impaired on
     * the damaged units; the line keeps them on record and packing no longer counts them; the task goes back to
     * PICKING, for its assignee to pick the new lines - or, with nothing to replace them, for the coordinator to
     * reallocate the new SHORT line, the order ON_HOLD (INVENTORY) meanwhile (X-SHORT). Packing resumes when the task
     * is picked again. The same report again answers with what it made.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code PACKING_NOT_READY}, {@code PACK_STATE_CONFLICT}; {@code PICK_LINE_NOT_OPEN} unless the line was
     *         picked; {@code VALIDATION_FAILED} for more units than it brought; {@code PACK_DAMAGED_UNITS_PACKED};
     *         {@code DAMAGE_PHOTO_REQUIRED}, {@code UNSUPPORTED_MEDIA_TYPE}; {@code DAMAGE_AREA_MISSING} and the other
     *         refusals of inventory's report; {@code CONFLICT} for a request id another report used
     */
    PickLists.PickDamageView reportDamageAfterPick(UUID taskId, UUID lineId, PackDamage damage,
                                                   java.util.List<com.stockflow.common.storage.FileUpload> photos,
                                                   CurrentUser actor);

    /**
     * What the internal label of a sealed parcel prints (08 step 6): order, shipment, package n of N, recipient and
     * address, the warehouse to return to, the COD amount (09 BR-04), weight, size and the fragile / rotatable flags.
     * Before the parcel is in a shipment, n/N and the COD amount are {@code provisional}: they count the sealed parcels
     * that would ship together now.
     */
    Label label(UUID taskId, UUID packageId, CurrentUser actor);

    // ------------------------------------------------------------------ views

    /** @param kitOverrideReason KIT-FORCE: why an item that ships as several boxes goes in this ordinary package */
    record Scan(UUID scanId, String scannedCode, String lotNumber, String serialNumber, int quantity,
                UUID orderLineId, String kitOverrideReason) {
        public Scan(UUID scanId, String scannedCode, String lotNumber, String serialNumber, int quantity,
                    UUID orderLineId) {
            this(scanId, scannedCode, lotNumber, serialNumber, quantity, orderLineId, null);
        }
    }

    /**
     * What the packer reports.
     *
     * @param requestId          generated by the client once per report, sent again on a retry
     * @param damageLocationCode the quarantine area the units are taken to; null lets the system choose
     */
    record PackDamage(UUID requestId, int damagedQuantity,
                      com.stockflow.inventory.consumption.StockDamageReason reason, String note,
                      String damageLocationCode) { }

    /** @param partCount what the client expects the kit's size to be; null: the item's, whatever it is */
    record OpenKit(UUID orderLineId, Integer partCount, String packageType) { }

    /** The parts of a kit just opened, part 1 first. */
    record Kit(UUID kitGroupId, UUID orderLineId, String sku, int partCount, List<Parcel> packages) { }

    record Seal(BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, String packageType,
                Boolean rotatable, Boolean fragile, String kitOverrideReason) {
        public Seal(BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                    String packageType, Boolean rotatable, Boolean fragile) {
            this(weightKg, lengthCm, widthCm, heightCm, packageType, rotatable, fragile, null);
        }
    }

    record QueueRow(UUID taskId, UUID orderId, String orderNumber, UUID warehouseId, String packStatus,
                    Instant pickedAt, String pickerName, UUID packerId, String packerName, Instant packingStartedAt,
                    int packageCount, int sealedPackageCount, int shipmentCount) { }

    /**
     * @param wanted    what the order still wants of the line
     * @param available what reached the packing area for it
     * @param packed    in parcels not voided
     * @param shipped   in parcels already in a shipment
     * @param remaining still to pack: {@code wanted - packed}
     * @param packageCount boxes one unit of its item ships as: more than one, a unit is packed as a kit (LP2-5)
     * @param kitSuggested {@code packageCount} above one: "đóng bộ" is the default (KIT-FORCE); an ordinary package
     *                     needs a reason
     */
    record Line(UUID orderLineId, String sku, String barcode, boolean printLine, UUID designSnapshotId, int wanted,
                int available, int packed, int shipped, int remaining, boolean lotTracked, boolean serialTracked,
                List<Lot> lots, int packageCount, boolean kitSuggested) { }

    record Lot(String lotNumber, int available, int packed) { }

    record Content(UUID orderLineId, String sku, String lotNumber, String serialNumber, int quantity) { }

    record Parcel(UUID packageId, int packageNo, String labelCode, String packageType, String status, UUID shipmentId,
                  BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                  BigDecimal dimWeightKg, boolean rotatable, boolean rotatableOverridden, boolean fragile,
                  boolean fragileOverridden, Instant sealedAt, List<Content> contents,
                  com.stockflow.fulfillment.internal.domain.KitPart kit, String kitOverrideReason,
                  Instant labelOutdatedAt) { }

    record ShipmentSummary(UUID shipmentId, String code, String status, String deliveryMode, int packageCount,
                           BigDecimal codAmount, String currency, boolean partial, String partialNote,
                           Instant readyAt) { }

    /** Money the order must receive before its goods may be handed over (G3-Q4): the packer's early warning. */
    record PaymentDue(BigDecimal amountDueBeforeHandover, String currency) { }

    record Session(UUID taskId, UUID orderId, String orderNumber, UUID warehouseId, String packStatus, UUID packerId,
                   String packerName, Instant startedAt, Instant packedAt, List<Line> lines, List<Parcel> packages,
                   List<ShipmentSummary> shipments, PaymentDue paymentDue) { }

    record Address(String recipientName, String phone, String line1, String line2, String wardName,
                   String provinceName, String countryCode, String postalCode) { }

    record Label(String labelCode, UUID packageId, String orderNumber, UUID shipmentId, String shipmentCode,
                 int packageNo, int packageCount, boolean provisional, BigDecimal codAmount, String currency,
                 Address recipient, String returnWarehouseName, String returnAddress, String packageType,
                 BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                 BigDecimal dimWeightKg, boolean fragile, boolean rotatable, List<Content> contents,
                 com.stockflow.fulfillment.internal.domain.KitPart kit) { }
}
