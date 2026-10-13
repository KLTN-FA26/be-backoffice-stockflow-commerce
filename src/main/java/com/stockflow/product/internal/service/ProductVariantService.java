package com.stockflow.product.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.Attribute;
import com.stockflow.product.internal.domain.AttributeRepository;
import com.stockflow.product.internal.domain.AttributeValue;
import com.stockflow.product.internal.domain.AxisOption;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductAttributeSet;
import com.stockflow.product.internal.domain.ProductAttributeSetRepository;
import com.stockflow.product.internal.domain.ProductAxis;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductRepository;
import com.stockflow.product.internal.domain.SkuCodeRule;
import com.stockflow.product.internal.domain.VariantMatrix;
import com.stockflow.product.internal.domain.VariantMatrix.Combination;
import com.stockflow.product.internal.domain.VariantSignature;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.entity.VariantAttributeValueJpaEntity;
import com.stockflow.product.internal.entity.VariantJpaEntity;
import com.stockflow.product.internal.repository.VariantAttributeValueJpaRepository;
import com.stockflow.product.internal.repository.VariantJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The variants of a product (WBS 3.1.2): its sellable SKUs.
 *
 * <p>Not an aggregate of its own in {@code internal.domain}: a variant's rules are its status table
 * ({@link VariantStatus}), the shape of its options, and three facts about its product. It can be
 * changed only while the product is not under review or discontinued, activated only once the product
 * is approved, and the default variant (created with the product, its SKU the product code) is never
 * made obsolete. The database states the rest: unique SKU, unique signature per product, one default,
 * one option per axis, a SKU frozen once the variant leaves DRAFT.</p>
 *
 * <p>A variant is a combination of one option per axis of its product. The client names the option
 * values; the signature is derived from them ({@link VariantSignature}) and never accepted, so two
 * requests for one combination meet on the unique index instead of creating twins. The SKU is the
 * caller's, or {@link SkuCodeRule} builds it, and its alphabet is {@link Sku}'s.</p>
 *
 * <p>The default variant takes the first combination (spec D1): a product is born with one variant
 * whose signature is {@code DEFAULT}, and the first combination any create path produces becomes
 * that variant instead of a second row, keeping its SKU, which is the product's code. Otherwise every
 * product with axes would carry a variant that matches no combination, and its SKU would be sold
 * beside the one that does. A SKU the caller sends with that first combination is therefore not used.</p>
 *
 * <p>Every write takes the product lock first ({@link #requireChangeable}), then reads the axes and
 * the dictionary, and only then the variants, so two writers of one product serialise before either
 * reads what the other is about to change.</p>
 */
@Service
@Transactional
public class ProductVariantService {

    private static final int MAX_NAME_LENGTH = 255;

    private final ProductRepository products;
    private final VariantJpaRepository variants;
    private final VariantAttributeValueJpaRepository variantOptions;
    private final ProductAttributeSetRepository attributeSets;
    private final AttributeRepository attributes;
    private final Clock clock;

    public ProductVariantService(ProductRepository products, VariantJpaRepository variants,
                                 VariantAttributeValueJpaRepository variantOptions,
                                 ProductAttributeSetRepository attributeSets, AttributeRepository attributes,
                                 Clock clock) {
        this.products = products;
        this.variants = variants;
        this.variantOptions = variantOptions;
        this.attributeSets = attributeSets;
        this.attributes = attributes;
        this.clock = clock;
    }

    public record VariantView(UUID id, UUID productId, String sku, String name, VariantStatus status,
                              boolean defaultVariant, String attributeSignature, int position, Instant obsoletedAt,
                              long version, List<VariantOption> options) {
    }

    /** The option a variant takes on one axis, with the dictionary's code and label. */
    public record VariantOption(UUID attributeId, String attributeCode, UUID valueId, String valueCode,
                                String label) {
    }

    /**
     * A variant to add or the new state of one. {@code optionValueIds} name one value per axis; null
     * on an update means "leave the options alone". {@code sku} and {@code name} are optional on a
     * product with axes (a rule and a generated name fill them in).
     */
    public record NewVariant(String sku, String name, List<UUID> optionValueIds, Integer position) {
    }

    public record Generated(List<VariantView> created, int skipped) {
    }

    /**
     * Called by product creation, inside its transaction. Public, like the other helpers other
     * services here call: a non-public method of a CGLIB proxy may run on the proxy instance itself,
     * whose fields are null.
     */
    public VariantView createDefault(UUID productId, String sku, String name) {
        var variant = new VariantJpaEntity(Identifiers.newId(), productId, sku, name,
                VariantSignature.defaultSignature().value(), 0, true);
        return view(variants.saveAndFlush(variant), List.of());
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
        Catalog catalog = catalog(requireProduct(productId));
        var pageable = Pages.of(page, size, Sort.by("position", "id"));
        var found = variants.findAll((root, query, cb) -> cb.equal(root.get("productId"), productId), pageable);
        // One query for the options of the whole page; resolving them against the axes is in memory.
        Map<UUID, List<VariantAttributeValueJpaEntity>> optionRows = found.isEmpty() ? Map.of()
                : variantOptions.findByVariantIdIn(found.map(VariantJpaEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(VariantAttributeValueJpaEntity::getVariantId));
        return Pages.toResponse(found.map(v -> view(v, catalog.optionsOf(optionRows.getOrDefault(v.getId(), List.of())))));
    }

    @Transactional(readOnly = true)
    public VariantView get(UUID productId, UUID variantId) {
        Catalog catalog = catalog(requireProduct(productId));
        return viewWithOptions(requireVariant(productId, variantId), catalog);
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "product-variant", resourceId = "#result?.id()")
    public VariantView add(UUID productId, NewVariant command) {
        Product product = requireChangeable(productId);
        Catalog catalog = catalog(product);
        if (catalog.axes.isEmpty()) {
            return addWithoutAxes(productId, command);
        }
        List<AxisOption> combination = catalog.resolve(command.optionValueIds());
        VariantSignature signature = catalog.signature(combination);
        if (variants.existsByProductIdAndAttributeSignature(productId, signature.value())) {
            throw combinationTaken(signature);
        }
        String name = command.name() == null || command.name().isBlank()
                ? catalog.defaultName(combination) : requireName(command.name());
        VariantJpaEntity defaultVariant = variants.findByProductIdAndDefaultVariantTrue(productId).orElse(null);
        if (isUnassigned(defaultVariant)) {
            return takeFirstCombination(defaultVariant, combination, signature, name, catalog);
        }
        Sku sku = command.sku() == null || command.sku().isBlank() ? catalog.skuFor(combination) : sku(command.sku());
        requireSkuFree(sku);
        int position = command.position() == null ? variants.nextPosition(productId) : command.position();
        var variant = new VariantJpaEntity(Identifiers.newId(), productId, sku.code(), name, signature.value(),
                position, false);
        return view(insert(variant, combination), catalog.optionViews(combination));
    }

    private VariantView addWithoutAxes(UUID productId, NewVariant command) {
        if (command.optionValueIds() != null && !command.optionValueIds().isEmpty()) {
            throw new BusinessException(ErrorCode.VARIANT_OPTIONS_INVALID,
                    "This product has no axes, so a variant carries no options");
        }
        if (command.sku() == null || command.sku().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "sku is required on a product without axes");
        }
        Sku sku = sku(command.sku());
        requireSkuFree(sku);
        VariantSignature signature = VariantSignature.ofSku(sku);
        if (variants.existsByProductIdAndAttributeSignature(productId, signature.value())) {
            throw combinationTaken(signature);
        }
        int position = command.position() == null ? variants.nextPosition(productId) : command.position();
        var variant = new VariantJpaEntity(Identifiers.newId(), productId, sku.code(), requireName(command.name()),
                signature.value(), position, false);
        return view(insert(variant, List.of()), List.of());
    }

    /**
     * Creates every combination of the options (all of them, or those named in {@code optionValueIds},
     * attribute id to value ids) that the product does not have yet. Running it again changes nothing.
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "product-variant", resourceId = "#productId")
    public Generated generate(UUID productId, Map<UUID, List<UUID>> optionValueIds) {
        Product product = requireChangeable(productId);
        Catalog catalog = catalog(product);
        if (catalog.axes.isEmpty()) {
            throw new BusinessException(ErrorCode.VARIANT_OPTIONS_INVALID,
                    "This product has no axes to generate variants from");
        }
        List<Combination> all = VariantMatrix.of(catalog.restrictedOptions(optionValueIds));
        Set<VariantSignature> existing = variants.signaturesOf(productId).stream()
                .map(VariantSignature::new).collect(Collectors.toSet());
        List<Combination> missing = VariantMatrix.missing(all, existing, c -> catalog.signature(c.options()));
        int skipped = all.size() - missing.size();

        VariantJpaEntity defaultVariant = variants.findByProductIdAndDefaultVariantTrue(productId).orElse(null);
        boolean defaultIsFree = isUnassigned(defaultVariant) && !missing.isEmpty();
        List<Combination> toInsert = defaultIsFree ? missing.subList(1, missing.size()) : missing;

        // Every SKU is decided and checked before the first row is written.
        List<Sku> skus = toInsert.stream().map(c -> catalog.skuFor(c.options())).toList();
        for (Sku sku : skus) {
            requireSkuFree(sku);
        }
        if (defaultIsFree) {
            List<AxisOption> first = missing.getFirst().options();
            takeFirstCombination(defaultVariant, first, catalog.signature(first), catalog.defaultName(first), catalog);
        }
        int position = variants.nextPosition(productId);
        List<VariantView> created = new ArrayList<>();
        for (int i = 0; i < toInsert.size(); i++) {
            List<AxisOption> combination = toInsert.get(i).options();
            var variant = new VariantJpaEntity(Identifiers.newId(), productId, skus.get(i).code(),
                    catalog.defaultName(combination), catalog.signature(combination).value(), position++, false);
            created.add(view(insert(variant, combination), catalog.optionViews(combination)));
        }
        return new Generated(created, skipped);
    }

    /**
     * The SKU may change only while the variant is DRAFT ({@code tg_variants_sku_frozen}), and so may
     * its options: a SKU already on a stock card and an order must not silently start to mean another
     * combination. The signature follows the options; the SKU is not regenerated.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-variant", resourceId = "#variantId")
    public VariantView update(UUID productId, UUID variantId, NewVariant command) {
        Product product = requireChangeable(productId);
        Catalog catalog = catalog(product);
        var variant = requireVariant(productId, variantId);

        String sku = variant.getSku();
        if (command.sku() != null && !command.sku().isBlank() && !sku(command.sku()).code().equals(sku)) {
            Sku requested = sku(command.sku());
            if (variant.getStatus() != VariantStatus.DRAFT) {
                throw new BusinessException(ErrorCode.INVALID_VARIANT_TRANSITION,
                        "The SKU of a variant is fixed once it has left DRAFT");
            }
            requireSkuFree(requested);
            sku = requested.code();
        }

        List<VariantAttributeValueJpaEntity> currentRows = variantOptions.findByVariantIdIn(List.of(variantId));
        String signature = variant.getAttributeSignature();
        List<AxisOption> newCombination = null;
        if (catalog.axes.isEmpty()) {
            if (command.optionValueIds() != null && !command.optionValueIds().isEmpty()) {
                throw new BusinessException(ErrorCode.VARIANT_OPTIONS_INVALID,
                        "This product has no axes, so a variant carries no options");
            }
            if (!variant.isDefaultVariant()) {
                signature = VariantSignature.ofSku(new Sku(sku)).value();
            }
        } else if (command.optionValueIds() != null) {
            List<AxisOption> combination = catalog.resolve(command.optionValueIds());
            Set<UUID> currentOptionIds = currentRows.stream()
                    .map(VariantAttributeValueJpaEntity::getProductAttributeOptionId).collect(Collectors.toSet());
            Set<UUID> requestedOptionIds = combination.stream().map(AxisOption::id).collect(Collectors.toSet());
            if (!currentOptionIds.equals(requestedOptionIds)) {
                if (variant.getStatus() != VariantStatus.DRAFT) {
                    throw new BusinessException(ErrorCode.INVALID_VARIANT_TRANSITION,
                            "The options of a variant are fixed once it has left DRAFT");
                }
                newCombination = combination;
                signature = catalog.signature(combination).value();
            }
        }
        if (!signature.equals(variant.getAttributeSignature())
                && variants.existsByProductIdAndAttributeSignature(productId, signature)) {
            throw combinationTaken(new VariantSignature(signature));
        }
        String name = command.name() == null || command.name().isBlank() ? variant.getName() : requireName(command.name());
        variant.setDetails(sku, name, signature, command.position() == null ? variant.getPosition() : command.position());
        try {
            if (newCombination != null) {
                variantOptions.deleteAll(currentRows);
                variantOptions.flush();
            }
            variants.saveAndFlush(variant);
            if (newCombination != null) {
                insertOptions(variant.getId(), newCombination);
            }
        } catch (DataIntegrityViolationException e) {
            throw raced();
        }
        return viewWithOptions(variant, catalog);
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
        variants.saveAndFlush(variant);
        return viewWithOptions(variant, catalog(requireProduct(productId)));
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

    /** Whether the default variant still waits for its combination (its signature is still {@code DEFAULT}). */
    private static boolean isUnassigned(VariantJpaEntity defaultVariant) {
        return defaultVariant != null
                && VariantSignature.defaultSignature().value().equals(defaultVariant.getAttributeSignature());
    }

    /** The default variant becomes the first combination; its SKU, the product's code, stays. */
    private VariantView takeFirstCombination(VariantJpaEntity defaultVariant, List<AxisOption> combination,
                                             VariantSignature signature, String name, Catalog catalog) {
        defaultVariant.setDetails(defaultVariant.getSku(), name, signature.value(), defaultVariant.getPosition());
        try {
            variants.saveAndFlush(defaultVariant);
            insertOptions(defaultVariant.getId(), combination);
        } catch (DataIntegrityViolationException e) {
            throw raced();
        }
        return view(defaultVariant, catalog.optionViews(combination));
    }

    /**
     * Inserts the variant and its options, flushing so a clash on {@code uk_variants_sku} or
     * {@code uk_variants_combination} from a request that raced past the checks surfaces here, as the
     * error the checks would have given, not as a constraint error at commit.
     */
    private VariantJpaEntity insert(VariantJpaEntity variant, List<AxisOption> combination) {
        try {
            VariantJpaEntity saved = variants.saveAndFlush(variant);
            insertOptions(saved.getId(), combination);
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw raced();
        }
    }

    private void insertOptions(UUID variantId, List<AxisOption> combination) {
        if (combination.isEmpty()) {
            return;
        }
        variantOptions.saveAll(combination.stream()
                .map(o -> new VariantAttributeValueJpaEntity(Identifiers.newId(), variantId, o.id())).toList());
        variantOptions.flush();
    }

    private void requireSkuFree(Sku sku) {
        if (variants.existsBySku(sku.code())) {
            throw new BusinessException(ErrorCode.VARIANT_SKU_ALREADY_EXISTS, "SKU " + sku + " is taken");
        }
    }

    private static BusinessException combinationTaken(VariantSignature signature) {
        return new BusinessException(ErrorCode.VARIANT_SKU_ALREADY_EXISTS,
                "This product already has a variant for " + signature.value());
    }

    private static BusinessException raced() {
        return new BusinessException(ErrorCode.VARIANT_SKU_ALREADY_EXISTS,
                "A variant with this SKU or combination was created meanwhile");
    }

    private static Sku sku(String raw) {
        try {
            return new Sku(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "sku must be 1-64 letters, digits, '.', '_' or '-', starting with a letter or digit");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "name must be 1-255 characters");
        }
        return name.strip();
    }

    private VariantView viewWithOptions(VariantJpaEntity v, Catalog catalog) {
        return view(v, catalog.optionsOf(variantOptions.findByVariantIdIn(List.of(v.getId()))));
    }

    static VariantView view(VariantJpaEntity v, List<VariantOption> options) {
        return new VariantView(v.getId(), v.getProductId(), v.getSku(), v.getName(), v.getStatus(),
                v.isDefaultVariant(), v.getAttributeSignature(), v.getPosition(), v.getObsoletedAt(), v.getVersion(),
                options);
    }

    private Catalog catalog(Product product) {
        ProductAttributeSet set = attributeSets.load(product.id().value(), product.status());
        Set<UUID> ids = set.axes().stream().map(ProductAxis::attributeId).collect(Collectors.toSet());
        Map<UUID, Attribute> dictionary = ids.isEmpty() ? Map.of()
                : attributes.findAllById(ids).stream().collect(Collectors.toMap(Attribute::id, a -> a));
        return new Catalog(product, set, dictionary);
    }

    /**
     * A product's axes read against the dictionary: the one place that knows the order (axes by sort
     * order then attribute code, options by the dictionary's order) and how a combination turns into a
     * signature, a SKU, a name and the options shown.
     */
    private static final class Catalog {

        private record Axis(ProductAxis axis, Attribute attribute, Map<UUID, AttributeValue> values,
                             List<AxisOption> options) {

            VariantOption optionView(AxisOption option) {
                AttributeValue value = values.get(option.valueId());
                return new VariantOption(attribute.id(), attribute.code(), value.id(), value.code(),
                        value.details().label());
            }
        }

        private final Product product;
        private final List<Axis> axes;
        private final Map<UUID, Integer> axisIndexByOptionId = new HashMap<>();
        private final Map<UUID, AxisOption> optionById = new HashMap<>();

        Catalog(Product product, ProductAttributeSet set, Map<UUID, Attribute> dictionary) {
            this.product = product;
            List<Axis> built = new ArrayList<>();
            for (ProductAxis axis : set.axes()) {
                Attribute attribute = dictionary.get(axis.attributeId());
                Map<UUID, AttributeValue> values = attribute.values().stream()
                        .collect(Collectors.toMap(AttributeValue::id, v -> v));
                List<AxisOption> options = axis.options().stream()
                        .sorted(Comparator.comparing(o -> values.get(o.valueId()), AttributeValue.DICTIONARY_ORDER))
                        .toList();
                built.add(new Axis(axis, attribute, values, options));
            }
            built.sort(Comparator.<Axis>comparingInt(a -> a.axis().sortOrder()).thenComparing(a -> a.attribute().code()));
            this.axes = List.copyOf(built);
            for (int i = 0; i < axes.size(); i++) {
                for (AxisOption option : axes.get(i).axis().options()) {
                    axisIndexByOptionId.put(option.id(), i);
                    optionById.put(option.id(), option);
                }
            }
        }

        /** One option per axis, in axis order, from the values the caller named. */
        List<AxisOption> resolve(List<UUID> valueIds) {
            List<UUID> requested = valueIds == null ? List.of() : valueIds;
            AxisOption[] chosen = new AxisOption[axes.size()];
            for (UUID valueId : requested) {
                int owner = -1;
                AxisOption option = null;
                for (int i = 0; i < axes.size() && owner < 0; i++) {
                    for (AxisOption candidate : axes.get(i).axis().options()) {
                        if (candidate.valueId().equals(valueId)) {
                            owner = i;
                            option = candidate;
                        }
                    }
                }
                if (owner < 0) {
                    throw new BusinessException(ErrorCode.VARIANT_OPTIONS_INVALID,
                            "Value %s is not an option of this product".formatted(valueId));
                }
                if (chosen[owner] != null) {
                    throw new BusinessException(ErrorCode.VARIANT_OPTIONS_INVALID,
                            "Axis %s has more than one value".formatted(axes.get(owner).attribute().code()));
                }
                chosen[owner] = option;
            }
            for (int i = 0; i < chosen.length; i++) {
                if (chosen[i] == null) {
                    throw new BusinessException(ErrorCode.VARIANT_OPTIONS_INVALID,
                            "Axis %s needs one value".formatted(axes.get(i).attribute().code()));
                }
            }
            return List.of(chosen);
        }

        /** The options per axis a generation uses: all of them, or those named for the axis. */
        List<List<AxisOption>> restrictedOptions(Map<UUID, List<UUID>> optionValueIds) {
            Map<UUID, List<UUID>> restriction = optionValueIds == null ? Map.of() : optionValueIds;
            Set<UUID> axisAttributes = axes.stream().map(a -> a.attribute().id()).collect(Collectors.toSet());
            for (UUID attributeId : restriction.keySet()) {
                if (!axisAttributes.contains(attributeId)) {
                    throw new BusinessException(ErrorCode.VARIANT_OPTIONS_INVALID,
                            "Attribute %s is not an axis of this product".formatted(attributeId));
                }
            }
            List<List<AxisOption>> result = new ArrayList<>();
            for (Axis axis : axes) {
                List<UUID> wanted = restriction.get(axis.attribute().id());
                if (wanted == null) {
                    result.add(axis.options());
                    continue;
                }
                Set<UUID> offered = axis.options().stream().map(AxisOption::valueId).collect(Collectors.toSet());
                if (wanted.isEmpty() || !offered.containsAll(wanted)) {
                    throw new BusinessException(ErrorCode.VARIANT_OPTIONS_INVALID,
                            "Axis %s: every named value must be an option of this product"
                                    .formatted(axis.attribute().code()));
                }
                Set<UUID> keep = new HashSet<>(wanted);
                result.add(axis.options().stream().filter(o -> keep.contains(o.valueId())).toList());
            }
            return result;
        }

        VariantSignature signature(List<AxisOption> combination) {
            Map<String, String> codes = new LinkedHashMap<>();
            for (int i = 0; i < axes.size(); i++) {
                codes.put(axes.get(i).attribute().code(), valueCode(i, combination.get(i)));
            }
            return VariantSignature.of(codes);
        }

        Sku skuFor(List<AxisOption> combination) {
            List<String> codes = new ArrayList<>();
            for (int i = 0; i < axes.size(); i++) {
                codes.add(valueCode(i, combination.get(i)));
            }
            return SkuCodeRule.generate(product.code(), codes);
        }

        String defaultName(List<AxisOption> combination) {
            String labels = optionViews(combination).stream().map(VariantOption::label)
                    .collect(Collectors.joining(" / "));
            String name = product.name() + " – " + labels;
            if (name.length() > MAX_NAME_LENGTH) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "The generated name is longer than %d characters; supply a name by hand"
                                .formatted(MAX_NAME_LENGTH));
            }
            return name;
        }

        List<VariantOption> optionViews(List<AxisOption> combination) {
            List<VariantOption> views = new ArrayList<>();
            for (int i = 0; i < axes.size(); i++) {
                views.add(axes.get(i).optionView(combination.get(i)));
            }
            return views;
        }

        /** The options a variant's stored rows stand for, in axis order. */
        List<VariantOption> optionsOf(Collection<VariantAttributeValueJpaEntity> rows) {
            return rows.stream().map(VariantAttributeValueJpaEntity::getProductAttributeOptionId)
                    .sorted(Comparator.comparingInt(axisIndexByOptionId::get))
                    .map(id -> axes.get(axisIndexByOptionId.get(id)).optionView(optionById.get(id)))
                    .toList();
        }

        private String valueCode(int axisIndex, AxisOption option) {
            return axes.get(axisIndex).values().get(option.valueId()).code();
        }
    }
}
