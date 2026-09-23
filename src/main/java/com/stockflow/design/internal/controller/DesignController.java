package com.stockflow.design.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.design.internal.controller.dto.ArtifactDownloadResponse;
import com.stockflow.design.internal.controller.dto.DesignArtifactResponse;
import com.stockflow.design.internal.service.DesignArtifactService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestParam;
import com.stockflow.design.api.DesignArtifactRole;

/**
 * Private artifact revisions. Draft review and confirmation use DesignWorkflowController.
 */
@RestController
@RequestMapping("/api/v1/designs/{designId}/artifacts")
@PermissionResource(code = DesignResources.DESIGNS, group = "Design", label = "Designs", route = "/designs",
        apiPath = "/api/v1/designs", actions = {Action.VIEW_PAGE, Action.READ, Action.UPDATE, Action.APPROVE})
class DesignController {
    private final DesignArtifactService artifacts;
    private final DesignArtifactWebMapper mapper;

    DesignController(DesignArtifactService artifacts, DesignArtifactWebMapper mapper) {
        this.artifacts = artifacts;
        this.mapper = mapper;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignArtifactResponse> upload(@PathVariable UUID designId,
            @AuthenticatedUser CurrentUser user, @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "CUSTOMER_PREVIEW") DesignArtifactRole role,
            @org.springframework.web.bind.annotation.RequestHeader(name = "Idempotency-Key", required = false) String requestKey) {
        try (var content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            UUID actor = user == null ? null : user.userId();
            return ApiResponse.ok(mapper.toResponse(artifacts.upload(designId, actor, role, upload, requestKey)));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    @GetMapping
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<List<DesignArtifactResponse>> list(@PathVariable UUID designId,
                                                         @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(artifacts.list(designId, user == null ? null : user.userId()).stream()
                .map(mapper::toResponse).toList());
    }

    @GetMapping("/{artifactId}/download-url")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ResponseEntity<ApiResponse<ArtifactDownloadResponse>> downloadLink(@PathVariable UUID designId,
            @PathVariable UUID artifactId, @AuthenticatedUser CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(mapper.toResponse(
                artifacts.downloadLink(designId, artifactId, user == null ? null : user.userId()))));
    }
}
