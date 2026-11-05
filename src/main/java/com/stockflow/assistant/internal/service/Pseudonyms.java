package com.stockflow.assistant.internal.service;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The customer aliases of one conversation while a question of it is answered (ADR-0014): what is sent to the model
 * provider instead of a customer's name ({@code KH#3}), and which customer each stands for.
 *
 * <p><b>Why {@code KH#n}.</b> Staff write customer codes as {@code KH-012} or {@code KH001} (MISA's habit); an alias in
 * that shape would be read back as the name of whichever customer has that number in the conversation, in a note or in
 * a code the user typed. No customer code is written with {@code #}, typed text that has the shape is neutralised
 * ({@link TypedText}), and only the canonical form, without leading zeros, is an alias.</p>
 *
 * <p>Thread-safe: a step's tools run at once ({@link ParallelTools}), and two of them may name customers.</p>
 */
final class Pseudonyms {

    /** An alias as written: {@code KH#3}, any case, not part of a longer token. */
    static final Pattern ALIAS = Pattern.compile("(?<![\\p{L}\\p{N}#])KH#([1-9]\\d{0,5})(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE);

    /** Shortest name replaced by its alias in free text: shorter ones are words, not names. */
    private static final int SHORTEST_NAME = 6;

    private final Map<Integer, UUID> byNumber = new LinkedHashMap<>();
    private final Map<UUID, Integer> byCustomer = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<Integer, UUID> added = new LinkedHashMap<>();

    /**
     * @param stored the conversation's aliases so far, by number
     * @param names  their customers' names as registered now, for finding them in what the user types
     */
    Pseudonyms(Map<Integer, UUID> stored, Map<UUID, String> names) {
        stored.forEach((number, customer) -> {
            byNumber.put(number, customer);
            byCustomer.put(customer, number);
        });
        this.names.putAll(names);
    }

    Pseudonyms(Map<Integer, UUID> stored) {
        this(stored, Map.of());
    }

    /** The customer's alias in this conversation, given out the first time it is named. */
    synchronized String aliasOf(UUID customerId, String name) {
        if (name != null && !name.isBlank()) {
            names.putIfAbsent(customerId, name);
        }
        Integer number = byCustomer.get(customerId);
        if (number == null) {
            number = byNumber.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
            byNumber.put(number, customerId);
            byCustomer.put(customerId, number);
            added.put(number, customerId);
        }
        return alias(number);
    }

    /** The customer {@code typed} names when it is one of this conversation's aliases, written whole. */
    synchronized Optional<UUID> customerOf(String typed) {
        Matcher alias = ALIAS.matcher(typed == null ? "" : typed.strip());
        if (!alias.matches()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byNumber.get(Integer.parseInt(alias.group(1))));
    }

    /** Whether {@code typed} is written as an alias, known or not. */
    static boolean looksLikeAlias(String typed) {
        return typed != null && ALIAS.matcher(typed.strip()).matches();
    }

    /**
     * {@code text} with the registered name of every customer this conversation has an alias for replaced by the alias,
     * wherever it appears whole (case and the composition of accents aside): the user asking about a customer the panel
     * showed by name, a note that names one. A customer the conversation has not met yet is not found: telling a name
     * from other words in free text is a guess this class does not make.
     */
    synchronized String hideNames(String text) {
        if (text == null || text.isBlank() || names.isEmpty()) {
            return text;
        }
        String hidden = Normalizer.normalize(text, Normalizer.Form.NFC);
        List<Map.Entry<UUID, String>> longestFirst = names.entrySet().stream()
                .filter(entry -> byCustomer.containsKey(entry.getKey()))
                .filter(entry -> entry.getValue().strip().length() >= SHORTEST_NAME)
                .sorted(Comparator.comparingInt((Map.Entry<UUID, String> entry) -> entry.getValue().length())
                        .reversed())
                .toList();
        for (Map.Entry<UUID, String> customer : longestFirst) {
            String name = Normalizer.normalize(customer.getValue().strip(), Normalizer.Form.NFC);
            Pattern whole = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            hidden = whole.matcher(hidden).replaceAll(Matcher.quoteReplacement(
                    alias(byCustomer.get(customer.getKey()))));
        }
        return hidden;
    }

    /** The aliases this question gave out, to be stored with its answer. */
    synchronized Map<Integer, UUID> added() {
        return Map.copyOf(added);
    }

    /** Every alias of the conversation, by number. */
    synchronized Map<Integer, UUID> all() {
        return Map.copyOf(byNumber);
    }

    /** The names known, by customer: those registered when the question started and those its tools read. */
    synchronized Map<UUID, String> knownNames() {
        return Map.copyOf(names);
    }

    static String alias(int number) {
        return "KH#" + number;
    }

    /**
     * Text with every alias of {@code aliases} replaced by the customer's name; an alias nobody was given, or one whose
     * customer has no name any more, stays as written. A name the model wrote beside its own alias - "Công ty ABC
     * (KH#1)", the name taken from the question and the alias from a tool, or the other way round - becomes the name
     * once, rather than "Công ty ABC (Công ty ABC)".
     *
     * @param names the customers' current names, asked once for all of them
     */
    static UnaryOperator<String> revealer(Map<Integer, UUID> aliases,
                                          Function<Collection<UUID>, Map<UUID, String>> names) {
        if (aliases.isEmpty()) {
            return UnaryOperator.identity();
        }
        Map<UUID, String> found = names.apply(aliases.values());
        Map<Integer, String> byNumber = new HashMap<>();
        aliases.forEach((number, customer) -> {
            String name = found.get(customer);
            if (name != null && !name.isBlank()) {
                byNumber.put(number, name);
            }
        });
        return text -> {
            if (text == null || byNumber.isEmpty() || !text.toUpperCase(Locale.ROOT).contains("KH#")) {
                return text;
            }
            for (Map.Entry<Integer, String> customer : byNumber.entrySet()) {
                String name = Pattern.quote(customer.getValue());
                String code = "KH#" + customer.getKey() + "(?![\\p{L}\\p{N}])";
                text = Pattern.compile("(" + name + ")\\s*\\(\\s*" + code + "\\s*\\)|" + code + "\\s*\\(\\s*(" + name
                                + ")\\s*\\)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text)
                        .replaceAll(pair -> Matcher.quoteReplacement(pair.group(1) != null ? pair.group(1)
                                : pair.group(2)));
            }
            Matcher alias = ALIAS.matcher(text);
            StringBuilder out = new StringBuilder(text.length() + 32);
            while (alias.find()) {
                String name = byNumber.get(Integer.parseInt(alias.group(1)));
                alias.appendReplacement(out, Matcher.quoteReplacement(name == null ? alias.group() : name));
            }
            return alias.appendTail(out).toString();
        };
    }
}
