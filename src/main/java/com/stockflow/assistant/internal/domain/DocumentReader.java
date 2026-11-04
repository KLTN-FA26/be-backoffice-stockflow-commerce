package com.stockflow.assistant.internal.domain;

import java.util.Map;
import java.util.Objects;

/**
 * The port to a model that reads a photographed document into JSON of a given shape (ADR-0010, design §8.1): the
 * assistant's only image input, kept apart from {@link LanguageModel}.
 *
 * <p><b>Why a port of its own.</b> A conversation sends every earlier question again with each new one
 * ({@link History}), so an image inside a chat turn would leave the system once per later question, and the chat port
 * carries text only. Reading a document is one call with no history and no tools, whose answer is data for Java to
 * check rather than text for a person: another contract, behind the same provider, key and switch.</p>
 *
 * <p><b>Nothing it returns is trusted.</b> {@link Reading#json()} is the model's answer exactly as written. The caller
 * parses it and checks every value against the system's own records before showing any of it, and shows the result as
 * a proposal a person checks against the paper. A reading writes nothing.</p>
 */
public interface DocumentReader {

    /**
     * False when the assistant is switched off or has no key of its provider's own, as for
     * {@link LanguageModel#available()}: the caller then refuses before the image is prepared or sent.
     */
    boolean available();

    /**
     * One reading: one model call, no retry of a refusal.
     *
     * @throws com.stockflow.common.error.BusinessException the codes of {@link LanguageModel#next}:
     *         {@code ASSISTANT_UNAVAILABLE} (worth trying again), {@code ASSISTANT_QUOTA_EXHAUSTED} and
     *         {@code ASSISTANT_MISCONFIGURED} (not), {@code ASSISTANT_DISABLED} when {@link #available()} is false
     */
    Reading read(Request request);

    /**
     * @param instruction what to read and how; sent as the system prompt, the same bytes on every call
     * @param context     the system's records the document is matched against, sent as text after the image
     * @param image       the document
     * @param schema      the JSON Schema of the answer, in the subset both providers enforce: objects, arrays, strings
     *                    and integers, every property required, no other property allowed, no numeric or length
     *                    constraints. Insertion-ordered maps: the order of the properties is the order the model
     *                    writes them in
     */
    record Request(String instruction, String context, Image image, Map<String, Object> schema) {

        public Request {
            Objects.requireNonNull(instruction, "instruction");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(schema, "schema");
        }
    }

    /**
     * An encoded image as it is sent.
     *
     * @param mediaType {@code image/jpeg}, {@code image/png} or {@code image/webp}
     */
    record Image(byte[] bytes, String mediaType) {

        public Image {
            Objects.requireNonNull(mediaType, "mediaType");
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("an image has bytes");
            }
        }

        /** The size and type only: the bytes are a document's contents and have no place in a log line. */
        @Override
        public String toString() {
            return "Image[" + mediaType + ", " + bytes.length + " bytes]";
        }
    }

    /**
     * @param outcome how the call ended
     * @param json    the answer exactly as the model wrote it; null unless {@link Outcome#READ}
     * @param usage   the call's tokens, as the provider bills them
     * @param model   the model that answered, as the provider names it
     */
    record Reading(Outcome outcome, String json, LanguageModel.Usage usage, String model) {

        public Reading {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(model, "model");
            if ((outcome == Outcome.READ) != (json != null)) {
                throw new IllegalArgumentException("a reading has an answer exactly when it is READ");
            }
        }
    }

    /** How a reading ended. Only {@link #READ} has an answer to check; the others read nothing. */
    enum Outcome {
        /** The model wrote an answer. Whether it is JSON of the schema is still the caller's to check. */
        READ,
        /** The provider's safeguards declined the image or the request. */
        REFUSED,
        /** The output limit cut the answer off, so it is not whole JSON. */
        CUT_SHORT,
        /** Anything else: an empty answer, a finish the adapter does not know. */
        NOTHING
    }
}
