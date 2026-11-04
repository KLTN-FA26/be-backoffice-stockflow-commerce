package com.stockflow.assistant.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes conversations idle for longer than {@code stockflow.assistant.retention-days} (design §5.5): what was asked,
 * what the tools returned and the verdicts people gave the answers do not stay forever. The verdicts need no delete of
 * their own: each cascades from the message it rates, which cascades from its conversation, so none outlives it.
 *
 * <p>Runs as the system, so it uses the store's unscoped delete, in batches with one transaction each, and never
 * touches a conversation being answered right now.</p>
 */
@Component
class AssistantRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AssistantRetentionJob.class);
    static final int BATCH = 500;

    private final ConversationService conversations;
    private final AssistantProperties properties;
    private final Clock clock;

    AssistantRetentionJob(ConversationService conversations, AssistantProperties properties, Clock clock) {
        this.conversations = conversations;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${stockflow.assistant.retention-cron:0 30 3 * * *}", zone = "Asia/Ho_Chi_Minh")
    @SchedulerLock(name = "assistant-retention", lockAtMostFor = "PT30M")
    public void deleteIdleConversations() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(properties.retentionDays()));
        int deleted = 0;
        int batch;
        do {
            batch = conversations.deleteIdleBefore(cutoff, BATCH);
            deleted += batch;
        } while (batch == BATCH);
        if (deleted > 0) {
            log.info("Assistant retention: deleted {} conversation(s) idle since before {}", deleted, cutoff);
        }
    }
}
