package com.stockflow.assistant.internal.entity;

import com.stockflow.assistant.internal.domain.MessageRole;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * {@code assistant.message}. Append-only: every column is {@code updatable = false}, so a turn the model has seen
 * can never be rewritten, which is what keeps a replayed history valid (design §3.4).
 */
@Entity
@Table(name = "message", schema = "assistant")
public class AssistantMessageJpaEntity extends BaseEntity {

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "sequence", nullable = false, updatable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16, updatable = false)
    private MessageRole role;

    @Column(name = "text", updatable = false)
    private String text;

    /** The turn as JSON; the repository's codec is the only reader and writer of its shape. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String content;

    @Column(name = "stop_reason", length = 32, updatable = false)
    private String stopReason;

    @Column(name = "model", length = 64, updatable = false)
    private String model;

    @Column(name = "input_tokens", updatable = false)
    private Integer inputTokens;

    @Column(name = "output_tokens", updatable = false)
    private Integer outputTokens;

    @Column(name = "cache_read_tokens", updatable = false)
    private Integer cacheReadTokens;

    @Column(name = "cache_write_tokens", updatable = false)
    private Integer cacheWriteTokens;

    protected AssistantMessageJpaEntity() {
    }

    public AssistantMessageJpaEntity(UUID id, UUID conversationId, int sequence, MessageRole role, String text,
                            String content, String stopReason, String model, Integer inputTokens,
                            Integer outputTokens, Integer cacheReadTokens, Integer cacheWriteTokens) {
        super(id);
        this.conversationId = conversationId;
        this.sequence = sequence;
        this.role = role;
        this.text = text;
        this.content = content;
        this.stopReason = stopReason;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
    }

    public UUID getConversationId() { return conversationId; }
    public int getSequence() { return sequence; }
    public MessageRole getRole() { return role; }
    public String getText() { return text; }
    public String getContent() { return content; }
    public String getStopReason() { return stopReason; }
    public String getModel() { return model; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public Integer getCacheReadTokens() { return cacheReadTokens; }
    public Integer getCacheWriteTokens() { return cacheWriteTokens; }
}
