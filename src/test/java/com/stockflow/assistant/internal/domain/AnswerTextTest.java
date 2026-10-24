package com.stockflow.assistant.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** An answer keeps its words and loses everything a renderer would fetch or follow. */
class AnswerTextTest {

    @Test
    @DisplayName("images, links, reference links, autolinks, bare URLs and tags are removed; words stay")
    void removesWhatWouldBeFetched() {
        assertThat(AnswerText.plain("Xem ![biểu đồ](https://x.example/a.png?rev=9) nhé")).isEqualTo("Xem biểu đồ nhé");
        assertThat(AnswerText.plain("Theo [BR-RMA-002](https://x.example/r)")).isEqualTo("Theo BR-RMA-002");
        assertThat(AnswerText.plain("Mở <https://x.example> hoặc www.x.example/a"))
                .isEqualTo("Mở [link removed] hoặc [link removed]");
        assertThat(AnswerText.plain("a\n[1]: https://x.example/leak\nb")).isEqualTo("a\n\nb");
        assertThat(AnswerText.plain("<img src=x onerror=alert(1)>Tồn <b>40</b>")).isEqualTo("Tồn 40");
        assertThat(AnswerText.plain("data:text/html;base64,AAAA")).isEqualTo("[link removed]");
    }

    @Test
    @DisplayName("ordinary answers pass unchanged, comparisons and tables included")
    void leavesOrdinaryText() {
        String table = "| SKU | Tồn |\n|---|---|\n| CUP-12OZ | 1.276 |\nCòn < 5 ngày; 3 > 2. Lô [A] đã hết.";
        assertThat(AnswerText.plain(table)).isEqualTo(table);
        assertThat(AnswerText.plain(null)).isNull();
    }
}
