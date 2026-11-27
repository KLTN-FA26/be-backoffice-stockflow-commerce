package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.payment.api.CustomerInstallments;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A payment reminder for the customer to edit and send (2026-11-27 work features 2.2): Sales or the Accountant chasing
 * an installment gets a polite Vietnamese message that names the contract, the installment, what it still owes, its
 * due day and what to write in the transfer - and nothing else. Sending stays theirs, through their own mail or chat
 * (ADR-0012).
 *
 * <p><b>What the model is given is what the customer may read.</b> The facts are the installment's own, read through
 * {@code payment :: api} ({@link CustomerInstallments#transfer}: what it still owes by the installment's own rule,
 * whether it is overdue): the contract and order numbers, the installment's name, the amount, the due day, how late,
 * the transfer reference. No hold note, no credit position, no other customer, no staff name. The customer is
 * {@code KH#1} to the model (ADR-0015, {@link DraftNames}) and its name is put back into the draft afterwards; the
 * installment's name, which Sales typed, goes through {@link TypedText} like any typed text, the customer's names in it
 * hidden.</p>
 *
 * <p><b>Only money the collections list chases.</b> An installment with no due day yet, or due beyond the list's week,
 * is refused (409 {@code INSTALLMENT_NOT_DUE_SOON}): a letter asking for money not owed yet is worse than no letter.</p>
 *
 * <p><b>Written as every draft is</b> ({@link DraftWriter}): every code and figure it states is checked against those
 * facts, what nothing holds listed in {@code unverifiedCodes} for the screen to flag. A model off or failing is
 * {@code 503 ASSISTANT_UNAVAILABLE}: the list beside it still works.</p>
 */
@Service
public class ReminderDrafts {

    /** How the reminder sounds; the facts are the same. */
    public enum Tone {
        /** A courteous reminder, for money due soon or just late. */
        GENTLE,
        /** Firmer, for money well overdue: asks for a payment date; still courteous, never a threat. */
        FIRM
    }

    static final String SYSTEM_PROMPT = """
            You write a payment reminder in Vietnamese that a member of a B2B printing company's staff (Sales or the
            Accountant) will edit and send to a business customer by e-mail or chat.

            Use ONLY the facts in the JSON the user message gives. Name the contract number, the installment, the amount
            still owed (in VND, written with dots as thousands separators, e.g. 125.000.000 đồng) and the due date exactly
            as given; if overdue is true, say how many days it is overdue. Ask the customer to write transferReference as
            the transfer's content so the payment is matched at once. Address the customer as "Quý khách" and refer to
            them by the customer field exactly as written (an alias such as KH#1): never invent a name, a person, a bank
            account, a phone number, an interest charge, a penalty, a legal step, a discount or any figure or date that
            is not in the facts. Do not mention other installments, orders, goods, credit limits or internal reasons.

            Tone GENTLE: warm and courteous, a friendly reminder. Tone FIRM: still courteous, clearly states the amount
            is overdue and asks the customer to confirm a payment date; no threats.

            Write everything in Vietnamese and never copy a JSON field name (contractNumber, installment,
            transferReference...) into the text: call the installment "đợt" followed by its name as given, e.g. "đợt
            Thanh toán sau giao hàng". Write only the message: a greeting, two to four short paragraphs, a thank-you, and
            no signature - the person who sends it signs it, Sales or the Accountant. Plain text, no Markdown, no subject
            line, no placeholders in brackets.""";

    private final CustomerInstallments installments;
    private final DraftWriter writer;
    private final ObjectMapper json;
    private final Clock clock;

    ReminderDrafts(CustomerInstallments installments, DraftWriter writer, ObjectMapper json, Clock clock) {
        this.installments = installments;
        this.writer = writer;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @throws BusinessException {@code INSTALLMENT_NOT_FOUND}; {@code INSTALLMENT_NOT_PAYABLE} for one paid or cancelled,
     *         which nobody is chased for; {@code INSTALLMENT_NOT_DUE_SOON} for one not due within
     *         {@value CollectionList#DUE_WITHIN_DAYS} days or with no due day yet; {@code ASSISTANT_UNAVAILABLE} when
     *         no model writes it
     */
    public Draft draft(UUID installmentId, Tone tone) {
        CustomerInstallments.Transfer installment = installments.transfer(installmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSTALLMENT_NOT_FOUND,
                        "No installment " + installmentId).localized("refusal.payment.installmentNotFound"));
        if (!Set.of("NOT_DUE", "DUE", "PARTIALLY_PAID").contains(installment.status())
                || installment.outstanding().signum() <= 0) {
            throw new BusinessException(ErrorCode.INSTALLMENT_NOT_PAYABLE, "Installment %d of contract %s is %s"
                    .formatted(installment.seq(), installment.contractNumber(), installment.status()))
                    .localized("refusal.payment.notPayable", installment.seq(), installment.contractNumber(),
                            installment.status());
        }
        LocalDate today = BusinessCalendar.date(clock.instant());
        requireDueSoon(installment, today);
        writer.requireModel();
        DraftNames names = writer.names(installment.customerId());
        String facts = facts(installment, names, tone == null ? Tone.GENTLE : tone, today);
        DraftWriter.Written written = writer.write("payment reminder", SYSTEM_PROMPT, facts);
        return writer.draft(written, List.of(facts), names, sources(installment));
    }

    /**
     * Only money the collections list offers to chase ({@link CollectionList}): an installment overdue or due within
     * {@value CollectionList#DUE_WITHIN_DAYS} days. One with no due day yet - paid before delivery, or after a delivery
     * that has not happened - owes nothing today, and a letter would ask the customer for money not owed; one due later
     * is not chased yet.
     */
    private static void requireDueSoon(CustomerInstallments.Transfer installment, LocalDate today) {
        LocalDate due = installment.dueDate();
        if (due != null && !due.isAfter(today.plusDays(CollectionList.DUE_WITHIN_DAYS))) {
            return;
        }
        String when = due == null ? "has no due date yet" : "is due on " + due;
        BusinessException refused = new BusinessException(ErrorCode.INSTALLMENT_NOT_DUE_SOON,
                "Installment %d of contract %s %s".formatted(installment.seq(), installment.contractNumber(), when));
        throw due == null
                ? refused.localized("refusal.assistant.reminderNoDueDate", installment.seq(),
                        installment.contractNumber())
                : refused.localized("refusal.assistant.reminderNotDueSoon", installment.seq(),
                        installment.contractNumber(), DocumentFormats.date(due),
                        String.valueOf(CollectionList.DUE_WITHIN_DAYS));
    }

    /**
     * The facts as the model reads them: JSON, so the grounding check reads its numbers as the values they are. The
     * installment's name is typed by Sales: masked, the customer's names in it hidden, as any typed text.
     */
    private String facts(CustomerInstallments.Transfer i, DraftNames names, Tone tone, LocalDate today) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("customer", names.customerAlias());
        facts.put("contractNumber", i.contractNumber());
        facts.put("orderNumber", i.orderNumber());
        facts.put("installment", names.hide(TypedText.masked(i.name())));
        facts.put("amountStillOwed", i.outstanding().setScale(0, RoundingMode.HALF_UP));
        facts.put("amountStillOwedText", DocumentFormats.money(i.outstanding()) + " đồng");
        facts.put("currency", i.currency());
        facts.put("dueDate", DocumentFormats.date(i.dueDate()));
        facts.put("overdue", i.overdue());
        facts.put("daysOverdue", i.overdue() ? ChronoUnit.DAYS.between(i.dueDate(), today) : 0);
        facts.put("transferReference", i.transferReference());
        facts.put("tone", tone.name());
        try {
            return json.writeValueAsString(facts);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<Draft.Source> sources(CustomerInstallments.Transfer i) {
        String order = "/admin/orders/" + i.orderId();
        // The back office has no page of its own for a contract: no link rather than a guessed one.
        return List.of(new Draft.Source(Draft.Kind.CONTRACT, i.contractNumber(), null),
                new Draft.Source(Draft.Kind.ORDER, i.orderNumber(), order),
                new Draft.Source(Draft.Kind.INSTALLMENT, i.contractNumber() + " - " + i.name(),
                        "/admin/payment/installments/" + i.installmentId()));
    }
}
