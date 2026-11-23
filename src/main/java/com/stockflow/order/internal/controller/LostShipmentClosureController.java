package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.order.internal.controller.dto.CloseLostShipmentRequest;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.service.LostShipmentClosures;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * WF-05 F-5 (decision Q-12 = a, BE-17): Sales close a shipment the carrier lost, with the carrier's confirmation - the
 * only way out of DELIVERY_FAILED for an order whose parcel never comes back. An approval-level grant of its own
 * ({@code sales-lost-shipment-closures:APPROVE}, declared in {@link LostShipmentClosuresResourceDeclaration}), so it can
 * be withheld from a role that may otherwise work the order.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
class LostShipmentClosureController {

    private final LostShipmentClosures closures;

    LostShipmentClosureController(LostShipmentClosures closures) {
        this.closures = closures;
    }

    @PostMapping("/{orderId}/shipments/{shipmentId}/lost-closure/evidence/uploads")
    @Operation(summary = "Where the browser uploads the carrier's confirmation of the loss before the shipment is "
            + "closed as lost")
    @RequiresPermission(resource = OrderResources.LOST_SHIPMENT_CLOSURES, action = Action.APPROVE)
    public ApiResponse<UploadSlot> beginEvidenceUpload(@PathVariable UUID orderId, @PathVariable UUID shipmentId,
                                                       @Valid @RequestBody UploadSlotRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(closures.beginEvidenceUpload(user, orderId, shipmentId, request));
    }

    @PostMapping("/{orderId}/shipments/{shipmentId}/lost-closure")
    @Operation(summary = "Close a shipment the carrier lost (WF-05 Q-12): a shipment DELIVERY_FAILED for LOST, or one "
            + "reported back whose goods never reached the warehouse (RETURNED, no receipt), its units still on it, "
            + "with a note and the carrier's confirmation. Its units are closed and "
            + "settled like a carrier's return at this moment; the order follows its shipments. 400 "
            + "ORDER_SHIPMENT_LOSS_EVIDENCE_REQUIRED without the confirmation; 409 ORDER_SHIPMENT_NOT_LOST_CLOSABLE")
    @RequiresPermission(resource = OrderResources.LOST_SHIPMENT_CLOSURES, action = Action.APPROVE)
    public ApiResponse<OrderResponse> close(@PathVariable UUID orderId, @PathVariable UUID shipmentId,
                                            @Valid @RequestBody CloseLostShipmentRequest request,
                                            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(OrderWebMapper.toResponse(closures.close(user, orderId, shipmentId, request.note(),
                request.evidenceUploadId(), request.version())));
    }
}
