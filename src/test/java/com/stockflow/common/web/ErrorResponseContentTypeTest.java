package com.stockflow.common.web;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An error answering a request that asked for a binary is still the JSON envelope (SCRUM-97). Without
 * a preset content type Spring negotiates the error body against {@code Accept: application/pdf},
 * finds no converter, and the client gets a 406 or 500 instead of the status the handler chose.
 */
class ErrorResponseContentTypeTest {

    private final StaticMessageSource source = new StaticMessageSource();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PdfController())
            .setControllerAdvice(new GlobalExceptionHandler(new Messages(source), source))
            .build();

    @Test
    void aBusinessErrorUnderAcceptPdfIsAJsonEnvelope() throws Exception {
        mvc.perform(post("/pdf").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("LOCATION_NOT_FOUND"));
    }

    @Test
    void aFrameworkErrorUnderAcceptPdfIsAJsonEnvelope() throws Exception {
        mvc.perform(post("/pdf-with-body").accept(MediaType.APPLICATION_PDF)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void askingAPdfEndpointForJsonOnlyIsA406InJson() throws Exception {
        mvc.perform(post("/pdf").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
    }

    @Test
    void aJsonEndpointErrorIsUnchanged() throws Exception {
        mvc.perform(post("/json").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("LOCATION_NOT_FOUND"));
    }

    record Body(@NotNull String name) {
    }

    @RestController
    static class PdfController {

        @PostMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
        public byte[] pdf() {
            throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No storage location has this code");
        }

        @PostMapping("/json")
        public String json() {
            throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No storage location has this code");
        }

        @PostMapping(value = "/pdf-with-body",produces = MediaType.APPLICATION_PDF_VALUE)
        public byte[] pdfWithBody(@Valid @RequestBody Body body) {
            return new byte[0];
        }
    }
}
