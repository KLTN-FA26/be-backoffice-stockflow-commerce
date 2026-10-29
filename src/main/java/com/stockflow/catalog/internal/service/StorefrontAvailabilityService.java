package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.domain.Availability;
import com.stockflow.catalog.internal.domain.SkuAvailability;
import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.customer.api.CustomerService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The stock indicator a storefront page shows for its SKUs (SCRUM-158, docs 13 BR-03).
 *
 * <p>Available to promise is inventory's figure and is read through {@code inventory :: api} in
 * one query for the whole page. It is system-wide: docs 13 BR-06 computes it per region only when
 * selling by region is switched on, which this system has no setting for (open question C3).</p>
 *
 * <p>Not cached. Docs 13 asks for "near real time" without a number (open question G4); a stale
 * "in stock" is what makes a customer reach checkout for something already gone. The endpoint's
 * own short {@code Cache-Control} absorbs a page being refreshed.</p>
 */
@Service
public class StorefrontAvailabilityService {

    private final InventoryService inventory;
    private final AvailabilityProperties properties;
    private final CustomerService customers;
    private final ListingRepository listings;

    public StorefrontAvailabilityService(InventoryService inventory, AvailabilityProperties properties,
                                        CustomerService customers, ListingRepository listings) {
        this.inventory = inventory;
        this.properties = properties;
        this.customers = customers;
        this.listings = listings;
    }

    /**
     * @param userId the authenticated identity subject, not a client-selected customer id
     * @param codes the SKUs asked about, in display order; duplicates collapse to one answer
     * @return one entry per distinct SKU, in the order asked; an unknown SKU is out of stock
     * @throws BusinessException {@code VALIDATION_FAILED} for no SKU, too many, or a malformed one
     */
    public List<SkuAvailability> availabilityOf(UUID userId, List<String> codes) {
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        // Resolve the profile from the authenticated subject; never accept a client customer id.
        customers.findByUserId(userId).filter(customer -> "ACTIVE".equals(customer.status()))
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        Set<Sku> asked = parse(codes);
        // Only SKUs of a published, enabled listing are answered, decided by the same rule as the storefront
        // catalog (ListingRepository#listedBySku); the rest are dropped before inventory is queried.
        Set<String> listed = listings.listedBySku(asked.stream().map(Sku::code).toList()).keySet();
        Set<Sku> skus = asked.stream().filter(sku -> listed.contains(sku.code()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (skus.isEmpty()) return List.of();
        Map<Sku, Integer> atp = inventory.availableToPromise(skus);
        return skus.stream()
                .map(sku -> new SkuAvailability(sku,
                        Availability.of(atp.getOrDefault(sku, 0), properties.lowStockThreshold())))
                .toList();
    }

    private Set<Sku> parse(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "At least one sku is required");
        }
        Set<Sku> skus = new LinkedHashSet<>();
        for (String code : codes) {
            String normalised = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
            try {
                skus.add(new Sku(normalised));
            } catch (IllegalArgumentException malformed) {
                // A BusinessException, not the IllegalArgumentException itself: the global handler
                // logs those at ERROR as programming mistakes, and a typo in a URL is not one.
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Malformed sku: " + code);
            }
        }
        if (skus.size() > properties.maxSkusPerRequest()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "At most " + properties.maxSkusPerRequest() + " skus per request");
        }
        return skus;
    }
}
