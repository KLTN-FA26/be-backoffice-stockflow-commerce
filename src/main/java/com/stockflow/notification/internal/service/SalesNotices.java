package com.stockflow.notification.internal.service;

import com.stockflow.identity.api.SalesStaff;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.identity.api.StaffMember;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.SalesAudiences;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The one way a notice about a customer reaches Sales (sales-owner plan §2.2, §10.2; kltn-docs 00 §6, 18 BR-20): to
 * whoever looks after the customer today - its owner, the owner's cover, or, with no active owner, the active Sales
 * Managers (else the System Admins), the notice then marked "cần phân công". <b>Never every salesperson</b> (owner
 * decision D6, 2026-11-28): telling the whole team about one customer opened the door to taking it.
 *
 * <p>Who that is comes from {@link SalesAudiences} (implemented by order, from {@code customer :: api}), asked when the
 * notice is written. A notice whose audience is a permission (a delay alert's, 17 BR-30) keeps the other people that
 * permission names - the coordinator, the managers, a warehouse's manager - and drops the salespeople who do not look
 * after the customer ({@link #exceptOtherSales}).</p>
 */
@Component
class SalesNotices {

    private final SalesAudiences audiences;
    private final StaffDirectory staff;
    private final SalesStaff salesStaff;

    SalesNotices(SalesAudiences audiences, StaffDirectory staff, SalesStaff salesStaff) {
        this.audiences = audiences;
        this.staff = staff;
        this.salesStaff = salesStaff;
    }

    SalesAudiences audiences() {
        return audiences;
    }

    /** The audience's ACTIVE accounts with their e-mail, for a notice that is also mailed ({@link StaffNotices}). */
    List<StaffContact> contacts(SalesAudience audience) {
        List<StaffContact> contacts = new ArrayList<>();
        for (UUID id : audience.userIds()) {
            staff.activeContact(id).ifPresentOrElse(contacts::add, () -> contacts.add(new StaffContact(id, null)));
        }
        return contacts;
    }

    /**
     * The holders of a permission without the salespeople who do not look after this customer, plus the audience:
     * the people the permission names for their role (coordinator, managers, warehouse managers) stay; of Sales, only
     * the owner or cover - or, with no active owner, the managers - remain.
     *
     * <p>Only a <i>plain</i> salesperson is dropped: one whose every role is a Sales role. Someone who holds SALES_STAFF
     * besides another role - a Production Coordinator or a Sales Manager who also sells - is told as that role, as
     * before customers had owners. Judged on the roles held, not on which of them grants the permission: who grants
     * what is the role matrix's business, which this directory does not read.</p>
     */
    List<UUID> exceptOtherSales(Collection<StaffContact> holders, SalesAudience audience) {
        Set<UUID> salespeople = salesStaff.activeSalesAgents().stream()
                .filter(member -> member.roles().stream().allMatch(role -> SalesStaff.SALES_ROLES.contains(
                        role.code())))
                .map(StaffMember::userId).collect(Collectors.toSet());
        Set<UUID> told = new LinkedHashSet<>();
        holders.stream().map(StaffContact::userId).filter(Objects::nonNull).filter(id -> !salespeople.contains(id))
                .forEach(told::add);
        told.addAll(audience.userIds());
        return List.copyOf(told);
    }

    /** {@code message} with the "cần phân công" line when the customer has no active owner. */
    static InboxMessage marked(InboxMessage message, boolean needsAssignment) {
        if (!needsAssignment) {
            return message;
        }
        String mark = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"))
                .getString("inbox.sales.needs-assignment");
        String body = message.body() == null || message.body().isBlank() ? mark : message.body() + "\n" + mark;
        if (body.length() > InboxMessage.BODY_MAX) {
            body = body.substring(0, InboxMessage.BODY_MAX);
        }
        return new InboxMessage(message.category(), message.severity(), message.title(), body, message.link(),
                message.sourceType(), message.sourceId(), message.dedupeKey());
    }
}
