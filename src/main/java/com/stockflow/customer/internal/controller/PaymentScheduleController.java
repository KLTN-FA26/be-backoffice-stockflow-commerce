package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.customer.api.DefaultPaymentSchedule;
import com.stockflow.customer.internal.controller.dto.ApprovePaymentScheduleRequest;
import com.stockflow.customer.internal.service.PaymentSchedules;
import com.stockflow.customer.internal.controller.dto.SavePaymentScheduleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A customer's default payment schedule (4B-1; kltn-docs 18 §3, §4 steps 2 and 5, BR-03; 14 §4.3 step 12; 15 BR-12).
 * Sales sets it ({@code customer-payment-schedules:UPDATE}); an installment after delivery needs the customer's approved
 * credit, and accounting's approval ({@code :APPROVE}) - given by saving it themselves or by approving what Sales
 * proposed. Handlers are public so the permission proxy advises them.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/payment-schedule")
@Tag(name = "Customers")
@PermissionResource(code = CustomerResources.PAYMENT_SCHEDULES, group = "Customer", label = "Default payment schedule",
        route = "/customers", apiPath = "/api/v1/customers/{customerId}/payment-schedule",
        actions = {Action.READ, Action.UPDATE, Action.APPROVE})
class PaymentScheduleController {

    private final PaymentSchedules schedules;

    PaymentScheduleController(PaymentSchedules schedules) {
        this.schedules = schedules;
    }

    @GetMapping
    @Operation(summary = "The customer's default payment schedule: installments, shares, milestones, days, approval")
    @RequiresPermission(resource = CustomerResources.PAYMENT_SCHEDULES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<DefaultPaymentSchedule> get(@PathVariable UUID customerId) {
        return ApiResponse.ok(schedules.defaultPaymentSchedule(customerId));
    }

    @PutMapping
    @Operation(summary = "Replace the customer's default payment schedule; the shares add up to 100. An installment "
            + "after delivery needs the customer's approved credit (409 PAYMENT_SCHEDULE_NEEDS_CREDIT); saved by "
            + "accounting it is approved, saved by anyone else it awaits accounting (awaitingApproval)")
    @RequiresPermission(resource = CustomerResources.PAYMENT_SCHEDULES, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<DefaultPaymentSchedule> save(@PathVariable UUID customerId,
                                                    @Valid @RequestBody SavePaymentScheduleRequest request,
                                                    @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(schedules.save(new PaymentSchedules.Save(customerId,
                request.installments().stream().map(l -> new PaymentSchedules.Line(l.name(), l.percent(),
                        l.milestone(), l.dueDays())).toList(), request.note(), user.userId(),
                user.can(CustomerResources.PAYMENT_SCHEDULES, Action.APPROVE), request.expectedVersion())));
    }

    @PostMapping("/approval")
    @Operation(summary = "Accounting approves the installments after delivery of the schedule as read (15 BR-12); "
            + "approving it again changes nothing")
    @RequiresPermission(resource = CustomerResources.PAYMENT_SCHEDULES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<DefaultPaymentSchedule> approve(@PathVariable UUID customerId,
                                                       @Valid @RequestBody ApprovePaymentScheduleRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(schedules.approve(customerId, request.expectedVersion(), user.userId()));
    }
}
