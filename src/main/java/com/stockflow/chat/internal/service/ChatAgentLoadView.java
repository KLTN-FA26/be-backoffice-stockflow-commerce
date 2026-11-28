package com.stockflow.chat.internal.service;

import java.util.UUID;

/**
 * One agent's chat load on the managers' assignment board (sales-owner plan §10.4,
 * {@code GET /api/v1/chat/agents/workload}).
 *
 * @param openConversations    conversations of theirs that have not ended
 * @param overdueConversations of those, how many have a late first answer or a late resolution (BR-09): the queue's own
 *                             "overdue"
 */
public record ChatAgentLoadView(UUID userId, long openConversations, long overdueConversations) {
}
