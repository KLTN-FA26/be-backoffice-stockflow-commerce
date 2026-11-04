package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.McpTokenRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes conversations idle for longer than {@code stockflow.assistant.retention-days} (design §5.5): what was asked,
 * what the tools returned and the verdicts people gave the answers do not stay forever. The verdicts need no delete of
 * their own: each cascades from the message it rates, which cascades from its conversation, so none outlives it.
 * Tool runs that belong to no conversation — an MCP client's, the "today" card's — go by age, after the same period,
 * and so do MCP tokens that expired or were revoked that long ago.
 *
 * <p>Runs as the system, so it uses the store's unscoped delete, in batches with one transaction each, and never
 * touches a conversation being answered right now.</p>
 */
@Component
class AssistantRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AssistantRetentionJob.class);
    static final int BATCH = 500;

    private final ConversationService conversations;
    private final ToolRunRecorder runs;
    private final AssistantProperties properties;
    private final Clock clock;
    private final AssistantActions actions;
    private final AssistantWatch watch;
    private final McpTokenRepository mcpTokens;
    private final TransactionTemplate tx;

    AssistantRetentionJob(ConversationService conversations, ToolRunRecorder runs, AssistantProperties properties,
                          Clock clock, AssistantActions actions, AssistantWatch watch, McpTokenRepository mcpTokens,
                          PlatformTransactionManager transactions) {
        this.actions = actions;
        this.watch = watch;
        this.mcpTokens = mcpTokens;
        this.tx = new TransactionTemplate(transactions);
        this.conversations = conversations;
        this.runs = runs;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${stockflow.assistant.retention-cron:0 30 3 * * *}", zone = "Asia/Ho_Chi_Minh")
    @SchedulerLock(name = "assistant-retention", lockAtMostFor = "PT30M")
    public void deleteIdleConversations() {
        int forgotten = 0;
        int forgottenBatch;
        do {
            forgottenBatch = watch.forgetIdle(clock.instant().minus(Duration.ofDays(properties.retentionDays())),
                    BATCH);
            forgotten += forgottenBatch;
        } while (forgottenBatch == BATCH);
        if (forgotten > 0) {
            log.info("Assistant retention: forgot {} watched list(s) nobody looked at for {} days", forgotten,
                    properties.retentionDays());
        }
        // A proactive conversation is asked in right after it is opened; one still empty an hour later was left by a
        // look that stopped in between, and would only show the user an empty conversation.
        int abandoned = 0;
        int abandonedBatch;
        do {
            abandonedBatch = conversations.deleteAbandonedProactiveBefore(clock.instant().minus(Duration.ofHours(1)),
                    BATCH);
            abandoned += abandonedBatch;
        } while (abandonedBatch == BATCH);
        if (abandoned > 0) {
            log.info("Assistant retention: deleted {} empty proactive conversation(s)", abandoned);
        }
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
        int standalone = 0;
        do {
            batch = runs.deleteBefore(cutoff, BATCH);
            standalone += batch;
        } while (batch == BATCH);
        if (standalone > 0) {
            log.info("Assistant retention: deleted {} MCP, today and background tool run(s) from before {}",
                    standalone, cutoff);
        }
        // A delivery note's suggestion belongs to no conversation; an answer's goes with its conversation.
        int drafts = 0;
        do {
            batch = actions.deleteStandaloneBefore(cutoff, BATCH);
            drafts += batch;
        } while (batch == BATCH);
        if (drafts > 0) {
            log.info("Assistant retention: deleted {} suggestion(s) read from delivery notes before {}", drafts,
                    cutoff);
        }
        int tokens = 0;
        do {
            batch = deadTokens(cutoff);
            tokens += batch;
        } while (batch == BATCH);
        if (tokens > 0) {
            log.info("Assistant retention: deleted {} MCP token(s) expired or revoked before {}", tokens, cutoff);
        }
    }

    /** A bare JDBC delete needs a transaction of its own: with auto-commit off it would be rolled back otherwise. */
    private int deadTokens(Instant cutoff) {
        Integer deleted = tx.execute(status -> mcpTokens.deleteDeadBefore(cutoff, BATCH));
        return deleted == null ? 0 : deleted;
    }
}
