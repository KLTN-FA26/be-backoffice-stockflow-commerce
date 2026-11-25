package com.stockflow.order.internal.service;

import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What the SKU of each line of a quote, an order, a contract or a return is called: its product's name and its own
 * (variant) name. A customer does not know their SKUs by code, and a screen that showed only the code looked each name up
 * itself.
 *
 * <p>Read for a whole document at once, never per line: one read of the variant names, one of the customer's own SKUs
 * (which name their product), then one product read per distinct product - a document's lines are a handful of SKUs of
 * one or two products, and {@code product :: api} has no batched product-name read. Only a SKU that is not a customer's
 * own (a legacy line's blank) is asked for its product's name on its own.</p>
 */
@Component
public class LineNames {

    /**
     * @param productName the name of the product the SKU belongs to; null when the product module does not know it
     * @param variantName the SKU's own name; null for an unknown SKU
     */
    public record Names(String productName, String variantName) {

        public static final Names NONE = new Names(null, null);
    }

    private final ProductService products;
    private final CustomerSkus customerSkus;

    LineNames(ProductService products, CustomerSkus customerSkus) {
        this.products = products;
        this.customerSkus = customerSkus;
    }

    /** The names of each of {@code skus}, by SKU as given; a SKU nothing is known of is absent. */
    public Map<String, Names> of(Collection<String> skus) {
        List<String> distinct = skus == null ? List.of() : skus.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<String, String> variants = products.variantNamesOf(distinct);
        Map<String, CustomerSku> own = customerSkus.findAll(distinct);
        Map<UUID, String> productNames = new HashMap<>();
        Map<String, Names> names = new HashMap<>();
        for (String sku : distinct) {
            CustomerSku customerSku = own.get(sku);
            String productName;
            if (customerSku != null && customerSku.productId() != null) {
                UUID productId = customerSku.productId();
                if (!productNames.containsKey(productId)) {
                    productNames.put(productId, products.findById(productId).map(ProductSummary::name).orElse(null));
                }
                productName = productNames.get(productId);
            } else {
                productName = products.nameForSku(sku).orElse(null);
            }
            String variantName = variants.get(sku);
            if (variantName == null && customerSku != null) {
                variantName = customerSku.name();
            }
            if (productName != null || variantName != null) {
                names.put(sku, new Names(productName, variantName));
            }
        }
        return names;
    }

    /** {@code names.get(sku)}, never null. */
    public static Names of(Map<String, Names> names, String sku) {
        return sku == null ? Names.NONE : names.getOrDefault(sku, Names.NONE);
    }
}
