package com.stockflow.common.document;

import com.stockflow.common.label.LabelFonts;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.BitSet;

/**
 * The fonts of a business document - a quotation, a contract, a record two companies sign: Tinos in its four styles,
 * SIL OFL 1.1 (licence in {@code fonts/OFL-Tinos.txt}).
 *
 * <p>A serif, because Vietnamese official papers are set in Times New Roman (Nghị định 30/2020/NĐ-CP, Phụ lục I) and a
 * contract in a sans-serif reads as a screen print, not a document. Times New Roman itself cannot be shipped in a jar;
 * Tinos has the same metrics, so a page breaks where a reader used to Times expects, and it has every Vietnamese
 * letter. Labels and forms worked from at a counter keep Noto Sans ({@link LabelFonts}): those are read at arm's
 * length, these are read and signed.</p>
 *
 * <p>Four styles, where labels have two: "Căn cứ" lines, a place and date, and the note under a signatory's title are
 * italic by the same decree. Each is rebuilt without {@code GSUB} as the label fonts are, and read once at startup so
 * a missing file stops the application instead of the first print.</p>
 */
@Component
public class DocumentFonts {

    private final byte[] regular;
    private final byte[] bold;
    private final byte[] italic;
    private final byte[] boldItalic;
    /** Code points all four styles draw: a paragraph mixes them, and what one cannot draw none may be asked to. */
    private final BitSet drawable = new BitSet();

    public DocumentFonts() {
        this.regular = LabelFonts.withoutGsub("fonts/Tinos-Regular.ttf", drawable);
        this.bold = narrowed("fonts/Tinos-Bold.ttf");
        this.italic = narrowed("fonts/Tinos-Italic.ttf");
        this.boldItalic = narrowed("fonts/Tinos-BoldItalic.ttf");
    }

    private byte[] narrowed(String path) {
        BitSet coverage = new BitSet();
        byte[] font = LabelFonts.withoutGsub(path, coverage);
        drawable.and(coverage);
        return font;
    }

    byte[] bytes(Style style) {
        return switch (style) {
            case REGULAR -> regular;
            case BOLD -> bold;
            case ITALIC -> italic;
            case BOLD_ITALIC -> boldItalic;
        };
    }

    public boolean canDraw(int codePoint) {
        return codePoint >= 0 && drawable.get(codePoint);
    }

    /**
     * {@code text} as it can be drawn: composed (NFC) so a letter typed with combining marks is one glyph, control
     * characters as spaces, and anything the fonts have no glyph for as {@code ?} - PDFBox throws on such a character,
     * and a company name with an emoji in it must not make its contract unprintable.
     */
    public String drawable(String text) {
        var out = new StringBuilder(text.length());
        Normalizer.normalize(text, Normalizer.Form.NFC).codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)) {
                out.append(' ');
            } else if (canDraw(codePoint)) {
                out.appendCodePoint(codePoint);
            } else {
                out.append('?');
            }
        });
        return out.toString();
    }
}
