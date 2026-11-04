package com.stockflow.assistant.internal.entity;

import com.stockflow.assistant.internal.domain.ToolChannel;
import com.stockflow.assistant.internal.domain.ToolOutcome;
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
 * {@code assistant.tool_call}. Append-only. A chat run belongs to its conversation and turn; an MCP or "today" run
 * belongs to none ({@code V20261104000100}).
 */
@Entity
@Table(name = "tool_call", schema = "assistant")
public class AssistantToolCallJpaEntity extends BaseEntity {

    @Column(name = "conversation_id", updatable = false)
    private UUID conversationId;

    @Column(name = "message_id", updatable = false)
    private UUID messageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private ToolChannel channel;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "call_id", nullable = false, length = 100, updatable = false)
    private String callId;

    @Column(name = "tool_name", nullable = false, length = 64, updatable = false)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "arguments", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String arguments;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16, updatable = false)
    private ToolOutcome outcome;

    @Column(name = "result", nullable = false, updatable = false)
    private String result;

    @Column(name = "duration_ms", nullable = false, updatable = false)
    private int durationMs;

    protected AssistantToolCallJpaEntity() {
    }

    public AssistantToolCallJpaEntity(UUID id, ToolChannel channel, UUID userId, UUID conversationId, UUID messageId,
                                      String callId, String toolName, String arguments, ToolOutcome outcome,
                                      String result, int durationMs) {
        super(id);
        this.channel = channel;
        this.userId = userId;
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.callId = callId;
        this.toolName = toolName;
        this.arguments = arguments;
        this.outcome = outcome;
        this.result = result;
        this.durationMs = durationMs;
    }

    public UUID getConversationId() { return conversationId; }
    public UUID getMessageId() { return messageId; }

    public ToolChannel getChannel() { return channel; }

    public UUID getUserId() { return userId; }
    public String getCallId() { return callId; }
    public String getToolName() { return toolName; }
    public String getArguments() { return arguments; }
    public ToolOutcome getOutcome() { return outcome; }
    public String getResult() { return result; }
    public int getDurationMs() { return durationMs; }
}
