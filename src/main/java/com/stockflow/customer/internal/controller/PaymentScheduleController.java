package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.customer.api.CustomerPaymentSchedules;
import com.stockflow.customer.api.DefaultPaymentSchedule;
import com.stockflow.customer.api.SaveDefaultPaymentScheduleCommand;
import com.stockflow.customer.internal.controller.dto.SavePaymentScheduleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A customer's default payment schedule (4B-1; kltn-docs 18 §3, §4 steps 2 and 5, BR-03; 14 §4.3 step 12). Sales sets
 * ON_SIGNING and BEFORE_DELIVERY instalments ({@code customer-payment-schedules:UPDATE}); an instalment after delivery
 * needs {@code :APPROVE} - accounting - and credit accounting approved, and is recorded as approved by whoever set it.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/payment-schedule")
@Tag(name = "Customers")
@PermissionResource(code = CustomerResources.PAYMENT_SCHEDULES, group = "Customer", label = "Default payment schedule",
        route = "/customers", apiPath = "/api/v1/customers/{customerId}/payment-schedule",
        actions = {Action.READ, Action.UPDATE, Action.APPROVE})
class PaymentScheduleController {

    private final CustomerPaymentSchedules schedules;

    PaymentScheduleController(CustomerPaymentSchedules schedules) {
        this.schedules = schedules;
    }

    @GetMapping
    @Operation(summary = "The customer's default payment schedule: instalments, shares, milestones, days, approval")
    @RequiresPermission(resource = CustomerResources.PAYMENT_SCHEDULES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<DefaultPaymentSchedule> get(@PathVariable UUID customerId) {
        return ApiResponse.ok(schedules.defaultPaymentSchedule(customerId));
    }

    @PutMapping
    @Operation(summary = "Replace the customer's default payment schedule; the shares add up to 100. An instalment "
            + "after delivery needs :APPROVE and the customer's approved credit (409 PAYMENT_SCHEDULE_NEEDS_CREDIT)")
    @RequiresPermission(resource = CustomerResources.PAYMENT_SCHEDULES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<DefaultPaymentSchedule> save(@PathVariable UUID customerId,
                                                    @Valid @RequestBody SavePaymentScheduleRequest request,
                                                    @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(schedules.save(new SaveDefaultPaymentScheduleCommand(customerId,
                request.installments().stream().map(l -> new SaveDefaultPaymentScheduleCommand.Line(l.name(),
                        l.percent(), l.milestone(), l.dueDays())).toList(), request.note(), user.userId(),
                user.can(CustomerResources.PAYMENT_SCHEDULES, Action.APPROVE), request.expectedVersion())));
    }
}
