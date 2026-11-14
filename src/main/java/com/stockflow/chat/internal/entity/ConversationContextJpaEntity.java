package com.stockflow.chat.internal.entity;

import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of {@code chat.conversation_context}: one row per document a conversation is about, the document in the
 * one foreign-key column of its kind. Written once ({@code tg_conversation_context_owner} refuses a change), so nothing
 * here is updatable.
 */
@Entity
@Table(name = "conversation_context", schema = "chat")
public class ConversationContextJpaEntity extends BaseEntity {

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32, updatable = false)
    private ContextKind kind;

    @Column(name = "product_id", updatable = false)
    private UUID productId;

    @Column(name = "variant_id", updatable = false)
    private UUID variantId;

    @Column(name = "design_id", updatable = false)
    private UUID designId;

    @Column(name = "design_project_id", updatable = false)
    private UUID designProjectId;

    @Column(name = "quote_id", updatable = false)
    private UUID quoteId;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "contract_id", updatable = false)
    private UUID contractId;

    @Column(name = "production_order_id", updatable = false)
    private UUID productionOrderId;

    @Column(name = "einvoice_id", updatable = false)
    private UUID einvoiceId;

    @Column(name = "is_primary", nullable = false, updatable = false)
    private boolean primary;

    @Column(name = "attached_by", updatable = false)
    private UUID attachedBy;

    @Column(name = "attached_at", nullable = false, updatable = false)
    private Instant attachedAt;

    protected ConversationContextJpaEntity() {
    }

    public ConversationContextJpaEntity(UUID id, UUID conversationId, ContextKind kind, UUID reference,
                                        boolean primary, UUID attachedBy, Instant attachedAt) {
        super(id);
        this.conversationId = conversationId;
        this.kind = kind;
        this.primary = primary;
        this.attachedBy = attachedBy;
        this.attachedAt = attachedAt;
        switch (kind) {
            case PRODUCT -> this.productId = reference;
            case CUSTOMER_SKU -> this.variantId = reference;
            case DESIGN -> this.designId = reference;
            case DESIGN_PROJECT -> this.designProjectId = reference;
            case QUOTE -> this.quoteId = reference;
            case ORDER -> this.orderId = reference;
            case CONTRACT -> this.contractId = reference;
            case PRODUCTION_ORDER -> this.productionOrderId = reference;
            case EINVOICE -> this.einvoiceId = reference;
        }
    }

    /** The id in the column of its kind. */
    public UUID getReference() {
        return switch (kind) {
            case PRODUCT -> productId;
            case CUSTOMER_SKU -> variantId;
            case DESIGN -> designId;
            case DESIGN_PROJECT -> designProjectId;
            case QUOTE -> quoteId;
            case ORDER -> orderId;
            case CONTRACT -> contractId;
            case PRODUCTION_ORDER -> productionOrderId;
            case EINVOICE -> einvoiceId;
        };
    }

    public UUID getConversationId() { return conversationId; }
    public ContextKind getKind() { return kind; }
    public boolean isPrimary() { return primary; }
    public UUID getAttachedBy() { return attachedBy; }
    public Instant getAttachedAt() { return attachedAt; }
}
