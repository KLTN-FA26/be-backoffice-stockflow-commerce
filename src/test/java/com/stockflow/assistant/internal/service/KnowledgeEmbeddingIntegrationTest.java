package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.EmbeddingModel;
import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.assistant.internal.domain.KnowledgeRepository;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.support.SecuredIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The vectors in PostgreSQL (design §6.4, {@code V20261105000600}): stored as {@code real[]} and read back value for
 * value, embedded again only when a passage's text or the model changes, never stored on text that changed meanwhile,
 * refused by the database when malformed, and searched by meaning over the real corpus. The embedding model is a fake:
 * no provider, no key.
 *
 * <p>The application's own {@link KnowledgeService} stays keyword-only (the assistant is off in tests). This test
 * builds a second one over the same repository with {@link FakeEmbeddingModel}, so it shares the assistant tests'
 * context instead of starting another. Should the environment switch the application's embeddings on (an exported
 * {@code ASSISTANT_ENABLED} with a Gemini key), the test is skipped: the application's own pass would be writing the
 * same columns at the same time.</p>
 */
@SecuredIntegrationTest
class KnowledgeEmbeddingIntegrationTest {

    private static final String SOURCE = "embedding-test";
    /** U6-03, untranslated: no word of it is in BR-RMA-002. */
    private static final String QUESTION = "hang in theo yeu cau co duoc tra lai khong";

    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired KnowledgeService application;
    @Autowired EmbeddingModel applicationEmbeddings;
    @Autowired KnowledgeRepository repository;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectProvider<BuildProperties> build;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;

    private final FakeEmbeddingModel embeddings = new FakeEmbeddingModel();
    private KnowledgeService knowledge;

    @BeforeEach
    void setUp() {
        assumeFalse(applicationEmbeddings.available(), "the application embeds the corpus itself in this environment");
        application.index();
        knowledge = new KnowledgeService(repository, embeddings, tx, build, clock);
    }

    @AfterEach
    void cleanUp() {
        if (knowledge == null) {
            return;
        }
        knowledge.stopEmbedding();
        tx.executeWithoutResult(status -> {
            repository.sync(SOURCE, List.of(), null);
            jdbc.update("DELETE FROM assistant.knowledge_source WHERE source = ?", SOURCE);
            jdbc.update("UPDATE assistant.knowledge_chunk SET embedding = NULL, embedding_model = NULL, "
                    + "embedding_hash = NULL WHERE embedding IS NOT NULL");
        });
    }

    private static KnowledgeChunk passage(String anchor, String body) {
        return new KnowledgeChunk(SOURCE, anchor, KnowledgeChunk.Kind.TERM, anchor, body);
    }

    private void sync(KnowledgeChunk... chunks) {
        tx.executeWithoutResult(status -> repository.sync(SOURCE, List.of(chunks), null));
    }

    private boolean save(KnowledgeChunk chunk, String model, float[] vector) {
        Boolean saved = tx.execute(status -> repository.saveEmbedding(chunk, model, vector));
        return Boolean.TRUE.equals(saved);
    }

    /** This source's passages that hold a current vector of {@code model}. */
    private List<KnowledgeRepository.EmbeddedChunk> embedded(String model, int dimensions) {
        List<KnowledgeRepository.EmbeddedChunk> all = tx.execute(status -> repository.embedded(model, dimensions));
        return all.stream().filter(chunk -> chunk.chunk().source().equals(SOURCE)).toList();
    }

    /** This source's passages waiting for a vector of {@code model}. */
    private List<KnowledgeChunk> withoutEmbedding(String model, int dimensions) {
        List<KnowledgeChunk> all = tx.execute(status -> repository.withoutEmbedding(model, dimensions));
        return all.stream().filter(chunk -> chunk.source().equals(SOURCE)).toList();
    }

    @Test
    @DisplayName("a vector is stored as real[] and read back value for value, for its own model and length only")
    void roundTrip() {
        KnowledgeChunk passage = passage("T-1", "A passage to embed.");
        sync(passage);
        float[] vector = {0.25f, -1.5f, 6.103515625E-5f, 0.1f, 1.17549435E-38f};

        assertThat(save(passage, "fake-embedding", vector)).isTrue();

        assertThat(embedded("fake-embedding", vector.length)).singleElement().satisfies(stored -> {
            assertThat(stored.chunk()).isEqualTo(passage);
            assertThat(stored.embedding()).containsExactly(vector);
        });
        assertThat(embedded("fake-embedding", 3)).as("another length").isEmpty();
        assertThat(embedded("another-model", vector.length)).as("another model").isEmpty();
    }

    @Test
    @DisplayName("every passage is embedded once; then only a reworded one; then, for a new model, all again")
    void embedsOnlyWhatChanged() {
        sync(passage("T-1", "First."), passage("T-2", "Second."));
        int corpus = jdbc.queryForObject("SELECT count(*) FROM assistant.knowledge_chunk", Integer.class);

        assertThat(knowledge.embed()).as("the documents' passages and this source's two").isEqualTo(corpus);
        assertThat(knowledge.embed()).as("nothing changed").isZero();

        sync(passage("T-1", "First, reworded."), passage("T-2", "Second."));
        assertThat(knowledge.embed()).isEqualTo(1);
        assertThat(embeddings.calls.getLast()).containsExactly("T-1\nFirst, reworded.");

        embeddings.name = "fake-embedding-2";
        assertThat(knowledge.embed()).isEqualTo(corpus);
    }

    @Test
    @DisplayName("a vector of text another sync has rewritten meanwhile is not stored, and an old one is not used")
    void oldTextNeverMeetsANewVector() {
        KnowledgeChunk before = passage("T-1", "Before.");
        sync(before);
        assertThat(save(before, "fake-embedding", FakeEmbeddingModel.UNRELATED)).isTrue();

        sync(passage("T-1", "After."));

        assertThat(save(before, "fake-embedding", FakeEmbeddingModel.UNRELATED)).as("made from the old text")
                .isFalse();
        assertThat(embedded("fake-embedding", 3)).as("the vector the rewrite left behind").isEmpty();
        assertThat(withoutEmbedding("fake-embedding", 3)).extracting(KnowledgeChunk::body).containsExactly("After.");
    }

    @Test
    @DisplayName("the database refuses a vector without its model or hash, an empty one, and one with a NaN or a NULL")
    void theDatabaseRefusesAMalformedVector() {
        sync(passage("T-1", "A passage."));

        for (String set : List.of(
                "embedding = '{0.5,0.5}', embedding_model = NULL, embedding_hash = content_hash",
                "embedding = '{0.5,0.5}', embedding_model = 'm', embedding_hash = NULL",
                "embedding = '{}', embedding_model = 'm', embedding_hash = content_hash",
                "embedding = '{0.5,NaN}', embedding_model = 'm', embedding_hash = content_hash",
                "embedding = '{0.5,NULL}', embedding_model = 'm', embedding_hash = content_hash")) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update(
                    "UPDATE assistant.knowledge_chunk SET " + set + " WHERE source = ?", SOURCE)))
                    .as(set).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    @DisplayName("over the real corpus, a Vietnamese question finds by meaning the rule its words never reach; a rule "
            + "it names stays first")
    void searchesByMeaning() {
        embeddings.vector("BR-RMA-002 ", 1, 0, 0).vector(QUESTION, 1, 0, 0);
        knowledge.embed();

        assertThat(knowledge.search(QUESTION, 5)).extracting(KnowledgeChunk::anchor).contains("BR-RMA-002");
        // This question's vector is the one nearly every passage has, so nothing stands out by meaning, and the
        // keyword ranking, the named rule first, decides.
        assertThat(knowledge.search("what does br-rma-001 say", 5).getFirst().anchor()).isEqualTo("BR-RMA-001");
    }
}
