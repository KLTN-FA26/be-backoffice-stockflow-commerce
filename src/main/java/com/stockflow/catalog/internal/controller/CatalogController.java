package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.SkuAvailabilityResponse;
import com.stockflow.catalog.internal.service.StorefrontAvailabilityService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.ratelimit.RateLimit;
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
 * Catalog reads for signed-in customers and staff (SCRUM-158).
 *
 * <p>Not under {@code /api/v1/public/**}. Since 2026-10-06 the business sells only to wholesale
 * customers it has onboarded, so there is no anonymous shopper to serve, and stock levels are
 * commercially sensitive — a competitor polling an open endpoint could read a customer's run
 * rate off them. The security chain's {@code anyRequest().authenticated()} is the gate.</p>
 *
 * <p>No {@code @RequiresPermission}: every role that can sign in, the portal customer included,
 * is meant to see the indicator, and it says only in stock / low / out — never a quantity.</p>
 */
@RestController
@RequestMapping("/api/v1/catalog")
class CatalogController {

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
    @Operation(summary = "Stock indicator for the SKUs on a catalog page, in the order asked")
    @RateLimit(limit = 120, perSeconds = 60, key = RateLimit.Key.USER)
    public ResponseEntity<ApiResponse<List<SkuAvailabilityResponse>>> availability(
            @RequestParam("sku") List<String> skus) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(FRESHNESS).cachePrivate())
                .body(ApiResponse.ok(mapper.toResponses(availability.availabilityOf(skus))));
    }
}
