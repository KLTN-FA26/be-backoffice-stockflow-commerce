package com.stockflow.assistant.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a draft hides of a customer's names and its contacts' (review finding 5), and how they come back. */
class DraftNamesTest {

    @Test
    @DisplayName("the registered name, its distinctive part however cased or accented, and a contact's full name are "
            + "hidden; the aliases come back as the names")
    void hidesAndReveals() {
        DraftNames names = DraftNames.of("Công ty TNHH Bao Bì Minh Phát", List.of(), List.of("Nguyễn Văn An"));

        assertThat(names.hide("Công ty TNHH Bao Bì Minh Phát, bên BAO BI MINH PHAT, anh nguyễn văn an gọi"))
                .isEqualTo("KH#1, bên KH#1, anh LH#1 gọi");
        assertThat(names.revealer().apply("Chào anh LH#1 bên KH#1 (Công ty TNHH Bao Bì Minh Phát)"))
                .isEqualTo("Chào anh Nguyễn Văn An bên Công ty TNHH Bao Bì Minh Phát");
    }

    @Test
    @DisplayName("a one-word short name is hidden only where it is written with a capital: elsewhere it is a word")
    void oneWordNeedsACapital() {
        DraftNames names = DraftNames.of("Công ty TNHH Xanh", List.of(), List.of());

        assertThat(names.hide("Bên Xanh muốn cốc màu xanh")).isEqualTo("Bên KH#1 muốn cốc màu xanh");
    }

    @Test
    @DisplayName("text typed in the shape of an alias is not read back as one")
    void typedAliasesAreNeutralised() {
        DraftNames names = DraftNames.of("Công ty ABC Việt", List.of(), List.of("Trần Thị Bích"));

        String hidden = names.hide("KH#1 và LH#1 là gì");
        assertThat(hidden).isEqualTo("KH# 1 và LH# 1 là gì");
        assertThat(names.revealer().apply(hidden)).isEqualTo(hidden);
    }
}
