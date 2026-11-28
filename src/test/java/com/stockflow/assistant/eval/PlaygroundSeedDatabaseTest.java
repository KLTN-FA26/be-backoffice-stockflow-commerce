package com.stockflow.assistant.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** PlaygroundSeed writes its shared-password accounts into the playground's database and no other. */
class PlaygroundSeedDatabaseTest {

    @Test
    @DisplayName("with DB_NAME unset the seed targets the playground's database, as its datasource URL does")
    void defaultTarget() {
        String database = PlaygroundSeed.targetDatabase(new Properties(), Map.of());

        assertThat(database).isEqualTo(PlaygroundSeed.PLAYGROUND_DATABASE);
        assertThat(PlaygroundSeed.allowed(database, null)).isTrue();
    }

    @Test
    @DisplayName("another database is refused, read the way Spring resolves DB_NAME: system property first")
    void otherDatabase() {
        Properties system = new Properties();
        assertThat(PlaygroundSeed.targetDatabase(system, Map.of("DB_NAME", "stockflow"))).isEqualTo("stockflow");
        assertThat(PlaygroundSeed.allowed("stockflow", null)).isFalse();
        assertThat(PlaygroundSeed.allowed("stockflow", " ")).isFalse();

        system.setProperty("DB_NAME", "stockflow_ai");
        assertThat(PlaygroundSeed.targetDatabase(system, Map.of("DB_NAME", "stockflow"))).isEqualTo("stockflow_ai");
    }

    @Test
    @DisplayName("a blank DB_NAME is refused: the URL would name no database and the driver would pick the user's")
    void blankName() {
        String database = PlaygroundSeed.targetDatabase(new Properties(), Map.of("DB_NAME", ""));

        assertThat(database).isEmpty();
        assertThat(PlaygroundSeed.allowed(database, null)).isFalse();
        assertThat(PlaygroundSeed.allowed(database, "")).isFalse();
    }

    @Test
    @DisplayName("-Dplayground.database allows the one database it names")
    void override() {
        assertThat(PlaygroundSeed.allowed("stockflow_ai_2", "stockflow_ai_2")).isTrue();
        assertThat(PlaygroundSeed.allowed("stockflow", "stockflow_ai_2")).isFalse();
        assertThat(PlaygroundSeed.allowed(PlaygroundSeed.PLAYGROUND_DATABASE, "stockflow_ai_2")).isTrue();
    }
}
