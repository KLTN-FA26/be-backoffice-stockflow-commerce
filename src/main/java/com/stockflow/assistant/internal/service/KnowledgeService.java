package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.assistant.internal.domain.KnowledgeParser;
import com.stockflow.assistant.internal.domain.KnowledgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The retrieval corpus: indexed from the business-design documents on every start, searched by
 * {@code SearchKnowledgeTool} (design §6.1–6.3).
 *
 * <p><b>Indexed on start, not by a job.</b> The documents are copied onto the classpath by the build
 * ({@code classpath:knowledge/}), so the index follows them on every deploy and nobody has to remember to re-index.
 * Unchanged chunks are not rewritten (content hash), so a normal start writes nothing.</p>
 *
 * <p><b>Never a reason not to start, never a stale rule.</b> A failed sync is logged per document, and the others
 * are still synced. A document missing from this build (renamed, or the {@code pom.xml} include no longer matching)
 * has its chunks deleted rather than left to be cited forever. A document a newer build indexed last is left alone:
 * an older instance starting after it must not put the older rules back ({@code assistant.knowledge_source}).</p>
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /** The documents indexed, with the parser that cuts each one (its natural unit differs). */
    static final List<Source> SOURCES = List.of(
            new Source("05-business-rules", KnowledgeParser::rules),
            new Source("01-ubiquitous-language", KnowledgeParser::terms),
            new Source("03-state-machines", KnowledgeParser::sections));

    record Source(String name, BiFunction<String, String, List<KnowledgeChunk>> parser) {

        String resource() {
            return "knowledge/" + name + ".md";
        }
    }

    private final KnowledgeRepository knowledge;
    private final TransactionTemplate tx;
    private final ObjectProvider<BuildProperties> build;

    KnowledgeService(KnowledgeRepository knowledge, TransactionTemplate tx, ObjectProvider<BuildProperties> build) {
        this.knowledge = knowledge;
        this.tx = tx;
        this.build = build;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void indexOnStartup() {
        try {
            int changes = index();
            log.info("Assistant knowledge index: {} chunk change(s)", changes);
        } catch (RuntimeException failed) {
            log.error("The assistant's knowledge index could not be refreshed; it answers from the previous one",
                    failed);
        }
    }

    /** Syncs every document, each in its own transaction. @return rows written or deleted */
    public int index() {
        BuildProperties info = build.getIfAvailable();
        Instant builtAt = info == null ? null : info.getTime();
        int changes = 0;
        for (Source source : SOURCES) {
            try {
                changes += index(source, builtAt);
            } catch (RuntimeException failed) {
                log.error("The assistant could not index {}; it answers from that document's previous index",
                        source.name(), failed);
            }
        }
        return changes;
    }

    private int index(Source source, Instant builtAt) {
        String markdown = read(source.resource());
        List<KnowledgeChunk> chunks;
        if (markdown == null) {
            log.error("classpath:{} is missing from this build: the assistant stops citing {}", source.resource(),
                    source.name());
            chunks = List.of();
        } else {
            chunks = source.parser().apply(source.name(), markdown);
        }
        KnowledgeRepository.Sync sync = tx.execute(status -> knowledge.sync(source.name(), chunks, builtAt));
        if (sync == null) {
            return 0;
        }
        if (sync.newerIndexed()) {
            log.warn("{} was indexed by a newer build than this one ({}); left as it is", source.name(), builtAt);
        }
        return sync.changes();
    }

    @Transactional(readOnly = true)
    public List<KnowledgeChunk> search(String query, int limit) {
        return knowledge.search(query, limit);
    }

    private static String read(String resource) {
        ClassPathResource file = new ClassPathResource(resource);
        if (!file.exists()) {
            return null;
        }
        try (InputStream in = file.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            log.warn("classpath:{} could not be read", resource, unreadable);
            return null;
        }
    }
}
