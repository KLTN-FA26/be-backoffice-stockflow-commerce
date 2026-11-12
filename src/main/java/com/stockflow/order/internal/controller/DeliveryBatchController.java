package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.internal.controller.dto.WindowChangeRequest;
import com.stockflow.order.internal.service.DeliveryBatchService;
import com.stockflow.order.internal.service.OrderWarehouseVisibility;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * An order's delivery schedule as it is worked (4B-8; kltn-docs 17 §4.5, §5.5, BR-28): the contract's batches with the
 * window in force and each batch's state, worked out. The back office reads it with
 * {@code sales-order-delivery-batches:READ} (WF4 BE-13): Sales, the Sales Manager and accounting, who read it with the
 * contract before, and whoever releases the batches - the warehouse manager, who must not read the contract itself
 * ({@code sales-contracts:READ}); a caller limited to warehouses reads only the schedule of an order shipping from or
 * released to one of theirs ({@link OrderWarehouseVisibility}), anything else is 404. Sales moves a window with the
 * right to edit contracts ({@code sales-contracts:UPDATE}, Sales' - 17 BR-28 "chỉ Sale"); the customer reads the
 * schedule in force of their own order ({@code sales-orders:READ}, OWN). Handlers are public so the permission proxy
 * advises them.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
@PermissionResource(code = OrderResources.DELIVERY_BATCHES, group = "Sales", label = "Order delivery schedule",
        route = "/sales/orders", apiPath = "/api/v1/orders/{orderId}/delivery-batches", actions = {Action.READ})
class DeliveryBatchController {

    private final DeliveryBatchService batches;
    private final CustomerService customers;
    private final OrderWarehouseVisibility warehouseVisibility;

    DeliveryBatchController(DeliveryBatchService batches, CustomerService customers,
                            OrderWarehouseVisibility warehouseVisibility) {
        this.batches = batches;
        this.customers = customers;
        this.warehouseVisibility = warehouseVisibility;
    }

    @GetMapping("/{orderId}/delivery-batches")
    @Operation(summary = "The order's delivery schedule in force: each batch's window (the contract's and the one in "
            + "force), state (AWAITING_STOCK, READY, IN_DELIVERY, DELIVERED, CLOSED), late flag, cartons held, release "
            + "and window changes")
    @RequiresPermission(resource = OrderResources.DELIVERY_BATCHES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<DeliveryBatchService.Schedule> schedule(@PathVariable UUID orderId,
                                                               @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        // An order row has no warehouse for the generic data-scope filter, so a caller limited to warehouses (the
        // warehouse manager) is narrowed here, as on the order's detail; an order out of reach is the same 404.
        if (!warehouseVisibility.visible(orderId, user)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId);
        }
        return ApiResponse.ok(batches.schedule(orderId));
    }

    @GetMapping("/me/{orderId}/delivery-batches")
    @Operation(summary = "The signed-in customer's order: its delivery schedule in force (17 BR-28)")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<DeliveryBatchService.Schedule> mySchedule(@PathVariable UUID orderId,
                                                                 @AuthenticatedUser CurrentUser user) {
        UUID customerId = customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
        return ApiResponse.ok(batches.scheduleOfCustomer(customerId, orderId));
    }

    @PostMapping("/{orderId}/delivery-batches/{batchId}/window-change/evidence/uploads")
    @Operation(summary = "Where the browser uploads the customer's consent to a window change (an e-mail, a signed "
            + "note) before the change is recorded")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.UPDATE)
    public ApiResponse<UploadSlot> beginEvidenceUpload(@PathVariable UUID orderId, @PathVariable UUID batchId,
                                                       @Valid @RequestBody UploadSlotRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(batches.beginEvidenceUpload(user, orderId, batchId, request));
    }

    @PostMapping("/{orderId}/delivery-batches/{batchId}/window-change")
    @Operation(summary = "Move a delivery batch's window (17 BR-28): only while none of its cartons was handed over, "
            + "not into the past, keeping the schedule's order; reason CUSTOMER_REQUEST or SELLER_DELAY, a note, and the "
            + "customer's consent - an uploaded file (evidenceUploadId) or a chat message (evidenceMessageId). 409 "
            + "DELIVERY_BATCH_WINDOW_LOCKED, 404 DELIVERY_BATCH_NOT_FOUND")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.UPDATE)
    public ApiResponse<DeliveryBatchService.Schedule> changeWindow(@PathVariable UUID orderId,
                                                                   @PathVariable UUID batchId,
                                                                   @Valid @RequestBody WindowChangeRequest request,
                                                                   @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(batches.changeWindow(user, orderId, batchId, new DeliveryBatchService.WindowChange(
                request.windowStart(), request.windowEnd(), request.reasonCode(), request.note(),
                request.evidenceUploadId(), request.evidenceMessageId())));
    }
}
