package com.stockflow.product.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.PrintConfigs;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductModel;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.PrintConfigRules;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.entity.CustomizationTemplateJpaEntity;
import com.stockflow.product.internal.entity.PrintAreaJpaEntity;
import com.stockflow.product.internal.entity.ProductJpaEntity;
import com.stockflow.product.internal.entity.VariantJpaEntity;
import com.stockflow.product.internal.repository.CustomizationTemplateJpaRepository;
import com.stockflow.product.internal.repository.PrintAreaJpaRepository;
import com.stockflow.product.internal.repository.PrintConfigPersistenceMapper;
import com.stockflow.product.internal.repository.ProductJpaRepository;
import com.stockflow.product.internal.repository.VariantJpaRepository;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.StoredFile;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Customization templates (kltn-docs 01 §4.10, BR-21): the Sales Manager's back-office writes, and {@link PrintConfigs}
 * for the modules that read them.
 *
 * <p>Not an aggregate in {@code internal.domain}: a template's rules are its limits and its model, both checked by the
 * pure {@link PrintConfigRules}, which also computes the print areas; everything else is a fact about its variant and
 * product. Saving replaces the areas whole - they are derived, never edited one by one - and a template is born
 * inactive, so a half-entered one is never offered to a customer.</p>
 *
 * <p>Every write takes the product lock first, then the template's: the same order as every other write of a product,
 * and what makes "one active template per variant" (activation retires the one active before it) safe under two
 * Sales Managers at once. {@code uk_customization_templates_active} states the rule again in the database.</p>
 *
 * <p>Drafts already started on a template follow its current configuration until they are approved (open question
 * B39); an approved design freezes the template it was drawn on in its snapshot. Changing a template is therefore
 * always allowed, and deactivating one only stops new designs.</p>
 */
@Service
public class PrintConfigService implements PrintConfigs {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,49}");

    private final CustomizationTemplateJpaRepository templates;
    private final PrintAreaJpaRepository areas;
    private final VariantJpaRepository variants;
    private final ProductJpaRepository products;
    private final FileTransfers files;
    private final TransactionOperations tx;
    private final EntityManager entityManager;

    public PrintConfigService(CustomizationTemplateJpaRepository templates, PrintAreaJpaRepository areas,
                              VariantJpaRepository variants, ProductJpaRepository products, FileTransfers files,
                              TransactionOperations tx, EntityManager entityManager) {
        this.templates = templates;
        this.areas = areas;
        this.variants = variants;
        this.products = products;
        this.files = files;
        this.tx = tx;
        this.entityManager = entityManager;
    }

    /** What a Sales Manager enters; the print areas are computed from {@code model} and {@code areaSpecs}. */
    public record Draft(String code, String name, PrintConfigRules.Limits limits, ProductModel model,
                        List<PrintConfigRules.AreaSpec> areaSpecs) {
    }

    // ---------------------------------------------------------------------------------------------
    // Back office
    // ---------------------------------------------------------------------------------------------

    /** The templates of one variant, by code. */
    @Transactional(readOnly = true)
    public List<PrintConfig> list(UUID productId, UUID variantId) {
        VariantJpaEntity variant = variantOf(productId, variantId);
        return assemble(templates.findByVariantIdOrderByCodeAsc(variantId), Map.of(variantId, variant));
    }

    @Transactional(readOnly = true)
    public PrintConfig get(UUID productId, UUID variantId, UUID templateId) {
        VariantJpaEntity variant = variantOf(productId, variantId);
        return assemble(List.of(templateOf(variantId, templateId)), Map.of(variantId, variant)).get(0);
    }

    /** A new, inactive template on a variant of a CUSTOMIZABLE product. */
    @Transactional
    @Auditable(action = AuditAction.CREATE, resourceType = "product-customization-template",
            resourceId = "#result?.templateId()")
    public PrintConfig create(UUID productId, UUID variantId, Draft draft) {
        ProductJpaEntity product = lockCustomizable(productId);
        VariantJpaEntity variant = variantOf(productId, variantId);
        requireWritable(product, variant);
        if (draft.code() == null || !CODE.matcher(draft.code()).matches()) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_INVALID,
                    "A template code is 1 to 50 capital letters, digits or hyphens");
        }
        if (templates.existsByVariantIdAndCode(variantId, draft.code())) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_CODE_TAKEN,
                    "Variant %s already has a template %s".formatted(variant.getSku(), draft.code()));
        }
        var computed = computeAreas(draft);
        var template = new CustomizationTemplateJpaEntity(Identifiers.newId(), variantId, draft.code());
        describe(template, draft);
        templates.save(template);
        replaceAreas(template.getId(), computed);
        return assemble(List.of(template), Map.of(variantId, variant)).get(0);
    }

    /** Everything but the code; the print areas are computed again. */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-customization-template", resourceId = "#templateId")
    public PrintConfig update(UUID productId, UUID variantId, UUID templateId, long version, Draft draft) {
        ProductJpaEntity product = lockCustomizable(productId);
        VariantJpaEntity variant = variantOf(productId, variantId);
        requireWritable(product, variant);
        CustomizationTemplateJpaEntity template = lockTemplate(variantId, templateId, version);
        var computed = computeAreas(draft);
        // The areas' names and margins live in their own rows: an edit of only those leaves the template row as it
        // was, and its version would not move - a second editor holding the same version would then overwrite it.
        entityManager.lock(template, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        describe(template, draft);
        templates.saveAndFlush(template);
        replaceAreas(templateId, computed);
        return assemble(List.of(template), Map.of(variantId, variant)).get(0);
    }

    /**
     * Customers may start designs on this template. The variant's template active until now, if any, is retired in
     * the same transaction: a customer starts a design on "the" template of a variant (kltn-docs 13 BR-14).
     */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-customization-template", resourceId = "#templateId")
    public PrintConfig activate(UUID productId, UUID variantId, UUID templateId, long version) {
        ProductJpaEntity product = lockCustomizable(productId);
        VariantJpaEntity variant = variantOf(productId, variantId);
        requireWritable(product, variant);
        CustomizationTemplateJpaEntity template = lockTemplate(variantId, templateId, version);
        if (template.getModelParams() == null) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_INVALID,
                    "Enter the product model before activating the template");
        }
        if (!template.isActive()) {
            for (CustomizationTemplateJpaEntity other : templates.findByVariantIdInAndActiveTrue(List.of(variantId))) {
                other.setActive(false);
                templates.saveAndFlush(other);
            }
            template.setActive(true);
            templates.saveAndFlush(template);
        }
        return assemble(List.of(template), Map.of(variantId, variant)).get(0);
    }

    /** No new design may start on this template; drafts already on it keep it. */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-customization-template", resourceId = "#templateId")
    public PrintConfig deactivate(UUID productId, UUID variantId, UUID templateId, long version) {
        // Not lockCustomizable: a product whose kind was changed afterwards must still be able to retire its template.
        products.lockById(productId).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        VariantJpaEntity variant = variantOf(productId, variantId);
        CustomizationTemplateJpaEntity template = lockTemplate(variantId, templateId, version);
        if (template.isActive()) {
            template.setActive(false);
            templates.saveAndFlush(template);
        }
        return assemble(List.of(template), Map.of(variantId, variant)).get(0);
    }

    /**
     * Attaches a reference die-line (PDF or PNG) for customers to compare against; replaces the one before, which the
     * orphaned-file sweep then removes. Not transactional itself: the file is checked, scanned and stored first, with
     * no lock held, and attached in a short transaction ({@link PreparedFiles}).
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-customization-template", resourceId = "#templateId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PrintConfig attachReference(UUID productId, UUID variantId, UUID templateId, long version,
                                       FileUpload upload) {
        try (PreparedFiles prepared = files.prepare(FileCategory.PRINT_TEMPLATE, upload)) {
            return tx.execute(status -> {
                ProductJpaEntity product = lockCustomizable(productId);
                VariantJpaEntity variant = variantOf(productId, variantId);
                requireWritable(product, variant);
                CustomizationTemplateJpaEntity template = lockTemplate(variantId, templateId, version);
                StoredFile file = prepared.single().file();
                template.attachReference(file.key(), file.originalName(), file.contentType());
                templates.saveAndFlush(template);
                prepared.adopt();
                return assemble(List.of(template), Map.of(variantId, variant)).get(0);
            });
        }
    }

    /** A short-lived link to the reference die-line, for the back office. */
    @Transactional(readOnly = true)
    public DownloadLink referenceLink(UUID productId, UUID variantId, UUID templateId) {
        variantOf(productId, variantId);
        CustomizationTemplateJpaEntity template = templateOf(variantId, templateId);
        if (template.getReferenceFileKey() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "The template has no reference file");
        }
        return files.downloadLink(template.getReferenceFileKey(), template.getReferenceFileName());
    }

    // ---------------------------------------------------------------------------------------------
    // PrintConfigs
    // ---------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<PrintConfig> activeOfProduct(UUID productId) {
        Optional<ProductJpaEntity> product = products.findById(productId);
        if (product.isEmpty() || product.get().getKind() != ProductKind.CUSTOMIZABLE) {
            return List.of();
        }
        List<VariantJpaEntity> active = variants.findByProductIdOrderByPositionAscIdAsc(productId).stream()
                .filter(v -> v.getStatus() == VariantStatus.ACTIVE && v.getOwnerCustomerId() == null)
                .toList();
        if (active.isEmpty()) {
            return List.of();
        }
        Map<UUID, VariantJpaEntity> byId = active.stream()
                .collect(Collectors.toMap(VariantJpaEntity::getId, Function.identity()));
        Map<UUID, Integer> position = new java.util.HashMap<>();
        for (int i = 0; i < active.size(); i++) {
            position.put(active.get(i).getId(), i);
        }
        List<CustomizationTemplateJpaEntity> found = templates.findByVariantIdInAndActiveTrue(byId.keySet()).stream()
                .sorted(Comparator.comparing(t -> position.get(t.getVariantId())))
                .toList();
        return assemble(found, byId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PrintConfig> ofVariant(String sku, String code) {
        return variants.findBySku(sku).flatMap(variant -> templates.findByVariantIdAndCode(variant.getId(), code)
                .map(template -> assemble(List.of(template), Map.of(variant.getId(), variant)).get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PrintConfig> byId(UUID templateId) {
        return templates.findById(templateId).flatMap(template -> variants.findById(template.getVariantId())
                .map(variant -> assemble(List.of(template), Map.of(variant.getId(), variant)).get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean designable(UUID templateId) {
        return templates.findById(templateId)
                .flatMap(template -> variants.findById(template.getVariantId()))
                .filter(variant -> variant.getStatus() == VariantStatus.ACTIVE && variant.getOwnerCustomerId() == null)
                .flatMap(variant -> products.findById(variant.getProductId()))
                .filter(product -> product.getKind() == ProductKind.CUSTOMIZABLE
                        && (product.getStatus() == ProductStatus.APPROVED || product.getStatus() == ProductStatus.PUBLISHED))
                .isPresent();
    }

    // ---------------------------------------------------------------------------------------------

    private void describe(CustomizationTemplateJpaEntity template, Draft draft) {
        if (draft.name() == null || draft.name().isBlank() || draft.name().length() > 255) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_INVALID, "A template needs a name of at most 255 characters");
        }
        if (draft.model() == null) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_INVALID, "A template needs the product model");
        }
        PrintConfigRules.Limits limits = PrintConfigRules.requireValid(draft.limits());
        template.describe(draft.name().trim(), limits.minDpi(), limits.colorMode(),
                limits.allowedFileFormats().toArray(String[]::new), limits.maxFileSizeMb(), limits.maxColors(),
                limits.allowedFontCodes() == null ? null : limits.allowedFontCodes().toArray(String[]::new),
                PrintConfigPersistenceMapper.modelParams(draft.model()));
    }

    /** The print areas of {@code draft}'s model, checked before anything is written. */
    private static List<PrintArea> computeAreas(Draft draft) {
        if (draft.model() == null) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_INVALID, "A template needs the product model");
        }
        return PrintConfigRules.areas(draft.model(), draft.areaSpecs());
    }

    private void replaceAreas(UUID templateId, List<PrintArea> computed) {
        areas.deleteByTemplateId(templateId);
        areas.saveAll(PrintConfigPersistenceMapper.toRows(templateId, computed));
        areas.flush();
    }

    private List<PrintConfig> assemble(List<CustomizationTemplateJpaEntity> found, Map<UUID, VariantJpaEntity> variantsById) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<PrintAreaJpaEntity>> areasByTemplate = areas
                .findByTemplateIdInOrderByTemplateIdAscSortOrderAsc(found.stream().map(t -> t.getId()).toList())
                .stream()
                .collect(Collectors.groupingBy(PrintAreaJpaEntity::getTemplateId));
        return found.stream()
                .map(t -> {
                    VariantJpaEntity variant = variantsById.get(t.getVariantId());
                    return PrintConfigPersistenceMapper.toConfig(t, variant.getProductId(), variant.getSku(),
                            areasByTemplate.getOrDefault(t.getId(), List.of()));
                })
                .toList();
    }

    private ProductJpaEntity lockCustomizable(UUID productId) {
        ProductJpaEntity product = products.lockById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (product.getKind() != ProductKind.CUSTOMIZABLE) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_CUSTOMIZABLE,
                    "Product %s is not customizable".formatted(product.getCode()));
        }
        return product;
    }

    private VariantJpaEntity variantOf(UUID productId, UUID variantId) {
        return variants.findByIdAndProductId(variantId, productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VARIANT_NOT_FOUND));
    }

    private CustomizationTemplateJpaEntity templateOf(UUID variantId, UUID templateId) {
        return templates.findById(templateId)
                .filter(t -> t.getVariantId().equals(variantId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRINT_CONFIG_NOT_FOUND));
    }

    private CustomizationTemplateJpaEntity lockTemplate(UUID variantId, UUID templateId, long version) {
        CustomizationTemplateJpaEntity template = templates.lockById(templateId)
                .filter(t -> t.getVariantId().equals(variantId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRINT_CONFIG_NOT_FOUND));
        if (template.getVersion() != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Template %s changed meanwhile (version %d, edit based on %d)"
                            .formatted(template.getCode(), template.getVersion(), version));
        }
        return template;
    }

    private static void requireWritable(ProductJpaEntity product, VariantJpaEntity variant) {
        if (product.getStatus() == ProductStatus.DISCONTINUED || variant.getStatus() == VariantStatus.OBSOLETE) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Variant %s is no longer sold: its templates are kept as they are".formatted(variant.getSku()));
        }
    }
}
