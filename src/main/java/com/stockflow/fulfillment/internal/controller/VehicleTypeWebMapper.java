package com.stockflow.fulfillment.internal.controller;

import com.stockflow.fulfillment.internal.controller.dto.VehicleTypeRequest;
import com.stockflow.fulfillment.internal.controller.dto.VehicleTypeResponse;
import com.stockflow.fulfillment.internal.controller.dto.VehicleTypeUpdateRequest;
import com.stockflow.fulfillment.internal.service.VehicleTypes;
import org.springframework.stereotype.Component;

/** Vehicle types to and from their wire format (SCRUM-287). */
@Component
class VehicleTypeWebMapper {

    VehicleTypeResponse toResponse(VehicleTypes.VehicleTypeView v) {
        return new VehicleTypeResponse(v.id(), v.code(), v.name(), v.kind(), v.innerLengthMm(), v.innerWidthMm(),
                v.innerHeightMm(), v.gussetMm(), v.maxPayloadKg(), v.availableCount(), v.rentalPrice(), v.preset(), v.status(),
                v.version());
    }

    VehicleTypes.SaveVehicleType toCommand(VehicleTypeRequest r) {
        return new VehicleTypes.SaveVehicleType(r.code(), r.name(), r.kind(), r.innerLengthMm(), r.innerWidthMm(),
                r.innerHeightMm(), r.gussetMm(), r.maxPayloadKg(), r.availableCount(), r.rentalPrice(), r.status(), null);
    }

    VehicleTypes.SaveVehicleType toCommand(VehicleTypeUpdateRequest r) {
        return new VehicleTypes.SaveVehicleType(r.code(), r.name(), r.kind(), r.innerLengthMm(), r.innerWidthMm(),
                r.innerHeightMm(), r.gussetMm(), r.maxPayloadKg(), r.availableCount(), r.rentalPrice(), r.status(), r.version());
    }
}
