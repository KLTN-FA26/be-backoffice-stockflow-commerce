package com.stockflow.contracts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by the <b>payment</b> module when an installment of a signed contract falls due - its milestone came
 * (kltn-docs 15 §4.1 step 2) - or a fee item is opened, due at once. The chat sends the customer the transfer details of
 * that installment as a card the system draws (16 §4.4 step 9, BR-06: the account is never typed by hand).
 *
 * <p>Facts only; the amount still owed is read when the card is drawn, so a payment made meanwhile shows. Consumers
 * receive it in-process; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param dueDate the day it is to be paid by; null for one paid before delivery, which has none (it blocks the handover)
 */
public record InstallmentDue(UUID installmentId, UUID contractId, String contractNumber, UUID orderId,
                             UUID customerId, int seq, LocalDate dueDate, Instant dueSince) {
}
