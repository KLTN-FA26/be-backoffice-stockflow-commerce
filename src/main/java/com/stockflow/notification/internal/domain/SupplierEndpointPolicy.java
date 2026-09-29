package com.stockflow.notification.internal.domain;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** The same allowlist protects supplier setup, PO submission and each outbound retry. */
public record SupplierEndpointPolicy(Set<String> hosts) {
    public SupplierEndpointPolicy(String hosts) {
        this(Arrays.stream(hosts.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet()));
    }

    public void validate(String channel, String recipient) {
        if ("EMAIL".equals(channel)) {
            if (recipient == null || recipient.isBlank()) throw new IllegalArgumentException("Supplier email is required");
            return;
        }
        if (!"API".equals(channel)) throw new IllegalArgumentException("Unsupported supplier channel");
        URI uri = URI.create(recipient == null ? "" : recipient);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getQuery() != null
                || uri.getPort() != -1 && uri.getPort() != 443
                || !hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Supplier API host is not approved by the operator");
        }
    }
}
