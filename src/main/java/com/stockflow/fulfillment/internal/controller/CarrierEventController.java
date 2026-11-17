package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.internal.controller.dto.CarrierEventResolutionRequest;
import com.stockflow.fulfillment.internal.service.CarrierEvents;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * What carriers reported through their webhooks, and the warehouse manager's error list (3-5; kltn-docs 09 §4.5 steps
 * 2 and 8). {@code fulfillment-carrier-events:READ} to read; {@code :UPDATE} to mark an error-list event dealt with.
 * Events matched to a load are their warehouse's; unmatched ones and bad credentials are everybody's with the
 * permission.
 */
@RestController
@RequestMapping("/api/v1/fulfillment/carrier-events")
@Tag(name = "Fulfillment - carrier events")
class CarrierEventController {

    private final CarrierEvents events;

    CarrierEventController(CarrierEvents events) {
        this.events = events;
    }

    @GetMapping
    @Operation(summary = "Carrier webhook events, newest first; result=ERRORS for the error list, open=true for the "
            + "unresolved")
    @RequiresPermission(resource = FulfillmentResources.CARRIER_EVENTS, action = Action.READ)
    public ApiResponse<PageResponse<CarrierEvents.EventRow>> list(@RequestParam(required = false) String result,
                                                                  @RequestParam(required = false) Boolean open,
                                                                  @RequestParam(required = false) UUID carrierId,
                                                                  @RequestParam(required = false) UUID loadId,
                                                                  @RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(events.list(result, open, carrierId, loadId, page, size));
    }

    @GetMapping("/{eventId}")
    @Operation(summary = "One carrier webhook event, with the body it carried (credentials masked)")
    @RequiresPermission(resource = FulfillmentResources.CARRIER_EVENTS, action = Action.READ)
    public ApiResponse<CarrierEvents.EventDetail> get(@PathVariable UUID eventId) {
        return ApiResponse.ok(events.get(eventId));
    }

    @PostMapping("/{eventId}/resolution")
    @Operation(summary = "Mark an error-list event dealt with by hand, with a note; it is never applied again")
    @RequiresPermission(resource = FulfillmentResources.CARRIER_EVENTS, action = Action.UPDATE)
    public ApiResponse<CarrierEvents.EventDetail> resolve(@PathVariable UUID eventId,
                                                          @Valid @RequestBody CarrierEventResolutionRequest request,
                                                          @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(events.resolve(eventId, request.note(), actor));
    }
}
