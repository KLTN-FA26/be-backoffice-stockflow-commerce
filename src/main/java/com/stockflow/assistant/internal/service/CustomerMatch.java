package com.stockflow.assistant.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.customer.api.ListCustomersQuery;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A customer as people name one - the company name, whole or in part, or its tax code - resolved the one way every
 * tool that takes a customer needs it: the exact name or tax code wins, a single match is that customer, and none or
 * several come back for the person to choose. Never a guess: the prices or the debt of the wrong "Công ty ABC" would
 * be quoted as fact.
 *
 * <p><b>Who is shown the choice.</b> The search is the customer list's own ({@code GET /api/v1/customers}: a name, an
 * e-mail or a phone by part, a tax code whole), a screen behind {@code customer-customers:READ}. A caller holding it is
 * shown, by name, which customers the text matched. A caller who does not - under the seeded grants the accountant and
 * the order coordinator, who read a customer's terms or prices on screens that reach the customer from an order or a
 * receivable - must name the customer exactly, by its full name or its tax code, and is never shown a list: the names
 * a fragment matches would hand them the customer list their grants leave out. The exact name is compared in Java,
 * because the search also matches parts of e-mails and phone numbers, which must not identify a customer to them.</p>
 *
 * <p>Only the name and the account status are copied out. The rows also carry the e-mail, the phone and the tax code;
 * none of it can reach an answer (design §5.5). A tax code the person typed is matched, never repeated.</p>
 *
 * @param customerId null when the text did not name exactly one customer
 * @param candidates what the text matched when it named none exactly, at most {@value #MAX_CANDIDATES}; always empty for
 *                   a caller who may not list customers
 * @param note       why no customer was chosen, for the model to relay; null when one was
 */
record CustomerMatch(UUID customerId, String name, String status, List<Candidate> candidates, String note) {

    /** Enough to show a person the choice; a longer list means the text is too vague to pick from. */
    static final int MAX_CANDIDATES = 5;

    /**
     * Rows read to look for the exact name or tax code among. The search matches parts of names, e-mails and phone
     * numbers, so a short name ("ABC") may match many customers besides the one called exactly that.
     */
    static final int SEARCHED = 50;

    /** Alphabetical, so the candidates read as a list a person can choose from. */
    private static final String BY_NAME = "fullName";

    /**
     * @param mayList whether the caller may search the customer list ({@code customer-customers:READ}), and so be shown
     *                which customers partly match
     */
    static CustomerMatch find(CustomerService customers, String typed, boolean mayList) {
        // Searched as it is compared: "Công ty  ABC", typed with two blanks or a pasted no-break space, is the
        // customer "Công ty ABC".
        String text = plain(typed);
        String taxCode = taxCodeOf(text);
        PageResponse<CustomerSummary> found = search(customers, text);
        List<CustomerSummary> exact = exact(found, text, taxCode);
        String asTyped = Normalizer.normalize(typed.strip(), Normalizer.Form.NFC);
        if (exact.isEmpty() && !asTyped.equals(text)) {
            // Names are stored as entered: one registered with the same odd blanks is found only as typed.
            PageResponse<CustomerSummary> typedFound = search(customers, asTyped);
            List<CustomerSummary> typedExact = exact(typedFound, text, taxCode);
            if (!typedExact.isEmpty()) {
                found = typedFound;
                exact = typedExact;
            }
        }
        if (exact.size() == 1) {
            return chosen(exact.getFirst());
        }
        if (exact.size() > 1) {
            return none(List.of(), "Several customers are registered under exactly the name '" + text + "'; ask "
                    + "for the tax code of the one meant.");
        }
        if (!mayList) {
            return none(List.of(), "No customer is registered under exactly the name '" + text + "' or has it as "
                    + "tax code. Ask for the customer's full name exactly as registered, or its tax code: this user "
                    + "may not search the customer list, so names that match only in part are not offered.");
        }
        if (found.totalElements() == 1 && found.items().size() == 1) {
            CustomerSummary only = found.items().getFirst();
            if (holdsName(only.fullName(), text)) {
                return chosen(only);
            }
            // The customer search also looks at e-mail, phone and tax code: matched there, it is a guess to confirm.
            return none(List.of(new Candidate(only.fullName(), only.status())), "One customer matches '" + text
                    + "', but not by its name; ask whether it is the one meant, or call again with its full name.");
        }
        if (found.items().isEmpty()) {
            return none(List.of(), "No customer matches '" + text + "'.");
        }
        return none(found.items().stream().limit(MAX_CANDIDATES)
                        .map(customer -> new Candidate(customer.fullName(), customer.status())).toList(),
                "Several customers match '" + text + "' (" + found.totalElements() + " in all"
                        + (found.totalElements() > MAX_CANDIDATES ? ", the first " + MAX_CANDIDATES + " by name "
                        + "listed" : "") + "); ask which one is meant, or call again with its full name or tax code.");
    }

    boolean found() {
        return customerId != null;
    }

    private static PageResponse<CustomerSummary> search(CustomerService customers, String text) {
        return customers.list(new ListCustomersQuery(0, SEARCHED, text, null, BY_NAME));
    }

    /** The customers called exactly {@code text}, or holding {@code taxCode}. */
    private static List<CustomerSummary> exact(PageResponse<CustomerSummary> found, String text, String taxCode) {
        return found.items().stream()
                .filter(customer -> sameName(customer.fullName(), text)
                        || taxCode != null && taxCode.equals(customer.taxCode()))
                .toList();
    }

    private static CustomerMatch chosen(CustomerSummary customer) {
        return new CustomerMatch(customer.customerId(), customer.fullName(), customer.status(), List.of(), null);
    }

    private static CustomerMatch none(List<Candidate> candidates, String note) {
        return new CustomerMatch(null, null, null, candidates, note);
    }

    /** Whether the registered name holds the typed text, case, composition and runs of blanks aside. */
    private static boolean holdsName(String registered, String text) {
        return registered != null
                && plain(registered).toLowerCase(Locale.ROOT).contains(plain(text).toLowerCase(Locale.ROOT));
    }

    /** Case, the composition of accented letters and runs of blanks aside, as a person reads a name. */
    private static boolean sameName(String registered, String text) {
        return registered != null && plain(registered).equalsIgnoreCase(plain(text));
    }

    /** Every Unicode blank, the no-break space of a name pasted from a spreadsheet or a chat included, made one. */
    private static String plain(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).replaceAll("(?U)\\s+", " ").strip();
    }

    /**
     * The canonical form {@code TaxCode.normalise} stores (copied: it is internal to the customer module): blanks and
     * dots dropped, 13 digits read as 10-3. Null when the text is not shaped like a tax code at all.
     */
    static String taxCodeOf(String text) {
        String compact = text.strip().replaceAll("[\\s.]", "");
        if (compact.matches("\\d{13}")) {
            compact = compact.substring(0, 10) + "-" + compact.substring(10);
        }
        return compact.matches("\\d{10}(-\\d{3})?|\\d{12}") ? compact : null;
    }

    /** @param status the account's status: ACTIVE, or BLOCKED, INACTIVE, PENDING_ACTIVATION */
    record Candidate(String customerName, String status) {
    }
}
