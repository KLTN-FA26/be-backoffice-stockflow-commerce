package com.stockflow.product.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The category tree as the storefront navigates it (SCRUM-171, WBS 3.13.4). {@code product.categories} is the one
 * category store: the catalog reads it through here and keeps no copy of its own, so an admin's rename, move or
 * deactivation is what customers see on their next request.
 *
 * <p><b>Visible</b> means the category and every ancestor of it are active: deactivating a category hides its
 * whole subtree from navigation without touching the children's own flags.</p>
 */
public interface CategoryNavigation {

    /** One visible category; {@code parentId} null for a root. */
    record CategoryNode(UUID id, UUID parentId, String code, String name, String slug, String path, int depth,
                        int sortOrder, String imageUrl, String seoTitle, String seoDescription) { }

    /** Every visible category, parents before children, siblings by sort order then name. */
    List<CategoryNode> visibleTree();

    /**
     * The visible category with this slug and every visible category under it — what browsing it covers.
     *
     * @return empty for an unknown slug, or a category that is not visible
     */
    Optional<Set<UUID>> visibleSubtree(String slug);

    /** The products assigned to any of {@code categoryIds}, primary or not; whether they are published is not asked. */
    Set<UUID> productsIn(Collection<UUID> categoryIds);
}
