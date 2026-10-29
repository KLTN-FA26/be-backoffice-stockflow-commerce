package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Message;
import com.stockflow.assistant.internal.domain.MessageRole;
import com.stockflow.assistant.internal.domain.PendingMessage;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
 * The agent loop against a scripted model (design §3.1, §5.4 {@code AssistantLoopTest}): tool rounds, the last call
 * that offers no tool, replies whose tool calls must not run, empty replies, provider and storage failures, and what
 * is stored in each case.
 */
class AssistantServiceTest {

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-25T02:00:00Z");
    private static final CurrentUser MANAGER = new CurrentUser(UUID.randomUUID(), "minh.vo",
            Set.of(Role.WAREHOUSE_MANAGER), Set.of(PermissionCode.of(ToolPermissions.INVENTORY_STOCK_ITEMS,
            Action.READ)), DataScope.WAREHOUSE, Set.of("HCM"));

    private final ConversationService conversations = mock(ConversationService.class);
    private final ScriptedModel model = new ScriptedModel();
    private final List<PendingMessage> stored = new ArrayList<>();
    private final AtomicInteger toolRuns = new AtomicInteger();
    private int maxToolRounds = 6;
    private Duration lease = Duration.ofMinutes(30);
    private final List<Turn> earlier = new ArrayList<>();
    private final MovingClock clock = new MovingClock(NOW);

    @BeforeEach
    void conversationStore() {
        when(conversations.questionsSince(any(), any())).thenReturn(0L);
        when(conversations.claimAndAsk(eq(CONVERSATION), any(), any(), any())).thenAnswer(call -> {
            List<Turn> history = new ArrayList<>(earlier);
            history.add(((PendingMessage) call.getArgument(1)).turn());
            return history;
        });
        when(conversations.appendAndRelease(eq(CONVERSATION), any(), anyList())).thenAnswer(call -> {
            List<PendingMessage> produced = call.getArgument(2);
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
            @RequiresPermission(resource = ToolPermissions.INVENTORY_STOCK_ITEMS, action = Action.READ)
            public Object run(ToolArguments arguments) {
                toolRuns.incrementAndGet();
                return new Stock(arguments.text("sku", 64), 40);
            }
        };
        AssistantProperties properties = new AssistantProperties(true, "key", "claude-opus-5-5", "low",
                maxToolRounds, 16000, 50, 90, Duration.ofSeconds(90), lease, true, "", "anthropic");
        AssistantTools tools = new AssistantTools(List.of(stock), new ObjectMapper());
        return new AssistantService(conversations, model, tools, new SystemPrompt(), properties,
                new Messages(new StaticMessageSource()), clock, new ObjectMapper(),
                new ToolAvailability(tools, conversations, properties, clock));
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
    @DisplayName("when the rounds run out, the last call offers no tool and says why, and its answer is kept with "
            + "every result already in")
    void lastCallOffersNoTool() {
        maxToolRounds = 1;
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "CUP-12OZ còn 40 cái; LID-90 tôi chưa kiểm tra được.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "So sánh hai mã", MANAGER);

        assertThat(answer.text()).isEqualTo("CUP-12OZ còn 40 cái; LID-90 tôi chưa kiểm tra được.");
        assertThat(answer.toolCalls()).extracting(ConversationService.ToolCallView::tool).containsExactly(
                "getStockLevels");
        assertThat(model.requests.getFirst().toolsAllowed()).isTrue();
        assertThat(model.requests.getFirst().note()).isNull();
        LanguageModel.Request last = model.requests.get(1);
        assertThat(last.toolsAllowed()).isFalse();
        assertThat(last.note()).contains("No more lookups", "say plainly what you could not check");
        assertThat(last.tools()).as("still declared: the history holds calls to them")
                .isEqualTo(model.requests.getFirst().tools());
        assertThat(last.history().getLast()).isInstanceOf(Turn.ToolResults.class);
        assertThat(stored).extracting(PendingMessage::role)
                .containsExactly(MessageRole.ASSISTANT, MessageRole.TOOL_RESULTS, MessageRole.ASSISTANT);
    }

    @Test
    @DisplayName("if the last call still asks for a tool, the call is answered NOT_RUN and the answer closes after "
            + "what the model wrote")
    void roundLimit() {
        maxToolRounds = 1;
        ToolCall call = new ToolCall("toolu_1", "getStockLevels", "{\"sku\":\"CUP-12OZ\"}");
        model.then(LanguageModel.StopReason.TOOL_USE, null, call);
        model.then(LanguageModel.StopReason.TOOL_USE, "CUP-12OZ còn 40 cái.", new ToolCall("toolu_2",
                "getStockLevels", "{\"sku\":\"LID-90\"}"));

        AssistantService.Answer answer = service().ask(CONVERSATION, "So sánh hai mã", MANAGER);

        assertThat(toolRuns).hasValue(1);
        assertThat(model.requests.getLast().toolsAllowed()).isFalse();
        assertThat(stored).extracting(PendingMessage::role).containsExactly(MessageRole.ASSISTANT,
                MessageRole.TOOL_RESULTS, MessageRole.ASSISTANT, MessageRole.TOOL_RESULTS, MessageRole.ASSISTANT);
        Turn.ToolResults notRun = (Turn.ToolResults) stored.get(3).turn();
        assertThat(notRun.results().getFirst().content()).startsWith("NOT_RUN");
        assertThat(notRun.results().getFirst().error()).isTrue();
        assertThat(stored.getLast().model()).isEqualTo(PendingMessage.CLOSING_MODEL);
        assertThat(answer.text()).startsWith("CUP-12OZ còn 40 cái.\n\n").contains("narrower question");
        assertThat(answer.toolCalls()).hasSize(1);
    }

    @Test
    @DisplayName("an empty reply after tool results is asked again once, with a note, as the same request and not "
            + "as a tool round; it is stored for the record but never sent back")
    void emptyReplyIsAskedAgain() {
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, null);
        model.then(LanguageModel.StopReason.END_TURN, "Còn 40 cái.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Còn bao nhiêu CUP-12OZ?", MANAGER);

        assertThat(answer.text()).isEqualTo("Còn 40 cái.");
        assertThat(toolRuns).hasValue(1);
        assertThat(model.requests).hasSize(3);
        assertThat(model.requests.get(2).history()).isEqualTo(model.requests.get(1).history());
        assertThat(model.requests.get(2).note()).contains("previous reply was empty");
        assertThat(model.requests.get(2).toolsAllowed()).isTrue();
        assertThat(stored).extracting(PendingMessage::role).containsExactly(MessageRole.ASSISTANT,
                MessageRole.TOOL_RESULTS, MessageRole.ASSISTANT, MessageRole.ASSISTANT);
        assertThat(stored.get(2).text()).isNull();
        assertThat(answer.usage().inputTokens()).as("every call is paid for, the empty one too").isEqualTo(300);
    }

    @Test
    @DisplayName("a second empty reply closes with a message that the assistant could not answer, never one that "
            + "reads as 'nothing was found'")
    void emptyTwice() {
        model.then(LanguageModel.StopReason.OTHER, null);
        model.then(LanguageModel.StopReason.END_TURN, "  ");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Lô nào sắp hết hạn?", MANAGER);

        assertThat(model.requests).hasSize(2);
        assertThat(answer.text()).isEqualTo("The assistant did not manage to write an answer this time. "
                + "Please ask again.");
        assertThat(stored.getLast().model()).isEqualTo(PendingMessage.CLOSING_MODEL);
        assertThat(stored).extracting(PendingMessage::role)
                .containsExactly(MessageRole.ASSISTANT, MessageRole.ASSISTANT, MessageRole.ASSISTANT);
    }

    @Test
    @DisplayName("a cut-off reply with no text says it was cut off, and is not asked again")
    void cutOffWithoutText() {
        model.then(LanguageModel.StopReason.MAX_TOKENS, null);

        AssistantService.Answer answer = service().ask(CONVERSATION, "Liệt kê tất cả", MANAGER);

        assertThat(model.requests).hasSize(1);
        assertThat(answer.cutShort()).isTrue();
        assertThat(answer.text()).isEqualTo("The answer was too long to finish. Please ask a narrower question.");
    }

    @Test
    @DisplayName("a call to a tool that does not exist is stored under a fixed name, its result naming what the "
            + "model asked for")
    void unknownToolName() {
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("call_1", "default_api.getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "Còn 40 cái.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER);

        assertThat(toolRuns).hasValue(0);
        assertThat(stored.getFirst().executions()).singleElement().satisfies(execution -> {
            assertThat(execution.call().name()).isEqualTo(AssistantService.UNKNOWN_TOOL);
            assertThat(execution.call().id()).isEqualTo("call_1");
            assertThat(execution.outcome()).isEqualTo(ToolOutcome.INVALID);
            assertThat(execution.result()).contains("default_api.getStockLevels");
        });
        assertThat(((Turn.ModelTurn) stored.getFirst().turn()).toolCalls().getFirst().name())
                .as("the model's own turn is replayed as it came").isEqualTo("default_api.getStockLevels");
        assertThat(answer.toolCalls()).extracting(ConversationService.ToolCallView::tool)
                .containsExactly(AssistantService.UNKNOWN_TOOL);
    }

    @Test
    @DisplayName("a line the model wrote beside its tool call is not the answer: the answer after it carries the call")
    void interimTextIsNotTheAnswer() {
        model.then(LanguageModel.StopReason.TOOL_USE, "Để tôi kiểm tra tồn kho.", new ToolCall("toolu_1",
                "getStockLevels", "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "Còn 40 cái.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER);

        assertThat(answer.text()).isEqualTo("Còn 40 cái.");
        assertThat(answer.toolCalls()).extracting(ConversationService.ToolCallView::tool)
                .containsExactly("getStockLevels");
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
        assertThat(model.requests).as("a declined reply is not asked again").hasSize(1);
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
    @DisplayName("when the provider fails halfway, the work so far is stored before the error goes back, closed by "
            + "the error's message")
    void providerFailureKeepsPartialWork() {
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.failNext();

        assertThatThrownBy(() -> service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThat(stored).extracting(PendingMessage::role)
                .containsExactly(MessageRole.ASSISTANT, MessageRole.TOOL_RESULTS, MessageRole.ASSISTANT);
        assertThat(stored.getFirst().executions()).hasSize(1);
        assertThat(stored.getLast().model()).isEqualTo(PendingMessage.CLOSING_MODEL);
        assertThat(stored.getLast().text()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE.defaultMessage());
    }

    @Test
    @DisplayName("a question the provider refuses before any reply is closed by the error's message right after it: "
            + "that is what keeps it out of the daily cap")
    void providerFailureBeforeAnyReply() {
        model.failNext();

        assertThatThrownBy(() -> service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThat(stored).singleElement().satisfies(closing -> {
            assertThat(closing.model()).isEqualTo(PendingMessage.CLOSING_MODEL);
            assertThat(closing.text()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE.defaultMessage())
                    .doesNotContain("provider down");
        });
    }

    @Test
    @DisplayName("a failure that is not the provider's is closed with the internal error's message, never its own")
    void unexpectedFailure() {
        model.fail(new IllegalStateException("/srv/app/secret.yml is unreadable"));

        assertThatThrownBy(() -> service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stored).singleElement().satisfies(closing -> assertThat(closing.text())
                .isEqualTo(ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    @Test
    @DisplayName("if the answer cannot be stored, the claim is released on its own, so the conversation is not "
            + "left busy for the whole lease")
    void storeFailureReleasesTheClaim() {
        model.then(LanguageModel.StopReason.END_TURN, "ok");
        when(conversations.appendAndRelease(eq(CONVERSATION), any(), anyList()))
                .thenThrow(new IllegalStateException("the database is gone"));

        assertThatThrownBy(() -> service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER))
                .isInstanceOf(IllegalStateException.class).hasMessage("the database is gone");
        verify(conversations).release(CONVERSATION, NOW);
    }

    @Test
    @DisplayName("a provider failure whose work cannot be stored either still releases the claim, and keeps both "
            + "errors")
    void failureAndStoreFailure() {
        model.failNext();
        when(conversations.appendAndRelease(eq(CONVERSATION), any(), anyList()))
                .thenThrow(new IllegalStateException("the database is gone"));

        assertThatThrownBy(() -> service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
                    assertThat(e.getSuppressed()).singleElement().satisfies(suppressed ->
                            assertThat(suppressed).hasMessage("the database is gone"));
                });
        verify(conversations).release(CONVERSATION, NOW);
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
        verify(conversations, never()).claimAndAsk(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the question carries today's Vietnam date and a calendar, roles and warehouses, the tools the user "
            + "may use, and never the user's name")
    void questionContext() {
        model.then(LanguageModel.StopReason.END_TURN, "ok");

        service().ask(CONVERSATION, "Hôm nay thế nào?", MANAGER);

        String context = ((Turn.Question) model.requests.getFirst().history().getFirst()).context();
        assertThat(context).contains("2026-10-25", "Sunday", "WAREHOUSE_MANAGER", "WAREHOUSE", "HCM")
                .contains(" Calendar (Vietnam time): today Sunday 2026-10-25, now 09:00; yesterday 2026-10-24; "
                        + "this week 2026-10-19 to 2026-10-25 so far; last week 2026-10-12 to 2026-10-18; "
                        + "this month 2026-10-01 to 2026-10-25 so far; last month 2026-09-01 to 2026-09-30.")
                .contains(" Tools this user may use: getStockLevels. Every other tool refuses them.")
                .endsWith(" They are limited to warehouses HCM: they see no other warehouse and no company-wide "
                        + "stock figure.")
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

    @Test
    @DisplayName("one step runs at most eight tools; the rest are answered NOT_RUN, every call still answered")
    void callsPerStep() {
        ToolCall[] calls = new ToolCall[10];
        for (int i = 0; i < calls.length; i++) {
            calls[i] = new ToolCall("toolu_" + i, "getStockLevels", "{\"sku\":\"SKU-" + i + "\"}");
        }
        model.then(LanguageModel.StopReason.TOOL_USE, null, calls);
        model.then(LanguageModel.StopReason.END_TURN, "ok");

        service().ask(CONVERSATION, "Tất cả mã", MANAGER);

        assertThat(toolRuns).hasValue(AssistantService.MAX_CALLS_PER_ROUND);
        Turn.ToolResults results = (Turn.ToolResults) stored.get(1).turn();
        assertThat(results.results()).hasSize(10);
        assertThat(results.results().subList(8, 10)).allSatisfy(result -> {
            assertThat(result.content()).startsWith("NOT_RUN");
            assertThat(result.error()).isTrue();
        });
        assertThat(stored.getFirst().executions()).hasSize(AssistantService.MAX_CALLS_PER_ROUND);
    }

    @Test
    @DisplayName("the answer shown loses links, images and URLs: nothing in it is fetched when it is rendered")
    void plainAnswer() {
        model.then(LanguageModel.StopReason.END_TURN,
                "Doanh thu ![x](https://evil.example/p?d=123) theo [báo cáo](http://evil.example) https://a.b/c");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Doanh thu?", MANAGER);

        assertThat(answer.text()).isEqualTo("Doanh thu x theo báo cáo \\[link removed\\]")
                .doesNotContain("evil.example", "http");
    }

    @Test
    @DisplayName("an earlier question goes back to the model with its answer only, never its tool results")
    void earlierQuestionsWithoutToolResults() {
        earlier.add(new Turn.Question("Còn bao nhiêu CUP-12OZ?", "ctx"));
        earlier.add(new Turn.ModelTurn("{\"r\":1}", null,
                List.of(new ToolCall("toolu_0", "getStockLevels", "{\"sku\":\"CUP-12OZ\"}"))));
        earlier.add(new Turn.ToolResults(List.of(new ToolResult("toolu_0", "{\"onHand\":40}", false))));
        earlier.add(new Turn.ModelTurn("{\"r\":2}", "Còn 40 cái.", List.of()));
        model.then(LanguageModel.StopReason.END_TURN, "ok");

        service().ask(CONVERSATION, "Còn LID-90 không?", MANAGER);

        List<Turn> sent = model.requests.getFirst().history();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(1)).isInstanceOfSatisfying(Turn.ModelTurn.class, turn -> {
            assertThat(turn.text()).isEqualTo("Còn 40 cái.");
            assertThat(turn.toolCalls()).isEmpty();
        });
        assertThat(sent).noneMatch(Turn.ToolResults.class::isInstance);
    }

    @Test
    @DisplayName("no model call starts that could outlast the lease: the answer closes instead")
    void stopsBeforeTheLeaseEnds() {
        lease = Duration.ofMinutes(9);
        model.onCall = () -> clock.advance(Duration.ofMinutes(5));
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "never asked");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER);

        // 5 minutes in, a call of up to 4.5 minutes (3 attempts of 90 s) would end past the 9-minute lease.
        assertThat(model.requests).hasSize(1);
        assertThat(stored.getLast().model()).isEqualTo(PendingMessage.CLOSING_MODEL);
        assertThat(answer.text()).contains("took too long");
        assertThat(answer.toolCalls()).as("the lookup that ran stays with the answer").hasSize(1);
    }

    @Test
    @DisplayName("when the lease has room for one more call only, that call offers no tool: the model answers from "
            + "what it has instead of asking for a lookup there is no time to follow")
    void leaseLeavesRoomForTheLastCall() {
        lease = Duration.ofMinutes(9);
        model.onCall = () -> clock.advance(Duration.ofMinutes(1));
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "Còn 40 cái.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER);

        // 1 minute in: one 4.5-minute call still fits the 9-minute lease, two would not.
        assertThat(model.requests.getFirst().toolsAllowed()).as("the first call always may").isTrue();
        assertThat(model.requests.get(1).toolsAllowed()).isFalse();
        assertThat(model.requests.get(1).note()).contains("No more lookups");
        assertThat(answer.text()).isEqualTo("Còn 40 cái.");
    }

    /** A clock that stands still until the test moves it. */
    private static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A language model that plays back a script and records what it was asked. */
    private static final class ScriptedModel implements LanguageModel {

        final List<Request> requests = new ArrayList<>();
        final Deque<Object> script = new ArrayDeque<>();
        final Turn.ModelTurn closing = new Turn.ModelTurn("{\"closing\":true}", "closing", List.of());
        boolean available = true;
        Runnable onCall = () -> { };

        void then(StopReason stop, String text, ToolCall... calls) {
            script.add(new Reply(new Turn.ModelTurn("{\"reply\":" + script.size() + "}", text, List.of(calls)), stop,
                    new Usage(100, 10, 0, 0), "claude-opus-5-5"));
        }

        void failNext() {
            fail(new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "provider down"));
        }

        void fail(RuntimeException failure) {
            script.add(failure);
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Reply next(Request request) {
            requests.add(request);
            onCall.run();
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
