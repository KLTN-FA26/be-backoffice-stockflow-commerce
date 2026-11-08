package com.stockflow.fulfillment.internal.controller;

import com.stockflow.fulfillment.internal.controller.dto.CarrierConnectionRequest;
import com.stockflow.fulfillment.internal.controller.dto.CarrierResponse;
import com.stockflow.fulfillment.internal.controller.dto.CarrierServiceCodeResponse;
import com.stockflow.fulfillment.internal.controller.dto.ControlledCancelResponse;
import com.stockflow.fulfillment.internal.controller.dto.LoadResponse;
import com.stockflow.fulfillment.internal.controller.dto.LoadRowResponse;
import com.stockflow.fulfillment.internal.controller.dto.ManifestResponse;
import com.stockflow.fulfillment.internal.controller.dto.ShipmentResponse;
import com.stockflow.fulfillment.internal.controller.dto.ShipmentRowResponse;
import com.stockflow.fulfillment.internal.service.CarrierServiceCodes;
import com.stockflow.fulfillment.internal.service.Carriers;
import com.stockflow.fulfillment.internal.service.Dispatching;
import com.stockflow.fulfillment.internal.service.Shipments;
import org.springframework.stereotype.Component;

/** Carriers, loads, manifests and shipments to their wire format (G4-2a). */
@Component
class DispatchWebMapper {

    CarrierResponse toResponse(Carriers.CarrierView c) {
        Carriers.ConnectionView k = c.connection();
        return new CarrierResponse(c.id(), c.code(), c.name(), c.contactName(), c.contactPhone(), c.status(),
                new CarrierResponse.Connection(k.type(), k.webhookEnabled(), k.webhookSecretConfigured(),
                        k.webhookAuthHeader(), k.webhookAuthValueConfigured(), k.apiEnvironment(), k.apiAccount(),
                        k.apiKeyConfigured(), k.failureReasonMap()), c.version());
    }

    CarrierServiceCodeResponse toResponse(CarrierServiceCodes.ServiceCode c) {
        return new CarrierServiceCodeResponse(c.vehicleTypeId(), c.vehicleTypeCode(), c.provinceCode(),
                c.carrierCityCode(), c.serviceCode());
    }

    Carriers.ConnectionInput toInput(CarrierConnectionRequest r) {
        return r == null ? null : new Carriers.ConnectionInput(r.connectionType(), r.webhookEnabled(),
                r.webhookSecret(), r.webhookAuthHeader(), r.webhookAuthValue(), r.apiEnvironment(), r.apiAccount(),
                r.apiKey(), r.failureReasonMap());
    }

    LoadRowResponse toResponse(Dispatching.LoadRow r) {
        return new LoadRowResponse(r.loadId(), r.code(), r.warehouseId(), r.status(), r.carrierId(),
                r.carrierName(), r.vehiclePlate(), r.driverName(), r.shipmentCount(), r.packageCount(),
                r.loadingStartedAt(), r.dispatchedAt(), r.version(), r.loadPlanId(), r.loadPlanCode(), r.vehicleSeq(),
                r.waybillNo());
    }

    LoadResponse toResponse(Dispatching.LoadDetail d) {
        return new LoadResponse(d.loadId(), d.code(), d.warehouseId(), d.status(), d.carrierId(),
                d.carrierCode(), d.carrierName(), d.vehiclePlate(), d.driverName(), d.driverPhone(),
                d.loadingStartedAt(), d.loadingStartedBy(), d.dispatchedAt(), d.dispatchedBy(), d.dispatchedByName(),
                d.carrierRepresentative(), d.signatureRef(), d.handoverNote(), d.cancelledAt(), d.cancelReason(),
                d.shipmentCount(), d.expectedPackageCount(), d.loadedPackageCount(),
                d.version(), d.shipments().stream().map(s -> new LoadResponse.Shipment(s.shipmentId(), s.code(),
                        s.orderId(), s.orderNumber(), s.taskId(), s.status(), s.packageCount(), s.loadedCount(),
                        s.packages().stream().map(p -> new LoadResponse.Parcel(
                                p.packageId(), p.labelCode(), p.weightKg(), p.loaded(), p.loadedAt(), p.loadedBy(),
                                PackingWebMapper.kit(p.kit()), p.labelOutdatedAt() != null, p.labelOutdatedAt()))
                        .toList())).toList(), d.loadPlanId(), d.loadPlanCode(), d.vehicleSeq(),
                d.vehicleTypeCode(),
                d.bookedAt(), d.bookedBy(), d.bookingCancelledAt(), d.bookingCancelReason(),
                d.pendingUnloads().stream().map(u -> new LoadResponse.PendingUnload(u.packageId(), u.labelCode(),
                        u.shipmentId(), u.shipmentCode(), u.reason(), u.requestedAt(), u.reasonCode())).toList(),
                d.carrierBookingToCancel(), d.cancelReasonCode(), d.replacementLoadId(), d.replacementLoadCode(),
                d.waybillNo(), d.carrierConnectionType(), d.carrierBooking() == null ? null
                        : new LoadResponse.CarrierBooking(d.carrierBooking().status(), d.carrierBooking().carrierOrderId(),
                        d.carrierBooking().trackingLink(), d.carrierBooking().requestedAt(),
                        d.carrierBooking().bookedAt(), d.carrierBooking().lastError()));
    }

    ControlledCancelResponse toResponse(Dispatching.ControlledCancelResult r) {
        return new ControlledCancelResponse(toResponse(r.cancelled()),
                r.replacement() == null ? null : toResponse(r.replacement()));
    }

    ManifestResponse toResponse(Dispatching.Manifest m) {
        return new ManifestResponse(m.loadCode(), m.status(), m.warehouseName(), m.warehouseAddress(),
                m.carrierCode(), m.carrierName(), m.carrierContactName(), m.carrierContactPhone(), m.vehiclePlate(),
                m.driverName(), m.driverPhone(), m.carrierRepresentative(), m.signatureRef(), m.handoverNote(),
                m.dispatchedAt(), m.dispatchedByName(), m.shipments().stream().map(s -> new ManifestResponse.Shipment(
                        s.shipmentCode(), s.orderNumber(), s.recipientName(), s.recipientPhone(), s.deliveryAddress(),
                        s.packageCount(), s.weightKg(),
                        s.packages().stream().map(p -> new ManifestResponse.Parcel(p.labelCode(), p.weightKg(),
                                p.lengthCm(), p.widthCm(), p.heightCm(), p.loaded(), PackingWebMapper.kit(p.kit()),
                                p.labelOutdatedAt() != null, p.labelOutdatedAt()))
                                .toList())).toList(),
                m.shipmentCount(), m.packageCount(), m.totalWeightKg(), m.printedAt(), m.loadPlanCode(),
                m.vehicleSeq(), m.vehicleTypeCode(), m.waybillNo());
    }

    ShipmentRowResponse toResponse(Shipments.ShipmentRow r) {
        return new ShipmentRowResponse(r.shipmentId(), r.code(), r.orderId(), r.orderNumber(), r.taskId(),
                r.warehouseId(), r.status(), r.packageCount(), r.carrierId(), r.carrierName(),
                r.loadId(), r.loadCode(), r.partial(), r.readyAt(), r.handedOverAt(), r.outcomeAt(),
                r.failureReason(), r.wholeOrderHandover(), r.version());
    }

    ShipmentResponse toResponse(Shipments.ShipmentDetail d) {
        return new ShipmentResponse(toResponse(d.shipment()), d.partialNote(), d.failureNote(), d.cancelledAt(),
                d.packages().stream().map(p -> new ShipmentResponse.Parcel(p.packageId(), p.labelCode(), p.status(),
                        p.weightKg(), p.lengthCm(), p.widthCm(), p.heightCm(), PackingWebMapper.kit(p.kit()),
                        p.labelOutdatedAt() != null, p.labelOutdatedAt()))
                        .toList(),
                d.timeline().stream().map(s -> new ShipmentResponse.Step(s.status(), s.occurredAt(), s.recordedBy(),
                        s.recordedByName(), s.note())).toList());
    }
}
