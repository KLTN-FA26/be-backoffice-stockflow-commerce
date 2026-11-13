package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>chat</b> module when Sales answer a customer who has no chat open at that moment (kltn-docs 16
 * §4.1 step 3, edge case "khách rời trang giữa chừng": the customer is told when Sales reply). At most once per
 * conversation per notice period: {@code periodStart} names the period, so a redelivered event and a second reply in
 * the same period arrive at the same message. The <b>notification</b> module e-mails the customer, when customer mail
 * is on.
 *
 * <p>Facts only: never what Sales wrote. Consumers receive it in-process; see {@code package-info.java} for the rules on
 * changing this contract.</p>
 */
public record CustomerChatReplied(UUID conversationId, UUID customerId, Instant periodStart, Instant repliedAt) {
}
