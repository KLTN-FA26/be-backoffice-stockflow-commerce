package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.AnswerResponse;
import com.stockflow.assistant.internal.controller.dto.ConversationResponse;
import com.stockflow.assistant.internal.controller.dto.ToolCallResponse;
import com.stockflow.assistant.internal.controller.dto.TranscriptEntryResponse;
import com.stockflow.assistant.internal.service.AssistantService;
import com.stockflow.assistant.internal.service.ConversationService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class AssistantWebMapper {

    ConversationResponse toResponse(ConversationService.ConversationView conversation) {
        return new ConversationResponse(conversation.id(), conversation.title(), conversation.messageCount(),
                conversation.lastActivityAt(), conversation.createdAt());
    }

    TranscriptEntryResponse toResponse(ConversationService.TranscriptEntry entry) {
        return new TranscriptEntryResponse(entry.messageId(), entry.sequence(), entry.role().name(), entry.text(),
                entry.at(), calls(entry.toolCalls()), entry.cutShort());
    }

    AnswerResponse toResponse(AssistantService.Answer answer) {
        return new AnswerResponse(answer.messageId(), answer.text(), calls(answer.toolCalls()), answer.cutShort(),
                new AnswerResponse.Usage(answer.usage().inputTokens(), answer.usage().cacheReadTokens(),
                        answer.usage().outputTokens()));
    }

    private static List<ToolCallResponse> calls(List<ConversationService.ToolCallView> calls) {
        return calls.stream().map(call -> new ToolCallResponse(call.tool(), call.outcome().name())).toList();
    }
}
