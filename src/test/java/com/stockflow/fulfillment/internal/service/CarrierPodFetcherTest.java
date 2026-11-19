package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileUpload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The proof of delivery a carrier's webhook points at (09B G7; kltn-docs 09 §4.3 step 2, §4.5 step 7, BR-28). */
class CarrierPodFetcherTest {

    private final ShipmentPodFiles pods = mock(ShipmentPodFiles.class);
    private final CarrierPodFetcher fetcher = new CarrierPodFetcher(pods,
            new com.stockflow.common.http.RestClientFactory(new com.stockflow.common.http.HttpClientProperties(
                    java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1))));

    @Test
    @DisplayName("fetched once and kept with every shipment the event delivered, named after the address")
    void keptWithEachShipment() {
        UUID event = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        byte[] pdf = {'%', 'P', 'D', 'F'};
        int[] calls = {0};
        fetcher.fetchWith(uri -> {
            calls[0]++;
            return new CarrierPodFetcher.Fetched(pdf, "application/pdf", CarrierPodFetcher.fileName(uri));
        });
        when(pods.keepFromCarrier(any(), any(), any())).thenReturn(true);

        fetcher.on(new CarrierPodReported(event, "https://files.example.com/pod/7/signed.pdf", List.of(first, second)));

        assertThat(calls[0]).isEqualTo(1);
        ArgumentCaptor<FileUpload> file = ArgumentCaptor.forClass(FileUpload.class);
        verify(pods).keepFromCarrier(eq(first), eq(event), file.capture());
        verify(pods).keepFromCarrier(eq(second), eq(event), any());
        assertThat(file.getValue().originalName()).isEqualTo("signed.pdf");
        assertThat(file.getValue().contentType()).isEqualTo("application/pdf");
        assertThat(file.getValue().sizeBytes()).isEqualTo(4);
    }

    @Test
    @DisplayName("a POD is optional (BR-28): one that cannot be fetched, or is refused for a shipment, is left - the "
            + "other shipments still get theirs")
    void leftWhenItCannotBeHad() {
        fetcher.fetchWith(uri -> {
            throw new IOException("the carrier's address answered 404");
        });
        fetcher.on(new CarrierPodReported(UUID.randomUUID(), "https://files.example.com/x.pdf",
                List.of(UUID.randomUUID())));
        verify(pods, never()).keepFromCarrier(any(), any(), any());

        UUID refused = UUID.randomUUID();
        fetcher.fetchWith(uri -> new CarrierPodFetcher.Fetched(new byte[] {1}, "text/plain", "x.txt"));
        when(pods.keepFromCarrier(eq(refused), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "A proof of delivery is a PDF or an image"));
        fetcher.on(new CarrierPodReported(UUID.randomUUID(), "https://files.example.com/x.txt",
                List.of(refused, UUID.randomUUID())));
        verify(pods, times(2)).keepFromCarrier(any(), any(), any());
    }

    @Test
    @DisplayName("only a public https address is fetched: no plain http, no loopback, private or link-local address")
    void onlyPublicAddresses() {
        for (String inside : new String[] {"http://8.8.8.8/x.pdf", "https://127.0.0.1/x.pdf", "https://10.1.2.3/x.pdf",
                "https://192.168.1.10/x.pdf", "https://169.254.169.254/latest", "https://[::1]/x.pdf",
                "https://[fd00::1]/x.pdf", "https://0.0.0.0/x.pdf"}) {
            assertThatThrownBy(() -> CarrierPodFetcher.requirePublic(URI.create(inside))).as(inside)
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    @DisplayName("the file is named after the address's last segment, or 'pod'")
    void fileNames() {
        assertThat(CarrierPodFetcher.fileName(URI.create("https://h.example.com/a/b/bien-ban.jpg"))).isEqualTo("bien-ban.jpg");
        assertThat(CarrierPodFetcher.fileName(URI.create("https://h.example.com/"))).isEqualTo("pod");
        assertThat(CarrierPodFetcher.fileName(URI.create("https://h.example.com"))).isEqualTo("pod");
    }
}
