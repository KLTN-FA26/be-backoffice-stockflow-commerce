package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.CustomerAliasRepository;
import com.stockflow.customer.api.CustomerService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Customer aliases (ADR-0014, design §5.5): a tool result goes to the model provider with {@code KH-1} where it names
 * a customer, and what the panel shows has the name back.
 *
 * <p><b>Why.</b> A list of the customers who owe past due, with how much, is the business's most sensitive answer, and
 * the provider needs none of the names to write "KH-1 nợ quá hạn 120 triệu, nên gọi nhắc trước". The mapping stays in
 * the company's database ({@code assistant.customer_alias}); the provider only ever sees the aliases.</p>
 *
 * <p><b>Names in free text.</b> Once the conversation has given a customer an alias, its registered name is replaced by
 * the alias wherever it appears whole in what goes to the provider: the user asking about the customer the panel showed
 * by name, a note that names it ({@link Pseudonyms#hideNames}). A customer the conversation has not met yet is sent as
 * the user typed it: telling a name from other words in free text is a guess this class does not make. Suppliers keep
 * their names: they are the business's partners, not the people it keeps data about. An MCP client receives names,
 * being the user's own model (AQ-17).</p>
 *
 * <p>{@code stockflow.assistant.pseudonymise-customers=false} sends names as before; answers stored with aliases still
 * read back with names, live and reopened.</p>
 */
@Component
public class CustomerPseudonyms {

    /** Off, and with nothing to reveal: the services' tests that do not exercise aliases. */
    static final CustomerPseudonyms OFF = new CustomerPseudonyms(false, null, null);

    private final boolean enabled;
    private final CustomerAliasRepository aliases;
    private final ObjectProvider<CustomerService> customers;

    /**
     * @param customers lazy: the assistant's module tests start without the customer module's beans
     */
    @Autowired
    CustomerPseudonyms(@Value("${stockflow.assistant.pseudonymise-customers:true}") boolean enabled,
                       CustomerAliasRepository aliases, ObjectProvider<CustomerService> customers) {
        this.enabled = enabled;
        this.aliases = aliases;
        this.customers = customers;
    }

    /**
     * The conversation's aliases for one question, with their customers' names as registered now, so a name the user
     * types is sent as its alias ({@link Pseudonyms#hideNames}); null when customers are sent by name.
     */
    @Transactional(readOnly = true)
    public Pseudonyms forQuestion(UUID conversationId) {
        if (!enabled || aliases == null) {
            return null;
        }
        Map<Integer, UUID> stored = aliases.aliasesOf(conversationId);
        return new Pseudonyms(stored, names(stored.values()));
    }

    /**
     * Stores the aliases the question gave out, in the transaction that stores its answer: an answer stored with
     * "KH#3" always has the customer it stands for. Outside a transaction this would roll back unseen (Hikari runs
     * with auto-commit off), so one is required.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(UUID conversationId, Pseudonyms used) {
        if (used != null && aliases != null) {
            aliases.save(conversationId, used.added());
        }
    }

    /** Puts the names back in the conversation's stored text, as its customers are named now. */
    @Transactional(readOnly = true)
    public UnaryOperator<String> revealer(UUID conversationId) {
        if (aliases == null) {
            return UnaryOperator.identity();
        }
        return Pseudonyms.revealer(aliases.aliasesOf(conversationId), this::names);
    }

    /** Puts the names back in a question's answer: those its tools read, then the earlier ones as registered now. */
    @Transactional(readOnly = true)
    public UnaryOperator<String> revealer(Pseudonyms used) {
        if (used == null) {
            return UnaryOperator.identity();
        }
        Map<UUID, String> known = used.knownNames();
        return Pseudonyms.revealer(used.all(), ids -> {
            Map<UUID, String> names = new HashMap<>(names(ids.stream().filter(id -> !known.containsKey(id))
                    .toList()));
            names.putAll(known);
            return names;
        });
    }

    private Map<UUID, String> names(Collection<UUID> ids) {
        CustomerService service = customers == null ? null : customers.getIfAvailable();
        return service == null || ids.isEmpty() ? Map.of() : service.fullNames(List.copyOf(ids));
    }
}
