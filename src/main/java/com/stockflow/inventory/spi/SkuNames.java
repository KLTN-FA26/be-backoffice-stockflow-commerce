package com.stockflow.inventory.spi;

import java.util.Collection;
import java.util.Map;

/**
 * Asks the module that owns the catalogue what each SKU is called, for the carton label (SCRUM-473): printed goods are
 * made to each customer's design, so two SKUs of one cup look alike on a shelf and the name tells a person what is in
 * the box. Implemented by {@code product}; see the package documentation for why the interface lives here.
 */
public interface SkuNames {

    /**
     * Runs in the caller's transaction. One question for a whole label sheet, never one per carton.
     *
     * @param skus SKU codes as inventory stores them
     * @return each of {@code skus} the catalogue knows, mapped to its name; an unknown SKU is absent
     */
    Map<String, String> namesOf(Collection<String> skus);

    /**
     * What each SKU is called, both ways a screen shows it (#454, the carton list): the product it is a variant of and
     * the variant itself ({@link #namesOf}'s name). Runs in the caller's transaction; one question for a whole page.
     *
     * @return each of {@code skus} the catalogue knows; an unknown SKU is absent
     */
    Map<String, ItemNames> itemNamesOf(Collection<String> skus);

    /** A SKU's product name and variant name. */
    record ItemNames(String productName, String variantName) {
    }
}
