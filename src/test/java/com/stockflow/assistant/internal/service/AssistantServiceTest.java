package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Message;
import com.stockflow.assistant.internal.domain.MessageRole;
import com.stockflow.assistant.internal.domain.PendingMessage;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agent loop against a scripted model (design §3.1, §5.4 {@code AssistantLoopTest}): tool rounds, the last call
 * that offers no tool, replies whose tool calls must not run, empty replies, provider and storage failures, and what
 * is stored in each case. Then the route over several models (design §11): a refused first call moved on, a spent
 * quota set aside, a question with no usable answer asked again on the next model, and what each model is sent.
 */
class AssistantServiceTest {

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-25T02:00:00Z");
    private static final CurrentUser MANAGER = new CurrentUser(UUID.randomUUID(), "minh.vo",
            Set.of(Role.WAREHOUSE_MANAGER), Set.of(PermissionCode.of(ToolPermissions.INVENTORY_STOCK_ITEMS,
            Action.READ)), DataScope.WAREHOUSE, Set.of("HCM"));

    private final ConversationService conversations = mock(ConversationService.class);
    private final ScriptedModel model = new ScriptedModel("claude-opus-5-5");
    private final ScriptedModel lite = new ScriptedModel("gemini-3.5-flash-lite");
    private final ScriptedModel flash = new ScriptedModel("gemini-3.5-flash");
    private final ScriptedModel pro = new ScriptedModel("gemini-3.5-pro");
    /** The models questions go to, cheapest first: the single {@link #model} unless a test routes. */
    private List<ScriptedModel> route = List.of();
    private boolean escalate = true;
    private final List<PendingMessage> stored = new ArrayList<>();
    private final AtomicInteger toolRuns = new AtomicInteger();
    /** What {@code getStockLevels} answers for a SKU. */
    private Function<String, Object> stockAnswer = sku -> new Stock(sku, 40);
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
        when(conversations.appendAndRelease(eq(CONVERSATION), any(), any(), anyList(), any())).thenAnswer(call -> {
            List<PendingMessage> produced = call.getArgument(3);
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
                return stockAnswer.apply(arguments.text("sku", 64));
            }
        };
        AssistantProperties properties = new AssistantProperties(true, "key", "claude-opus-5-5", "low",
                maxToolRounds, 16000, 50, 90, Duration.ofSeconds(90), lease, true, "", "anthropic");
        AssistantTools tools = new AssistantTools(List.of(stock), new ObjectMapper());
        List<ScriptedModel> models = route.isEmpty() ? List.of(model) : route;
        // Gemini's quota day: the routed tests are about its free tier, whose daily quotas restart at midnight Pacific.
        ModelRouter router = new ModelRouter(models.stream()
                .map(scripted -> new ModelRouter.Candidate(scripted.name, scripted)).toList(),
                new ModelRoutingProperties(models.stream().map(scripted -> scripted.name).toList(), escalate, null),
                ModelRouter.quotaDay("gemini"), clock);
        return new AssistantService(conversations, router, tools, new SystemPrompt(), properties,
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
        assertThat(model.requests).as("every number is a result's: nothing to correct").hasSize(2);
    }

    @Test
    @DisplayName("an answer stating a number no result holds is written again once, without tools: the rewrite is "
            + "the answer, the first stays on record")
    void unverifiedNumbersAreCorrected() {
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "Còn 40 cái, đủ cho 125 đơn.");
        model.then(LanguageModel.StopReason.END_TURN, "Còn 40 cái; số đơn đủ giao tôi chưa tra được.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Còn bao nhiêu CUP-12OZ?", MANAGER);

        assertThat(answer.text()).isEqualTo("Còn 40 cái; số đơn đủ giao tôi chưa tra được.");
        assertThat(answer.unverifiedNumbers()).isEmpty();
        assertThat(model.requests).hasSize(3);
        LanguageModel.Request repair = model.requests.get(2);
        assertThat(repair.toolsAllowed()).isFalse();
        assertThat(repair.note()).startsWith("Note from the system").contains(": 125.").doesNotContain("40");
        assertThat(repair.history().getLast()).as("the answer being corrected, then the note")
                .isInstanceOfSatisfying(Turn.ModelTurn.class,
                        turn -> assertThat(turn.text()).isEqualTo("Còn 40 cái, đủ cho 125 đơn."));
        assertThat(stored).extracting(PendingMessage::text).containsExactly(null, null,
                "Còn 40 cái, đủ cho 125 đơn.", "Còn 40 cái; số đơn đủ giao tôi chưa tra được.");
        assertThat(answer.usage().inputTokens()).as("both calls are paid for").isEqualTo(300);
    }

    @Test
    @DisplayName("a rewrite that states as many unheld numbers, asks for a tool or says nothing leaves the answer as it "
            + "was, flagged; the rewrite is kept for its tokens and never shown")
    void unusableCorrectionsAreSetAside() {
        lookUp(model);
        model.then(LanguageModel.StopReason.END_TURN, "Doanh thu hôm qua là 12.500.000 ₫.");
        model.then(LanguageModel.StopReason.END_TURN, "Doanh thu hôm qua khoảng 12,5 triệu ₫.");
        lookUp(model);
        model.then(LanguageModel.StopReason.END_TURN, "Còn 50 cái.");
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_9", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        lookUp(model);
        model.then(LanguageModel.StopReason.END_TURN, "Còn 50 cái.");
        model.then(LanguageModel.StopReason.MAX_TOKENS, "Còn");
        AssistantService service = service();

        AssistantService.Answer same = service.ask(CONVERSATION, "Doanh thu hôm qua?", MANAGER);
        AssistantService.Answer tool = service.ask(CONVERSATION, "Còn bao nhiêu?", MANAGER);
        AssistantService.Answer cut = service.ask(CONVERSATION, "Còn bao nhiêu?", MANAGER);

        assertThat(same.text()).isEqualTo("Doanh thu hôm qua là 12.500.000 ₫.");
        assertThat(same.unverifiedNumbers()).containsExactly("12.500.000");
        assertThat(tool.text()).isEqualTo("Còn 50 cái.");
        assertThat(toolRuns).as("no lookup runs while an answer is corrected: only the three asked for").hasValue(3);
        assertThat(cut.text()).isEqualTo("Còn 50 cái.");
        assertThat(cut.cutShort()).as("the answer shown was complete; the rewrite set aside was cut").isFalse();
        assertThat(stored).extracting(PendingMessage::text).containsExactly(
                null, null, "Doanh thu hôm qua là 12.500.000 ₫.", null,
                null, null, "Còn 50 cái.", null,
                null, null, "Còn 50 cái.", null);
        assertThat(List.of(stored.get(3), stored.get(7), stored.get(11))).as("set aside: no text, no tool call")
                .allSatisfy(audit -> assertThat((Turn.ModelTurn) audit.turn()).satisfies(turn -> {
                    assertThat(turn.text()).isNull();
                    assertThat(turn.toolCalls()).isEmpty();
                    assertThat(turn.providerContent()).as("kept for the audit").contains("\"reply\"");
                }));
        assertThat(stored).filteredOn(message -> message.role() == MessageRole.ASSISTANT)
                .extracting(PendingMessage::usage).allSatisfy(usage -> assertThat(usage.inputTokens()).isEqualTo(100));
    }

    @Test
    @DisplayName("a rewrite set aside is never sent back: the next question is sent the answer the user saw")
    void aRewriteSetAsideIsNotSentBack() {
        lookUp(model);
        model.then(LanguageModel.StopReason.END_TURN, "Doanh thu hôm qua là 12.500.000 ₫.");
        model.then(LanguageModel.StopReason.END_TURN, "Doanh thu hôm qua là 99.999.999 ₫, gồm đơn SO-1-777.");
        model.then(LanguageModel.StopReason.END_TURN, "Xin chào.");
        AssistantService service = service();

        service.ask(CONVERSATION, "Doanh thu hôm qua?", MANAGER);
        earlier.add(new Turn.Question("Doanh thu hôm qua?", "ctx"));
        stored.forEach(message -> earlier.add(message.turn()));
        service.ask(CONVERSATION, "Chào", MANAGER);

        List<Turn> sent = model.requests.getLast().history();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(1)).isInstanceOfSatisfying(Turn.ModelTurn.class,
                turn -> assertThat(turn.text()).isEqualTo("Doanh thu hôm qua là 12.500.000 ₫."));
    }

    @Test
    @DisplayName("a rewrite is the answer only if it loses nothing: one adding an unheld number, or dropping a grounded "
            + "one, leaves the answer as it was")
    void aRewriteMustLoseNothing() {
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "Còn 40 cái, đủ 125 đơn, giao 300 cái.");
        model.then(LanguageModel.StopReason.END_TURN, "Còn 40 cái, đủ 125 đơn, giao 250 cái.");
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_2", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "Còn 40 cái, đủ 125 đơn.");
        model.then(LanguageModel.StopReason.END_TURN, "Tôi chưa tra được số đơn.");
        AssistantService service = service();

        AssistantService.Answer added = service.ask(CONVERSATION, "Còn bao nhiêu CUP-12OZ?", MANAGER);
        AssistantService.Answer dropped = service.ask(CONVERSATION, "Còn bao nhiêu CUP-12OZ?", MANAGER);

        assertThat(added.text()).as("250 is a new unheld number").isEqualTo("Còn 40 cái, đủ 125 đơn, giao 300 cái.");
        assertThat(dropped.text()).as("the 40 that was looked up is gone").isEqualTo("Còn 40 cái, đủ 125 đơn.");
        assertThat(dropped.unverifiedNumbers()).containsExactly("125");
    }

    @Test
    @DisplayName("a number only a code holds is named as such, and a rewrite that writes the code whole is the answer")
    void aNumberInsideACodeIsWrittenWhole() {
        stockAnswer = sku -> java.util.Map.of("sku", sku, "lot", "EVAL-LOT-2401", "onHand", 40);
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "Lô 2401 còn 40 cái.");
        model.then(LanguageModel.StopReason.END_TURN, "Lô EVAL-LOT-2401 còn 40 cái.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Lô nào của CUP-12OZ?", MANAGER);

        assertThat(model.requests.get(2).note()).contains("only inside a code", ": 2401.")
                .doesNotContain("appear in no tool result");
        assertThat(answer.text()).isEqualTo("Lô EVAL-LOT-2401 còn 40 cái.");
        assertThat(answer.unverifiedNumbers()).isEmpty();
    }

    @Test
    @DisplayName("a rewrite call failing with anything, not only a provider error, leaves the answer as it was")
    void anyFailureOfTheRewriteKeepsTheAnswer() {
        lookUp(model);
        model.then(LanguageModel.StopReason.END_TURN, "Còn 50 cái.");
        model.fail(new IllegalStateException("malformed reply"));

        AssistantService.Answer answer = service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER);

        assertThat(answer.text()).isEqualTo("Còn 50 cái.");
        assertThat(stored).extracting(PendingMessage::text).containsExactly(null, null, "Còn 50 cái.");
    }

    @Test
    @DisplayName("a quota spent on the rewrite sets the model aside even on a route of models all set aside: the next "
            + "question can still move on")
    void aQuotaSpentOnTheRewriteIsRemembered() {
        route = List.of(lite, flash);
        lite.fail(quota());
        flash.fail(quota());
        AssistantService service = service();
        assertThatThrownBy(() -> service.ask(CONVERSATION, "Chào", MANAGER)).isInstanceOf(BusinessException.class);

        lookUp(lite);
        lite.then(LanguageModel.StopReason.END_TURN, "Còn 50 cái.");
        lite.fail(quota());
        assertThat(service.ask(CONVERSATION, "Còn bao nhiêu?", MANAGER).text()).isEqualTo("Còn 50 cái.");

        lite.fail(quota());
        flash.then(LanguageModel.StopReason.END_TURN, "Xin chào.");
        assertThat(service.ask(CONVERSATION, "Chào", MANAGER).text()).isEqualTo("Xin chào.");
    }

    @Test
    @DisplayName("a correction the provider fails leaves the answer as it was, and a spent quota still sets the model "
            + "aside for the next question")
    void failedCorrectionKeepsTheAnswer() {
        route = List.of(lite, flash);
        lookUp(lite);
        lite.then(LanguageModel.StopReason.END_TURN, "Còn 50 cái.");
        lite.fail(quota());
        flash.then(LanguageModel.StopReason.END_TURN, "Xin chào.");
        AssistantService service = service();

        assertThat(service.ask(CONVERSATION, "Còn bao nhiêu?", MANAGER).text()).isEqualTo("Còn 50 cái.");
        assertThat(stored).extracting(PendingMessage::text).containsExactly(null, null, "Còn 50 cái.");
        assertThat(service.ask(CONVERSATION, "Chào", MANAGER).text()).isEqualTo("Xin chào.");
        assertThat(lite.requests).as("set aside after the failed correction").hasSize(3);
    }

    @Test
    @DisplayName("an answer stating data with no lookup is set aside unseen, and the model is asked once, with the "
            + "tools, to look it up: what it then answers from the results is the answer")
    void anAnswerWithoutALookupIsLookedUp() {
        model.then(LanguageModel.StopReason.END_TURN, "Có 2 mã dưới điểm đặt hàng: CUP-A và LID-B.");
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "CUP-12OZ còn 40 cái.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Mã nào sắp hết?", MANAGER);

        assertThat(answer.text()).isEqualTo("CUP-12OZ còn 40 cái.");
        assertThat(answer.unverifiedCodes()).isEmpty();
        LanguageModel.Request asked = model.requests.get(1);
        assertThat(asked.toolsAllowed()).as("the lookup is the point: the tools are offered").isTrue();
        assertThat(asked.note()).startsWith("Note from the system").contains("2 mã", "CUP-A", "LID-B")
                .contains("call the ones that give it");
        assertThat(asked.history().getLast()).as("the withdrawn answer is not sent back for the model to defend")
                .isInstanceOf(Turn.Question.class);
        assertThat(stored).extracting(PendingMessage::text).containsExactly(null, null, null, "CUP-12OZ còn 40 cái.");
        assertThat((Turn.ModelTurn) stored.getFirst().turn()).as("set aside: kept for the audit, never shown")
                .satisfies(turn -> {
                    assertThat(turn.text()).isNull();
                    assertThat(turn.providerContent()).contains("\"reply\"");
                });
        assertThat(answer.usage().inputTokens()).as("every call is paid for").isEqualTo(300);
    }

    @Test
    @DisplayName("asked to look the data up, a model that answers again without a lookup is shown flagged when no "
            + "stronger model is left, and its question moves to the stronger one when there is")
    void anAnswerStillWithoutALookupIsEscalated() {
        model.then(LanguageModel.StopReason.END_TURN, "Có 2 mã: CUP-A và LID-B.");
        model.then(LanguageModel.StopReason.END_TURN, "Có 2 mã: CUP-A và LID-B, đã kiểm tra.");

        AssistantService.Answer alone = service().ask(CONVERSATION, "Mã nào sắp hết?", MANAGER);

        assertThat(alone.text()).isEqualTo("Có 2 mã: CUP-A và LID-B, đã kiểm tra.");
        assertThat(alone.unverifiedCodes()).containsExactly("CUP-A", "LID-B");
        assertThat(alone.unverifiedNumbers()).containsExactly("2 mã");

        stored.clear();
        route = List.of(lite, flash);
        lite.then(LanguageModel.StopReason.END_TURN, "Có 2 mã: CUP-A và LID-B.");
        lite.then(LanguageModel.StopReason.END_TURN, "Có 2 mã: CUP-A và LID-B.");
        flash.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("call_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        flash.then(LanguageModel.StopReason.END_TURN, "CUP-12OZ còn 40 cái.");

        AssistantService.Answer escalated = service().ask(CONVERSATION, "Mã nào sắp hết?", MANAGER);

        assertThat(escalated.text()).isEqualTo("CUP-12OZ còn 40 cái.");
        assertThat(flash.requests.getFirst().history().getLast()).as("the stronger model starts from the question")
                .isInstanceOf(Turn.Question.class);
    }

    @Test
    @DisplayName("a lookup call that fails leaves the answer set aside standing, flagged, as it would have without "
            + "the call")
    void aFailedLookupKeepsTheAnswer() {
        model.then(LanguageModel.StopReason.END_TURN, "Có 2 mã: CUP-A và LID-B.");
        model.fail(new IllegalStateException("malformed reply"));

        AssistantService.Answer answer = service().ask(CONVERSATION, "Mã nào sắp hết?", MANAGER);

        assertThat(answer.text()).isEqualTo("Có 2 mã: CUP-A và LID-B.");
        assertThat(answer.unverifiedCodes()).containsExactly("CUP-A", "LID-B");
        assertThat(stored).extracting(PendingMessage::text).containsExactly(null, "Có 2 mã: CUP-A và LID-B.");
        assertThat(answer.usage().inputTokens()).as("the answer's call is counted once").isEqualTo(100);
    }

    @Test
    @DisplayName("an answer with no lookup that states only what the question holds, or nothing to check, is the answer")
    void anAnswerWithoutALookupThatHoldsIsKept() {
        model.then(LanguageModel.StopReason.END_TURN, "Mã CUP-12OZ: bạn muốn xem tồn kho hay lô?");

        AssistantService.Answer answer = service().ask(CONVERSATION, "CUP-12OZ", MANAGER);

        assertThat(answer.text()).isEqualTo("Mã CUP-12OZ: bạn muốn xem tồn kho hay lô?");
        assertThat(model.requests).hasSize(1);
    }

    @Test
    @DisplayName("an answer that copies the prompt's example, placeholders and all, is asked to look the data up")
    void aCopiedExampleIsLookedUp() {
        model.then(LanguageModel.StopReason.END_TURN, "Có «n» mã dưới điểm đặt hàng: «SKU 1» và «SKU 2».");
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "CUP-12OZ còn 40 cái.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Mã nào sắp hết?", MANAGER);

        assertThat(answer.text()).isEqualTo("CUP-12OZ còn 40 cái.");
        assertThat(model.requests.get(1).note()).contains("«SKU 1»");
    }

    @Test
    @DisplayName("a refused call looked nothing up: data stated after it is still to be looked up")
    void aRefusedCallIsNoLookup() {
        stockAnswer = sku -> {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Missing permission: inventory-stock-items:READ");
        };
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-A\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "Có 2 mã: CUP-A và LID-B.");
        model.then(LanguageModel.StopReason.END_TURN, "Chưa xem được tồn kho: tài khoản thiếu quyền.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Mã nào sắp hết?", MANAGER);

        assertThat(answer.text()).isEqualTo("Chưa xem được tồn kho: tài khoản thiếu quyền.");
        assertThat(model.requests.get(2).note()).as("the refused call's line grounds no code")
                .contains("CUP-A", "LID-B").contains("call the ones that give it");
        assertThat(model.requests.get(2).toolsAllowed()).isTrue();
    }

    @Test
    @DisplayName("a lookup call that fails shows the answer set aside and lets the stronger model try")
    void aFailedLookupIsEscalated() {
        route = List.of(lite, flash);
        lite.then(LanguageModel.StopReason.END_TURN, "Có 2 mã: CUP-A và LID-B.");
        lite.fail(quota());
        flash.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("call_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        flash.then(LanguageModel.StopReason.END_TURN, "CUP-12OZ còn 40 cái.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Mã nào sắp hết?", MANAGER);

        assertThat(answer.text()).isEqualTo("CUP-12OZ còn 40 cái.");
        assertThat(stored).extracting(PendingMessage::text).contains("Có 2 mã: CUP-A và LID-B.");
    }

    @Test
    @DisplayName("a reply to the lookup that is cut off leaves the complete answer set aside standing, flagged")
    void aCutLookupKeepsTheAnswer() {
        model.then(LanguageModel.StopReason.END_TURN, "Có 2 mã: CUP-A và LID-B.");
        model.then(LanguageModel.StopReason.MAX_TOKENS, "Có");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Mã nào sắp hết?", MANAGER);

        assertThat(answer.text()).isEqualTo("Có 2 mã: CUP-A và LID-B.");
        assertThat(answer.cutShort()).isFalse();
        assertThat(answer.unverifiedCodes()).containsExactly("CUP-A", "LID-B");
    }

    /** A first step that looks something up, so the answer after it is a looked-up one ({@code repairNote}). */
    private static void lookUp(ScriptedModel scripted) {
        scripted.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_" + scripted.script.size(),
                "getStockLevels", "{\"sku\":\"CUP-12OZ\"}"));
    }

    @Test
    @DisplayName("no correction is asked for when the lease has no room left for the call")
    void noCorrectionWithoutTime() {
        lease = Duration.ofMinutes(12);
        model.onCall = () -> clock.advance(Duration.ofMinutes(8));
        model.then(LanguageModel.StopReason.END_TURN, "Còn 40 cái.");

        assertThat(service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER).text()).isEqualTo("Còn 40 cái.");
        assertThat(model.requests).hasSize(1);
    }

    @Test
    @DisplayName("a step's tools run at once; their results go back in the order the model asked for them, and each is "
            + "reported as it finishes, on the thread that runs the loop")
    void stepToolsRunTogether() {
        CountDownLatch secondDone = new CountDownLatch(1);
        stockAnswer = sku -> {
            if (sku.equals("CUP-12OZ")) {
                try {
                    // Run one after the other, this would wait for a call that has not started.
                    if (!secondDone.await(10, TimeUnit.SECONDS)) {
                        return new Stock("TIMED-OUT", 0);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new Stock(sku, 40);
            }
            secondDone.countDown();
            return new Stock(sku, 12);
        };
        model.then(LanguageModel.StopReason.TOOL_USE, null,
                new ToolCall("toolu_1", "getStockLevels", "{\"sku\":\"CUP-12OZ\"}"),
                new ToolCall("toolu_2", "getStockLevels", "{\"sku\":\"LID-90\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "CUP-12OZ còn 40 cái, LID-90 còn 12 cái.");
        List<String> reported = new CopyOnWriteArrayList<>();
        Thread loop = Thread.currentThread();

        AssistantService.Answer answer = service().askAsOwner(CONVERSATION, "So sánh CUP-12OZ với LID-90", MANAGER,
                call -> reported.add(call.tool() + (Thread.currentThread() == loop ? "@loop" : "@worker")));

        assertThat(((Turn.ToolResults) model.requests.get(1).history().getLast()).results())
                .extracting(ToolResult::callId).containsExactly("toolu_1", "toolu_2");
        assertThat(stored.getFirst().executions()).extracting(ToolExecution::result).containsExactly(
                "{\"sku\":\"CUP-12OZ\",\"onHand\":40}", "{\"sku\":\"LID-90\",\"onHand\":12}");
        assertThat(reported).containsExactly("getStockLevels@loop", "getStockLevels@loop");
        assertThat(answer.unverifiedNumbers()).isEmpty();
    }

    @Test
    @DisplayName("a step runs only as many tools at once as the room left for results can hold at their longest: what "
            + "ran was sent, what was not sent did not run")
    void theBudgetBoundsEachWave() {
        String pad = "x".repeat(19_950);
        stockAnswer = sku -> java.util.Map.of("sku", sku, "pad", pad);
        ToolCall[] calls = new ToolCall[9];
        for (int i = 0; i < calls.length; i++) {
            calls[i] = new ToolCall("toolu_" + (i + 1), "getStockLevels", "{\"sku\":\"S-" + (i + 1) + "\"}");
        }
        model.then(LanguageModel.StopReason.TOOL_USE, null, calls);
        model.then(LanguageModel.StopReason.END_TURN, "Tôi đã tra được một phần.");

        service().ask(CONVERSATION, "Tra hết", MANAGER);

        // 100 000 characters hold five results of up to 20 000; one more runs when a little room is left, as before.
        assertThat(toolRuns).hasValue(6);
        List<ToolResult> results = ((Turn.ToolResults) model.requests.get(1).history().getLast()).results();
        assertThat(results).extracting(ToolResult::callId).containsExactly("toolu_1", "toolu_2", "toolu_3",
                "toolu_4", "toolu_5", "toolu_6", "toolu_7", "toolu_8", "toolu_9");
        assertThat(results.subList(0, 6)).allSatisfy(result -> assertThat(result.error()).isFalse());
        assertThat(results.subList(6, 8)).allSatisfy(result ->
                assertThat(result.content()).startsWith("NOT_RUN: this question has used up its room"));
        assertThat(results.get(8).content()).startsWith("NOT_RUN: at most 8 tools");
        assertThat(stored.getFirst().executions()).as("the record holds what ran, as it was sent").hasSize(6)
                .extracting(execution -> execution.call().id()).containsExactly("toolu_1", "toolu_2", "toolu_3",
                        "toolu_4", "toolu_5", "toolu_6");
    }

    @Test
    @DisplayName("when the rounds run out, the last call offers no tool and says why, and its answer is kept with "
            + "every result already in")
    void lastCallOffersNoTool() {
        maxToolRounds = 1;
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "CUP-12OZ còn 40 cái; LID-90 tôi chưa kiểm tra được.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "So sánh CUP-12OZ với LID-90", MANAGER);

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
        model.then(LanguageModel.StopReason.END_TURN, "Còn hàng.");

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
        when(conversations.appendAndRelease(eq(CONVERSATION), any(), any(), anyList(), any()))
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
        when(conversations.appendAndRelease(eq(CONVERSATION), any(), any(), anyList(), any()))
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
    @DisplayName("the watch's question is offered only the tools it names, of the user's own, and does not count "
            + "against the user's daily questions")
    void aProactiveQuestionGetsOnlyItsTools() {
        model.then(LanguageModel.StopReason.END_TURN, "Không có gì cần làm.");
        model.then(LanguageModel.StopReason.END_TURN, "Còn 40 cái.");
        when(conversations.questionsSince(any(), any())).thenReturn(1_000L);

        service().askProactively(CONVERSATION, "Các PO sau đã trễ: PO-1.", MANAGER, Set.of("findPurchaseOrders"));
        service().askProactively(CONVERSATION, "Các mã sau đã thấp: CUP-12OZ.", MANAGER, Set.of("getStockLevels"));

        assertThat(model.requests.getFirst().tools()).as("findPurchaseOrders is not the user's").isEmpty();
        assertThat(model.requests.get(1).tools()).extracting(ToolSpec::name).containsExactly("getStockLevels");
        verify(conversations, times(2)).requireProactive(CONVERSATION);
        assertThat(stored).filteredOn(message -> message.role() == MessageRole.ASSISTANT).isNotEmpty();
    }

    @Test
    @DisplayName("in the watch's question a call to a tool it was not offered is refused without running, even one "
            + "the user may use: text it quotes cannot reach another tool")
    void aProactiveQuestionCannotReachAnotherTool() {
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "Không xem được tồn kho trong câu này.");

        AssistantService.Answer answer = service().askProactively(CONVERSATION, "Các PO sau đã trễ: PO-1.",
                MANAGER, Set.of("findPurchaseOrders"));

        assertThat(toolRuns).as("never run").hasValue(0);
        assertThat(answer.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.tool()).isEqualTo("getStockLevels");
            assertThat(call.outcome()).isEqualTo(ToolOutcome.DENIED);
        });
        assertThat(stored).flatExtracting(PendingMessage::executions)
                .allSatisfy(run -> assertThat(run.result()).startsWith("NOT_OFFERED"));
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
        assertThat(model.requests.getFirst().tools()).extracting(ToolSpec::name).containsExactly("getStockLevels");
        assertThat(model.requests.getFirst().systemPrompt()).doesNotContain("2026");
    }

    @Test
    @DisplayName("only the tools the user may use are offered, the same list on every call; a call to another tool "
            + "still reaches it, under its own name, where its guard would refuse it (no proxy here: "
            + "AssistantOfferedToolsIntegrationTest checks the refusal)")
    void offersOnlyGrantedTools() {
        CurrentUser sales = new CurrentUser(UUID.randomUUID(), "sales", Set.of(Role.SALES_STAFF),
                Set.of(PermissionCode.of(ToolPermissions.SALES_ORDERS, Action.READ)), DataScope.ALL, Set.of());
        model.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("toolu_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        model.then(LanguageModel.StopReason.END_TURN, "Chưa xem được tồn kho.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Còn bao nhiêu CUP-12OZ?", sales);

        assertThat(model.requests).hasSize(2).allSatisfy(request -> assertThat(request.tools())
                .as("no stock tool for a user without stock permissions").isEmpty());
        assertThat(toolRuns).hasValue(1);
        assertThat(answer.toolCalls()).extracting(ConversationService.ToolCallView::tool)
                .as("recorded under its own name, not as an unknown tool").containsExactly("getStockLevels");
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

    @Test
    @DisplayName("a spent quota on the first call moves the question to the next model before any reply; the next "
            + "questions start there until Gemini's quota day ends, at midnight Pacific time")
    void spentQuotaMovesOn() {
        route = List.of(lite, flash);
        lite.fail(quota());
        flash.then(LanguageModel.StopReason.END_TURN, "Còn hàng.");
        flash.then(LanguageModel.StopReason.END_TURN, "Hết hàng.");
        AssistantService service = service();

        assertThat(service.ask(CONVERSATION, "Còn bao nhiêu CUP-12OZ?", MANAGER).text()).isEqualTo("Còn hàng.");
        // 06:59 UTC is 23:59 on 24/10 in Los Angeles: the free tier's day has not ended yet.
        clock.advance(Duration.ofHours(4).plusMinutes(59));
        assertThat(service.ask(CONVERSATION, "Còn bao nhiêu LID-90?", MANAGER).text()).isEqualTo("Hết hàng.");

        assertThat(lite.requests).as("set aside after one refused call").hasSize(1);
        assertThat(stored).as("the refused call left nothing behind; each answer names the model that wrote it")
                .extracting(PendingMessage::model).containsExactly("gemini-3.5-flash", "gemini-3.5-flash");

        clock.advance(Duration.ofMinutes(1));
        lite.then(LanguageModel.StopReason.END_TURN, "Còn 7 cái.");
        assertThat(service.ask(CONVERSATION, "Còn bao nhiêu ống hút?", MANAGER).text())
                .as("a new quota day: the cheap model again").isEqualTo("Còn 7 cái.");
        assertThat(flash.requests).hasSize(2);
    }

    @Test
    @DisplayName("a provider that could not be reached moves the question on, and sets nothing aside for the next one")
    void unreachableMovesOn() {
        route = List.of(lite, flash);
        lite.fail(unavailable());
        flash.then(LanguageModel.StopReason.END_TURN, "Còn hàng.");
        lite.then(LanguageModel.StopReason.END_TURN, "Hết hàng.");
        AssistantService service = service();

        assertThat(service.ask(CONVERSATION, "Còn bao nhiêu CUP-12OZ?", MANAGER).text()).isEqualTo("Còn hàng.");
        assertThat(service.ask(CONVERSATION, "Còn bao nhiêu LID-90?", MANAGER).text()).isEqualTo("Hết hàng.");
        assertThat(flash.requests).hasSize(1);
    }

    @Test
    @DisplayName("once a model has replied, the question stays on it and fails with it: its turns are valid for no "
            + "other model")
    void staysOnTheModelThatReplied() {
        route = List.of(lite, flash);
        lite.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("call_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        lite.fail(unavailable());

        assertThatThrownBy(() -> service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThat(flash.requests).isEmpty();
        assertThat(stored).extracting(PendingMessage::role)
                .containsExactly(MessageRole.ASSISTANT, MessageRole.TOOL_RESULTS, MessageRole.ASSISTANT);
        assertThat(((Turn.ModelTurn) stored.getLast().turn()).providerContent())
                .as("closed in the format of the model the question was on").isEqualTo(lite.closing.providerContent());
    }

    @Test
    @DisplayName("a quota spent in the middle of an answer ends it there, on its model; the user is told to ask again "
            + "in a moment, not that asking again will not work, and the next question starts on the next model")
    void quotaSpentMidAnswer() {
        route = List.of(lite, flash);
        lite.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("call_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        lite.fail(quota());
        flash.then(LanguageModel.StopReason.END_TURN, "Còn hàng.");
        AssistantService service = service();

        assertThatThrownBy(() -> service.ask(CONVERSATION, "Còn bao nhiêu CUP-12OZ?", MANAGER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThat(flash.requests).as("the question stays on the model that replied").isEmpty();
        assertThat(stored.getFirst().executions()).as("the lookup that ran is on record").hasSize(1);
        assertThat(stored.getLast().text()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE.defaultMessage());

        assertThat(service.ask(CONVERSATION, "Còn bao nhiêu CUP-12OZ?", MANAGER).text()).isEqualTo("Còn hàng.");
        assertThat(lite.requests).hasSize(2);
    }

    @Test
    @DisplayName("on the last model with a quota, one spent in the middle of an answer is reported as spent")
    void quotaSpentMidAnswerOnTheLastModel() {
        route = List.of(lite, flash);
        lite.fail(quota());
        flash.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("call_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        flash.fail(quota());

        assertThatThrownBy(() -> service().ask(CONVERSATION, "Còn bao nhiêu CUP-12OZ?", MANAGER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
    }

    @Test
    @DisplayName("ASSISTANT_QUOTA_EXHAUSTED reaches the user only when every model is spent; then each question tries "
            + "them all again, and the first that replies is no longer set aside")
    void quotaExhaustedWhenEveryModelIsSpent() {
        route = List.of(lite, flash);
        lite.fail(quota());
        flash.fail(quota());
        AssistantService service = service();

        assertThatThrownBy(() -> service.ask(CONVERSATION, "Còn bao nhiêu?", MANAGER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        assertThat(stored).singleElement().satisfies(closing -> {
            assertThat(closing.model()).isEqualTo(PendingMessage.CLOSING_MODEL);
            assertThat(closing.text()).isEqualTo(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED.defaultMessage());
        });

        // Both set aside until midnight Pacific; an operator raised the second one's quota meanwhile.
        lite.fail(quota());
        flash.then(LanguageModel.StopReason.END_TURN, "Còn hàng.");
        assertThat(service.ask(CONVERSATION, "Còn bao nhiêu?", MANAGER).text()).isEqualTo("Còn hàng.");
        flash.then(LanguageModel.StopReason.END_TURN, "Hết hàng.");
        assertThat(service.ask(CONVERSATION, "Còn bao nhiêu LID-90?", MANAGER).text()).isEqualTo("Hết hàng.");
        assertThat(lite.requests).as("not asked again once another model answered").hasSize(2);
    }

    @Test
    @DisplayName("a model that is only unreachable is never reported as spent")
    void unreachableIsNotSpent() {
        route = List.of(lite, flash);
        lite.fail(quota());
        flash.fail(unavailable());

        assertThatThrownBy(() -> service().ask(CONVERSATION, "Còn bao nhiêu?", MANAGER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
    }

    @Test
    @DisplayName("a reply empty twice is answered again, from the question, on the next model, which is sent the "
            + "earlier answers as its own text turns, never what another model or provider wrote")
    void emptyTwiceIsEscalated() {
        route = List.of(lite, flash);
        // Answered by Claude, before the deployment moved to Gemini: thinking signature and all.
        earlier.add(new Turn.Question("Còn bao nhiêu CUP-12OZ?", "ctx"));
        earlier.add(new Turn.ModelTurn("{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\","
                + "\"signature\":\"EqQBCkYIBxgCKkB-claude\"},{\"type\":\"text\",\"text\":\"Còn 40 cái.\"}]}",
                "Còn 40 cái.", List.of()));
        lite.then(LanguageModel.StopReason.OTHER, null);
        lite.then(LanguageModel.StopReason.END_TURN, " ");
        flash.then(LanguageModel.StopReason.END_TURN, "Chưa có lô nào sắp hết hạn.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Lô nào sắp hết hạn?", MANAGER);

        assertThat(answer.text()).isEqualTo("Chưa có lô nào sắp hết hạn.");
        assertThat(lite.requests).hasSize(2);
        assertThat(lite.requests.getFirst().history().get(1)).isInstanceOfSatisfying(Turn.ModelTurn.class,
                turn -> assertThat(turn.providerContent()).isEqualTo(lite.closing.providerContent()));
        List<Turn> sent = flash.requests.getFirst().history();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(1)).isInstanceOfSatisfying(Turn.ModelTurn.class, turn -> {
            assertThat(turn.text()).isEqualTo("Còn 40 cái.");
            assertThat(turn.providerContent()).isEqualTo(flash.closing.providerContent())
                    .doesNotContain("EqQBCkYIBxgCKkB-claude");
        });
        assertThat(sent.getLast()).isInstanceOfSatisfying(Turn.Question.class,
                question -> assertThat(question.text()).isEqualTo("Lô nào sắp hết hạn?"));
        assertThat(stored).as("the first model's replies and its closing are on record; the answer is the second's")
                .extracting(PendingMessage::model).containsExactly("gemini-3.5-flash-lite", "gemini-3.5-flash-lite",
                        PendingMessage.CLOSING_MODEL, "gemini-3.5-flash");
        assertThat(answer.usage().inputTokens()).as("every call is paid for, on either model").isEqualTo(300);
    }

    @Test
    @DisplayName("lookups that ran out without an answer are answered again on the next model; every lookup that ran "
            + "is listed with the answer")
    void roundsRunOutIsEscalated() {
        maxToolRounds = 1;
        route = List.of(lite, flash);
        lite.then(LanguageModel.StopReason.TOOL_USE, null, new ToolCall("call_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        lite.then(LanguageModel.StopReason.TOOL_USE, "CUP-12OZ còn 40 cái.", new ToolCall("call_2",
                "getStockLevels", "{\"sku\":\"LID-90\"}"));
        flash.then(LanguageModel.StopReason.TOOL_USE, null,
                new ToolCall("toolu_1", "getStockLevels", "{\"sku\":\"CUP-12OZ\"}"),
                new ToolCall("toolu_2", "getStockLevels", "{\"sku\":\"LID-90\"}"));
        flash.then(LanguageModel.StopReason.END_TURN, "CUP-12OZ và LID-90 đều còn 40 cái.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "So sánh hai mã", MANAGER);

        assertThat(answer.text()).isEqualTo("CUP-12OZ và LID-90 đều còn 40 cái.");
        assertThat(flash.requests.getFirst().history()).as("from the question alone")
                .singleElement().isInstanceOf(Turn.Question.class);
        assertThat(flash.requests).flatExtracting(LanguageModel.Request::history)
                .filteredOn(Turn.ModelTurn.class::isInstance)
                .as("every model turn the second model is sent, it wrote").isNotEmpty()
                .allSatisfy(turn -> assertThat(writtenBy(flash, (Turn.ModelTurn) turn)).isTrue());
        assertThat(toolRuns).hasValue(3);
        assertThat(answer.toolCalls()).as("the first model's lookup ran and is on record too").hasSize(3);
    }

    @Test
    @DisplayName("a reply that held nothing but markup is answered again on the next model")
    void markupOnlyIsEscalated() {
        route = List.of(lite, flash);
        lite.then(LanguageModel.StopReason.END_TURN, "![](https://evil.example/p.png?d=12500000)");
        flash.then(LanguageModel.StopReason.END_TURN, "Hôm qua chưa có doanh thu.");

        AssistantService.Answer answer = service().ask(CONVERSATION, "Doanh thu hôm qua?", MANAGER);

        assertThat(answer.text()).isEqualTo("Hôm qua chưa có doanh thu.");
        assertThat(lite.requests).hasSize(1);
    }

    @Test
    @DisplayName("an answer, a cut-off one or a refusal is the model's say: no other model is asked")
    void answersAreNotEscalated() {
        route = List.of(lite, flash);
        lite.then(LanguageModel.StopReason.REFUSAL, null);
        lite.then(LanguageModel.StopReason.MAX_TOKENS, null);
        lite.then(LanguageModel.StopReason.END_TURN, "Còn hàng.");
        AssistantService service = service();

        assertThat(service.ask(CONVERSATION, "…", MANAGER).text()).isEqualTo("I cannot help with that request.");
        assertThat(service.ask(CONVERSATION, "Liệt kê tất cả", MANAGER).cutShort()).isTrue();
        assertThat(service.ask(CONVERSATION, "Còn bao nhiêu?", MANAGER).text()).isEqualTo("Còn hàng.");
        assertThat(flash.requests).isEmpty();
    }

    @Test
    @DisplayName("escalation happens once, and not at all when it is switched off")
    void escalatesOnce() {
        route = List.of(lite, flash, pro);
        lite.then(LanguageModel.StopReason.OTHER, null);
        lite.then(LanguageModel.StopReason.OTHER, null);
        flash.then(LanguageModel.StopReason.OTHER, null);
        flash.then(LanguageModel.StopReason.OTHER, null);

        AssistantService.Answer answer = service().ask(CONVERSATION, "Lô nào sắp hết hạn?", MANAGER);

        assertThat(answer.text()).isEqualTo("The assistant did not manage to write an answer this time. "
                + "Please ask again.");
        assertThat(flash.requests).hasSize(2);
        assertThat(pro.requests).isEmpty();

        escalate = false;
        lite.then(LanguageModel.StopReason.OTHER, null);
        lite.then(LanguageModel.StopReason.OTHER, null);
        assertThat(service().ask(CONVERSATION, "Lô nào sắp hết hạn?", MANAGER).text())
                .startsWith("The assistant did not manage");
        assertThat(flash.requests).hasSize(2);
    }

    @Test
    @DisplayName("an escalation that cannot start leaves the answer as the first model closed it, without an error; "
            + "the spent quota it met is remembered")
    void escalationThatCannotStart() {
        route = List.of(lite, flash);
        lite.then(LanguageModel.StopReason.OTHER, null);
        lite.then(LanguageModel.StopReason.OTHER, null);
        flash.fail(quota());
        lite.then(LanguageModel.StopReason.OTHER, null);
        lite.then(LanguageModel.StopReason.OTHER, null);
        AssistantService service = service();

        assertThat(service.ask(CONVERSATION, "Lô nào sắp hết hạn?", MANAGER).text())
                .startsWith("The assistant did not manage");
        assertThat(stored).extracting(PendingMessage::model).containsExactly("gemini-3.5-flash-lite",
                "gemini-3.5-flash-lite", PendingMessage.CLOSING_MODEL);

        service.ask(CONVERSATION, "Lô nào sắp hết hạn?", MANAGER);
        assertThat(flash.requests).as("set aside: the second question is not escalated to it").hasSize(1);
    }

    @Test
    @DisplayName("no other model is asked when the lease has no room left for its first call and a closing one")
    void noEscalationWithoutTime() {
        lease = Duration.ofMinutes(12);
        route = List.of(lite, flash);
        lite.onCall = () -> clock.advance(Duration.ofMinutes(2));
        lite.then(LanguageModel.StopReason.OTHER, null);
        lite.then(LanguageModel.StopReason.OTHER, null);

        AssistantService.Answer answer = service().ask(CONVERSATION, "Lô nào sắp hết hạn?", MANAGER);

        // 4 minutes in, two 4.5-minute calls would end past the 12-minute lease.
        assertThat(answer.text()).startsWith("The assistant did not manage");
        assertThat(flash.requests).isEmpty();
    }

    /** Whether {@code model} wrote the turn: its name, quoted, is in the content (one name may begin another). */
    private static boolean writtenBy(ScriptedModel model, Turn.ModelTurn turn) {
        return turn.providerContent().contains("\"" + model.name + "\"");
    }

    private static BusinessException quota() {
        return new BusinessException(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED, "quota spent");
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "provider down");
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

    /**
     * A language model that plays back a script and records what it was asked. Every turn it makes carries its name,
     * the way a provider's content is its own format: a turn in a request that does not name the model it went to was
     * written by another one.
     */
    private static final class ScriptedModel implements LanguageModel {

        final String name;
        final List<Request> requests = new ArrayList<>();
        final Deque<Object> script = new ArrayDeque<>();
        final Turn.ModelTurn closing;
        boolean available = true;
        Runnable onCall = () -> { };

        ScriptedModel(String name) {
            this.name = name;
            this.closing = new Turn.ModelTurn("{\"closing\":\"" + name + "\"}", "closing", List.of());
        }

        void then(StopReason stop, String text, ToolCall... calls) {
            script.add(new Reply(new Turn.ModelTurn("{\"reply\":" + script.size() + ",\"by\":\"" + name + "\"}", text,
                    List.of(calls)), stop, new Usage(100, 10, 0, 0), name));
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
