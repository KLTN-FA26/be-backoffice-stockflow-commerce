package com.stockflow.assistant.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The assistant's watch (design §9.1): what it reads for whom, what it tells them, and what it answers on its own.
 *
 * @param enabled          whether the job runs at all ({@code ASSISTANT_WATCH_ENABLED}); off unless asked for
 * @param post             lists whose new items are posted to the user's inbox. By default the two nothing else tells
 *                         anyone about: orders put on hold and purchase orders gone late. Low stock is already posted
 *                         by the inventory alerts, and posting it again would ring twice
 * @param analyse          lists whose new items the watch also answers a fixed question about, in a proactive
 *                         conversation of the user's, with only the tools that question needs: lowStock (a reorder
 *                         proposal, which becomes the user's suggestion to confirm), heldOrders, overduePurchaseOrders.
 *                         Empty by default: each answer is a model call
 * @param roles            per list, the roles whose holders are watched for it (a list's job, not just the right to
 *                         read it: every sales and accounting user may read held orders, the coordinators handle
 *                         them). A list not named here goes to whoever may read it
 * @param dailyAnalyses    proactive conversations one user may be given in a business day
 * @param analysesPerRun   proactive conversations one run of the job may open across all users
 * @param runBudget        how long one run may go on opening proactive conversations; reading and posting go on, so
 *                         nobody's news waits, but no answer starts that could end after it
 * @param rebaselineAfter  a list not looked at for longer than this (the watch was off) is looked at anew, its items a
 *                         baseline: what turned up meanwhile is not news any more
 * @param models           the models the watch's questions go to, as {@code routing.models} names them; empty for the
 *                         questions' own
 */
@ConfigurationProperties(prefix = "stockflow.assistant.watch")
public record AssistantWatchProperties(
        boolean enabled,
        List<String> post,
        List<String> analyse,
        Map<String, List<String>> roles,
        @DefaultValue("3") int dailyAnalyses,
        @DefaultValue("5") int analysesPerRun,
        @DefaultValue("PT30M") Duration runBudget,
        @DefaultValue("P4D") Duration rebaselineAfter,
        List<String> models) {

    /** The lists the watch reads: the three whose news someone acts on, each read whole (AssistantWatch). */
    static final Set<String> WATCHABLE = Set.of(AssistantWatch.LOW_STOCK, AssistantWatch.HELD_ORDERS,
            AssistantWatch.OVERDUE_PURCHASE_ORDERS);

    public AssistantWatchProperties {
        post = clean(post);
        analyse = clean(analyse);
        models = clean(models);
        roles = roles == null ? Map.of() : roles.entrySet().stream()
                .filter(entry -> !clean(entry.getValue()).isEmpty())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> clean(entry.getValue())));
        for (String section : post) {
            requireWatchable("post", section);
        }
        for (String section : analyse) {
            requireWatchable("analyse", section);
        }
        roles.keySet().forEach(section -> requireWatchable("roles", section));
        if (dailyAnalyses < 0 || analysesPerRun < 0) {
            throw new IllegalArgumentException("stockflow.assistant.watch daily-analyses and analyses-per-run must not "
                    + "be negative");
        }
        requirePositive("run-budget", runBudget);
        requirePositive("rebaseline-after", rebaselineAfter);
    }

    /** What one look reads: every list it posts or answers about. */
    List<String> sections() {
        return java.util.stream.Stream.concat(post.stream(), analyse.stream()).distinct().toList();
    }

    /** Whether a holder of {@code roleCodes} is watched for {@code section}. */
    boolean watchedFor(String section, Set<String> roleCodes) {
        List<String> wanted = roles.get(section);
        return wanted == null || wanted.stream().anyMatch(roleCodes::contains);
    }

    private static void requireWatchable(String property, String section) {
        if (!WATCHABLE.contains(section)) {
            throw new IllegalArgumentException("stockflow.assistant.watch." + property + ": " + section
                    + " is not one of " + WATCHABLE);
        }
    }

    private static void requirePositive(String property, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("stockflow.assistant.watch." + property + " must be positive");
        }
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::strip)
                .filter(value -> !value.isEmpty()).distinct().toList();
    }
}
