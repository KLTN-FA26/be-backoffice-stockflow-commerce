package com.stockflow.chat.internal.domain;

import java.util.List;

/** One page of a list and how many there are in all; the repository's answer, free of Spring Data's types. */
public record Slice<T>(List<T> items, long total) {

    public Slice {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
