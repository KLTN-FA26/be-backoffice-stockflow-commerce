package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The models a question may be answered on, cheapest first, and which of them have used up their quota
 * ({@link ModelRoutingProperties}, design §11). {@link AssistantService} takes each question along a {@link Route}.
 *
 * <p><b>No dead day.</b> A model whose quota is spent is set aside until the quota is expected back —
 * {@code quota-cooldown}, or by default the end of its provider's quota day — so the next questions start on the next
 * model instead of each one spending a refused call to learn it again. Gemini's free tier counts its daily quotas per
 * model and restarts them at midnight Pacific time, which is why a second model is a second day's worth of questions.
 * Anthropic has no daily quota: what its adapter reports as spent is a wait of more than a minute, and its day is
 * taken to end at midnight UTC; with several Claude models, a {@code quota-cooldown} of an hour or so fits better.</p>
 *
 * <p><b>When every model is set aside, every one is tried again.</b> A cooldown is a guess: a quota can come back
 * earlier, or an operator can raise it. A refused call costs nothing and fails at once, so a question then goes to each
 * model, cheapest first; the first one that replies is no longer set aside, and only a question that every model
 * refused gets {@code ASSISTANT_QUOTA_EXHAUSTED}.</p>
 *
 * <p><b>In memory, per instance.</b> A restart, or another instance, learns a spent quota again from one refused call;
 * that is not worth a table.</p>
 *
 * <p><b>Every call is logged with its model</b>: a reply as configured and as the provider names it, with its tokens,
 * and a failure with its error code. The log says which model answered what, as the {@code model} column of
 * {@code assistant.message} does.</p>
 */
class ModelRouter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    /** Where Gemini's daily quotas restart: midnight Pacific time (Google's rate-limit documentation, October 2026). */
    static final ZoneId GEMINI_QUOTA_DAY = ModelProvider.GEMINI_QUOTA_DAY;

    /**
     * One model of the list.
     *
     * @param name     the model as configured ({@link ModelCatalog}), the key of its quota bookkeeping
     * @param model    its adapter
     * @param quotaDay where its vendor's quota day ends at midnight; null for the router's own
     */
    record Candidate(String name, LanguageModel model, ZoneId quotaDay, String provider, String modelId) {

        Candidate {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(model, "model");
            provider = provider == null ? "unknown" : provider;
            modelId = modelId == null ? name : modelId;
        }

        /** A model of {@code spec}'s vendor, on that vendor's quota day. */
        Candidate(ModelCatalog.ModelSpec spec, LanguageModel model) {
            this(spec.name(), model, spec.provider().quotaDay(), spec.provider().id(), spec.model());
        }

        /** A model on {@code quotaDay}, its vendor unnamed in the traces. */
        Candidate(String name, LanguageModel model, ZoneId quotaDay) {
            this(name, model, quotaDay, null, null);
        }

        /** A model on the router's own quota day, as every model was when a route had one vendor. */
        Candidate(String name, LanguageModel model) {
            this(name, model, null);
        }
    }

    private final List<Candidate> candidates;
    private final ModelRoutingProperties routing;
    private final ZoneId quotaDay;
    private final Clock clock;
    private final List<AutoCloseable> owned;
    private final AssistantTelemetry telemetry;
    private final Map<String, Instant> spentUntil = new ConcurrentHashMap<>();

    /**
     * @param candidates cheapest first; a route may mix vendors, each candidate with its own quota day
     * @param quotaDay   where the quota day ends at midnight for a candidate that names none
     *                   ({@link #quotaDay(String)})
     * @param owned      adapters built for the route alone, closed with it; never the {@code LanguageModel} bean,
     *                   which the application context closes itself
     */
    ModelRouter(List<Candidate> candidates, ModelRoutingProperties routing, ZoneId quotaDay, Clock clock,
                List<AutoCloseable> owned) {
        this(candidates, routing, quotaDay, clock, owned, AssistantTelemetry.NONE);
    }

    /** @param telemetry where each model call's span and each move of the route are recorded */
    ModelRouter(List<Candidate> candidates, ModelRoutingProperties routing, ZoneId quotaDay, Clock clock,
                List<AutoCloseable> owned, AssistantTelemetry telemetry) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("a route holds at least one model");
        }
        this.telemetry = telemetry;
        this.candidates = candidates.stream()
                .map(candidate -> new Candidate(candidate.name(), new Logged(candidate, telemetry),
                        candidate.quotaDay() == null ? quotaDay : candidate.quotaDay(), candidate.provider(),
                        candidate.modelId()))
                .toList();
        this.routing = routing;
        this.quotaDay = quotaDay;
        this.clock = clock;
        this.owned = List.copyOf(owned);
    }

    ModelRouter(List<Candidate> candidates, ModelRoutingProperties routing, ZoneId quotaDay, Clock clock) {
        this(candidates, routing, quotaDay, clock, List.of());
    }

    /** Where a provider's quota day ends at midnight ({@link ModelProvider#quotaDay()}); UTC for an unknown name. */
    static ZoneId quotaDay(String provider) {
        ModelProvider known = ModelProvider.named(provider);
        return known == null ? ZoneOffset.UTC : known.quotaDay();
    }

    /**
     * Whether the assistant can answer at all. The route holds real adapters only when the assistant's own settings
     * let it answer (AssistantModelConfig), and its own model alone otherwise, so the first model says for all.
     */
    boolean available() {
        return candidates.getFirst().model().available();
    }

    /** The models as configured, cheapest first. */
    List<String> names() {
        return candidates.stream().map(Candidate::name).toList();
    }

    /**
     * A new question's way along the models: those not set aside, cheapest first; every one of them when all are,
     * since a cooldown is only a guess (class javadoc).
     */
    Route route() {
        Instant now = clock.instant();
        List<Candidate> ready = candidates.stream().filter(candidate -> !setAside(candidate, now)).toList();
        return ready.isEmpty() ? new Route(candidates, true) : new Route(ready, false);
    }

    private boolean setAside(Candidate candidate, Instant now) {
        Instant until = spentUntil.get(candidate.name());
        return until != null && now.isBefore(until);
    }

    /** Sets a model whose quota is spent aside, for every question, until the quota is expected back. */
    private void quotaSpent(Candidate candidate) {
        Instant until = renewal(clock.instant(), candidate.quotaDay());
        spentUntil.merge(candidate.name(), until, (known, found) -> known.isAfter(found) ? known : found);
        log.warn("The quota of model {} is used up: questions skip it until {}", candidate.name(), until);
    }

    /**
     * What the user is told of a failure that ends a question. A spent quota is reported as one only when every
     * model's is: while one is left, asking again goes to it, so the user hears that the assistant cannot answer for
     * the moment ({@code ASSISTANT_UNAVAILABLE}), not that asking again will not work.
     */
    private BusinessException told(BusinessException failure) {
        Instant now = clock.instant();
        if (failure.errorCode() == ErrorCode.ASSISTANT_QUOTA_EXHAUSTED
                && candidates.stream().anyMatch(candidate -> !setAside(candidate, now))) {
            return new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE,
                    "This model's quota is used up; another model takes the next question", failure);
        }
        return failure;
    }

    /**
     * When a quota found spent at {@code now} is expected back, on the router's own quota day: after the cooldown, or
     * when the quota day ends.
     */
    Instant renewal(Instant now) {
        return renewal(now, quotaDay);
    }

    private Instant renewal(Instant now, ZoneId day) {
        Duration cooldown = routing.quotaCooldown();
        if (cooldown != null) {
            return now.plus(cooldown);
        }
        return now.atZone(day).toLocalDate().plusDays(1).atStartOfDay(day).toInstant();
    }

    /** Closes the adapters built for the route; the first failure is thrown, with any later one suppressed in it. */
    @Override
    public void close() throws Exception {
        Exception failed = null;
        for (AutoCloseable adapter : owned) {
            try {
                adapter.close();
            } catch (Exception closing) {
                if (failed == null) {
                    failed = closing;
                } else {
                    failed.addSuppressed(closing);
                }
            }
        }
        if (failed != null) {
            throw failed;
        }
    }

    /**
     * One question's way along the models: where it is, what failed on the way, and whether it was escalated. Used by
     * the thread answering that question, and by no other.
     */
    final class Route {

        private final List<Candidate> models;
        private final boolean everyOneSetAside;
        private final List<BusinessException> failures = new ArrayList<>();
        private int at;
        private boolean escalated;

        private Route(List<Candidate> models, boolean everyOneSetAside) {
            this.models = models;
            this.everyOneSetAside = everyOneSetAside;
        }

        /** The model the question is on now. */
        LanguageModel model() {
            return models.get(at).model();
        }

        /** Its name as configured. */
        String name() {
            return models.get(at).name();
        }

        /**
         * The model failed before it replied to this question. A spent quota sets it aside, for every question, until
         * the quota is expected back: the next questions start on the next model.
         */
        void failedBeforeReplying(BusinessException failure) {
            failures.add(failure);
            if (failure.errorCode() == ErrorCode.ASSISTANT_QUOTA_EXHAUSTED) {
                quotaSpent(models.get(at));
            }
        }

        /**
         * The model failed after it had replied: the question ends with it, since the turns it wrote are valid for no
         * other model. A spent quota still sets it aside, so the next question starts on the next model.
         *
         * @return what the user is told: a spent quota only when every model's is
         */
        BusinessException failedAfterReplying(BusinessException failure) {
            if (failure.errorCode() == ErrorCode.ASSISTANT_QUOTA_EXHAUSTED) {
                quotaSpent(models.get(at));
            }
            return told(failure);
        }

        /**
         * Moves a question its model failed before replying to on to the next model. A spent quota and a provider
         * that could not be reached after the adapter's own retries are worth it: another model has a quota of its own
         * and may be served by other machines. So is a refused request: a model name mistyped in the list is refused
         * for that model alone, and would otherwise stop every question that starts on it. A refused key fails on every
         * model, fast and at no cost, and the user is still told the assistant is misconfigured ({@link #failure}).
         *
         * @return false when the failure is not one of those, or no model is left
         */
        boolean fallBack(BusinessException failure) {
            boolean anotherMayReply = failure.errorCode() == ErrorCode.ASSISTANT_QUOTA_EXHAUSTED
                    || failure.errorCode() == ErrorCode.ASSISTANT_UNAVAILABLE
                    || failure.errorCode() == ErrorCode.ASSISTANT_MISCONFIGURED;
            if (!anotherMayReply || at + 1 >= models.size()) {
                return false;
            }
            String from = name();
            at++;
            log.warn("Model {} failed before replying ({}): the question goes to {}", from, failure.errorCode(),
                    name());
            telemetry.routeEvent("fallback", from);
            return true;
        }

        /**
         * The model replied but gave no usable answer: the question goes to the next model, once, to be answered
         * again from the start.
         *
         * @return false when escalation is switched off, was used already, or no model is left
         */
        boolean escalate() {
            if (!routing.escalateOnFailure() || escalated || at + 1 >= models.size()) {
                return false;
            }
            String from = name();
            escalated = true;
            at++;
            log.info("Model {} gave no usable answer: the question is answered again on {}", from, name());
            telemetry.routeEvent("escalation", from);
            return true;
        }

        /** Whether the question was escalated: what the first model's answer closed with stands if this one fails. */
        boolean escalated() {
            return escalated;
        }

        /** The model replied: if it had been set aside with every other one, its quota is back. */
        void replied() {
            if (everyOneSetAside) {
                spentUntil.remove(name());
            }
        }

        /**
         * What the user is told when no model replied: the last failure that was not a spent quota, and a spent quota
         * only when every model's is — not when a model was left untried because the lease could not hold its call.
         */
        BusinessException failure() {
            BusinessException last = failures.getLast();
            for (BusinessException failure : failures) {
                if (failure.errorCode() != ErrorCode.ASSISTANT_QUOTA_EXHAUSTED) {
                    last = failure;
                }
            }
            return told(last);
        }
    }

    /**
     * A model whose every call is logged with the model it went to (class javadoc), and traced as a {@code chat} span
     * ({@link AssistantTelemetry#chat}). A failed call is logged too: the adapter's own log line has the provider's
     * words, but not always the model.
     */
    private record Logged(String name, LanguageModel model, String provider, String modelId,
                          AssistantTelemetry telemetry) implements LanguageModel {

        Logged(Candidate candidate, AssistantTelemetry telemetry) {
            this(candidate.name(), candidate.model(), candidate.provider(), candidate.modelId(), telemetry);
        }

        @Override
        public boolean available() {
            return model.available();
        }

        @Override
        public Reply next(Request request) {
            Reply reply;
            try {
                reply = telemetry.chat(name, provider, modelId, () -> model.next(request));
            } catch (BusinessException failed) {
                log.info("Assistant model call to {} failed: {}", name, failed.errorCode());
                throw failed;
            }
            Usage usage = reply.usage();
            log.info("Assistant model call to {} (answered by {}): {}, {} input tokens, {} cached, {} output tokens",
                    name, reply.model(), reply.stopReason(), usage.inputTokens(), usage.cacheReadTokens(),
                    usage.outputTokens());
            return reply;
        }

        @Override
        public Turn.ModelTurn closingTurn(String text) {
            return model.closingTurn(text);
        }
    }
}
