package com.stockflow.design.internal.controller;

import com.stockflow.common.storage.DownloadLink;
import com.stockflow.design.internal.controller.dto.ArtifactDownloadResponse;
import com.stockflow.design.internal.controller.dto.DesignArtifactResponse;
import com.stockflow.design.internal.domain.DesignArtifact;
import org.springframework.stereotype.Component;

@Component
class DesignArtifactWebMapper {
    DesignArtifactResponse toResponse(DesignArtifact artifact) {
        var file = artifact.file();
        return new DesignArtifactResponse(artifact.id(), artifact.designId(), file.originalName(),
                file.contentType(), file.sizeBytes(), file.storedAt(), artifact.checksum());
    }

    ArtifactDownloadResponse toResponse(DownloadLink link) {
        return new ArtifactDownloadResponse(link.url(), link.expiresAt());
    }
}
