package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.AnswerText;
import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.Conversation;
import com.stockflow.assistant.internal.domain.History;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Message;
import com.stockflow.assistant.internal.domain.PendingMessage;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolChannel;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
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
 * <p><b>On the request thread, its tools beside it.</b> Tools rely on the {@code SecurityContext} and on the data scope
 * the permission aspect installs, both thread-bound, and {@code AsyncConfig} copies neither to another thread. The
 * loop runs where it is called; the calls of one step run at once on workers that are handed both
 * ({@link ParallelTools}), one call alone on the loop's own thread (design §5.2).</p>
 *
 * <p><b>Numbers it cannot point to are corrected once.</b> An answer stating a number that no tool result, question or
 * context of the conversation holds ({@link AnswerNumbers}) is sent back once, without tools, with those numbers named,
 * to be written again with only numbers it can point to. The rewrite is the answer only when it is complete and
 * states fewer such numbers; otherwise the first stands, flagged as before. Both are stored and paid for; a rewrite
 * that fails or would outlast the lease leaves the first answer as it is.</p>
 *
 * <p><b>What was looked up is not thrown away.</b> The answer's last call — the tool rounds are spent, or the lease
 * has room for this one call only — offers the model no tool and says why: it answers from the results it already
 * has, instead of asking for one more lookup and leaving every result in without an answer. If it asks anyway, or
 * the time runs out before that call, the answer closes with a message that says so, after what the model wrote.</p>
 *
 * <p><b>Partial work is kept.</b> If the provider fails halfway, the turns produced so far — tools already run
 * included — are stored before the error goes back, with a closing turn holding the error's message, so the audit
 * trail never misses a tool call that happened and the transcript says what became of the question. A question the
 * provider never replied to is left out of the daily cap. If storing fails, the claim is released all the same.</p>
 *
 * <p><b>Every answer closes the history cleanly.</b> A reply that asks for tools the loop will not run (it was cut
 * off, the provider declined, or no lookup was left) still gets a result for each call and a closing turn, so the
 * next question is sent after a well-formed history.</p>
 *
 * <p><b>An empty reply is asked again, once.</b> A small model sometimes returns nothing at all: a function call the
 * provider could not parse, an empty stop after tool results. Shown as it is, that reads as "nothing was found". The
 * same request goes again with a note, not as a tool round; a second empty reply closes with a message that the
 * assistant could not answer, and the question goes to a stronger model if there is one (below).</p>
 *
 * <p><b>Bounded cost.</b> Earlier questions go to the model with their answers only ({@link History}); one step runs
 * at most {@value #MAX_CALLS_PER_ROUND} tools and one question adds at most {@value #RESULT_BUDGET} characters of
 * tool results, the rest answered NOT_RUN, never run: a step runs at once only as many as the room left holds at
 * their longest; and no model call starts that could outlast the conversation's lease, so a
 * slow provider ends the answer instead of letting a second question in while this one still writes. A question is
 * answered at most twice, the second time only when the first gave nothing usable (below).</p>
 *
 * <p><b>A cheap model first, a stronger one when needed ({@link ModelRouter}).</b> A question starts on the cheapest
 * model whose quota is not spent. If that model's first call fails with a spent quota, or with a provider that could
 * not be reached, the question goes to the next model. Once a model has replied, the question stays on it and fails
 * with it: the turns it wrote carry thought signatures and thinking blocks that only that model accepts back. A
 * question the model replied to without a usable answer — empty or malformed twice, nothing left once its markup was
 * removed, or still asking for lookups when they ran out — is answered once more, from the start, on the next model;
 * what the first one did, tools included, is stored as well, and the answer is the second one's. Every attempt is sent
 * the history rebuilt for its own model ({@link History}), never a turn another model wrote.
 * {@code ASSISTANT_QUOTA_EXHAUSTED} reaches the user only when every model is spent.</p>
 *
 * <p><b>Plain text out.</b> The answer the panel shows has its links, images and URLs removed ({@link AnswerText}).</p>
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    /** Tools one reply may run; a model asking for dozens at once is answered NOT_RUN for the rest. */
    static final int MAX_CALLS_PER_ROUND = 8;

    /** Characters of tool results one question may add to what is sent to the model. */
    static final int RESULT_BUDGET = 100_000;

    /**
     * Stored as the name of a call to a tool that does not exist. Gemini has no strict mode, and a made-up name may
     * hold a dot, a dash or a colon, which {@code ck_tool_call_name} refuses: one such row would roll the whole answer
     * back at the very end. The call's result still names what the model asked for.
     */
    static final String UNKNOWN_TOOL = "unknownTool";

    /** How a note to the model starts: like the question's context, it is the system speaking, never the user. */
    private static final String NOTE = "Note from the system, not written by the user:";


    private final ConversationService conversations;
    private final ModelRouter models;
    private final AssistantTools tools;
    private final SystemPrompt prompt;
    private final AssistantProperties properties;
    private final Messages messages;
    private final Clock clock;
    private final ObjectMapper json;
    private final ToolAvailability availability;
    private final AssistantTelemetry telemetry;
    private final ParallelTools parallel;

    AssistantService(ConversationService conversations, ModelRouter models, AssistantTools tools,
                     SystemPrompt prompt, AssistantProperties properties, Messages messages, Clock clock,
                     ObjectMapper json, ToolAvailability availability) {
        this(conversations, models, tools, prompt, properties, messages, clock, json, availability,
                AssistantTelemetry.NONE, new ParallelTools(tools));
    }

    /**
     * @param telemetry where each answer's span, the parent of its model calls and tools, is recorded
     * @param parallel  runs a step's calls at once, in the order the model asked for them
     */
    @Autowired
    AssistantService(ConversationService conversations, ModelRouter models, AssistantTools tools,
                     SystemPrompt prompt, AssistantProperties properties, Messages messages, Clock clock,
                     ObjectMapper json, ToolAvailability availability, AssistantTelemetry telemetry,
                     ParallelTools parallel) {
        this.parallel = parallel;
        this.conversations = conversations;
        this.models = models;
        this.tools = tools;
        this.prompt = prompt;
        this.properties = properties;
        this.messages = messages;
        this.clock = clock;
        this.json = json;
        this.availability = availability;
        this.telemetry = telemetry;
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
     *         {@code ASSISTANT_UNAVAILABLE} and the provider's other failures as the adapter reports them, once no
     *         other model may take the question; {@code ASSISTANT_QUOTA_EXHAUSTED} only when every model's quota is
     *         spent (the question, any partial work and a closing turn with the error's message are stored)
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
        if (!models.available()) {
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
        return telemetry.answer(ToolChannel.CHAT, () -> answering(conversationId, text, user, progress));
    }

    private Answer answering(UUID conversationId, String text, CurrentUser user, Progress progress) {
        Instant now = clock.instant();
        String question = refuseUpFront(text, user, now);
        Turn.Question turn = new Turn.Question(question, context(user, now));
        Instant claimedAt = now.truncatedTo(ChronoUnit.MICROS);
        ModelRouter.Route route = models.route();
        List<Turn> stored = conversations.claimAndAsk(conversationId, PendingMessage.question(turn), claimedAt,
                properties.answerLease());
        List<PendingMessage> produced = new ArrayList<>();
        try {
            answerOnRoute(route, stored, produced, progress, claimedAt.plus(properties.answerLease()),
                    availability.specsFor(user));
        } catch (RuntimeException failure) {
            // The transcript says what became of the question: the turns so far, tools that ran included, then the
            // error's message. Right after the question, that closing turn also keeps it out of the daily cap.
            whileFailing(failure, () -> produced.add(closing(route.model(), failureText(failure))));
            whileFailing(failure, () -> store(conversationId, user.userId(), claimedAt, produced));
            throw failure;
        }
        Answer answer = Answer.of(stored, store(conversationId, user.userId(), claimedAt, produced), json);
        return answer.proposals().isEmpty() ? answer
                : answer.withActions(conversations.actionsOf(answer.messageId(), user.userId()));
    }

    /**
     * Takes the question along the route's models (class javadoc), each attempt sent the history rebuilt for its own
     * model from {@code stored}: the earlier answers as text turns of that model, then the question alone. Nothing an
     * earlier attempt wrote is sent: its turns are another model's, and the question starts again from the beginning.
     *
     * <p>A failure before the model's first reply moves the question on, or ends it with what the route says to tell
     * the user; one after it ends the question there, a spent quota still setting the model aside for the next one. A
     * model moved on to must have the lease room for a first call and a closing one, as the first model had: otherwise
     * the question ends as it stands.</p>
     *
     * @param stored   the conversation as stored, ending with the question
     * @param produced receives what every attempt produced, in order, failed ones included: the audit keeps every
     *                 tool that ran
     * @param offered  the tools the user may use, worked out once for the question: every attempt is offered the same
     */
    private void answerOnRoute(ModelRouter.Route route, List<Turn> stored, List<PendingMessage> produced,
                               Progress progress, Instant leaseEnds, List<ToolSpec> offered) {
        // What the earlier exchanges let an answer repeat, as the transcript reads it (AnswerSources.earlierGrounds).
        AnswerNumbers.Grounds earlier = AnswerSources.earlierGrounds(stored);
        while (true) {
            LanguageModel model = route.model();
            int before = produced.size();
            Outcome outcome;
            try {
                outcome = answer(model, new ArrayList<>(History.forModel(stored, model::closingTurn)), produced::add,
                        progress, leaseEnds, offered, earlier);
            } catch (RepairFailed failed) {
                // The answer stands as the model first wrote it. The model replied, so the route is told so first;
                // a spent quota then still sets it aside, even on a route made of models all set aside.
                route.replied();
                if (failed.cause instanceof BusinessException refused) {
                    route.failedAfterReplying(refused);
                } else {
                    log.warn("Assistant model {} failed rewriting an answer; the answer stands as written",
                            route.name(), failed.cause);
                }
                telemetry.repair("failed");
                return;
            } catch (BusinessException failed) {
                if (produced.size() > before) {
                    // The model replied: the question is tied to it, and ends with it.
                    throw route.failedAfterReplying(failed);
                }
                route.failedBeforeReplying(failed);
                if (fits(clock.instant(), 2, leaseEnds) && route.fallBack(failed)) {
                    continue;
                }
                if (route.escalated()) {
                    // The stronger model could not start: the answer stands as the first model closed it.
                    return;
                }
                throw route.failure();
            }
            route.replied();
            if (outcome == Outcome.NO_ANSWER && fits(clock.instant(), 2, leaseEnds) && route.escalate()) {
                continue;
            }
            return;
        }
    }

    /**
     * Stores what the loop produced and releases the claim. If that fails, the claim is released on its own: the
     * failed transaction rolled the release back with the rest, and the conversation would refuse every question for
     * the rest of the lease with nothing answering. What it held is lost; storing it again without its tool calls
     * would drop exactly the audit rows this class promises to keep.
     */
    private List<Message> store(UUID conversationId, UUID owner, Instant claimedAt, List<PendingMessage> produced) {
        try {
            return conversations.appendAndRelease(conversationId, owner, claimedAt, produced);
        } catch (RuntimeException failed) {
            whileFailing(failed, () -> conversations.release(conversationId, claimedAt));
            throw failed;
        }
    }

    /** A clean-up step of a failed answer: its own failure is added to the first one instead of hiding it. */
    private static void whileFailing(RuntimeException failure, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException alsoFailed) {
            failure.addSuppressed(alsoFailed);
        }
    }

    /**
     * One model's attempt at the answer: the loop of model calls and tool rounds (class javadoc).
     *
     * @param model   the model of this attempt; every call of it goes there
     * @param history what this model is sent, ending with the question; the attempt's turns are added to it
     * @param offered the tools the user may use ({@link ToolAvailability#specsFor}), sent on every call
     * @param earlier what the exchanges before this question let an answer repeat ({@link #repairNote})
     * @return how the attempt ended, for {@link #answerOnRoute} to decide whether another model should try
     */
    private Outcome answer(LanguageModel model, List<Turn> history, Consumer<PendingMessage> sink, Progress progress,
                           Instant leaseEnds, List<ToolSpec> offered, AnswerNumbers.Grounds earlier) {
        Consumer<PendingMessage> record = message -> {
            history.add(message.turn());
            sink.accept(message);
        };
        int budget = RESULT_BUDGET;
        int rounds = 0;
        boolean askedAgain = false;
        boolean askingAgain = false;
        // Set once the answer stated numbers nothing holds: the next call is the one chance to correct them.
        String repairing = null;
        // What this attempt's tools returned, for what the answer cites (a rewrite must keep it).
        List<ToolExecution> ranInAttempt = new ArrayList<>();
        for (int call = 0; ; call++) {
            Instant now = clock.instant();
            // A rewrite was decided with the lease checked already: the answer is in, and must not be closed after.
            if (call > 0 && repairing == null && !fits(now, 1, leaseEnds)) {
                // The last step's results are in; another call could outlast the claim. Close, and say so.
                record.accept(closing(model, outOfTime()));
                return Outcome.OUT_OF_TIME;
            }
            // The last call offers no tool: the rounds are spent, or the lease has room for this call only. Not the
            // first call, whatever the lease: the start-up check leaves room for two, a few milliseconds aside.
            boolean roundsSpent = rounds >= properties.maxToolRounds();
            boolean last = roundsSpent || repairing != null || call > 0 && !fits(now, 2, leaseEnds);
            // Only the user's own tools, worked out once per question (ToolAvailability.specsFor): the same bytes on
            // every call, the last one included. A call to any other tool still runs, and its guard refuses it.
            LanguageModel.Reply reply;
            if (repairing == null) {
                reply = model.next(new LanguageModel.Request(prompt.text(), offered, history, !last,
                        note(last, askingAgain)));
            } else {
                try {
                    reply = model.next(new LanguageModel.Request(prompt.text(), offered, history, false, repairing));
                } catch (RuntimeException failed) {
                    // Optional, this call: nothing it fails with may replace the answer already written.
                    throw new RepairFailed(failed);
                }
                return repaired(reply, record, sink, history, earlier, ranInAttempt);
            }
            askingAgain = false;
            List<ToolCall> calls = reply.turn().toolCalls();
            if (reply.stopReason() == LanguageModel.StopReason.TOOL_USE && !calls.isEmpty() && !last) {
                List<ToolExecution> executions = new ArrayList<>();
                List<ToolResult> results = new ArrayList<>();
                List<ToolCall> admitted = calls.subList(0, Math.min(calls.size(), MAX_CALLS_PER_ROUND));
                int ran = 0;
                while (ran < admitted.size() && budget > 0) {
                    // As many at once as the room left holds at their longest, one at least: a step ends no further
                    // past the budget than one result, as when the calls ran one by one. Every call either ran and
                    // its result was sent, or did not run: what the model received is what the record keeps.
                    int wave = Math.min(admitted.size() - ran, Math.max(1, budget / ToolExecution.MAX_RESULT_LENGTH));
                    for (ToolExecution done : parallel.run(admitted.subList(ran, ran + wave), ToolChannel.CHAT,
                            finished -> progress.toolRan(ConversationService.ToolCallView.of(forTheRecord(finished))))) {
                        ToolExecution execution = forTheRecord(done);
                        budget -= execution.result().length();
                        executions.add(execution);
                        ranInAttempt.add(execution);
                        results.add(execution.toResult());
                    }
                    ran += wave;
                }
                for (ToolCall notRun : calls.subList(ran, calls.size())) {
                    results.add(new ToolResult(notRun.id(), results.size() >= MAX_CALLS_PER_ROUND
                            ? "NOT_RUN: at most " + MAX_CALLS_PER_ROUND + " tools run in one step; ask for the rest "
                                    + "in the next step"
                            : "NOT_RUN: this question has used up its room for tool results; answer with what you "
                                    + "have, or ask the user to narrow the question", true));
                }
                record.accept(PendingMessage.reply(reply, executions));
                record.accept(PendingMessage.results(new Turn.ToolResults(results)));
                rounds++;
                continue;
            }
            if (empty(reply)) {
                // Stored for the audit and the token count, never sent back: the request goes again as it was, with
                // a note, and is not a tool round.
                sink.accept(new PendingMessage(reply.turn(), null, reply.model(), reply.stopReason(), reply.usage(),
                        List.of()));
                if (!askedAgain) {
                    askedAgain = true;
                    askingAgain = true;
                    continue;
                }
                record.accept(closing(model, messages.get("assistant.noAnswer",
                        "The assistant did not manage to write an answer this time. Please ask again.")));
                return Outcome.NO_ANSWER;
            }
            if (calls.isEmpty()) {
                record.accept(new PendingMessage(reply.turn(), displayText(reply), reply.model(), reply.stopReason(),
                        reply.usage(), List.of()));
                if (wroteNothing(reply)) {
                    return Outcome.NO_ANSWER;
                }
                String note = repairNote(reply, history, earlier);
                if (note != null) {
                    if (fits(clock.instant(), 1, leaseEnds)) {
                        repairing = note;
                        continue;
                    }
                    telemetry.repair("no_time");
                }
                return Outcome.ANSWERED;
            }
            // Tool calls that will not run: answer each, then close the turn, so the history stays well formed.
            record.accept(PendingMessage.reply(reply, List.of()));
            String why = switch (reply.stopReason()) {
                case MAX_TOKENS -> "NOT_RUN: the reply was cut off before the call was complete";
                case REFUSAL -> "NOT_RUN: the request was declined";
                default -> "NOT_RUN: no more lookups can be made for this question";
            };
            record.accept(PendingMessage.results(new Turn.ToolResults(
                    calls.stream().map(notRun -> new ToolResult(notRun.id(), why, true)).toList())));
            String stop = switch (reply.stopReason()) {
                case MAX_TOKENS, REFUSAL -> closingText(reply.stopReason());
                // The last call asked for a tool anyway: an adapter that cannot refuse tools, or a model ignoring it.
                default -> last && !roundsSpent ? outOfTime() : closingText(reply.stopReason());
            };
            record.accept(closing(model, afterReplyText(reply, stop)));
            return switch (reply.stopReason()) {
                case MAX_TOKENS, REFUSAL -> Outcome.ANSWERED;
                default -> last && !roundsSpent ? Outcome.OUT_OF_TIME : Outcome.NO_ANSWER;
            };
        }
    }

    /**
     * How one model's attempt ended. Only {@link #NO_ANSWER} is worth another model: a cut-off answer would be cut off
     * again, a declined one is not to be asked around, and an answer out of time has no time left for another.
     */
    private enum Outcome {
        /** The model had its say: an answer, one cut off at the output limit, or a refusal. */
        ANSWERED,
        /**
         * The model replied without a usable answer: empty or malformed twice, nothing left once its markup was
         * removed, or still asking for lookups when they ran out.
         */
        NO_ANSWER,
        /** The lease ran short before the model could finish. */
        OUT_OF_TIME
    }

    /**
     * A final reply with nothing to show once {@link AnswerText} removed its markup: the user reads that the assistant
     * could not answer ({@link #displayText}). A cut-off or declined one has its own message.
     */
    private static boolean wroteNothing(LanguageModel.Reply reply) {
        String shown = AnswerText.plain(reply.turn().text());
        return (shown == null || shown.isBlank())
                && reply.stopReason() != LanguageModel.StopReason.MAX_TOKENS
                && reply.stopReason() != LanguageModel.StopReason.REFUSAL;
    }

    /** Whether {@code calls} more model calls, each as long as the longest one can be, end within the lease. */
    private boolean fits(Instant now, int calls, Instant leaseEnds) {
        return !now.plus(properties.longestModelCall().multipliedBy(calls)).isAfter(leaseEnds);
    }

    /**
     * The note that asks the model, once, to correct an answer that states numbers no tool result, question or
     * context holds ({@link AnswerNumbers}, design §3.1); null when every number is grounded or the reply is not a
     * finished answer. The grounds are what this model was shown: the questions and results of the exchanges before,
     * and this question with everything its tools returned in this attempt. A number that only a code holds ("lô
     * 2401" for {@code EVAL-LOT-2401}) is named as such, so the model writes the code whole instead of dropping it.
     */
    private static String repairNote(LanguageModel.Reply reply, List<Turn> history, AnswerNumbers.Grounds earlier) {
        if (reply.stopReason() != LanguageModel.StopReason.END_TURN) {
            return null;
        }
        List<AnswerNumbers.Checked> unheld = check(AnswerText.plain(reply.turn().text()), history, earlier).stream()
                .filter(checked -> !checked.grounded()).toList();
        if (unheld.isEmpty()) {
            return null;
        }
        List<String> nowhere = unheld.stream().filter(checked -> !checked.onlyInACode())
                .map(AnswerNumbers.Checked::written).distinct().toList();
        List<String> inCodes = unheld.stream().filter(AnswerNumbers.Checked::onlyInACode)
                .map(AnswerNumbers.Checked::written).distinct().toList();
        StringBuilder note = new StringBuilder(NOTE);
        if (!nowhere.isEmpty()) {
            note.append(" these numbers in your answer appear in no tool result, nor in the question or its context: ")
                    .append(String.join("; ", nowhere)).append('.');
        }
        if (!inCodes.isEmpty()) {
            note.append(" These appear there only inside a code (an order, lot, bin or SKU code), not as figures of "
                    + "their own: ").append(String.join("; ", inCodes))
                    .append(". Write such a code whole, as the result does, or leave the number out.");
        }
        return note.append(" Write the answer again, in the user's language: keep every figure, code and rule you can "
                + "point to above, and where a figure was not looked up, say so instead of giving one. No more lookups "
                + "can be made.").toString();
    }

    /** The checked numbers of {@code text} against what the model was shown ({@link #repairNote}). */
    private static List<AnswerNumbers.Checked> check(String text, List<Turn> history, AnswerNumbers.Grounds earlier) {
        return AnswerNumbers.check(text, earlier.and(AnswerSources.groundsOf(history)));
    }

    /**
     * The model's correction. It replaces the answer only when it is better and loses nothing: written in full (no
     * tool asked for, not cut off or declined), its unverified numbers a strict subset of the answer's — so it adds no
     * new one — and every number the answer had grounds for, and every rule it cited, still in it. Anything else
     * leaves the answer as it was, stored already, its numbers flagged as before.
     *
     * <p>A correction is stored after the answer, so the transcript shows it as the answer and the first stays for the
     * audit. One set aside is stored for its tokens with no text and no tool calls, its provider content kept for the
     * audit: the transcript, the next question's history ({@link History}) and the cut-short flag all read the answer
     * the user saw, never a rewrite nobody was shown.</p>
     */
    private Outcome repaired(LanguageModel.Reply reply, Consumer<PendingMessage> record, Consumer<PendingMessage> sink,
                             List<Turn> history, AnswerNumbers.Grounds earlier, List<ToolExecution> ranInAttempt) {
        String before = history.getLast() instanceof Turn.ModelTurn answer ? AnswerText.plain(answer.text()) : null;
        String after = AnswerText.plain(reply.turn().text());
        boolean usable = before != null && reply.stopReason() == LanguageModel.StopReason.END_TURN
                && reply.turn().toolCalls().isEmpty() && !wroteNothing(reply)
                && better(check(before, history, earlier), check(after, history, earlier))
                && AnswerSources.citations(after, ranInAttempt, json)
                        .containsAll(AnswerSources.citations(before, ranInAttempt, json));
        if (usable) {
            record.accept(new PendingMessage(reply.turn(), displayText(reply), reply.model(), reply.stopReason(),
                    reply.usage(), List.of()));
            telemetry.repair(check(after, history, earlier).stream().allMatch(AnswerNumbers.Checked::grounded)
                    ? "corrected" : "still_unverified");
        } else {
            Turn.ModelTurn audit = new Turn.ModelTurn(reply.turn().providerContent(), null, List.of());
            sink.accept(new PendingMessage(audit, null, reply.model(), reply.stopReason(), reply.usage(), List.of()));
            telemetry.repair("kept");
        }
        return Outcome.ANSWERED;
    }

    /**
     * Whether a rewrite is better than the answer: fewer unverified numbers, none of them new, and every grounded
     * number of the answer still written. Compared as written: "12,5 triệu" for "12.500.000" is the same claim, not a
     * correction, and keeps the answer.
     */
    private static boolean better(List<AnswerNumbers.Checked> answer, List<AnswerNumbers.Checked> rewrite) {
        java.util.Set<String> wrong = written(answer, false);
        java.util.Set<String> stillWrong = written(rewrite, false);
        java.util.Set<String> written = new java.util.HashSet<>(written(rewrite, false));
        written.addAll(written(rewrite, true));
        return wrong.containsAll(stillWrong) && stillWrong.size() < wrong.size()
                && written.containsAll(written(answer, true));
    }

    private static java.util.Set<String> written(List<AnswerNumbers.Checked> checked, boolean grounded) {
        return checked.stream().filter(number -> number.grounded() == grounded).map(AnswerNumbers.Checked::written)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * A repair call that failed, with any exception: the question keeps the answer it had ({@link #answerOnRoute}).
     * Stackless, it only carries the failure there.
     */
    private static final class RepairFailed extends RuntimeException {

        private final RuntimeException cause;

        RepairFailed(RuntimeException cause) {
            super(cause.getMessage(), cause, false, false);
            this.cause = cause;
        }
    }

    /**
     * What the application tells the model along with this call, or null: that it must answer now, from what the
     * tools returned, and say what it could not check; and, asking again, that its previous reply was empty.
     */
    private static String note(boolean last, boolean askingAgain) {
        if (!last && !askingAgain) {
            return null;
        }
        String again = askingAgain ? " Your previous reply was empty." : "";
        String now = last ? " No more lookups can be made for this question: answer now from what the tools returned "
                + "above, and say plainly what you could not check."
                : " Answer the question, or call a tool with valid arguments.";
        return NOTE + again + now;
    }

    /**
     * A reply with neither text nor a tool call. A cut-off or declined one is not: it has its own message, and asking
     * again would only be cut off or declined again.
     */
    private static boolean empty(LanguageModel.Reply reply) {
        String text = reply.turn().text();
        return reply.turn().toolCalls().isEmpty() && (text == null || text.isBlank())
                && reply.stopReason() != LanguageModel.StopReason.MAX_TOKENS
                && reply.stopReason() != LanguageModel.StopReason.REFUSAL;
    }

    /**
     * The execution as it is stored and shown: a call to a tool that does not exist goes on record as
     * {@value #UNKNOWN_TOOL}, its result still naming what the model asked for.
     */
    private ToolExecution forTheRecord(ToolExecution execution) {
        ToolCall call = execution.call();
        if (tools.specs().stream().anyMatch(spec -> spec.name().equals(call.name()))) {
            return execution;
        }
        return new ToolExecution(new ToolCall(call.id(), UNKNOWN_TOOL, call.arguments()), execution.outcome(),
                execution.result(), execution.durationMs());
    }

    /** A closing turn in {@code model}'s own format, like every other turn of its attempt. */
    private static PendingMessage closing(LanguageModel model, String text) {
        return PendingMessage.closing(model.closingTurn(text));
    }

    /**
     * Closes a reply whose tool calls will not run: what the model wrote before them is part of the answer, and
     * {@code stop} says why it ends there. A declined reply's text is not shown: the provider declined it. The text
     * is the model's as written; the closing message makes it plain, once ({@link PendingMessage#closing}).
     */
    private static String afterReplyText(LanguageModel.Reply reply, String stop) {
        String text = reply.stopReason() == LanguageModel.StopReason.REFUSAL ? null : reply.turn().text();
        String shown = AnswerText.plain(text);
        return shown == null || shown.isBlank() ? stop : text.strip() + "\n\n" + stop;
    }

    /** What the transcript shows for a question whose answer failed: the error's message, in the user's language. */
    private String failureText(RuntimeException failure) {
        return messages.forCode(failure instanceof BusinessException refused ? refused.errorCode()
                : ErrorCode.INTERNAL_ERROR);
    }

    private String outOfTime() {
        return messages.get("assistant.outOfTime", "This answer took too long, so I stopped before finishing it; "
                + "the lookups that ran are listed with it. Please ask again, or ask about one part at a time.");
    }

    private String displayText(LanguageModel.Reply reply) {
        String text = AnswerText.plain(reply.turn().text());
        if (text != null && !text.isBlank()) {
            return text;
        }
        return switch (reply.stopReason()) {
            case REFUSAL -> messages.get("assistant.refused", "I cannot help with that request.");
            case MAX_TOKENS -> messages.get("assistant.cutShort",
                    "The answer was too long to finish. Please ask a narrower question.");
            // Text that held nothing but markup, removed by AnswerText; an empty reply never gets here.
            default -> messages.get("assistant.noAnswer",
                    "The assistant did not manage to write an answer this time. Please ask again.");
        };
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
     * byte-identical: the date, with a calendar to turn "yesterday" or "last month" into dates; who is asking — roles
     * and warehouses, never a name; and the tools they may use. The tool list comes from {@link ToolAvailability},
     * the same declarations the panel shows, so the model knows a refusal before calling the tool instead of spending
     * a request to learn it. Stored with the question, so a replay sends it unchanged.
     */
    private String context(CurrentUser user, Instant now) {
        LocalDate today = BusinessDate.of(now);
        String warehouses = user.warehouseCodes().isEmpty() ? ""
                : " (warehouses: " + String.join(", ", user.warehouseCodes().stream().sorted().toList()) + ")";
        return ("Context for this question, from the system and not written by the user: today is %s, %s, Vietnam "
                + "time. The user's roles: %s. Their data scope: %s%s.")
                .formatted(today, today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                        String.join(", ", user.roles().stream().map(Enum::name).sorted().toList()),
                        user.scope(), warehouses)
                + calendar(now) + access(availability.grantedTo(user), user);
    }

    /**
     * The periods people name, as dates: a week runs Monday to Sunday and a month from its first day to its last. A
     * period still running ends today ("so far"), since no figure exists past today and getSalesSummary refuses a "to"
     * after it; last week and last month are whole.
     */
    static String calendar(Instant now) {
        LocalDate today = BusinessDate.of(now);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        // A period still running ends today: getSalesSummary refuses a "to" after today, and a model copying the
        // calendar would otherwise spend a round on a refused call.
        return (" Calendar (Vietnam time): today %s %s, now %s; yesterday %s; this week %s to %s so far; last week %s "
                + "to %s; this month %s to %s so far; last month %s to %s.")
                .formatted(today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH), today,
                        now.atZone(BusinessDate.ZONE).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                        today.minusDays(1), monday, today, monday.minusWeeks(1), monday.minusDays(1),
                        firstOfMonth, today, firstOfMonth.minusMonths(1), firstOfMonth.minusDays(1));
    }

    /**
     * The tools the user may use, and for a user limited to warehouses what that hides: told up front, a small model
     * neither offers figures it cannot get nor reads an empty list as "nothing there".
     */
    static String access(List<String> granted, CurrentUser user) {
        String tools = granted.isEmpty() ? " Tools this user may use: none. Every tool refuses them."
                : " Tools this user may use: " + String.join(", ", granted) + ". Every other tool refuses them.";
        if (!ToolAvailability.limitedToWarehouses(user)) {
            return tools;
        }
        List<String> codes = user.warehouseCodes().stream().sorted().toList();
        return tools + (codes.isEmpty()
                ? " They are limited to warehouses but assigned to none: they see no warehouse and no company-wide "
                        + "stock figure."
                : " They are limited to warehouses " + String.join(", ", codes)
                        + ": they see no other warehouse and no company-wide stock figure.");
    }

    /**
     * One answer as the panel shows it.
     *
     * @param messageId         the stored message holding the answer
     * @param toolCalls         every tool run for it, in order
     * @param cutShort          the answer hit the output limit and may be incomplete
     * @param usage             tokens across every model call it took
     * @param citations         rule anchors the answer names that its own searches returned (design §6.3)
     * @param proposals         purchase orders the panel may open pre-filled (design §8)
     * @param tables            the rows its lookups returned, as the panel shows them under it (design §7)
     * @param unverifiedNumbers numbers it states that nothing the model was given for it holds, as written (design §7)
     * @param followUps         the questions its lookups lead to, before the asker's permissions are applied
     */
    public record Answer(UUID messageId, String text, List<ConversationService.ToolCallView> toolCalls,
                         boolean cutShort, LanguageModel.Usage usage, List<String> citations,
                         List<AnswerSources.Proposal> proposals, List<ResultTable.Table> tables,
                         List<String> unverifiedNumbers, List<FollowUps.FollowUp> followUps,
                         java.util.Map<String, AssistantActions.ActionView> actions) {

        public Answer {
            actions = actions == null ? java.util.Map.of() : java.util.Map.copyOf(actions);
        }

        /** With the suggestions its proposals became (ADR-0011), by proposal key. */
        Answer withActions(java.util.Map<String, AssistantActions.ActionView> made) {
            return new Answer(messageId, text, toolCalls, cutShort, usage, citations, proposals, tables,
                    unverifiedNumbers, followUps, made);
        }

        /**
         * Read as the transcript reads it ({@link ConversationService.TranscriptEntry#answer}), plus what it cost.
         *
         * @param asked  the conversation's stored turns, ending with the question answered: what its numbers are
         *               checked against, as the transcript checks them
         * @param stored the turns the answer produced
         */
        static Answer of(List<Turn> asked, List<Message> stored, ObjectMapper json) {
            ConversationService.TranscriptEntry answer = ConversationService.TranscriptEntry
                    .answer(asked, stored, json)
                    .orElseThrow(() -> new IllegalStateException(
                            "an answer always ends with an assistant turn holding text"));
            long input = 0;
            long output = 0;
            long cacheRead = 0;
            long cacheWrite = 0;
            for (Message message : stored) {
                input += message.usage().inputTokens();
                output += message.usage().outputTokens();
                cacheRead += message.usage().cacheReadTokens();
                cacheWrite += message.usage().cacheWriteTokens();
            }
            return new Answer(answer.messageId(), answer.text(), answer.toolCalls(), answer.cutShort(),
                    new LanguageModel.Usage(input, output, cacheRead, cacheWrite), answer.citations(),
                    answer.proposals(), answer.tables(), answer.unverifiedNumbers(), answer.followUps(),
                    java.util.Map.of());
        }
    }
}
