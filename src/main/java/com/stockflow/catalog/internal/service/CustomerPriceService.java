package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.catalog.internal.domain.CustomerPrice;
import com.stockflow.catalog.internal.domain.CustomerPriceRepository;
import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Back-office management of customers' own prices (SCRUM-428; kltn-docs 13 §2: E-commerce Admin
 * configures price lists per customer and quantity tier).
 *
 * <p>Every change locks the SKU's product first when it is published - the lock checkout takes before
 * it prices an order - so an order is priced either wholly before or wholly after the change, never
 * from a half-written list (14 BR-03).</p>
 */
@Service
@Transactional
public class CustomerPriceService {

    private final CustomerPriceRepository prices;
    private final PriceResolver resolver;
    private final ListingRepository listings;
    private final ProductPublication publication;
    private final ProductService products;

    CustomerPriceService(CustomerPriceRepository prices, PriceResolver resolver, ListingRepository listings,
                         ProductPublication publication, ProductService products) {
        this.prices = prices;
        this.resolver = resolver;
        this.listings = listings;
        this.publication = publication;
        this.products = products;
    }

    /** A tier as entered: from {@code minQuantity} units on, {@code unitPrice} whole VND. */
    public record TierInput(int minQuantity, BigDecimal unitPrice) {
    }

    /** A customer's own price as stored; {@code moq} is its first tier. */
    public record PriceView(UUID customerId, String sku, int moq, List<TierInput> tiers, String note, long version) {
    }

    @Transactional(readOnly = true)
    public List<PriceView> list(UUID customerId, Collection<String> skus) {
        resolver.customer(customerId);
        return prices.ofCustomer(customerId, skus.stream().map(CustomerPriceService::sku).toList()).stream()
                .map(CustomerPriceService::view).toList();
    }

    @Transactional(readOnly = true)
    public PriceView get(UUID customerId, String sku) {
        return prices.find(customerId, sku(sku)).map(CustomerPriceService::view)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_PRICE_NOT_FOUND));
    }

    /** What the customer pays for the SKU now: own tiers, else group or base price. */
    @Transactional(readOnly = true)
    public PriceSchedule schedule(UUID customerId, String sku) {
        return resolver.schedule(customerId, sku(sku));
    }

    /**
     * Gives the customer its own price for a SKU.
     *
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND}, {@code VARIANT_NOT_FOUND}, {@code CONFLICT}
     *         when the customer already has one (replace it instead), {@code VALIDATION_FAILED}
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "customer-price", resourceId = "#customerId")
    public PriceView create(UUID customerId, String sku, List<TierInput> tiers, String note) {
        String code = prepare(customerId, sku);
        if (prices.findForUpdate(customerId, code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "This customer already has a price for " + code);
        }
        try {
            return view(prices.save(new CustomerPrice(Identifiers.newId(), customerId, code, domain(tiers), note, 0L)));
        } catch (DuplicateKeyException raced) {
            throw new BusinessException(ErrorCode.CONFLICT, "This customer already has a price for " + code);
        }
    }

    /** @throws BusinessException {@code CUSTOMER_PRICE_NOT_FOUND}, {@code OPTIMISTIC_LOCK}, {@code VALIDATION_FAILED} */
    @Auditable(action = AuditAction.UPDATE, resourceType = "customer-price", resourceId = "#customerId")
    public PriceView replace(UUID customerId, String sku, List<TierInput> tiers, String note,
                             long expectedVersion) {
        String code = prepare(customerId, sku);
        CustomerPrice price = prices.findForUpdate(customerId, code)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_PRICE_NOT_FOUND));
        price.replace(domain(tiers), note, expectedVersion);
        return view(prices.save(price));
    }

    /** The customer goes back to its group's or the base price. */
    @Auditable(action = AuditAction.DELETE, resourceType = "customer-price", resourceId = "#customerId")
    public void delete(UUID customerId, String sku, long expectedVersion) {
        String code = prepare(customerId, sku);
        CustomerPrice price = prices.findForUpdate(customerId, code)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_PRICE_NOT_FOUND));
        if (price.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "The price of %s for this customer changed since it was read".formatted(code));
        }
        prices.delete(price.id());
    }

    private static List<CustomerPrice.Tier> domain(List<TierInput> tiers) {
        return tiers == null ? null : tiers.stream().map(t -> new CustomerPrice.Tier(t.minQuantity(), t.unitPrice())).toList();
    }

    private static PriceView view(CustomerPrice price) {
        return new PriceView(price.customerId(), price.sku(), price.moq(),
                price.tiers().stream().map(t -> new TierInput(t.minQuantity(), t.unitPrice())).toList(),
                price.note(), price.version());
    }

    /** Validates the references and takes checkout's product lock, before anything is read for update. */
    private String prepare(UUID customerId, String sku) {
        resolver.customer(customerId);
        String code = sku(sku);
        if (products.nameForSku(code).isEmpty()) throw new BusinessException(ErrorCode.VARIANT_NOT_FOUND);
        listings.publishedProductForSku(code).ifPresent(publication::lock);
        return code;
    }

    /** The shape of {@code product.variants.sku} (ck_variants_sku), upper-cased like the storefront does. */
    private static final java.util.regex.Pattern SKU = java.util.regex.Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,63}");

    private static String sku(String raw) {
        String code = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!SKU.matcher(code).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Malformed sku: " + raw);
        }
        return code;
    }
}
