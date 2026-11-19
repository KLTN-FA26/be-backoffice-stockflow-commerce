package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.internal.controller.dto.FakeAhamovePlayRequest;
import com.stockflow.fulfillment.internal.controller.dto.FakeAhamoveStepRequest;
import com.stockflow.fulfillment.internal.service.FakeAhamove;
import com.stockflow.fulfillment.internal.service.FakeAhamoveOnly;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * The demo's remote control for the fake Ahamove ({@code stockflow.fulfillment.ahamove.mode=fake}): it exists only in
 * that mode (a live deployment answers 404), and moves an order along Ahamove's life - a driver, a pickup, a delivery -
 * by sending the system its own webhook for each step. Guarded as the warehouse manager's carrier events are:
 * {@code fulfillment-carrier-events:UPDATE}.
 */
@RestController
@FakeAhamoveOnly
@RequestMapping("/api/v1/fulfillment/dev/fake-ahamove")
@Tag(name = "Fulfillment - fake Ahamove (demo mode only)")
class FakeAhamoveController {

    private final FakeAhamove fake;

    FakeAhamoveController(FakeAhamove fake) {
        this.fake = fake;
    }

    @GetMapping("/orders")
    @Operation(summary = "The orders the fake Ahamove holds, newest first")
    @RequiresPermission(resource = FulfillmentResources.CARRIER_EVENTS, action = Action.UPDATE)
    public ApiResponse<List<FakeAhamove.OrderView>> orders() {
        return ApiResponse.ok(fake.orders());
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "One order of the fake Ahamove: status, driver, drop-offs")
    @RequiresPermission(resource = FulfillmentResources.CARRIER_EVENTS, action = Action.UPDATE)
    public ApiResponse<FakeAhamove.OrderView> order(@PathVariable String orderId) {
        return ApiResponse.ok(fake.order(orderId));
    }

    @PostMapping("/orders/{orderId}/events")
    @Operation(summary = "One step of an order's life, sent to the system as Ahamove's webhook; answers what the "
            + "system made of it")
    @RequiresPermission(resource = FulfillmentResources.CARRIER_EVENTS, action = Action.UPDATE)
    public ApiResponse<FakeAhamove.Played> step(@PathVariable String orderId,
                                                @Valid @RequestBody FakeAhamoveStepRequest request) {
        FakeAhamove.Step step;
        try {
            step = FakeAhamove.Step.valueOf(request.step().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "step: one of "
                    + java.util.Arrays.toString(FakeAhamove.Step.values()));
        }
        return ApiResponse.ok(fake.send(orderId, step, new FakeAhamove.Details(request.stop(), request.reason(),
                request.driverName(), request.driverPhone(), request.vehiclePlate())));
    }

    @PostMapping("/orders/{orderId}/play")
    @Operation(summary = "Every step an order still has, up to its delivery (or failure and return); stops at the "
            + "first step the system did not take")
    @RequiresPermission(resource = FulfillmentResources.CARRIER_EVENTS, action = Action.UPDATE)
    public ApiResponse<FakeAhamove.Played> play(@PathVariable String orderId,
                                                @Valid @RequestBody(required = false) FakeAhamovePlayRequest request) {
        FakeAhamovePlayRequest given = request == null ? new FakeAhamovePlayRequest(null, null, null, null, null)
                : request;
        String outcome = given.outcome() == null ? "DELIVERED" : given.outcome().strip().toUpperCase(Locale.ROOT);
        if (!outcome.equals("DELIVERED") && !outcome.equals("FAILED")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "outcome: DELIVERED or FAILED");
        }
        return ApiResponse.ok(fake.play(orderId, outcome.equals("DELIVERED"), new FakeAhamove.Details(null,
                given.reason(), given.driverName(), given.driverPhone(), given.vehiclePlate())));
    }
}
