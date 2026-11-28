package com.stockflow.assistant.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.ListUsersQuery;
import com.stockflow.identity.api.StaffUser;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the assistant's watch (design §9.1) for every active back-office user, one after another, on the instance that
 * holds the lock.
 *
 * <p><b>Off unless asked for</b> ({@code stockflow.assistant.watch.enabled}), and never with security off: there no
 * request carries a data scope, so a watch answer could not reach its own conversation, and nobody's grants would be
 * what the watch reads by. The default schedule is every fifteen minutes in business hours, Monday to Saturday,
 * business time: the lists change by the hour, and a look at night has nobody to tell.</p>
 *
 * <p><b>One user's failure is one user's.</b> Each look is caught and logged; the run goes on to the next user. A user
 * the watch may not act for (disabled, locked, a portal account) is skipped by {@code RunAs} before anything is read.
 * The users are listed through {@code identity :: api}, page by page; it leaves out every account that holds the
 * CUSTOMER role, a staff account that also holds it included.</p>
 *
 * <p><b>Bounded.</b> No answer starts that could end after {@link AssistantWatchProperties#runBudget} (30 minutes by
 * default) from the run's start, each answer being bounded by the answer lease. Reading and posting go on to the end;
 * they take a few database reads per user, so the run stays well inside the lock's 55 minutes for the company's few
 * hundred users at most, and the next run does not start on another instance while it lasts. On this instance the
 * scheduler never starts a run while the last is going on.</p>
 *
 * <p>Users are visited in a new order each run, so the run's few answers do not always go to the same users.</p>
 */
@Component
@ConditionalOnExpression("${stockflow.assistant.watch.enabled:false} and ${stockflow.security.enabled:true}")
class AssistantWatchJob {

    private static final Logger log = LoggerFactory.getLogger(AssistantWatchJob.class);

    static final int PAGE = 100;

    private final AssistantWatch watch;
    private final IdentityService identity;
    private final AssistantWatchProperties properties;
    private final Clock clock;

    AssistantWatchJob(AssistantWatch watch, IdentityService identity, AssistantWatchProperties properties,
                      Clock clock) {
        this.watch = watch;
        this.identity = identity;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${stockflow.assistant.watch.cron:0 */15 7-19 * * MON-SAT}", zone = BusinessCalendar.ZONE_NAME)
    @SchedulerLock(name = "assistant-watch", lockAtMostFor = "PT55M", lockAtLeastFor = "PT1M")
    public void lookForEveryone() {
        if (properties.sections().isEmpty()) {
            log.warn("Assistant watch is on, but neither stockflow.assistant.watch.post nor .analyse names a list");
            return;
        }
        AssistantWatch.Budget answers = new AssistantWatch.Budget(properties.analysesPerRun(),
                clock.instant().plus(properties.runBudget()));
        Map<AssistantWatch.Outcome, Integer> outcomes = new EnumMap<>(AssistantWatch.Outcome.class);
        int failed = 0;
        int newItems = 0;
        int posted = 0;
        int answered = 0;
        // Listed first, then visited in a new order each run: the run's answers are few, and the same users must not
        // always come first and spend them.
        List<java.util.UUID> everyone = new ArrayList<>();
        int page = 0;
        PageResponse<StaffUser> users;
        do {
            users = identity.listUsers(new ListUsersQuery(null, "ACTIVE", null, false, page, PAGE, null));
            users.items().forEach(user -> everyone.add(user.userId()));
            page++;
        } while (page < users.totalPages());
        Collections.shuffle(everyone);
        for (java.util.UUID user : everyone) {
            try {
                AssistantWatch.Look look = watch.look(user, answers);
                outcomes.merge(look.outcome(), 1, Integer::sum);
                newItems += look.newItems();
                posted += look.posted();
                answered += look.answered();
            } catch (RuntimeException failure) {
                failed++;
                log.error("Assistant watch failed for user {}", user, failure);
            }
        }
        log.info("Assistant watch looked: {}, {} failed; {} new item(s), {} message(s) posted, {} answered",
                outcomes, failed, newItems, posted, answered);
    }
}
