package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.List;

/**
 * A tool call's arguments, read field by field and checked on the way.
 *
 * <p>The model's arguments are untrusted input exactly like a request body: strict tool use guarantees their shape,
 * not their values. Every accessor validates, and a bad value is a {@code VALIDATION_FAILED} that the model receives
 * as an {@code INVALID} result it can correct, never an exception that ends the answer.</p>
 */
public final class ToolArguments {

    private final JsonNode node;
    private final Pseudonyms pseudonyms;

    private ToolArguments(JsonNode node, Pseudonyms pseudonyms) {
        this.node = node;
        this.pseudonyms = pseudonyms;
    }

    private ToolArguments(JsonNode node) {
        this(node, null);
    }

    /** The same arguments, read in a conversation whose customers have aliases ({@link CustomerName}). */
    ToolArguments withPseudonyms(Pseudonyms aliases) {
        return aliases == null ? this : new ToolArguments(node, aliases);
    }

    /** The question's customer aliases, so a tool taking a customer accepts "KH#3"; null where there are none. */
    Pseudonyms pseudonyms() {
        return pseudonyms;
    }

    static ToolArguments parse(ObjectMapper json, String arguments) {
        JsonNode node;
        try {
            node = json.readTree(arguments);
        } catch (JsonProcessingException malformed) {
            throw invalid("the arguments are not JSON");
        }
        if (node == null || !node.isObject()) {
            throw invalid("the arguments must be a JSON object");
        }
        return new ToolArguments(node);
    }

    /** For tests and for tools that take no input. */
    static ToolArguments of(JsonNode node) {
        return new ToolArguments(node);
    }

    /** A non-blank string, trimmed, at most {@code maxLength} characters. */
    public String text(String name, int maxLength) {
        String value = optionalText(name, maxLength);
        if (value == null) {
            throw invalid(name + " is required");
        }
        return value;
    }

    /** A string, trimmed; null when absent or blank. */
    public String optionalText(String name, int maxLength) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(name + " must be a string");
        }
        String text = value.asText().strip();
        if (text.length() > maxLength) {
            throw invalid(name + " must be at most " + maxLength + " characters");
        }
        return text.isEmpty() ? null : text;
    }

    /** An integer in {@code min..max}. */
    public int integer(String name, int min, int max) {
        JsonNode value = node.get(name);
        if (value == null || !value.canConvertToInt() || !value.isIntegralNumber()) {
            throw invalid(name + " must be an integer");
        }
        int number = value.asInt();
        if (number < min || number > max) {
            throw invalid(name + " must be between " + min + " and " + max);
        }
        return number;
    }

    /** 1..{@code maxItems} non-blank strings, each trimmed and at most {@code maxLength} characters. */
    public List<String> texts(String name, int maxItems, int maxLength) {
        JsonNode value = node.get(name);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw invalid(name + " must be a non-empty list");
        }
        if (value.size() > maxItems) {
            throw invalid(name + " must hold at most " + maxItems + " items");
        }
        List<String> texts = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            String text = item.isTextual() ? item.asText().strip() : "";
            if (text.isEmpty() || text.length() > maxLength) {
                throw invalid(name + " items must be 1 to " + maxLength + " characters");
            }
            texts.add(text);
        }
        return List.copyOf(texts);
    }

    /** One of {@code values}, exactly. */
    public String choice(String name, List<String> values) {
        String value = text(name, 64);
        if (!values.contains(value)) {
            throw invalid(name + " must be one of " + values);
        }
        return value;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
