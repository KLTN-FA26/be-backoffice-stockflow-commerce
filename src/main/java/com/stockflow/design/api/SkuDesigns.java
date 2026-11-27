package com.stockflow.design.api;

import java.util.Collection;
import java.util.Map;

/**
 * The designs behind customers' own SKUs, for a module that lists lines by SKU - quotes, orders, contracts (FE R5-3:
 * a line that names only its SKU does not say what is printed on it). Read-only.
 */
public interface SkuDesigns {

    /**
     * The design of each of {@code skus} that is a customer's own SKU, by SKU as stored; ordinary and unknown SKUs, and
     * a SKU whose design is gone, are absent. A fixed number of statements whatever the count, so a page of lines costs
     * the same as one line. The preview links are view links, signed locally without asking the store.
     */
    Map<String, SkuDesign> ofSkus(Collection<String> skus);
}
