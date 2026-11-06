package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadInspectionTest {
    @Test void streamsClamdProtocolAndAcceptsOnlyCleanResult() throws Exception {
        run("stream: OK\0", false);
    }
    @Test void rejectsMalwareAndScannerErrors() throws Exception {
        run("stream: TestSignature FOUND\0", true);
        run("INSTREAM size limit exceeded. ERROR\0", true);
    }
    @Test void anUnreachableScannerFailsClosedWhenEnabled() throws Exception {
        int closedPort;
        try (var probe = new ServerSocket(0)) { closedPort = probe.getLocalPort(); }
        var scanner = new UploadInspection("localhost", closedPort, true);
        assertThatThrownBy(() -> scanner.requireClean(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(StorageException.class);
    }
    @Test void aDisabledScannerNeverOpensAConnection() throws Exception {
        int closedPort;
        try (var probe = new ServerSocket(0)) { closedPort = probe.getLocalPort(); }
        var scanner = new UploadInspection("localhost", closedPort, false);
        scanner.requireClean(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void pingExpectsPong() throws Exception {
        try (var server = new ServerSocket(0)) {
            var peer = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    assertThat(new String(socket.getInputStream().readNBytes(6), StandardCharsets.US_ASCII))
                            .isEqualTo("zPING\0");
                    socket.getOutputStream().write("PONG\0".getBytes(StandardCharsets.US_ASCII));
                } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
            });
            new UploadInspection("localhost", server.getLocalPort(), true).ping();
            peer.get(5, TimeUnit.SECONDS);
        }
    }
    @Test void pingFailsWhenTheScannerIsUnreachable() throws Exception {
        int closedPort;
        try (var probe = new ServerSocket(0)) { closedPort = probe.getLocalPort(); }
        assertThatThrownBy(() -> new UploadInspection("localhost", closedPort, true).ping())
                .isInstanceOf(StorageException.class);
    }
    /** A second upload while the only slot is taken waits for it rather than failing with 429 at once. */
    @Test void aBusyScannerWaitsForASlotInsteadOfRefusingAtOnce() throws Exception {
        try (var server = new ServerSocket(0)) {
            var release = new java.util.concurrent.CountDownLatch(1);
            CompletableFuture.runAsync(() -> serve(server, release));
            var scanner = new UploadInspection("localhost", server.getLocalPort(), true, 1, java.time.Duration.ofSeconds(10));
            var first = CompletableFuture.runAsync(() -> scanner.requireClean(sample()));
            Thread.sleep(200);   // the first scan now holds the only slot, its reply held back
            var second = CompletableFuture.runAsync(() -> scanner.requireClean(sample()));
            Thread.sleep(200);
            assertThat(second).as("waiting for the slot, not refused").isNotDone();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
    }
    @Test void withNoWaitConfiguredABusyScannerRefusesWithRateLimited() throws Exception {
        try (var server = new ServerSocket(0)) {
            var release = new java.util.concurrent.CountDownLatch(1);
            CompletableFuture.runAsync(() -> serve(server, release));
            var scanner = new UploadInspection("localhost", server.getLocalPort(), true, 1, java.time.Duration.ZERO);
            var first = CompletableFuture.runAsync(() -> scanner.requireClean(sample()));
            Thread.sleep(200);
            assertThatThrownBy(() -> scanner.requireClean(sample())).isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).errorCode())
                    .isEqualTo(com.stockflow.common.error.ErrorCode.RATE_LIMITED);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
    }
    @Test void aNonPositiveSlotCountIsAConfigurationError() {
        assertThatThrownBy(() -> new UploadInspection("localhost", 1, true, 0, java.time.Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private static ByteArrayInputStream sample() {
        return new ByteArrayInputStream("sample".getBytes(StandardCharsets.UTF_8));
    }
    /** A clamd that answers every scan OK; the first reply waits for {@code release}. */
    private static void serve(ServerSocket server, java.util.concurrent.CountDownLatch release) {
        boolean firstConnection = true;
        while (!server.isClosed()) {
            try {
                var socket = server.accept();
                boolean hold = firstConnection;
                firstConnection = false;
                CompletableFuture.runAsync(() -> {
                    try (socket; var input = new DataInputStream(socket.getInputStream())) {
                        input.readNBytes(10);
                        for (int size; (size = input.readInt()) != 0;) { input.readNBytes(size); }
                        if (hold) { release.await(5, TimeUnit.SECONDS); }
                        socket.getOutputStream().write("stream: OK\0".getBytes(StandardCharsets.UTF_8));
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                });
            } catch (java.io.IOException closed) { return; }
        }
    }
    private void run(String response, boolean failure) throws Exception {
        try (var server = new ServerSocket(0)) {
            var peer = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept(); var input = new DataInputStream(socket.getInputStream())) {
                    assertThat(new String(input.readNBytes(10), StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                    int size = input.readInt();
                    assertThat(new String(input.readNBytes(size), StandardCharsets.UTF_8)).isEqualTo("sample");
                    assertThat(input.readInt()).isZero();
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
            });
            var scanner = new UploadInspection("localhost", server.getLocalPort(), true);
            if (failure) {
                assertThatThrownBy(() -> scanner.requireClean(new ByteArrayInputStream("sample".getBytes(StandardCharsets.UTF_8))))
                        .isInstanceOf(BusinessException.class);
            } else { scanner.requireClean(new ByteArrayInputStream("sample".getBytes(StandardCharsets.UTF_8))); }
            peer.get(5, TimeUnit.SECONDS);
        }
    }
}
