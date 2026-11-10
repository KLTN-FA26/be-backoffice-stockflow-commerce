package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.SkuAvailabilityResponse;
import com.stockflow.catalog.internal.service.StorefrontAvailabilityService;
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
 * <p>Guarded by {@code catalog-storefront:READ} (V20261022000200), seeded for the CUSTOMER role, scope OWN as
 * {@code /orders/me}: the answer is about the caller's own customer, taken from the token. Staff keep their
 * permission-guarded inventory APIs; a caller without an ACTIVE customer profile is refused in the service
 * whatever it was granted. The indicator never includes quantities.</p>
 *
 * <p>No prices: the storefront shows none (kltn-docs 13 BR-04, team plan 5-3). A customer sees a price only on the
 * quote, order or contract Sales sends; the internal price list behind it is staff-only.</p>
 */
@RestController
@RequestMapping("/api/v1/catalog")
@PermissionResource(code = CatalogController.STOREFRONT, group = "Catalog", label = "Storefront: my stock",
        route = "/catalog", apiPath = "/api/v1/catalog/products", actions = {Action.READ})
class CatalogController {

    /** The signed-in customer's stock indicator for its own SKUs. */
    static final String STOREFRONT = "catalog-storefront";

    /**
     * Long enough to absorb a page being refreshed, short enough that "in stock" is never more
     * than this stale (docs 13: near real time; open question G4 sets no number).
     */
    private static final Duration FRESHNESS = Duration.ofSeconds(10);

    private final StorefrontAvailabilityService availability;
    private final CatalogWebMapper mapper;

    CatalogController(StorefrontAvailabilityService availability, CatalogWebMapper mapper) {
        this.availability = availability;
        this.mapper = mapper;
    }

    /**
     * Rate-limited per user: every call reaches the database. {@code private} caching, because the
     * answer sits behind a token and a shared cache must not hand it to the next caller.
     */
    @GetMapping("/products/availability")
    @Operation(summary = "Stock indicator, in cartons, of the caller's own SKUs asked about, in the order asked")
    @RateLimit(limit = 120, perSeconds = 60, key = RateLimit.Key.USER)
    @RequiresPermission(resource = STOREFRONT, action = Action.READ, scope = DataScope.OWN)
    public ResponseEntity<ApiResponse<List<SkuAvailabilityResponse>>> availability(
            @AuthenticatedUser CurrentUser user,
            @RequestParam("sku") List<String> skus) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(FRESHNESS).cachePrivate())
                .body(ApiResponse.ok(mapper.toResponses(availability.availabilityOf(user.userId(), skus))));
    }
}
