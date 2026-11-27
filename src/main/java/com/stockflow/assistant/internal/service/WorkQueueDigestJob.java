package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RunAs;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.ListUsersQuery;
import com.stockflow.identity.api.StaffUser;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The morning digest of the work queue (contract 3.1): one inbox message per staff user, at the configured time, with
 * the count of each of their sections that has something waiting - so the day starts from the bell, not from five
 * screens. A user with nothing waiting gets nothing: a daily "0, 0, 0" teaches people to ignore the bell.
 *
 * <p><b>Off unless asked for</b> ({@code stockflow.assistant.digest.enabled}), and never with security off, for the
 * reason the watch is not ({@link AssistantWatchJob}): nobody's grants would be what the queue is read by. Default
 * 07:30 Monday to Saturday, business time ({@code stockflow.assistant.digest.cron}).</p>
 *
 * <p><b>As the user, never as the system.</b> Each queue is read inside {@link RunAs}, with the user's roles, grants
 * and warehouses as stored now, exactly as their own request to {@code GET /work-queue} reads it: a warehouse manager
 * is told about their warehouses' batches, never the company's. A user who may not use the assistant
 * ({@code assistant-conversations:READ}, the widget's permission) is skipped; {@code RunAs} refuses a disabled, locked
 * or portal account before anything is read. Acting for a user outside a request is reviewed per caller (ADR-0016):
 * {@code ArchitectureTest.onlyTheAssistantsWatchActsForAUser} names this job beside the watch.</p>
 *
 * <p><b>Once per user per day</b>: the message's dedupe key is the business day, and the inbox posts a key to a
 * recipient once, so a second instance, a retry or a re-run tells nobody twice. Only system text: section titles and
 * counts, never a name or anything typed ({@link Inbox}).</p>
 *
 * <p><b>One user's failure is one user's</b>: caught, logged, and the run goes on.</p>
 */
@Component
@ConditionalOnExpression("${stockflow.assistant.digest.enabled:false} and ${stockflow.security.enabled:true}")
class WorkQueueDigestJob {

    private static final Logger log = LoggerFactory.getLogger(WorkQueueDigestJob.class);

    static final int PAGE = 100;

    /** Who acts for the user, in the token's {@code act} claim and the audit columns. */
    static final String ACTOR = "assistant-digest";

    /** The inbox category, as the other posters name theirs. */
    static final String CATEGORY = "ASSISTANT_DIGEST";

    /** Where the message opens: the dashboard, which shows the queue itself. */
    static final String LINK = WorkLinks.DASHBOARD;

    private final WorkQueue queue;
    private final RunAs runAs;
    private final IdentityService identity;
    private final Inbox inbox;
    private final Messages messages;
    private final Clock clock;

    WorkQueueDigestJob(WorkQueue queue, RunAs runAs, IdentityService identity, Inbox inbox, Messages messages,
                       Clock clock) {
        this.queue = queue;
        this.runAs = runAs;
        this.identity = identity;
        this.inbox = inbox;
        this.messages = messages;
        this.clock = clock;
    }

    @Scheduled(cron = "${stockflow.assistant.digest.cron:0 30 7 * * MON-SAT}", zone = BusinessCalendar.ZONE_NAME)
    @SchedulerLock(name = "assistant-work-queue-digest", lockAtMostFor = "PT55M", lockAtLeastFor = "PT1M")
    public void postEveryone() {
        LocalDate today = BusinessDate.of(clock.instant());
        List<UUID> everyone = new ArrayList<>();
        int page = 0;
        PageResponse<StaffUser> users;
        do {
            users = identity.listUsers(new ListUsersQuery(null, "ACTIVE", null, false, page, PAGE, null));
            users.items().forEach(user -> everyone.add(user.userId()));
            page++;
        } while (page < users.totalPages());
        int posted = 0;
        int failed = 0;
        for (UUID userId : everyone) {
            try {
                posted += runAs.call(userId, ACTOR, user -> digestFor(user, today));
            } catch (RunAs.RefusedException refused) {
                log.debug("Assistant digest skipped user {}: {}", userId, refused.refusal());
            } catch (RuntimeException failure) {
                failed++;
                log.error("Assistant digest failed for user {}", userId, failure);
            }
        }
        log.info("Assistant work queue digest {}: {} user(s), {} message(s) posted, {} failed", today,
                everyone.size(), posted, failed);
    }

    /** @return 1 when a message was newly posted to the user, else 0 */
    int digestFor(CurrentUser user, LocalDate today) {
        if (!user.can(ToolPermissions.ASSISTANT, Action.READ)) {
            return 0;
        }
        WorkQueue.Queue read = queue.of(user, null);
        InboxMessage message = message(read, today);
        return message == null ? 0 : inbox.post(List.of(user.userId()), message);
    }

    /** The message for one queue; null when nothing waits in it. */
    InboxMessage message(WorkQueue.Queue read, LocalDate today) {
        List<WorkQueue.Section> waiting = read.sections().stream()
                .filter(section -> !section.failed() && section.count() != null && section.count() > 0).toList();
        if (waiting.isEmpty()) {
            return null;
        }
        boolean critical = waiting.stream().anyMatch(section -> section.severity() == WorkQueue.Severity.CRITICAL);
        StringBuilder body = new StringBuilder();
        for (WorkQueue.Section section : waiting) {
            String line = "- " + section.title() + ": " + section.count();
            if (body.length() + line.length() + 1 > InboxMessage.BODY_MAX) {
                break;
            }
            body.append(body.isEmpty() ? "" : "\n").append(line);
        }
        String title = messages.get("assistant.digest.title", "Work waiting today ({0})", today.toString());
        return new InboxMessage(CATEGORY, critical ? InboxSeverity.WARN : InboxSeverity.INFO,
                title.length() > InboxMessage.TITLE_MAX ? title.substring(0, InboxMessage.TITLE_MAX) : title,
                body.toString(), LINK, "WORK_QUEUE", today.toString(), "assistant:work-queue:" + today);
    }
}
