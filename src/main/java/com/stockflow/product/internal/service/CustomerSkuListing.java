package com.stockflow.product.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.customer.api.CustomerSummaries;
import com.stockflow.customer.api.CustomerSummary;
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
 * product's tax class's rate today (14 BR-21) - so the figure shown is the one the saved line gets. A handful of
 * statements whatever the count: the SKUs and their source variants, their tax classes, their units (the source
 * variant's cartons: the SKU needs no inventory item for that, V20270105000100), the rates, and the owners' names
 * and codes ({@code customer :: api}, one read for the page: a product's SKUs belong to many customers).
 */
@Service
@Transactional(readOnly = true)
public class CustomerSkuListing {

    private final CustomerSkus customerSkus;
    private final ProductService products;
    private final UomConversionService units;
    private final ObjectProvider<TaxRatesToday> rates;
    private final ProductCustomerSkus issued;
    private final ObjectProvider<CustomerSummaries> customers;

    CustomerSkuListing(CustomerSkus customerSkus, ProductService products, UomConversionService units,
                       ObjectProvider<TaxRatesToday> rates, ProductCustomerSkus issued,
                       ObjectProvider<CustomerSummaries> customers) {
        this.customerSkus = customerSkus;
        this.products = products;
        this.units = units;
        this.rates = rates;
        this.issued = issued;
        this.customers = customers;
    }

    /**
     * @param unitsPerCarton pieces in the SKU's carton; null when it has no single carton unit yet (a quote refuses it
     *                       with {@code CARTON_SPEC_MISSING})
     * @param taxClass       its product's tax class; null while it has none
     * @param taxRate        that class's rate today, a percentage; null without a class, or where no module keeps rates
     * @param ownerName      the owner customer's name; null where the customer module does not answer (a module test)
     * @param ownerCode      the owner as a document code spells them ({@code CustomerSummary.code()}); null likewise
     */
    public record Listed(CustomerSku sku, Integer unitsPerCarton, String taxClass, BigDecimal taxRate,
                         String ownerName, String ownerCode) {
    }

    /** A customer's own SKUs, of one product and of one catalogue variant when those are given. */
    public List<Listed> ownedBy(UUID customerId, UUID productId, UUID variantId) {
        return listed(customerSkus.ownedBy(customerId, productId).stream()
                .filter(sku -> variantId == null || variantId.equals(sku.sourceVariantId())).toList());
    }

    /** Every customer's SKU drawn on a product, or on one of its catalogue variants, a page at a time ({@link ProductCustomerSkus#drawnOn}). */
    public PageResponse<Listed> drawnOn(UUID productId, UUID variantId, int page, int size) {
        PageResponse<CustomerSku> found = issued.drawnOn(productId, variantId, page, size);
        return PageResponse.of(listed(found.items()), found.page(), found.size(), found.totalElements());
    }

    /** One SKU as {@link #ownedBy} lists it: what a lifecycle move answers with. */
    public Listed one(CustomerSku sku) {
        return listed(List.of(sku)).getFirst();
    }

    private List<Listed> listed(List<CustomerSku> owned) {
        if (owned.isEmpty()) {
            return List.of();
        }
        List<String> codes = owned.stream().map(CustomerSku::sku).distinct().toList();
        Map<String, TaxClass> classes = products.taxClassesOf(codes);
        Map<String, List<UomConversion>> declared = units.conversionsOf(codes.stream().map(Sku::new).toList());
        TaxRatesToday source = rates.getIfAvailable();
        Map<String, BigDecimal> today = classes.isEmpty() || source == null ? Map.of() : source.today();
        CustomerSummaries summaries = customers.getIfAvailable();
        Map<UUID, CustomerSummary> owners = summaries == null ? Map.of()
                : summaries.summariesOf(owned.stream().map(CustomerSku::ownerCustomerId).distinct().toList());
        return owned.stream().map(sku -> {
            TaxClass taxClass = classes.get(sku.sku());
            Integer perCarton = UomConversion.cartonOf(declared.getOrDefault(sku.sku(), List.of()))
                    .map(UomConversion::factor).orElse(null);
            CustomerSummary owner = owners.get(sku.ownerCustomerId());
            return new Listed(sku, perCarton, taxClass == null ? null : taxClass.name(),
                    taxClass == null ? null : today.get(taxClass.name()), owner == null ? null : owner.fullName(),
                    owner == null ? null : owner.code());
        }).toList();
    }
}
