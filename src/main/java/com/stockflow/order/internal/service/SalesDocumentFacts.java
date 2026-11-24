package com.stockflow.order.internal.service;

import com.stockflow.common.document.Letterhead;
import com.stockflow.common.document.SellerLetterhead;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a quotation and a sales contract print beside their own data: our letterhead, and each SKU's product name. Read
 * here once for both, so the two papers of one sale show the same company and call the same goods by the same name.
 *
 * <p>The letterhead is the deployment's as it is now, not a copy kept with the document: a phone number or a bank
 * account is how to reach and pay the company today. The seller's legal identity - name, tax code, address,
 * representative - is another matter, and a contract keeps the one it was drawn up with
 * ({@code ordering.sales_contract.seller_*}).</p>
 */
@Component
class SalesDocumentFacts {

    private final SellerLetterhead letterhead;
    private final CustomerSkus customerSkus;

    SalesDocumentFacts(SellerLetterhead letterhead, CustomerSkus customerSkus) {
        this.letterhead = letterhead;
        this.customerSkus = customerSkus;
    }

    Letterhead head() {
        return letterhead.get();
    }

    /**
     * Each SKU's name, as quotes and orders show it. A SKU the product module has no name for (a legacy line) is
     * absent: the document then shows its code where the name would be.
     */
    Map<String, String> names(Collection<String> skus) {
        Map<String, String> names = new LinkedHashMap<>();
        skus.stream().distinct().forEach(sku -> customerSkus.find(sku).map(CustomerSku::name)
                .ifPresent(name -> names.put(sku, name)));
        return names;
    }
}
