package com.stockflow.payment.internal.controller;

import com.stockflow.common.api.PageResponse;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerCredits;
import com.stockflow.customer.api.CustomerLabel;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.payment.internal.controller.dto.CreditRequestResponse;
import com.stockflow.payment.internal.domain.CreditRequest;
import com.stockflow.payment.internal.service.CreditRequestFiles;
import com.stockflow.payment.internal.service.CreditRequestService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Credit requests as the screens show them, with whose they are and who acted by name - one read of the names for a
 * whole page, never one per row; the back office's rows also carry each customer's credit limit now (audit bo-28), read
 * once for the page through {@link CustomerCredits}. A portal customer is shown neither Sales' opinion, nor who in the
 * back office acted, nor the limit.
 */
@Component
class CreditRequestWebMapper {

    private final CustomerService customers;
    private final CustomerCredits credits;
    private final IdentityService identities;

    CreditRequestWebMapper(CustomerService customers, CustomerCredits credits, IdentityService identities) {
        this.customers = customers;
        this.credits = credits;
        this.identities = identities;
    }

    PageResponse<CreditRequestResponse> rows(PageResponse<CreditRequest> page, boolean backOffice) {
        List<UUID> customerIds = page.items().stream().map(CreditRequest::customerId).distinct().toList();
        Map<UUID, CustomerLabel> labels = customers.labels(customerIds);
        Map<UUID, String> names = names(page.items(), backOffice);
        Map<UUID, CreditTerms> terms = backOffice && !customerIds.isEmpty() ? credits.creditTermsOf(customerIds)
                : Map.of();
        return page.map(r -> response(r, labels, names, null, null, backOffice,
                terms.containsKey(r.customerId()) ? terms.get(r.customerId()).creditLimit() : null));
    }

    CreditRequestResponse detail(CreditRequest request, List<CreditRequestFiles.CreditFile> files,
                                 CreditRequestService.Review review, boolean backOffice) {
        return response(request, customers.labels(List.of(request.customerId())), names(List.of(request), backOffice),
                files.stream().map(CreditRequestWebMapper::file).toList(), review == null ? null : review(review),
                backOffice, backOffice && review != null ? review.currentTerms().creditLimit() : null);
    }

    static CreditRequestResponse.File file(CreditRequestFiles.CreditFile f) {
        return new CreditRequestResponse.File(f.id(), f.fileName(), f.contentType(), f.sizeBytes(), f.uploadedBy(),
                f.uploadedAt());
    }

    private Map<UUID, String> names(Collection<CreditRequest> requests, boolean backOffice) {
        if (!backOffice) {
            return Map.of();
        }
        List<UUID> ids = requests.stream()
                .flatMap(r -> Stream.of(r.submittedBy(), r.recommendedBy(), r.decidedBy()))
                .filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : identities.displayNames(ids);
    }

    private static CreditRequestResponse response(CreditRequest r, Map<UUID, CustomerLabel> labels,
                                                  Map<UUID, String> names, List<CreditRequestResponse.File> files,
                                                  CreditRequestResponse.Review review, boolean backOffice,
                                                  java.math.BigDecimal currentCreditLimit) {
        CustomerLabel label = labels.get(r.customerId());
        return new CreditRequestResponse(r.id(), r.customerId(), label == null ? null : label.name(),
                label == null ? null : label.code(), r.source().name(), r.status().name(), r.requestedLimit(),
                r.requestedTermDays(), r.currency(), r.reason(),
                backOffice ? r.submittedBy() : null, backOffice ? names.get(r.submittedBy()) : null, r.submittedAt(),
                backOffice ? r.salesNote() : null, backOffice ? r.salesRecommendedLimit() : null,
                backOffice ? r.recommendedBy() : null, backOffice ? names.get(r.recommendedBy()) : null,
                backOffice ? r.recommendedAt() : null, r.approvedLimit(), r.approvedTermDays(),
                backOffice ? r.decidedBy() : null, backOffice ? names.get(r.decidedBy()) : null, r.decidedAt(),
                r.decisionNote(), r.version(), files, review, backOffice ? currentCreditLimit : null);
    }

    private static CreditRequestResponse.Review review(CreditRequestService.Review r) {
        var history = r.history();
        var position = r.position();
        var terms = r.currentTerms();
        return new CreditRequestResponse.Review(history.settledContracts(), history.paidInstallments(),
                history.onTimeInstallments(), history.onTimeRatio(), history.averageDaysLate(),
                history.paidLast12Months(), position.outstandingReceivables(), position.hasOverdue(),
                position.overdueAmount(), position.oldestOverdueDueDate(), terms.creditLimit(),
                terms.creditTermDays(), terms.configured() ? terms.version() : -1L, r.advice().suggestedLimit(),
                r.advice().warnings().stream().map(Enum::name).toList(), r.minSettledContracts(),
                r.suggestedMonths());
    }
}
