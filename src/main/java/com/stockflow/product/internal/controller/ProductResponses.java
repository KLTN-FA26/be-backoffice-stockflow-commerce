package com.stockflow.product.internal.controller;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.product.api.ProductDiscontinuation;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.internal.controller.dto.ProductDiscontinuationResponse;
import com.stockflow.product.internal.controller.dto.ProductResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Builds {@link ProductResponse}s with what the screens otherwise looked up themselves: who submitted and who
 * approved the product, by name, and whether the caller may approve it.
 *
 * <p>The names are asked of identity once per response, or once per page with every id of the page gathered,
 * never once per row. {@code canApprove} repeats the rule the service enforces on approval (BR-PRD-003: the
 * submitter approves only with {@code product-lifecycle:APPROVE}) so the screen no longer compares the submitter's
 * id with its own; the service still decides.</p>
 */
@Component
class ProductResponses {

    private final ProductWebMapper mapper;
    private final IdentityService identity;
    private final CurrentUserProvider users;

    ProductResponses(ProductWebMapper mapper, IdentityService identity, CurrentUserProvider users) {
        this.mapper = mapper;
        this.identity = identity;
        this.users = users;
    }

    ProductResponse toResponse(ProductSummary product) {
        Map<UUID, String> names = namesOf(List.of(product));
        Optional<CurrentUser> caller = users.current();
        return mapper.toResponse(product, name(names, product.submittedBy()), name(names, product.approvedBy()),
                canApprove(product, caller));
    }

    ProductResponse toDetail(ProductSummary product, List<ProductSummary> possibleDuplicates) {
        Map<UUID, String> names = namesOf(List.of(product));
        return mapper.toDetail(product, possibleDuplicates, name(names, product.submittedBy()),
                name(names, product.approvedBy()), canApprove(product, users.current()));
    }

    PageResponse<ProductResponse> toResponses(PageResponse<ProductSummary> page) {
        Map<UUID, String> names = namesOf(page.items());
        Optional<CurrentUser> caller = users.current();
        return page.map(product -> mapper.toResponse(product, name(names, product.submittedBy()),
                name(names, product.approvedBy()), canApprove(product, caller)));
    }

    ProductDiscontinuationResponse toResponse(ProductDiscontinuation discontinuation) {
        return mapper.toResponse(discontinuation, toResponse(discontinuation.product()));
    }

    private Map<UUID, String> namesOf(List<ProductSummary> products) {
        List<UUID> ids = products.stream()
                .flatMap(product -> Stream.of(product.submittedBy(), product.approvedBy()))
                .filter(java.util.Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : identity.displayNames(ids);
    }

    private static String name(Map<UUID, String> names, UUID userId) {
        return userId == null ? null : names.get(userId);
    }

    /** False for an unknown caller: nobody is told they may approve on a guess. */
    static boolean canApprove(ProductSummary product, Optional<CurrentUser> caller) {
        if (product.status() != ProductStatus.PENDING_APPROVAL || caller.isEmpty()) {
            return false;
        }
        CurrentUser user = caller.get();
        if (!user.can(ProductResources.PRODUCTS, Action.APPROVE)) {
            return false;
        }
        boolean ownSubmission = user.userId() != null && user.userId().equals(product.submittedBy());
        return !ownSubmission || user.can(ProductResources.LIFECYCLE, Action.APPROVE);
    }
}
