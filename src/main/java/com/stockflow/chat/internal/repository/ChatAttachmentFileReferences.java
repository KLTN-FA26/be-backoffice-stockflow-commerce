package com.stockflow.chat.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.StoredFileReferences;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The files the chat's messages keep, for the orphaned-file sweep: an upload whose message was never committed (the
 * transaction rolled back after the file was stored) is found and deleted, one that a message names never is.
 */
@Component
class ChatAttachmentFileReferences implements StoredFileReferences {

    private final NamedParameterJdbcTemplate jdbc;

    ChatAttachmentFileReferences(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<FileCategory> categories() {
        return Set.of(FileCategory.CHAT_ATTACHMENT);
    }

    @Override
    public Set<String> referenced(FileCategory category, Collection<String> keys) {
        if (category != FileCategory.CHAT_ATTACHMENT || keys.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("SELECT file_key FROM chat.message_attachment WHERE file_key IN (:keys)",
                Map.of("keys", keys), String.class));
    }
}
