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
import com.stockflow.product.internal.controller.dto.CustomizationTemplateRequest;
import com.stockflow.product.internal.controller.dto.CustomizationTemplateResponse;
import com.stockflow.product.internal.controller.dto.TemplateVersionRequest;
import com.stockflow.product.internal.service.PrintConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import java.util.UUID;

/**
 * Customization templates of a variant (kltn-docs 01 §4.10, BR-21): what a customer may print on it and where. The
 * Sales Manager's ({@code product-customization-templates}, held by SALES_MANAGER and SYSTEM_ADMIN).
 *
 * <p>The request carries the product's model and, per print area, only its name and margins: the areas' position and
 * shape are computed on the server, so the storefront and the preflight draw the same geometry. A template is created
 * inactive; activating it retires the variant's template active before. Handlers are public so the permission proxy
 * is certain to advise them.</p>
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
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.DELETE})
class CustomizationTemplateController {

    private final PrintConfigService templates;
    private final CustomizationTemplateWebMapper mapper;

    CustomizationTemplateController(PrintConfigService templates, CustomizationTemplateWebMapper mapper) {
        this.templates = templates;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "The variant's templates, by code")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.READ)
    public ApiResponse<List<CustomizationTemplateResponse>> list(@PathVariable UUID productId,
                                                                 @PathVariable UUID variantId) {
        return ApiResponse.ok(templates.list(productId, variantId).stream().map(mapper::toResponse).toList());
    }

    @GetMapping("/{templateId}")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.READ)
    public ApiResponse<CustomizationTemplateResponse> get(@PathVariable UUID productId, @PathVariable UUID variantId,
                                                          @PathVariable UUID templateId) {
        return ApiResponse.ok(mapper.toResponse(templates.get(productId, variantId, templateId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an inactive template; its print areas are computed from the model",
            description = "Only on a variant of a CUSTOMIZABLE product (else 409 PRODUCT_NOT_CUSTOMIZABLE). A cup has "
                    + "one area, BODY; a bag four, FRONT, BACK, LEFT, RIGHT. A model with impossible sizes, a missing or "
                    + "extra area, or margins leaving nothing to print on is 400 PRINT_CONFIG_INVALID; a code the "
                    + "variant already has is 409 PRINT_CONFIG_CODE_TAKEN.")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.CREATE)
    public ApiResponse<CustomizationTemplateResponse> create(@PathVariable UUID productId,
                                                             @PathVariable UUID variantId,
                                                             @Valid @RequestBody CustomizationTemplateRequest request) {
        return ApiResponse.ok(mapper.toResponse(templates.create(productId, variantId, mapper.toDraft(request))));
    }

    @PutMapping("/{templateId}")
    @Operation(summary = "Change everything but the code; the print areas are computed again",
            description = "Drafts not yet approved follow the change (B39); approved designs keep the template frozen "
                    + "in their snapshot. version is required: a changed template is 409 OPTIMISTIC_LOCK.")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.UPDATE)
    public ApiResponse<CustomizationTemplateResponse> update(@PathVariable UUID productId,
                                                             @PathVariable UUID variantId,
                                                             @PathVariable UUID templateId,
                                                             @Valid @RequestBody CustomizationTemplateRequest request) {
        if (request.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "version is required");
        }
        return ApiResponse.ok(mapper.toResponse(templates.update(productId, variantId, templateId,
                request.version(), mapper.toDraft(request))));
    }

    @PostMapping("/{templateId}/activation")
    @Operation(summary = "Let customers start designs on this template; the variant's previous one is retired")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.UPDATE)
    public ApiResponse<CustomizationTemplateResponse> activate(@PathVariable UUID productId,
                                                               @PathVariable UUID variantId,
                                                               @PathVariable UUID templateId,
                                                               @Valid @RequestBody TemplateVersionRequest request) {
        return ApiResponse.ok(mapper.toResponse(templates.activate(productId, variantId, templateId,
                request.version())));
    }

    @PostMapping("/{templateId}/deactivation")
    @Operation(summary = "Stop new designs on this template; drafts already on it keep it")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.UPDATE)
    public ApiResponse<CustomizationTemplateResponse> deactivate(@PathVariable UUID productId,
                                                                 @PathVariable UUID variantId,
                                                                 @PathVariable UUID templateId,
                                                                 @Valid @RequestBody TemplateVersionRequest request) {
        return ApiResponse.ok(mapper.toResponse(templates.deactivate(productId, variantId, templateId,
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
            return ApiResponse.ok(mapper.toResponse(templates.attachReference(productId, variantId, templateId,
                    version, upload)));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    /**
     * The template's 3D model (FE request 2026-11-13 B-1). A browser sends a {@code .glb} as
     * {@code application/octet-stream} or with no type at all; such a part named {@code *.glb} is taken as
     * {@code model/gltf-binary}, and its first bytes then decide.
     */
    @PutMapping(value = "/{templateId}/model-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Attach the template's 3D model (glTF 2.0 binary .glb, at most 10 MB)",
            description = "Replaces the file attached before and moves the template's version; drafts follow, approved "
                    + "designs keep the model frozen in their snapshot. Not a glTF 2.0 binary is 415 "
                    + "UNSUPPORTED_MEDIA_TYPE, larger than 10 MB 413 PAYLOAD_TOO_LARGE, a file the scan refuses 400 "
                    + "VALIDATION_FAILED, a changed template 409 OPTIMISTIC_LOCK. Only the file's SHA-256 is kept "
                    + "about its contents (model3d.fileChecksum).")
    @RequiresPermission(resource = ProductResources.CUSTOMIZATION_TEMPLATES, action = Action.UPDATE)
    public ApiResponse<CustomizationTemplateResponse> attachModel(@PathVariable UUID productId,
                                                                  @PathVariable UUID variantId,
                                                                  @PathVariable UUID templateId,
                                                                  @RequestParam long version,
                                                                  @RequestPart("file") MultipartFile file) {
        try (var content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename(), modelType(file), file.getSize(), content);
            return ApiResponse.ok(mapper.toResponse(templates.attachModel(productId, variantId, templateId,
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
