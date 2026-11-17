package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.notification.api.DeliveryAttemptSummary;
import com.stockflow.order.internal.service.ProductionOrderDispatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * How a production order went to its producer (4A-3, kltn-docs 17 §4.3 step 7): the slip it was sent with, and every
 * message the producer was sent - read by those who read production orders, a warehouse manager those coming to their
 * warehouses. The resource is {@link ProductionOrderController}'s.
 *
 * <p>Handler methods are public, as on {@link ProductionOrderController}: the permission aspect needs a proxied public
 * method.</p>
 */
@RestController
@RequestMapping("/api/v1/production-orders/{id}")
@Tag(name = "Production orders")
class ProductionOrderDispatchController {

    /** The slip a production order was sent with. */
    @Schema(name = "ProductionOrderSlip")
    record SlipResponse(String sha256, long sizeBytes, Instant createdAt) {
    }

    /** One attempt to tell the producer something. */
    @Schema(name = "ProductionOrderMessageAttempt")
    record MessageResponse(@Schema(description = "production_order_sent, production_order_sample_approved or "
            + "production_order_stopped") String message, String status, Instant attemptedAt, Instant sentAt,
                           @Schema(description = "Why it failed or was not sent, a code") String failure,
                           int attemptNumber, String recipient,
                           @Schema(description = "0 for the message as the production order's events sent it; each "
                                   + "re-send by staff is the next") int generation) {
    }

    /** Which message goes to the producer again. */
    @Schema(name = "ProductionOrderResend")
    record ResendResponse(@Schema(description = "SENT or SAMPLE_APPROVED") String message, int generation) {
    }

    /** How the production order went to its producer. */
    @Schema(name = "ProductionOrderDispatch")
    record DispatchResponse(@Schema(description = "EMAIL: the system e-mails the producer; PDF: the coordinator hands "
            + "the slip over") String channel, SlipResponse slip, PageResponse<MessageResponse> messages) {
    }

    private final ProductionOrderDispatch dispatch;

    ProductionOrderDispatchController(ProductionOrderDispatch dispatch) {
        this.dispatch = dispatch;
    }

    @GetMapping("/slip")
    @Operation(summary = "A link of minutes to the slip (PDF) the production order was sent with, after checking the "
            + "stored file is still the one sent. 404 PRODUCTION_ORDER_SLIP_NOT_FOUND, 409 PRODUCTION_ORDER_SLIP_MISMATCH")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<DownloadLink> slip(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(dispatch.slipLink(id));
    }

    @GetMapping("/dispatch")
    @Operation(summary = "How the production order went to its producer: the channel, the slip and every message the "
            + "producer was sent (sent, sample approved, stopped), newest first")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<DispatchResponse> dispatch(@PathVariable UUID id,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(required = false) Integer size,
                                                  @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        ProductionOrderDispatch.DispatchView view = dispatch.dispatch(id, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size);
        return ApiResponse.ok(new DispatchResponse(view.channel(), view.slip() == null ? null
                : new SlipResponse(view.slip().sha256(), view.slip().sizeBytes(), view.slip().createdAt()),
                view.messages().map(ProductionOrderDispatchController::message)));
    }

    @PostMapping("/dispatch/resend")
    @Operation(summary = "Send the producer its latest message again (the order, or the approved sample) with fresh "
            + "links: after its retries are spent, its address was corrected, or its links died. 409 "
            + "PRODUCTION_ORDER_RESEND_NOT_ALLOWED: not sent or no longer open, a producer handed the slip, or the "
            + "latest message still on its way")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ResendResponse> resend(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var resend = dispatch.resend(id);
        return ApiResponse.ok(new ResendResponse(resend.message(), resend.generation()));
    }

    private static MessageResponse message(DeliveryAttemptSummary attempt) {
        return new MessageResponse(attempt.templateCode(), attempt.status(), attempt.attemptedAt(), attempt.sentAt(),
                attempt.failure(), attempt.attemptNumber(), attempt.recipient(), attempt.generation());
    }
}
