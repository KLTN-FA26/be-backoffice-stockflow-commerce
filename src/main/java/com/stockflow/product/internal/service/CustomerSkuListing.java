package com.stockflow.product.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.spi.TaxRatesToday;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's own SKUs as the quote editor lists them (R5-4): each with the pieces in its carton and the VAT rate a
 * quote line drawn up today would copy, so Sales see a line's total before the draft is saved. Read the way the quote
 * reads them - the carton is the SKU's one carton unit ({@link UomConversion#cartonOf}, 01 BR-03), the rate its
 * product's tax class's rate today (14 BR-21) - so the figure shown is the one the saved line gets. Four statements
 * whatever the count: the SKUs, their tax classes, their units, the rates.
 */
@Service
@Transactional(readOnly = true)
public class CustomerSkuListing {

    private final CustomerSkus customerSkus;
    private final ProductService products;
    private final UomConversionService units;
    private final ObjectProvider<TaxRatesToday> rates;

    CustomerSkuListing(CustomerSkus customerSkus, ProductService products, UomConversionService units,
                       ObjectProvider<TaxRatesToday> rates) {
        this.customerSkus = customerSkus;
        this.products = products;
        this.units = units;
        this.rates = rates;
    }

    /**
     * @param unitsPerCarton pieces in the SKU's carton; null when it has no single carton unit yet (a quote refuses it
     *                       with {@code CARTON_SPEC_MISSING})
     * @param taxClass       its product's tax class; null while it has none
     * @param taxRate        that class's rate today, a percentage; null without a class, or where no module keeps rates
     */
    public record Listed(CustomerSku sku, Integer unitsPerCarton, String taxClass, BigDecimal taxRate) {
    }

    public List<Listed> ownedBy(UUID customerId, UUID productId) {
        List<CustomerSku> owned = customerSkus.ownedBy(customerId, productId);
        if (owned.isEmpty()) {
            return List.of();
        }
        List<String> codes = owned.stream().map(CustomerSku::sku).distinct().toList();
        Map<String, TaxClass> classes = products.taxClassesOf(codes);
        Map<String, List<UomConversion>> declared = units.conversionsOf(codes.stream().map(Sku::new).toList());
        TaxRatesToday source = rates.getIfAvailable();
        Map<String, BigDecimal> today = classes.isEmpty() || source == null ? Map.of() : source.today();
        return owned.stream().map(sku -> {
            TaxClass taxClass = classes.get(sku.sku());
            Integer perCarton = UomConversion.cartonOf(declared.getOrDefault(sku.sku(), List.of()))
                    .map(UomConversion::factor).orElse(null);
            return new Listed(sku, perCarton, taxClass == null ? null : taxClass.name(),
                    taxClass == null ? null : today.get(taxClass.name()));
        }).toList();
    }
}
