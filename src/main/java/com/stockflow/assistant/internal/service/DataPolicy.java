package com.stockflow.assistant.internal.service;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Where the assistant may send what it reads ({@code stockflow.assistant.data-policy}, design §5.5 and §13 AQ-01):
 * tool results, the knowledge base's passages, photographed delivery notes.
 *
 * <p><b>{@code LOCAL_ONLY} is a guarantee, not a preference.</b> No adapter is built for an endpoint outside the
 * company's own network — no Gemini, no Claude, no OpenAI-compatible server on a public host — for the chat, the
 * embeddings or the delivery-note reader. A model that would break it is not used, and the assistant says why at
 * start-up and answers 503 {@code ASSISTANT_DISABLED}, as it does without a key; the application still starts. It is
 * the technical half of AQ-01. Whether data may leave at all is the business owner's decision, and this switch is how
 * that decision is enforced instead of remembered.</p>
 *
 * <p><b>Local means every address it reaches is private</b> ({@link #isLocal}):</p>
 * <ul>
 *   <li>an address written as four decimal numbers, or in IPv6, that is loopback, private (10/8, 172.16/12,
 *       192.168/16, fc00::/7) or link-local. Any other numeric host — {@code 134744072}, {@code 0x08080808},
 *       {@code 010.0.0.1}, {@code 10.1} — is refused: the resolver reads such forms as addresses, and
 *       {@code 134744072} is 8.8.8.8;</li>
 *   <li>a name that looks internal — {@code localhost}, one ending {@code .localhost}, {@code .internal} or
 *       {@code .local}, or one with no dot such as a Docker Compose service ({@code ollama}) — and that resolves, now,
 *       to private addresses only. The lookup catches a single-label name a DNS search domain turns into a public
 *       host. A name that does not resolve is refused: what cannot be checked is not trusted.</li>
 * </ul>
 * <p>The decision is taken once per endpoint, when the adapters are built at start-up, and kept: every bean built
 * from the same URL sees the same answer, and a lookup never runs on a question's path.</p>
 */
enum DataPolicy {

    /** Cloud models are allowed: the business owner accepted that tool results reach the provider (AQ-01). */
    CLOUD_ALLOWED,

    /** Only endpoints inside the company's network ({@link #isLocal}). */
    LOCAL_ONLY;

    /** Four decimal numbers 0–255, no leading zero: the one numeric form every resolver reads the same way. */
    private static final Pattern DOTTED_QUAD = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

    /** Any other host made of numbers: decimal, octal or hex, with or without dots. */
    private static final Pattern NUMERIC = Pattern.compile("(0x[0-9a-f]*|\\d+)(\\.(0x[0-9a-f]*|\\d+))*\\.?");

    private static final Map<String, Boolean> DECISIONS = new ConcurrentHashMap<>();

    /** Resolves a name to its addresses; replaced in tests. */
    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** Whether this policy lets a model at {@code endpoint} be used; a cloud vendor's own API is never local. */
    boolean allows(ModelProvider provider, String endpoint) {
        if (this == CLOUD_ALLOWED) {
            return true;
        }
        return provider == ModelProvider.OPENAI && isLocal(endpoint);
    }

    /** Whether every address {@code url}'s host reaches is private (class javadoc); decided once per URL. */
    static boolean isLocal(String url) {
        String key = url == null ? "" : url.strip();
        return DECISIONS.computeIfAbsent(key, endpoint -> isLocal(endpoint, InetAddress::getAllByName));
    }

    /** The decision itself, with the lookup given. */
    static boolean isLocal(String url, Resolver resolver) {
        String host;
        try {
            host = URI.create(url.strip()).getHost();
            if (host == null || host.isEmpty()) {
                return false;
            }
            host = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
            host = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException | NullPointerException unreadable) {
            return false;
        }
        if (host.contains(":") || DOTTED_QUAD.matcher(host).matches()) {
            try {
                // A literal address: getByName parses it without a lookup.
                return isPrivate(InetAddress.getByName(host));
            } catch (UnknownHostException malformed) {
                return false;
            }
        }
        if (NUMERIC.matcher(host).matches()) {
            return false;
        }
        boolean looksInternal = host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".internal")
                || host.endsWith(".local") || !host.contains(".");
        if (!looksInternal) {
            return false;
        }
        try {
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses == null || addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (!isPrivate(address)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException | SecurityException unresolved) {
            return false;
        }
    }

    private static boolean isPrivate(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || uniqueLocal(address);
    }

    /** fc00::/7, IPv6's private range, which {@link InetAddress#isSiteLocalAddress} does not cover. */
    private static boolean uniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
