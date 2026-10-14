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
import com.stockflow.product.internal.domain.ProductChangeType;
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
import com.stockflow.product.internal.repository.ProductChangeLogRepository;
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
 * is approved, and the default variant (created with the product) is never made obsolete. The database states the rest: unique SKU, unique signature per product, one default,
 * one option per axis, a SKU frozen once the variant leaves DRAFT.</p>
 *
 * <p>A variant is a combination of one option per axis of its product. The client names the option
 * values; the signature is derived from them ({@link VariantSignature}) and never accepted. The SKU is
 * the caller's, or {@link SkuCodeRule} builds it, and its alphabet is {@link Sku}'s.</p>
 *
 * <p>Whether a combination is taken is decided by the option ids the variants carry
 * ({@code variant_attribute_values}), never by comparing signatures. A signature is a derived string
 * whose spelling has changed: rows written before it was sorted by attribute code (the deployed seed's
 * {@code SIZE=...;COLOR=...}) name the same combination as {@code COLOR=...;SIZE=...}, so a string
 * comparison would offer to generate a combination that exists and let {@code add} create its twin.
 * The product lock serialises the check with every other write of the product.</p>
 *
 * <p>The default variant takes the first combination (spec D1): a product is born with one variant
 * whose signature is {@code DEFAULT}, and the matrix-first combination (the first option, in dictionary
 * order, of every axis) becomes that variant instead of a second row. Its SKU is the product's code
 * unless the caller sends one, which is applied as a rename. Otherwise every product with axes would
 * carry a variant that matches no combination, and its SKU would be sold beside the one that does. The
 * transform happens only while the default variant is DRAFT, for the reason a SKU is frozen after
 * that: a SKU on a stock card must not silently start to mean another combination. Any other
 * combination, and every combination once the default has left DRAFT, is a row of its own.</p>
 *
 * <p>A product created with axes (spec D2) skips that placeholder: it gets no {@code DEFAULT} variant,
 * and {@link #createMatrix} inserts the matrix-first combination as the default variant beside the
 * others, every SKU by the rule.</p>
 *
 * <p>On that transform path {@code add} answers 201 with the default variant itself: no row is created,
 * {@code position} is ignored (the default keeps its own), and the audit entry is a CREATE of the
 * default's id. The same holds for the matrix-first combination in a generation.</p>
 *
 * <p>A deactivated dictionary value ({@code AttributeValue.active() == false}; the attribute's own flag
 * does not count) is no longer used to make NEW variants. Generation skips such options, both
 * unrestricted and restricted; a restriction that names one, a manual {@code add} that names one, and
 * an axis left with no active option are all 400 {@code VARIANT_OPTIONS_INVALID} (the last naming the
 * axis). The matrix-first combination is computed over the active options only, so generation and
 * {@code add} agree on it. Variants that already carry an inactive value are untouched, and an
 * {@code update} may keep their current options: only newly chosen values must be active.</p>
 *
 * <p>An update is partial: a null {@code optionValueIds} keeps the options, a blank sku or name keeps
 * the current one. A generation creates at most {@link VariantMatrix#MAX_PER_CALL} combinations, and
 * the one that goes to the default variant counts toward that limit. The limit is checked from the axis
 * sizes before the matrix is built ({@link VariantMatrix#requireWithinCap}).</p>
 *
 * <p>Variants that have no options, left from before the product got axes, are repaired with a PUT
 * update carrying {@code optionValueIds} while they are DRAFT: the options differ from the (empty)
 * current ones, so the signature is re-derived from them and the stale {@code SKU=...} signature goes.
 * The SKU is kept. The default variant is the same case: while DRAFT, an update may assign it a
 * combination, which is the way out when the matrix-first combination already exists as a row of its
 * own and the transform therefore could not take it.</p>
 *
 * <p>Every write takes the product lock first ({@link #requireChangeable}), then reads the axes and
 * the dictionary, and only then the variants, so two writers of one product serialise before either
 * reads what the other is about to change.</p>
 *
 * <p>Every change is written to the product's history ({@code product_change_log}) in the same
 * transaction (SCRUM-461). Discontinuing the product makes all its variants {@code OBSOLETE},
 * the default one included ({@link #obsoleteAll}): kltn-docs 01 §5.2 lets an obsolete SKU be shipped and
 * adjusted to clear the stock, which is what a product that is going away needs, where {@code BLOCKED}
 * would stop orders already running.</p>
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
    private final ProductChangeLogRepository history;
    private final Clock clock;

    public ProductVariantService(ProductRepository products, VariantJpaRepository variants,
                                 VariantAttributeValueJpaRepository variantOptions,
                                 ProductAttributeSetRepository attributeSets, AttributeRepository attributes,
                                 ProductChangeLogRepository history, Clock clock) {
        this.products = products;
        this.variants = variants;
        this.variantOptions = variantOptions;
        this.attributeSets = attributeSets;
        this.attributes = attributes;
        this.history = history;
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
            history.record(productId, ProductChangeType.VARIANT_STATUS, variant.getId(), VariantStatus.DRAFT.name(),
                    VariantStatus.ACTIVE.name(), null, List.of());
        }
    }

    /**
     * Called by product discontinuation, inside its transaction: every variant that is not already
     * {@code OBSOLETE} becomes so, whatever it was, and the default variant is no exception — the
     * "default variant is never made obsolete" rule of {@link #obsolete} is about taking one SKU off a
     * product that goes on, and the whole product is going away here. Each move is written to the history
     * with the product's reason.
     */
    public void obsoleteAll(UUID productId, String reason) {
        Instant now = clock.instant();
        for (var variant : variants.findByProductIdOrderByPositionAscIdAsc(productId)) {
            VariantStatus from = variant.getStatus();
            if (from == VariantStatus.OBSOLETE) {
                continue;
            }
            variant.moveTo(VariantStatus.OBSOLETE, now);
            history.record(productId, ProductChangeType.VARIANT_STATUS, variant.getId(), from.name(),
                    VariantStatus.OBSOLETE.name(), reason, List.of());
        }
        variants.flush();
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
        List<AxisOption> combination = catalog.resolve(command.optionValueIds(), Set.of());
        VariantSignature signature = catalog.signature(combination);
        if (combinationsOf(productId, null).contains(optionIds(combination))) {
            throw combinationTaken(signature);
        }
        String name = command.name() == null || command.name().isBlank()
                ? catalog.defaultName(combination) : requireName(command.name());
        Sku requested = command.sku() == null || command.sku().isBlank() ? null : sku(command.sku());
        VariantJpaEntity defaultVariant = variants.findByProductIdAndDefaultVariantTrue(productId).orElse(null);
        if (isUnassigned(defaultVariant) && catalog.isMatrixFirst(combination)) {
            if (requested != null && !requested.code().equals(defaultVariant.getSku())) {
                requireSkuFree(requested);
            } else {
                requested = null;
            }
            return takeFirstCombination(defaultVariant, combination, signature, name, requested, catalog);
        }
        Sku sku = requested != null ? requested : catalog.skuFor(combination);
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
        return generate(requireChangeable(productId), optionValueIds);
    }

    /**
     * Called by product creation with axes (spec D2), inside its transaction and after the axes are
     * written, on a product that has no variant yet: every combination is created, and the matrix-first
     * one is inserted as the default variant, its SKU by the rule like the others. Not audited on its
     * own; the product's CREATE entry covers it.
     */
    public List<VariantView> createMatrix(UUID productId) {
        return generate(requireChangeable(productId), null).created();
    }

    private Generated generate(Product product, Map<UUID, List<UUID>> optionValueIds) {
        UUID productId = product.id().value();
        Catalog catalog = catalog(product);
        if (catalog.axes.isEmpty()) {
            throw new BusinessException(ErrorCode.VARIANT_OPTIONS_INVALID,
                    "This product has no axes to generate variants from");
        }
        List<List<AxisOption>> options = catalog.restrictedOptions(optionValueIds);
        Set<Set<UUID>> existing = combinationsOf(productId, null);
        // Refused from the sizes alone, before the matrix is built: see VariantMatrix.requireWithinCap.
        VariantMatrix.requireWithinCap(options.stream().map(List::size).toList(), existing.size());
        List<Combination> all = VariantMatrix.of(options);
        List<Combination> missing = VariantMatrix.missing(all, existing, c -> optionIds(c.options()));
        int skipped = all.size() - missing.size();

        VariantJpaEntity defaultVariant = variants.findByProductIdAndDefaultVariantTrue(productId).orElse(null);
        // The default takes the matrix-first combination only if this call creates it; a restricted
        // matrix that leaves it out leaves the default as it is.
        Combination forDefault = isUnassigned(defaultVariant)
                ? missing.stream().filter(c -> catalog.isMatrixFirst(c.options())).findFirst().orElse(null) : null;
        List<Combination> toInsert = missing.stream().filter(c -> c != forDefault).toList();

        // Every SKU is decided and checked, against the database and against each other, before the
        // first row is written. Value codes may contain '-', so two combinations can build one SKU.
        List<Sku> skus = toInsert.stream().map(c -> catalog.skuFor(c.options())).toList();
        Set<Sku> distinct = new HashSet<>();
        for (Sku sku : skus) {
            if (!distinct.add(sku)) {
                throw new BusinessException(ErrorCode.VARIANT_SKU_ALREADY_EXISTS,
                        "Two combinations would both get SKU " + sku + "; supply SKUs by hand");
            }
            requireSkuFree(sku);
        }
        if (forDefault != null) {
            List<AxisOption> first = forDefault.options();
            takeFirstCombination(defaultVariant, first, catalog.signature(first), catalog.defaultName(first), null,
                    catalog);
        }
        // A product created with axes has no default variant yet: the matrix-first combination becomes it.
        boolean needsDefault = defaultVariant == null;
        int position = variants.nextPosition(productId);
        List<VariantView> created = new ArrayList<>();
        for (int i = 0; i < toInsert.size(); i++) {
            List<AxisOption> combination = toInsert.get(i).options();
            boolean isDefault = needsDefault && catalog.isMatrixFirst(combination);
            var variant = new VariantJpaEntity(Identifiers.newId(), productId, skus.get(i).code(),
                    catalog.defaultName(combination), catalog.signature(combination).value(), position++, isDefault);
            created.add(view(insert(variant, combination), catalog.optionViews(combination)));
        }
        return new Generated(created, skipped);
    }

    /**
     * The SKU may change only while the variant is DRAFT ({@code tg_variants_sku_frozen}), and so may
     * its options: a SKU already on a stock card and an order must not silently start to mean another
     * combination. The signature follows the options; the SKU is not regenerated. Giving options to a
     * variant that has none, or a combination to the DRAFT default, is a change like any other.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-variant", resourceId = "#variantId")
    public VariantView update(UUID productId, UUID variantId, NewVariant command) {
        Product product = requireChangeable(productId);
        Catalog catalog = catalog(product);
        var variant = requireVariant(productId, variantId);

        String sku = variant.getSku();
        Sku requested = command.sku() == null || command.sku().isBlank() ? null : sku(command.sku());
        if (requested != null && !requested.code().equals(sku)) {
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
            Set<UUID> currentOptionIds = currentRows.stream()
                    .map(VariantAttributeValueJpaEntity::getProductAttributeOptionId).collect(Collectors.toSet());
            List<AxisOption> combination = catalog.resolve(command.optionValueIds(), currentOptionIds);
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
        boolean taken = newCombination != null
                ? combinationsOf(productId, variantId).contains(optionIds(newCombination))
                : !signature.equals(variant.getAttributeSignature())
                        && variants.existsByProductIdAndAttributeSignature(productId, signature);
        if (taken) {
            throw combinationTaken(new VariantSignature(signature));
        }
        String name = command.name() == null || command.name().isBlank() ? variant.getName() : requireName(command.name());
        int position = command.position() == null ? variant.getPosition() : command.position();
        List<String> changed = new ArrayList<>();
        if (!sku.equals(variant.getSku())) changed.add("sku");
        if (!name.equals(variant.getName())) changed.add("name");
        if (!signature.equals(variant.getAttributeSignature())) changed.add("attributeSignature");
        if (position != variant.getPosition()) changed.add("position");
        variant.setDetails(sku, name, signature, position);
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
            throw translate(e);
        }
        if (!changed.isEmpty()) {
            history.record(productId, ProductChangeType.VARIANT_UPDATED, variant.getId(), null, null, null, changed);
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
        return move(product, variantId, VariantStatus.ACTIVE);
    }

    @Auditable(action = AuditAction.TRANSITION, resourceType = "product-variant", resourceId = "#variantId")
    public VariantView block(UUID productId, UUID variantId) {
        return move(requireChangeable(productId), variantId, VariantStatus.BLOCKED);
    }

    /** Terminal. The default variant stays: it is the product's own SKU. */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "product-variant", resourceId = "#variantId")
    public VariantView obsolete(UUID productId, UUID variantId) {
        Product product = requireChangeable(productId);
        if (requireVariant(productId, variantId).isDefaultVariant()) {
            throw new BusinessException(ErrorCode.INVALID_VARIANT_TRANSITION,
                    "The default variant cannot be made obsolete; discontinue the product instead");
        }
        return move(product, variantId, VariantStatus.OBSOLETE);
    }

    /** {@code product} is the one {@link #requireChangeable} already loaded and locked; it is not read again. */
    private VariantView move(Product product, UUID variantId, VariantStatus target) {
        UUID productId = product.id().value();
        var variant = requireVariant(productId, variantId);
        if (!variant.getStatus().canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_VARIANT_TRANSITION,
                    "Variant %s cannot go from %s to %s".formatted(variant.getSku(), variant.getStatus(), target));
        }
        VariantStatus from = variant.getStatus();
        variant.moveTo(target, clock.instant());
        variants.saveAndFlush(variant);
        history.record(productId, ProductChangeType.VARIANT_STATUS, variant.getId(), from.name(), target.name(), null,
                List.of());
        return viewWithOptions(variant, catalog(product));
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

    /** Whether the default variant still waits for its combination: signature {@code DEFAULT} and still DRAFT. */
    private static boolean isUnassigned(VariantJpaEntity defaultVariant) {
        return defaultVariant != null && defaultVariant.getStatus() == VariantStatus.DRAFT
                && VariantSignature.defaultSignature().value().equals(defaultVariant.getAttributeSignature());
    }

    /**
     * The default variant becomes the first combination; it keeps its SKU unless {@code rename} is given.
     * No row is added, so the history says the default variant was updated, naming what moved.
     */
    private VariantView takeFirstCombination(VariantJpaEntity defaultVariant, List<AxisOption> combination,
                                             VariantSignature signature, String name, Sku rename, Catalog catalog) {
        String sku = rename == null ? defaultVariant.getSku() : rename.code();
        List<String> changed = new ArrayList<>();
        if (!sku.equals(defaultVariant.getSku())) changed.add("sku");
        if (!name.equals(defaultVariant.getName())) changed.add("name");
        changed.add("attributeSignature");
        defaultVariant.setDetails(sku, name, signature.value(), defaultVariant.getPosition());
        try {
            variants.saveAndFlush(defaultVariant);
            insertOptions(defaultVariant.getId(), combination);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        history.record(defaultVariant.getProductId(), ProductChangeType.VARIANT_UPDATED, defaultVariant.getId(),
                null, null, null, changed);
        return view(defaultVariant, catalog.optionViews(combination));
    }

    /** The option-id set of every variant of the product but {@code except}. */
    private Set<Set<UUID>> combinationsOf(UUID productId, UUID except) {
        return variantOptions.findByProductId(productId).stream()
                .filter(o -> !o.getVariantId().equals(except))
                .collect(Collectors.groupingBy(VariantAttributeValueJpaEntity::getVariantId,
                        Collectors.mapping(VariantAttributeValueJpaEntity::getProductAttributeOptionId,
                                Collectors.toSet())))
                .values().stream().collect(Collectors.toSet());
    }

    private static Set<UUID> optionIds(List<AxisOption> combination) {
        return combination.stream().map(AxisOption::id).collect(Collectors.toSet());
    }

    /**
     * Inserts the variant and its options, flushing so a clash on {@code uk_variants_sku} or
     * {@code uk_variants_combination} from a request that raced past the checks surfaces here, as the
     * error the checks would have given, not as a constraint error at commit. Every new row goes
     * through here, which is what puts each one in the history: an add and a generation alike.
     */
    private VariantJpaEntity insert(VariantJpaEntity variant, List<AxisOption> combination) {
        VariantJpaEntity saved;
        try {
            saved = variants.saveAndFlush(variant);
            insertOptions(saved.getId(), combination);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        history.record(saved.getProductId(), ProductChangeType.VARIANT_ADDED, saved.getId(), null,
                saved.getStatus().name(), null, List.of());
        return saved;
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

    /**
     * What a failed flush means. Only the two unique indexes are a request that raced past the checks;
     * the inventory rename guard (a DRAFT SKU that something already references) is a refused
     * transition; anything else is not ours to explain and goes to the global handler unchanged.
     */
    private static RuntimeException translate(DataIntegrityViolationException e) {
        if (violates(e, "uk_variants_sku") || violates(e, "uk_variants_combination")) {
            return new BusinessException(ErrorCode.VARIANT_SKU_ALREADY_EXISTS,
                    "A variant with this SKU or combination was created meanwhile");
        }
        String message = e.getMostSpecificCause().getMessage();
        if (message != null && message.contains("Only an unused DRAFT variant can rename")) {
            return new BusinessException(ErrorCode.INVALID_VARIANT_TRANSITION,
                    "This SKU is referenced and can no longer be renamed");
        }
        return e;
    }

    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException v
                    && constraint.equalsIgnoreCase(v.getConstraintName())) {
                return true;
            }
        }
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains("\"" + constraint + "\"");
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

            /** The options whose dictionary value is still active: the ones NEW variants may use. */
            List<AxisOption> activeOptions() {
                return options.stream().filter(o -> values.get(o.valueId()).active()).toList();
            }

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

        /**
         * One option per axis, in axis order, from the values the caller named. A value that is
         * deactivated in the dictionary is refused unless its option is in {@code keepable} (the options
         * the variant being updated already carries).
         */
        List<AxisOption> resolve(List<UUID> valueIds, Set<UUID> keepable) {
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
                if (!keepable.contains(option.id())
                        && !axes.get(owner).values().get(valueId).active()) {
                    throw new BusinessException(ErrorCode.VARIANT_OPTIONS_INVALID,
                            "Value %s of axis %s is deactivated; new variants cannot use it"
                                    .formatted(axes.get(owner).values().get(valueId).code(),
                                            axes.get(owner).attribute().code()));
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

        /**
         * The first ACTIVE option, in dictionary order, of every axis: the combination the default
         * variant becomes. Over active options only, so generation and {@code add} agree on it.
         */
        boolean isMatrixFirst(List<AxisOption> combination) {
            for (int i = 0; i < axes.size(); i++) {
                List<AxisOption> active = axes.get(i).activeOptions();
                if (active.isEmpty() || !active.getFirst().id().equals(combination.get(i).id())) {
                    return false;
                }
            }
            return true;
        }

        /**
         * The options per axis a generation uses: the active ones, or the named ones, which must all be
         * active. An axis left with none is 400 naming it, so generation never silently makes nothing.
         */
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
                List<AxisOption> active = axis.activeOptions();
                if (active.isEmpty()) {
                    throw new BusinessException(ErrorCode.VARIANT_OPTIONS_INVALID,
                            "Axis %s has no active option to generate from".formatted(axis.attribute().code()));
                }
                if (wanted == null) {
                    result.add(active);
                    continue;
                }
                Set<UUID> offered = active.stream().map(AxisOption::valueId).collect(Collectors.toSet());
                if (wanted.isEmpty() || !offered.containsAll(wanted)) {
                    throw new BusinessException(ErrorCode.VARIANT_OPTIONS_INVALID,
                            "Axis %s: every named value must be an active option of this product"
                                    .formatted(axis.attribute().code()));
                }
                Set<UUID> keep = new HashSet<>(wanted);
                result.add(active.stream().filter(o -> keep.contains(o.valueId())).toList());
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
