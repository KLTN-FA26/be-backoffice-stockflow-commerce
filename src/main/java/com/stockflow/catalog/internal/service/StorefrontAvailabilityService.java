package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.domain.Availability;
import com.stockflow.catalog.internal.domain.SkuAvailability;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The stock indicator of a customer's own SKUs (SCRUM-158; kltn-docs 13 §4.4 step 4, BR-07, BR-12).
 *
 * <p>Only the caller's own ACTIVE SKUs are answered (13 BR-01, BR-02, BR-11): another customer's SKU is not there at
 * all, and a variant (quy cách) is only a mould to design on - it has no stock indicator. The SKU need not be on a
 * published product: a customer's SKU stays visible while it is ACTIVE even when its product is unlisted (13 §4.5
 * step 4).</p>
 *
 * <p>Counted in whole cartons of the SKU's standard size (13 BR-12, open-questions A9): available to promise is
 * inventory's system-wide figure, every warehouse together (13 BR-07, C3), read through {@code inventory :: api} in one
 * query for the whole page, then divided by the SKU's carton. The carton is the one {@code QuoteService} quotes in: the
 * SKU's single active packing unit of more than one piece, the default shipping one should there be several. A SKU
 * without one gets no indicator - a guessed carton would promise stock no quote can sell - and the gap is logged.</p>
 *
 * <p>Not cached. Docs 13 allows ten seconds of lag; the endpoint's own short {@code Cache-Control} absorbs a page being
 * refreshed.</p>
 */
@Service
@Transactional(readOnly = true)
public class StorefrontAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(StorefrontAvailabilityService.class);

    private final InventoryService inventory;
    private final UomConversionService units;
    private final AvailabilityProperties properties;
    private final CustomerService customers;
    private final CustomerSkus customerSkus;

    public StorefrontAvailabilityService(InventoryService inventory, UomConversionService units,
                                        AvailabilityProperties properties, CustomerService customers,
                                        CustomerSkus customerSkus) {
        this.inventory = inventory;
        this.units = units;
        this.properties = properties;
        this.customers = customers;
        this.customerSkus = customerSkus;
    }

    /**
     * @param userId the authenticated identity subject, not a client-selected customer id
     * @param codes the SKUs asked about, in display order; duplicates collapse to one answer
     * @return one entry per distinct SKU of the caller's own with a carton, in the order asked; any other SKU - another
     *         customer's, a variant, an unknown or retired one - is left out, as if it did not exist
     * @throws BusinessException {@code VALIDATION_FAILED} for no SKU, too many, or a malformed one
     */
    public List<SkuAvailability> availabilityOf(UUID userId, List<String> codes) {
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        // Resolve the profile from the authenticated subject; never accept a client customer id.
        CustomerSummary customer = customers.findByUserId(userId).filter(found -> "ACTIVE".equals(found.status()))
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        Set<Sku> asked = parse(codes);
        Set<String> own = customerSkus.ownedBy(customer.customerId(), null).stream()
                .filter(sku -> "ACTIVE".equals(sku.status()))
                .map(CustomerSku::sku)
                .collect(Collectors.toSet());
        Set<Sku> owned = asked.stream().filter(sku -> own.contains(sku.code()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (owned.isEmpty()) return List.of();
        Map<String, Integer> cartons = cartonSizes(owned);
        Set<Sku> counted = owned.stream().filter(sku -> cartons.containsKey(sku.code()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (counted.isEmpty()) return List.of();
        Map<Sku, Integer> atp = inventory.availableToPromise(counted);
        return counted.stream()
                .map(sku -> new SkuAvailability(sku, Availability.ofCartons(atp.getOrDefault(sku, 0),
                        cartons.get(sku.code()), properties.lowStockThreshold())))
                .toList();
    }

    /** The pieces in a carton of each SKU that has exactly one carton unit (see the class comment), by SKU code. */
    private Map<String, Integer> cartonSizes(Set<Sku> skus) {
        Map<String, List<UomConversion>> declared = units.conversionsOf(skus);
        Map<String, Integer> sizes = new HashMap<>();
        for (Sku sku : skus) {
            List<UomConversion> candidates = declared.getOrDefault(sku.code(), List.of()).stream()
                    .filter(unit -> unit.active() && !unit.base() && unit.factor() > 1).toList();
            if (candidates.size() > 1) {
                candidates = candidates.stream().filter(UomConversion::defaultShipping).toList();
            }
            if (candidates.size() == 1) {
                sizes.put(sku.code(), candidates.getFirst().factor());
            } else {
                log.warn("Customer SKU {} has no single carton unit (kltn-docs 01 BR-03): no stock indicator for it",
                        sku.code());
            }
        }
        return sizes;
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
