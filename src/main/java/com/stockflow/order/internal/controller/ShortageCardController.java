package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.ShortageOption;
import com.stockflow.order.internal.controller.dto.CancellationFeeRequest;
import com.stockflow.order.internal.service.OrderOperations;
import com.stockflow.order.internal.service.ShortageCardExecutionService;
import com.stockflow.order.internal.controller.dto.ShortageCardResponse;
import com.stockflow.order.internal.controller.dto.ShortagePreviewResponse;
import com.stockflow.order.internal.service.OrderWarehouseVisibility;
import com.stockflow.order.internal.service.ShortageCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The shortage choice card of an order (kltn-docs 17 §4.14, BR-33; 16 BR-15): for Sales, what a card could be sent for
 * the order now and the cards it has had; for the customer, the live card of their order and their one choice on it
 * (the same operation as the chat card's {@code CHOOSE} action).
 *
 * <p>No resource of its own: reading is reading orders ({@code sales-orders:READ}, back office only here - the portal
 * customer holds it for their own orders, which the other two endpoints use with scope OWN) and the customer's choice
 * is updating their own order ({@code sales-orders:UPDATE}, OWN), as for the cancellation they may ask for. The Sales
 * side that <em>sends</em> a card lives in the chat ({@code POST /chat/conversations/{id}/cards/shortage}: the card is
 * posted into the conversation Sales are in charge of).</p>
 *
 * <p>Carrying the customer's choice out (17 §4.14 step 58) answers to the permission of the operation each option runs,
 * back office only: waiting lifts the hold ({@code sales-order-holds:UPDATE}; moving the window too needs the
 * window's own {@code sales-contracts:UPDATE}); shipping part allows partial shipment and lifts the hold
 * ({@code sales-order-partial-shipments:UPDATE} and {@code sales-order-holds:UPDATE}); closing the rest and cancelling
 * are cancellations ({@code sales-order-cancellations:UPDATE}, as {@code /remainder-closure} and
 * {@code /admin-cancellation}). The second permission of an endpoint is checked by {@link CurrentUser#require}, the
 * codebase's way to ask for one beyond the endpoint's own.</p>
 *
 * <p>Handler methods are public: the permission aspect needs a proxied public method. Another customer's order or card
 * is 404, as one that does not exist.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
class ShortageCardController {

    /** The customer's choice: the option, an optional note, and the version of the card they saw. */
    @Schema(name = "ShortageChoiceRequest")
    record ChoiceRequest(@Schema(description = "One of the options the card offers; required - checked after the card's "
            + "owner, so another customer's card is 404 whatever the body says") ShortageOption option,
                         @Size(max = 500, message = "note is at most 500 characters") String note,
                         @Schema(description = "The card's version the customer saw; a stale one is 409") Long version) {
    }

    /** Sales' own words added after the system's note; 400 leaves room for it within the steps' 500 characters. */
    private static final int NOTE_MAX = 400;

    /**
     * Wait for production (D48): with {@code moveWindow} the batch's window moves to the card's expected date and ends on
     * {@code windowEnd} - only when the card said that date is after the window.
     */
    @Schema(name = "ShortageWaitExecutionRequest")
    record WaitRequest(@Schema(description = "The card's version Sales saw; a stale one is 409") Long version,
                       @Schema(description = "Move the batch's window to the card's expected date as well (only when "
                               + "the card's afterWindow is true)") Boolean moveWindow,
                       @Schema(description = "With moveWindow: the last day of the new window, on or after the card's "
                               + "expected date") LocalDate windowEnd,
                       @Size(max = NOTE_MAX, message = "note is at most 400 characters") String note) {
    }

    /** Ship part (step 1 of "ship part, close the rest"). */
    @Schema(name = "ShortageShipPartExecutionRequest")
    record ShipPartRequest(Long version, @Size(max = NOTE_MAX, message = "note is at most 400 characters") String note) {
    }

    /** Close the rest (step 2): the remainder closure's own fields. */
    @Schema(name = "ShortageCloseRestExecutionRequest")
    record CloseRestRequest(Long version,
                            @NotNull(message = "reasonCode is required") CancellationReasonCode reasonCode,
                            @Size(max = NOTE_MAX, message = "note is at most 400 characters") String note,
                            @Valid CancellationFeeRequest agreedFee) {
    }

    /** Cancel the order (reason OUT_OF_STOCK): the staff cancellation's fee fields. */
    @Schema(name = "ShortageCancelExecutionRequest")
    record CancelRequest(Long version,
                         @Size(max = NOTE_MAX, message = "note is at most 400 characters") String note,
                         @DecimalMin(value = "0.0", message = "retainedPercent is at least 0")
                         @DecimalMax(value = "100.0", message = "retainedPercent is at most 100")
                         BigDecimal retainedPercent,
                         @Valid CancellationFeeRequest agreedFee) {
    }

    private final ShortageCardService cards;
    private final OrderWarehouseVisibility warehouseVisibility;
    private final CustomerService customers;
    private final IdentityService identities;
    private final OrderOperations operations;

    ShortageCardController(ShortageCardService cards, OrderWarehouseVisibility warehouseVisibility,
                           CustomerService customers, IdentityService identities, OrderOperations operations) {
        this.operations = operations;
        this.cards = cards;
        this.warehouseVisibility = warehouseVisibility;
        this.customers = customers;
        this.identities = identities;
    }

    @GetMapping("/{orderId}/shortage")
    @Operation(summary = "The shortage choice card of an order, for Sales: whether a card can be sent now (and, if not, "
            + "why: NOT_ON_HOLD, NOTHING_SHORT, LIVE_CARD, NO_BATCH), the batch, the cartons short, the options it "
            + "would offer with their money and date figures, the live card and the order's cards. Back office only")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<ShortagePreviewResponse> preview(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        warehouseVisibility.requireVisible(orderId, user);
        ShortageCardService.Preview preview = cards.preview(orderId);
        return ApiResponse.ok(ShortagePreviewResponse.of(preview, identities.displayNames(
                ShortagePreviewResponse.peopleOf(preview))));
    }

    @GetMapping("/me/{orderId}/shortage-cards/live")
    @Operation(summary = "The live shortage card of one of the signed-in customer's own orders, for its page on the "
            + "storefront; null data when there is none. Another customer's order is 404")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<ShortageCardResponse> live(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(cards.liveCardOf(customerOf(user), orderId).map(ShortageCardResponse::of).orElse(null));
    }

    @PostMapping("/me/{orderId}/shortage-cards/{cardId}/choice")
    @Operation(summary = "Choose one of the options a shortage card offers (the customer; once per card - the choice is "
            + "the evidence of their consent). 409 SHORTAGE_CARD_STATE_CONFLICT when the card was chosen on, replaced or "
            + "ended, or the version is stale; 409 SHORTAGE_OPTION_NOT_AVAILABLE; another customer's card is 404")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.UPDATE, scope = DataScope.OWN)
    public ApiResponse<ShortageCardResponse> choose(@PathVariable UUID orderId, @PathVariable UUID cardId,
                                                    @Valid @RequestBody ChoiceRequest request,
                                                    @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ShortageCardResponse.of(cards.chooseOnOrder(customerOf(user), user.userId(), orderId,
                cardId, request.option(), request.note(), request.version())));
    }

    // ---- carrying the choice out (17 §4.14 step 58) -------------------------------------------------------------------

    @PostMapping("/{orderId}/shortage-cards/{cardId}/execution/wait-for-production")
    @Operation(summary = "Carry out the customer's choice to wait for production: the short pick lines are given up "
            + "(their holds released), the INVENTORY hold is lifted and the warehouse manager and the production "
            + "coordinator are told; with moveWindow the batch's window moves to the card's expected date, the card being "
            + "the consent (17 BR-28, D48 - needs sales-contracts:UPDATE too). 409 SHORTAGE_CARD_STATE_CONFLICT, 409 "
            + "SHORTAGE_OPTION_NOT_AVAILABLE when the customer chose another option, 404 SHORTAGE_CARD_NOT_FOUND; a "
            + "refusal of a step (e.g. DELIVERY_BATCH_WINDOW_LOCKED) changes nothing. Back office only")
    @RequiresPermission(resource = OrderResources.HOLDS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ShortageCardResponse> executeWait(@PathVariable UUID orderId, @PathVariable UUID cardId,
                                                         @Valid @RequestBody WaitRequest request,
                                                         @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        boolean move = Boolean.TRUE.equals(request.moveWindow());
        if (move) {
            user.require(OrderResources.SALES_CONTRACTS, Action.UPDATE);
        }
        return ApiResponse.ok(ShortageCardResponse.of(operations.executeShortageWait(user, orderId, cardId,
                request.version(), new ShortageCardExecutionService.WindowMove(move, request.windowEnd()),
                request.note())));
    }

    @PostMapping("/{orderId}/shortage-cards/{cardId}/execution/ship-part")
    @Operation(summary = "Carry out step 1 of the customer's choice to ship part and close the rest: the short pick "
            + "lines are given up, partial shipment is allowed and the hold lifted; the warehouse manager is told. Once "
            + "the warehouse completed the partial check, Sales are told to close the rest. Needs "
            + "sales-order-holds:UPDATE too. Back office only")
    @RequiresPermission(resource = OrderResources.PARTIAL_SHIPMENTS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ShortageCardResponse> executeShipPart(@PathVariable UUID orderId, @PathVariable UUID cardId,
                                                             @Valid @RequestBody ShipPartRequest request,
                                                             @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        user.require(OrderResources.HOLDS, Action.UPDATE);
        return ApiResponse.ok(ShortageCardResponse.of(operations.executeShortageShipPart(orderId, cardId,
                request.version(), request.note(), user.userId())));
    }

    @PostMapping("/{orderId}/shortage-cards/{cardId}/execution/close-rest")
    @Operation(summary = "Carry out step 2 of the customer's choice to ship part and close the rest: the remainder "
            + "closure as POST /orders/{id}/remainder-closure runs it (labelled cartons not handed over stay and are "
            + "delivered; the fee only as that closure allows it); the card is then carried out. 409 "
            + "SHORTAGE_CARD_STATE_CONFLICT before step 1. Back office only")
    @RequiresPermission(resource = OrderResources.ORDER_CANCELLATIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ShortageCardResponse> executeCloseRest(@PathVariable UUID orderId, @PathVariable UUID cardId,
                                                              @Valid @RequestBody CloseRestRequest request,
                                                              @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(ShortageCardResponse.of(operations.executeShortageCloseRest(orderId, cardId,
                request.version(), request.reasonCode(), request.note(), OrderWebMapper.toFee(request.agreedFee()),
                user.userId())));
    }

    @PostMapping("/{orderId}/shortage-cards/{cardId}/execution/cancel")
    @Operation(summary = "Carry out the customer's choice to cancel: the short pick lines are given up, then the staff "
            + "cancellation with reason OUT_OF_STOCK and the fee as that cancellation allows it (17 §4.8 step 30); "
            + "picked cartons go to the put-back queue. Back office only")
    @RequiresPermission(resource = OrderResources.ORDER_CANCELLATIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ShortageCardResponse> executeCancel(@PathVariable UUID orderId, @PathVariable UUID cardId,
                                                           @Valid @RequestBody CancelRequest request,
                                                           @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(ShortageCardResponse.of(operations.executeShortageCancel(orderId, cardId,
                request.version(), request.note(), request.retainedPercent(),
                OrderWebMapper.toFee(request.agreedFee()), user.userId())));
    }

    /** The signed-in customer's profile id; a back-office account is no customer. */
    private UUID customerOf(CurrentUser user) {
        return customers.findByUserId(user.userId()).orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
    }
}
