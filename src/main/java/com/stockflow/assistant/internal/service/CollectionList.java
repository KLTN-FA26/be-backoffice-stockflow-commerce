package com.stockflow.assistant.internal.service;

import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.i18n.Messages;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerCredits;
import com.stockflow.customer.api.CustomerLabel;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.CustomerDocuments;
import com.stockflow.payment.api.CustomerReceivable;
import com.stockflow.payment.api.InstallmentsToCollect;
import com.stockflow.payment.api.PayableInstallment;
import com.stockflow.payment.api.Receivables;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The installments to chase first (2026-11-27 work features 2.2): the Accountant's and Sales' morning list of money
 * overdue or falling due within the week, the ones to call about first on top, each with the customer, the
 * salesperson who answers for the contract and why it ranks where it does. What the installment list cannot say on its
 * own: which of forty overdue lines matter most today, and whom to send.
 *
 * <p>Deterministic, no model, one read per kind of fact: the installments ({@link InstallmentsToCollect}, payment's
 * own "overdue"), the receivables as the credit check reads them ({@link Receivables}: what else each customer owes
 * overdue, and its exposure after delivery), the credit limits ({@link CustomerCredits}), the customers' names, the
 * contracts' salespeople ({@link CustomerDocuments#contractSalespeople}) and their names. Ranked by
 * {@link CollectionPriority}; at most {@value #MAX_ITEMS} shown, with how many there are.</p>
 */
@Service
public class CollectionList {

    /** Days ahead an installment falls due in and is listed: chased before it is late. */
    static final int DUE_WITHIN_DAYS = 7;
    /** Installments ranked: the most overdue first, so what falls off a longer list is what is least late. */
    static final int READ = 500;
    /** Installments shown. */
    static final int MAX_ITEMS = 50;

    private final InstallmentsToCollect installments;
    private final Receivables receivables;
    private final CustomerCredits credits;
    private final CustomerService customers;
    private final CustomerDocuments documents;
    private final IdentityService identity;
    private final Messages messages;

    CollectionList(InstallmentsToCollect installments, Receivables receivables, CustomerCredits credits,
                CustomerService customers, CustomerDocuments documents, IdentityService identity, Messages messages) {
        this.installments = installments;
        this.receivables = receivables;
        this.credits = credits;
        this.customers = customers;
        this.documents = documents;
        this.identity = identity;
        this.messages = messages;
    }

    /**
     * @param total     how many installments are overdue or due within the week
     * @param truncated whether {@code items} shows fewer than {@code total}
     */
    public record Result(LocalDate today, List<Item> items, int total, boolean truncated) {

        public Result {
            items = List.copyOf(items);
        }
    }

    /**
     * @param salespersonId   who answers for the contract; null when nobody is recorded
     * @param salespersonName that person's name; null with the id
     * @param priority        {@code HIGH}, {@code MEDIUM} or {@code LOW}
     * @param why             the facts that ranked it, as sentences in the reader's language
     */
    public record Item(PayableInstallment installment, String customerName, UUID salespersonId,
                       String salespersonName, String priority, int points, List<String> why) {

        public Item {
            why = List.copyOf(why);
        }
    }

    /** Its use is counted by the caller once this has answered, so a failure is not a use. */
    public Result list() {
        InstallmentsToCollect.Page page = installments.dueWithin(DUE_WITHIN_DAYS, READ);
        LocalDate today = page.today();
        Set<UUID> customerIds = new LinkedHashSet<>();
        Set<UUID> contractIds = new LinkedHashSet<>();
        page.items().forEach(i -> {
            customerIds.add(i.customerId());
            contractIds.add(i.contractId());
        });
        if (customerIds.isEmpty()) {
            return new Result(today, List.of(), page.total(), false);
        }
        Map<String, CustomerReceivable> owed = new HashMap<>();
        receivables.receivables(today, null).stream().filter(r -> customerIds.contains(r.customerId()))
                .forEach(r -> owed.put(key(r.customerId(), r.currency()), r));
        Map<UUID, CreditTerms> terms = credits.creditTermsOf(customerIds);
        Map<UUID, CustomerLabel> labels = customers.labels(customerIds);
        Map<UUID, UUID> salespeople = documents.contractSalespeople(contractIds);
        Map<UUID, String> staffNames = salespeople.isEmpty() ? Map.of()
                : identity.displayNames(new LinkedHashSet<>(salespeople.values()));

        List<Ranked> ranked = new ArrayList<>();
        for (PayableInstallment installment : page.items()) {
            CollectionPriority.Customer customer = customer(installment, owed, terms);
            ranked.add(new Ranked(installment, customer, CollectionPriority.of(installment, customer)));
        }
        ranked.sort(Comparator.comparingInt((Ranked r) -> r.rank().points()).reversed()
                .thenComparing(r -> r.installment().daysOverdue(), Comparator.reverseOrder())
                .thenComparing(r -> r.installment().outstanding(), Comparator.reverseOrder())
                .thenComparing(r -> r.installment().dueDate())
                .thenComparing(r -> r.installment().contractNumber())
                .thenComparingInt(r -> r.installment().seq()));
        List<Item> items = ranked.stream().limit(MAX_ITEMS).map(r -> {
            PayableInstallment i = r.installment();
            CustomerLabel label = labels.get(i.customerId());
            UUID sales = salespeople.get(i.contractId());
            return new Item(i, label == null ? null : label.name(), sales, sales == null ? null : staffNames.get(sales),
                    r.rank().level().name(), r.rank().points(), why(i, r.customer(), r.rank(), today));
        }).toList();
        int total = Math.max(page.total(), page.items().size());
        return new Result(today, items, total, total > items.size());
    }

    private record Ranked(PayableInstallment installment, CollectionPriority.Customer customer,
                          CollectionPriority.Rank rank) {
    }

    /**
     * What else the customer owes, as the credit check reads it: overdue is {@code overdue() + signingOverdue}, the
     * installments of every milestone past their due day (the receivables' javadoc); this installment's own share is
     * taken off. Exposure is receivable plus committed, against the limit accounting approved.
     */
    private static CollectionPriority.Customer customer(PayableInstallment installment,
                                                        Map<String, CustomerReceivable> owed,
                                                        Map<UUID, CreditTerms> terms) {
        CustomerReceivable receivable = owed.get(key(installment.customerId(), installment.currency()));
        CreditTerms credit = terms.get(installment.customerId());
        BigDecimal limit = credit == null || !credit.configured() ? null : credit.creditLimit();
        if (receivable == null) {
            return new CollectionPriority.Customer(BigDecimal.ZERO, BigDecimal.ZERO, limit);
        }
        BigDecimal overdue = receivable.overdue().add(receivable.signingOverdue());
        if (installment.overdue()) {
            overdue = overdue.subtract(installment.outstanding());
        }
        return new CollectionPriority.Customer(overdue.max(BigDecimal.ZERO),
                receivable.receivable().add(receivable.committedNotReached()), limit);
    }

    private List<String> why(PayableInstallment i, CollectionPriority.Customer customer, CollectionPriority.Rank rank,
                             LocalDate today) {
        List<String> said = new ArrayList<>();
        for (CollectionPriority.Why why : rank.why()) {
            said.add(switch (why) {
                case OVERDUE -> messages.get("assistant.collections.why.overdue",
                        "Overdue by " + i.daysOverdue() + " days (due " + DocumentFormats.date(i.dueDate()) + ")",
                        String.valueOf(i.daysOverdue()), DocumentFormats.date(i.dueDate()));
                case DUE_SOON -> {
                    long days = ChronoUnit.DAYS.between(today, i.dueDate());
                    yield days == 0
                            ? messages.get("assistant.collections.why.dueToday",
                                    "Due today (" + DocumentFormats.date(i.dueDate()) + ")",
                                    DocumentFormats.date(i.dueDate()))
                            : messages.get("assistant.collections.why.dueSoon",
                                    "Due in " + days + " days (" + DocumentFormats.date(i.dueDate()) + ")",
                                    String.valueOf(days), DocumentFormats.date(i.dueDate()));
                }
                case LARGE_AMOUNT -> messages.get("assistant.collections.why.largeAmount",
                        DocumentFormats.money(i.outstanding()) + " VND still owed",
                        DocumentFormats.money(i.outstanding()));
                case OTHER_OVERDUE -> messages.get("assistant.collections.why.otherOverdue",
                        "The customer has " + DocumentFormats.money(customer.otherOverdue())
                                + " VND overdue on other installments", DocumentFormats.money(customer.otherOverdue()));
                case OVER_CREDIT_LIMIT -> messages.get("assistant.collections.why.overCreditLimit",
                        "The customer owes " + DocumentFormats.money(customer.exposure()) + " VND, over its credit "
                                + "limit of " + DocumentFormats.money(customer.creditLimit()) + " VND",
                        DocumentFormats.money(customer.exposure()), DocumentFormats.money(customer.creditLimit()));
            });
        }
        return said;
    }

    private static String key(UUID customerId, String currency) {
        return customerId + "/" + currency;
    }
}
