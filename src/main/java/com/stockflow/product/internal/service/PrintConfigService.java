package com.stockflow.product.internal.service;

import com.stockflow.common.api.FieldError;
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
import com.stockflow.product.api.TemplateModel3d;
import com.stockflow.product.internal.domain.ModelFileRules;
import com.stockflow.product.internal.domain.PrintConfigRules;
import com.stockflow.product.internal.domain.PrintFacts;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p>A template carries two versions. {@code version} is the optimistic lock and moves with every write. The print
 * version ({@link PrintConfig#printVersion}) moves only when an edit changes what customers' files are drawn on
 * ({@link PrintFacts}): renaming, activating, deactivating - including the variant's template retired by another's
 * activation - and attaching a reference die-line leave it, so designs waiting for review stay approvable.</p>
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

    /** The longest mesh name a 3D surface may give. */
    private static final int MESH_NAME_MAX = 100;

    /**
     * What a Sales Manager enters; the print areas are computed from {@code model} and {@code areaSpecs}.
     * {@code surfaces} maps print areas to meshes of the template's {@code .glb}; null keeps the template's own (empty
     * on create), so a client that knows nothing of 3D models cannot drop the map by saving the template.
     */
    public record Draft(String code, String name, PrintConfigRules.Limits limits, ProductModel model,
                        List<PrintConfigRules.AreaSpec> areaSpecs, List<TemplateModel3d.Surface> surfaces) {

        public Draft(String code, String name, PrintConfigRules.Limits limits, ProductModel model,
                     List<PrintConfigRules.AreaSpec> areaSpecs) {
            this(code, name, limits, model, areaSpecs, null);
        }
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

    /**
     * A new, inactive template on a variant of a CUSTOMIZABLE product, created with its {@code .glb}: the back office
     * generates the measurements from that file, so a template never exists without the file they came from
     * ({@code PRINT_CONFIG_MODEL_FILE_REQUIRED} when {@code modelFile} is null). Not transactional itself, as
     * {@link #attachReference} is not: the file is checked, scanned and stored first with no lock held, and the
     * template is written with it in one short transaction ({@link PreparedFiles}); a failure leaves nothing but the
     * stored object, which is deleted.
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "product-customization-template",
            resourceId = "#result?.templateId()")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PrintConfig create(UUID productId, UUID variantId, Draft draft, FileUpload modelFile) {
        if (modelFile == null) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_MODEL_FILE_REQUIRED,
                    "A template is created together with its 3D model file (.glb)");
        }
        // Cheap and pure: a draft that cannot be saved is refused before its file is scanned and stored.
        computeAreas(draft);
        try (PreparedFiles prepared = files.prepare(FileCategory.PRINT_MODEL, modelFile)) {
            return tx.execute(status -> createLocked(productId, variantId, draft, prepared));
        }
    }

    private PrintConfig createLocked(UUID productId, UUID variantId, Draft draft, PreparedFiles prepared) {
        ProductJpaEntity product = lockCustomizable(productId);
        VariantJpaEntity variant = variantOf(productId, variantId);
        requireWritable(product, variant);
        if (draft.code() == null || !CODE.matcher(draft.code()).matches()) {
            throw PrintConfigRules.invalid("code", "Pattern",
                    "A template code is 1 to 50 capital letters, digits or hyphens");
        }
        if (templates.existsByVariantIdAndCode(variantId, draft.code())) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_CODE_TAKEN,
                    "Variant %s already has a template %s".formatted(variant.getSku(), draft.code()));
        }
        var computed = computeAreas(draft);
        List<TemplateModel3d.Surface> surfaces =
                requireSurfaces(draft.surfaces() == null ? List.of() : draft.surfaces(), computed);
        requireComplete(surfaces, codes(computed));
        var template = new CustomizationTemplateJpaEntity(Identifiers.newId(), variantId, draft.code());
        describe(template, draft);
        template.mapModelSurfaces(PrintConfigPersistenceMapper.surfaces(surfaces));
        PreparedFiles.PreparedFile file = prepared.single();
        template.attachModelFile(file.file().key(), file.file().originalName(), file.sha256());
        templates.saveAndFlush(template);
        prepared.adopt();
        replaceAreas(template.getId(), computed);
        return assemble(List.of(template), Map.of(variantId, variant)).get(0);
    }

    /**
     * Everything but the code; the print areas are computed again. With {@code modelFile} the template's {@code .glb}
     * is replaced in the same transaction as the measurements it determines; without one, those measurements, the
     * model's kind and the mesh map must stay as they are ({@link ModelFileRules}), since the back office generates
     * them from the file and a file alone, or measurements alone, would leave the two disagreeing. The file is
     * prepared before the transaction, as on {@link #create}.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-customization-template", resourceId = "#templateId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PrintConfig update(UUID productId, UUID variantId, UUID templateId, long version, Draft draft,
                              FileUpload modelFile) {
        if (modelFile == null) {
            return tx.execute(status -> updateLocked(productId, variantId, templateId, version, draft, null));
        }
        computeAreas(draft);
        try (PreparedFiles prepared = files.prepare(FileCategory.PRINT_MODEL, modelFile)) {
            return tx.execute(status -> updateLocked(productId, variantId, templateId, version, draft, prepared));
        }
    }

    /** {@link #update} inside its transaction; {@code prepared} is the new {@code .glb}, or null for none. */
    private PrintConfig updateLocked(UUID productId, UUID variantId, UUID templateId, long version, Draft draft,
                                     PreparedFiles prepared) {
        ProductJpaEntity product = lockCustomizable(productId);
        VariantJpaEntity variant = variantOf(productId, variantId);
        requireWritable(product, variant);
        CustomizationTemplateJpaEntity template = lockTemplate(variantId, templateId, version);
        PrintFacts before = PrintFacts.of(assemble(List.of(template), Map.of(variantId, variant)).get(0));
        var computed = computeAreas(draft);
        TemplateModel3d current = PrintConfigPersistenceMapper.model3d(template);
        List<TemplateModel3d.Surface> requested = draft.surfaces() == null ? null
                : requireSurfaces(draft.surfaces(), computed);
        if (prepared == null) {
            ModelFileRules.requireUnchangedWithoutFile(PrintConfigPersistenceMapper.model(template.getModelParams()),
                    current.surfaces(), draft.model(), requested);
        }
        List<TemplateModel3d.Surface> surfaces = requested != null ? requested
                : keptSurfaces(current.surfaces(), computed);
        if (prepared != null || current.hasFile()) {
            requireComplete(surfaces, codes(computed));
        }
        // The areas' names and margins live in their own rows: an edit of only those leaves the template row as it
        // was, and its version would not move - a second editor holding the same version would then overwrite it.
        entityManager.lock(template, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        describe(template, draft);
        template.mapModelSurfaces(PrintConfigPersistenceMapper.surfaces(surfaces));
        if (prepared != null) {
            PreparedFiles.PreparedFile file = prepared.single();
            template.attachModelFile(file.file().key(), file.file().originalName(), file.sha256());
        }
        // The edit as it will be stored, areas included, against the template as it was: only a change customers'
        // files are drawn on outdates them.
        PrintFacts after = PrintFacts.of(PrintConfigPersistenceMapper.toConfig(template, productId, variant.getSku(),
                PrintConfigPersistenceMapper.toRows(templateId, computed)));
        if (!after.equals(before)) {
            template.movePrintVersion(PrintFacts.next(template.getPrintVersion()));
        }
        templates.saveAndFlush(template);
        if (prepared != null) {
            prepared.adopt();
        }
        replaceAreas(templateId, computed);
        return assemble(List.of(template), Map.of(variantId, variant)).get(0);
    }

    /**
     * Customers may start designs on this template. The variant's template active until now, if any, is retired in
     * the same transaction: a customer starts a design on "the" template of a variant (kltn-docs 13 BR-14). Only a
     * template with its {@code .glb} and every print area mapped to its {@code print:<code>} mesh is offered: the
     * storefront draws the 3D view from them, and a template written before files were required may lack both.
     */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "product-customization-template", resourceId = "#templateId")
    public PrintConfig activate(UUID productId, UUID variantId, UUID templateId, long version) {
        ProductJpaEntity product = lockCustomizable(productId);
        VariantJpaEntity variant = variantOf(productId, variantId);
        requireWritable(product, variant);
        CustomizationTemplateJpaEntity template = lockTemplate(variantId, templateId, version);
        // A template written before files were required may lack its model, its file or both: either way the fix is
        // the same - upload the .glb with its measurements - so the answer is the same 409 (FE request 2026-11-17 B-2).
        if (template.getModelParams() == null || template.getModelFileKey() == null) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_MODEL_FILE_REQUIRED,
                    "Upload the template's 3D model file (.glb) with its measurements before activating it");
        }
        requireComplete(PrintConfigPersistenceMapper.model3d(template).surfaces(),
                areas.findByTemplateIdInOrderByTemplateIdAscSortOrderAsc(List.of(templateId)).stream()
                        .map(PrintAreaJpaEntity::getCode)
                        .toList());
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

    /** A short-lived link to the template's 3D model, for the back office. */
    @Transactional(readOnly = true)
    public DownloadLink modelLink(UUID productId, UUID variantId, UUID templateId) {
        variantOf(productId, variantId);
        CustomizationTemplateJpaEntity template = templateOf(variantId, templateId);
        if (template.getModelFileKey() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "The template has no 3D model file");
        }
        return files.downloadLink(template.getModelFileKey(), template.getModelFileName());
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
            throw PrintConfigRules.invalid("name", "Size", "A template needs a name of at most 255 characters");
        }
        if (draft.model() == null) {
            throw PrintConfigRules.invalid("model", "NotNull", "A template needs the product model");
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
            throw PrintConfigRules.invalid("model", "NotNull", "A template needs the product model");
        }
        return PrintConfigRules.areas(draft.model(), draft.areaSpecs());
    }

    /**
     * {@code surfaces} checked against the template's print areas: each names one of them, at most once, and gives a
     * mesh name of 1 to 100 characters. 422 naming every entry at fault.
     */
    private static List<TemplateModel3d.Surface> requireSurfaces(List<TemplateModel3d.Surface> surfaces,
                                                                 List<PrintArea> computed) {
        Set<String> codes = computed.stream().map(PrintArea::code).collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        List<FieldError> errors = new ArrayList<>();
        List<TemplateModel3d.Surface> checked = new ArrayList<>();
        for (int i = 0; i < surfaces.size(); i++) {
            TemplateModel3d.Surface surface = surfaces.get(i);
            String at = "model3d.surfaces[%d]".formatted(i);
            if (surface == null) {
                errors.add(new FieldError(at, "A surface is required", "NotNull"));
                continue;
            }
            String code = surface.printAreaCode();
            if (code == null || !codes.contains(code)) {
                errors.add(new FieldError(at + ".printAreaCode",
                        "Not a print area of this template's model: " + codes.stream().sorted().toList(),
                        "PrintAreaUnknown"));
            } else if (!seen.add(code)) {
                errors.add(new FieldError(at + ".printAreaCode", "Print area " + code + " is mapped twice",
                        "PrintAreaDuplicate"));
            }
            String mesh = surface.meshName() == null ? null : surface.meshName().trim();
            if (mesh == null || mesh.isEmpty() || mesh.length() > MESH_NAME_MAX) {
                errors.add(new FieldError(at + ".meshName", "A mesh name is 1 to 100 characters", "Size"));
            }
            checked.add(new TemplateModel3d.Surface(code, mesh));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_SURFACES_INVALID,
                    "The 3D model's surfaces do not match the template's print areas", errors);
        }
        return checked;
    }

    /**
     * {@code surfaces} as a template with a {@code .glb} needs them: every print area mapped, each to its
     * {@code print:<code>} mesh, the only name the storefront looks for. 422 naming every entry at fault and every area
     * left out.
     */
    private static void requireComplete(List<TemplateModel3d.Surface> surfaces, List<String> areaCodes) {
        List<FieldError> errors = ModelFileRules.incompleteSurfaces(surfaces, areaCodes);
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_SURFACES_INVALID,
                    "Every print area of a template with a 3D model is drawn on its own print:<code> mesh", errors);
        }
    }

    private static List<String> codes(List<PrintArea> computed) {
        return computed.stream().map(PrintArea::code).toList();
    }

    /**
     * The surfaces kept by an update that sent none: those whose print area the new model still has. A model of
     * another kind (a cup made a bag) has none of the old areas, and a mesh map naming them would mean nothing.
     */
    private static List<TemplateModel3d.Surface> keptSurfaces(List<TemplateModel3d.Surface> surfaces,
                                                              List<PrintArea> computed) {
        Set<String> codes = computed.stream().map(PrintArea::code).collect(Collectors.toSet());
        return surfaces.stream().filter(s -> codes.contains(s.printAreaCode())).toList();
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
