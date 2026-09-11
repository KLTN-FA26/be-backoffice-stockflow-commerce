package com.stockflow.chat.internal.entity;

import com.stockflow.chat.internal.domain.ConversationStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/** JPA mapping of a conversation (table {@code chat.conversation}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "conversation", schema = "chat")
public class ConversationJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "subject", length = 300)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ConversationStatus status;

    @Column(name = "assigned_agent_id")
    private UUID assignedAgentId;

    protected ConversationJpaEntity() {
    }

    public ConversationJpaEntity(UUID id, UUID customerId, UUID orderId, String subject,
                                 ConversationStatus status, UUID assignedAgentId) {
        super(id);
        this.customerId = customerId;
        this.orderId = orderId;
        this.subject = subject;
        this.status = status;
        this.assignedAgentId = assignedAgentId;
    }

    public UUID getCustomerId() { return customerId; }
    public UUID getOrderId() { return orderId; }
    public String getSubject() { return subject; }
    public ConversationStatus getStatus() { return status; }
    public UUID getAssignedAgentId() { return assignedAgentId; }
}
