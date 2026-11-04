package com.stockflow.assistant.internal.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which model a question goes to, which model it moves on to, and when a model whose quota was spent is asked again
 * (design §11). {@code AssistantServiceTest} drives the same route through whole answers.
 */
class ModelRouterTest {

    /** 19:00 on 24/10 in Los Angeles, daylight time: Gemini's quota day ends five hours later, 07:00 UTC. */
    private static final Instant SUMMER = Instant.parse("2026-10-25T02:00:00Z");

    private final SettableClock clock = new SettableClock(SUMMER);
    private final LanguageModel lite = mock(LanguageModel.class);
    private final LanguageModel flash = mock(LanguageModel.class);
    private final LanguageModel pro = mock(LanguageModel.class);
    private final Logger routerLog = (Logger) LoggerFactory.getLogger(ModelRouter.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        previousLevel = routerLog.getLevel();
        routerLog.setLevel(Level.INFO);
        logged.start();
        routerLog.addAppender(logged);
    }

    @AfterEach
    void releaseLogs() {
        routerLog.detachAppender(logged);
        routerLog.setLevel(previousLevel);
    }

    private ModelRouter router(ModelRoutingProperties routing, ZoneId quotaDay) {
        return new ModelRouter(List.of(new ModelRouter.Candidate("gemini-3.5-flash-lite", lite),
                new ModelRouter.Candidate("gemini-3.5-flash", flash), new ModelRouter.Candidate("gemini-3.5-pro", pro)),
                routing, quotaDay, clock);
    }

    private ModelRouter router() {
        return router(new ModelRoutingProperties(List.of(), true, null), ModelRouter.quotaDay("gemini"));
    }

    private static BusinessException failure(ErrorCode code) {
        return new BusinessException(code, code.name());
    }

    @Test
    @DisplayName("a spent quota is expected back when the provider's quota day ends: midnight Pacific for Gemini, "
            + "daylight saving time or not, midnight UTC for Anthropic; or after quota-cooldown when one is set")
    void renewal() {
        ModelRouter gemini = router();
        assertThat(gemini.renewal(SUMMER)).isEqualTo(Instant.parse("2026-10-25T07:00:00Z"));
        // 01:00 on 10/12 in Los Angeles, standard time (UTC-8): the day ends at 08:00 UTC on 11/12.
        assertThat(gemini.renewal(Instant.parse("2026-12-10T09:00:00Z")))
                .isEqualTo(Instant.parse("2026-12-11T08:00:00Z"));

        assertThat(router(new ModelRoutingProperties(List.of(), true, null), ModelRouter.quotaDay("anthropic"))
                .renewal(SUMMER)).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
        assertThat(ModelRouter.quotaDay("anthropic")).isEqualTo(ZoneOffset.UTC);

        assertThat(router(new ModelRoutingProperties(List.of(), true, Duration.ofHours(1)),
                ModelRouter.quotaDay("gemini")).renewal(SUMMER)).isEqualTo(SUMMER.plus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("a model whose quota is spent is set aside for every question until it is expected back; an "
            + "unreachable one is not set aside at all")
    void setAside() {
        ModelRouter router = router();
        ModelRouter.Route first = router.route();
        assertThat(first.name()).isEqualTo("gemini-3.5-flash-lite");

        first.failedBeforeReplying(failure(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        assertThat(first.fallBack(failure(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED))).isTrue();
        assertThat(first.name()).isEqualTo("gemini-3.5-flash");
        first.failedBeforeReplying(failure(ErrorCode.ASSISTANT_UNAVAILABLE));

        assertThat(router.route().name()).as("set aside: the next question starts on the next model")
                .isEqualTo("gemini-3.5-flash");
        clock.set(Instant.parse("2026-10-25T06:59:59Z"));
        assertThat(router.route().name()).isEqualTo("gemini-3.5-flash");
        clock.set(Instant.parse("2026-10-25T07:00:00Z"));
        assertThat(router.route().name()).as("a new quota day").isEqualTo("gemini-3.5-flash-lite");
    }

    @Test
    @DisplayName("when every model is set aside, a question goes to each of them again, cheapest first, and the first "
            + "that replies is no longer set aside")
    void everyOneSetAside() {
        ModelRouter router = router();
        ModelRouter.Route spending = router.route();
        for (int i = 0; i < 3; i++) {
            spending.failedBeforeReplying(failure(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
            spending.fallBack(failure(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        }
        assertThat(spending.failure().errorCode()).as("every model refused").isEqualTo(
                ErrorCode.ASSISTANT_QUOTA_EXHAUSTED);

        ModelRouter.Route again = router.route();
        assertThat(again.name()).isEqualTo("gemini-3.5-flash-lite");
        again.failedBeforeReplying(failure(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        assertThat(again.fallBack(failure(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED))).isTrue();
        again.replied();

        assertThat(router.route().name()).as("its quota is back").isEqualTo("gemini-3.5-flash");
    }

    @Test
    @DisplayName("a spent quota, an unreachable provider and a refused request move a question on; an assistant switched "
            + "off, or the last model, end it")
    void fallBack() {
        ModelRouter.Route route = router().route();
        assertThat(route.fallBack(failure(ErrorCode.ASSISTANT_DISABLED))).isFalse();
        assertThat(route.name()).isEqualTo("gemini-3.5-flash-lite");

        // A refused request: a mistyped model name is refused for that model only.
        assertThat(route.fallBack(failure(ErrorCode.ASSISTANT_MISCONFIGURED))).isTrue();
        assertThat(route.fallBack(failure(ErrorCode.ASSISTANT_UNAVAILABLE))).isTrue();
        assertThat(route.name()).isEqualTo("gemini-3.5-pro");
        assertThat(route.fallBack(failure(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED))).isFalse();
    }

    @Test
    @DisplayName("the user hears of a spent quota only when every model they were routed to had spent its own")
    void failureTold() {
        ModelRouter.Route quotaThenDown = router().route();
        quotaThenDown.failedBeforeReplying(failure(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        quotaThenDown.fallBack(failure(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        BusinessException down = failure(ErrorCode.ASSISTANT_UNAVAILABLE);
        quotaThenDown.failedBeforeReplying(down);
        assertThat(quotaThenDown.failure()).isSameAs(down);

        ModelRouter.Route downThenQuota = router().route();
        BusinessException unreachable = failure(ErrorCode.ASSISTANT_UNAVAILABLE);
        downThenQuota.failedBeforeReplying(unreachable);
        downThenQuota.fallBack(unreachable);
        downThenQuota.failedBeforeReplying(failure(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        assertThat(downThenQuota.failure()).isSameAs(unreachable);

        // The lease left no room to try the next model: asking again goes to it, so its quota is not reported spent.
        ModelRouter.Route untried = router().route();
        untried.failedBeforeReplying(failure(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        assertThat(untried.failure().errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);

        ModelRouter.Route misconfigured = router().route();
        BusinessException refused = failure(ErrorCode.ASSISTANT_MISCONFIGURED);
        misconfigured.failedBeforeReplying(refused);
        assertThat(misconfigured.failure()).isSameAs(refused);
    }

    @Test
    @DisplayName("a quota spent after the model replied still sets it aside, and is a moment's unavailability while "
            + "another model has a quota; a spent quota only once every model's is")
    void quotaSpentAfterReplying() {
        ModelRouter router = router();
        BusinessException spent = failure(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED);

        BusinessException told = router.route().failedAfterReplying(spent);

        assertThat(told.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
        assertThat(told.getCause()).isSameAs(spent);
        BusinessException down = failure(ErrorCode.ASSISTANT_UNAVAILABLE);
        assertThat(router.route().failedAfterReplying(down)).as("nothing set aside").isSameAs(down);

        ModelRouter.Route lastOnes = router.route();
        assertThat(lastOnes.name()).isEqualTo("gemini-3.5-flash");
        lastOnes.failedBeforeReplying(spent);
        assertThat(lastOnes.fallBack(spent)).isTrue();
        assertThat(lastOnes.failedAfterReplying(spent)).as("every model is spent").isSameAs(spent);
    }

    @Test
    @DisplayName("a question is escalated once, to the next model, and never when escalation is off")
    void escalation() {
        ModelRouter.Route route = router().route();
        assertThat(route.escalated()).isFalse();
        assertThat(route.escalate()).isTrue();
        assertThat(route.name()).isEqualTo("gemini-3.5-flash");
        assertThat(route.escalated()).isTrue();
        assertThat(route.escalate()).as("once").isFalse();
        assertThat(route.name()).isEqualTo("gemini-3.5-flash");

        ModelRouter.Route off = router(new ModelRoutingProperties(List.of(), false, null),
                ModelRouter.quotaDay("gemini")).route();
        assertThat(off.escalate()).isFalse();
        assertThat(off.name()).isEqualTo("gemini-3.5-flash-lite");

        ModelRouter single = new ModelRouter(List.of(new ModelRouter.Candidate("claude-opus-5-5", lite)),
                new ModelRoutingProperties(List.of(), true, null), ZoneOffset.UTC, clock);
        assertThat(single.route().escalate()).as("no model left").isFalse();
    }

    @Test
    @DisplayName("every call is logged with the model it went to: a reply with the model the provider named and its "
            + "tokens, a failure with its code; the assistant is available when its first model is")
    void everyCallIsLoggedWithItsModel() {
        LanguageModel.Reply reply = new LanguageModel.Reply(new Turn.ModelTurn("{}", "Còn 40 cái.", List.of()),
                LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(1200, 40, 900, 0), "gemini-3.5-flash-001");
        LanguageModel.Request request = new LanguageModel.Request("prompt", List.of(),
                List.of(new Turn.Question("Còn bao nhiêu?", "ctx")));
        when(lite.next(request)).thenReturn(reply);
        when(lite.available()).thenReturn(true);
        when(flash.next(request)).thenThrow(failure(ErrorCode.ASSISTANT_UNAVAILABLE));
        ModelRouter router = router();

        assertThat(router.available()).isTrue();
        ModelRouter.Route route = router.route();
        assertThat(route.model().next(request)).isSameAs(reply);
        assertThat(logged.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .contains("gemini-3.5-flash-lite", "gemini-3.5-flash-001", "END_TURN", "1200", "900", "40"));

        route.escalate();
        logged.list.clear();
        assertThatThrownBy(() -> route.model().next(request)).isInstanceOf(BusinessException.class);
        assertThat(logged.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .contains("gemini-3.5-flash", "ASSISTANT_UNAVAILABLE"));
        assertThat(router.names()).containsExactly("gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-3.5-pro");
    }

    @Test
    @DisplayName("a closing turn is made by the model the question is on")
    void closingTurnOfTheModel() {
        Turn.ModelTurn closing = new Turn.ModelTurn("{\"by\":\"flash\"}", "x", List.of());
        when(flash.closingTurn("x")).thenReturn(closing);
        ModelRouter.Route route = router().route();
        route.fallBack(failure(ErrorCode.ASSISTANT_UNAVAILABLE));

        assertThat(route.model().closingTurn("x")).isSameAs(closing);
        verify(flash).closingTurn("x");
    }

    @Test
    @DisplayName("closing the route closes the adapters built for it, and those only")
    void closesWhatItBuilt() throws Exception {
        List<String> closed = new ArrayList<>();
        AutoCloseable built = () -> closed.add("built");
        new ModelRouter(List.of(new ModelRouter.Candidate("claude-opus-5-5", lite)),
                new ModelRoutingProperties(List.of(), true, null), ZoneOffset.UTC, clock, List.of(built)).close();

        assertThat(closed).containsExactly("built");
    }

    /** A clock the test sets. */
    private static final class SettableClock extends Clock {

        private Instant now;

        SettableClock(Instant now) {
            this.now = now;
        }

        void set(Instant at) {
            now = at;
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
}
