package com.stockflow.chat.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The chat's timed jobs (kltn-docs 16 §5, BR-09), each on one instance at a time ({@code @SchedulerLock}) and each in
 * bounded batches: the first-response alert, auto-close of silent conversations, and closing after the reopen window.
 * Every conversation is its own transaction ({@link ChatLifecycle}): one that fails is logged and left for the next
 * run, the others go on.
 */
@Component
class ChatLifecycleJobs {

    private static final Logger log = LoggerFactory.getLogger(ChatLifecycleJobs.class);

    private final ChatLifecycle lifecycle;

    ChatLifecycleJobs(ChatLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelayString = "${stockflow.chat.sla-job-interval:PT1M}")
    @SchedulerLock(name = "chat.alertFirstResponses", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void alertFirstResponses() {
        run("first response overdue", lifecycle.overdueFirstResponses(), lifecycle::alertFirstResponse);
    }

    @Scheduled(fixedDelayString = "${stockflow.chat.sla-job-interval:PT1M}")
    @SchedulerLock(name = "chat.autoCloseSilent", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void autoCloseSilent() {
        run("auto-closed", lifecycle.silentCustomers(), lifecycle::autoClose);
    }

    @Scheduled(fixedDelayString = "${stockflow.chat.sla-job-interval:PT1M}")
    @SchedulerLock(name = "chat.closeAfterReopenWindow", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void closeAfterReopenWindow() {
        run("closed", lifecycle.pastReopenWindow(), lifecycle::close);
    }

    private static void run(String what, List<UUID> conversations, Predicate<UUID> step) {
        int done = 0;
        for (UUID id : conversations) {
            try {
                if (step.test(id)) {
                    done++;
                }
            } catch (RuntimeException failed) {
                log.warn("Chat job: conversation {} not {} this run", id, what, failed);
            }
        }
        if (done > 0) {
            log.info("Chat job: {} conversation(s) {}", done, what);
        }
    }
}
