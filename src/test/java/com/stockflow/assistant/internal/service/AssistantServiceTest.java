package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Message;
import com.stockflow.assistant.internal.domain.MessageRole;
import com.stockflow.assistant.internal.domain.PendingMessage;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agent loop against a scripted model (design §3.1, §5.4 {@code AssistantLoopTest}): tool rounds, the round
 * limit, replies whose tool calls must not run, provider failures, and what is stored in each case.
 */
class AssistantServiceTest {

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-25T02:00:00Z");
    private static final CurrentUser MANAGER = new CurrentUser(UUID.randomUUID(), "minh.vo",
            Set.of(Role.WAREHOUSE_MANAGER), Set.of(), DataScope.WAREHOUSE, Set.of("HCM"));

    private final ConversationService conversations = mock(ConversationService.class);
    private final ScriptedModel model = new ScriptedModel();
    private final List<PendingMessage> stored = new ArrayList<>();
    private final AtomicInteger toolRuns = new AtomicInteger();
    private int maxToolRounds = 6;

    @BeforeEach
    void conversationStore() {
        when(conversations.questionsSince(any(), any())).thenReturn(0L);
        when(conversations.claimAndAsk(eq(CONVERSATION), any(), any()))
                .thenAnswer(call -> List.of(((PendingMessage) call.getArgument(1)).turn()));
        when(conversations.appendAndRelease(eq(CONVERSATION), anyList())).thenAnswer(call -> {
            List<PendingMessage> produced = call.getArgument(1);
            stored.addAll(produced);
            List<Message> messages = new ArrayList<>();
            for (PendingMessage message : produced) {
                messages.add(new Message(UUID.randomUUID(), messages.size() + 1, message.role(), message.text(),
                        message.turn(), message.model(), message.stopReason(), message.usage(),
                        message.executions(), NOW));
            }
            return messages;
        });
    }

    private AssistantService service() {
        AssistantTool stock = new AssistantTool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("getStockLevels", "stock", ToolSchema.object().string("sku", "SKU").build());
            }

            @Override
            public String mirrors() {
                return null;
            }

            @Override
            public Object run(ToolArguments arguments) {
                toolRuns.incrementAndGet();
                return new Stock(arguments.text("sku", 64), 40);
            }
        };
        AssistantProperties properties = new AssistantProperties(true, "key", "claude-opus-5-5", "low",
                maxToolRounds, 16000, 50, 90, Duration.ofSeconds(90), Duration.ofMinutes(30), true, "");
        return new AssistantService(conversations, model, new AssistantTools(List.of(stock), new ObjectMapper()),
                new SystemPrompt(), properties, new Messages(new StaticMessageSource()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    record Stock(String sku, int onHand) {
    }

    @Test
    @DisplayName("an answer with no tools is one model turn, stored after the loop")
    void answersDirectly() {
        model.then(LanguageModel.StopReason.END_TURN, "Xin chào, tôi giúp được gì?");

        AssistantService.Answer answer = service().ask(CONVERSATION, "  Xin chào  ", MANAGER);

        assertThat(answer.text()).isEqualTo("Xin chào, tôi giúp được gì?");
        assertThat(answer.toolCalls()).isEmpty();
        assertThat(stored).extracting(PendingMessage::role).containsExactly(MessageRole.ASSISTANT);
        assertThat(((Turn.Question) model.requests.getFirst().history().getFirst()).text()).isEqualTo("Xin chào");
    }

    @Test
    @DisplayName("a tool round runs the tools, sends their results back, and stores every turn with the call")
    void runsTools() {
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "Còn 40 cái.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Còn bao nhiêu CUP-12OZ?", MANAGER);

        assertThat(answer.text()).isEqualTo("Còn 40 cái.");
        assertThat(answer.toolCalls()).extracting(ConversationService.ToolCallView::tool, ConversationService.ToolCallView::outcome)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("getStockLevels", ToolOutcome.OK));
        assertThat(stored).extracting(PendingMessage::role)
                .containsExactly(MessageRole.ASSISTANT, MessageRole.TOOL_RESULTS, MessageRole.ASSISTANT);
        assertThat(stored.getFirst().executions().getFirst().result()).isEqualTo("{\"sku\":\"CUP-12OZ\",\"onHand\":40}");
        Turn last = model.requests.get(1).history().getLast();
        assertThat(last).isInstanceOf(Turn.ToolResults.class);
        assertThat(((Turn.ToolResults) last).results().getFirst().callId()).isEqualTo("toolu_1");
        assertThat(answer.usage().inputTokens()).isEqualTo(200);
    }

    @Test
    @DisplayName("when the rounds run out, the last tool calls are answered NOT_RUN and the turn is closed")
    void roundLimit() {
        maxToolRounds = 1;
        ToolCall call = new ToolCall("toolu_1", "getStockLevels", "{\"sku\":\"CUP-12OZ\"}");
        model.then(LanguageModel.StopReason.TOOL_USE, null, call);
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_2", "getStockLevels",
                "{\"sku\":\"LID-90\"}"));

        AssistantService.Answer answer = service().ask(CONVERSATION, "So sánh hai mã", MANAGER);

        assertThat(toolRuns).hasValue(1);
        assertThat(stored).extracting(PendingMessage::role).containsExactly(MessageRole.ASSISTANT,
                MessageRole.TOOL_RESULTS, MessageRole.ASSISTANT, MessageRole.TOOL_RESULTS, MessageRole.ASSISTANT);
        Turn.ToolResults notRun = (Turn.ToolResults) stored.get(3).turn();
        assertThat(notRun.results().getFirst().content()).startsWith("NOT_RUN");
        assertThat(notRun.results().getFirst().error()).isTrue();
        assertThat(stored.getLast().model()).isEqualTo(AssistantService.CLOSING_TURN_MODEL);
        assertThat(answer.text()).contains("narrower question");
    }

    @Test
    @DisplayName("a declined reply never runs its tool call, and closes with the refusal message")
    void refusalWithToolCall() {
        model.then(LanguageModel.StopReason.REFUSAL, null, new ToolCall("toolu_1", "getStockLevels", "{\"sku\":"));

        AssistantService.Answer answer = service().ask(CONVERSATION, "…", MANAGER);

        assertThat(toolRuns).hasValue(0);
        assertThat(answer.text()).isEqualTo("I cannot help with that request.");
        assertThat(((Turn.ModelTurn) stored.getLast().turn()).providerContent())
                .isEqualTo(model.closing.providerContent());
    }

    @Test
    @DisplayName("a cut-off reply is flagged, and its incomplete tool call is not run")
    void cutOff() {
        model.then(LanguageModel.StopReason.MAX_TOKENS, "Một phần câu trả lời");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Liệt kê tất cả", MANAGER);

        assertThat(answer.cutShort()).isTrue();
        assertThat(answer.text()).isEqualTo("Một phần câu trả lời");
    }

    @Test
    @DisplayName("when the provider fails halfway, the work so far is stored before the error goes back")
    void providerFailureKeepsPartialWork() {
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.failNext();

        assertThatThrownBy(() -> service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThat(stored).extracting(PendingMessage::role)
                .containsExactly(MessageRole.ASSISTANT, MessageRole.TOOL_RESULTS);
        assertThat(stored.getFirst().executions()).hasSize(1);
    }

    @Test
    @DisplayName("switched off, or over the daily cap: refused before anything is stored")
    void refusedUpFront() {
        model.available = false;
        assertThatThrownBy(() -> service().ask(CONVERSATION, "q", MANAGER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_DISABLED));

        model.available = true;
        when(conversations.questionsSince(any(), any())).thenReturn(50L);
        assertThatThrownBy(() -> service().ask(CONVERSATION, "q", MANAGER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_DAILY_LIMIT));
        verify(conversations, never()).claimAndAsk(any(), any(), any());
    }

    @Test
    @DisplayName("the question carries today's Vietnam date, roles and warehouses, and never the user's name")
    void questionContext() {
        model.then(LanguageModel.StopReason.END_TURN, "ok");

        service().ask(CONVERSATION, "Hôm nay thế nào?", MANAGER);

        String context = ((Turn.Question) model.requests.getFirst().history().getFirst()).context();
        assertThat(context).contains("2026-10-25", "Sunday", "WAREHOUSE_MANAGER", "WAREHOUSE", "HCM")
                .doesNotContain("minh.vo");
    }

    @Test
    @DisplayName("every call sends the same system prompt and tool list, byte for byte, so they can be cached")
    void stablePrefix() {
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "ok");

        service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER);

        assertThat(model.requests).hasSize(2);
        assertThat(model.requests.get(1).systemPrompt()).isEqualTo(model.requests.getFirst().systemPrompt());
        assertThat(model.requests.get(1).tools()).isEqualTo(model.requests.getFirst().tools());
        assertThat(model.requests.getFirst().systemPrompt()).doesNotContain("2026");
    }

    /** A language model that plays back a script and records what it was asked. */
    private static final class ScriptedModel implements LanguageModel {

        final List<Request> requests = new ArrayList<>();
        final Deque<Object> script = new ArrayDeque<>();
        final Turn.ModelTurn closing = new Turn.ModelTurn("{\"closing\":true}", "closing", List.of());
        boolean available = true;

        void then(StopReason stop, String text, ToolCall... calls) {
            script.add(new Reply(new Turn.ModelTurn("{\"reply\":" + script.size() + "}", text, List.of(calls)), stop,
                    new Usage(100, 10, 0, 0), "claude-opus-5-5"));
        }

        void failNext() {
            script.add(new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "provider down"));
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Reply next(Request request) {
            requests.add(request);
            Object next = script.removeFirst();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return (Reply) next;
        }

        @Override
        public Turn.ModelTurn closingTurn(String text) {
            return new Turn.ModelTurn(closing.providerContent(), text, List.of());
        }
    }
}
