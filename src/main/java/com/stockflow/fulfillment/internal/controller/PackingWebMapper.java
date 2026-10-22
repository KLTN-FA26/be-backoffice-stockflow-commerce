package com.stockflow.fulfillment.internal.controller;

import com.stockflow.fulfillment.internal.controller.dto.KitPartResponse;
import com.stockflow.fulfillment.internal.controller.dto.PackageKitResponse;
import com.stockflow.fulfillment.internal.controller.dto.PackageLabelResponse;
import com.stockflow.fulfillment.internal.controller.dto.PackageResponse;
import com.stockflow.fulfillment.internal.controller.dto.PackingQueueRowResponse;
import com.stockflow.fulfillment.internal.controller.dto.PackingSessionResponse;
import com.stockflow.fulfillment.internal.domain.KitPart;
import com.stockflow.fulfillment.internal.service.Packing;
import org.springframework.stereotype.Component;

import java.util.List;

/** The packing station's views to their wire format. */
@Component
class PackingWebMapper {

    PackingQueueRowResponse toResponse(Packing.QueueRow row) {
        return new PackingQueueRowResponse(row.taskId(), row.orderId(), row.orderNumber(), row.warehouseId(),
                row.packStatus(), row.packStatus(), row.pickedAt(), row.pickerName(), row.packerId(), row.packerName(),
                row.packingStartedAt(), row.packageCount(), row.sealedPackageCount(), row.shipmentCount());
    }

    PackingSessionResponse toResponse(Packing.Session session) {
        return new PackingSessionResponse(session.taskId(), session.orderId(), session.orderNumber(),
                session.warehouseId(), session.packStatus(), session.packerId(), session.packerName(),
                session.startedAt(), session.packedAt(),
                session.lines().stream().map(line -> new PackingSessionResponse.Line(line.orderLineId(), line.sku(),
                        line.barcode(), line.printLine(), line.designSnapshotId(), line.wanted(), line.available(),
                        line.packed(), line.shipped(), line.remaining(), line.lotTracked(), line.serialTracked(),
                        line.lots().stream().map(lot -> new PackingSessionResponse.Lot(lot.lotNumber(), lot.available(),
                                lot.packed())).toList(), line.packageCount(), line.kitSuggested())).toList(),
                session.packages().stream().map(this::toResponse).toList(),
                session.shipments().stream().map(s -> new PackingSessionResponse.Shipment(s.shipmentId(), s.code(),
                        s.status(), s.deliveryMode(), s.packageCount(), s.codAmount(), s.currency(), s.partial(),
                        s.partialNote(),
                        s.readyAt())).toList(),
                session.paymentDue() == null ? null : new PackingSessionResponse.PaymentDue(
                        session.paymentDue().amountDueBeforeHandover(), session.paymentDue().currency()));
    }

    PackageResponse toResponse(Packing.Parcel parcel) {
        return new PackageResponse(parcel.packageId(), parcel.packageNo(), parcel.labelCode(), parcel.packageType(),
                parcel.status(), parcel.shipmentId(), parcel.weightKg(), parcel.lengthCm(), parcel.widthCm(),
                parcel.heightCm(), parcel.dimWeightKg(), parcel.rotatable(), parcel.rotatableOverridden(),
                parcel.fragile(), parcel.fragileOverridden(), parcel.sealedAt(), contents(parcel.contents()),
                kit(parcel.kit()), parcel.kitOverrideReason());
    }

    PackageKitResponse toResponse(Packing.Kit kit) {
        return new PackageKitResponse(kit.kitGroupId(), kit.orderLineId(), kit.sku(), kit.partCount(),
                kit.packages().stream().map(this::toResponse).toList());
    }

    static KitPartResponse kit(KitPart kit) {
        return kit == null ? null : new KitPartResponse(kit.groupId(), kit.partNo(), kit.partCount(),
                kit.orderLineId());
    }

    PackageLabelResponse toResponse(Packing.Label label) {
        var r = label.recipient();
        return new PackageLabelResponse(label.labelCode(), label.packageId(), label.orderNumber(), label.shipmentId(),
                label.shipmentCode(), label.packageNo(), label.packageCount(), label.provisional(), label.codAmount(),
                label.currency(), new PackageLabelResponse.Recipient(r.recipientName(), r.phone(), r.line1(),
                r.line2(), r.wardName(), r.provinceName(), r.countryCode(), r.postalCode()),
                label.returnWarehouseName(), label.returnAddress(), label.packageType(), label.weightKg(),
                label.lengthCm(), label.widthCm(), label.heightCm(), label.dimWeightKg(), label.fragile(),
                label.rotatable(), contents(label.contents()), kit(label.kit()));
    }

    private static List<PackageResponse.Content> contents(List<Packing.Content> contents) {
        return contents.stream().map(c -> new PackageResponse.Content(c.orderLineId(), c.sku(), c.lotNumber(),
                c.serialNumber(), c.quantity())).toList();
    }
}
