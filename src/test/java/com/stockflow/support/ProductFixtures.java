package com.stockflow.support;

import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.product.internal.service.ProductTaxonomyService.CategoryDetails;

import java.util.UUID;

/**
 * Products as the product module itself makes them, for tests of what happens around a product's life
 * (SCRUM-461): created with a brand and a category, submitted and approved by two different people, so the
 * default variant is ACTIVE and the SKU has an inventory item.
 */
public final class ProductFixtures {

    private ProductFixtures() {
    }

    /** A new brand and a root category, for a product that can be submitted. */
    public record Taxonomy(UUID brandId, UUID categoryId) {
    }

    public static Taxonomy taxonomy(ProductTaxonomyService taxonomy, String suffix) {
        var brand = taxonomy.createBrand("BR-" + suffix, "Brand " + suffix, null);
        var category = taxonomy.createCategory("CAT-" + suffix, null,
                new CategoryDetails("Category " + suffix, 0, null, null, null, true));
        return new Taxonomy(brand.id(), category.id());
    }

    /** A DRAFT product with the given taxonomy; its code, and its default variant's SKU, is {@code FX-<suffix>}. */
    public static ProductSummary draft(ProductService products, Taxonomy taxonomy, String suffix) {
        return products.create(new CreateProductCommand("FX-" + suffix, "Fixture " + suffix, "Fixture " + suffix,
                taxonomy.brandId(), taxonomy.categoryId(), null, null, null, TaxClass.STANDARD,
                ProductKind.STANDARD, null));
    }

    /** Submitted by {@code editor}, approved by {@code approver}: APPROVED, its variant ACTIVE. */
    public static ProductSummary approved(ProductService products, Taxonomy taxonomy, String suffix, UUID editor,
                                          UUID approver) {
        ProductSummary created = draft(products, taxonomy, suffix);
        products.submit(created.productId(), editor);
        return products.approve(created.productId(), approver);
    }
}
