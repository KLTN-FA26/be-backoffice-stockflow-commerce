package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.FeatureUseRepository;
import com.stockflow.assistant.internal.domain.FeatureUseRepository.FeatureCount;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.Role;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Counts the use of each assistant feature per feature key, role and business day (contract §5.2), so the System Admin
 * and the Sales Manager see which features people actually use and can drop the ones nobody does. Counts only: no user
 * id, no content ({@code assistant.feature_use}, V20270107000400).
 *
 * <p><b>Counted in memory, written about once a minute.</b> {@link #record} adds one to a counter and returns: no
 * database, no lock, no allocation once the day's key exists, so it neither slows nor can fail the request that uses a
 * feature. A background thread adds the counters to their rows every {@code stockflow.assistant.feature-use-flush}
 * (default one minute), in one batch of upserts, and once more when the application stops. The alternative, one upsert
 * per use, would put a connection and a round trip on every chat question and every button, and a row lock on the
 * day's busiest row; counts used to decide which features to keep do not need that. The price: an instance killed
 * without a shutdown loses at most a minute of counts, and the usage endpoint lags by as much.</p>
 *
 * <p><b>A failed write keeps the counts.</b> They go back into the counters and are tried again at the next flush,
 * with one warning in the log; nothing reaches the request that used the feature.</p>
 *
 * <p><b>The role is every role the user holds</b>, '+'-joined in the order of {@link Role} ({@link #roleOf}), so a
 * user holding two roles is counted once, under both: one use is one use, whichever way the counts are summed. A role
 * created at run time has no {@link Role} constant and counts as {@code OTHER}.</p>
 */
@Component
public class FeatureUse {

    private static final Logger log = LoggerFactory.getLogger(FeatureUse.class);

    // ---- The feature keys of contract §5.2: each endpoint that serves a feature records its own key.

    /** A question asked in the assistant panel and answered (plain or streamed). */
    public static final String CHAT = "chat";
    /** The panel's "today" card. */
    public static final String TODAY = "today";
    /** 1.1 Why is this order not moving. */
    public static final String NEXT_STEPS = "next-steps";
    /** 1.2 Reply draft for the customer. */
    public static final String REPLY_DRAFT = "reply-draft";
    /** 1.3 Conversation summary. */
    public static final String CHAT_SUMMARY = "chat-summary";
    /** 1.4 Price review before approving a quote. */
    public static final String PRICE_REVIEW = "price-review";
    /** 2.1 Match a bank transfer. */
    public static final String MATCH_SUGGESTIONS = "match-suggestions";
    /** 2.2 Collections list. */
    public static final String COLLECTIONS = "collections";
    /** 2.2 Reminder draft for an installment. */
    public static final String REMINDER_DRAFT = "reminder-draft";
    /** 3.1 Work queue. */
    public static final String WORK_QUEUE = "work-queue";
    /** 3.2 Missing carton investigation. */
    public static final String CARTON_INVESTIGATION = "carton-investigation";
    /** 3.3 Production follow-ups list. */
    public static final String PRODUCTION_FOLLOW_UPS = "production-follow-ups";
    /** 3.3 Message draft to a producer. */
    public static final String PRODUCER_MESSAGE_DRAFT = "producer-message-draft";
    /** 4.1 Why was I refused. */
    public static final String ERROR_EXPLANATION = "error-explanation";

    /** Every key above: a key outside it is a programming error, logged once and not counted. */
    public static final Set<String> KEYS = Set.of(CHAT, TODAY, NEXT_STEPS, REPLY_DRAFT, CHAT_SUMMARY, PRICE_REVIEW,
            MATCH_SUGGESTIONS, COLLECTIONS, REMINDER_DRAFT, WORK_QUEUE, CARTON_INVESTIGATION, PRODUCTION_FOLLOW_UPS,
            PRODUCER_MESSAGE_DRAFT, ERROR_EXPLANATION);

    /** The role of a user holding no role this build knows by name. */
    public static final String OTHER_ROLE = "OTHER";

    /** The longest period one usage request reads: a quarter, at most 14 keys × a few roles × 92 days of rows. */
    public static final int MAX_DAYS = 92;

    /** How long stopping waits for a flush in flight before the final one ({@link #stop}). */
    static final Duration STOP_WAIT = Duration.ofSeconds(5);

    /** Counts are kept this long; the retention job deletes older days ({@link AssistantRetentionJob}). */
    public static final int RETENTION_DAYS = 400;

    /** Counts nowhere and reads nothing: for code built without the component, tests among it. */
    public static final FeatureUse NONE = new FeatureUse(null, null, Clock.systemUTC());

    private final FeatureUseRepository repository;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnly;
    private final Clock clock;
    private final Map<Key, LongAdder> counters = new ConcurrentHashMap<>();
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService flusher;

    @Autowired
    FeatureUse(FeatureUseRepository repository, PlatformTransactionManager transactions, Clock clock,
               @Value("${stockflow.assistant.feature-use-flush:PT1M}") Duration every) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactions);
        this.readOnly = readOnly(transactions);
        this.clock = clock;
        this.flusher = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("assistant-feature-use").factory());
        long period = Math.max(1, every.toMillis());
        flusher.scheduleWithFixedDelay(this::flushQuietly, period, period, TimeUnit.MILLISECONDS);
    }

    /** No background thread: a test calls {@link #flush} itself. */
    FeatureUse(FeatureUseRepository repository, PlatformTransactionManager transactions, Clock clock) {
        this.repository = repository;
        this.tx = transactions == null ? null : new TransactionTemplate(transactions);
        this.readOnly = transactions == null ? null : readOnly(transactions);
        this.clock = clock;
        this.flusher = null;
    }

    private static TransactionTemplate readOnly(PlatformTransactionManager transactions) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setReadOnly(true);
        return template;
    }

    /**
     * Counts one use of {@code featureKey} by {@code user}, today. Never throws and never waits on anything: call it
     * after the feature has answered, so a refused or failed call is not counted as a use.
     *
     * @param featureKey one of {@link #KEYS}
     */
    public void record(String featureKey, CurrentUser user) {
        if (repository == null) {
            return;
        }
        try {
            if (!KEYS.contains(featureKey)) {
                if (warnedKeys.add(String.valueOf(featureKey))) {
                    log.warn("Assistant feature use: '{}' is not a feature key; not counted", featureKey);
                }
                return;
            }
            counters.computeIfAbsent(new Key(featureKey, roleOf(user), BusinessDate.of(clock.instant())),
                    key -> new LongAdder()).increment();
        } catch (RuntimeException failed) {
            log.warn("Assistant feature use: a use of {} was not counted ({})", featureKey,
                    failed.getClass().getSimpleName());
        }
    }

    /**
     * The counts of the days from {@code from} to {@code to}, both included, as written so far (up to a minute
     * behind).
     *
     * @throws BusinessException {@code VALIDATION_FAILED} when {@code to} is before {@code from}, or the period is
     *                           longer than {@value #MAX_DAYS} days
     */
    public List<FeatureCount> between(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "to " + to + " is before from " + from);
        }
        if (from.plusDays(MAX_DAYS - 1L).isBefore(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A usage period is at most " + MAX_DAYS + " days");
        }
        if (repository == null) {
            return List.of();
        }
        List<FeatureCount> counts = readOnly.execute(status -> repository.between(from, to));
        return counts == null ? List.of() : counts;
    }

    /** Deletes up to {@code limit} rows of days before {@code day}, in a transaction of its own; returns how many. */
    int deleteBefore(LocalDate day, int limit) {
        if (repository == null) {
            return 0;
        }
        Integer deleted = tx.execute(status -> repository.deleteBefore(day, limit));
        return deleted == null ? 0 : deleted;
    }

    /**
     * Writes the counters to their rows and empties them. A count taken out and then not written goes back into its
     * counter for the next flush. Public for a test that reads the counts back without waiting for the minute.
     */
    public void flush() {
        if (repository == null) {
            return;
        }
        List<FeatureCount> taken = new ArrayList<>();
        LocalDate today = BusinessDate.of(clock.instant());
        for (Map.Entry<Key, LongAdder> entry : counters.entrySet()) {
            long uses = entry.getValue().sumThenReset();
            if (uses > 0) {
                Key key = entry.getKey();
                taken.add(new FeatureCount(key.featureKey(), key.role(), key.day(), uses));
            } else if (entry.getKey().day().isBefore(today)) {
                // A past day takes no more uses; dropping its empty counter keeps the map to today's keys.
                counters.remove(entry.getKey(), entry.getValue());
            }
        }
        if (taken.isEmpty()) {
            return;
        }
        try {
            tx.executeWithoutResult(status -> repository.add(taken));
        } catch (RuntimeException failed) {
            taken.forEach(count -> counters.computeIfAbsent(
                    new Key(count.featureKey(), count.role(), count.day()), key -> new LongAdder()).add(count.uses()));
            throw failed;
        }
    }

    /**
     * Lets a flush in flight finish, then writes what is left. Not {@code shutdownNow}: interrupting a flush half way
     * fails its write, which puts its counts back into the counters - after the final flush below has already found
     * them empty, so they were never written. Waiting a few seconds for the flush in flight costs less than losing a
     * minute of counts on every deploy.
     */
    @PreDestroy
    void stop() {
        if (flusher != null) {
            flusher.shutdown();
            try {
                if (!flusher.awaitTermination(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("Assistant feature use: a flush still running after {}; writing what is left anyway",
                            STOP_WAIT);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        flushQuietly();
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException failed) {
            log.warn("Assistant feature use: counts not written, kept for the next try ({})",
                    failed.getClass().getSimpleName());
        }
    }

    /**
     * {@code user}'s roles as one value: their {@link Role} names, '+'-joined in the enum's order
     * ({@code SALES_STAFF+SALES_MANAGER}); {@value #OTHER_ROLE} when they hold none this build knows by name.
     */
    public static String roleOf(CurrentUser user) {
        if (user == null || user.roles() == null || user.roles().isEmpty()) {
            return OTHER_ROLE;
        }
        return user.roles().stream().sorted(Comparator.comparingInt(Role::ordinal)).map(Role::name)
                .collect(Collectors.joining("+"));
    }

    /** {@link #roleOf}, read back: the roles of a '+'-joined value, in its order. */
    public static List<String> roles(String joined) {
        return joined == null || joined.isBlank() ? List.of() : Arrays.asList(joined.split("\\+"));
    }

    private record Key(String featureKey, String role, LocalDate day) {
    }
}
