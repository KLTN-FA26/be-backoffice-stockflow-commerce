package com.stockflow.common.label;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The fonts embedded in location labels: Noto Sans Regular and Bold, SIL OFL 1.1 (licence in
 * {@code fonts/OFL.txt}).
 *
 * <p>Embedded because the 14 standard PDF fonts are WinAnsi only and cannot draw Vietnamese -
 * a warehouse name like {@code Kho Hồ Chí Minh} would fail to render. Read once here, at startup,
 * so a missing font file stops the application instead of the first print request.</p>
 *
 * <p>Each font is rebuilt without its {@code GSUB} table. PDFBox applies glyph substitution on every
 * {@code showText} for a font that has one, recompiling its patterns each call; at three lines per label
 * that made 2,000 labels take 3.5-9 seconds. A label needs no ligatures, so the table is dropped: the
 * rebuilt font keeps every glyph the Unicode cmap reaches, and its outlines and metrics unchanged.</p>
 */
@Component
public class LabelFonts {

    /**
     * Everything a TrueType font needs to draw and to be embedded again, and nothing else. Named
     * explicitly: without a list, {@code TTFSubsetter} copies every table, {@code GSUB} included.
     */
    private static final List<String> KEPT_TABLES = List.of("head", "hhea", "maxp", "OS/2", "name", "cmap",
            "post", "cvt ", "fpgm", "prep", "gasp", "glyf", "loca", "hmtx");

    private final byte[] regular;
    private final byte[] bold;
    /** Code points both fonts have a glyph for; anything else would make PDFBox throw mid-render. */
    private final BitSet drawable = new BitSet();

    public LabelFonts() {
        BitSet regularCoverage = new BitSet();
        this.regular = withoutGsub("fonts/NotoSans-Regular.ttf", regularCoverage);
        this.bold = withoutGsub("fonts/NotoSans-Bold.ttf", drawable);
        drawable.and(regularCoverage);
    }

    public byte[] regular() {
        return regular;
    }

    public byte[] bold() {
        return bold;
    }

    public boolean canDraw(int codePoint) {
        return codePoint >= 0 && drawable.get(codePoint);
    }

    private static byte[] withoutGsub(String path, BitSet coverage) {
        try (InputStream in = new ClassPathResource(path).getInputStream();
             TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(in.readAllBytes()))) {
            CmapLookup cmap = font.getUnicodeCmapLookup();
            Set<Integer> codePoints = new HashSet<>();
            for (int codePoint = 0; codePoint <= Character.MAX_VALUE; codePoint++) {
                if (!Character.isSurrogate((char) codePoint) && cmap.getGlyphId(codePoint) > 0) {
                    codePoints.add(codePoint);
                    coverage.set(codePoint);
                }
            }
            var subsetter = new TTFSubsetter(font, KEPT_TABLES);
            subsetter.addAll(codePoints);
            var out = new ByteArrayOutputStream();
            subsetter.writeToStream(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Label font " + path + " is missing or unreadable", e);
        }
    }
}
