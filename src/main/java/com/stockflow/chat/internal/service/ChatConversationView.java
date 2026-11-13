package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.domain.ConversationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A conversation as a list row or a detail page shows it, to the customer or to the staff. Fields only the staff see
 * are null in a customer's view: the escalation reason, the moves open to the caller.
 *
 * @param nextStatuses        the statuses the staff caller may ask for from here (16 §5); null for a customer
 * @param agentOnline         whether the agent in charge has the back office open now (16 §4.1 step 3)
 * @param firstResponseOverdue whether the first answer is late (BR-09); the queue shows it first
 * @param resolutionDueAt     when the conversation is to be resolved (BR-09, {@code stockflow.chat.resolution-target}
 *                            working days after the customer asked, again after they reopened it); staff only - the
 *                            customer is promised an answer, not a deadline the staff set themselves - null otherwise
 * @param resolutionOverdue   whether it is past that and not resolved; the queue shows it first too; staff only
 * @param lastSeq             the sequence of the newest message the caller may see
 * @param unread              how many of those the caller has not read
 * @param lastMessage         the newest message the caller may see; null when none
 * @param contexts            what it is about; in a list row without labels, on the detail page with them
 * @param readMarkers         how far each participant has read; detail page only
 * @param withinBusinessHours whether Sales are at work now (16 §4.1 step 3)
 * @param expectedResponseAt  when the customer can expect an answer: the first answer's due time while none came, or
 *                            the next opening outside business hours; null when nothing is awaited
 * @param canRate             whether the customer may rate it now (resolved, not rated yet)
 * @param csatScore           the customer's rating, 1-5; null until rated
 * @param csatComment         their comment; staff only
 */
public record ChatConversationView(UUID id, UUID customerId, String customerName, String subject,
                                   ConversationStatus status, Set<ConversationStatus> nextStatuses,
                                   UUID assignedAgentId, String assignedAgentName, boolean agentOnline,
                                   String escalationReason, Instant firstResponseDueAt, Instant firstRespondedAt,
                                   boolean firstResponseOverdue, Instant resolutionDueAt, boolean resolutionOverdue,
                                   Instant reopenUntil, UUID continuedFromId,
                                   Instant createdAt, Instant lastMessageAt, long lastSeq, long unread,
                                   ChatMessageView lastMessage, List<Context> contexts,
                                   List<ChatReadMarkerView> readMarkers, boolean withinBusinessHours,
                                   Instant expectedResponseAt, Instant resolvedAt, boolean canRate, Integer csatScore,
                                   String csatComment, Instant csatSubmittedAt) {

    /**
     * A document the conversation is about.
     *
     * @param refId  the document's id (for a customer's SKU, the variant's)
     * @param label  its number or name (quote, order, contract number; design name); null in list rows
     * @param status its own status in its module; null in list rows
     */
    public record Context(UUID id, ContextKind kind, UUID refId, boolean primary, String label, String status,
                          Instant attachedAt) {
    }
}
