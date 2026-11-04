package com.stockflow.assistant.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Which models answer the assistant's questions ({@code stockflow.assistant.routing}, design §11): a cheap one first,
 * a stronger one when it is needed, and no dead day when one model's quota is spent. {@link ModelRouter} does the
 * routing; {@link AssistantService} decides when to move on.
 *
 * <p><b>A record of its own.</b> Routing is optional, and {@link AssistantProperties} is built by hand in many tests:
 * unset, these settings change nothing, and nothing that builds the assistant's other settings has to know them.</p>
 *
 * <p><b>One provider, one key.</b> Every model of the list is called on {@code stockflow.assistant.provider} with its
 * key and its other settings (effort, timeouts, output tokens). A list that mixed vendors would need a key per vendor,
 * and choosing that key per model is exactly where one vendor's credential ends up sent to the other.</p>
 *
 * @param models            the provider's model ids, cheapest first ({@code ASSISTANT_MODELS}, a comma list). Empty:
 *                          {@code stockflow.assistant.model} alone, as before
 * @param escalateOnFailure a question the model could not answer — an empty or malformed reply twice, or still asking
 *                          for lookups when they ran out — is answered once more, from the start, on the next model
 * @param quotaCooldown     how long a model whose quota is spent is skipped, so the next questions start on the next
 *                          model; null (blank) for until the provider's quota day ends ({@link ModelRouter})
 */
@ConfigurationProperties(prefix = "stockflow.assistant.routing")
public record ModelRoutingProperties(
        List<String> models,
        @DefaultValue("true") boolean escalateOnFailure,
        Duration quotaCooldown) {

    public ModelRoutingProperties {
        // A comma list from the environment may carry spaces, an empty entry or a repeat; none of them is a model.
        models = models == null ? List.of() : models.stream().filter(Objects::nonNull).map(String::strip)
                .filter(model -> !model.isEmpty()).distinct().toList();
        if (quotaCooldown != null && (quotaCooldown.isNegative() || quotaCooldown.isZero())) {
            throw new IllegalArgumentException("stockflow.assistant.routing.quota-cooldown must be positive, or blank "
                    + "for until the provider's quota day ends");
        }
    }

    /** The models a question may go to, cheapest first: {@link #models}, or {@code model} alone when none is set. */
    public List<String> route(String model) {
        return models.isEmpty() ? List.of(model) : models;
    }
}
