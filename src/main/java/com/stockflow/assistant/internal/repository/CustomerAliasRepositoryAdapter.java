package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.domain.CustomerAliasRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** {@link CustomerAliasRepository} in SQL: a mapping read whole and added to, never changed. */
@Repository
class CustomerAliasRepositoryAdapter implements CustomerAliasRepository {

    private final NamedParameterJdbcTemplate jdbc;

    CustomerAliasRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Integer, UUID> aliasesOf(UUID conversationId) {
        Map<Integer, UUID> aliases = new LinkedHashMap<>();
        jdbc.query("""
                SELECT alias_number, customer_id FROM assistant.customer_alias
                WHERE conversation_id = :conversation ORDER BY alias_number""",
                new MapSqlParameterSource("conversation", conversationId),
                row -> {
                    aliases.put(row.getInt("alias_number"), row.getObject("customer_id", UUID.class));
                });
        return aliases;
    }

    /**
     * One question at a time per conversation holds its claim, so numbers do not race. A question that outlived its
     * lease while a second one was asked could still have given the same number to another customer: an alias that is
     * already there for another customer, or a customer already there under another number, fails the store, and the
     * question with it, rather than leave an answer whose "KH#n" reads back as someone else.
     */
    @Override
    public void save(UUID conversationId, Map<Integer, UUID> added) {
        for (Map.Entry<Integer, UUID> alias : added.entrySet()) {
            MapSqlParameterSource row = new MapSqlParameterSource("conversation", conversationId)
                    .addValue("number", alias.getKey()).addValue("customer", alias.getValue());
            int inserted = jdbc.update("""
                    INSERT INTO assistant.customer_alias (conversation_id, alias_number, customer_id)
                    VALUES (:conversation, :number, :customer)
                    ON CONFLICT DO NOTHING""", row);
            if (inserted == 0) {
                Integer same = jdbc.query("""
                        SELECT count(*) FROM assistant.customer_alias
                        WHERE conversation_id = :conversation AND alias_number = :number AND customer_id = :customer""",
                        row, (rs, i) -> rs.getInt(1)).stream().findFirst().orElse(0);
                if (same == 0) {
                    throw new IllegalStateException("Alias KH#" + alias.getKey() + " of conversation " + conversationId
                            + " is taken by another question's customer");
                }
            }
        }
    }
}
