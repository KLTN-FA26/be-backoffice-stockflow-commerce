package com.stockflow.order.internal.service;

import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Heading;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Run;

import java.util.List;
import java.util.ResourceBundle;

/**
 * Numbers the clauses and points of a paper that follows a contract - its appendix, its liquidation - as they are
 * written: "ĐIỀU 2", "2.1.", "2.2.". Counted, never typed into the bundle, so a clause left out for a fact the paper
 * lacks (an appendix that changes no goods) leaves no gap in the numbers, and a reader checking "Điều 3" against the
 * signed copy finds the third clause.
 */
final class DocumentClauses {

    private final List<Block> blocks;
    private final ResourceBundle t;
    private final String prefix;
    private int clause;
    private int point;

    /** @param prefix the paper's keys: "appendix.pdf" finds "appendix.pdf.clause" and "appendix.pdf.clause.goods" */
    DocumentClauses(List<Block> blocks, ResourceBundle t, String prefix) {
        this.blocks = blocks;
        this.t = t;
        this.prefix = prefix;
    }

    void title(String key) {
        clause++;
        point = 0;
        blocks.add(new Heading(t.getString(prefix + ".clause").formatted(clause,
                t.getString(prefix + ".clause." + key))));
    }

    void point(String key, Object... args) {
        point++;
        blocks.add(Paragraph.numbered(clause + "." + point + ".", Run.of(t.getString(prefix + "." + key)
                .formatted(args))).after(3));
    }

    /** The clause being written: for a text that refers to it ("các nghĩa vụ nêu tại Điều 3"). */
    int number() {
        return clause;
    }
}
