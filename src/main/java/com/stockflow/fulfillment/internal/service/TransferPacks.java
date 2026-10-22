package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Packing a transfer order at the source warehouse (SCRUM-287 PR T; kltn-docs 10 §4 step 5, 08 step 5-9, BR-01,
 * BR-03; lead's decisions LP-9, LP2-6, LP2-8). The same station as an order's ({@code PackingSession}: scan to verify,
 * kits, real weight and size at sealing, KIT-FORCE), with the transfer's lines in place of an order's:
 *
 * <ul>
 *   <li>Opening a session starts the transfer's picking (APPROVED → PICKING, 10 §5); one PICKING already is packed as
 *       it is. Only a FULL_TRUCKLOAD transfer is packed for a load plan ({@code TRANSFER_NOT_FULL_TRUCKLOAD}).</li>
 *   <li>An unopened stock carton of the transfer's goods may ship as it is (LP2-8): its parcel is opened holding the
 *       carton's units, with the carton's packing-unit size and weight suggested; the packer still seals it with what
 *       the scale and the tape say.</li>
 *   <li>Completing marks the transfer packed - ready for a load plan; nothing leaves stock until its plan's last
 *       vehicle signs. A transfer that ships less than requested says why ({@code partial}, {@code note}).</li>
 * </ul>
 *
 * <p>Every write locks the session first; a packer who did not start it is refused ({@code PACKING_TAKEN}) unless a
 * warehouse manager ({@code fulfillment-packing:APPROVE}). Limited to the source warehouse ({@code OUT_OF_DATA_SCOPE}).
 * Line ids are the transfer's line ids.</p>
 */
public interface TransferPacks {

    /**
     * @param requested what the transfer asks for of the line
     * @param packed    in parcels not voided
     * @param remaining {@code requested - packed}
     * @param packageCount boxes one unit of its item ships as: above one, a unit is packed as a kit (LP2-5)
     */
    record Line(UUID transferLineId, int lineNo, String sku, String barcode, String lotNumber, int requested,
                int packed, int remaining, int packageCount, boolean kitSuggested) { }

    record Content(UUID transferLineId, String sku, String lotNumber, int quantity) { }

    /** A kit part (LP2-6): part 1 holds the unit of {@code transferLineId}. */
    record KitView(UUID groupId, int partNo, int partCount, UUID transferLineId) { }

    /**
     * @param stockPackageId the unopened stock carton it ships as it is (LP2-8); null for a parcel packed at the table
     */
    record Parcel(UUID packageId, int packageNo, String labelCode, String packageType, String status,
                  BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                  BigDecimal dimWeightKg, boolean rotatable, boolean fragile, Instant sealedAt,
                  List<Content> contents, KitView kit, String kitOverrideReason, UUID stockPackageId) { }

    /** Measures suggested for a carton shipped as it is: its packing unit's, in cm and kg (the packer confirms). */
    record Measures(BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) { }

    /**
     * A carton opened as a parcel (LP2-8).
     *
     * @param suggested its packing unit's size and weight; null when the unit declares none (measure it)
     */
    record CartonParcel(Parcel parcel, String lpn, String unitOfMeasure, Measures suggested) { }

    record Kit(UUID kitGroupId, UUID transferLineId, String sku, int partCount, List<Parcel> packages) { }

    /**
     * @param transferStatus the transfer's status (PICKING while it is packed and loaded)
     * @param packStatus     PACKING, then PACKED (ready for a load plan); CANCELLED when picking was cancelled
     */
    record Session(UUID packId, UUID transferOrderId, String transferNumber, UUID fromWarehouseId,
                   UUID toWarehouseId, String transferStatus, String deliveryMode, String packStatus, UUID packerId,
                   Instant startedAt, Instant packedAt, List<Line> lines, List<Parcel> packages) { }

    record Scan(UUID scanId, String scannedCode, String lotNumber, int quantity, UUID transferLineId,
                String kitOverrideReason) { }

    record Seal(BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, String packageType,
                Boolean rotatable, Boolean fragile, String kitOverrideReason) { }

    /**
     * Opens (or answers with) the transfer's packing session. A session already open or packed is answered as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code TRANSFER_ORDER_NOT_FOUND};
     *         {@code TRANSFER_NOT_FULL_TRUCKLOAD}; {@code INVALID_TRANSFER_TRANSITION} unless APPROVED or PICKING;
     *         {@code OUT_OF_DATA_SCOPE} outside the source warehouse
     */
    Session open(UUID transferOrderId, CurrentUser actor);

    Session get(UUID packId);

    Parcel openPackage(UUID packId, String type, String kitOverrideReason, CurrentUser actor);

    /** "Đóng bộ" for one unit of a transfer line whose item ships as N &gt; 1 boxes (LP2-6). */
    Kit openKit(UUID packId, UUID transferLineId, Integer partCount, String packageType, CurrentUser actor);

    Session scan(UUID packId, UUID packageId, Scan scan, CurrentUser actor);

    Parcel seal(UUID packId, UUID packageId, Seal seal, CurrentUser actor);

    /** Taken apart before it left: its units count as unpacked again (a kit is voided whole). */
    Session voidPackage(UUID packId, UUID packageId, CurrentUser actor);

    /**
     * LP2-8: an unopened stock carton of a transfer line ships as it is - a new parcel holding all its units.
     *
     * @throws com.stockflow.common.error.BusinessException {@code STOCK_CARTON_NOT_SHIPPABLE} for a carton that is not
     *         unopened and in stock at the source warehouse, of a line's SKU (and lot), all of it sellable, and not
     *         more than the line still wants; {@code NOT_FOUND} for an unknown label
     */
    CartonParcel shipCarton(UUID packId, String lpn, String packageType, CurrentUser actor);

    /**
     * Packing complete (10 step 5): every parcel sealed (a kit whole), nothing open with goods in it; the transfer is
     * packed - ready for a load plan. Everything requested must be packed unless {@code partial}, with a note.
     */
    Session complete(UUID packId, boolean partial, String note, CurrentUser actor);
}
