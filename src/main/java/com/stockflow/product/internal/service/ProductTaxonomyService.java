package com.stockflow.product.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.persistence.Specs;
import com.stockflow.product.internal.entity.BrandJpaEntity;
import com.stockflow.product.internal.entity.CategoryJpaEntity;
import com.stockflow.product.internal.repository.BrandJpaRepository;
import com.stockflow.product.internal.repository.CategoryJpaRepository;
import com.stockflow.product.internal.repository.Slugs;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Brands and the category tree: the reference data a product points at (WBS 3.1.1).
 *
 * <p>Both are plain reference rows with no lifecycle beyond {@code active}, so they are kept as
 * entities without an aggregate. A code is fixed at creation and its slug derived from it; a category
 * keeps its code fixed, while a guarded move rewrites the subtree's materialised paths.
 * Deactivating is how a brand or category is retired: a product keeps pointing at it, and the
 * catalog stops offering an inactive category.</p>
 */
@Service
@Transactional
public class ProductTaxonomyService implements com.stockflow.product.api.CategoryNavigation {

    /** {@code ck_categories_depth}: a root is depth 0, so the tree has at most eleven levels. */
    static final int MAX_DEPTH = 10;

    /** {@code ck_brands_code}, {@code ck_categories_code}. */
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,49}");
    private static final SortWhitelist BRAND_SORT = SortWhitelist.of("name", "code", "createdAt")
            .withDefault("name", Sort.Direction.ASC);
    private static final SortWhitelist CATEGORY_SORT = SortWhitelist.of("path", "name", "code", "sortOrder", "createdAt")
            .withDefault("path", Sort.Direction.ASC);

    private final BrandJpaRepository brands;
    private final CategoryJpaRepository categories;
    private final EntityManager entityManager;

    public ProductTaxonomyService(BrandJpaRepository brands, CategoryJpaRepository categories,
                                  EntityManager entityManager) {
        this.brands = brands;
        this.categories = categories;
        this.entityManager = entityManager;
    }

    public record BrandView(UUID id, String code, String name, String slug, String logoUrl, boolean active,
                            long version) {
    }

    public record CategoryView(UUID id, UUID parentId, String code, String name, String slug, String path, int depth,
                               int sortOrder, String imageUrl, String seoTitle, String seoDescription,
                               boolean active, long version) {
    }

    public record CategoryDetails(String name, int sortOrder, String imageUrl, String seoTitle, String seoDescription,
                                  boolean active) {
    }

    // ------------------------------------------------------------------ brands

    @Transactional(readOnly = true)
    public PageResponse<BrandView> brands(String search, Boolean active, int page, int size, String sort) {
        Specification<BrandJpaEntity> spec = Specification
                .<BrandJpaEntity>where(Specs.<BrandJpaEntity>contains("name", search).or(Specs.contains("code", search)))
                .and(Specs.eq("active", active));
        return Pages.toResponse(brands.findAll(spec, Pages.of(page, size, BRAND_SORT.parse(sort)))
                .map(ProductTaxonomyService::view));
    }

    @Transactional(readOnly = true)
    public BrandView brand(UUID id) {
        return view(requireBrand(id));
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "product-brand", resourceId = "#result?.id()")
    public BrandView createBrand(String code, String name, String logoUrl) {
        String normalised = code(code);
        String slug = Slugs.of(normalised);
        if (brands.existsByCode(normalised) || brands.existsBySlug(slug)) {
            throw new BusinessException(ErrorCode.BRAND_CODE_ALREADY_EXISTS, "Brand " + normalised + " exists");
        }
        var brand = new BrandJpaEntity(Identifiers.newId(), normalised, slug);
        brand.setDetails(required(name, "name"), logoUrl, true);
        return view(save(brand));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product-brand", resourceId = "#id")
    public BrandView updateBrand(UUID id, String name, String logoUrl, boolean active) {
        var brand = requireBrand(id);
        brand.setDetails(required(name, "name"), logoUrl, active);
        return view(brands.saveAndFlush(brand));
    }

    // ------------------------------------------------------------------ categories

    /** {@code parentId} null lists every category; use {@code rootsOnly} for the top level. */
    @Transactional(readOnly = true)
    public PageResponse<CategoryView> categories(String search, UUID parentId, boolean rootsOnly, Boolean active,
                                                 int page, int size, String sort) {
        Specification<CategoryJpaEntity> spec = Specification
                .<CategoryJpaEntity>where(Specs.<CategoryJpaEntity>contains("name", search)
                        .or(Specs.contains("code", search)))
                .and(rootsOnly ? Specs.isNull("parentId") : Specs.eq("parentId", parentId))
                .and(Specs.eq("active", active));
        return Pages.toResponse(categories.findAll(spec, Pages.of(page, size, CATEGORY_SORT.parse(sort)))
                .map(ProductTaxonomyService::view));
    }

    @Transactional(readOnly = true)
    public CategoryView category(UUID id) {
        return view(requireCategory(id));
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "product-category", resourceId = "#result?.id()")
    public CategoryView createCategory(String code, UUID parentId, CategoryDetails details) {
        lockTree();
        String normalised = code(code);
        String slug = Slugs.of(normalised);
        if (categories.existsByCode(normalised) || categories.existsBySlug(slug)) {
            throw new BusinessException(ErrorCode.CATEGORY_CODE_ALREADY_EXISTS, "Category " + normalised + " exists");
        }
        CategoryJpaEntity parent = parentId == null ? null : requireCategory(parentId);
        if (parent != null && parent.getDepth() >= MAX_DEPTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The category tree is at most %d levels deep".formatted(MAX_DEPTH + 1));
        }
        var category = new CategoryJpaEntity(Identifiers.newId(), parent, normalised, slug);
        apply(category, details);
        return view(save(category));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "product-category", resourceId = "#id")
    public CategoryView updateCategory(UUID id, CategoryDetails details) {
        var category = requireCategory(id);
        apply(category, details);
        return view(categories.saveAndFlush(category));
    }

    /**
     * Moves a category, with its whole subtree, under {@code newParentId} — or to the root when it is null
     * (SCRUM-183). The materialised {@code path} and {@code depth} of the node and of every descendant are
     * rewritten in one transaction, parents before children, so {@code tg_categories_tree} sees each row agree with
     * its already-moved parent. Products keep pointing at the same category ids: nothing in
     * {@code product_categories} or in the catalog changes, and slugs (derived from the fixed code) stay as they
     * were. Moving to the parent it already has is a no-op.
     *
     * <p><b>Concurrency.</b> The tree's advisory lock, taken first, serialises moves and creations: two moves that
     * would each be legal alone (A under B, B under A) run one after the other, and the second sees the first's
     * paths and is refused as a cycle; a child cannot be created under the subtree between the moment its
     * descendants are read and the moment they are rewritten.</p>
     *
     * @throws BusinessException {@code CATEGORY_NOT_FOUND} for the category or the new parent;
     *         {@code VALIDATION_FAILED} (field {@code parentId}) for a move under itself or its own subtree, or one
     *         that would put any node deeper than {@value #MAX_DEPTH}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-category", resourceId = "#id")
    public CategoryView moveCategory(UUID id, UUID newParentId) {
        lockTree();
        CategoryJpaEntity category = requireCategory(id);
        if (java.util.Objects.equals(category.getParentId(), newParentId)) {
            return view(category);
        }
        CategoryJpaEntity parent = newParentId == null ? null : requireCategory(newParentId);
        String oldPath = category.getPath();
        if (parent != null && (parent.getId().equals(id) || parent.getPath().startsWith(oldPath + "/"))) {
            throw invalidParent("A category cannot move under itself or one of its own subcategories");
        }
        List<CategoryJpaEntity> descendants = categories.findByPathStartingWithOrderByDepthAscPathAsc(oldPath + "/");
        int depthChange = (parent == null ? 0 : parent.getDepth() + 1) - category.getDepth();
        int deepest = descendants.stream().mapToInt(CategoryJpaEntity::getDepth).max().orElse(category.getDepth());
        if (deepest + depthChange > MAX_DEPTH) {
            throw invalidParent("The category tree is at most %d levels deep; this move would make it %d"
                    .formatted(MAX_DEPTH + 1, deepest + depthChange + 1));
        }
        category.moveTo(parent);
        categories.saveAndFlush(category);
        String newPath = category.getPath();
        for (CategoryJpaEntity descendant : descendants) {
            descendant.repath(newPath + descendant.getPath().substring(oldPath.length()),
                    descendant.getDepth() + depthChange);
            categories.saveAndFlush(descendant);
        }
        return view(category);
    }

    private static BusinessException invalidParent(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message,
                List.of(com.stockflow.common.api.FieldError.of("parentId", message)));
    }

    // ------------------------------------------------------------------ storefront navigation (SCRUM-171)

    @Override
    @Transactional(readOnly = true)
    public List<CategoryNode> visibleTree() {
        java.util.Map<UUID, CategoryJpaEntity> visible = new java.util.LinkedHashMap<>();
        // Depth first: a parent is decided before its children, and a child of a hidden parent stays hidden.
        for (CategoryJpaEntity c : categories.findAll(Sort.by("depth", "sortOrder", "name", "code"))) {
            if (c.isActive() && (c.getParentId() == null || visible.containsKey(c.getParentId()))) {
                visible.put(c.getId(), c);
            }
        }
        return visible.values().stream().map(c -> new CategoryNode(c.getId(), c.getParentId(), c.getCode(),
                c.getName(), c.getSlug(), c.getPath(), c.getDepth(), c.getSortOrder(), c.getImageUrl(),
                c.getSeoTitle(), c.getSeoDescription())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<java.util.Set<UUID>> visibleSubtree(String slug) {
        if (slug == null || slug.isBlank()) {
            return java.util.Optional.empty();
        }
        List<CategoryNode> tree = visibleTree();
        String wanted = slug.strip().toLowerCase(Locale.ROOT);
        return tree.stream().filter(node -> node.slug().equals(wanted)).findFirst()
                .map(root -> tree.stream()
                        .filter(node -> node.path().equals(root.path()) || node.path().startsWith(root.path() + "/"))
                        .map(CategoryNode::id)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<UUID> productsIn(java.util.Collection<UUID> categoryIds) {
        return categoryIds.isEmpty() ? java.util.Set.of()
                : java.util.Set.copyOf(categories.findProductIdsInCategories(categoryIds));
    }

    private void lockTree() {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(139701751)").getSingleResult();
    }

    private static void apply(CategoryJpaEntity category, CategoryDetails d) {
        category.setDetails(required(d.name(), "name"), d.sortOrder(), d.imageUrl(), d.seoTitle(), d.seoDescription(),
                d.active());
    }

    private BrandJpaEntity requireBrand(UUID id) {
        return brands.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.BRAND_NOT_FOUND, "No brand " + id));
    }

    private CategoryJpaEntity requireCategory(UUID id) {
        return categories.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "No category " + id));
    }

    /** The unique constraints are the real guard against two creations racing past the check. */
    private BrandJpaEntity save(BrandJpaEntity brand) {
        try {
            return brands.saveAndFlush(brand);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.BRAND_CODE_ALREADY_EXISTS, "Brand " + brand.getCode() + " exists");
        }
    }

    private CategoryJpaEntity save(CategoryJpaEntity category) {
        try {
            return categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CATEGORY_CODE_ALREADY_EXISTS,
                    "Category " + category.getCode() + " exists");
        }
    }

    private static String code(String code) {
        String value = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "code must be 1-50 letters, digits, '_' or '-', starting with a letter or digit");
        }
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, field + " must be 1-255 characters");
        }
        return value.strip();
    }

    private static BrandView view(BrandJpaEntity b) {
        return new BrandView(b.getId(), b.getCode(), b.getName(), b.getSlug(), b.getLogoUrl(), b.isActive(),
                b.getVersion());
    }

    private static CategoryView view(CategoryJpaEntity c) {
        return new CategoryView(c.getId(), c.getParentId(), c.getCode(), c.getName(), c.getSlug(), c.getPath(),
                c.getDepth(), c.getSortOrder(), c.getImageUrl(), c.getSeoTitle(), c.getSeoDescription(), c.isActive(),
                c.getVersion());
    }
}
