package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A webhook body as it is kept in the event log (kltn-docs 09 §4.5 step 2: "nội dung"): as received, except that a
 * credential it echoes is masked. Ahamove's callback carries the partner's own {@code api_key} among the order's fields,
 * so keeping the body verbatim would keep the key in clear in a table every warehouse manager reads.
 *
 * <p>A JSON body has every field named like a credential replaced by {@value #MASK}, at any depth; a body that is not
 * JSON is masked by pattern. A NUL - which PostgreSQL text cannot hold - becomes U+FFFD, and the result is cut to what
 * the column takes.</p>
 */
final class CarrierWebhookPayload {

    static final String MASK = "***";
    /** The column's limit (ck_carrier_event_payload), and the body limit the controller reads to. */
    static final int MAX_BYTES = 64 * 1024;

    private static final Set<String> SECRET_FIELDS = Set.of("api_key", "apikey", "token", "access_token",
            "refresh_token", "password", "secret", "authorization");
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(\"(?i:api_?key|token|access_token|refresh_token|password|secret|authorization)\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"");

    private CarrierWebhookPayload() {
    }

    /** The text to keep for {@code body}. */
    static String stored(byte[] body, ObjectMapper json) {
        String text = new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8).replace('\0', '�');
        if (mayHoldSecret(text)) {
            text = masked(text, json);
        }
        return cut(text);
    }

    private static boolean mayHoldSecret(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return SECRET_FIELDS.stream().anyMatch(lower::contains);
    }

    private static String masked(String text, ObjectMapper json) {
        try {
            JsonNode tree = json.readTree(text);
            if (tree != null && tree.isContainerNode()) {
                mask(tree);
                return json.writeValueAsString(tree);
            }
        } catch (IOException notJson) {
            // masked by pattern below
        }
        return SECRET_PAIR.matcher(text).replaceAll("$1\"" + MASK + "\"");
    }

    private static void mask(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> secret = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (SECRET_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
                    secret.add(field.getKey());
                } else {
                    mask(field.getValue());
                }
            }
            secret.forEach(name -> object.set(name, TextNode.valueOf(MASK)));
        } else if (node.isArray()) {
            node.forEach(CarrierWebhookPayload::mask);
        }
    }

    private static String cut(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return text;
        }
        int end = text.length();
        while (end > 0 && text.substring(0, end).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            end -= Math.max(1, (text.substring(0, end).getBytes(StandardCharsets.UTF_8).length - MAX_BYTES) / 4);
        }
        // Never split a surrogate pair.
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
