package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.util.UUID;

/**
 * A customer's name in a tool's result (ADR-0014). Written as the customer's alias in this conversation
 * ({@code KH#3}) when the result goes to the assistant's model provider, and as the name itself everywhere else: an MCP
 * client, which is the user's own choice of model (AQ-17), and the "today" card, which no model reads.
 *
 * <p>The choice is the result writer's, not the tool's: a tool returns the name it read, and {@link AssistantTools}
 * writes the result with the question's {@link Pseudonyms} when there are some.</p>
 *
 * @param id   the customer
 * @param name its name as registered
 */
@JsonSerialize(using = CustomerName.Writer.class)
record CustomerName(UUID id, String name) {

    /** The name, or the alias where the writer carries the question's pseudonyms. */
    static final class Writer extends StdSerializer<CustomerName> {

        Writer() {
            super(CustomerName.class);
        }

        @Override
        public void serialize(CustomerName customer, JsonGenerator out, SerializerProvider provider)
                throws IOException {
            if (provider.getAttribute(Pseudonyms.class) instanceof Pseudonyms aliases && customer.id() != null) {
                out.writeString(aliases.aliasOf(customer.id(), customer.name()));
            } else if (customer.name() == null) {
                out.writeNull();
            } else {
                out.writeString(customer.name());
            }
        }
    }
}
