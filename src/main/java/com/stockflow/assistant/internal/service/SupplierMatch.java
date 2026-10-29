package com.stockflow.assistant.internal.service;

import com.stockflow.procurement.api.SupplierService;
import com.stockflow.procurement.api.SupplierSummary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A supplier as people name one (code, name or tax code, whole or in part), resolved the one way every tool that takes
 * a supplier needs it: an exact code wins, a single match is that supplier, and none or several come back as candidates
 * for the person to choose from. Never a guess: picking one of several "Xưởng ..." would answer "nothing ordered" or
 * "does not sell it" about the wrong supplier.
 *
 * <p>The search is the supplier list's own. Its rows also carry the contact person, phone, e-mail and tax code; only
 * the code, name and status are copied out, so none of the rest can reach an answer (design §5.5).</p>
 *
 * @param supplierId null when the text did not name exactly one supplier
 * @param candidates what the text matched when it did not name exactly one, at most {@value #MAX_CANDIDATES}
 * @param note       why no supplier was chosen, for the model to relay; null when one was
 */
record SupplierMatch(UUID supplierId, String code, String name, String status, List<Candidate> candidates,
                     String note) {

    /** Enough to show a person the choice; a longer list means the text is too vague to pick from. */
    static final int MAX_CANDIDATES = 5;

    static SupplierMatch find(SupplierService suppliers, String text) {
        List<SupplierSummary> found = suppliers.list(0, MAX_CANDIDATES + 1, text, null, null).items();
        Optional<SupplierSummary> exact = found.stream()
                .filter(supplier -> supplier.code().equalsIgnoreCase(text)).findFirst();
        if (exact.isPresent() || found.size() == 1) {
            SupplierSummary supplier = exact.orElse(found.getFirst());
            return new SupplierMatch(supplier.supplierId(), supplier.code(), supplier.name(), supplier.status(),
                    List.of(), null);
        }
        return new SupplierMatch(null, null, null, null,
                found.stream().limit(MAX_CANDIDATES).map(supplier -> new Candidate(supplier.code(), supplier.name()))
                        .toList(),
                found.isEmpty() ? "No supplier matches '" + text + "'."
                        : "Several suppliers match '" + text
                        + "'; ask which one is meant, or call again with its code.");
    }

    boolean found() {
        return supplierId != null;
    }

    record Candidate(String supplierCode, String supplierName) {
    }
}
