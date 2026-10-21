package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.SkuAvailabilityResponse;
import com.stockflow.catalog.internal.controller.dto.PriceScheduleResponse;
import com.stockflow.catalog.internal.service.StorefrontAvailabilityService;
import com.stockflow.catalog.internal.service.StorefrontPriceService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Catalog reads for signed-in, active portal customers (SCRUM-429).
 *
 * <p>Not under {@code /api/v1/public/**}. Since 2026-10-06 the business sells only to wholesale
 * customers it has onboarded, so there is no anonymous shopper to serve, and stock levels are
 * commercially sensitive — a competitor polling an open endpoint could read a customer's run
 * rate off them. Both the authenticated subject and its customer profile are checked.</p>
 *
 * <p>Guarded by {@code catalog-storefront:READ} (V20261021000600), seeded for the CUSTOMER role, scope OWN as
 * {@code /orders/me}: the answer is about the caller's own customer, taken from the token. Staff keep their
 * permission-guarded inventory and price APIs; a caller without an ACTIVE customer profile is refused in the service
 * whatever it was granted. The indicator never includes quantities or prices.</p>
 */
@RestController
@RequestMapping("/api/v1/catalog")
@PermissionResource(code = CatalogController.STOREFRONT, group = "Catalog", label = "Storefront: my prices and stock",
        route = "/catalog", apiPath = "/api/v1/catalog/products", actions = {Action.READ})
class CatalogController {

    /** The signed-in customer's own prices and stock indicator. */
    static final String STOREFRONT = "catalog-storefront";

    /**
     * Long enough to absorb a page being refreshed, short enough that "in stock" is never more
     * than this stale (docs 13: near real time; open question G4 sets no number).
     */
    private static final Duration FRESHNESS = Duration.ofSeconds(10);

    private final StorefrontAvailabilityService availability;
    private final StorefrontPriceService prices;
    private final CatalogWebMapper mapper;

    CatalogController(StorefrontAvailabilityService availability, StorefrontPriceService prices,
                      CatalogWebMapper mapper) {
        this.availability = availability;
        this.prices = prices;
        this.mapper = mapper;
    }

    /**
     * Rate-limited per user: every call reaches the database. {@code private} caching, because the
     * answer sits behind a token and a shared cache must not hand it to the next caller.
     */
    @GetMapping("/products/availability")
    @Operation(summary = "Stock indicator for the SKUs on a catalog page, in the order asked")
    @RateLimit(limit = 120, perSeconds = 60, key = RateLimit.Key.USER)
    @RequiresPermission(resource = STOREFRONT, action = Action.READ, scope = DataScope.OWN)
    public ResponseEntity<ApiResponse<List<SkuAvailabilityResponse>>> availability(
            @AuthenticatedUser CurrentUser user,
            @RequestParam("sku") List<String> skus) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(FRESHNESS).cachePrivate())
                .body(ApiResponse.ok(mapper.toResponses(availability.availabilityOf(user.userId(), skus))));
    }

    /**
     * The logged-in customer's prices for the SKUs of a catalog page: its own tiers, else its group's or
     * the base price, with the MOQ (kltn-docs 13 BR-02, BR-05; SCRUM-428). Unpublished or unpriced SKUs are
     * left out. Not cached: a price edit must show at once, and the checkout re-checks it anyway.
     */
    @GetMapping("/products/prices")
    @Operation(summary = "The logged-in customer's prices, tiers and MOQ for the SKUs asked")
    @RateLimit(limit = 120, perSeconds = 60, key = RateLimit.Key.USER)
    @RequiresPermission(resource = STOREFRONT, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<List<PriceScheduleResponse>> prices(@AuthenticatedUser CurrentUser user,
                                                           @RequestParam("sku") List<String> skus) {
        return ApiResponse.ok(prices.pricesOf(user.userId(), skus).stream()
                .map(CustomerPriceController::toResponse).toList());
    }
}
