package com.stockflow.product.internal.domain;

import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Decision 1: which edits of a template change what customers' files are drawn on. */
class PrintFactsTest {

    private static PrintConfig template(String name, String areaName, int minDpi, boolean active, String reference,
                                        double bleed) {
        return new PrintConfig(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP-12", "CUP12-BODY", name,
                minDpi, "CMYK", List.of("image/png"), 20, null, null, active,
                new CupModel(90, 60, 110, 3, 8, 6, 8, "paper-white"),
                List.of(new PrintArea("BODY", areaName, 0, 0, 280, 104, 3, bleed, new PrintAreaShape.Rect(), "body")),
                reference, reference, 7);
    }

    @Test
    void labelsActivationAndTheReferenceDieLineAreNotWhatFilesAreDrawnOn() {
        PrintFacts facts = PrintFacts.of(template("Ly", "Thân", 300, true, null, 2));

        assertThat(PrintFacts.of(template("Ly mới", "Thân ly", 300, false, "print-templates/k.pdf", 2)))
                .isEqualTo(facts);
        assertThat(PrintFacts.of(template("Ly", "Thân", 600, true, null, 2))).isNotEqualTo(facts);
        assertThat(PrintFacts.of(template("Ly", "Thân", 300, true, null, 3))).isNotEqualTo(facts);
    }

    @Test
    void aPrintVersionOnlyMovesUpFromZeroOrMore() {
        assertThat(PrintFacts.next(0)).isEqualTo(1);
        assertThatThrownBy(() -> PrintFacts.next(-1)).isInstanceOf(IllegalStateException.class);
    }
}
