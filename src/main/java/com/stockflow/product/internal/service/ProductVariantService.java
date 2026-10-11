package com.stockflow.product.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductRepository;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.entity.VariantJpaEntity;
import com.stockflow.product.internal.repository.VariantJpaRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The variants of a product (WBS 3.1.2): its sellable SKUs.
 *
 * <p>Not an aggregate of its own in {@code internal.domain}: a variant's rules are its status table
 * ({@link VariantStatus}) and three facts about its product — it can be changed only while the
 * product is not under review or discontinued, activated only once the product is approved, and the
 * default variant (the one created with the product, its SKU the product code) is never made
 * obsolete. The database states the rest: unique SKU, unique attribute combination per product, one
 * default, a SKU frozen once the variant leaves DRAFT.</p>
 */
@Service
@Transactional
public class ProductVariantService {

    /** {@code ck_variants_sku}. */
    static final Pattern SKU = Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,63}");
    private static final String DEFAULT_SIGNATURE = "DEFAULT";

    private final ProductRepository products;
    private final VariantJpaRepository variants;
    private final Clock clock;

    public ProductVariantService(ProductRepository products, VariantJpaRepository variants, Clock clock) {
        this.products = products;
        this.variants = variants;
        this.clock = clock;
    }

    public record VariantView(UUID id, UUID productId, String sku, String name, VariantStatus status,
                              boolean defaultVariant, String attributeSignature, int position, Instant obsoletedAt,
                              long version) {
    }

    /** A variant to add. {@code attributeSignature} null means "the SKU is the combination". */
    public record NewVariant(String sku, String name, String attributeSignature, Integer position) {
    }

    /**
     * Called by product creation, inside its transaction. Public, like the other helpers other
     * services here call: a non-public method of a CGLIB proxy may run on the proxy instance itself,
     * whose fields are null.
     */
    public VariantView createDefault(UUID productId, String sku, String name) {
        var variant = new VariantJpaEntity(Identifiers.newId(), productId, sku, name, DEFAULT_SIGNATURE, 0, true);
        return view(variants.saveAndFlush(variant));
    }

    /** Called by product approval, inside its transaction: what was drafted with the product goes on sale with it. */
    public void activateDrafts(UUID productId) {
        Instant now = clock.instant();
        for (var variant : variants.findByProductIdAndStatus(productId, VariantStatus.DRAFT)) {
            variant.moveTo(VariantStatus.ACTIVE, now);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<VariantView> list(UUID productId, int page, int size) {
        requireProduct(productId);
        var pageable = Pages.of(page, size, Sort.by("position", "id"));
        return Pages.toResponse(variants.findAll((root, query, cb) -> cb.equal(root.get("productId"), productId), pageable)
                .map(ProductVariantService::view));
    }

    @Transactional(readOnly = true)
    public VariantView get(UUID productId, UUID variantId) {
        return view(requireVariant(productId, variantId));
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "product-variant", resourceId = "#result?.id()")
    public VariantView add(UUID productId, NewVariant command) {
        requireChangeable(productId);
        String sku = normalise(command.sku());
        if (variants.existsBySku(sku)) {
            throw new BusinessException(ErrorCode.VARIANT_SKU_ALREADY_EXISTS, "SKU " + sku + " is taken");
        }
        String signature = signatureOf(command.attributeSignature(), sku);
        if (variants.existsByProductIdAndAttributeSignature(productId, signature)) {
            throw new BusinessException(ErrorCode.VARIANT_SKU_ALREADY_EXISTS,
                    "This product already has a variant for " + signature);
        }
        int position = command.position() == null ? variants.nextPosition(productId) : command.position();
        var variant = new VariantJpaEntity(Identifiers.newId(), productId, sku, requireName(command.name()), signature,
                position, false);
        return view(variants.saveAndFlush(variant));
    }

    /** The SKU may change only while the variant is DRAFT ({@code tg_variants_sku_frozen}). */
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-variant", resourceId = "#variantId")
    public VariantView update(UUID productId, UUID variantId, NewVariant command) {
        requireChangeable(productId);
        var variant = requireVariant(productId, variantId);
        String sku = normalise(command.sku());
        if (!sku.equals(variant.getSku())) {
            if (variant.getStatus() != VariantStatus.DRAFT) {
                throw new BusinessException(ErrorCode.INVALID_VARIANT_TRANSITION,
                        "The SKU of a variant is fixed once it has left DRAFT");
            }
            if (variants.existsBySku(sku)) {
                throw new BusinessException(ErrorCode.VARIANT_SKU_ALREADY_EXISTS, "SKU " + sku + " is taken");
            }
        }
        String signature = variant.isDefaultVariant() && command.attributeSignature() == null
                ? variant.getAttributeSignature() : signatureOf(command.attributeSignature(), sku);
        if (!signature.equals(variant.getAttributeSignature())
                && variants.existsByProductIdAndAttributeSignature(productId, signature)) {
            throw new BusinessException(ErrorCode.VARIANT_SKU_ALREADY_EXISTS,
                    "This product already has a variant for " + signature);
        }
        variant.setDetails(sku, requireName(command.name()), signature,
                command.position() == null ? variant.getPosition() : command.position());
        return view(variants.saveAndFlush(variant));
    }

    /** On sale. Only once the product is approved: approval is what puts its variants on sale. */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "product-variant", resourceId = "#variantId")
    public VariantView activate(UUID productId, UUID variantId) {
        Product product = requireChangeable(productId);
        if (product.status() != ProductStatus.APPROVED && product.status() != ProductStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.INVALID_VARIANT_TRANSITION,
                    "A variant goes on sale once its product is approved");
        }
        return move(productId, variantId, VariantStatus.ACTIVE);
    }

    @Auditable(action = AuditAction.TRANSITION, resourceType = "product-variant", resourceId = "#variantId")
    public VariantView block(UUID productId, UUID variantId) {
        requireChangeable(productId);
        return move(productId, variantId, VariantStatus.BLOCKED);
    }

    /** Terminal. The default variant stays: it is the product's own SKU. */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "product-variant", resourceId = "#variantId")
    public VariantView obsolete(UUID productId, UUID variantId) {
        requireChangeable(productId);
        if (requireVariant(productId, variantId).isDefaultVariant()) {
            throw new BusinessException(ErrorCode.INVALID_VARIANT_TRANSITION,
                    "The default variant cannot be made obsolete; discontinue the product instead");
        }
        return move(productId, variantId, VariantStatus.OBSOLETE);
    }

    private VariantView move(UUID productId, UUID variantId, VariantStatus target) {
        var variant = requireVariant(productId, variantId);
        if (!variant.getStatus().canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_VARIANT_TRANSITION,
                    "Variant %s cannot go from %s to %s".formatted(variant.getSku(), variant.getStatus(), target));
        }
        variant.moveTo(target, clock.instant());
        return view(variants.saveAndFlush(variant));
    }

    public VariantJpaEntity requireVariant(UUID productId, UUID variantId) {
        return variants.findByIdAndProductId(variantId, productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VARIANT_NOT_FOUND,
                        "Product %s has no variant %s".formatted(productId, variantId)));
    }

    /** The product, locked: two concurrent variant edits of one product serialise on it. */
    public Product requireChangeable(UUID productId) {
        Product product = products.findForUpdate(new ProductId(productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "No product with id " + productId));
        if (!product.acceptsChanges()) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION,
                    "The variants and media of a product cannot change while it is " + product.status());
        }
        return product;
    }

    private Product requireProduct(UUID productId) {
        return products.findById(new ProductId(productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "No product with id " + productId));
    }

    private static String normalise(String sku) {
        String value = sku == null ? "" : sku.strip().toUpperCase(Locale.ROOT);
        if (!SKU.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "sku must be 1-64 letters, digits, '.', '_' or '-', starting with a letter or digit");
        }
        return value;
    }

    private static String signatureOf(String requested, String sku) {
        String value = requested == null || requested.isBlank() ? "SKU=" + sku : requested.strip();
        if (value.length() > 512) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "attributeSignature must be at most 512 characters");
        }
        return value;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 255) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "name must be 1-255 characters");
        }
        return name.strip();
    }

    static VariantView view(VariantJpaEntity v) {
        return new VariantView(v.getId(), v.getProductId(), v.getSku(), v.getName(), v.getStatus(),
                v.isDefaultVariant(), v.getAttributeSignature(), v.getPosition(), v.getObsoletedAt(), v.getVersion());
    }
}
