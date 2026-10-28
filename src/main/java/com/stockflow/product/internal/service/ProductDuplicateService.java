package com.stockflow.product.internal.service;

import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.internal.domain.ProductNameKey;
import com.stockflow.product.internal.repository.ProductSearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Products that look like the same product under another code (SCRUM-88, WBS 3.1.9.2).
 *
 * <p>The exact duplicates are refused elsewhere, twice each: the code ({@code uk_products_code}), the SKU
 * ({@code uk_variants_sku}), the slug and a product's variant combination. What none of those catches is the
 * same cup created twice under two codes. kltn-docs 01 §3 identifies a product by its name, code, category and
 * brand, so two products of one brand and one primary category whose names have the same
 * {@link ProductNameKey} are reported as possible duplicates.</p>
 *
 * <p><b>Reported, never refused.</b> A wholesale catalogue legitimately holds items with the same name that
 * differ by a specification the name leaves out, so the person creating or approving the product decides. If
 * the team decides to block instead, the place to do it is the create and update of {@code ProductServiceImpl},
 * calling this.</p>
 *
 * <p>Without a brand or a category there is nothing to compare with yet: the check runs again on the edit
 * that sets them, and the approver sees the result on the product's detail.</p>
 */
@Service
@Transactional(readOnly = true)
public class ProductDuplicateService {

    private final ProductSearchRepository search;

    ProductDuplicateService(ProductSearchRepository search) {
        this.search = search;
    }

    /**
     * The products of {@code brandId} and {@code categoryId}, not discontinued, whose name has the key of
     * {@code name}, ordered by code. {@code excludeProductId} is the product being edited or shown, never its
     * own duplicate; null when there is none yet.
     */
    public List<ProductSummary> possibleDuplicates(String name, UUID brandId, UUID categoryId,
                                                   UUID excludeProductId) {
        String key = ProductNameKey.of(name);
        if (key.isEmpty() || brandId == null || categoryId == null) {
            return List.of();
        }
        return search.sameBrandAndCategory(brandId, categoryId).stream()
                .filter(p -> !p.productId().equals(excludeProductId))
                .filter(p -> key.equals(ProductNameKey.of(p.name())))
                .sorted(Comparator.comparing(ProductSummary::code))
                .toList();
    }
}
