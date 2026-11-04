package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.service.CatalogNavigationService;
import com.stockflow.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Category navigation for the B2B storefront (SCRUM-171): the menu a signed-in customer or staff member browses the
 * catalogue by. Like {@link StorefrontCatalogController}, no {@code @RequiresPermission}: the security chain admits
 * any signed-in user, and the tree holds nothing a customer may not see — only visible categories.
 */
@RestController
@RequestMapping("/api/v1/catalog/categories")
class StorefrontCategoryController {

    /** One menu entry; {@code slug} is the {@code category} parameter of the product listing. */
    record CategoryNodeResponse(UUID id, String code, String name, String slug, String path, int depth, int sortOrder,
                                String imageUrl, String seoTitle, String seoDescription,
                                List<CategoryNodeResponse> children) { }

    private final CatalogNavigationService navigation;

    StorefrontCategoryController(CatalogNavigationService navigation) {
        this.navigation = navigation;
    }

    /**
     * The visible category tree, nested: an inactive category hides its whole subtree. No-store, as the rest of the
     * catalogue behind a token; an admin's move or rename shows on the next request.
     */
    @GetMapping
    @Operation(summary = "The storefront category tree, for navigation")
    public ResponseEntity<ApiResponse<List<CategoryNodeResponse>>> tree() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(navigation.tree().stream().map(StorefrontCategoryController::toResponse).toList()));
    }

    private static CategoryNodeResponse toResponse(CatalogNavigationService.Node node) {
        return new CategoryNodeResponse(node.id(), node.code(), node.name(), node.slug(), node.path(), node.depth(),
                node.sortOrder(), node.imageUrl(), node.seoTitle(), node.seoDescription(),
                node.children().stream().map(StorefrontCategoryController::toResponse).toList());
    }
}
