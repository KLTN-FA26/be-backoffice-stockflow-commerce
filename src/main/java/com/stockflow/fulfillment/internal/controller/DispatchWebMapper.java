package com.stockflow.fulfillment.internal.controller;

import com.stockflow.fulfillment.internal.controller.dto.CarrierResponse;
import com.stockflow.fulfillment.internal.controller.dto.LoadResponse;
import com.stockflow.fulfillment.internal.controller.dto.LoadRowResponse;
import com.stockflow.fulfillment.internal.controller.dto.ManifestResponse;
import com.stockflow.fulfillment.internal.controller.dto.ShipmentResponse;
import com.stockflow.fulfillment.internal.controller.dto.ShipmentRowResponse;
import com.stockflow.fulfillment.internal.service.Carriers;
import com.stockflow.fulfillment.internal.service.Dispatching;
import com.stockflow.fulfillment.internal.service.Shipments;
import org.springframework.stereotype.Component;

/** Carriers, loads, manifests and shipments to their wire format (G4-2a). */
@Component
class DispatchWebMapper {

    CarrierResponse toResponse(Carriers.CarrierView c) {
        return new CarrierResponse(c.id(), c.code(), c.name(), c.contactName(), c.contactPhone(), c.status(),
                c.version());
    }

    LoadRowResponse toResponse(Dispatching.LoadRow r) {
        return new LoadRowResponse(r.loadId(), r.code(), r.warehouseId(), r.mode(), r.status(), r.carrierId(),
                r.carrierName(), r.vehiclePlate(), r.driverName(), r.shipmentCount(), r.packageCount(), r.codTotal(),
                r.loadingStartedAt(), r.dispatchedAt(), r.version());
    }

    LoadResponse toResponse(Dispatching.LoadDetail d) {
        return new LoadResponse(d.loadId(), d.code(), d.warehouseId(), d.mode(), d.status(), d.carrierId(),
                d.carrierCode(), d.carrierName(), d.vehiclePlate(), d.driverName(), d.driverPhone(),
                d.loadingStartedAt(), d.loadingStartedBy(), d.dispatchedAt(), d.dispatchedBy(), d.dispatchedByName(),
                d.carrierRepresentative(), d.signatureRef(), d.handoverNote(), d.cancelledAt(), d.cancelReason(),
                d.shipmentCount(), d.expectedPackageCount(), d.loadedPackageCount(), d.codTotal(), d.currency(),
                d.version(), d.shipments().stream().map(s -> new LoadResponse.Shipment(s.shipmentId(), s.code(),
                        s.orderId(), s.orderNumber(), s.status(), s.packageCount(), s.loadedCount(),
                        s.labelCodAmount(), s.codAmount(), s.packages().stream().map(p -> new LoadResponse.Parcel(
                                p.packageId(), p.labelCode(), p.weightKg(), p.loaded(), p.loadedAt(), p.loadedBy()))
                        .toList())).toList());
    }

    ManifestResponse toResponse(Dispatching.Manifest m) {
        return new ManifestResponse(m.loadCode(), m.status(), m.warehouseName(), m.warehouseAddress(),
                m.carrierCode(), m.carrierName(), m.carrierContactName(), m.carrierContactPhone(), m.vehiclePlate(),
                m.driverName(), m.driverPhone(), m.carrierRepresentative(), m.signatureRef(), m.handoverNote(),
                m.dispatchedAt(), m.dispatchedByName(), m.shipments().stream().map(s -> new ManifestResponse.Shipment(
                        s.shipmentCode(), s.orderNumber(), s.recipientName(), s.recipientPhone(), s.deliveryAddress(),
                        s.packageCount(), s.weightKg(), s.codAmount(), s.codChangedSinceLabel(),
                        s.packages().stream().map(p -> new ManifestResponse.Parcel(p.labelCode(), p.weightKg(),
                                p.lengthCm(), p.widthCm(), p.heightCm(), p.loaded())).toList())).toList(),
                m.shipmentCount(), m.packageCount(), m.totalWeightKg(), m.codTotal(), m.currency(), m.printedAt());
    }

    ShipmentRowResponse toResponse(Shipments.ShipmentRow r) {
        return new ShipmentRowResponse(r.shipmentId(), r.code(), r.orderId(), r.orderNumber(), r.warehouseId(),
                r.status(), r.deliveryMode(), r.packageCount(), r.codAmount(), r.carrierId(), r.carrierName(),
                r.loadId(), r.loadCode(), r.partial(), r.readyAt(), r.handedOverAt(), r.outcomeAt(),
                r.failureReason(), r.wholeOrderHandover(), r.version());
    }

    ShipmentResponse toResponse(Shipments.ShipmentDetail d) {
        return new ShipmentResponse(toResponse(d.shipment()), d.partialNote(), d.failureNote(), d.cancelledAt(),
                d.packages().stream().map(p -> new ShipmentResponse.Parcel(p.packageId(), p.labelCode(), p.status(),
                        p.weightKg(), p.lengthCm(), p.widthCm(), p.heightCm())).toList(),
                d.timeline().stream().map(s -> new ShipmentResponse.Step(s.status(), s.occurredAt(), s.recordedBy(),
                        s.recordedByName(), s.note())).toList());
    }
}
