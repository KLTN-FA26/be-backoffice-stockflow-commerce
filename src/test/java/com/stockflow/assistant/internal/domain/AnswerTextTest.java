package com.stockflow.assistant.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An answer keeps its words and loses everything a renderer would fetch or follow. The expected strings were checked
 * with the GFM parser react-markdown uses: none of them holds an image, a link, a reference, a definition or raw HTML.
 */
class AnswerTextTest {

    @Test
    @DisplayName("images, links, reference links, autolinks, bare URLs and tags are removed; words stay")
    void removesWhatWouldBeFetched() {
        assertThat(AnswerText.plain("Xem ![biểu đồ](https://x.example/a.png?rev=9) nhé")).isEqualTo("Xem biểu đồ nhé");
        assertThat(AnswerText.plain("Theo [BR-RMA-002](https://x.example/r)")).isEqualTo("Theo BR-RMA-002");
        assertThat(AnswerText.plain("Mở <https://x.example> hoặc www.x.example/a"))
                .isEqualTo("Mở \\[link removed\\] hoặc \\[link removed\\]");
        assertThat(AnswerText.plain("a\n[1]: https://x.example/leak\nb")).isEqualTo("a\n\nb");
        assertThat(AnswerText.plain("<img src=x onerror=alert(1)>Tồn <b>40</b>")).isEqualTo("Tồn 40");
        assertThat(AnswerText.plain("data:text/html;base64,AAAA")).isEqualTo("\\[link removed\\]");
    }

    @Test
    @DisplayName("ordinary answers keep their words, comparisons and tables included; brackets and backslashes are "
            + "escaped and render as they were")
    void leavesOrdinaryText() {
        String table = "| SKU | Tồn |\n|---|---|\n| CUP-12OZ | 1.276 |\nCòn < 5 ngày; 3 > 2. Lô [A] đã hết.";
        assertThat(AnswerText.plain(table)).isEqualTo(table.replace("[A]", "\\[A\\]"));
        assertThat(AnswerText.plain("Q&A về R&D, AT&T")).isEqualTo("Q&A về R&D, AT&T");
        assertThat(AnswerText.plain("Giá 5\\* ở C:\\tmp")).isEqualTo("Giá 5\\\\* ở C:\\\\tmp");
        assertThat(AnswerText.plain(null)).isNull();
    }

    @Test
    @DisplayName("an image the patterns cannot follow still never forms: nested or escaped brackets, an entity for "
            + "the colon, a reference defined inside a quote or a list item, a link with nested brackets")
    void imagesThePatternsMiss() {
        assertThat(AnswerText.plain("A ![a [b] c](//evil.example/p.png?rev=1250000000) B"))
                .isEqualTo("A !\\[a \\[b\\] c\\](//evil.example/p.png?rev=1250000000) B");
        assertThat(AnswerText.plain("A ![a\\]b](//evil.example/p.png?rev=1250000000)"))
                .isEqualTo("A !\\[a\\\\\\]b\\](//evil.example/p.png?rev=1250000000)");
        assertThat(AnswerText.plain("A ![a [b] c](https&#58;//evil.example/p.png?rev=1250000000) B"))
                .isEqualTo("A !\\[a \\[b\\] c\\](https\\&#58;//evil.example/p.png?rev=1250000000) B");
        assertThat(AnswerText.plain("A ![s][r]\n\n> [r]: //evil.example/p.png?rev=1250000000"))
                .isEqualTo("A !\\[s\\]\\[r\\]\n\n> \\[r\\]: //evil.example/p.png?rev=1250000000");
        assertThat(AnswerText.plain("A ![s][r]\n\n- [r]: https&#x3A;//evil.example/p.png?rev=1250000000"))
                .isEqualTo("A !\\[s\\]\\[r\\]\n\n- \\[r\\]: https\\&#x3A;//evil.example/p.png?rev=1250000000");
        assertThat(AnswerText.plain("[click [here] now](//evil.example/x)"))
                .isEqualTo("\\[click \\[here\\] now\\](//evil.example/x)");
    }

    @Test
    @DisplayName("a backslash the model wrote cannot unescape a bracket: every backslash is doubled before the "
            + "brackets are escaped")
    void backslashes() {
        // Escaping only the brackets would turn "\[" into "\\[": a literal backslash, then a live bracket.
        assertThat(AnswerText.plain("\\[a [b] c](//evil.example/x)"))
                .isEqualTo("\\\\\\[a \\[b\\] c\\](//evil.example/x)");
        assertThat(AnswerText.plain("\\\\[a [b] c](//evil.example/x)"))
                .isEqualTo("\\\\\\\\\\[a \\[b\\] c\\](//evil.example/x)");
        assertThat(AnswerText.plain("\\[a\\](//evil.example/x)")).doesNotContain("](");
    }

    @Test
    @DisplayName("an address a renderer would decode into a link stays text: escaped slashes or dots, character "
            + "references, a tag inside it")
    void addressesARendererWouldDecode() {
        assertThat(AnswerText.plain("Xem https:\\/\\/evil.example/p.png"))
                .isEqualTo("Xem https:\\\\/\\\\/evil.example/p.png");
        assertThat(AnswerText.plain("Xem https&colon;//evil.example/p.png"))
                .isEqualTo("Xem https\\&colon;//evil.example/p.png");
        assertThat(AnswerText.plain("Xem www&#46;evil.example")).isEqualTo("Xem www\\&#46;evil.example");
        assertThat(AnswerText.plain("Xem https:<b>//evil.example/p.png?d=1")).isEqualTo("Xem \\[link removed\\]");
        assertThat(AnswerText.plain("Xem h<i>ttps://evil.example/p.png</i>")).isEqualTo("Xem \\[link removed\\]");
    }

    @Test
    @DisplayName("a tag rebuilt by removing the tag inside it stays text")
    void rebuiltTags() {
        assertThat(AnswerText.plain("A <<b>img src=//evil.example/p.png> B"))
                .isEqualTo("A \\<img src=//evil.example/p.png> B");
        assertThat(AnswerText.plain("<</b>script>x")).isEqualTo("\\<script>x");
    }

    @Test
    @DisplayName("nothing that comes out can open a link, an image, a reference, a tag or a character reference")
    void noLiveMarkupLeft() {
        String[] attacks = {
                "A ![a [b] c](//evil.example/p.png?rev=1250000000) B",
                "A ![a\\]b](//evil.example/p.png?rev=1250000000)",
                "A ![s][r]\n\n> [r]: //evil.example/p.png?rev=1250000000",
                "x\n\n\\[r\\]: //evil.example/p.png\n\n![s]\\[r\\]",
                "https://a.example: //evil.example/p.png\n!https://b.example and ![x](https://c.example)",
                "\\\\[a](//evil.example/x) \\\\\\\\[b [c]](//evil.example/y)",
                "&#92;[a [b]](//evil.example/x) and `\\`[a [b]](//evil.example/x)",
                "Lô [A] đã hết; xem [^1]\n\n[^1]: //evil.example/n",
                "A <<b>img src=//evil.example/p.png> B <<i>/b>",
        };
        for (String attack : attacks) {
            String plain = AnswerText.plain(attack);
            assertThat(liveMarkup(plain)).as("live markup left in %s", plain).isEqualTo(-1);
        }
    }

    /**
     * The index of the first character a renderer could still read as markup: a bracket, a {@code <} that could open
     * a tag or an {@code &} that could open a character reference, with no backslash of its own before it, or a
     * backslash that is not doubled; -1 if there is none.
     */
    private static int liveMarkup(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                char next = i + 1 < text.length() ? text.charAt(i + 1) : ' ';
                if (next == '\\' || next == '[' || next == ']' || next == '<' || next == '&') {
                    i++;
                    continue;
                }
                return i;
            }
            char next = i + 1 < text.length() ? text.charAt(i + 1) : ' ';
            boolean tag = c == '<' && (next >= 'a' && next <= 'z' || next >= 'A' && next <= 'Z'
                    || "/!?".indexOf(next) >= 0);
            boolean reference = c == '&' && text.substring(i + 1)
                    .matches("(?s)(#[0-9]{1,7};|#[xX][0-9a-fA-F]{1,6};|[A-Za-z][A-Za-z0-9]{0,31};).*");
            if (c == '[' || c == ']' || tag || reference) {
                return i;
            }
        }
        return -1;
    }
}
