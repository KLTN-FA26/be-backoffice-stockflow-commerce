package com.stockflow.assistant.internal.entity;

import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * {@code assistant.knowledge_chunk}. The generated {@code search_document} column is not mapped: the database computes
 * it from the text, and the search reads it in SQL.
 *
 * <p>Neither are the embedding columns ({@code embedding}, {@code embedding_model}, {@code embedding_hash}): the
 * repository adapter writes and reads them in SQL. So {@link #rewrite} never touches a vector, and never has to: the
 * vector keeps the content hash of the text it was made from, and a vector whose hash is not the chunk's is ignored
 * until the indexer embeds the new text.</p>
 */
@Entity
@Table(name = "knowledge_chunk", schema = "assistant")
public class AssistantKnowledgeChunkJpaEntity extends BaseEntity {

    @Column(name = "source", nullable = false, length = 64, updatable = false)
    private String source;

    @Column(name = "anchor", nullable = false, length = 160, updatable = false)
    private String anchor;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private KnowledgeChunk.Kind kind;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    protected AssistantKnowledgeChunkJpaEntity() {
    }

    public AssistantKnowledgeChunkJpaEntity(UUID id, KnowledgeChunk chunk) {
        super(id);
        this.source = chunk.source();
        this.anchor = chunk.anchor();
        rewrite(chunk);
    }

    /** The document changed this chunk's text: take the new one. */
    public void rewrite(KnowledgeChunk chunk) {
        this.kind = chunk.kind();
        this.title = chunk.title();
        this.body = chunk.body();
        this.contentHash = chunk.contentHash();
    }

    public String getSource() { return source; }
    public String getAnchor() { return anchor; }
    public KnowledgeChunk.Kind getKind() { return kind; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getContentHash() { return contentHash; }
}
