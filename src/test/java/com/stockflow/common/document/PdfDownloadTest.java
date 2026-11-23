package com.stockflow.common.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class PdfDownloadTest {

    @Test
    @DisplayName("a paper is answered as a PDF shown inline, never cached, under its Vietnamese name encoded for a "
            + "header - diacritics kept, what a file system refuses replaced")
    void aVietnameseNameSurvivesTheHeader() {
        ResponseEntity<byte[]> answer = PdfDownload.inline("Biên bản thỏa thuận điều chỉnh hóa đơn BB-01/2026.pdf",
                new byte[] {1});

        assertThat(answer.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(answer.getHeaders().getCacheControl()).isEqualTo("private, no-store");
        String disposition = answer.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).startsWith("inline;")
                .contains("filename*=UTF-8''Bi%C3%AAn%20b%E1%BA%A3n%20th%E1%BB%8Fa%20thu%E1%BA%ADn")
                .contains("BB-01-2026.pdf")
                .doesNotContain("BB-01/2026");
        assertThat(disposition.chars().allMatch(c -> c < 128)).as("a header carries ASCII only").isTrue();
    }

    @Test
    @DisplayName("the characters no file system takes become hyphens; letters and spaces stay")
    void savable() {
        assertThat(PdfDownload.savable(" Phiếu nhập kho PNK:01\\02*?\"<>|\t.pdf "))
                .isEqualTo("Phiếu nhập kho PNK-01-02-------.pdf");
    }
}
