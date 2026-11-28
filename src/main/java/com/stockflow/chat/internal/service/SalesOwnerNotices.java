package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.SalesStaff;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * What the chat tells people, through the bell, about who looks after a customer (sales-owner plan BR-S05, S06, S08;
 * kltn-docs 16 BR-13, BR-14):
 * <ul>
 *   <li><b>the chat supervisors</b> (holders of {@code chat-supervision:UPDATE}, who assign conversations): a customer
 *       with no Sales owner at work wrote and waits in the queue; an agent's account stopped working and their open
 *       conversations are back in the queue, how many;</li>
 *   <li><b>the Sales Managers</b> (else the System Admins, plan §4 case 25), who change owners: a salesperson became a
 *       customer's owner by claiming its conversation, so a manager can change it back (BR-S05);</li>
 *   <li><b>the customer</b>, when their salesperson changed and no conversation of theirs is open to carry the line
 *       (BR-S06, D3).</li>
 * </ul>
 * Posted in the caller's transaction, deduplicated by key; titles and bodies are the system's own words from the
 * Vietnamese bundle, like {@link ChatNotifier}'s - never what anyone typed.
 */
@Component
public class SalesOwnerNotices {

    private static final String ASSIGNS_CONVERSATIONS = ChatCallers.SUPERVISION + ":UPDATE";

    private final Inbox inbox;
    private final IdentityService identity;
    private final SalesStaff salesStaff;
    private final CustomerService customers;

    public SalesOwnerNotices(Inbox inbox, IdentityService identity, SalesStaff salesStaff, CustomerService customers) {
        this.inbox = inbox;
        this.identity = identity;
        this.salesStaff = salesStaff;
        this.customers = customers;
    }

    /** A customer with no Sales owner at work opened {@code c}, which waits in the queue (16 §4.1 step 2). */
    public void queued(Conversation c) {
        List<UUID> supervisors = supervisors();
        if (supervisors.isEmpty()) {
            return;
        }
        ResourceBundle m = bundle();
        inbox.post(supervisors, new InboxMessage("CHAT", InboxSeverity.INFO, m.getString("inbox.chat.queued.title"),
                m.getString("inbox.chat.queued.body").formatted(customerName(c.customerId())),
                ChatNotifier.STAFF_LINK + c.id(), "CHAT_CONVERSATION", c.id().toString(), "chat-queued:" + c.id()));
    }

    /** {@code claimerId} became the customer's owner by claiming {@code c} (BR-S05): the managers may change it. */
    public void claimed(Conversation c, UUID claimerId) {
        List<UUID> managers = managers();
        if (managers.isEmpty()) {
            return;
        }
        ResourceBundle m = bundle();
        String claimer = name(claimerId);
        inbox.post(managers, new InboxMessage("CHAT", InboxSeverity.INFO,
                m.getString("inbox.chat.claimed.title").formatted(claimer),
                m.getString("inbox.chat.claimed.body").formatted(customerName(c.customerId()), claimer),
                ChatNotifier.STAFF_LINK + c.id(), "SALES_OWNER", c.customerId().toString(),
                "chat-claimed:" + c.customerId() + ":" + claimerId + ":" + c.id()));
    }

    /**
     * {@code count} open conversations of {@code agentId}, whose account was locked or disabled at {@code at}, are back
     * in the queue (BR-S08): the supervisors assign them. Once per deactivation.
     */
    public void requeued(UUID agentId, int count, Instant at) {
        List<UUID> supervisors = supervisors();
        if (supervisors.isEmpty() || count <= 0) {
            return;
        }
        ResourceBundle m = bundle();
        inbox.post(supervisors, new InboxMessage("CHAT", InboxSeverity.WARN,
                m.getString("inbox.chat.requeued.title").formatted(count),
                m.getString("inbox.chat.requeued.body").formatted(name(agentId)), "/admin/chat", "USER",
                agentId.toString(), "chat-requeued:" + agentId + ":" + at.toEpochMilli()));
    }

    /**
     * The customer's salesperson is now {@code ownerName} and no conversation of theirs is open to say so (BR-S06):
     * their portal account hears of it on the bell, once per change.
     */
    public void ownerChanged(UUID customerId, String ownerName, Instant changedAt) {
        UUID account = customers.findById(customerId).map(customer -> customer.userId()).orElse(null);
        if (account == null) {
            return;
        }
        ResourceBundle m = bundle();
        inbox.post(List.of(account), new InboxMessage("CHAT", InboxSeverity.INFO,
                m.getString("inbox.chat.ownerChanged.title"),
                m.getString("inbox.chat.ownerChanged.body").formatted(ownerName), "/chat", "SALES_OWNER",
                customerId.toString(), "chat-owner-changed:" + customerId + ":" + changedAt.toEpochMilli()));
    }

    private List<UUID> supervisors() {
        return identity.notificationRecipientsHolding(ASSIGNS_CONVERSATIONS, null).stream()
                .map(StaffContact::userId).toList();
    }

    private List<UUID> managers() {
        List<UUID> managers = salesStaff.activeSalesManagers();
        return managers.isEmpty() ? salesStaff.activeSystemAdmins() : managers;
    }

    private String name(UUID userId) {
        return identity.displayNames(List.of(userId)).getOrDefault(userId, "?");
    }

    private String customerName(UUID customerId) {
        Map<UUID, String> names = customers.fullNames(List.of(customerId));
        return names.getOrDefault(customerId, "?");
    }

    private static ResourceBundle bundle() {
        return ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
    }
}
