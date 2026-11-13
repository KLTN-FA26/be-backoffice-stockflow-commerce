package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.contracts.CustomerChatReplied;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Who the chat tells, outside the conversation itself, through the bell ({@code notification :: api}):
 * <ul>
 *   <li><b>the customer</b>, when Sales answer while no chat of theirs is open (16 §4.1 step 3, edge case "khách rời
 *       trang giữa chừng") - at most once per conversation per {@link ChatProperties#customerNoticePeriod}, however
 *       many replies come in it: the inbox's dedupe key names the period. The first notice of a period also asks the
 *       notification module to e-mail them ({@link CustomerChatReplied}), sent only where customer mail is on;</li>
 *   <li><b>the chat supervisors</b>, when a first answer is late (BR-09), once per conversation.</li>
 * </ul>
 * Every notice is posted in the caller's transaction: a reply rolled back tells nobody. Titles and bodies are the
 * system's own words - never what anyone typed, which the bell would show to someone else.
 */
@Component
public class ChatNotifier {

    private static final String ANSWERS_ESCALATIONS = ChatCallers.SUPERVISION + ":UPDATE";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final Inbox inbox;
    private final IdentityService identity;
    private final ChatPresence presence;
    private final ApplicationEventPublisher events;
    private final ChatProperties properties;

    public ChatNotifier(Inbox inbox, IdentityService identity, ChatPresence presence,
                        ApplicationEventPublisher events, ChatProperties properties) {
        this.inbox = inbox;
        this.identity = identity;
        this.presence = presence;
        this.events = events;
        this.properties = properties;
    }

    /**
     * Sales said something to the customer: tell them, unless a chat of theirs is open now.
     *
     * @param customerAccounts the customer's portal account(s); none means nobody to tell
     */
    public void salesReplied(Conversation c, List<UUID> customerAccounts, Instant at) {
        List<UUID> away = customerAccounts.stream().filter(user -> !presence.isCustomerOnline(user)).toList();
        if (away.isEmpty()) {
            return;
        }
        Instant period = periodOf(at);
        ResourceBundle m = bundle();
        int told = inbox.post(away, new InboxMessage("CHAT", InboxSeverity.INFO, m.getString("inbox.chat.reply.title"),
                m.getString("inbox.chat.reply.body"), "/chat/conversations/" + c.id(), "CHAT_CONVERSATION",
                c.id().toString(), "chat-reply:" + c.id() + ":" + period.getEpochSecond()));
        if (told > 0) {
            events.publishEvent(new CustomerChatReplied(c.id(), c.customerId(), period, at));
        }
    }

    /** The first answer of {@code c} is late: the chat supervisors hear of it, once (the job marks it told). */
    public void firstResponseOverdue(Conversation c) {
        List<UUID> supervisors = identity.notificationRecipientsHolding(ANSWERS_ESCALATIONS, null).stream()
                .map(StaffContact::userId).toList();
        if (supervisors.isEmpty()) {
            return;
        }
        ResourceBundle m = bundle();
        String due = c.firstResponseDueAt() == null ? "" : TIME.withZone(ZoneId.of(properties.businessZone()))
                .format(c.firstResponseDueAt());
        inbox.post(supervisors, new InboxMessage("CHAT_SLA", InboxSeverity.WARN,
                m.getString("inbox.chat.overdue.title"), m.getString("inbox.chat.overdue.body").formatted(due),
                "/chat/conversations/" + c.id(), "CHAT_CONVERSATION", c.id().toString(),
                "chat-first-response-overdue:" + c.id()));
    }

    /** The start of the notice period {@code at} falls in: periods are counted from the epoch, so all agree on them. */
    Instant periodOf(Instant at) {
        Duration period = properties.customerNoticePeriod();
        long seconds = period.getSeconds();
        return Instant.ofEpochSecond(at.getEpochSecond() - Math.floorMod(at.getEpochSecond(), seconds));
    }

    private static ResourceBundle bundle() {
        return ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
    }
}
