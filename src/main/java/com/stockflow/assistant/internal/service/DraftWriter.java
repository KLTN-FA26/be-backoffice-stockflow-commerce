package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.identity.api.IdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * How every model-backed draft is written (contract §0, "Draft"): the reply and the summary in the staff chat
 * ({@link ChatDrafts}), the payment reminder ({@link ReminderDrafts}) and the message to a producer
 * ({@link ProductionFollowUps}). Each of them gathers its own facts; this is the part that must not differ between
 * them, because a user cannot tell which draft behaves how.
 *
 * <ul>
 *   <li><b>One model turn on the questions' route</b>, told the business day ({@code dd/MM/yyyy}, the form the drafts
 *       write dates in), with no tool offered. A model failing before it replies hands the draft to the next, as a
 *       question does ({@link ModelRouter.Route#fallBack}); one that replies with nothing usable (no text, or stopped
 *       for any reason but the end of its turn) is asked once more on the next model if the route allows it
 *       ({@link ModelRouter.Route#escalate}). Nothing left is {@code 503 ASSISTANT_UNAVAILABLE}: the feature is fine,
 *       no model is answering. Nothing is stored: a draft is not a conversation.</li>
 *   <li><b>Checked against what the model was given</b> ({@link #unverified}): every code and figure of the text as the
 *       model wrote it - aliases in place - is looked for in the facts and the typed text it was sent, also as they were
 *       sent; what none holds is listed in {@code unverifiedCodes}, never removed. Checking before the names go back
 *       means a customer whose name holds a number ("In Ấn 247") is not flagged for its own name.</li>
 *   <li><b>Names go back last</b> ({@link DraftNames#revealer}): the customer and its contacts the model saw as
 *       {@code KH#1} and {@code LH#n}.</li>
 * </ul>
 */
@Component
public class DraftWriter {

    private static final Logger log = LoggerFactory.getLogger(DraftWriter.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ModelRouter models;
    private final CustomerService customers;
    private final CustomerLegalProfiles legalProfiles;
    private final IdentityService identities;
    private final Clock clock;

    DraftWriter(ModelRouter models, CustomerService customers, CustomerLegalProfiles legalProfiles,
                IdentityService identities, Clock clock) {
        this.models = models;
        this.customers = customers;
        this.legalProfiles = legalProfiles;
        this.identities = identities;
        this.clock = clock;
    }

    /** What a model wrote, as it wrote it (aliases in place), and which model it was. */
    public record Written(String text, String model) {
    }

    /**
     * {@code 503 ASSISTANT_UNAVAILABLE} when no model is on. A draft calls it once the caller was found allowed and the
     * record found, so a refused caller is told 403 or 404 first, as on the screen.
     */
    public void requireModel() {
        if (!models.available()) {
            throw unavailable("no model is available");
        }
    }

    /** The business day, as the drafts write a date and as the model is told it. */
    public String today() {
        return DAY.format(BusinessDate.of(clock.instant()));
    }

    /**
     * One model turn (see the class comment).
     *
     * @param what the draft's name, for the log
     * @throws BusinessException {@code ASSISTANT_UNAVAILABLE} when no model writes it
     */
    public Written write(String what, String systemPrompt, String question) {
        requireModel();
        LanguageModel.Request request = new LanguageModel.Request(systemPrompt, List.of(),
                List.of(new Turn.Question(question, "Business date: " + today())), false, null);
        ModelRouter.Route route = models.route();
        while (true) {
            LanguageModel.Reply reply;
            try {
                reply = route.model().next(request);
            } catch (BusinessException failed) {
                route.failedBeforeReplying(failed);
                if (route.fallBack(failed)) {
                    continue;
                }
                throw unavailable(what + ": no model answered (" + route.failure().errorCode() + ")");
            }
            route.replied();
            String text = reply.turn() == null ? null : reply.turn().text();
            if (reply.stopReason() == LanguageModel.StopReason.END_TURN && text != null && !text.isBlank()) {
                return new Written(text.strip(), reply.model());
            }
            if (!route.escalate()) {
                throw unavailable(what + ": the model gave no usable text (" + reply.stopReason() + ")");
            }
        }
    }

    /**
     * The draft of {@code written}: checked against {@code grounds}, then its names put back.
     *
     * @param grounds what the model was given, as it was sent: the facts, the conversation, the typed instruction
     */
    public Draft draft(Written written, List<String> grounds, DraftNames names, List<Draft.Source> sources) {
        return draft(written.text(), written.text(), written.model(), grounds, names, sources);
    }

    /**
     * A draft whose text is one part of what the model wrote (the summary of a reply that also lists items): every
     * part is checked, {@code text} is the one shown.
     *
     * @param checked all the model wrote, as it wrote it
     */
    public Draft draft(String text, String checked, String model, List<String> grounds, DraftNames names,
                       List<Draft.Source> sources) {
        return new Draft(names.revealer().apply(text), sources, unverified(checked, grounds), model, clock.instant());
    }

    /** The codes and figures of {@code text} that none of {@code grounds} holds, codes first, each once. */
    public static List<String> unverified(String text, List<String> grounds) {
        AnswerNumbers.Grounds known = AnswerNumbers.Grounds.of(grounds);
        Set<String> flagged = new LinkedHashSet<>(AnswerCodes.unverified(text, known));
        flagged.addAll(AnswerNumbers.unverified(text, known));
        return List.copyOf(flagged);
    }

    /**
     * The names of customer {@code customerId} a draft must hide ({@link DraftNames}): its registered name, the
     * company name of its legal profile, and its contact people - the legal representative, the holder of its bank
     * account, and the owner of its portal account, who is the person writing in the chat. A part that cannot be read
     * is left out, logged: the draft still hides what was read.
     */
    public DraftNames names(UUID customerId) {
        if (customerId == null) {
            return DraftNames.NONE;
        }
        String registered = null;
        List<String> others = new ArrayList<>();
        List<String> contacts = new ArrayList<>();
        try {
            CustomerSummary customer = customers.findById(customerId).orElse(null);
            if (customer != null) {
                registered = customer.fullName();
                if (customer.userId() != null) {
                    String owner = identities.displayNames(List.of(customer.userId())).get(customer.userId());
                    if (owner != null) {
                        contacts.add(owner);
                    }
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Draft: the customer {} could not be read to hide its names", customerId, failure);
        }
        try {
            CustomerLegalProfile profile = legalProfiles.legalProfile(customerId).orElse(null);
            if (profile != null) {
                others.add(profile.companyName());
                contacts.add(0, profile.representativeName());
                if (profile.bankAccount() != null) {
                    // A person or the company itself: a contact unless it is one of the customer's own names.
                    contacts.add(profile.bankAccount().accountHolder());
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Draft: the legal profile of customer {} could not be read to hide its names", customerId,
                    failure);
        }
        return DraftNames.of(registered, others, contacts);
    }

    private static BusinessException unavailable(String why) {
        log.warn("Assistant draft not written: {}", why);
        return new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "No draft: " + why);
    }
}
