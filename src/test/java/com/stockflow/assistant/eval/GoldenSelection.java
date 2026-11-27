package com.stockflow.assistant.eval;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Which golden cases one run of the real-model evaluation asks (design §10.3). A free model quota covers about ten
 * cases a day, so the set is run a part at a time:
 *
 * <ul>
 *   <li>{@code -Dassistant.eval.cases=U1-01,U3-*}: case ids, {@code *} matching any characters;</li>
 *   <li>{@code -Dassistant.eval.useCases=U3,U6}: whole use-case groups (the {@code useCase} of a case);</li>
 *   <li>{@code -Dassistant.eval.smoke=true}: the cases marked {@code smoke}.</li>
 * </ul>
 *
 * <p>Each one adds cases; with none of them, every case is asked. Matching ignores case. A pattern or group that
 * matches no case is refused, so a typo cannot run nothing and report the empty run as green.</p>
 */
record GoldenSelection(List<String> cases, Set<String> useCases, boolean smoke) {

    GoldenSelection {
        cases = List.copyOf(cases);
        useCases = Set.copyOf(useCases);
    }

    /** The selection the system properties ask for, checked against {@code set}. */
    static GoldenSelection fromSystemProperties(List<GoldenCase> set) {
        GoldenSelection selection = parse(System.getProperty("assistant.eval.cases", ""),
                System.getProperty("assistant.eval.useCases", ""), Boolean.getBoolean("assistant.eval.smoke"));
        selection.requireEveryPartMatches(set);
        return selection;
    }

    static GoldenSelection parse(String cases, String useCases, boolean smoke) {
        return new GoldenSelection(items(cases), Set.copyOf(items(useCases)), smoke);
    }

    /** Nothing asked for in particular: the whole set. */
    boolean everything() {
        return cases.isEmpty() && useCases.isEmpty() && !smoke;
    }

    boolean includes(GoldenCase golden) {
        return everything() || smoke && golden.smoke()
                || golden.useCase() != null && useCases.contains(golden.useCase().toUpperCase(Locale.ROOT))
                || cases.stream().anyMatch(pattern -> matches(pattern, golden.id()));
    }

    void requireEveryPartMatches(List<GoldenCase> set) {
        List<String> unmatched = cases.stream()
                .filter(pattern -> set.stream().noneMatch(golden -> matches(pattern, golden.id())))
                .collect(Collectors.toList());
        useCases.stream().filter(group -> set.stream().noneMatch(golden -> golden.useCase() != null
                && golden.useCase().equalsIgnoreCase(group))).forEach(unmatched::add);
        if (smoke && set.stream().noneMatch(GoldenCase::smoke)) {
            unmatched.add("smoke");
        }
        if (!unmatched.isEmpty()) {
            throw new IllegalArgumentException("no golden case matches " + unmatched);
        }
    }

    private static boolean matches(String pattern, String id) {
        String regex = Arrays.stream(pattern.split("\\*", -1)).map(Pattern::quote).collect(Collectors.joining(".*"));
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(id).matches();
    }

    private static List<String> items(String list) {
        return Arrays.stream(list.split(",")).map(String::strip).filter(item -> !item.isEmpty())
                .map(item -> item.toUpperCase(Locale.ROOT)).toList();
    }
}
