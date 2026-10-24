package com.stockflow.assistant.internal.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One passage the assistant can retrieve and cite: a business rule, a glossary term or a state machine.
 *
 * @param source the document, by file name without extension ({@code 05-business-rules})
 * @param anchor what an answer cites ({@code BR-RMA-002}); unique within its source
 */
public record KnowledgeChunk(String source, String anchor, Kind kind, String title, String body) {

    /** The longest body stored; also {@code ck_knowledge_chunk_text}. */
    public static final int MAX_BODY = 20_000;

    public enum Kind { RULE, TERM, STATE_MACHINE }

    public KnowledgeChunk {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        if (anchor.isBlank() || title.isBlank() || body.isBlank()) {
            throw new IllegalArgumentException("a chunk has an anchor, a title and a body");
        }
        body = body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body;
    }

    /** SHA-256 of title and body, hex: the indexer rewrites a chunk only when this changes. */
    public String contentHash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((title + "\n" + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JVM has SHA-256", impossible);
        }
    }
}
