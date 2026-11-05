package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolChannel;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.WatchRepository;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RunAs;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The assistant's watch for one user (design §9.1, ADR-0012): reads a few of their lists as them, records what it saw,
 * tells them in the in-app inbox what is new, and may answer a fixed question about it in a conversation of theirs.
 *
 * <p><b>As the user, never as the system.</b> Each look runs inside {@link RunAs}: the user's roles, grants, scope and
 * warehouses as stored now. Each list is read by a tool of the chat ({@link AssistantTools#execute}), through its own
 * guard, so a manager of one warehouse is told only about that warehouse, as their screens would tell them. A list is
 * read for a user only when it is theirs to act on: they hold its tool and one of its roles
 * ({@link AssistantWatchProperties#roles}). Who may not use the assistant ({@code assistant-conversations:CREATE}) is not
 * looked at: the watch is the assistant speaking.</p>
 *
 * <p><b>Read whole, so "new" means new.</b> Each list is read as far as its tool allows (fifty held orders; twenty
 * purchase orders per sent status, the earliest expected first, which is where every late one is), never the card's ten
 * most urgent: there a late order sorts last and would never be seen. A list read whole drops the items no longer on it,
 * so an item that comes back is news again. A list longer than one read is "read in part": nothing is dropped, since an
 * item missing from it may just be further down, and what appears in it is not posted, since it may have been there all
 * along; the user is told once that the list is too long to watch, until it is read whole again. A list that could not
 * be read changes nothing.</p>
 *
 * <p><b>What is not news.</b> A first look at a list, a look after the user's view of it changed (their scope or
 * warehouses: a manager moved to another warehouse), and a look after a long pause
 * ({@link AssistantWatchProperties#rebaselineAfter}) record what is there without telling anyone. A list the user may no
 * longer read is forgotten, so getting it back is a first look.</p>
 *
 * <p><b>Told once, even after a crash.</b> An item is recorded first, and dealt with later: posted, or set aside. An item
 * still waiting at the next look (a look stopped half way) is dealt with then; a message's key is made of its first
 * item's id, and {@link Inbox#post} is idempotent per key. Only codes go into a message — SKU, order and purchase order
 * numbers, a hold reason — never text someone typed, as {@link Inbox} requires.</p>
 *
 * <p><b>Answered at most so often, and once per item.</b> A list in {@link AssistantWatchProperties#analyse} has its new
 * items asked about in a proactive conversation opened for the user ({@link AssistantService#askProactively}), offered
 * only the tools that question needs and refused any other (a held order's typed note cannot steer the answer into a
 * reorder proposal). The items are marked asked about in the transaction that opens the conversation, so no crash makes
 * them asked twice. At most {@link #ASKED_AT_ONCE} items go into one question; the rest wait for the next look. Not
 * more than {@link AssistantWatchProperties#dailyAnalyses} a business day per user, nor
 * {@link AssistantWatchProperties#analysesPerRun} per run, nor once the run's {@link AssistantWatchProperties#runBudget}
 * could be outlived; never when the watch's models cannot answer. Items of a list the watch only answers about wait,
 * while that is so, for up to {@link #WAITS_FOR_AN_ANSWER}; then they are set aside.</p>
 *
 * <p><b>Every answer is told.</b> An answered list is told about once, by the answer's message, which says what came of
 * it (a reorder proposal or not). An answer that failed after its question was stored is told too, pointing to its
 * conversation; one refused before anything was stored leaves no conversation, and its items are not asked about.</p>
 */
@Service
public class AssistantWatch {

    private static final Logger log = LoggerFactory.getLogger(AssistantWatch.class);

    static final String LOW_STOCK = "lowStock";
    static final String HELD_ORDERS = "heldOrders";
    static final String OVERDUE_PURCHASE_ORDERS = "overduePurchaseOrders";

    /** Who acts for the user, in the token's {@code act} claim and the audit columns. */
    static final String ACTOR = "assistant-watch";

    /** Inbox categories, as the other posters name theirs. */
    static final String NEWS = "ASSISTANT_WATCH";
    static final String ANSWER = "ASSISTANT_ANSWER";

    /** Characters of codes one message body lists; the rest are counted, within {@link InboxMessage#BODY_MAX}. */
    static final int BODY_BUDGET = InboxMessage.BODY_MAX - 60;

    /** Items one question asks about: a question the model can answer item by item. */
    static final int ASKED_AT_ONCE = 30;

    /** How long an item of a list the watch only answers about waits for an answer the caps hold back. */
    static final Duration WAITS_FOR_AN_ANSWER = Duration.ofDays(1);

    /** Same rule as {@code ck_app_role_code_format}: a role among the token's authorities, never a permission. */
    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    /**
     * A list the watch reads.
     *
     * @param calls  the tool's arguments, one call each; all of them make the list
     * @param answer the tools the list's question is offered: what it needs, nothing that writes a suggestion it did
     *               not ask for
     * @param link   where its news sends the user: the screen that lists it
     */
    private record Watched(String section, String tool, List<String> calls, Set<String> answer, String link) {
    }

    private static final Map<String, Watched> WATCHED = Map.of(
            LOW_STOCK, new Watched(LOW_STOCK, "findLowStock",
                    List.of("{\"limit\":%d}".formatted(FindLowStockTool.MAX_ROWS)),
                    Set.of("findLowStock", "proposeReorder", "getStockLevels", "getSupplierTerms"),
                    "/admin/inventory/alerts?kind=REORDER"),
            HELD_ORDERS, new Watched(HELD_ORDERS, "findOrders",
                    List.of("{\"status\":\"ON_HOLD\",\"holdReason\":\"ANY\",\"placedFrom\":\"\",\"placedTo\":\"\","
                            + "\"customer\":\"\",\"limit\":%d}".formatted(FindOrdersTool.MAX_ROWS)),
                    Set.of("findOrders", "findOrder", "getCustomerCredit"),
                    "/admin/orders"),
            OVERDUE_PURCHASE_ORDERS, new Watched(OVERDUE_PURCHASE_ORDERS, "findPurchaseOrders",
                    TodayCard.SENT_STATUSES.stream().map(TodayCard::purchaseOrdersIn).toList(),
                    Set.of("findPurchaseOrders", "getSupplierTerms", "findGoodsReceipts"),
                    "/admin/purchase-orders"));

    /** The order lists are read and posted in. */
    private static final List<String> ORDER = List.of(HELD_ORDERS, OVERDUE_PURCHASE_ORDERS, LOW_STOCK);

    private final RunAs runAs;
    private final AssistantTools tools;
    private final ToolRunRecorder recorder;
    private final WatchRepository watched;
    private final Inbox inbox;
    private final AssistantService assistant;
    private final ConversationService conversations;
    private final ToolAvailability availability;
    private final AssistantWatchProperties properties;
    private final AssistantProperties assistantProperties;
    private final Messages messages;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    AssistantWatch(RunAs runAs, AssistantTools tools, ToolRunRecorder recorder, WatchRepository watched, Inbox inbox,
                   AssistantService assistant, ConversationService conversations, ToolAvailability availability,
                   AssistantWatchProperties properties, AssistantProperties assistantProperties, Messages messages,
                   ObjectMapper json, Clock clock, PlatformTransactionManager transactions) {
        this.runAs = runAs;
        this.tools = tools;
        this.recorder = recorder;
        this.watched = watched;
        this.inbox = inbox;
        this.assistant = assistant;
        this.conversations = conversations;
        this.availability = availability;
        this.properties = properties;
        this.assistantProperties = assistantProperties;
        this.messages = messages;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** What one look did for one user, for the job's log. */
    record Look(UUID userId, Outcome outcome, int newItems, int posted, int answered) {

        static Look skipped(UUID userId, Outcome outcome) {
            return new Look(userId, outcome, 0, 0, 0);
        }
    }

    /** LOOKED, or why the user was not looked at. */
    enum Outcome { LOOKED, NOT_ACTED_FOR, NO_ASSISTANT }

    /**
     * What one run may still spend on answers: so many conversations, none started that could end after the deadline.
     */
    static final class Budget {

        private final AtomicInteger answers;
        private final Instant deadline;

        Budget(int answers, Instant deadline) {
            this.answers = new AtomicInteger(answers);
            this.deadline = deadline;
        }

        /** Takes one answer, if one is left and an answer started at {@code now} would end in time. */
        boolean take(Instant now, Duration longestAnswer) {
            if (!now.plus(longestAnswer).isBefore(deadline)) {
                return false;
            }
            if (answers.getAndDecrement() <= 0) {
                answers.incrementAndGet();
                return false;
            }
            return true;
        }

        void giveBack() {
            answers.incrementAndGet();
        }
    }

    /** Looks at {@code userId}'s lists as them; called by {@link AssistantWatchJob} only. */
    Look look(UUID userId, Budget budget) {
        try {
            return runAs.call(userId, ACTOR, user -> lookAs(user, budget));
        } catch (RunAs.RefusedException refused) {
            log.debug("Assistant watch skips user {}: {}", userId, refused.refusal());
            return Look.skipped(userId, Outcome.NOT_ACTED_FOR);
        }
    }

    /**
     * Drops what the watch recorded for lists nobody has looked at since {@code cutoff} (the retention period): a user
     * gone, a list switched off.
     */
    int forgetIdle(Instant cutoff, int limit) {
        Integer deleted = tx.execute(status -> watched.deleteNotLookedAtSince(cutoff, limit));
        return deleted == null ? 0 : deleted;
    }

    private Look lookAs(CurrentUser user, Budget budget) {
        UUID owner = user.userId();
        if (user.isPortalCustomer() || !user.can(ToolPermissions.ASSISTANT, Action.CREATE)) {
            tx.executeWithoutResult(status -> ORDER.forEach(section -> watched.drop(owner, section)));
            return Look.skipped(owner, Outcome.NO_ASSISTANT);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Set<String> granted = Set.copyOf(availability.grantedTo(user));
        Set<String> roles = roleCodes();
        Map<String, Read> reads = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        List<ToolExecution> ran = new ArrayList<>();
        try {
            for (String section : ORDER) {
                Watched list = WATCHED.get(section);
                if (!properties.sections().contains(section) || !properties.watchedFor(section, roles)
                        || !granted.contains(list.tool())) {
                    dropped.add(section);
                    continue;
                }
                Read read = read(list, ran);
                if (read != null) {
                    reads.put(section, read);
                }
            }
        } finally {
            recorder.recordReadsOnly(ToolChannel.PROACTIVE, owner, ran);
        }
        String view = view(user);
        int[] newItems = {0};
        int[] posted = {0};
        tx.executeWithoutResult(status -> {
            dropped.forEach(section -> watched.drop(owner, section));
            reads.forEach((section, read) -> {
                int fresh = record(owner, section, read, view, now);
                if (fresh < 0) {
                    posted[0] += partial(owner, section, now);
                } else {
                    newItems[0] += fresh;
                }
            });
        });
        Map<String, List<WatchRepository.SeenItem>> pending = tx.execute(status -> watched.pending(owner).stream()
                .filter(item -> reads.containsKey(item.section()))
                .collect(Collectors.groupingBy(WatchRepository.SeenItem::section, LinkedHashMap::new,
                        Collectors.toList())));
        int answered = pending == null ? 0 : answer(user, pending, granted, budget);
        posted[0] += pending == null ? 0 : post(owner, pending);
        return new Look(owner, Outcome.LOOKED, newItems[0], posted[0], answered);
    }

    // ------------------------------------------------------------------------------------------------ reading

    /** The list's items, and whether the list was read whole. */
    private record Read(List<String> keys, boolean whole) {
    }

    /** @return null when a call did not answer: the list is not known this time */
    private Read read(Watched list, List<ToolExecution> ran) {
        List<String> keys = new ArrayList<>();
        boolean whole = true;
        for (int i = 0; i < list.calls().size(); i++) {
            ToolExecution run = tools.execute(new ToolCall("watch-" + list.section() + "-" + (i + 1), list.tool(),
                    list.calls().get(i)), ToolChannel.PROACTIVE);
            ran.add(run);
            if (run.outcome() != ToolOutcome.OK) {
                return null;
            }
            JsonNode answer;
            try {
                answer = json.readTree(run.result());
            } catch (IOException unreadable) {
                log.warn("Assistant watch could not read {}'s answer: {}", list.tool(), unreadable.toString());
                return null;
            }
            // The tool judged "late" by its own clock, right before: the same day, read right after it.
            whole &= readInto(list.section(), answer, keys, BusinessDate.of(clock.instant()));
        }
        return new Read(keys, whole);
    }

    /** Adds the answer's items to {@code keys}; whether the answer holds every item of its part of the list. */
    private static boolean readInto(String section, JsonNode answer, List<String> keys, LocalDate today) {
        boolean truncated = answer.path("truncated").asBoolean(false);
        switch (section) {
            case LOW_STOCK -> answer.path("skus").forEach(row -> add(keys, row.path("sku")));
            case HELD_ORDERS -> answer.path("orders").forEach(row -> {
                String number = text(row.path("orderNumber"));
                String reason = text(row.path("hold").path("reason"));
                if (number != null) {
                    // The reason is part of the item: an order held again for another reason is held anew.
                    keys.add(reason == null ? number : number + " (" + reason + ")");
                }
            });
            case OVERDUE_PURCHASE_ORDERS -> {
                JsonNode rows = answer.path("purchaseOrders");
                rows.forEach(row -> {
                    if (row.path("overdue").asBoolean(false)) {
                        add(keys, row.path("poNumber"));
                    }
                });
                // Read earliest expected first: once a row is expected today or later, no later one is late.
                if (truncated && !rows.isEmpty()) {
                    truncated = !expectedFrom(rows.get(rows.size() - 1).path("expectedAt"), today);
                }
            }
            default -> throw new IllegalArgumentException("not a watched list: " + section);
        }
        return !truncated;
    }

    /**
     * Whether a row expected at {@code expectedAt} ends the late ones: expected today or later, or not at all (the list
     * puts those last). A date that cannot be read does not.
     */
    private static boolean expectedFrom(JsonNode expectedAt, LocalDate today) {
        String expected = text(expectedAt);
        if (expected == null) {
            return true;
        }
        try {
            return !LocalDate.parse(expected).isBefore(today);
        } catch (java.time.format.DateTimeParseException unreadable) {
            return false;
        }
    }

    private static void add(List<String> keys, JsonNode value) {
        String text = text(value);
        if (text != null) {
            keys.add(text);
        }
    }

    /** A JSON value as its text: a text node's {@code toString} would be its JSON, quotes included. */
    private static String text(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode() || value.asText().isBlank() ? null
                : value.asText();
    }

    /** What the user sees of a list, as a digest: their scope, and their warehouses when it is a warehouse's. */
    static String view(CurrentUser user) {
        String seen = user.scope() + ":" + user.warehouseIds().stream().map(UUID::toString).sorted()
                .collect(Collectors.joining(","));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(seen.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JVM has SHA-256", impossible);
        }
    }

    /** The role codes of the user being acted for: the authorities that are roles, not permissions. */
    private static Set<String> roleCodes() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).filter(code -> ROLE_CODE.matcher(code).matches())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Records one list read now (the class javadoc's rules).
     *
     * @return how many of its items are news; -1 when the list became too long to watch at this look, which its
     *         user is told once (a first look included: it is not news about an item, but about the watch)
     */
    private int record(UUID owner, String section, Read read, String view, Instant now) {
        java.util.Optional<WatchRepository.State> state = watched.state(owner, section);
        boolean first = state.map(known -> !known.view().equals(view)
                        || known.lastLookedAt().isBefore(now.minus(properties.rebaselineAfter())))
                .orElse(true);
        Instant partialSince = read.whole() ? null
                : state.filter(known -> !first).map(WatchRepository.State::partialSince).orElse(null);
        boolean becamePartial = !read.whole() && partialSince == null;
        if (first) {
            watched.restart(owner, section, view, now);
        }
        watched.looked(owner, section, now, read.whole() ? null : becamePartial ? now : partialSince);
        List<WatchRepository.SeenItem> added = watched.see(owner, section, read.keys(), now);
        if (read.whole()) {
            watched.forgetUnseen(owner, section, now);
        } else {
            log.warn("Assistant watch reads only part of {} for user {}: what appears in it is not posted",
                    section, owner);
        }
        if (first || !read.whole()) {
            watched.dealtWith(ids(added), now);
            return becamePartial ? -1 : 0;
        }
        return added.size();
    }

    /** Tells the user, once per episode, that a list is too long for the watch to tell them what is new on it. */
    private int partial(UUID owner, String section, Instant since) {
        return inbox.post(List.of(owner), new InboxMessage(NEWS, InboxSeverity.INFO,
                messages.get("assistant.watch.partial." + section, "Too long to watch: " + section),
                messages.get("assistant.watch.partial", "Open the screen for the whole list."),
                WATCHED.get(section).link(), "ASSISTANT_WATCH", section,
                "assistant:partial:" + section + ":" + since.toEpochMilli()));
    }

    // ------------------------------------------------------------------------------------------------ answering

    /** How a question of the watch's ended. */
    private enum Asked { ANSWERED, FAILED, NOT_STARTED }

    /**
     * The fixed question of each answerable list with items waiting (and not yet asked about), each in a conversation
     * of its own, within the caps. Items an answer was told about leave {@code pending}.
     */
    private int answer(CurrentUser user, Map<String, List<WatchRepository.SeenItem>> pending, Set<String> granted,
                       Budget budget) {
        int answered = 0;
        Duration longest = assistantProperties.answerLease();
        for (String section : properties.analyse()) {
            List<WatchRepository.SeenItem> waiting = pending.getOrDefault(section, List.of()).stream()
                    .filter(item -> !item.analysed()).toList();
            Watched list = WATCHED.get(section);
            Set<String> offered = list.answer().stream().filter(granted::contains)
                    .collect(Collectors.toUnmodifiableSet());
            if (waiting.isEmpty() || offered.isEmpty()) {
                continue;
            }
            Instant now = clock.instant();
            if (!assistant.answersInTheBackground()
                    || conversations.proactiveSince(user.userId(), BusinessDate.startOfDay(now))
                    >= properties.dailyAnalyses()) {
                return answered;
            }
            if (!budget.take(now, longest)) {
                log.info("Assistant watch leaves {} new {} of user {} for a later run: this run's answers are spent",
                        waiting.size(), section, user.userId());
                return answered;
            }
            List<WatchRepository.SeenItem> asked = waiting.subList(0, Math.min(waiting.size(), ASKED_AT_ONCE));
            UUID conversation = tx.execute(status -> {
                UUID opened = conversations.startProactive(user.userId()).id();
                watched.analysed(ids(asked), opened, clock.instant());
                return opened;
            });
            List<String> codes = asked.stream().map(WatchRepository.SeenItem::key).toList();
            Answered outcome = ask(user, conversation, section, codes, offered);
            if (outcome.asked() == Asked.NOT_STARTED) {
                tx.executeWithoutResult(status -> watched.notAnalysed(ids(asked)));
                budget.giveBack();
                continue;
            }
            String title = outcome.asked() == Asked.FAILED
                    ? messages.get("assistant.watch.failed", "The assistant could not answer about "
                            + codes.size() + " " + section, codes.size())
                    : LOW_STOCK.equals(section) && !outcome.proposed()
                    ? messages.get("assistant.watch.answered.lowStock.none", "The assistant looked at "
                            + codes.size() + " " + section, codes.size())
                    : messages.get("assistant.watch.answered." + section, "The assistant looked at "
                            + codes.size() + " " + section, codes.size());
            boolean told = outcome.asked() == Asked.ANSWERED || !properties.post().contains(section);
            if (told) {
                // A failed answer of a list the watch posts falls back to the list's news; one it only answers about is
                // told as such, pointing to the conversation, which may already hold a suggestion.
                tx.executeWithoutResult(status -> {
                    inbox.post(List.of(user.userId()), new InboxMessage(ANSWER,
                            outcome.asked() == Asked.ANSWERED ? InboxSeverity.INFO : InboxSeverity.WARN, title,
                            body(codes), null, "ASSISTANT_CONVERSATION", conversation.toString(),
                            "assistant:answer:" + conversation));
                    watched.dealtWith(ids(asked), clock.instant());
                });
                List<WatchRepository.SeenItem> left = new ArrayList<>(pending.get(section));
                left.removeAll(asked);
                pending.put(section, left);
            }
            if (outcome.asked() == Asked.ANSWERED) {
                answered++;
            }
        }
        return answered;
    }

    /** How a question ended, and whether its answer proposed a purchase order. */
    private record Answered(Asked asked, boolean proposed) {
    }

    /**
     * Asks the list's fixed question in {@code conversation}. A question refused before anything was stored (the
     * models switched off since the check, say) leaves an empty conversation, which is deleted: NOT_STARTED. One that
     * failed later keeps the question and the failure for the user to see: FAILED.
     */
    private Answered ask(CurrentUser user, UUID conversation, String section, List<String> codes,
                         Set<String> offered) {
        String listed = String.join(", ", codes);
        try {
            AssistantService.Answer answer = assistant.askProactively(conversation, messages.get(
                    "assistant.watch.ask." + section, section + ": " + listed, listed), user, offered);
            return new Answered(Asked.ANSWERED, !answer.proposals().isEmpty());
        } catch (RuntimeException failed) {
            boolean empty = conversations.discardEmptyProactive(conversation, user.userId());
            log.warn("Assistant watch could not answer about {} new {} of user {} in conversation {} ({}): {}",
                    codes.size(), section, user.userId(), conversation, empty ? "discarded, nothing stored" : "kept",
                    failed.toString());
            return new Answered(empty ? Asked.NOT_STARTED : Asked.FAILED, false);
        }
    }

    // ------------------------------------------------------------------------------------------------ posting

    /**
     * Deals with every item still waiting: posts the lists the watch posts, one message each. Items of a list it only
     * answers about wait for an answer, up to {@link #WAITS_FOR_AN_ANSWER}, then are set aside.
     */
    private int post(UUID owner, Map<String, List<WatchRepository.SeenItem>> pending) {
        Integer posted = tx.execute(status -> {
            int count = 0;
            Instant now = clock.instant();
            for (String section : ORDER) {
                List<WatchRepository.SeenItem> items = pending.get(section);
                if (items == null || items.isEmpty()) {
                    continue;
                }
                if (properties.post().contains(section)) {
                    List<String> codes = items.stream().map(WatchRepository.SeenItem::key).toList();
                    count += inbox.post(List.of(owner), new InboxMessage(NEWS, severity(section),
                            messages.get("assistant.watch.title." + section, items.size() + " new " + section,
                                    items.size()),
                            body(codes), WATCHED.get(section).link(), "ASSISTANT_WATCH", section,
                            "assistant:watch:" + section + ":" + items.getFirst().id()));
                    watched.dealtWith(ids(items), now);
                } else {
                    List<WatchRepository.SeenItem> done = items.stream().filter(item -> item.analysed()
                            || item.firstSeenAt().isBefore(now.minus(WAITS_FOR_AN_ANSWER))).toList();
                    if (!done.isEmpty()) {
                        watched.dealtWith(ids(done), now);
                    }
                }
            }
            return count;
        });
        return posted == null ? 0 : posted;
    }

    private static InboxSeverity severity(String section) {
        return LOW_STOCK.equals(section) ? InboxSeverity.INFO : InboxSeverity.WARN;
    }

    /** The codes, as many as fit, then how many more. */
    private String body(Collection<String> codes) {
        StringBuilder listed = new StringBuilder();
        int shown = 0;
        for (String code : new LinkedHashSet<>(codes)) {
            int extra = (listed.isEmpty() ? 0 : 2) + code.length();
            if (listed.length() + extra > BODY_BUDGET) {
                break;
            }
            listed.append(listed.isEmpty() ? "" : ", ").append(code);
            shown++;
        }
        int more = codes.size() - shown;
        return more == 0 ? listed.toString()
                : listed + " " + messages.get("assistant.watch.more", "+" + more, more);
    }

    private static List<UUID> ids(Collection<WatchRepository.SeenItem> items) {
        return items.stream().map(WatchRepository.SeenItem::id).toList();
    }
}
