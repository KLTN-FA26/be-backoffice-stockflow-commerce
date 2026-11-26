package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.internal.domain.StandardPrice;
import com.stockflow.catalog.internal.domain.StandardPriceRepository;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.product.api.ProductService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Back-office management of variants' standard prices (kltn-docs 13 BR-04 as changed on 2026-11-26): the Sales Manager
 * keeps one per variant of the catalogue - price breaks by cartons, a price per piece, for a usual print - and Sales reads
 * them. A quote line starts from the customer's own price for its SKU, else from the standard price of the variant the
 * SKU was drawn on, and the quote waits for the Sales Manager when it is priced more than the threshold below that
 * reference, or has none (14 BR-10, {@code QuotePriceCheck}).
 *
 * <p>A customer's own SKU has no standard price: it is priced by the customer's own price ({@link CustomerPriceService};
 * {@code tg_standard_price_variant} holds the same rule). Changing a standard price does not move a quote already sent or
 * approved: a quote copies its prices, and an approval keeps the reference it was decided on.</p>
 *
 * <p>Every change is audited with what changed, as a customer's own price (13 §4.2 step 3): the variant and its new
 * breaks ("from 5: 10500" - from 5 cartons, 10 500 VND a piece).</p>
 */
@Service
@Transactional
public class StandardPriceService {

    private static final String AUDITED_SKU = "sku: #sku?.strip()?.toUpperCase(T(java.util.Locale).ROOT)";
    private static final String AUDITED_TIERS = "tiers: (#tiers == null ? null "
            + ": #tiers.!['from ' + minQuantity() + ': ' + unitPrice()?.toPlainString()])";

    private final StandardPriceRepository prices;
    private final ProductService products;
    private final CustomerSkus customerSkus;

    StandardPriceService(StandardPriceRepository prices, ProductService products, CustomerSkus customerSkus) {
        this.prices = prices;
        this.products = products;
        this.customerSkus = customerSkus;
    }

    /** A break as entered: from {@code minQuantity} cartons on, {@code unitPrice} whole VND a piece. */
    public record TierInput(int minQuantity, BigDecimal unitPrice) {
    }

    /**
     * A variant's standard price as stored; {@code moq} is where its first break starts.
     *
     * @param productName the name of the variant's product; null when the product module does not know it
     * @param variantName the variant's own name
     */
    public record PriceView(String sku, String productName, String variantName, int moq, List<TierInput> tiers,
                            String note, long version) {
    }

    /** The standard prices of these variants - every one for an empty collection - by SKU. */
    @Transactional(readOnly = true)
    public List<PriceView> list(Collection<String> skus) {
        List<StandardPrice> found = prices.of(skus.stream().map(StandardPriceService::sku).toList());
        Map<String, String> variantNames = found.isEmpty() ? Map.of()
                : products.variantNamesOf(found.stream().map(StandardPrice::sku).toList());
        return found.stream().map(price -> view(price, variantNames)).toList();
    }

    @Transactional(readOnly = true)
    public PriceView get(String sku) {
        return prices.find(sku(sku)).map(this::view)
                .orElseThrow(() -> new BusinessException(ErrorCode.STANDARD_PRICE_NOT_FOUND));
    }

    /**
     * Gives a variant of the catalogue its standard price.
     *
     * @throws BusinessException {@code VARIANT_NOT_FOUND}, {@code STANDARD_PRICE_VARIANT_REQUIRED} for a customer's own
     *         SKU, {@code CONFLICT} when the variant already has one (replace it instead), {@code VALIDATION_FAILED}
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "standard-price", resourceId = "#sku",
            details = "{" + AUDITED_SKU + ", " + AUDITED_TIERS + "}")
    public PriceView create(String sku, List<TierInput> tiers, String note) {
        String code = catalogueVariant(sku);
        if (prices.findForUpdate(code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, code + " already has a standard price");
        }
        try {
            return view(prices.save(new StandardPrice(Identifiers.newId(), code, domain(tiers), note, 0L)));
        } catch (DuplicateKeyException raced) {
            throw new BusinessException(ErrorCode.CONFLICT, code + " already has a standard price");
        }
    }

    /** @throws BusinessException {@code STANDARD_PRICE_NOT_FOUND}, {@code OPTIMISTIC_LOCK}, {@code VALIDATION_FAILED} */
    @Auditable(action = AuditAction.UPDATE, resourceType = "standard-price", resourceId = "#sku",
            details = "{" + AUDITED_SKU + ", " + AUDITED_TIERS + ", version: #expectedVersion}")
    public PriceView replace(String sku, List<TierInput> tiers, String note, long expectedVersion) {
        String code = catalogueVariant(sku);
        StandardPrice price = prices.findForUpdate(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.STANDARD_PRICE_NOT_FOUND));
        price.replace(domain(tiers), note, expectedVersion);
        return view(prices.save(price));
    }

    /**
     * The variant no longer has a standard price: a quote line of its SKUs without a customer price starts unpriced, and
     * the quote waits for the Sales Manager when it is sent (14 BR-10); quotes already sent keep theirs.
     */
    @Auditable(action = AuditAction.DELETE, resourceType = "standard-price", resourceId = "#sku",
            details = "{" + AUDITED_SKU + ", version: #expectedVersion}")
    public void delete(String sku, long expectedVersion) {
        String code = sku(sku);
        StandardPrice price = prices.findForUpdate(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.STANDARD_PRICE_NOT_FOUND));
        if (price.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "The standard price of %s changed since it was read".formatted(code));
        }
        prices.delete(price.id());
    }

    /** A variant that exists and that no customer owns (13 BR-04, BR-05). */
    private String catalogueVariant(String sku) {
        String code = sku(sku);
        if (products.nameForSku(code).isEmpty()) {
            throw new BusinessException(ErrorCode.VARIANT_NOT_FOUND);
        }
        if (customerSkus.ownerOf(code).isPresent()) {
            throw new BusinessException(ErrorCode.STANDARD_PRICE_VARIANT_REQUIRED, ("%s is a customer's own SKU: it is "
                    + "priced by that customer's own price, not a standard price").formatted(code));
        }
        return code;
    }

    private static List<StandardPrice.Tier> domain(List<TierInput> tiers) {
        return tiers == null ? null
                : tiers.stream().map(t -> new StandardPrice.Tier(t.minQuantity(), t.unitPrice())).toList();
    }

    private PriceView view(StandardPrice price) {
        return view(price, products.variantNamesOf(List.of(price.sku())));
    }

    private PriceView view(StandardPrice price, Map<String, String> variantNames) {
        return new PriceView(price.sku(), products.nameForSku(price.sku()).orElse(null), variantNames.get(price.sku()),
                price.moq(), price.tiers().stream().map(t -> new TierInput(t.minQuantity(), t.unitPrice())).toList(),
                price.note(), price.version());
    }

    /** The shape of {@code product.variants.sku} (ck_variants_sku), upper-cased as every SKU is kept. */
    private static final java.util.regex.Pattern SKU = java.util.regex.Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,63}");

    private static String sku(String raw) {
        String code = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!SKU.matcher(code).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Malformed sku: " + raw);
        }
        return code;
    }
}
