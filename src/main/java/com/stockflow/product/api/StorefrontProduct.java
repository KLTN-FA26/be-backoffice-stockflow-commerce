package com.stockflow.product.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What a storefront product page shows of a published product beyond its title and SEO (ADR-0013): the description as
 * HTML, the brand, the primary category, the descriptive attributes, and the variants a customer is shown - each with
 * its options and its own description. Never a price or a stock figure (kltn-docs 13 BR-04, BR-11).
 *
 * @param descriptionHtml the product's description: HTML the server cleaned to its allow-list; null when none
 * @param brand           null when the product has none
 * @param category        the primary category; null when none
 * @param attributes      the descriptive attributes, in their order
 * @param variants        the ACTIVE variants, in display order
 */
public record StorefrontProduct(UUID productId, String descriptionHtml, Brand brand, Category category,
                                List<Attribute> attributes, List<Variant> variants) {

    public StorefrontProduct {
        attributes = List.copyOf(attributes);
        variants = List.copyOf(variants);
    }

    public record Brand(UUID id, String name, String slug, String logoUrl) {
    }

    public record Category(UUID id, String name, String slug) {
    }

    /**
     * One descriptive attribute and its value, as its data type holds it: {@code values} (the labels) for a choice,
     * {@code text}, {@code number} (in {@code unit}) or {@code bool}; the others are empty or null.
     */
    public record Attribute(String code, String name, String dataType, String unit, List<String> values, String text,
                            BigDecimal number, Boolean bool) {
        public Attribute {
            values = List.copyOf(values);
        }
    }

    /**
     * One ACTIVE variant (quy cách).
     *
     * @param descriptionHtml the variant's own description, else the product's: what the page shows for it
     * @param ownDescription  whether {@code descriptionHtml} is the variant's own
     */
    public record Variant(UUID variantId, String sku, String name, String descriptionHtml, boolean ownDescription,
                          List<Option> options) {
        public Variant {
            options = List.copyOf(options);
        }
    }

    /** One axis value of a variant, e.g. the axis "Dung tích" with the value "12oz". */
    public record Option(String axisCode, String axisName, String valueCode, String label) {
    }
}
