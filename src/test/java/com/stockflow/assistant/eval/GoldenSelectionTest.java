package com.stockflow.assistant.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A run picks its part of the golden set by id, by use case or by the smoke mark, and a typo is refused. */
class GoldenSelectionTest {

    private static final GoldenCase U1_01 = golden("U1-01", "U1", true);
    private static final GoldenCase U3_01 = golden("U3-01", "U3", false);
    private static final GoldenCase U3_07 = golden("U3-07", "U3", false);
    private static final GoldenCase S_02 = golden("S-02", "S", true);
    private static final List<GoldenCase> SET = List.of(U1_01, U3_01, U3_07, S_02);

    @Test
    @DisplayName("nothing asked for in particular runs the whole set")
    void everything() {
        GoldenSelection selection = GoldenSelection.parse("", " ", false);

        assertThat(selection.everything()).isTrue();
        assertThat(SET).allMatch(selection::includes);
    }

    @Test
    @DisplayName("ids match exactly or with * as a wildcard, whatever their case")
    void ids() {
        GoldenSelection selection = GoldenSelection.parse("u1-01, U3-*", "", false);

        assertThat(SET).filteredOn(selection::includes).containsExactly(U1_01, U3_01, U3_07);
    }

    @Test
    @DisplayName("use-case groups and the smoke mark each add their cases")
    void groupsAndSmoke() {
        assertThat(SET).filteredOn(GoldenSelection.parse("", "U3", false)::includes).containsExactly(U3_01, U3_07);
        assertThat(SET).filteredOn(GoldenSelection.parse("", "", true)::includes).containsExactly(U1_01, S_02);
        assertThat(SET).filteredOn(GoldenSelection.parse("U3-07", "s", true)::includes)
                .containsExactly(U1_01, U3_07, S_02);
    }

    @Test
    @DisplayName("an id, a group or a smoke run that matches no case is refused, not run as an empty green run")
    void typo() {
        assertThatCode(() -> GoldenSelection.parse("U1-0*", "U3", true).requireEveryPartMatches(SET))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> GoldenSelection.parse("U1-01,U9-*", "U3,U8", false).requireEveryPartMatches(SET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("U9-*").hasMessageContaining("U8").hasMessageNotContaining("U1-01");
        assertThatThrownBy(() -> GoldenSelection.parse("", "", true)
                .requireEveryPartMatches(List.of(U3_01))).hasMessageContaining("smoke");
    }

    private static GoldenCase golden(String id, String useCase, boolean smoke) {
        return new GoldenCase(id, useCase, "formal", new GoldenCase.Persona("INVENTORY_PLANNER", List.of()),
                "Câu hỏi " + id, List.of(), List.of(), false, List.of(), null, List.of(), null, false, null, List.of(),
                List.of(), List.of(), smoke);
    }
}
