package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The portal's price display (kltn-docs 13 BR-02/BR-05): the logged-in customer's own price - or its
 * group's or the base price - with tiers and MOQ, for published SKUs only (BR-01). Like availability,
 * the customer comes from the authenticated subject, never from the request, and must be ACTIVE (18
 * BR-01a). A SKU with no price is left out: it cannot be ordered either.
 */
@Service
@Transactional(readOnly = true)
public class StorefrontPriceService {

    private static final int MAX_SKUS = 50;

    private final CustomerService customers;
    private final PriceResolver resolver;
    private final ListingRepository listings;

    StorefrontPriceService(CustomerService customers, PriceResolver resolver, ListingRepository listings) {
        this.customers = customers;
        this.resolver = resolver;
        this.listings = listings;
    }

    public List<PriceSchedule> pricesOf(UUID userId, List<String> codes) {
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        CustomerSummary customer = customers.findByUserId(userId)
                .filter(found -> "ACTIVE".equals(found.status()))
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        if (codes == null || codes.isEmpty() || codes.size() > MAX_SKUS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Between 1 and " + MAX_SKUS + " skus");
        }
        var skus = new LinkedHashSet<String>();
        for (String code : codes) skus.add(code == null ? "" : code.strip().toUpperCase(Locale.ROOT));
        List<PriceSchedule> schedules = new ArrayList<>();
        for (String sku : skus) {
            if (listings.publishedProductForSku(sku).isEmpty()) continue;
            try {
                schedules.add(resolver.schedule(customer, sku));
            } catch (BusinessException noPrice) {
                if (noPrice.errorCode() != ErrorCode.PRICE_NOT_AVAILABLE) throw noPrice;
            }
        }
        return schedules;
    }
}
