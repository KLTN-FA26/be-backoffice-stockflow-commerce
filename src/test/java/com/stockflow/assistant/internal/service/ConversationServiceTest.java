package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.AnswerFeedback;
import com.stockflow.assistant.internal.domain.AnswerFeedbackRepository;
import com.stockflow.assistant.internal.domain.Conversation;
import com.stockflow.assistant.internal.domain.ConversationRepository;
import com.stockflow.assistant.internal.domain.FeedbackVerdict;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Message;
import com.stockflow.assistant.internal.domain.MessageRole;
import com.stockflow.assistant.internal.domain.PendingMessage;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The transcript a reopened conversation shows (design §7): one answer per question, read exactly as the live answer
 * was, whatever the model wrote between its tool calls, and a failed question closed by what the user was told; and
 * the verdicts its owner gives the answers.
 */
class ConversationServiceTest {

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");
    private static final String MODEL = "claude-opus-5-5";

    private final ConversationRepository store = mock(ConversationRepository.class);
    private final AnswerFeedbackRepository verdicts = mock(AnswerFeedbackRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ConversationService service = new ConversationService(store, verdicts,
            Clock.fixed(NOW, ZoneOffset.UTC), json);

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
    @DisplayName("storing an answer suggests its proposals on its last text, in the same call; the transcript shows "
            + "each proposal with its suggestion")
    void answersSuggestTheirProposals() {
        AssistantActions actions = org.mockito.Mockito.mock(AssistantActions.class);
        ConversationService suggesting = new ConversationService(store, verdicts, Clock.fixed(NOW, ZoneOffset.UTC),
                json, actions);
        ToolExecution reorder = new ToolExecution(new ToolCall("toolu_2", "proposeReorder", "{}"), ToolOutcome.OK,
                "{\"lines\":[],\"proposals\":[{\"type\":\"PURCHASE_ORDER_DRAFT\",\"supplierCode\":\"SUP-A\","
                        + "\"supplierName\":\"A\",\"generatedAt\":\"2026-10-25T03:00:00Z\","
                        + "\"lines\":[{\"sku\":\"CUP-12OZ\",\"quantity\":1276}]}]}", 5);
        List<Message> produced = List.of(
                model(1, null, LanguageModel.StopReason.TOOL_USE, reorder),
                results(2, "toolu_2"),
                model(3, "Nên nhập thêm CUP-12OZ.", LanguageModel.StopReason.END_TURN));
        UUID owner = UUID.randomUUID();
        when(store.appendAndRelease(org.mockito.ArgumentMatchers.eq(CONVERSATION), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(produced);

        suggesting.appendAndRelease(CONVERSATION, owner, NOW, List.of());

        org.mockito.ArgumentCaptor<List<AnswerSources.Proposal>> proposals = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(actions).suggestPurchaseOrders(org.mockito.ArgumentMatchers.eq(owner),
                org.mockito.ArgumentMatchers.eq(CONVERSATION), org.mockito.ArgumentMatchers.eq(produced.get(2).id()),
                proposals.capture());
        assertThat(proposals.getValue()).singleElement()
                .satisfies(proposal -> assertThat(proposal.key()).isEqualTo("PURCHASE_ORDER_DRAFT:SUP-A"));

        UUID suggestion = UUID.randomUUID();
        when(store.messagesInScope(CONVERSATION)).thenReturn(java.util.stream.Stream.concat(
                java.util.stream.Stream.of(question(0, "Có nên nhập thêm CUP-12OZ không?")), produced.stream()).toList());
        when(actions.ofAnswers(org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Map.of(produced.get(2).id(), java.util.Map.of("PURCHASE_ORDER_DRAFT:SUP-A",
                        new AssistantActions.ActionView(suggestion,
                                com.stockflow.assistant.internal.domain.AssistantAction.Kind.PURCHASE_ORDER_DRAFT,
                                com.stockflow.assistant.internal.domain.AssistantAction.Status.SUGGESTED, NOW, NOW,
                                null, null, null, null, null, null))));
        assertThat(suggesting.transcript(CONVERSATION).get(1).actions())
                .containsOnlyKeys("PURCHASE_ORDER_DRAFT:SUP-A")
                .satisfies(made -> assertThat(made.get("PURCHASE_ORDER_DRAFT:SUP-A").id()).isEqualTo(suggestion));
    }

    @Test
    @DisplayName("an answer that proposes nothing suggests nothing")
    void noProposalNoSuggestion() {
        AssistantActions actions = org.mockito.Mockito.mock(AssistantActions.class);
        ConversationService suggesting = new ConversationService(store, verdicts, Clock.fixed(NOW, ZoneOffset.UTC),
                json, actions);
        when(store.appendAndRelease(org.mockito.ArgumentMatchers.eq(CONVERSATION), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(model(1, "Xin chào.", LanguageModel.StopReason.END_TURN)));

        suggesting.appendAndRelease(CONVERSATION, UUID.randomUUID(), NOW, List.of());

        org.mockito.Mockito.verifyNoInteractions(actions);
    }

    @Test
    @DisplayName("the live answer and the reopened transcript read an exchange the same way: tools, citations, "
            + "tables, unverified numbers and follow-ups alike, the earlier exchanges included")
    void liveAndReopenedAgree() {
        ToolExecution stock = new ToolExecution(new ToolCall("toolu_0", "getStockLevels", "{}"), ToolOutcome.OK,
                "{\"sku\":\"EVAL-BOX-A4\",\"warehouses\":[{\"warehouseCode\":\"HCM\",\"onHand\":75}]}", 2);
        ToolExecution search = new ToolExecution(new ToolCall("toolu_1", "searchKnowledge", "{}"), ToolOutcome.OK,
                "{\"passages\":[{\"anchor\":\"BR-RMA-002\"}]}", 3);
        ToolExecution lots = new ToolExecution(new ToolCall("toolu_2", "findExpiringLots", "{}"), ToolOutcome.OK,
                "{\"expiringTotal\":1,\"expiring\":[{\"sku\":\"EVAL-LID-90\",\"lotNumber\":\"EVAL-LID-A\","
                        + "\"quantityAtLastCheck\":30}]}", 3);
        List<Message> earlier = List.of(question(0, "Tồn EVAL-BOX-A4?"),
                model(1, null, LanguageModel.StopReason.TOOL_USE, stock),
                results(2, stock),
                model(3, "Kho HCM còn 75.", LanguageModel.StopReason.END_TURN));
        Message asked = question(4, "Trả lại được không, lô nào sắp hết hạn?");
        List<Message> exchange = List.of(
                model(5, "Để tôi kiểm tra.", LanguageModel.StopReason.TOOL_USE, search, lots),
                results(6, search, lots),
                model(7, "Không (BR-RMA-002). Lô EVAL-LID-A còn 30, HCM vẫn 75, thêm 64 nữa.",
                        LanguageModel.StopReason.END_TURN));
        List<Message> conversation = new ArrayList<>(earlier);
        conversation.add(asked);
        conversation.addAll(exchange);
        when(store.messagesInScope(CONVERSATION)).thenReturn(conversation);

        AssistantService.Answer live = AssistantService.Answer.of(
                conversation.subList(0, 5).stream().map(Message::turn).toList(), exchange, json);
        ConversationService.TranscriptEntry reopened = service.transcript(CONVERSATION).getLast();

        assertThat(reopened.messageId()).isEqualTo(live.messageId());
        assertThat(reopened.text()).isEqualTo(live.text());
        assertThat(reopened.toolCalls()).isEqualTo(live.toolCalls());
        assertThat(reopened.citations()).isEqualTo(live.citations());
        assertThat(reopened.cutShort()).isEqualTo(live.cutShort());
        assertThat(reopened.tables()).isEqualTo(live.tables()).extracting(ResultTable.Table::key)
                .containsExactly("findExpiringLots.expiring");
        assertThat(reopened.unverifiedNumbers()).isEqualTo(live.unverifiedNumbers())
                .as("75 was looked up in the earlier exchange; 64 nowhere").containsExactly("64");
        assertThat(reopened.followUps()).isEqualTo(live.followUps())
                .containsExactly(new FollowUps.FollowUp(FollowUps.Kind.SKU_LOTS, "EVAL-LID-90"));
    }

    @Test
    @DisplayName("each answer carries its owner's verdict; a question carries none")
    void verdicts() {
        List<Message> messages = List.of(question(0, "Trả lại được không?"),
                model(1, "Không.", LanguageModel.StopReason.END_TURN));
        when(store.messagesInScope(CONVERSATION)).thenReturn(messages);
        when(verdicts.verdictsOf(List.of(messages.get(1).id())))
                .thenReturn(Map.of(messages.get(1).id(), FeedbackVerdict.DOWN));

        List<ConversationService.TranscriptEntry> transcript = service.transcript(CONVERSATION);

        assertThat(transcript).extracting(ConversationService.TranscriptEntry::feedback)
                .containsExactly(null, FeedbackVerdict.DOWN);
    }

    @Test
    @DisplayName("a verdict is recorded only on an answer of the caller's own conversation; anything else is not "
            + "found")
    void rateAnswer() {
        CurrentUser owner = new CurrentUser(UUID.randomUUID(), "owner", Set.of(Role.INVENTORY_PLANNER), Set.of(),
                DataScope.ALL, Set.of());
        UUID answer = UUID.randomUUID();
        AnswerFeedback down = new AnswerFeedback(FeedbackVerdict.DOWN, "  Sai số tồn  ");
        when(verdicts.record(CONVERSATION, answer, owner.userId(), down)).thenReturn(true);

        service.rateAnswer(CONVERSATION, answer, owner, down);

        verify(verdicts).record(CONVERSATION, answer, owner.userId(), new AnswerFeedback(FeedbackVerdict.DOWN,
                "Sai số tồn"));
        UUID question = UUID.randomUUID();
        assertThatThrownBy(() -> service.rateAnswer(CONVERSATION, question, owner, down))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("Answer");
        UUID someoneElses = UUID.randomUUID();
        when(store.findInScope(someoneElses)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rateAnswer(someoneElses, answer, owner, down))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("Conversation");
        verify(verdicts, never()).record(eq(someoneElses), any(), any(), any());
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

    /** What the model was sent back: the results of {@code executions}, as the loop stores them. */
    private static Message results(int sequence, ToolExecution... executions) {
        return new Message(UUID.randomUUID(), sequence, MessageRole.TOOL_RESULTS, null,
                new Turn.ToolResults(Arrays.stream(executions).map(ToolExecution::toResult).toList()),
                null, null, LanguageModel.Usage.NONE, List.of(), NOW);
    }

    private static Message closing(int sequence, String text) {
        return new Message(UUID.randomUUID(), sequence, MessageRole.ASSISTANT, text,
                new Turn.ModelTurn("{\"closing\":true}", text, List.of()), PendingMessage.CLOSING_MODEL,
                LanguageModel.StopReason.OTHER, LanguageModel.Usage.NONE, List.of(), NOW);
    }
}
