package com.stockflow.fulfillment.internal.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The addresses a carrier's unsigned webhook may come from (WF-05 fix plan item 6): single addresses and CIDR blocks,
 * IPv4 or IPv6. An empty list allows every address - the list is an option, on only where the carrier publishes the
 * addresses it calls from.
 *
 * <p>Entries are address literals only. A name would be looked up, and a webhook's defence must not depend on a DNS
 * answer: an IPv4 literal is read here, and only text with a colon in it - which can be nothing but an IPv6 literal -
 * is handed to {@link InetAddress}, which then parses and never resolves.</p>
 */
final class IpAllowList {

    private static final Pattern LITERAL = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private record Block(byte[] network, int prefix) {

        boolean holds(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int whole = prefix / 8;
            for (int i = 0; i < whole; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (address[whole] & mask) == (network[whole] & mask);
        }
    }

    private final List<Block> blocks;

    private IpAllowList(List<Block> blocks) {
        this.blocks = blocks;
    }

    /**
     * @param entries addresses ({@code 203.0.113.7}) and blocks ({@code 203.0.113.0/24}, {@code 2001:db8::/32}); null
     *                or empty: every address is allowed
     * @throws IllegalArgumentException naming the entry that is neither
     */
    static IpAllowList of(List<String> entries) {
        List<Block> blocks = new ArrayList<>();
        if (entries != null) {
            for (String raw : entries) {
                String entry = raw == null ? "" : raw.strip();
                if (entry.isEmpty()) {
                    continue;
                }
                int slash = entry.indexOf('/');
                byte[] network = bytes(slash < 0 ? entry : entry.substring(0, slash));
                if (network == null) {
                    throw new IllegalArgumentException("'" + entry + "' is not an IP address or a CIDR block");
                }
                int prefix = network.length * 8;
                if (slash >= 0) {
                    try {
                        prefix = Integer.parseInt(entry.substring(slash + 1));
                    } catch (NumberFormatException notANumber) {
                        prefix = -1;
                    }
                    if (prefix < 0 || prefix > network.length * 8) {
                        throw new IllegalArgumentException("'" + entry + "' is not an IP address or a CIDR block");
                    }
                }
                blocks.add(new Block(network, prefix));
            }
        }
        return new IpAllowList(List.copyOf(blocks));
    }

    /** Whether any address is allowed (no list was configured). */
    boolean open() {
        return blocks.isEmpty();
    }

    /** Whether a call from {@code remoteAddress} is allowed; an address that cannot be read is not. */
    boolean allows(String remoteAddress) {
        if (blocks.isEmpty()) {
            return true;
        }
        byte[] address = remoteAddress == null ? null : bytes(remoteAddress.strip());
        return address != null && blocks.stream().anyMatch(block -> block.holds(address));
    }

    /** The address's bytes (an IPv4-mapped IPv6 address as IPv4); null when it is not an address literal. */
    private static byte[] bytes(String literal) {
        if (!LITERAL.matcher(literal).matches()) {
            return null;
        }
        if (literal.indexOf(':') < 0) {
            return ipv4(literal);
        }
        try {
            // With a colon in it the text can only be an IPv6 literal: it is parsed, never looked up.
            return InetAddress.getByName(literal).getAddress();
        } catch (UnknownHostException notAnAddress) {
            return null;
        }
    }

    /** Four decimal octets; anything else would be taken for a host name and looked up, so it is read here. */
    private static byte[] ipv4(String literal) {
        String[] parts = literal.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] address = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("\\d{1,3}") || Integer.parseInt(parts[i]) > 255) {
                return null;
            }
            address[i] = (byte) Integer.parseInt(parts[i]);
        }
        return address;
    }
}
