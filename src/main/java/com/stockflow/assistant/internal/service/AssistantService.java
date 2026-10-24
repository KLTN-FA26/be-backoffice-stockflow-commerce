package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.AnswerText;
import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.Conversation;
import com.stockflow.assistant.internal.domain.History;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Message;
import com.stockflow.assistant.internal.domain.MessageRole;
import com.stockflow.assistant.internal.domain.PendingMessage;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The agent loop (design §3.1): one question in, one answer out, with as many tool rounds as the model needs up to
 * {@code stockflow.assistant.max-tool-rounds}.
 *
 * <p><b>Not transactional, on purpose.</b> A model call takes seconds; a transaction around the loop would hold a
 * pooled connection for the whole answer. The question is stored in one short transaction before the loop, what the
 * loop produced in another after it, and each tool's {@code api} method opens its own.</p>
 *
 * <p><b>On the request thread.</b> Tools rely on the {@code SecurityContext} and on the data scope the permission
 * aspect installs, both thread-bound, and {@code AsyncConfig} copies neither to another thread (design §5.2).</p>
 *
 * <p><b>Partial work is kept.</b> If the provider fails halfway, the turns produced so far — tools already run
 * included — are stored before the error goes back, so the audit trail never misses a tool call that happened.</p>
 *
 * <p><b>Every answer closes the history cleanly.</b> A reply that asks for tools the loop will not run (it was cut
 * off, the provider declined, or the rounds ran out) still gets a result for each call and a closing turn, so the
 * next question is sent after a well-formed history.</p>
 *
 * <p><b>Bounded cost.</b> Earlier questions go to the model with their answers only ({@link History}); one step runs
 * at most {@value #MAX_CALLS_PER_ROUND} tools and one question adds at most {@value #RESULT_BUDGET} characters of
 * tool results, the rest answered NOT_RUN; and no model call starts that could outlast the conversation's lease, so a
 * slow provider ends the answer instead of letting a second question in while this one still writes.</p>
 *
 * <p><b>Plain text out.</b> The answer the panel shows has its links, images and URLs removed ({@link AnswerText}).</p>
 */
@Service
public class AssistantService {

    /** The model name recorded on a closing turn: written by the application, not produced by a model call. */
    static final String CLOSING_TURN_MODEL = "stockflow";

    /** Tools one reply may run; a model asking for dozens at once is answered NOT_RUN for the rest. */
    static final int MAX_CALLS_PER_ROUND = 8;

    /** Characters of tool results one question may add to what is sent to the model. */
    static final int RESULT_BUDGET = 100_000;

    private final ConversationService conversations;
    private final LanguageModel model;
    private final AssistantTools tools;
    private final SystemPrompt prompt;
    private final AssistantProperties properties;
    private final Messages messages;
    private final Clock clock;
    private final ObjectMapper json;

    AssistantService(ConversationService conversations, LanguageModel model, AssistantTools tools,
                     SystemPrompt prompt, AssistantProperties properties, Messages messages, Clock clock,
                     ObjectMapper json) {
        this.conversations = conversations;
        this.model = model;
        this.tools = tools;
        this.prompt = prompt;
        this.properties = properties;
        this.messages = messages;
        this.clock = clock;
        this.json = json;
    }

    /** Told each time a tool finishes, for a streamed answer; on the thread that runs the loop. */
    @FunctionalInterface
    public interface Progress {

        Progress NONE = call -> { };

        void toolRan(ConversationService.ToolCallView call);
    }

    /**
     * Answers one question in a conversation the caller owns.
     *
     * @throws BusinessException {@code ASSISTANT_DISABLED} when the assistant cannot answer at all (nothing is
     *         stored); {@code VALIDATION_FAILED} for an empty or overlong question; {@code ASSISTANT_DAILY_LIMIT};
     *         {@code ASSISTANT_BUSY} while another question in the conversation is being answered;
     *         {@code ASSISTANT_UNAVAILABLE} when the provider fails (the question and any partial work are stored)
     * @throws com.stockflow.common.error.NotFoundException when the conversation is not the caller's
     */
    public Answer ask(UUID conversationId, String text, CurrentUser user) {
        return ask(conversationId, text, user, Progress.NONE);
    }

    /**
     * {@link #ask}, guarded here as well as at the endpoint: {@link AssistantStreams} calls it on a worker thread,
     * where no data scope is in force until this annotation's aspect installs the caller's OWN scope again
     * (design §5.2). Called through the proxy, never from inside this class.
     */
    @RequiresPermission(resource = ToolPermissions.ASSISTANT, action = Action.CREATE, scope = DataScope.OWN)
    public Answer askAsOwner(UUID conversationId, String text, CurrentUser user, Progress progress) {
        return ask(conversationId, text, user, progress);
    }

    /**
     * Every refusal a question would meet before the model is called — switched off, an invalid question, the daily
     * cap, a conversation that is not the caller's or is busy — without storing anything. {@link AssistantStreams}
     * calls it on the request thread, so these come back as ordinary responses instead of an {@code error} event
     * inside a 200 stream. {@link #ask} checks them again: this answers early, it does not reserve.
     */
    public void refuseEarly(UUID conversationId, String text, CurrentUser user) {
        refuseUpFront(text, user, clock.instant());
        conversations.requireIdle(conversationId, properties.answerLease());
    }

    /** @return the question, trimmed */
    private String refuseUpFront(String text, CurrentUser user, Instant now) {
        if (!model.available()) {
            throw new BusinessException(ErrorCode.ASSISTANT_DISABLED);
        }
        String question = Conversation.question(text);
        // A soft cap: two questions racing in two conversations may both pass at the limit. It bounds cost, it is not
        // a security boundary.
        if (conversations.questionsSince(user.userId(), BusinessDate.startOfDay(now))
                >= properties.dailyMessageLimit()) {
            throw new BusinessException(ErrorCode.ASSISTANT_DAILY_LIMIT,
                    "The daily limit of " + properties.dailyMessageLimit() + " questions is reached");
        }
        return question;
    }

    private Answer ask(UUID conversationId, String text, CurrentUser user, Progress progress) {
        Instant now = clock.instant();
        String question = refuseUpFront(text, user, now);
        Turn.Question turn = new Turn.Question(question, context(user, now));
        Instant claimedAt = now.truncatedTo(ChronoUnit.MICROS);
        List<Turn> history = new ArrayList<>(History.forModel(conversations.claimAndAsk(conversationId,
                PendingMessage.question(turn), claimedAt, properties.answerLease()), model::closingTurn));
        List<PendingMessage> produced = new ArrayList<>();
        try {
            answer(history, produced::add, progress, claimedAt.plus(properties.answerLease()));
        } catch (RuntimeException failure) {
            try {
                conversations.appendAndRelease(conversationId, claimedAt, produced);
            } catch (RuntimeException alsoFailed) {
                failure.addSuppressed(alsoFailed);
            }
            throw failure;
        }
        return Answer.of(conversations.appendAndRelease(conversationId, claimedAt, produced), json);
    }

    private void answer(List<Turn> history, Consumer<PendingMessage> sink, Progress progress, Instant leaseEnds) {
        Consumer<PendingMessage> record = message -> {
            history.add(message.turn());
            sink.accept(message);
        };
        int budget = RESULT_BUDGET;
        for (int round = 0; ; round++) {
            if (round > 0 && clock.instant().plus(properties.longestModelCall()).isAfter(leaseEnds)) {
                // The last step's results are in; another call could outlast the claim. Close instead.
                Turn.ModelTurn closing = model.closingTurn(closingText(LanguageModel.StopReason.OTHER));
                record.accept(new PendingMessage(closing, closing.text(), CLOSING_TURN_MODEL,
                        LanguageModel.StopReason.OTHER, LanguageModel.Usage.NONE, List.of()));
                return;
            }
            LanguageModel.Reply reply = model.next(new LanguageModel.Request(prompt.text(), tools.specs(), history));
            List<ToolCall> calls = reply.turn().toolCalls();
            if (reply.stopReason() == LanguageModel.StopReason.TOOL_USE && !calls.isEmpty()
                    && round < properties.maxToolRounds()) {
                List<ToolExecution> executions = new ArrayList<>();
                List<ToolResult> results = new ArrayList<>();
                for (int i = 0; i < calls.size(); i++) {
                    ToolCall call = calls.get(i);
                    if (i >= MAX_CALLS_PER_ROUND) {
                        results.add(new ToolResult(call.id(), "NOT_RUN: at most " + MAX_CALLS_PER_ROUND
                                + " tools run in one step; ask for the rest in the next step", true));
                    } else if (budget <= 0) {
                        results.add(new ToolResult(call.id(), "NOT_RUN: this question has used up its room for tool "
                                + "results; answer with what you have, or ask the user to narrow the question", true));
                    } else {
                        ToolExecution execution = tools.execute(call);
                        budget -= execution.result().length();
                        progress.toolRan(ConversationService.ToolCallView.of(execution));
                        executions.add(execution);
                        results.add(execution.toResult());
                    }
                }
                record.accept(PendingMessage.reply(reply, executions));
                record.accept(PendingMessage.results(new Turn.ToolResults(results)));
                continue;
            }
            if (calls.isEmpty()) {
                record.accept(new PendingMessage(reply.turn(), displayText(reply), reply.model(), reply.stopReason(),
                        reply.usage(), List.of()));
                return;
            }
            // Tool calls that will not run: answer each, then close the turn, so the history stays well formed.
            record.accept(PendingMessage.reply(reply, List.of()));
            String why = switch (reply.stopReason()) {
                case MAX_TOKENS -> "NOT_RUN: the reply was cut off before the call was complete";
                case REFUSAL -> "NOT_RUN: the request was declined";
                default -> "NOT_RUN: no tool rounds are left for this question";
            };
            record.accept(PendingMessage.results(new Turn.ToolResults(
                    calls.stream().map(call -> new ToolResult(call.id(), why, true)).toList())));
            Turn.ModelTurn closing = model.closingTurn(closingText(reply.stopReason()));
            record.accept(new PendingMessage(closing, closing.text(), CLOSING_TURN_MODEL,
                    LanguageModel.StopReason.OTHER, LanguageModel.Usage.NONE, List.of()));
            return;
        }
    }

    private String displayText(LanguageModel.Reply reply) {
        String text = AnswerText.plain(reply.turn().text());
        if (text != null && !text.isBlank()) {
            return text;
        }
        return reply.stopReason() == LanguageModel.StopReason.REFUSAL
                ? messages.get("assistant.refused", "I cannot help with that request.")
                : messages.get("assistant.noAnswer", "I could not find an answer to that.");
    }

    private String closingText(LanguageModel.StopReason stopReason) {
        return switch (stopReason) {
            case REFUSAL -> messages.get("assistant.refused", "I cannot help with that request.");
            case MAX_TOKENS -> messages.get("assistant.cutShort",
                    "The answer was too long to finish. Please ask a narrower question.");
            default -> messages.get("assistant.outOfRounds",
                    "I could not finish looking this up. Please ask a narrower question.");
        };
    }

    /**
     * What the model needs alongside the question and must not take from the system prompt, which stays
     * byte-identical: today's date, and who is asking — roles and warehouses, never a name.
     */
    private static String context(CurrentUser user, Instant now) {
        LocalDate today = BusinessDate.of(now);
        String warehouses = user.warehouseCodes().isEmpty() ? ""
                : " (warehouses: " + String.join(", ", user.warehouseCodes().stream().sorted().toList()) + ")";
        return ("Context for this question, from the system and not written by the user: today is %s, %s, Vietnam "
                + "time. The user's roles: %s. Their data scope: %s%s.")
                .formatted(today, today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                        String.join(", ", user.roles().stream().map(Enum::name).sorted().toList()),
                        user.scope(), warehouses);
    }

    /**
     * One answer as the panel shows it.
     *
     * @param messageId the stored message holding the answer
     * @param toolCalls every tool run for it, in order
     * @param cutShort  the answer hit the output limit and may be incomplete
     * @param usage     tokens across every model call it took
     * @param citations rule anchors the answer names that its own searches returned (design §6.3)
     * @param proposals purchase orders the panel may open pre-filled (design §8)
     */
    public record Answer(UUID messageId, String text, List<ConversationService.ToolCallView> toolCalls,
                         boolean cutShort, LanguageModel.Usage usage, List<String> citations,
                         List<AnswerSources.Proposal> proposals) {

        static Answer of(List<Message> stored, ObjectMapper json) {
            Message last = stored.stream().filter(message -> message.role() == MessageRole.ASSISTANT
                    && message.text() != null).reduce((first, second) -> second).orElseThrow(
                    () -> new IllegalStateException("an answer always ends with an assistant turn holding text"));
            long input = 0;
            long output = 0;
            long cacheRead = 0;
            long cacheWrite = 0;
            List<ConversationService.ToolCallView> calls = new ArrayList<>();
            List<ToolExecution> executions = new ArrayList<>();
            boolean cutShort = false;
            for (Message message : stored) {
                input += message.usage().inputTokens();
                output += message.usage().outputTokens();
                cacheRead += message.usage().cacheReadTokens();
                cacheWrite += message.usage().cacheWriteTokens();
                executions.addAll(message.executions());
                message.executions().forEach(execution -> calls.add(ConversationService.ToolCallView.of(execution)));
                cutShort |= message.stopReason() == LanguageModel.StopReason.MAX_TOKENS;
            }
            return new Answer(last.id(), last.text(), List.copyOf(calls), cutShort,
                    new LanguageModel.Usage(input, output, cacheRead, cacheWrite),
                    AnswerSources.citations(last.text(), executions, json),
                    AnswerSources.proposals(executions, json));
        }
    }
}
