package com.stockflow.assistant.internal.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.Turn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The JSON of {@code assistant.message.content}: every turn reads back as written, the provider's bytes untouched. */
class TurnCodecTest {

    private final TurnCodec codec = new TurnCodec(new ObjectMapper());

    @Test
    @DisplayName("a question, a model turn and tool results all read back equal")
    void roundTrip() {
        List<Turn> turns = List.of(
                new Turn.Question("Lô L-1 đi đâu?", "today is 2026-10-25"),
                new Turn.ModelTurn("{\"role\":\"assistant\",\"content\":[]}", null,
                        List.of(new ToolCall("toolu_1", "traceLot", "{\"lotNumber\":\"L-1\"}"))),
                new Turn.ToolResults(List.of(new ToolResult("toolu_1", "{\"lots\":[]}", false),
                        new ToolResult("toolu_2", "NOT_PERMITTED: Missing permission: x:READ", true))),
                new Turn.ModelTurn("{\"role\":\"assistant\"}", "Không tìm thấy lô.", List.of()));

        for (Turn turn : turns) {
            assertThat(codec.read(codec.write(turn))).isEqualTo(turn);
        }
    }

    @Test
    @DisplayName("the provider's content is stored as a string, so its exact bytes survive jsonb's key reordering")
    void providerBytesSurvive() {
        // Keys out of jsonb's canonical order, odd spacing, an escaped unicode character: none of it may change.
        String provider = "{\"z\": 1,  \"a\":{\"signature\":\"EqQBCkY\\u00e9\",\"thinking\":\"\"}}";
        String written = codec.write(new Turn.ModelTurn(provider, "ok", List.of()));

        assertThat(((Turn.ModelTurn) codec.read(written)).providerContent()).isEqualTo(provider);
    }
}
