package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.Conversation;
import com.stockflow.assistant.internal.domain.ConversationRepository;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Message;
import com.stockflow.assistant.internal.domain.MessageRole;
import com.stockflow.assistant.internal.domain.PendingMessage;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.Turn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The transcript a reopened conversation shows (design §7): one answer per question, read exactly as the live answer
 * was, whatever the model wrote between its tool calls, and a failed question closed by what the user was told.
 */
class ConversationServiceTest {

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");
    private static final String MODEL = "claude-opus-5-5";

    private final ConversationRepository store = mock(ConversationRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ConversationService service = new ConversationService(store, Clock.fixed(NOW, ZoneOffset.UTC), json);

    @BeforeEach
    void conversation() {
        when(store.findInScope(CONVERSATION)).thenReturn(Optional.of(
                new Conversation(CONVERSATION, UUID.randomUUID(), "t", 6, NOW, null, NOW)));
    }

    @Test
    @DisplayName("one answer per question: the last text, carrying every call made for it, its citations and "
            + "proposals; a line the model wrote beside its tool call takes nothing with it")
    void oneAnswerPerQuestion() {
        ToolExecution search = new ToolExecution(new ToolCall("toolu_1", "searchKnowledge", "{}"), ToolOutcome.OK,
                "{\"passages\":[{\"anchor\":\"BR-RMA-002\"}]}", 3);
        ToolExecution reorder = new ToolExecution(new ToolCall("toolu_2", "proposeReorder", "{}"), ToolOutcome.OK,
                "{\"lines\":[],\"proposals\":[{\"type\":\"PURCHASE_ORDER_DRAFT\",\"supplierCode\":\"SUP-A\","
                        + "\"supplierName\":\"A\",\"generatedAt\":\"2026-10-25T03:00:00Z\","
                        + "\"lines\":[{\"sku\":\"CUP-12OZ\",\"quantity\":1276}]}]}", 5);
        List<Message> messages = List.of(
                question(0, "Hàng in theo yêu cầu có được trả lại không, và nên nhập thêm CUP-12OZ không?"),
                model(1, "Để tôi kiểm tra quy định đổi trả.", LanguageModel.StopReason.TOOL_USE, search, reorder),
                results(2, "toolu_1", "toolu_2"),
                model(3, "Không, trừ khi hàng bị lỗi (BR-RMA-002). Nên nhập 1.276 cái.",
                        LanguageModel.StopReason.END_TURN));
        when(store.messagesInScope(CONVERSATION)).thenReturn(messages);

        List<ConversationService.TranscriptEntry> transcript = service.transcript(CONVERSATION);

        assertThat(transcript).extracting(ConversationService.TranscriptEntry::role)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        ConversationService.TranscriptEntry answer = transcript.get(1);
        assertThat(answer.messageId()).isEqualTo(messages.get(3).id());
        assertThat(answer.text()).startsWith("Không, trừ khi hàng bị lỗi");
        assertThat(answer.toolCalls()).containsExactly(
                new ConversationService.ToolCallView("searchKnowledge", ToolOutcome.OK),
                new ConversationService.ToolCallView("proposeReorder", ToolOutcome.OK));
        assertThat(answer.citations()).containsExactly("BR-RMA-002");
        assertThat(answer.proposals()).singleElement()
                .satisfies(proposal -> assertThat(proposal.supplierCode()).isEqualTo("SUP-A"));
        assertThat(answer.cutShort()).isFalse();
    }

    @Test
    @DisplayName("the live answer and the reopened transcript read an exchange the same way")
    void liveAndReopenedAgree() {
        ToolExecution search = new ToolExecution(new ToolCall("toolu_1", "searchKnowledge", "{}"), ToolOutcome.OK,
                "{\"passages\":[{\"anchor\":\"BR-RMA-002\"}]}", 3);
        List<Message> exchange = List.of(
                model(1, "Để tôi kiểm tra.", LanguageModel.StopReason.TOOL_USE, search),
                results(2, "toolu_1"),
                model(3, "Không (BR-RMA-002).", LanguageModel.StopReason.END_TURN));
        when(store.messagesInScope(CONVERSATION)).thenReturn(List.of(question(0, "Trả lại được không?"),
                exchange.get(0), exchange.get(1), exchange.get(2)));

        AssistantService.Answer live = AssistantService.Answer.of(exchange, json);
        ConversationService.TranscriptEntry reopened = service.transcript(CONVERSATION).getLast();

        assertThat(reopened.messageId()).isEqualTo(live.messageId());
        assertThat(reopened.text()).isEqualTo(live.text());
        assertThat(reopened.toolCalls()).isEqualTo(live.toolCalls());
        assertThat(reopened.citations()).isEqualTo(live.citations());
        assertThat(reopened.cutShort()).isEqualTo(live.cutShort());
    }

    @Test
    @DisplayName("a failed question shows the message its closing turn holds; an empty reply kept for the record "
            + "shows nothing; a question still being answered has no answer yet")
    void failedEmptyAndRunning() {
        when(store.messagesInScope(CONVERSATION)).thenReturn(List.of(
                question(0, "Lô nào sắp hết hạn?"),
                model(1, null, LanguageModel.StopReason.OTHER),
                closing(2, "Trợ lý tạm thời không trả lời được, vui lòng thử lại sau ít phút"),
                question(3, "Còn bao nhiêu CUP-12OZ?")));

        List<ConversationService.TranscriptEntry> transcript = service.transcript(CONVERSATION);

        assertThat(transcript).extracting(ConversationService.TranscriptEntry::role)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER);
        assertThat(transcript.get(1).text()).startsWith("Trợ lý tạm thời không trả lời được");
        assertThat(transcript.get(1).toolCalls()).isEmpty();
    }

    @Test
    @DisplayName("an answer is cut short when any reply behind it was, not only its last one")
    void cutShort() {
        when(store.messagesInScope(CONVERSATION)).thenReturn(List.of(
                question(0, "Liệt kê tất cả"),
                model(1, "Phần đầu", LanguageModel.StopReason.MAX_TOKENS),
                results(2, "toolu_9"),
                closing(3, "Phần đầu\n\nCâu trả lời quá dài nên chưa hoàn tất.")));

        assertThat(service.transcript(CONVERSATION).getLast().cutShort()).isTrue();
    }

    // ------------------------------------------------------------------------------------------------------------

    private static Message question(int sequence, String text) {
        return new Message(UUID.randomUUID(), sequence, MessageRole.USER, text, new Turn.Question(text, "ctx"), null,
                null, LanguageModel.Usage.NONE, List.of(), NOW);
    }

    private static Message model(int sequence, String text, LanguageModel.StopReason stop,
                                 ToolExecution... executions) {
        List<ToolExecution> ran = List.of(executions);
        return new Message(UUID.randomUUID(), sequence, MessageRole.ASSISTANT, text,
                new Turn.ModelTurn("{}", text, ran.stream().map(ToolExecution::call).toList()), MODEL, stop,
                new LanguageModel.Usage(100, 10, 0, 0), ran, NOW);
    }

    private static Message results(int sequence, String... callIds) {
        return new Message(UUID.randomUUID(), sequence, MessageRole.TOOL_RESULTS, null,
                new Turn.ToolResults(Arrays.stream(callIds).map(id -> new ToolResult(id, "{}", false)).toList()),
                null, null, LanguageModel.Usage.NONE, List.of(), NOW);
    }

    private static Message closing(int sequence, String text) {
        return new Message(UUID.randomUUID(), sequence, MessageRole.ASSISTANT, text,
                new Turn.ModelTurn("{\"closing\":true}", text, List.of()), PendingMessage.CLOSING_MODEL,
                LanguageModel.StopReason.OTHER, LanguageModel.Usage.NONE, List.of(), NOW);
    }
}
