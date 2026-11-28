package com.stockflow.order.internal.service;

import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.UserLabel;
import com.stockflow.order.internal.domain.SalesContacts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The salesperson to show beside each customer of the back office list (owner decision 2026-11-27): who sent the
 * customer's most recent quote or contract, named, and whether that account is still active - so a departed
 * salesperson is shown as such instead of looking like someone to call. Derived on every read from the documents
 * themselves (kltn-docs 00 §2: responsibility belongs to a document, not to the customer); nothing is stored and no
 * business rule reads it.
 *
 * <p>The customer module cannot ask this itself: order depends on customer, so the list calls
 * {@code GET /orders/sales-contacts} once per page beside {@code GET /customers}, as it does for the credit columns.
 * Two reads whatever the page size: one statement over the documents, one over the accounts.</p>
 */
@Service
@Transactional(readOnly = true)
public class SalesContactService {

    /** A customer's salesperson as the list shows it. */
    public record SalesContact(UUID customerId, UUID salesUserId, String salesName, boolean salesActive,
                               SalesContacts.Source source, String documentNumber, Instant at) { }

    private final SalesContacts contacts;
    private final IdentityService identity;

    SalesContactService(SalesContacts contacts, IdentityService identity) {
        this.contacts = contacts;
        this.identity = identity;
    }

    /**
     * @return one contact per distinct customer that has a sent document, in the order given; a customer with none (or
     *         an id nobody knows) is left out
     */
    public List<SalesContact> of(Collection<UUID> customerIds) {
        List<UUID> ids = customerIds == null ? List.of()
                : customerIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        var latest = contacts.latestOf(ids);
        var people = identity.userLabels(latest.values().stream().map(SalesContacts.Latest::salesUserId).toList());
        return ids.stream().filter(latest::containsKey).map(id -> {
            SalesContacts.Latest last = latest.get(id);
            UserLabel person = people.get(last.salesUserId());
            // fk_quote_sent_by / fk_sales_contract_sent_by / _signed_by make every sender an account; should one ever
            // be missing, it is shown as gone rather than as someone to call.
            return new SalesContact(id, last.salesUserId(), person == null ? null : person.displayName(),
                    person != null && person.active(), last.source(), last.documentNumber(), last.at());
        }).toList();
    }
}
