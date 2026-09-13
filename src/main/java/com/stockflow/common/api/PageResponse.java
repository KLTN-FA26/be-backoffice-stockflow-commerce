package com.stockflow.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.function.Function;

/**
 * Standard pagination result, so no two modules invent their own field names.
 *
 * <p>{@code hasNext} and {@code hasPrevious} are derived rather than left to the client. Computing
 * them looks trivial — {@code page + 1 < totalPages} — and is exactly the kind of arithmetic that
 * gets written slightly differently in three screens, with one of them being off by one on the last
 * page. Deriving them once, on the side that already knows the totals, removes the question.</p>
 */
@Schema(name = "Page", description = "One page of results")
public record PageResponse<T>(

        List<T> items,

        @Schema(description = "Zero-based page number", example = "0")
        int page,

        int size,
        long totalElements,
        int totalPages,

        @Schema(description = "Whether a further page exists - do not recompute this client-side")
        boolean hasNext,

        boolean hasPrevious
) {

    public PageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Canonical factory. Derives the page count and both flags from the totals. */
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(
                items, page, size, totalElements, totalPages,
                page + 1 < totalPages,
                page > 0);
    }

    /** An empty page, for a filter that matched nothing. */
    public static <T> PageResponse<T> empty(int page, int size) {
        return of(List.of(), page, size, 0);
    }

    /** Maps every item, keeping the page metadata — for translating an api-layer summary into its
     *  HTTP wire-format response without re-deriving the totals. */
    public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = items.stream().<R>map(mapper).toList();
        return new PageResponse<R>(mapped, page, size, totalElements, totalPages, hasNext, hasPrevious);
    }
}
