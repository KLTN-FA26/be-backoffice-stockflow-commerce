package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.design.internal.domain.DesignArtifact;
import com.stockflow.design.internal.domain.DesignArtifactRepository;
import com.stockflow.design.internal.domain.DesignDraft;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.BufferedInputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class DesignArtifactService {
    private final DesignArtifactRepository designs;
    private final FileTransfers files;

    public DesignArtifactService(DesignArtifactRepository designs, FileTransfers files) {
        this.designs = designs;
        this.files = files;
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#designId")
    public DesignArtifact upload(UUID designId, UUID userId, FileUpload upload) {
        var draft = requireDraft(designId, userId, true);
        draft.requireEditable();
        if (designs.hasSnapshot(designId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "A confirmed design requires a new draft");
        }
        MessageDigest digest = sha256();
        // Buffer outside the digest so policy sniffing/reset never hashes the same bytes twice.
        var content = new BufferedInputStream(new DigestInputStream(upload.content(), digest));
        var file = files.store(FileCategory.DESIGN_RENDER,
                new FileUpload(upload.originalName(), upload.contentType(), upload.sizeBytes(), content));
        var artifact = new DesignArtifact(Identifiers.newId(), designId, file,
                HexFormat.of().formatHex(digest.digest()));
        designs.attach(artifact);
        return artifact;
    }

    @Transactional(readOnly = true)
    public List<DesignArtifact> list(UUID designId, UUID userId) {
        requireDraft(designId, userId, false);
        return designs.findArtifacts(designId);
    }

    @Transactional(readOnly = true)
    public DownloadLink downloadLink(UUID designId, UUID artifactId, UUID userId) {
        var artifact = list(designId, userId).stream().filter(file -> file.id().equals(artifactId))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return files.downloadLink(artifact.file().key());
    }

    private DesignDraft requireDraft(UUID designId, UUID userId, boolean forUpdate) {
        if (userId == null) { throw new BusinessException(ErrorCode.UNAUTHORIZED); }
        var draft = designs.findDraft(designId, userId, forUpdate)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        draft.requireAccess(userId);
        return draft;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The Java runtime must support SHA-256", impossible);
        }
    }
}
