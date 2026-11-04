package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.AnswerResponse;
import com.stockflow.assistant.internal.controller.dto.ConversationResponse;
import com.stockflow.assistant.internal.controller.dto.FeedbackRequest;
import com.stockflow.assistant.internal.controller.dto.ProposalResponse;
import com.stockflow.assistant.internal.controller.dto.TableResponse;
import com.stockflow.assistant.internal.controller.dto.ToolCallResponse;
import com.stockflow.assistant.internal.controller.dto.TranscriptEntryResponse;
import com.stockflow.assistant.internal.domain.AnswerFeedback;
import com.stockflow.assistant.internal.domain.FeedbackVerdict;
import com.stockflow.assistant.internal.service.AnswerSources;
import com.stockflow.assistant.internal.service.AssistantService;
import com.stockflow.assistant.internal.service.ConversationService;
import com.stockflow.assistant.internal.service.FollowUps;
import com.stockflow.assistant.internal.service.ResultTable;
import com.stockflow.common.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Application types to response bodies, for the one reader at hand: the follow-up questions an answer offers depend on
 * what that reader may ask now ({@link FollowUps}), so they are decided here, each time an answer is shown.
 */
@Component
class AssistantWebMapper {

    private final FollowUps followUps;

    AssistantWebMapper(FollowUps followUps) {
        this.followUps = followUps;
    }

    ConversationResponse toResponse(ConversationService.ConversationView conversation) {
        return new ConversationResponse(conversation.id(), conversation.title(), conversation.messageCount(),
                conversation.lastActivityAt(), conversation.createdAt());
    }

    /** A transcript for {@code reader}: what they may use is read once, for every answer in it. */
    List<TranscriptEntryResponse> toResponse(List<ConversationService.TranscriptEntry> transcript,
                                             CurrentUser reader) {
        Set<String> granted = followUps.toolsFor(reader);
        return transcript.stream().map(entry -> toResponse(entry, granted)).toList();
    }

    TranscriptEntryResponse toResponse(ConversationService.TranscriptEntry entry, Set<String> granted) {
        return new TranscriptEntryResponse(entry.messageId(), entry.sequence(), entry.role().name(), entry.text(),
                entry.at(), calls(entry.toolCalls()), entry.cutShort(), entry.citations(),
                proposals(entry.proposals()), tables(entry.tables()), followUps.suggestions(entry.followUps(), granted),
                entry.unverifiedNumbers(), entry.feedback() == null ? null : entry.feedback().name());
    }

    AnswerResponse toResponse(AssistantService.Answer answer, CurrentUser reader) {
        return new AnswerResponse(answer.messageId(), answer.text(), calls(answer.toolCalls()), answer.cutShort(),
                new AnswerResponse.Usage(answer.usage().inputTokens(), answer.usage().cacheReadTokens(),
                        answer.usage().outputTokens()),
                answer.citations(), proposals(answer.proposals()), tables(answer.tables()),
                followUps.suggestionsFor(answer.followUps(), reader), answer.unverifiedNumbers());
    }

    ToolCallResponse toResponse(ConversationService.ToolCallView call) {
        return new ToolCallResponse(call.tool(), call.outcome().name());
    }

    /** The request's verdict; its pattern admits only the verdict names. */
    AnswerFeedback toFeedback(FeedbackRequest request) {
        return new AnswerFeedback(FeedbackVerdict.valueOf(request.verdict()), request.reason());
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

    private static List<TableResponse> tables(List<ResultTable.Table> tables) {
        return tables.stream().map(AssistantWebMapper::table).toList();
    }

    /** An answer's table, or a section of the "today" card: one shape for the panel to draw (contract C3). */
    static TableResponse table(ResultTable.Table table) {
        return new TableResponse(table.key(), table.tool(), table.columns(), table.rows(), table.total(),
                table.truncated());
    }
}
