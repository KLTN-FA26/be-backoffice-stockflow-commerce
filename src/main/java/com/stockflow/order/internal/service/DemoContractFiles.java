package com.stockflow.order.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.DataScopeContext;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Demo only ({@code local} profile, the one the WF-05 demo runs under): puts the files of the seeded contracts into
 * storage, so "Tải hợp đồng đã gửi" and "Tải bản đã ký" work on a database built from the demo seed (WF-05 demo-run
 * 24/11/2026, defect D-4).
 *
 * <p>The seed ({@code db/demo}, {@code db/demo-wf05}) is SQL and cannot write an object: its contracts name
 * {@code documents/demo/<contract number>.pdf} as the copy sent and {@code documents/demo/<number>-ky.pdf} as the signed
 * scan, and nothing was behind either - both downloads answered 503 "Attached object is missing". On every start, each
 * such key with no object behind it gets the contract as this application renders it ({@link SalesContracts#preview}:
 * the live contract, dated the day it was sent, no draft watermark once sent) - what sending would have stored. The
 * signed scan is the same rendering: the seed has no scan to give. Keys outside {@code documents/demo/} - every
 * contract sent or signed through the application - are never looked at, and an object already there is left alone,
 * so a restart does nothing more.</p>
 *
 * <p>The storage port chooses the key of what it stores ({@code FileStorage#store}); the rendering is stored under a key
 * of its own, copied to the seeded key, and the staged copy deleted. Runs after start-up on its own thread, under a
 * system scope ({@link SalesContracts#preview} reads the order in scope); a failure is logged and leaves the
 * application running - the preview still renders the same contract.</p>
 */
@Component
@Profile("local")
class DemoContractFiles {

    private static final Logger LOG = LoggerFactory.getLogger(DemoContractFiles.class);
    static final String DEMO_PREFIX = "documents/demo/";
    /** Whom the rendering is done as: a fixed, recognisable system user ("de300" - demo). */
    private static final CurrentUser LOADER = new CurrentUser(UUID.fromString("00000000-0000-0000-0000-0000000de300"),
            "demo-contract-files", Set.of(), Set.of(), DataScope.ALL, Set.of(), Set.of());

    /** A seeded contract and the keys it names. */
    record SeededContract(UUID orderId, String contractNumber, String sentKey, String signedKey) {
    }

    private final JdbcTemplate jdbc;
    private final SalesContracts contracts;
    private final FileStorage storage;

    DemoContractFiles(JdbcTemplate jdbc, SalesContracts contracts, FileStorage storage) {
        this.jdbc = jdbc;
        this.contracts = contracts;
        this.storage = storage;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            int stored = materialize();
            if (stored > 0) {
                LOG.info("Demo contract files: {} seeded contract file(s) rendered into storage", stored);
            }
        } catch (RuntimeException e) {
            LOG.warn("Demo contract files were not put into storage (is the bucket there?): {}", e.toString());
        }
    }

    /** @return how many objects were written */
    int materialize() {
        int stored = 0;
        for (SeededContract contract : seeded()) {
            List<String> missing = new ArrayList<>();
            for (String key : new String[] {contract.sentKey(), contract.signedKey()}) {
                if (key != null && key.startsWith(DEMO_PREFIX) && !storage.exists(key)) {
                    missing.add(key);
                }
            }
            if (missing.isEmpty()) {
                continue;
            }
            try {
                stored += store(contract, missing);
            } catch (RuntimeException e) {
                LOG.warn("Demo contract {}: its files were not stored: {}", contract.contractNumber(), e.toString());
            }
        }
        return stored;
    }

    private int store(SeededContract contract, List<String> keys) {
        byte[] pdf = render(contract.orderId());
        StoredFile staged = storage.store(FileCategory.DOCUMENT, contract.contractNumber() + ".pdf", "application/pdf",
                pdf.length, new ByteArrayInputStream(pdf));
        try {
            for (String key : keys) {
                storage.copy(staged.key(), key);
            }
        } finally {
            storage.delete(staged.key());
        }
        return keys.size();
    }

    private byte[] render(UUID orderId) {
        var previous = DataScopeContext.current().orElse(null);
        DataScopeContext.setSystemScope(LOADER);
        try {
            return contracts.preview(orderId);
        } finally {
            DataScopeContext.restore(previous);
        }
    }

    /** The live contracts naming a demo key; a contract cancelled and drawn up again is not rendered. */
    List<SeededContract> seeded() {
        return jdbc.query("""
                SELECT order_id, contract_number, sent_document_key, signed_file_key
                  FROM ordering.sales_contract
                 WHERE status <> 'CANCELLED'
                   AND (sent_document_key LIKE 'documents/demo/%' OR signed_file_key LIKE 'documents/demo/%')
                 ORDER BY contract_number""",
                (row, n) -> new SeededContract(row.getObject("order_id", UUID.class), row.getString("contract_number"),
                        row.getString("sent_document_key"), row.getString("signed_file_key")));
    }
}
