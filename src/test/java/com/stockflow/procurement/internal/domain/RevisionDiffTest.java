package com.stockflow.procurement.internal.domain;

import com.stockflow.procurement.internal.domain.PurchaseOrderRepository.RevisionLine;
import com.stockflow.procurement.internal.domain.RevisionDiff.LineChange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RevisionDiffTest {

    private static final UUID LINE_1 = UUID.randomUUID();
    private static final UUID LINE_2 = UUID.randomUUID();

    private static RevisionLine line(UUID id, int no, int quantity, String price) {
        return new RevisionLine(id, no, "SKU-" + no, quantity, new BigDecimal(price), new BigDecimal("10"), null);
    }

    @Test
    void aChangedQuantityIsOneChange() {
        List<LineChange> changes = RevisionDiff.between(List.of(line(LINE_1, 1, 10, "1250")),
                List.of(line(LINE_1, 1, 8, "1250")));

        assertThat(changes).containsExactly(new LineChange(1, "SKU-1", "quantity", "10", "8"));
    }

    @Test
    void aLineOnlyInTheBaseIsRemoved() {
        List<LineChange> changes = RevisionDiff.between(
                List.of(line(LINE_1, 1, 10, "1250"), line(LINE_2, 2, 5, "300")), List.of(line(LINE_1, 1, 10, "1250")));

        assertThat(changes).containsExactly(new LineChange(2, "SKU-2", "removed", "5", null));
    }

    @Test
    void aLineOnlyInTheTargetIsAdded() {
        List<LineChange> changes = RevisionDiff.between(List.of(line(LINE_1, 1, 10, "1250")),
                List.of(line(LINE_1, 1, 10, "1250"), line(LINE_2, 2, 5, "300")));

        assertThat(changes).containsExactly(new LineChange(2, "SKU-2", "added", null, "5"));
    }

    @Test
    void identicalLinesHaveNoChange() {
        assertThat(RevisionDiff.between(List.of(line(LINE_1, 1, 10, "1250")), List.of(line(LINE_1, 1, 10, "1250"))))
                .isEmpty();
    }

    @Test
    void pricesAreComparedByValueNotByScale() {
        assertThat(RevisionDiff.between(List.of(line(LINE_1, 1, 10, "1250.0")),
                List.of(line(LINE_1, 1, 10, "1250.00")))).isEmpty();
    }

    @Test
    void changesAreOrderedByLineThenField() {
        List<LineChange> changes = RevisionDiff.between(
                List.of(line(LINE_1, 1, 10, "1250"), line(LINE_2, 2, 5, "300")),
                List.of(line(LINE_1, 1, 12, "1300"), line(LINE_2, 2, 4, "300")));

        assertThat(changes).extracting(LineChange::lineNo, LineChange::field)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "quantity"),
                        org.assertj.core.groups.Tuple.tuple(1, "unitPrice"),
                        org.assertj.core.groups.Tuple.tuple(2, "quantity"));
    }
}
