package com.stockflow.chat.internal.entity;

import com.stockflow.chat.internal.domain.MessageKind;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/**
 * JPA mapping of {@code chat.message_attachment}: a stored file of a message. The message's kind is a column too,
 * because the composite key to {@code chat.message (id, kind)} is what limits files to attachment messages and notes.
 */
@Entity
@Immutable
@Table(name = "message_attachment", schema = "chat")
public class MessageAttachmentJpaEntity extends BaseEntity {

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_kind", nullable = false, length = 16)
    private MessageKind messageKind;

    @Column(name = "file_key", nullable = false, length = 512)
    private String fileKey;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    protected MessageAttachmentJpaEntity() {
    }

    public MessageAttachmentJpaEntity(UUID id, UUID messageId, MessageKind messageKind, String fileKey,
                                      String originalName, String contentType, long sizeBytes, String sha256,
                                      Integer width, Integer height, UUID uploadedBy) {
        super(id);
        this.messageId = messageId;
        this.messageKind = messageKind;
        this.fileKey = fileKey;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.width = width;
        this.height = height;
        this.uploadedBy = uploadedBy;
    }

    public UUID getMessageId() { return messageId; }
    public MessageKind getMessageKind() { return messageKind; }
    public String getFileKey() { return fileKey; }
    public String getOriginalName() { return originalName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public UUID getUploadedBy() { return uploadedBy; }
}
