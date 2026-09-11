package com.stockflow.common.persistence;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.function.Function;

/**
 * The two paging operations every list endpoint needs: build a bounded {@link Pageable}, and turn
 * the result into the wire format.
 *
 * <p>Both are one-liners, and both are worth having in one place — because the interesting part is
 * the limit, and a limit that is enforced in some endpoints and not others is the same as no limit
 * at all.</p>
 */
public final class Pages {

    /**
     * Hard ceiling on page size.
     *
     * <p>Without one, {@code ?size=1000000} is a request to load the whole table into heap and
     * serialise it. That is not a hypothetical: it is how a list endpoint becomes an accidental
     * data export, and how one client retry loop takes the process down with an
     * {@code OutOfMemoryError}. 200 is generous for a UI and small enough to survive being asked
     * for repeatedly.</p>
     */
    public static final int MAX_PAGE_SIZE = 200;

    public static final int DEFAULT_PAGE_SIZE = 20;

    private Pages() {
    }

    /**
     * @param page zero-based page number
     * @param size requested size; a value above {@link #MAX_PAGE_SIZE} is rejected rather than
     *             silently clamped — a client asking for 10 000 rows and quietly receiving 200 will
     *             read the short page as "no more data" and lose the rest
     */
    public static Pageable of(int page, int size, Sort sort) {
        if (page < 0) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER,
                    "Page number must not be negative, got " + page);
        }
        if (size < 1) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER,
                    "Page size must be at least 1, got " + size);
        }
        if (size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER,
                    "Page size must not exceed %d, got %d".formatted(MAX_PAGE_SIZE, size));
        }
        return PageRequest.of(page, size, sort);
    }

    public static Pageable of(int page, int size) {
        return of(page, size, Sort.unsorted());
    }

    /** Wire format for a page of already-mapped items. */
    public static <T> PageResponse<T> toResponse(Page<T> page) {
        return PageResponse.of(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    /**
     * Map entities to DTOs and wrap, in one step.
     *
     * <p>The usual shape at a controller: {@code Pages.toResponse(entities, mapper::toResponse)}.
     * Doing it in one call keeps the entity type from escaping into a variable that then gets
     * returned by accident.</p>
     */
    public static <E, T> PageResponse<T> toResponse(Page<E> page,
                                                    Function<? super E, ? extends T> mapper) {
        return PageResponse.of(
                page.getContent().stream().<T>map(mapper::apply).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
