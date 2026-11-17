package com.stockflow.fulfillment.internal.controller;

import com.stockflow.fulfillment.internal.controller.dto.CartonCheckQueueRowResponse;
import com.stockflow.fulfillment.internal.controller.dto.CartonCheckSessionResponse;
import com.stockflow.fulfillment.internal.service.CartonChecks;
import org.springframework.stereotype.Component;

/** The check station's views to their wire format (team plan 4C-6). */
@Component
class CartonCheckWebMapper {

    CartonCheckQueueRowResponse toResponse(CartonChecks.QueueRow row) {
        return new CartonCheckQueueRowResponse(row.taskId(), row.orderId(), row.orderNumber(), row.warehouseId(),
                row.status(), row.pickedAt(), row.pickerName(), row.startedBy(), row.startedByName(), row.startedAt(),
                row.cartonCount(), row.checkedCount(), row.labeledCount(), row.shipmentCount());
    }

    CartonCheckSessionResponse toResponse(CartonChecks.Session session) {
        return new CartonCheckSessionResponse(session.taskId(), session.orderId(), session.orderNumber(),
                session.warehouseId(), session.status(), session.startedBy(), session.startedByName(),
                session.startedAt(), session.completedAt(), session.orderLocked(),
                session.lines().stream().map(l -> new CartonCheckSessionResponse.Line(l.orderLineId(), l.sku(),
                        l.wantedUnits(), l.cartons(), l.checkedCartons(), l.labeledCartons(), l.removedCartons(),
                        l.shippedCartons(), l.labeledUnits(), l.missingUnits())).toList(),
                session.cartons().stream().map(c -> new CartonCheckSessionResponse.Carton(c.packageId(),
                        c.packageNo(), c.labelCode(), c.lpn(), c.pickLineId(), c.orderLineId(), c.sku(),
                        c.lotNumber(), c.quantity(), c.status(), c.checkedAt(), c.labeledAt(), c.labelVersion(),
                        c.lengthCm(), c.widthCm(), c.heightCm(), c.weightKg(), c.dimWeightKg(), c.rotatable(),
                        c.shipmentId(), c.removalReason(), c.removalNote())).toList(),
                session.shipments().stream().map(s -> new CartonCheckSessionResponse.Shipment(s.shipmentId(),
                        s.code(), s.status(), s.packageCount(), s.partial(), s.partialNote(), s.readyAt())).toList(),
                session.paymentDue() == null ? null : new CartonCheckSessionResponse.PaymentDue(
                        session.paymentDue().amount(), session.paymentDue().currency()),
                session.skusWithoutCartonSpec());
    }
}
