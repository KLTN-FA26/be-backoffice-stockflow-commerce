package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.SearchSuggestionResponse;
import com.stockflow.catalog.internal.service.CatalogSearchService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.ratelimit.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Search-box completions for the storefront (SCRUM-170/181/182).
 *
 * <p>Same gate as the rest of the catalog: any signed-in user, nothing for an anonymous caller
 * (docs 13 §4.2 step 1). No {@code @RequiresPermission} for the same reason as
 * {@code CatalogController}; the answer carries no price and no stock.</p>
 */
@RestController
@RequestMapping("/api/v1/catalog/search")
class CatalogSearchController {

    private final CatalogSearchService search;
    private final CatalogCommerceWebMapper mapper;

    CatalogSearchController(CatalogSearchService search, CatalogCommerceWebMapper mapper) {
        this.search = search;
        this.mapper = mapper;
    }

    /**
     * A box that fires while the shopper types hits the database on every call, hence the budget per
     * user: 30 calls per 10 seconds is far above a 250 ms debounce and far below a scraper.
     * {@code no-store}: the text typed is a customer's own and the answer sits behind a token.
     */
    @GetMapping("/suggestions")
    @Operation(summary = "Title completions for the search box (at least 2 characters, at most 10 results)")
    @RateLimit(limit = 30, perSeconds = 10, key = RateLimit.Key.USER)
    public ResponseEntity<ApiResponse<List<SearchSuggestionResponse>>> suggestions(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "" + CatalogSearchService.DEFAULT_SUGGESTIONS) int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(search.suggest(q, limit).stream().map(mapper::toResponse).toList()));
    }
}
