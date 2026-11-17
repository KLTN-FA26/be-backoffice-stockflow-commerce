package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The questions offered under an answer to ask next (design §7): at most {@value #MAX}, one click each, built here from
 * what the answer's lookups returned, with no model call.
 *
 * <p><b>Why not let the model suggest them.</b> A model-written suggestion offers whatever reads well, including a
 * question the user's own tools refuse: a sales clerk offered "where is this order short?" spends a question to be
 * told they cannot see picking. Here a question is offered only when {@link ToolAvailability} grants this user the
 * tool it leads to, the same rule the question context gives the model. It saves typing, not quota: a click is still
 * a question.</p>
 *
 * <p><b>Fixed texts, checked codes.</b> Each question is an i18n template ({@code assistant.followUp.*}) filled with a
 * code taken from a structured field of an OK result, and only a code that passes its own format (a SKU, an order
 * number): a product title or a hold note, text a customer may have typed, never becomes part of a question.</p>
 *
 * <p><b>Worked out once, offered per reader.</b> What an answer leads to ({@link #leads}) is read from its stored
 * results, like its tables, so the live answer and the reopened transcript lead to the same places. Which of those
 * are offered is decided each time the answer is shown, under the reader's permissions of that moment.</p>
 *
 * <p>The texts are the golden set's own questions where one exists (U2-01, U3-05, S-06, S-01), so a click asks a
 * question the assistant is measured on.</p>
 */
@Component
public class FollowUps {

    /** Questions offered under one answer. */
    static final int MAX = 3;

    /** {@code OrderNumber}'s format, copied by value: the order module keeps it internal. */
    private static final Pattern ORDER_NUMBER = Pattern.compile("SO-\\d{8}-\\d{6,}");

    private final ToolAvailability availability;
    private final Messages messages;

    FollowUps(ToolAvailability availability, Messages messages) {
        this.availability = availability;
        this.messages = messages;
    }

    /** What a follow-up asks for, the tool that answers it and its text. */
    public enum Kind {
        /** After an order held for stock: which lines are short, and where. */
        ORDER_SHORTAGE("getOrderPicking", "assistant.followUp.orderShortage",
                "Which lines of order {0} are short, by how much, and at which locations?"),
        /** After a SKU's stock: the SKU's lots, oldest received first, and their bins. */
        SKU_LOTS("getStockLots", "assistant.followUp.skuLots",
                "Which lots of {0} are there, which goes out first and where are they?"),
        /** After a short pick, a best seller or a catalog match: the SKU's stock per warehouse. */
        SKU_STOCK("getStockLevels", "assistant.followUp.skuStock", "How much {0} is in stock in each warehouse?");

        private final String tool;
        private final String key;
        private final String english;

        Kind(String tool, String key, String english) {
            this.tool = tool;
            this.key = key;
            this.english = english;
        }

        public String tool() {
            return tool;
        }

        public String key() {
            return key;
        }
    }

    /**
     * One question an answer leads to, before anyone's permissions are applied.
     *
     * @param subject the code it is about, as it goes into the text: a SKU, an order number, or several SKUs joined
     */
    public record FollowUp(Kind kind, String subject) {

        public FollowUp {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(subject, "subject");
        }
    }

    /** The tools {@code user} may use now: what {@link #suggestions} filters by, read once per response. */
    public Set<String> toolsFor(CurrentUser user) {
        return Set.copyOf(availability.grantedTo(user));
    }

    /** {@link #suggestions} for one answer shown to {@code user}. */
    public List<String> suggestionsFor(List<FollowUp> leads, CurrentUser user) {
        return leads.isEmpty() ? List.of() : suggestions(leads, toolsFor(user));
    }

    /**
     * The first {@value #MAX} of {@code leads} whose tool {@code granted} holds, in the reader's language, each once.
     *
     * @param granted the tools the reader may use ({@link #toolsFor})
     */
    public List<String> suggestions(List<FollowUp> leads, Set<String> granted) {
        Set<String> texts = new LinkedHashSet<>();
        for (FollowUp lead : leads) {
            if (texts.size() == MAX) {
                break;
            }
            if (granted.contains(lead.kind().tool())) {
                texts.add(messages.get(lead.kind().key(), MessageFormat.format(lead.kind().english, lead.subject()),
                        lead.subject()));
            }
        }
        return List.copyOf(texts);
    }

    /**
     * What the OK results among {@code executions} lead to, best first: per lookup in the order they ran, leaving out
     * a question this answer already looked up (the stock of a SKU it read, the picking of an order it read).
     */
    static List<FollowUp> leads(List<ToolExecution> executions, ObjectMapper json) {
        List<FollowUp> leads = new ArrayList<>();
        Set<FollowUp> answered = new HashSet<>();
        for (ToolExecution execution : executions) {
            if (execution.outcome() != ToolOutcome.OK) {
                continue;
            }
            JsonNode result = read(execution.result(), json);
            if (result == null) {
                continue;
            }
            switch (execution.call().name()) {
                case "findOrder" -> {
                    JsonNode picking = result.path("picking");
                    if (picking.isObject()) {
                        // Its picking block holds the short lines: where it is short came with the order.
                        orderNumber(result.path("orderNumber"))
                                .ifPresent(order -> answered.add(new FollowUp(Kind.ORDER_SHORTAGE, order)));
                        sku(picking.path("shortLines").path(0).path("sku"))
                                .ifPresent(sku -> leads.add(new FollowUp(Kind.SKU_STOCK, sku)));
                    } else {
                        heldForStock(result).ifPresent(leads::add);
                    }
                }
                case "findOrders" -> {
                    for (JsonNode order : result.path("orders")) {
                        Optional<FollowUp> held = heldForStock(order);
                        if (held.isPresent()) {
                            leads.add(held.get());
                            break;
                        }
                    }
                }
                case "getOrderPicking" -> {
                    orderNumber(result.path("orderNumber"))
                            .ifPresent(order -> answered.add(new FollowUp(Kind.ORDER_SHORTAGE, order)));
                    for (JsonNode line : result.path("lines")) {
                        if (line.path("shortQuantity").asLong(0) > 0) {
                            sku(line.path("sku")).ifPresent(sku -> leads.add(new FollowUp(Kind.SKU_STOCK, sku)));
                            break;
                        }
                    }
                }
                case "getStockLevels" -> sku(result.path("sku")).ifPresent(sku -> {
                    answered.add(new FollowUp(Kind.SKU_STOCK, sku));
                    leads.add(new FollowUp(Kind.SKU_LOTS, sku));
                });
                case "getStockLots" -> sku(result.path("sku"))
                        .ifPresent(sku -> answered.add(new FollowUp(Kind.SKU_LOTS, sku)));
                case "searchCatalog" -> onlySku(result.path("products"))
                        .ifPresent(sku -> leads.add(new FollowUp(Kind.SKU_STOCK, sku)));
                case "getSalesSummary" -> sku(result.path("topSkus").path(0).path("sku"))
                        .ifPresent(sku -> leads.add(new FollowUp(Kind.SKU_STOCK, sku)));
                default -> {
                    // No lead: the other lookups answer their own question, or lead nowhere a person asks next.
                }
            }
        }
        return leads.stream().distinct().filter(lead -> !answered.contains(lead)).toList();
    }

    /** An order on hold for stock: where it is short is what Sales ask next (FEAT-5). */
    private static Optional<FollowUp> heldForStock(JsonNode order) {
        boolean heldForStock = "ON_HOLD".equals(order.path("status").asText())
                && "INVENTORY".equals(order.path("hold").path("reason").asText());
        return heldForStock ? orderNumber(order.path("orderNumber"))
                .map(number -> new FollowUp(Kind.ORDER_SHORTAGE, number)) : Optional.empty();
    }

    /** The one SKU a catalog search found; none when it found several, which the answer asks the user to pick from. */
    private static Optional<String> onlySku(JsonNode products) {
        List<JsonNode> skus = new ArrayList<>();
        products.forEach(product -> product.path("skus").forEach(variant -> skus.add(variant.path("sku"))));
        return skus.size() == 1 ? sku(skus.getFirst()) : Optional.empty();
    }

    private static Optional<String> sku(JsonNode value) {
        if (!value.isTextual()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Sku(value.textValue()).code());
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private static Optional<String> orderNumber(JsonNode value) {
        return value.isTextual() && ORDER_NUMBER.matcher(value.textValue()).matches()
                ? Optional.of(value.textValue()) : Optional.empty();
    }

    private static JsonNode read(String result, ObjectMapper json) {
        try {
            JsonNode root = json.readTree(result);
            return root != null && root.isObject() ? root : null;
        } catch (JsonProcessingException notJson) {
            return null;
        }
    }
}
