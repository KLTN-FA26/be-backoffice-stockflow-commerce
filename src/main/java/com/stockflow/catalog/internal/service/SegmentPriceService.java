package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.domain.SegmentPrice;
import com.stockflow.catalog.internal.domain.SegmentPriceRepository;
import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CustomerService;
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
import java.util.regex.Pattern;

/**
 * Back-office management of customer-group prices (user decision P7, 2026-10-12): E-commerce Admin and System
 * Admin set them, Sales reads them and negotiates per quote instead. Resolution is unchanged: the customer's
 * own tiers, else its group's price, else the base price ({@link PriceResolver}).
 *
 * <p>Every change takes the SKU's product lock first when it is published - the lock checkout takes before it
 * prices an order - like {@link CustomerPriceService}, so an order is priced wholly before or after it. Quotes
 * and orders keep the prices they were given (14 BR-03).</p>
 */
@Service
@Transactional
public class SegmentPriceService {

    /** The shape of {@code product.variants.sku}, upper-cased like the storefront does. */
    private static final Pattern SKU = Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,63}");

    private final SegmentPriceRepository prices;
    private final CustomerService customers;
    private final ListingRepository listings;
    private final ProductPublication publication;
    private final ProductService products;

    SegmentPriceService(SegmentPriceRepository prices, CustomerService customers, ListingRepository listings,
                        ProductPublication publication, ProductService products) {
        this.prices = prices;
        this.customers = customers;
        this.listings = listings;
        this.publication = publication;
        this.products = products;
    }

    public record PriceView(UUID segmentId, String sku, BigDecimal price, long version) {
    }

    @Transactional(readOnly = true)
    public List<PriceView> list(UUID segmentId, Collection<String> skus) {
        requireSegment(segmentId);
        return prices.ofSegment(segmentId, skus.stream().map(SegmentPriceService::sku).toList()).stream()
                .map(SegmentPriceService::view).toList();
    }

    @Transactional(readOnly = true)
    public PriceView get(UUID segmentId, String sku) {
        return prices.find(segmentId, sku(sku)).map(SegmentPriceService::view)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEGMENT_PRICE_NOT_FOUND));
    }

    /**
     * @throws BusinessException {@code SEGMENT_NOT_FOUND}, {@code VARIANT_NOT_FOUND}, {@code CONFLICT} when the
     *         group already has a price for the SKU (replace it instead), {@code VALIDATION_FAILED}
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "segment-price", resourceId = "#segmentId")
    public PriceView create(UUID segmentId, String sku, BigDecimal price) {
        String code = prepare(segmentId, sku);
        if (prices.findForUpdate(segmentId, code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "This group already has a price for " + code);
        }
        try {
            return view(prices.save(new SegmentPrice(Identifiers.newId(), segmentId, code, price, 0L)));
        } catch (DuplicateKeyException raced) {
            throw new BusinessException(ErrorCode.CONFLICT, "This group already has a price for " + code);
        }
    }

    /** @throws BusinessException {@code SEGMENT_PRICE_NOT_FOUND}, {@code OPTIMISTIC_LOCK}, {@code VALIDATION_FAILED} */
    @Auditable(action = AuditAction.UPDATE, resourceType = "segment-price", resourceId = "#segmentId")
    public PriceView replace(UUID segmentId, String sku, BigDecimal price, long expectedVersion) {
        String code = prepare(segmentId, sku);
        SegmentPrice current = prices.findForUpdate(segmentId, code)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEGMENT_PRICE_NOT_FOUND));
        current.replace(price, expectedVersion);
        return view(prices.save(current));
    }

    /** The group's customers go back to the base price (or their own). */
    @Auditable(action = AuditAction.DELETE, resourceType = "segment-price", resourceId = "#segmentId")
    public void delete(UUID segmentId, String sku, long expectedVersion) {
        String code = prepare(segmentId, sku);
        SegmentPrice current = prices.findForUpdate(segmentId, code)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEGMENT_PRICE_NOT_FOUND));
        if (current.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "The group price of %s changed since it was read".formatted(code));
        }
        prices.delete(current.id());
    }

    private static PriceView view(SegmentPrice price) {
        return new PriceView(price.segmentId(), price.sku(), price.price(), price.version());
    }

    /** Validates the references and takes checkout's product lock, before anything is read for update. */
    private String prepare(UUID segmentId, String sku) {
        requireSegment(segmentId);
        String code = sku(sku);
        if (products.nameForSku(code).isEmpty()) throw new BusinessException(ErrorCode.VARIANT_NOT_FOUND);
        listings.publishedProductForSku(code).ifPresent(publication::lock);
        return code;
    }

    private void requireSegment(UUID segmentId) {
        if (segmentId == null || !customers.segmentExists(segmentId)) {
            throw new BusinessException(ErrorCode.SEGMENT_NOT_FOUND);
        }
    }

    private static String sku(String raw) {
        String code = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!SKU.matcher(code).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Malformed sku: " + raw);
        }
        return code;
    }
}
