package com.stockflow.catalog.internal.service;

import com.stockflow.product.api.CategoryNavigation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The storefront's category navigation (SCRUM-171, WBS 3.13.4): the visible category tree, nested, read from
 * {@code product}'s categories on every request. The catalog keeps no category data of its own — a second copy is
 * exactly the competing category concept the backlog warns against, and the tree is small enough to read whole.
 */
@Service
public class CatalogNavigationService {

    /**
     * One menu entry. {@code slug} is what {@code GET /api/v1/catalog/products?category=} takes; browsing it covers
     * its children too.
     */
    public record Node(UUID id, String code, String name, String slug, String path, int depth, int sortOrder,
                       String imageUrl, String seoTitle, String seoDescription, List<Node> children) { }

    private final CategoryNavigation categories;

    public CatalogNavigationService(CategoryNavigation categories) {
        this.categories = categories;
    }

    /** The visible roots, each with its visible subtree; siblings by sort order, then name. */
    @Transactional(readOnly = true)
    public List<Node> tree() {
        List<CategoryNavigation.CategoryNode> flat = categories.visibleTree();
        Map<UUID, List<CategoryNavigation.CategoryNode>> children = new LinkedHashMap<>();
        List<CategoryNavigation.CategoryNode> roots = new ArrayList<>();
        for (CategoryNavigation.CategoryNode node : flat) {
            if (node.parentId() == null) {
                roots.add(node);
            } else {
                children.computeIfAbsent(node.parentId(), id -> new ArrayList<>()).add(node);
            }
        }
        return roots.stream().map(root -> nest(root, children)).toList();
    }

    private static Node nest(CategoryNavigation.CategoryNode node,
                             Map<UUID, List<CategoryNavigation.CategoryNode>> children) {
        return new Node(node.id(), node.code(), node.name(), node.slug(), node.path(), node.depth(), node.sortOrder(),
                node.imageUrl(), node.seoTitle(), node.seoDescription(),
                children.getOrDefault(node.id(), List.of()).stream().map(child -> nest(child, children)).toList());
    }
}
