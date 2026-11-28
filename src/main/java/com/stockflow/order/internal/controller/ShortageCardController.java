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
import com.stockflow.order.api.ShortageOption;
import com.stockflow.order.internal.controller.dto.ShortageCardResponse;
import com.stockflow.order.internal.controller.dto.ShortagePreviewResponse;
import com.stockflow.order.internal.service.OrderWarehouseVisibility;
import com.stockflow.order.internal.service.ShortageCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 * posted into the conversation Sales are in charge of); carrying a choice out is a later step.</p>
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

    private final ShortageCardService cards;
    private final OrderWarehouseVisibility warehouseVisibility;
    private final CustomerService customers;
    private final IdentityService identities;

    ShortageCardController(ShortageCardService cards, OrderWarehouseVisibility warehouseVisibility,
                           CustomerService customers, IdentityService identities) {
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

    /** The signed-in customer's profile id; a back-office account is no customer. */
    private UUID customerOf(CurrentUser user) {
        return customers.findByUserId(user.userId()).orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
    }
}
