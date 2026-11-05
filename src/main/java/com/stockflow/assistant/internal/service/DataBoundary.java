package com.stockflow.assistant.internal.service;

import java.net.IDN;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Where the assistant may send what it reads, written down ({@code stockflow.assistant.data-policy},
 * {@code allowed-providers}, {@code allowed-hosts}; design §5.5, ADR-0014): the one check every adapter that sends
 * tool results, passages, photos or voice to a model passes before it is built.
 *
 * <p><b>Why a list, beside the policy.</b> {@link DataPolicy} has two answers, any cloud or none. A deployment
 * that accepted Gemini (AQ-01) can still be configured, by one wrong variable, to send the same data to a second
 * vendor nobody approved: a routed model of another provider, an embedding server on a public host, a delivery-note
 * reader. The lists name what was approved, and anything else is refused when the application starts, with the reason
 * logged; adding Bedrock later is one line, read by whoever reviews the change.</p>
 *
 * <ul>
 *   <li>An endpoint inside the company's network ({@link DataPolicy#isLocal}) is always allowed, whatever the lists
 *       say: nothing leaves.</li>
 *   <li>{@code allowed-providers}: provider ids ({@code anthropic}, {@code gemini}, {@code openai}); empty for any.</li>
 *   <li>{@code allowed-hosts}: the hosts an endpoint outside the network may be on. Empty, with providers listed, it is
 *       those providers' own API hosts: listing {@code gemini} pins Google's API, so a base URL pointing elsewhere is
 *       refused, and an OpenAI-compatible server outside the network needs its host named. Empty with no providers
 *       listed, any host.</li>
 * </ul>
 *
 * <p><b>A mistake in the lists switches the assistant off, never the application.</b> A provider id that is none of the
 * known ones, or a host that is not a bare host name, makes every model refused with the reason logged; the rest of
 * StockFlow starts as usual.</p>
 *
 * @param providers the provider ids allowed, lower case; empty for any
 * @param hosts     the hosts allowed outside the company's network, lower case; empty for any
 * @param mistake   why the lists cannot be read; null when they can
 */
record DataBoundary(DataPolicy policy, Set<String> providers, Set<String> hosts, String mistake) {

    /** A host as the list holds it: a name or an IPv4 address, no scheme, port, path or wildcard. */
    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?"
            + "(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");

    DataBoundary {
        policy = policy == null ? DataPolicy.CLOUD_ALLOWED : policy;
        providers = Set.copyOf(providers == null ? Set.of() : providers);
        hosts = Set.copyOf(hosts == null ? Set.of() : hosts);
    }

    /** The lists as configured: blanks dropped, case folded; a mistake in them recorded, never thrown. */
    static DataBoundary of(DataPolicy policy, List<String> providers, List<String> hosts) {
        Set<String> allowedProviders = normalised(providers);
        Set<String> allowedHosts = normalised(hosts);
        for (String provider : allowedProviders) {
            if (ModelProvider.named(provider) == null) {
                return new DataBoundary(policy, allowedProviders, allowedHosts, "stockflow.assistant.allowed-providers "
                        + "holds '" + provider + "', which is none of " + ModelProvider.ids());
            }
        }
        for (String host : allowedHosts) {
            if (!HOST.matcher(host).matches()) {
                return new DataBoundary(policy, allowedProviders, allowedHosts, "stockflow.assistant.allowed-hosts "
                        + "holds '" + host + "', which is not a bare host name (no scheme, port, path or wildcard)");
            }
        }
        if (allowedHosts.isEmpty() && !allowedProviders.isEmpty()) {
            allowedProviders.stream().map(ModelProvider::named).map(DataBoundary::ownHost)
                    .filter(java.util.Objects::nonNull).forEach(allowedHosts::add);
            if (allowedHosts.isEmpty()) {
                // Only openai listed: its server must be inside the network, or named.
                allowedHosts.add("localhost");
            }
        }
        return new DataBoundary(policy, allowedProviders, allowedHosts, null);
    }

    /** Why {@code provider} at {@code endpoint} may not be used, for the start-up log; empty when it may. */
    Optional<String> refusal(ModelProvider provider, String endpoint) {
        if (mistake != null) {
            return Optional.of(mistake);
        }
        if (!policy.allows(provider, endpoint)) {
            return Optional.of("the data policy " + policy + " forbids " + provider.id() + " at "
                    + AssistantProperties.loggable(endpoint));
        }
        if (DataPolicy.isLocal(endpoint)) {
            return Optional.empty();
        }
        if (!providers.isEmpty() && !providers.contains(provider.id())) {
            return Optional.of(provider.id() + " is not in stockflow.assistant.allowed-providers " + new TreeSet<>(
                    providers));
        }
        if (!hosts.isEmpty()) {
            String host = host(endpoint);
            if (host == null || !hosts.contains(host)) {
                return Optional.of("the host of " + AssistantProperties.loggable(endpoint) + " is not in "
                        + "stockflow.assistant.allowed-hosts " + new TreeSet<>(hosts));
            }
        }
        return Optional.empty();
    }

    /** One line for the start-up log: what may receive the assistant's data. */
    String describe() {
        if (mistake != null) {
            return "nothing: " + mistake;
        }
        return "data policy " + policy + ", providers " + (providers.isEmpty() ? "any" : new TreeSet<>(providers))
                + ", hosts outside the company's network " + (hosts.isEmpty() ? "any" : new TreeSet<>(hosts))
                + "; an MCP client, when MCP is on, receives tool results for its own model";
    }

    /** The host a cloud vendor's API is on; null for the OpenAI-compatible server, which has none of its own. */
    private static String ownHost(ModelProvider provider) {
        return switch (provider) {
            case GEMINI -> host(GeminiLanguageModel.DEFAULT_BASE_URL);
            case ANTHROPIC -> host(AssistantProperties.ANTHROPIC_BASE_URL);
            case OPENAI -> null;
        };
    }

    private static Set<String> normalised(List<String> values) {
        Set<String> kept = new TreeSet<>();
        if (values != null) {
            values.stream().filter(value -> value != null && !value.isBlank())
                    .map(value -> value.strip().toLowerCase(Locale.ROOT)).forEach(kept::add);
        }
        return kept;
    }

    /** The endpoint's host, in the form the list is written in; null when it has none. */
    private static String host(String endpoint) {
        try {
            String host = URI.create(endpoint.strip()).getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            host = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
            return IDN.toASCII(host).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException | NullPointerException unreadable) {
            return null;
        }
    }
}
