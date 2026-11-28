package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.Visibility;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.CustomerSalesOwnerChanged;
import com.stockflow.contracts.StaffAccountDeactivated;
import com.stockflow.customer.api.CustomerSalesOwner;
import com.stockflow.customer.api.Responsible;
import com.stockflow.customer.api.ResponsibleSales;
import com.stockflow.customer.api.SalesOwnerState;
import com.stockflow.customer.api.SalesOwnership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * The conversations that change hands with the customer or the agent, not with a person's click (sales-owner plan
 * BR-S06, BR-S08; kltn-docs 16 BR-14). Called after commit by {@link SalesOwnerEventsListener}, each in a transaction
 * of its own; conversations are locked one by one in id order, so two of these never wait on each other in a circle.
 *
 * <p><b>The customer's owner changed</b> (assigned, changed, claimed, transferred in bulk, returned): every open
 * conversation of the customer goes to the new owner, keeping its status (16 §5), with a line for the staff "Chuyển từ
 * A sang B: lý do" and a TRANSFERRED line in its log; the customer reads "Từ nay anh/chị được B hỗ trợ" in their
 * newest open conversation when the change says so ({@code notifyCustomer}, D3), on the bell when none is open. A
 * change is applied once ({@code chat.sales_owner_change_applied}), and not at all once it is stale - the customer has
 * another owner since: that later change carries the truth.</p>
 *
 * <p><b>An agent's account was locked or disabled</b>: their open conversations go back to the queue, flagged first
 * ({@link Conversation#requeueFrom}), and the supervisors hear how many. A redelivered event finds none left; one that
 * comes after the account was enabled again does nothing at all.</p>
 *
 * <p>Neither moves anything when a cover starts or ends (owner decision F9): a cover decides who gets <i>new</i>
 * conversations ({@link SalesRouter}) and notices, never who has the open ones; a manager hands those over by hand.</p>
 */
@Service
public class ConversationReassignment {

    private static final Logger log = LoggerFactory.getLogger(ConversationReassignment.class);

    /** The log's note on a conversation that followed the customer's owner. A stored note, like every log note: English. */
    static final String FOLLOWED_OWNER_NOTE = "Followed the customer's new Sales owner";

    private final ConversationRepository conversations;
    private final ConversationWriter writer;
    private final SalesOwnership ownership;
    private final AgentDirectory agents;
    private final SalesOwnerNotices notices;
    /** The owner read again once the conversations are locked: a statement of its own, never a cached entity. */
    private final ResponsibleSales responsible;

    public ConversationReassignment(ConversationRepository conversations, ConversationWriter writer,
                                    SalesOwnership ownership, AgentDirectory agents, SalesOwnerNotices notices,
                                    ResponsibleSales responsible) {
        this.responsible = responsible;
        this.conversations = conversations;
        this.writer = writer;
        this.ownership = ownership;
        this.agents = agents;
        this.notices = notices;
    }

    /**
     * BR-S06: the customer's open conversations follow {@code changed.toUserId()}.
     *
     * @return how many conversations moved; 0 for a change applied before, or stale
     */
    @Transactional
    public int followOwner(CustomerSalesOwnerChanged changed) {
        Optional<CustomerSalesOwner> current = ownership.ownerOf(changed.customerId());
        boolean stillTheOwner = current.filter(owner -> owner.state() == SalesOwnerState.ASSIGNED)
                .map(CustomerSalesOwner::salesOwnerId).filter(changed.toUserId()::equals).isPresent();
        if (!stillTheOwner) {
            log.info("Owner change of customer {} at {} is stale: nothing moved", changed.customerId(), changed.at());
            return 0;
        }
        Instant now = writer.now();
        if (!conversations.recordOwnerChangeApplied(changed.customerId(), changed.at(), changed.toUserId(), now)) {
            return 0;
        }
        Map<UUID, String> names = agents.names(people(changed));
        String toName = names.getOrDefault(changed.toUserId(), "?");
        int moved = 0;
        if (agents.answersCustomers(changed.toUserId())) {
            String line = staffLine(changed, names, toName);
            String detail = "{\"salesOwnerFrom\":" + quoted(changed.fromUserId()) + ",\"salesOwnerTo\":"
                    + quoted(changed.toUserId()) + ",\"reason\":\"" + changed.reason().name() + "\"}";
            List<Conversation> locked = conversations.openOf(changed.customerId()).stream().map(writer::lock)
                    .toList();
            if (!stillOwnedBy(changed)) {
                log.info("Owner change of customer {} at {} went stale while its conversations were locked: nothing"
                        + " moved", changed.customerId(), changed.at());
                return 0;
            }
            for (Conversation c : locked) {
                UUID before = c.assignedAgentId();
                if (c.followOwner(changed.toUserId(), FOLLOWED_OWNER_NOTE, detail, now)) {
                    writer.save(c, before);
                    systemLine(c, line, Visibility.STAFF);
                    moved++;
                }
            }
        } else {
            log.warn("The new owner {} of customer {} does not answer customers: its conversations stay where they are",
                    changed.toUserId(), changed.customerId());
        }
        if (changed.notifyCustomer()) {
            String line = bundle().getString("chat.system.ownerChanged.customer").formatted(toName);
            Optional<Conversation> newest = conversations.newestOpenOf(changed.customerId());
            if (newest.isPresent()) {
                systemLine(writer.lock(newest.get().id()), line, Visibility.CUSTOMER);
            } else {
                notices.ownerChanged(changed.customerId(), toName, changed.at());
            }
        }
        return moved;
    }

    /**
     * BR-S08: the open conversations of {@code deactivated.userId()} go back to the queue, flagged, and the supervisors
     * are told how many.
     *
     * @return how many went back; 0 when none was left, or the account works again
     */
    @Transactional
    public int requeueAgent(StaffAccountDeactivated deactivated) {
        UUID agentId = deactivated.userId();
        if (agents.answersCustomers(agentId)) {
            log.info("Account {} answers customers again: its deactivation of {} moves nothing", agentId,
                    deactivated.at());
            return 0;
        }
        Instant now = writer.now();
        int requeued = 0;
        for (UUID id : conversations.openAssignedTo(agentId)) {
            Conversation c = writer.lock(id);
            UUID before = c.assignedAgentId();
            if (c.requeueFrom(agentId, now)) {
                writer.save(c, before);
                requeued++;
            }
        }
        notices.requeued(agentId, requeued, deactivated.at());
        return requeued;
    }

    /**
     * Whether {@code changed.toUserId()} is still the customer's owner, read once this transaction holds the
     * conversations' locks. The first check reads before any lock: a later change can commit, and the chat apply it,
     * between that read and the locks - and moving the conversations then would hand them back to the earlier owner
     * (A to B, then B to C: B's change, late, would leave them with B). A later change applied before our locks
     * committed is seen here; one committing after this read waits for our locks and moves them after us. Read through
     * {@link ResponsibleSales}, a query of its own, because {@link SalesOwnership#ownerOf} answers from the entity this
     * transaction already loaded.
     */
    private boolean stillOwnedBy(CustomerSalesOwnerChanged changed) {
        return responsible.responsibleFor(changed.customerId(), BusinessCalendar.date(writer.now()))
                .map(Responsible::ownerId).filter(changed.toUserId()::equals).isPresent();
    }

    /** "Chuyển từ A sang B: lý do" for the staff; "Giao cho B: lý do" when the customer had no owner before. */
    private static String staffLine(CustomerSalesOwnerChanged changed, Map<UUID, String> names, String toName) {
        ResourceBundle m = bundle();
        String reason = m.getString("chat.ownerReason." + changed.reason().name());
        if (changed.fromUserId() == null) {
            return m.getString("chat.system.ownerChanged.staffNoPrevious").formatted(toName, reason);
        }
        return m.getString("chat.system.ownerChanged.staff").formatted(
                names.getOrDefault(changed.fromUserId(), "?"), toName, reason);
    }

    private void systemLine(Conversation c, String text, Visibility visibility) {
        writer.post(c, Actor.system(), null, text, conversation -> conversation.acceptSystemMessage(writer.now()),
                seq -> ChatMessage.system(Identifiers.newId(), c.id(), seq, text, visibility, null, writer.now()));
    }

    private static List<UUID> people(CustomerSalesOwnerChanged changed) {
        List<UUID> people = new ArrayList<>();
        people.add(changed.toUserId());
        if (changed.fromUserId() != null) {
            people.add(changed.fromUserId());
        }
        return people;
    }

    private static String quoted(UUID id) {
        return id == null ? "null" : "\"" + id + "\"";
    }

    private static ResourceBundle bundle() {
        return ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
    }
}
