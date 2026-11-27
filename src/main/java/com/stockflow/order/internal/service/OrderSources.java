package com.stockflow.order.internal.service;

import com.stockflow.design.api.SkuDesign;
import com.stockflow.design.api.SkuDesigns;
import com.stockflow.order.internal.repository.QuoteSearch;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where an order came from, for its detail page: the quote it was converted from (R5-7) and the design behind each
 * line's SKU (R5-3). Read beside the order rather than stored on it: the quote names its order
 * ({@code ordering.quote.converted_order_id}, one per order since conversion is idempotent on the quote), and a SKU's
 * design is the design module's.
 */
@Service
@Transactional(readOnly = true)
public class OrderSources {

    private final QuoteSearch quotes;
    private final SkuDesigns designs;

    OrderSources(QuoteSearch quotes, SkuDesigns designs) {
        this.quotes = quotes;
        this.designs = designs;
    }

    /**
     * @param sourceQuoteId     the quote the order was converted from; null for an order no quote became
     * @param sourceQuoteNumber its number
     * @param designs           the design of each line's SKU, by SKU as stored; a SKU with none is absent
     */
    public record Extras(UUID sourceQuoteId, String sourceQuoteNumber, Map<String, SkuDesign> designs) {

        public static final Extras NONE = new Extras(null, null, Map.of());

        public SkuDesign design(String sku) {
            return sku == null ? null : designs.get(sku);
        }
    }

    /** Three statements whatever the size of the order: the quote, its number, and the lines' designs. */
    public Extras of(UUID orderId, Collection<String> skus) {
        UUID quoteId = orderId == null ? null : quotes.quoteConvertedInto(orderId).orElse(null);
        String number = quoteId == null ? null
                : quotes.findRow(quoteId).map(QuoteSearch.Row::quoteNumber).orElse(null);
        var distinct = skus == null ? java.util.List.<String>of()
                : skus.stream().filter(Objects::nonNull).distinct().toList();
        return new Extras(quoteId, number, distinct.isEmpty() ? Map.of() : designs.ofSkus(distinct));
    }
}
