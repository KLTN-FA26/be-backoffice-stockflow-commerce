package com.stockflow.order.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.customer.api.Responsible;
import com.stockflow.customer.api.ResponsibleSales;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who hears about a contract or an order on Sales' side (WF-02 FLOW-4 as changed by the sales-owner plan on 2026-11-28;
 * kltn-docs 00 §6, 15 BR-22, 18 BR-20): whoever looks after the order's <b>customer</b> today - its owner, the owner's
 * cover while one is in force, or, when the customer has no active owner, the active Sales Managers (else the System
 * Admins) with the notice marked "cần phân công". The answer is {@link ResponsibleSales}'s, the one rule chat and every
 * notice follow; it replaced "who sent the contract, else every Sales account", which gave an order two salespeople
 * (chat's and the follow-ups') and told the whole team when one left. Never every salesperson (owner decision D6).
 * It only chooses who is told; who may act is unchanged.
 */
@Component
class SalesRecipients {

    private final ResponsibleSales responsible;
    private final Clock clock;

    SalesRecipients(ResponsibleSales responsible, Clock clock) {
        this.responsible = responsible;
        this.clock = clock;
    }

    /**
     * Who is told, and whether the customer has no active owner (the notice says "cần phân công").
     *
     * @param needsAssignment the recipients are the managers standing in for a missing owner
     */
    record Recipients(List<UUID> userIds, boolean needsAssignment) {

        Recipients {
            userIds = List.copyOf(userIds);
        }

        boolean nobody() {
            return userIds.isEmpty();
        }

        /** The same people plus {@code first} ahead of them, each once. */
        Recipients withFirst(UUID first) {
            if (first == null) {
                return this;
            }
            Set<UUID> all = new LinkedHashSet<>();
            all.add(first);
            all.addAll(userIds);
            return new Recipients(List.copyOf(all), needsAssignment);
        }
    }

    /** A chooser for one run: each customer is asked once, and {@link Chooser#load} asks for a batch in one go. */
    Chooser chooser() {
        return new Chooser(BusinessCalendar.date(clock.instant()));
    }

    final class Chooser {

        private final LocalDate day;
        private final Map<UUID, Responsible> known = new HashMap<>();
        private final Set<UUID> asked = new java.util.HashSet<>();

        private Chooser(LocalDate day) {
            this.day = day;
        }

        /** Reads the customers not read yet, in one batched question (a constant handful of statements). */
        Chooser load(Collection<UUID> customerIds) {
            List<UUID> missing = customerIds.stream().filter(Objects::nonNull).filter(id -> !asked.contains(id))
                    .distinct().toList();
            if (!missing.isEmpty()) {
                known.putAll(responsible.responsibleFor(missing, day));
                asked.addAll(missing);
            }
            return this;
        }

        /** Who looks after the customer; nobody for an unknown or null one. */
        Recipients of(UUID customerId) {
            if (customerId == null) {
                return new Recipients(List.of(), false);
            }
            load(List.of(customerId));
            Responsible who = known.get(customerId);
            return who == null ? new Recipients(List.of(), false)
                    : new Recipients(who.recipients(), who.needsAssignment());
        }
    }
}
