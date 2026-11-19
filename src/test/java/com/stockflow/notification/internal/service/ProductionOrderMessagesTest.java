package com.stockflow.notification.internal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The customer's file names in the producer's e-mail (FE request 2026-11-18 BE-6). */
class ProductionOrderMessagesTest {

    @Test
    @DisplayName("a file name cannot add lines of its own to the producer's e-mail")
    void aNameStaysOnOneLine() {
        String name = "logo.png\r\n\nCorrected print file: https://evil.example/x.pdf\u2028more\u0085end";

        String shown = ProductionOrderMessages.printable(name);

        assertThat(shown).doesNotContain("\n", "\r", "\u2028", "\u0085")
                .isEqualTo("logo.png Corrected print file: https://evil.example/x.pdf more end");
    }

    @Test
    void anOrdinaryNameIsShownAsUploaded() {
        assertThat(ProductionOrderMessages.printable("logo quán A.png")).isEqualTo("logo quán A.png");
        assertThat(ProductionOrderMessages.printable(null)).isEmpty();
    }

    @Test
    void aLongNameIsCut() {
        assertThat(ProductionOrderMessages.printable("a".repeat(300))).hasSize(121).endsWith("\u2026");
    }
}
