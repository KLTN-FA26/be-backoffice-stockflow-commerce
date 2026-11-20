package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.internal.controller.dto.CustomizationTemplateRequest;
import com.stockflow.product.internal.controller.dto.CustomizationTemplateResponse;
import com.stockflow.product.internal.controller.dto.TemplateVersionRequest;
import com.stockflow.product.internal.service.PrintConfigService;
import com.stockflow.product.spi.PendingDesignReviews;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Customization templates of a variant (kltn-docs 01 §4.10, BR-21): what a customer may print on it and where. The
 * Sales Manager's ({@code product-customization-templates}, held by SALES_MANAGER and SYSTEM_ADMIN).
 *
 * <p>The request carries the product's model and, per print area, only its name and margins: the areas' position and
 * shape are computed on the server, so the storefront and the preflight draw the same geometry. A template is created
 * inactive; activating it retires the variant's template active before. Handlers are public so the permission proxy
 * is certain to advise them.</p>
 *
 * <p>A template and its {@code .glb} are saved together, multipart, on the same paths as the JSON forms (selected by
 * {@code Content-Type}): the back office generates the measurements from the file, so there is deliberately no endpoint
 * that replaces the file alone.</p>
 *
 * <p>No {@code DELETE} action: a template is never deleted - designs and snapshots name it (B39) - only deactivated, an
 * {@code UPDATE}. The baseline still seeds {@code product-customization-templates:DELETE} and grants it to two roles;
 * an undeclared grant is shown by no matrix and guards nothing, so the row is left as it is rather than migrated away.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/variants/{variantId}/customization-templates")
@Tag(name = "Customization templates", description = "What a customer may print on a variant, and where")
@PermissionResource(
        code = ProductResources.CUSTOMIZATION_TEMPLATES,
        group = "Product",
        label = "Customization templates",
        route = "/admin/products",
        apiPath = "/api/v1/products/{productId}/variants/{variantId}/customization-templates",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
class CustomizationTemplateController {

    /** What every save says about the model file; shared by the create and update descriptions. */
    private static final String MODEL_FILE_RULES = "The measurements are generated from the .glb, so they change "
            + "only with a new file: without one, changing a cup's diameters, height or band offsets, a bag's width, "
            + "gusset or height less top fold, the model kind, or model3d.surfaces is 409 "
            + "PRINT_CONFIG_MODEL_FILE_REQUIRED (also a create without a file). With a file, stored or sent, every "
            + "print area has exactly one surface, on the mesh print:<printAreaCode>, else 422 "
            + "PRINT_CONFIG_SURFACES_INVALID. "
            + "Not a glTF 2.0 binary is 415 UNSUPPORTED_MEDIA_TYPE, larger than 10 MB 413 PAYLOAD_TOO_LARGE, a file "
            + "the scan refuses 400 VALIDATION_FAILED. Only the file's SHA-256 is kept about its contents "
            + "(model3d.fileChecksum).";

    private final PrintConfigService templates;
    private final CustomizationTemplateWebMapper mapper;
    /** Absent where the design module is not (product's own module test): nothing waits for review there. */
    private final ObjectProvider<PendingDesignReviews> pendingReviews;

    CustomizationTemplateController(PrintConfigService templates, CustomizationTemplateWebMapper mapper,
                                    ObjectProvider<PendingDesignReviews> pendingReviews) {
        this.templates = templates;
        this.mapper = mapper;
        this.pendingReviews = pendingReviews;
    }

    /** The templates as answered, with the designs waiting for review on each counted in one query. */
    private List<CustomizationTemplateResponse> respond(List<PrintConfig> configs) {
        PendingDesignReviews counter = pendingReviews.getIfAvailable();
        Map<UUID, Long> pending = counter == null || configs.isEmpty() ? Map.of()
                : counter.countByTemplate(configs.stream().map(PrintConfig::templateId).toList());
        return configs.stream().map(config -> mapper.toResponse(config,
                pending.getOrDefault(config.templateId(), 0L))).toList();
    }

    private CustomizationTemplateResponse respond(PrintConfig config) {
        return respond(List.of(config)).get(0);
    }

    @GetMapping
    @Operation(summary = "The variant's templates, by code")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.READ)
    public ApiResponse<List<CustomizationTemplateResponse>> list(@PathVariable UUID productId,
                                                                 @PathVariable UUID variantId) {
        return ApiResponse.ok(respond(templates.list(productId, variantId)));
    }

    @GetMapping("/{templateId}")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.READ)
    public ApiResponse<CustomizationTemplateResponse> get(@PathVariable UUID productId, @PathVariable UUID variantId,
                                                          @PathVariable UUID templateId) {
        return ApiResponse.ok(respond(templates.get(productId, variantId, templateId)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an inactive template with its 3D model file; print areas computed from the model",
            description = "multipart/form-data: part template is the JSON CustomizationTemplateRequest (sent with "
                    + "Content-Type application/json), part file the glTF 2.0 binary (.glb, at most 10 MB) the "
                    + "measurements were generated from. Only on a variant of a CUSTOMIZABLE product (else 409 "
                    + "PRODUCT_NOT_CUSTOMIZABLE). A cup has one area, BODY; a bag four, FRONT, BACK, LEFT, RIGHT. A "
                    + "model with impossible sizes, a missing or extra area, or margins leaving nothing to print on is "
                    + "400 PRINT_CONFIG_INVALID; a code the variant already has is 409 PRINT_CONFIG_CODE_TAKEN. "
                    + MODEL_FILE_RULES)
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.CREATE)
    public ApiResponse<CustomizationTemplateResponse> create(
            @PathVariable UUID productId, @PathVariable UUID variantId,
            @Valid @RequestPart("template") CustomizationTemplateRequest request,
            @RequestPart(value = "file", required = false) MultipartFile file) {
        return withModelFile(file, upload -> templates.create(productId, variantId, mapper.toDraft(request), upload));
    }

    /**
     * The JSON form of {@link #create}, kept so a client that sends no file is told why (409
     * {@code PRINT_CONFIG_MODEL_FILE_REQUIRED}) rather than answered 415.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Always refused: a template is created with its 3D model file (multipart)",
            description = "409 PRINT_CONFIG_MODEL_FILE_REQUIRED once the body is valid; send multipart/form-data with "
                    + "parts template and file instead.")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.CREATE)
    public ApiResponse<CustomizationTemplateResponse> createWithoutFile(
            @PathVariable UUID productId, @PathVariable UUID variantId,
            @Valid @RequestBody CustomizationTemplateRequest request) {
        return ApiResponse.ok(respond(templates.create(productId, variantId, mapper.toDraft(request), null)));
    }

    @PutMapping(value = "/{templateId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Change everything but the code, with or without a new 3D model file",
            description = "multipart/form-data: part template is the JSON CustomizationTemplateRequest with version "
                    + "(Content-Type application/json), optional part file a new .glb replacing the one before, saved "
                    + "in the same transaction as the measurements. Drafts not yet approved follow the change (B39); "
                    + "approved designs keep the template frozen in their snapshot. A changed template is 409 "
                    + "OPTIMISTIC_LOCK. " + MODEL_FILE_RULES)
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.UPDATE)
    public ApiResponse<CustomizationTemplateResponse> update(
            @PathVariable UUID productId, @PathVariable UUID variantId, @PathVariable UUID templateId,
            @Valid @RequestPart("template") CustomizationTemplateRequest request,
            @RequestPart(value = "file", required = false) MultipartFile file) {
        long version = versionOf(request);
        return withModelFile(file, upload -> templates.update(productId, variantId, templateId, version,
                mapper.toDraft(request), upload));
    }

    @PutMapping(value = "/{templateId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Change what the 3D model file does not determine",
            description = "The JSON form of the multipart update, without a file: names, limits, margins, a cup's "
                    + "seam and rim, material and handle. Changing a measurement the file determines is 409 "
                    + "PRINT_CONFIG_MODEL_FILE_REQUIRED. version is required: a changed template is 409 "
                    + "OPTIMISTIC_LOCK.")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.UPDATE)
    public ApiResponse<CustomizationTemplateResponse> updateWithoutFile(
            @PathVariable UUID productId, @PathVariable UUID variantId, @PathVariable UUID templateId,
            @Valid @RequestBody CustomizationTemplateRequest request) {
        return ApiResponse.ok(respond(templates.update(productId, variantId, templateId,
                versionOf(request), mapper.toDraft(request), null)));
    }

    @PostMapping("/{templateId}/activation")
    @Operation(summary = "Let customers start designs on this template; the variant's previous one is retired")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.UPDATE)
    public ApiResponse<CustomizationTemplateResponse> activate(@PathVariable UUID productId,
                                                               @PathVariable UUID variantId,
                                                               @PathVariable UUID templateId,
                                                               @Valid @RequestBody TemplateVersionRequest request) {
        return ApiResponse.ok(respond(templates.activate(productId, variantId, templateId,
                request.version())));
    }

    @PostMapping("/{templateId}/deactivation")
    @Operation(summary = "Stop new designs on this template; drafts already on it keep it")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.UPDATE)
    public ApiResponse<CustomizationTemplateResponse> deactivate(@PathVariable UUID productId,
                                                                 @PathVariable UUID variantId,
                                                                 @PathVariable UUID templateId,
                                                                 @Valid @RequestBody TemplateVersionRequest request) {
        return ApiResponse.ok(respond(templates.deactivate(productId, variantId, templateId,
                request.version())));
    }

    @PutMapping(value = "/{templateId}/reference-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Attach a reference die-line (PDF or PNG, at most 20 MB) for customers to compare against",
            description = "Replaces the file attached before. Scanned and checked before it is stored; a PDF that "
                    + "runs actions, carries JavaScript, files or encryption is refused.")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.UPDATE)
    public ApiResponse<CustomizationTemplateResponse> attachReference(@PathVariable UUID productId,
                                                                      @PathVariable UUID variantId,
                                                                      @PathVariable UUID templateId,
                                                                      @RequestParam long version,
                                                                      @RequestPart("file") MultipartFile file) {
        try (var content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(respond(templates.attachReference(productId, variantId, templateId,
                    version, upload)));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    @GetMapping("/{templateId}/model-file/download-url")
    @Operation(summary = "A short-lived link to the template's 3D model; 404 when none is attached")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.READ)
    public ApiResponse<DownloadLink> modelLink(@PathVariable UUID productId, @PathVariable UUID variantId,
                                               @PathVariable UUID templateId) {
        return ApiResponse.ok(templates.modelLink(productId, variantId, templateId));
    }

    @GetMapping("/{templateId}/reference-file/download-url")
    @Operation(summary = "A short-lived link to the reference die-line")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.READ)
    public ApiResponse<DownloadLink> referenceLink(@PathVariable UUID productId, @PathVariable UUID variantId,
                                                   @PathVariable UUID templateId) {
        return ApiResponse.ok(templates.referenceLink(productId, variantId, templateId));
    }

    private static long versionOf(CustomizationTemplateRequest request) {
        if (request.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "version is required");
        }
        return request.version();
    }

    /**
     * Runs {@code save} with the uploaded {@code .glb}, or with null when the request carries none (an absent part, or
     * the empty part a browser sends for a file input left blank).
     */
    private ApiResponse<CustomizationTemplateResponse> withModelFile(MultipartFile file,
                                                                     Function<FileUpload, PrintConfig> save) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.ok(respond(save.apply(null)));
        }
        try (var content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename(), modelType(file), file.getSize(), content);
            return ApiResponse.ok(respond(save.apply(upload)));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    /**
     * A browser sends a {@code .glb} as {@code application/octet-stream} or with no type at all (FE request 2026-11-13
     * B-1); such a part named {@code *.glb} is taken as {@code model/gltf-binary}, and its first bytes then decide.
     */
    private static String modelType(MultipartFile file) {
        String declared = file.getContentType();
        String name = file.getOriginalFilename();
        boolean generic = declared == null || declared.isBlank()
                || declared.toLowerCase(java.util.Locale.ROOT).startsWith("application/octet-stream");
        if (generic && name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".glb")) {
            return "model/gltf-binary";
        }
        return declared;
    }
}
