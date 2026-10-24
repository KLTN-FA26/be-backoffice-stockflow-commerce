package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.AnswerResponse;
import com.stockflow.assistant.internal.controller.dto.ConversationResponse;
import com.stockflow.assistant.internal.controller.dto.ProposalResponse;
import com.stockflow.assistant.internal.controller.dto.ToolCallResponse;
import com.stockflow.assistant.internal.controller.dto.TranscriptEntryResponse;
import com.stockflow.assistant.internal.service.AnswerSources;
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
                entry.at(), calls(entry.toolCalls()), entry.cutShort(), entry.citations(),
                proposals(entry.proposals()));
    }

    AnswerResponse toResponse(AssistantService.Answer answer) {
        return new AnswerResponse(answer.messageId(), answer.text(), calls(answer.toolCalls()), answer.cutShort(),
                new AnswerResponse.Usage(answer.usage().inputTokens(), answer.usage().cacheReadTokens(),
                        answer.usage().outputTokens()),
                answer.citations(), proposals(answer.proposals()));
    }

    ToolCallResponse toResponse(ConversationService.ToolCallView call) {
        return new ToolCallResponse(call.tool(), call.outcome().name());
    }

    private List<ToolCallResponse> calls(List<ConversationService.ToolCallView> calls) {
        return calls.stream().map(this::toResponse).toList();
    }

    private static List<ProposalResponse> proposals(List<AnswerSources.Proposal> proposals) {
        return proposals.stream().map(proposal -> new ProposalResponse(proposal.type(), proposal.supplierCode(),
                proposal.supplierName(), proposal.generatedAt(), proposal.lines().stream()
                .map(line -> new ProposalResponse.Line(line.sku(), line.quantity()))
                .toList())).toList();
    }
}
