package com.stockflow.common.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class UploadInspectionHealthIndicatorTest {

    @Test
    void aDisabledScannerIsReportedAsUnknownNotDown() {
        var health = new UploadInspectionHealthIndicator(new UploadInspection("localhost", 1, false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("enabled", false);
    }

    @Test
    void anUnreachableScannerIsDownWithoutItsAddressInTheDetails() throws Exception {
        int closedPort;
        try (var probe = new ServerSocket(0)) { closedPort = probe.getLocalPort(); }

        var health = new UploadInspectionHealthIndicator(new UploadInspection("localhost", closedPort, true)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().toString()).doesNotContain(String.valueOf(closedPort));
    }

    @Test
    void aScannerAnsweringPongIsUp() throws Exception {
        try (var server = new ServerSocket(0)) {
            var peer = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    socket.getInputStream().readNBytes(6);
                    socket.getOutputStream().write("PONG\0".getBytes(StandardCharsets.US_ASCII));
                } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
            });

            var health = new UploadInspectionHealthIndicator(
                    new UploadInspection("localhost", server.getLocalPort(), true)).health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            peer.get(5, TimeUnit.SECONDS);
        }
    }
}
